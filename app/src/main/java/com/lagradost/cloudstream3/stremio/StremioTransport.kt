package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

object StremioTransport {
    private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
    private const val DEFAULT_TIMEOUT_SECONDS = 20L

    private val defaultHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Stremio/4.4.168 (CloudStream)",
    )

    /**
     * Equivalent to JavaScript `encodeURIComponent`, matching `Stremio/stremio-addon-client`.
     */
    fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
    }

    /**
     * Normalizes user-entered or deep-linked Stremio addon URLs into an HTTP(S) `manifest.json` URL.
     */
    fun normalizeManifestUrl(rawInput: String): String {
        var url = rawInput.trim()
        require(url.isNotEmpty()) { "Addon URL cannot be empty" }

        url = when {
            url.startsWith("stremio+http://", ignoreCase = true) ->
                "http://" + url.substring("stremio+http://".length)
            url.startsWith("stremio://", ignoreCase = true) ->
                "https://" + url.substring("stremio://".length)
            !url.contains("://") ->
                if (url.startsWith("127.0.0.1") || url.startsWith("localhost")) {
                    "http://$url"
                } else {
                    "https://$url"
                }
            else -> url
        }

        val parsed = URI(url)
        val scheme = parsed.scheme?.lowercase() ?: "https"
        val rawAuthority = parsed.rawAuthority?.replaceFirst("localhost", "127.0.0.1")
            ?: throw IllegalArgumentException("Invalid addon host")
        var rawPath = parsed.rawPath ?: ""

        rawPath = rawPath.trimEnd('/')
        rawPath = when {
            rawPath.endsWith("/manifest.json", ignoreCase = true) -> rawPath
            rawPath.endsWith("/configure", ignoreCase = true) ->
                rawPath.dropLast("/configure".length) + "/manifest.json"
            else -> "$rawPath/manifest.json"
        }
        if (!rawPath.startsWith("/")) {
            rawPath = "/$rawPath"
        }

        val querySuffix = parsed.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
        return "$scheme://$rawAuthority$rawPath$querySuffix"
    }

    fun extractTransportBaseUrl(manifestUrl: String): String {
        val withoutQuery = manifestUrl.substringBefore("?").substringBefore("#")
        return if (withoutQuery.endsWith("/manifest.json", ignoreCase = true)) {
            withoutQuery.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            withoutQuery.trimEnd('/')
        }
    }

    /** Removes the serialized declarative-config path segment, if the addon has one. */
    fun unconfiguredTransportBaseUrl(addon: InstalledStremioAddon): String {
        val base = addon.transportBaseUrl.trimEnd('/')
        val lastSlash = base.lastIndexOf('/')
        val protoEnd = base.indexOf("://")
        val hostSlash = if (protoEnd >= 0) base.indexOf('/', protoEnd + 3) else -1
        if (hostSlash < 0 || lastSlash < hostSlash) return base

        val lastSegment = base.substring(lastSlash + 1)
        val decoded = runCatching { java.net.URLDecoder.decode(lastSegment, "UTF-8") }.getOrNull()
        val isConfigSegment = decoded != null && decoded.startsWith("{") && decoded.endsWith("}")
        return if (isConfigSegment || addon.configValues.isNotEmpty()) {
            base.substring(0, lastSlash)
        } else {
            base
        }
    }

    /** Builds the add-on's configure web URL from its (de-serialized) transport base URL. */
    fun buildConfigureWebUrl(addon: InstalledStremioAddon): String {
        return "${unconfiguredTransportBaseUrl(addon)}/configure"
    }

    private fun extractQuerySuffix(manifestUrl: String): String {
        val q = manifestUrl.substringAfter("?", "")
            .substringBefore("#")
            .trim()
        return if (q.isNotEmpty()) "?$q" else ""
    }

    /**
     * Builds a configured `manifest.json` URL from declarative `manifest.config` values
     * following `Stremio/stremio-addon-sdk` (`/{encodedJsonConfig}/manifest.json`).
     */
    fun buildConfiguredManifestUrl(
        addon: InstalledStremioAddon,
        newConfig: Map<String, String>
    ): String {
        val cleanMap = newConfig.filterValues { it.isNotEmpty() }
        val querySuffix = extractQuerySuffix(addon.manifestUrl)

        // Strip an existing serialized config segment before appending the new one.
        val rootBase = unconfiguredTransportBaseUrl(addon)

        if (cleanMap.isEmpty()) {
            return "$rootBase/manifest.json$querySuffix"
        }
        val jsonConfig = JSONObject()
        cleanMap.forEach { (k, v) -> jsonConfig.put(k, v) }
        val encoded = encodeComponent(jsonConfig.toString())
        return "$rootBase/$encoded/manifest.json$querySuffix"
    }

    /**
     * Matches `Stremio/stremio-addon-client/lib/stringifyRequest.js`:
     * `/{resource}/{type}/{id}.json` or `/{resource}/{type}/{id}/{extraQuery}.json`
     */
    fun buildResourceUrl(
        addon: InstalledStremioAddon,
        resource: String,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val base = addon.transportBaseUrl.trimEnd('/')
        val querySuffix = extractQuerySuffix(addon.manifestUrl)
        val encodedResource = encodeComponent(resource)
        val encodedType = encodeComponent(type)
        val encodedId = encodeComponent(id)

        val filteredExtra = extra.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        val extraSegment = if (filteredExtra.isEmpty()) {
            ""
        } else {
            "/" + filteredExtra.entries.joinToString("&") { (k, v) ->
                "${encodeComponent(k)}=${encodeComponent(v)}"
            }
        }
        return "$base/$encodedResource/$encodedType/$encodedId$extraSegment.json$querySuffix"
    }

    suspend fun fetchManifest(rawInputUrl: String): Pair<String, StremioManifest> {
        val normalizedUrl = normalizeManifestUrl(rawInputUrl)
        val response = app.get(
            normalizedUrl,
            headers = defaultHeaders,
            timeout = DEFAULT_TIMEOUT_SECONDS,
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code} when fetching manifest")
        }
        val manifest = StremioJsonParser.parseManifest(response.text)
        return normalizedUrl to manifest
    }

    suspend fun fetchCatalog(
        addon: InstalledStremioAddon,
        type: String,
        catalogId: String,
        extra: Map<String, String> = emptyMap(),
    ): List<StremioMetaItem> {
        val url = buildResourceUrl(addon, "catalog", type, catalogId, extra)
        val response = app.get(
            url,
            headers = defaultHeaders,
            timeout = DEFAULT_TIMEOUT_SECONDS,
        )
        if (!response.isSuccessful) return emptyList()
        return StremioJsonParser.parseCatalogResponse(response.text, type)
    }

    suspend fun fetchMeta(
        addon: InstalledStremioAddon,
        type: String,
        id: String,
    ): StremioMetaItem? {
        val url = buildResourceUrl(addon, "meta", type, id)
        val response = app.get(
            url,
            headers = defaultHeaders,
            timeout = DEFAULT_TIMEOUT_SECONDS,
        )
        if (!response.isSuccessful) return null
        return StremioJsonParser.parseMetaResponse(response.text, type)
    }

    /**
     * Official Stremio Cinemeta fallback for `tt...` IMDb IDs when no installed Metadata addon
     * provides full episode/movie metadata for an IMDb item.
     */
    suspend fun fetchCinemetaFallback(
        type: String,
        imdbId: String,
    ): StremioMetaItem? {
        val mappedType = if (type.equals("anime", ignoreCase = true)) "series" else type
        val url = "$CINEMETA_BASE_URL/meta/${encodeComponent(mappedType)}/${encodeComponent(imdbId)}.json"
        val response = app.get(
            url,
            headers = defaultHeaders,
            timeout = DEFAULT_TIMEOUT_SECONDS,
        )
        if (!response.isSuccessful) return null
        return StremioJsonParser.parseMetaResponse(response.text, type)
    }

    suspend fun fetchStreams(
        addon: InstalledStremioAddon,
        type: String,
        videoId: String,
    ): List<StremioStreamItem> {
        val url = buildResourceUrl(addon, "stream", type, videoId)
        val response = app.get(
            url,
            headers = defaultHeaders,
            timeout = DEFAULT_TIMEOUT_SECONDS,
        )
        if (!response.isSuccessful) return emptyList()
        return StremioJsonParser.parseStreamsResponse(response.text)
    }

    suspend fun fetchSubtitles(
        addon: InstalledStremioAddon,
        type: String,
        videoId: String,
        extra: Map<String, String> = emptyMap(),
    ): List<StremioSubtitleItem> {
        val url = buildResourceUrl(addon, "subtitles", type, videoId, extra)
        val response = app.get(
            url,
            headers = defaultHeaders,
            timeout = DEFAULT_TIMEOUT_SECONDS,
        )
        if (!response.isSuccessful) return emptyList()
        return StremioJsonParser.parseSubtitlesResponse(response.text)
    }
}
