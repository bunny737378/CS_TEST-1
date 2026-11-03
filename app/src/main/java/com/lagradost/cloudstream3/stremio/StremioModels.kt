package com.lagradost.cloudstream3.stremio

import org.json.JSONArray
import org.json.JSONObject

private val SENSITIVE_JSON_VALUE =
    Regex("\"(authKey|key|token|password)\"\\s*:\\s*\"[^\"]*\"")

/** Remove session tokens and passwords before showing an API error in the UI. */
fun String.redactStremioSecrets(): String =
    SENSITIVE_JSON_VALUE.replace(this) { match ->
        "\"${match.groupValues[1]}\":\"[REDACTED]\""
    }

/** Converts an org.json tree into plain Kotlin maps/lists that NiceHttp can serialize. */
fun JSONObject.toAnyMap(): Map<String, Any> {
    val result = LinkedHashMap<String, Any>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        if (isNull(key)) continue
        result[key] = when (val value = get(key)) {
            is JSONObject -> value.toAnyMap()
            is JSONArray -> value.toAnyList()
            else -> value
        }
    }
    return result
}

fun JSONArray.toAnyList(): List<Any> {
    val result = ArrayList<Any>(length())
    for (index in 0 until length()) {
        if (isNull(index)) continue
        result.add(
            when (val value = get(index)) {
                is JSONObject -> value.toAnyMap()
                is JSONArray -> value.toAnyList()
                else -> value
            }
        )
    }
    return result
}

private fun JSONObject.string(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() && it != "null" }
}

private fun JSONObject.intValue(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return when (val value = get(name)) {
        is Number -> value.toInt()
        is String -> value.filter { it.isDigit() }.toIntOrNull()
        else -> null
    }
}

private fun JSONObject.numberString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return when (val value = get(name)) {
        is Number -> value.toString()
        is String -> value.takeIf { it.isNotBlank() && it != "null" }
        else -> null
    }
}

private fun JSONArray.stringList(): List<String> {
    val result = ArrayList<String>(length())
    for (index in 0 until length()) {
        if (isNull(index)) continue
        when (val value = get(index)) {
            is String -> result.add(value)
            is JSONObject -> value.string("name")?.let(result::add)
            else -> optString(index).takeIf { it.isNotBlank() }?.let(result::add)
        }
    }
    return result
}

private fun JSONObject.stringArray(name: String): List<String> =
    optJSONArray(name)?.stringList().orEmpty()

private fun JSONObject.stringMap(name: String): Map<String, String> {
    val source = optJSONObject(name) ?: return emptyMap()
    return source.stringMapValues()
}

private fun JSONObject.stringMapValues(): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        string(key)?.let { result[key] = it }
    }
    return result
}

data class StremioExtraProp(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
)

/** Mirrors stremio-core's ManifestResource: a short string resource inherits the manifest types/prefixes. */
data class StremioResource(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
    val isShort: Boolean = false,
)

data class StremioCatalog(
    val id: String,
    val type: String,
    val name: String?,
    val extraProps: List<StremioExtraProp>,
) {
    val extra: List<String> get() = extraProps.map { it.name }

    /** Mirrors ManifestCatalog::is_extra_supported from stremio-core. */
    fun isExtraSupported(names: Collection<String>): Boolean {
        val supported = extraProps.map { it.name }.toSet()
        val allSupported = names.all { it in supported }
        val requiredSatisfied = extraProps
            .filter { it.isRequired }
            .all { it.name in names }
        return allSupported && requiredSatisfied
    }

    val supportsSearch: Boolean get() = isExtraSupported(listOf("search"))
    val supportsSkip: Boolean get() = "skip" in extra

    /** First option for every required extra, matching core's default_required_extra(). */
    fun defaultRequiredExtra(): Map<String, String> = extraProps
        .filter { it.isRequired && it.options.isNotEmpty() }
        .associate { it.name to it.options.first() }
}

