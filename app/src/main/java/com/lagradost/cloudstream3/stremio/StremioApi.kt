package com.lagradost.cloudstream3.stremio

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Standalone Stremio API / addon-protocol client.
 *
 * This deliberately does not depend on the Rust core or the native streaming server from
 * stremio-native. It speaks the same public HTTP API and addon manifest/catalog/meta/stream
 * protocol, then hands the results to CloudStream's own provider models.
 *
 * The addon transport mirrors stremio-core 90c38f181d290fc705049e4c8bd30df00f6f3e66:
 * a descriptor transportUrl ends in /manifest.json (or the legacy /stremio/v1), and resource
 * requests replace that suffix with /{resource}/{type}/{id}[/{extra}].json. It also supports
 * the legacy JSON-RPC /q.json?b=... transport so the official Opensubtitles v1 addon works.
 */
object StremioApi {
    private const val TAG = "StremioApi"
    private const val API_BASE = "https://api.strem.io"

    /** stremio-core constants.rs: NON_ALPHANUMERIC minus -_.!~*'(). */
    private const val URI_COMPONENT_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** base64 of {"params":[],"method":"meta","id":1,"jsonrpc":"2.0"}. */
    private const val LEGACY_MANIFEST_PARAM =
        "eyJwYXJhbXMiOltdLCJtZXRob2QiOiJtZXRhIiwiaWQiOjEsImpzb25ycGMiOiIyLjAifQ=="

