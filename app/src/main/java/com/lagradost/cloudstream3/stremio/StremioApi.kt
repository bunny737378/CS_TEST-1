package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * Standalone Stremio API / addon-protocol client.
 *
 * This deliberately does not depend on the Rust core or the native streaming server from
 * stremio-native. It speaks the same public HTTP API and addon manifest/catalog/meta/stream
 * protocol, then hands the results to CloudStream's own provider models.
 */
object StremioApi {
    private const val API_BASE = "https://api.strem.io"

    private suspend fun post(method: String, body: Map<String, Any>): JSONObject {
        val text = app.post("$API_BASE/api/$method", json = body).text
        val json = JSONObject(text)
        val error = errorText(json)
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        return json
    }

    private fun errorText(json: JSONObject): String? {
        if (!json.has("error") || json.isNull("error")) return null
        return when (val error = json.get("error")) {
            is Boolean -> if (error) "Stremio API returned an error" else null
            is String -> error.takeIf { it.isNotBlank() && it != "null" }
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
                ?: error.toString().takeIf { it.isNotBlank() }
            else -> error.toString().takeIf { it.isNotBlank() && it != "null" }
        }
    }

    suspend fun login(email: String, password: String): StremioUser? = runCatching {
        val json = post(
            "login",
            mapOf(
                "type" to "Login",
                "email" to email.trim(),
                "password" to password,
            )
        )
        val result = json.optJSONObject("result") ?: return@runCatching null
        StremioUser.fromAuthResult(result)
    }.getOrNull()

    suspend fun register(email: String, password: String): StremioUser? = runCatching {
        val json = post(
            "register",
            mapOf(
                "type" to "Register",
                "email" to email.trim(),
                "password" to password,
                "gdpr_consent" to mapOf(
                    "terms" to true,
                    "privacy" to true,
                    "marketing" to false,
                ),
            )
        )
        val result = json.optJSONObject("result") ?: return@runCatching null
        StremioUser.fromAuthResult(result)
    }.getOrNull()

    suspend fun loginWithAuthKey(authKey: String): StremioUser? = runCatching {
        val json = post(
            "getUser",
            mapOf(
                "type" to "GetUser",
                "authKey" to authKey.trim(),
            )
        )
        val result = json.optJSONObject("result") ?: return@runCatching null
        StremioUser.fromUserResult(result, authKey.trim())
    }.getOrNull()

    suspend fun addonCollectionGet(authKey: String): List<JSONObject>? = runCatching {
        val json = post(
            "addonCollectionGet",
            mapOf(
                "type" to "AddonCollectionGet",
                "authKey" to authKey,
                "update" to true,
                "addFromURL" to emptyList<String>(),
            )
        )
        val result = json.opt("result")
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
                "type" to "AddonCollectionSet",
                "authKey" to authKey,
                "addons" to addons,
            )
        )
        true
    }.getOrDefault(false)

    suspend fun fetchManifest(manifestUrl: String): StremioAddon? = runCatching {
        val base = normalizeTransportUrl(manifestUrl)
        val manifestJson = JSONObject(app.get("${base}manifest.json").text)
        val descriptor = JSONObject()
            .put("transportUrl", base)
            .put("manifest", manifestJson)
            .put(
                "flags",
                JSONObject()
                    .put("official", false)
                    .put("protected", false)
            )
        StremioAddon.fromDescriptor(descriptor)
    }.getOrNull()

    suspend fun fetchCatalog(
        addon: StremioAddon,
        catalog: StremioCatalog,
        query: String? = null,
        page: Int = 1,
    ): List<StremioMetaPreview> = runCatching {
        val extras = LinkedHashMap<String, String>()
        if (!query.isNullOrBlank() && catalog.supportsSearch) extras["search"] = query.trim()
        val skip = ((page - 1).coerceAtLeast(0)) * 20
        if (skip > 0 && catalog.supportsSkip) extras["skip"] = skip.toString()

        val text = app.get(resourceUrl(addon, "catalog", catalog.type, catalog.id, extras)).text
        val json = JSONObject(text)
        val array = json.optJSONArray("metas")
            ?: json.optJSONArray("metasDetailed")
            ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { StremioMetaPreview.from(it, catalog.type) }
        }
    }.getOrDefault(emptyList())

    suspend fun fetchMeta(
        addon: StremioAddon,
        type: String,
        id: String,
    ): StremioMeta? = runCatching {
        val text = app.get(resourceUrl(addon, "meta", type, id, emptyMap())).text
        val json = JSONObject(text)
        val metaValue = json.opt("meta") ?: json.opt("metas")
        val metaJson = when (metaValue) {
            is JSONObject -> metaValue
            is JSONArray -> if (metaValue.length() > 0) metaValue.optJSONObject(0) else null
            else -> json
        } ?: return@runCatching null
        StremioMeta.from(metaJson, type)
    }.getOrNull()

    suspend fun fetchStreams(
        addon: StremioAddon,
        type: String,
        videoId: String,
    ): List<StremioStream> = runCatching {
        val text = app.get(resourceUrl(addon, "stream", type, videoId, emptyMap())).text
        val json = JSONObject(text)
        val array = json.optJSONArray("streams") ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { StremioStream.from(it) }
        }
    }.getOrDefault(emptyList())

    fun resolveUrl(addon: StremioAddon, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            URI(addon.transportUrl).resolve(url).toString()
        }.getOrElse {
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) url
            else addon.transportUrl.trimEnd('/') + "/" + url.trimStart('/')
        }
    }

    fun normalizeTransportUrl(url: String): String {
        var normalized = url.trim()
        when {
            normalized.startsWith("stremio://", true) ->
                normalized = "https://" + normalized.removePrefix("stremio://")
            normalized.startsWith("stremio:", true) ->
                normalized = "https://" + normalized.removePrefix("stremio:")
        }
        normalized = normalized
            .removeSuffix("manifest.json")
            .removeSuffix("/")
        if (!normalized.endsWith("/")) normalized += "/"
        return normalized
    }

    private fun resourceUrl(
        addon: StremioAddon,
        resource: String,
        type: String,
        id: String,
        extras: Map<String, String>,
    ): String {
        val base = addon.transportUrl.trimEnd('/') + "/"
        val encodedType = encode(type)
        val encodedId = encode(id)
        val suffix = if (extras.isEmpty()) {
            ".json"
        } else {
            "/" + extras.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" } + ".json"
        }
        return "$base$resource/$encodedType/$encodedId$suffix"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