data class StremioManifest(
    val rawJson: String,
    val id: String,
    val name: String,
    val description: String?,
    val version: String?,
    val logo: String?,
    val background: String?,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val resources: List<StremioResource>,
    val catalogs: List<StremioCatalog>,
) {
    companion object {
        fun from(json: JSONObject): StremioManifest? {
            val id = json.string("id") ?: return null
            val name = json.string("name") ?: id
            val resources = ArrayList<StremioResource>()
            json.optJSONArray("resources")?.let { array ->
                for (index in 0 until array.length()) {
                    when (val value = array.opt(index)) {
                        is String -> resources.add(
                            StremioResource(name = value, isShort = true)
                        )
                        is JSONObject -> {
                            val resourceName = value.string("name") ?: continue
                            resources.add(
                                StremioResource(
                                    name = resourceName,
                                    types = value.optJSONArray("types")?.stringList(),
                                    idPrefixes = value.optJSONArray("idPrefixes")?.stringList(),
                                    isShort = false,
                                )
                            )
                        }
                    }
                }
            }

            val catalogs = ArrayList<StremioCatalog>()
            json.optJSONArray("catalogs")?.let { array ->
                for (index in 0 until array.length()) {
                    val catalog = array.optJSONObject(index) ?: continue
                    val catalogId = catalog.string("id") ?: continue
                    val catalogType = catalog.string("type") ?: continue
                    val extraProps = LinkedHashMap<String, StremioExtraProp>()
                    catalog.optJSONArray("extra")?.let { extraArray ->
                        for (extraIndex in 0 until extraArray.length()) {
                            when (val extra = extraArray.opt(extraIndex)) {
                                is String -> {
                                    if (!extraProps.containsKey(extra)) {
                                        extraProps[extra] = StremioExtraProp(extra)
                                    }
                                }
                                is JSONObject -> {
                                    val extraName = extra.string("name") ?: continue
                                    val isRequired = extra.optBoolean("isRequired", false)
                                    val options = extra.optJSONArray("options")?.stringList().orEmpty()
                                    extraProps[extraName] = StremioExtraProp(
                                        name = extraName,
                                        isRequired = isRequired,
                                        options = options,
                                    )
                                }
                            }
                        }
                    }
                    catalog.stringArray("extraSupported").forEach { extraName ->
                        if (!extraProps.containsKey(extraName)) {
                            extraProps[extraName] = StremioExtraProp(extraName)
                        }
                    }
                    catalog.stringArray("extraRequired").forEach { extraName ->
                        val existing = extraProps[extraName]
                        extraProps[extraName] = existing?.copy(isRequired = true)
                            ?: StremioExtraProp(extraName, isRequired = true)
                    }
                    catalogs.add(
                        StremioCatalog(
                            id = catalogId,
                            type = catalogType,
                            name = catalog.string("name"),
                            extraProps = extraProps.values.toList()
                        )
                    )
                }
            }

            return StremioManifest(
                rawJson = json.toString(),
                id = id,
                name = name,
                description = json.string("description"),
                version = json.string("version"),
                logo = json.string("logo"),
                background = json.string("background"),
                types = json.stringArray("types"),
                idPrefixes = json.optJSONArray("idPrefixes")?.stringList(),
                resources = resources,
                catalogs = catalogs,
            )
        }
    }
}

data class StremioAddon(
    val rawJson: String,
    val transportUrl: String,
    val manifest: StremioManifest,
    val flags: Map<String, Boolean>,
) {
    companion object {
        fun fromDescriptor(json: JSONObject): StremioAddon? {
            val transportUrl = json.string("transportUrl") ?: json.string("url")
                ?: return null
            val manifestJson = json.optJSONObject("manifest") ?: return null
            val manifest = StremioManifest.from(manifestJson) ?: return null
            val flags = LinkedHashMap<String, Boolean>()
            json.optJSONObject("flags")?.let { flagsJson ->
                val iterator = flagsJson.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    flags[key] = flagsJson.optBoolean(key, false)
                }
            }
            return StremioAddon(
                rawJson = json.toString(),
                transportUrl = transportUrl,
                manifest = manifest,
                flags = flags,
            )
        }
    }

    fun descriptorMap(): Map<String, Any> = JSONObject(rawJson).toAnyMap()

    /**
     * Mirrors stremio-core Manifest::is_resource_supported: short resources inherit
     * manifest types/prefixes, full resources use only their own optional types/prefixes.
     */
    fun supports(resource: String, type: String, id: String): Boolean {
        if (resource == "catalog") {
            return manifest.catalogs.any { it.type == type && it.id == id }
        }
        val resourceDefinition = manifest.resources.firstOrNull { it.name == resource } ?: return false
        val types: List<String>? = if (resourceDefinition.isShort) {
            manifest.types
        } else {
            resourceDefinition.types
        }
        if (types == null || type !in types) return false
        val prefixes: List<String>? = if (resourceDefinition.isShort) {
            manifest.idPrefixes
        } else {
            resourceDefinition.idPrefixes
        }
        return prefixes == null || prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
    }
}

data class StremioMetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val year: Int?,
    val genres: List<String>,
) {
    companion object {
        fun from(json: JSONObject, defaultType: String): StremioMetaPreview? {
            val id = json.string("id") ?: json.string("imdb_id") ?: return null
            val name = json.string("name") ?: return null
            val releaseInfo = json.string("releaseInfo")
            return StremioMetaPreview(
                id = id,
                type = json.string("type") ?: defaultType,
                name = name,
                poster = json.string("poster"),
                background = json.string("background"),
                logo = json.string("logo"),
                description = json.string("description"),
                releaseInfo = releaseInfo,
                imdbRating = json.numberString("imdbRating"),
                year = json.intValue("year")
                    ?: releaseInfo?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() },
                genres = json.stringArray("genres"),
            )
        }
    }
}