    private suspend fun post(method: String, body: Map<String, Any>): JSONObject {
        val response = app.post(
            "$API_BASE/api/$method",
            headers = mapOf("Content-Type" to "application/json"),
            json = body,
        )
        val text = response.text
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Stremio HTTP ${response.code}: ${text.redactStremioSecrets().take(200)}"
            )
        }
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw IllegalStateException(
                "Stremio returned non-JSON: ${text.redactStremioSecrets().take(200)}"
            )
        }
        val error = errorText(json)
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        return json
    }

    /**
     * Stremio uses an untagged { result } / { error } envelope. Some successful responses also
     * carry a zero/false/empty error field, so only a real message or non-zero code is a failure.
     */
    private fun errorText(json: JSONObject): String? {
        if (!json.has("error") || json.isNull("error")) return null
        return when (val error = json.get("error")) {
            is Boolean -> if (error) "Stremio API returned an error" else null
            is Number -> if (error.toLong() != 0L) "Stremio API error $error" else null
            is String -> error.takeIf {
                it.isNotBlank() && !it.equals("null", true) &&
                    !it.equals("false", true) && it != "0"
            }
            is JSONObject -> {
                val message = error.optString("message").takeIf { it.isNotBlank() }
                val code = error.optLong("code", 0L)
                when {
                    message != null -> message
                    code != 0L -> "Stremio API error $code"
                    else -> null
                }
            }
            is JSONArray -> if (error.length() == 0) null else error.toString()
            else -> null
        }
    }

    private fun authResult(json: JSONObject, fallbackAuthKey: String? = null): StremioUser {
        val rawResult = json.opt("result").takeUnless { it === JSONObject.NULL }
        val result = when (rawResult) {
            is JSONObject -> rawResult
            is String -> JSONObject().put("authKey", rawResult)
            is JSONArray -> rawResult.optJSONObject(0) ?: JSONObject()
            else -> json
        }
        return StremioUser.fromAuthResult(result, fallbackAuthKey)
            ?: throw IllegalStateException(
                "Stremio response has no auth key: ${
                    json.toString().redactStremioSecrets().take(240)
                }"
            )
    }

    suspend fun login(email: String, password: String): StremioUser {
        val json = post(
            "login",
            mapOf(
                "email" to email.trim(),
                "password" to password,
                "facebook" to false,
            )
        )
        return authResult(json)
    }

    suspend fun register(email: String, password: String): StremioUser {
        val json = post(
            "register",
            mapOf(
                "email" to email.trim(),
                "password" to password,
                "gdpr_consent" to mapOf(
                    "tos" to true,
                    "privacy" to true,
                    "marketing" to false,
                    "from" to "cloudstream",
                ),
            )
        )
        return authResult(json)
    }

    suspend fun loginWithAuthKey(authKey: String): StremioUser {
        val key = authKey.trim()
        val json = post(
            "getUser",
            mapOf("authKey" to key)
        )
        return authResult(json, key)
    }

    suspend fun addonCollectionGet(authKey: String): List<JSONObject>? = runCatching {
        val json = post(
            "addonCollectionGet",
            mapOf(
                "authKey" to authKey,
                "update" to true,
                "addFromURL" to emptyList<String>(),
            )
        )
        val result = json.opt("result").takeUnless { it === JSONObject.NULL }
        val array: JSONArray? = when {
            result is JSONObject && result.optJSONArray("addons") != null ->
                result.getJSONArray("addons")
            result is JSONArray -> result
            json.optJSONArray("addons") != null -> json.getJSONArray("addons")
            else -> null
        }
        array ?: return@runCatching null
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrNull()

    suspend fun addonCollectionSet(
        authKey: String,
        addons: List<Map<String, Any>>,
    ): Boolean = runCatching {
        post(
            "addonCollectionSet",
            mapOf(
                "authKey" to authKey,
                "addons" to addons,
            )
        )
        true
    }.getOrDefault(false)

    // region Addon transport

    private data class TransportParts(
        /** Full base URL ending in '/', used to append resource paths. */
        val base: String,
        /** Raw query string, appended after the resource path (config addons). */
        val rawQuery: String?,
        val isLegacy: Boolean,
        /** Canonical descriptor value, e.g. https://host/manifest.json?config=... */
        val canonicalTransportUrl: String,
        /** URL used to fetch the manifest. */
        val manifestUrl: String,
    ) {
        val querySuffix: String get() = rawQuery?.let { "?$it" }.orEmpty()
    }

    private fun transportParts(url: String): TransportParts {
        var normalized = url.trim()
        when {
            normalized.startsWith("stremio://", true) ->
                normalized = "https://" + normalized.substringAfter("://")
            normalized.startsWith("stremio:", true) ->
                normalized = "https://" + normalized.substringAfter(":")
        }
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (uri == null || uri.scheme == null || uri.authority == null) {
            val pathOnly = normalized.substringBefore('?')
            val fallbackBase = pathOnly
                .removeSuffix("manifest.json")
                .removeSuffix("/") + "/"
            val fallbackQuery = normalized.substringAfter('?', "").takeIf { it.isNotBlank() }
            val fallbackSuffix = fallbackQuery?.let { "?$it" }.orEmpty()
            val fallbackCanonical = fallbackBase + "manifest.json" + fallbackSuffix
            return TransportParts(
                base = fallbackBase,
                rawQuery = fallbackQuery,
                isLegacy = false,
                canonicalTransportUrl = fallbackCanonical,
                manifestUrl = fallbackCanonical,
            )
        }

        val rawQuery = uri.rawQuery
        val suffix = rawQuery?.let { "?$it" }.orEmpty()
        val trimmedPath = (uri.rawPath ?: "/").trimEnd('/').ifEmpty { "/" }
        val lowerPath = trimmedPath.lowercase(Locale.US)
        val isLegacy = lowerPath.endsWith("/stremio/v1")

        if (isLegacy) {
            val legacyPath = trimmedPath + "/"
            val base = "${uri.scheme}://${uri.authority}$legacyPath"
            val canonical = "${uri.scheme}://${uri.authority}$trimmedPath$suffix"
            return TransportParts(
                base = base,
                rawQuery = rawQuery,
                isLegacy = true,
                canonicalTransportUrl = canonical,
                manifestUrl = base + "q.json?b=" + LEGACY_MANIFEST_PARAM,
            )
        }

        val pathWithoutManifest = if (lowerPath.endsWith("/manifest.json")) {
            trimmedPath.substring(0, trimmedPath.length - "/manifest.json".length)
        } else {
            trimmedPath
        }
        val resourcePath = pathWithoutManifest + "/"
        val base = "${uri.scheme}://${uri.authority}$resourcePath"
        val canonical = base + "manifest.json" + suffix
        return TransportParts(
            base = base,
            rawQuery = rawQuery,
            isLegacy = false,
            canonicalTransportUrl = canonical,
            manifestUrl = canonical,
        )
    }

    /** Percent-encode exactly like stremio-core's query_params_encode.rs. */
    fun encodeUriComponent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (URI_COMPONENT_SAFE.indexOf(char) >= 0) {
                out.append(char)
            } else {
                val unsigned = byte.toInt() and 0xFF
                out.append('%')
                out.append(Character.forDigit(unsigned ushr 4, 16).uppercaseChar())
                out.append(Character.forDigit(unsigned and 0x0F, 16).uppercaseChar())
            }
        }
        return out.toString()
    }

    fun resourceBase(url: String): String = transportParts(url).base

    fun isLegacyTransportUrl(url: String): Boolean = transportParts(url).isLegacy

    /** Exposed for tests; builds the exact stremio-core resource URL. */
    fun resourceUrl(
        addon: StremioAddon,
        resource: String,
        type: String,
        id: String,
        extras: Map<String, String>,
    ): String {
        val parts = transportParts(addon.transportUrl)
        val encodedResource = encodeUriComponent(resource)
        val encodedType = encodeUriComponent(type)
        val encodedId = encodeUriComponent(id)
        val extraSuffix = if (extras.isEmpty()) {
            ".json"
        } else {
            "/" + extras.entries.joinToString("&") { (key, value) ->
                "${encodeUriComponent(key)}=${encodeUriComponent(value)}"
            } + ".json"
        }
        return parts.base +
            "$encodedResource/$encodedType/$encodedId$extraSuffix" +
            parts.querySuffix
    }

    private suspend fun getJson(url: String): JSONObject {
        val response = app.get(url)
        val text = response.text
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Stremio addon HTTP ${response.code}: ${text.redactStremioSecrets().take(200)}"
            )
        }
        return runCatching { JSONObject(text) }.getOrElse {
            throw IllegalStateException(
                "Stremio addon returned non-JSON: ${text.redactStremioSecrets().take(200)}"
            )
        }
    }

    private fun JSONArray.toStringListCompat(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }

    private fun legacyError(json: JSONObject): String? {
        if (!json.has("error") || json.isNull("error")) return null
        return when (val error = json.get("error")) {
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
                ?: error.optLong("code", 0L).takeIf { it != 0L }?.let { "JSON-RPC error $it" }
            is String -> error.takeIf { it.isNotBlank() }
            else -> "Legacy addon returned an error"
        }
    }

    private fun mapLegacyIdPrefix(property: String): String? = when (property) {
        "imdb_id" -> "tt"
        "yt_id" -> "UC"
        else -> "$property:"
    }

    private fun legacyManifestToStandard(manifest: JSONObject): JSONObject {
        val types = manifest.optJSONArray("types")?.toStringListCompat() ?: emptyList()
        val methods = manifest.optJSONArray("methods")?.toStringListCompat() ?: emptyList()
        val resources = JSONArray()
        if ("meta.get" in methods) resources.put("meta")
        if ("stream.find" in methods) resources.put("stream")
        if ("subtitles.find" in methods) resources.put("subtitles")

        val catalogs = JSONArray()
        if ("meta.find" in methods) {
            val sorts = manifest.optJSONArray("sorts")
            if (sorts != null && sorts.length() > 0) {
                for (index in 0 until sorts.length()) {
                    val sort = sorts.optJSONObject(index) ?: continue
                    val sortId = sort.optString("prop").takeIf { it.isNotBlank() } ?: "top"
                    val sortName = sort.optString("name").takeIf { it.isNotBlank() }
                    val sortTypes = sort.optJSONArray("types")?.toStringListCompat()
                        ?: types
                    sortTypes.forEach { type ->
                        val catalog = JSONObject()
                            .put("id", sortId)
                            .put("type", type)
                        sortName?.let { catalog.put("name", it) }
                        catalogs.put(catalog)
                    }
                }
            } else {
                types.forEach { type ->
                    catalogs.put(
                        JSONObject()
                            .put("id", "top")
                            .put("type", type)
                    )
                }
            }
        }

        val idPrefixes = JSONArray()
        when (val idProperty = manifest.opt("idProperty")) {
            is String -> mapLegacyIdPrefix(idProperty)?.let { idPrefixes.put(it) }
            is JSONArray -> idProperty.toStringListCompat().forEach { property ->
                mapLegacyIdPrefix(property)?.let { idPrefixes.put(it) }
            }
        }

        val standard = JSONObject()
            .put("id", manifest.optString("id"))
            .put("name", manifest.optString("name"))
            .put("version", manifest.optString("version"))
            .put("description", manifest.optString("description"))
            .put("logo", manifest.optString("logo"))
            .put("background", manifest.optString("background"))
            .put("contactEmail", manifest.optString("contactEmail"))
            .put("types", JSONArray(types))
            .put("resources", resources)
            .put("catalogs", catalogs)
            .put("idPrefixes", idPrefixes)
            .put("behaviorHints", JSONObject())
        return standard
    }

    /** Mirrors stremio-core legacy/mod.rs::query_from_id. */
    private fun legacyQueryFromId(id: String): JSONObject? {
        val parts = id.split(':')
        if (parts.isEmpty() || parts[0].isBlank()) return null
        return when {
            id.startsWith("tt", true) -> {
                val query = JSONObject().put("imdb_id", parts[0])
                if (parts.size == 3) {
                    query.put("season", parts[1].toIntOrNull() ?: 1)
                    query.put("episode", parts[2].toIntOrNull() ?: 1)
                }
                query
            }
            id.startsWith("UC", true) -> {
                val query = JSONObject().put("yt_id", parts[0])
                if (parts.size == 2) query.put("video_id", parts[1])
                query
            }
            parts.size == 3 -> JSONObject()
                .put(parts[0], parts[1])
                .put("video_id", parts[2])
            parts.size == 2 -> JSONObject().put(parts[0], parts[1])
            else -> null
        }
    }

    private fun legacyRequestJson(
        resource: String,
        type: String,
        id: String,
        extras: Map<String, String>,
    ): JSONObject {
        val params = JSONObject()
        when (resource) {
            "catalog" -> {
                val query = JSONObject().put("type", type)
                extras["genre"]?.let { query.put("genre", it) }
                val sort = if (id == "top") {
                    JSONObject.NULL
                } else {
                    JSONObject().put(id, -1).put("popularity", -1)
                }
                params.put("query", query)
                params.put("limit", 100)
                params.put("sort", sort)
                params.put("skip", extras["skip"]?.toIntOrNull() ?: 0)
            }
            "meta" -> params.put("query", legacyQueryFromId(id) ?: JSONObject.NULL)
            "stream" -> {
                val query = legacyQueryFromId(id)
                    ?: throw IllegalStateException("Legacy addon cannot stream id $id")
                query.put("type", type)
                params.put("query", query)
            }
            "subtitles" -> {
                val query = JSONObject().put("itemHash", id.replace(':', ' '))
                extras["videoHash"]?.let { query.put("videoHash", it) }
                extras["videoSize"]?.toLongOrNull()?.let { query.put("videoSize", it) }
                extras["filename"]?.let { query.put("filename", it) }
                params.put("query", query)
            }
            else -> throw IllegalStateException("Legacy addon does not support $resource")
        }
        val method = when (resource) {
            "catalog" -> "meta.find"
            "meta" -> "meta.get"
            "stream" -> "stream.find"
            "subtitles" -> "subtitles.find"
            else -> resource
        }
        return buildJsonRpc(method, params)
    }

    private fun buildJsonRpc(method: String, params: JSONObject): JSONObject {
        val callParams = JSONArray()
            .put(JSONObject.NULL)
            .put(params)
        return JSONObject()
            .put("params", callParams)
            .put("method", method)
            .put("id", 1)
            .put("jsonrpc", "2.0")
    }

    private fun legacyRequestUrl(parts: TransportParts, request: JSONObject): String {
        val bytes = request.toString().toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return parts.base + "q.json?b=" + encoded
    }

    private fun legacyResponseToResource(resource: String, json: JSONObject): JSONObject {
        legacyError(json)?.let { throw IllegalStateException("Legacy addon: $it") }
        val result = json.opt("result").takeUnless { it === JSONObject.NULL }
        return when (resource) {
            "catalog" -> JSONObject().put("metas", result as? JSONArray ?: JSONArray())
            "meta" -> JSONObject().put("meta", result as? JSONObject ?: JSONObject())
            "stream" -> JSONObject().put("streams", result as? JSONArray ?: JSONArray())
            "subtitles" -> {
                val subtitles = when (result) {
                    is JSONArray -> result
                    is JSONObject -> result.optJSONArray("all") ?: JSONArray()
                    else -> JSONArray()
                }
                JSONObject().put("subtitles", subtitles)
            }
            else -> JSONObject().put(resource, result ?: JSONObject.NULL)
        }
    }

    private suspend fun fetchResource(
        addon: StremioAddon,
        resource: String,
        type: String,
        id: String,
        extras: Map<String, String>,
    ): JSONObject {
        val parts = transportParts(addon.transportUrl)
        return if (parts.isLegacy) {
            val request = legacyRequestJson(resource, type, id, extras)
            val json = getJson(legacyRequestUrl(parts, request))
            legacyResponseToResource(resource, json)
        } else {
            getJson(resourceUrl(addon, resource, type, id, extras))
        }
    }

    private fun logAddonError(addon: StremioAddon, resource: String, error: Throwable) {
        val message = (error.message ?: error.toString())
            .redactStremioSecrets()
            .take(240)
        Log.e(TAG, "${addon.manifest.name} $resource failed: $message")
    }

    private inline fun <T> addonCall(
        addon: StremioAddon,
        resource: String,
        fallback: T,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logAddonError(addon, resource, error)
        fallback
    }

    suspend fun fetchManifest(manifestUrl: String): StremioAddon? = try {
        val parts = transportParts(manifestUrl)
        val response = getJson(parts.manifestUrl)
        val manifestJson = if (parts.isLegacy) {
            legacyError(response)?.let { throw IllegalStateException("Legacy addon: $it") }
            val legacyManifest = response.optJSONObject("result")?.optJSONObject("manifest")
                ?: throw IllegalStateException("Legacy addon manifest is missing")
            legacyManifestToStandard(legacyManifest)
        } else {
            response
        }
        val descriptor = JSONObject()
            .put("transportUrl", parts.canonicalTransportUrl)
            .put("manifest", manifestJson)
            .put(
                "flags",
                JSONObject()
                    .put("official", false)
                    .put("protected", false)
            )
        StremioAddon.fromDescriptor(descriptor)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Log.e(
            TAG,
            "Manifest ${manifestUrl.redactStremioSecrets().take(160)} failed: ${
                (error.message ?: error.toString()).redactStremioSecrets().take(240)
            }"
        )
        null
    }

    suspend fun fetchCatalog(
        addon: StremioAddon,
        catalog: StremioCatalog,
        query: String? = null,
        page: Int = 1,
    ): List<StremioMetaPreview> = addonCall(addon, "catalog/${catalog.id}", emptyList()) {
        val extras = LinkedHashMap<String, String>()
        if (!query.isNullOrBlank() && catalog.supportsSearch) {
            extras["search"] = query.trim()
        }
        catalog.defaultRequiredExtra().forEach { (key, value) ->
            if (key !in extras) extras[key] = value
        }
        val skip = ((page - 1).coerceAtLeast(0)) * 20
        if (skip > 0 && catalog.supportsSkip) extras["skip"] = skip.toString()
        if (!catalog.isExtraSupported(extras.keys)) return@addonCall emptyList()

        val json = fetchResource(addon, "catalog", catalog.type, catalog.id, extras)
        val array = json.optJSONArray("metas")
            ?: json.optJSONArray("metasDetailed")
            ?: return@addonCall emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { StremioMetaPreview.from(it, catalog.type) }
        }
    }

    suspend fun fetchMeta(
        addon: StremioAddon,
        type: String,
        id: String,
    ): StremioMeta? = addonCall(addon, "meta/$id", null) {
        val json = fetchResource(addon, "meta", type, id, emptyMap())
        val metaValue = json.opt("meta").takeUnless { it === JSONObject.NULL }
            ?: json.opt("metas").takeUnless { it === JSONObject.NULL }
        val metaJson = when (metaValue) {
            is JSONObject -> metaValue
            is JSONArray -> if (metaValue.length() > 0) metaValue.optJSONObject(0) else null
            else -> null
        } ?: return@addonCall null
        StremioMeta.from(metaJson, type)
    }

    suspend fun fetchStreams(
        addon: StremioAddon,
        type: String,
        videoId: String,
    ): List<StremioStream> = addonCall(addon, "stream/$videoId", emptyList()) {
        val json = fetchResource(addon, "stream", type, videoId, emptyMap())
        val array = json.optJSONArray("streams") ?: return@addonCall emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { StremioStream.from(it) }
        }
    }

    fun resolveUrl(addon: StremioAddon, url: String?): String? {
        if (url.isNullOrBlank()) return null
        val base = resourceBase(addon.transportUrl)
        return runCatching {
            URI(base).resolve(url).toString()
        }.getOrElse {
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) url
            else base + url.trimStart('/')
        }
    }

    /** Kept for callers that only need a stable base; resource requests use transportParts(). */
    fun normalizeTransportUrl(url: String): String = resourceBase(url)

    // endregion
}