data class StremioVideo(
    val id: String,
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val thumbnail: String?,
    val released: String?,
) {
    companion object {
        fun from(json: JSONObject, fallbackId: String): StremioVideo {
            return StremioVideo(
                id = json.string("id") ?: fallbackId,
                name = json.string("name") ?: json.string("title"),
                season = json.intValue("season"),
                episode = json.intValue("episode") ?: json.intValue("number"),
                overview = json.string("overview") ?: json.string("description"),
                thumbnail = json.string("thumbnail") ?: json.string("poster"),
                released = json.string("released") ?: json.string("firstAired"),
            )
        }
    }
}

data class StremioMeta(
    val rawJson: String,
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val year: Int?,
    val genres: List<String>,
    val runtime: String?,
    val certification: String?,
    val status: String?,
    val videos: List<StremioVideo>,
) {
    companion object {
        fun from(json: JSONObject, defaultType: String): StremioMeta? {
            val id = json.string("id") ?: json.string("imdb_id") ?: return null
            val name = json.string("name") ?: return null
            val releaseInfo = json.string("releaseInfo")
            val videos = ArrayList<StremioVideo>()
            json.optJSONArray("videos")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { videos.add(StremioVideo.from(it, id)) }
                }
            }
            return StremioMeta(
                rawJson = json.toString(),
                id = id,
                type = json.string("type") ?: defaultType,
                name = name,
                poster = json.string("poster"),
                background = json.string("background"),
                logo = json.string("logo"),
                description = json.string("description") ?: json.string("overview"),
                releaseInfo = releaseInfo,
                imdbRating = json.numberString("imdbRating"),
                year = json.intValue("year")
                    ?: releaseInfo?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() },
                genres = json.stringArray("genres"),
                runtime = json.string("runtime"),
                certification = json.string("certification"),
                status = json.string("status"),
                videos = videos,
            )
        }
    }
}

data class StremioSubtitle(
    val url: String,
    val lang: String,
)

data class StremioStream(
    val url: String?,
    val ytId: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val externalUrl: String?,
    val androidTvUrl: String?,
    val playerFrameUrl: String?,
    val name: String?,
    val title: String?,
    val description: String?,
    val subtitles: List<StremioSubtitle>,
    val headers: Map<String, String>,
) {
    companion object {
        fun from(json: JSONObject): StremioStream {
            val subtitles = ArrayList<StremioSubtitle>()
            json.optJSONArray("subtitles")?.let { array ->
                for (index in 0 until array.length()) {
                    val subtitle = array.optJSONObject(index) ?: continue
                    val url = subtitle.string("url") ?: continue
                    subtitles.add(
                        StremioSubtitle(
                            url = url,
                            lang = subtitle.string("lang") ?: "eng",
                        )
                    )
                }
            }
            val behaviorHints = json.optJSONObject("behaviorHints")
            val proxyRequestHeaders = behaviorHints
                ?.optJSONObject("proxyHeaders")
                ?.optJSONObject("request")
                ?.stringMapValues()
                .orEmpty()
            return StremioStream(
                url = json.string("url"),
                ytId = json.string("ytId"),
                infoHash = json.string("infoHash"),
                fileIdx = json.intValue("fileIdx"),
                externalUrl = json.string("externalUrl"),
                androidTvUrl = json.string("androidTvUrl"),
                playerFrameUrl = json.string("playerFrameUrl"),
                name = json.string("name"),
                title = json.string("title"),
                description = json.string("description"),
                subtitles = subtitles,
                headers = proxyRequestHeaders,
            )
        }
    }
}

data class StremioUser(
    val authKey: String,
    val id: String?,
    val email: String?,
) {
    companion object {
        /** Accepts the official authKey shape plus the legacy key/token and nested auth shapes. */
        fun fromAuthResult(result: JSONObject, fallbackAuthKey: String? = null): StremioUser? {
            val authObject = result.optJSONObject("auth")
            val authKey = result.string("authKey")
                ?: result.string("token")
                ?: result.string("key")
                ?: authObject?.string("authKey")
                ?: authObject?.string("key")
                ?: authObject?.string("token")
                ?: fallbackAuthKey?.takeIf { it.isNotBlank() }
                ?: return null
            val user = result.optJSONObject("user")
                ?: authObject?.optJSONObject("user")
            return StremioUser(
                authKey = authKey,
                id = user?.string("_id") ?: user?.string("id")
                    ?: result.string("_id") ?: result.string("id"),
                email = user?.string("email") ?: result.string("email"),
            )
        }

        /** getUser returns only the user object, so the pasted auth key is the session token. */
        fun fromUserResult(result: JSONObject, authKey: String): StremioUser =
            fromAuthResult(result, authKey) ?: StremioUser(
                authKey = authKey,
                id = result.string("_id") ?: result.string("id"),
                email = result.string("email"),
            )
    }
}
