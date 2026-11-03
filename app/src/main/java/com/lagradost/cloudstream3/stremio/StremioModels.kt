package com.lagradost.cloudstream3.stremio

import org.json.JSONArray
import org.json.JSONObject

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
    val result = LinkedHashMap<String, String>()
    val iterator = source.keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        source.string(key)?.let { result[key] = it }
    }
    return result
}

data class StremioResource(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
)

data class StremioCatalog(
    val id: String,
    val type: String,
    val name: String?,
    val extra: List<String>,
) {
    val supportsSearch: Boolean get() = "search" in extra
    val supportsSkip: Boolean get() = "skip" in extra
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
                        is String -> resources.add(StremioResource(value))
                        is JSONObject -> {
                            val resourceName = value.string("name") ?: continue
                            resources.add(
                                StremioResource(
                                    name = resourceName,
                                    types = value.stringArray("types"),
                                    idPrefixes = value.optJSONArray("idPrefixes")?.stringList()
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
                    val extras = LinkedHashSet<String>()
                    catalog.optJSONArray("extra")?.let { extraArray ->
                        for (extraIndex in 0 until extraArray.length()) {
                            when (val extra = extraArray.opt(extraIndex)) {
                                is String -> extras.add(extra)
                                is JSONObject -> extra.string("name")?.let(extras::add)
                            }
                        }
                    }
                    extras.addAll(catalog.stringArray("extraSupported"))
                    catalogs.add(
                        StremioCatalog(
                            id = catalogId,
                            type = catalogType,
                            name = catalog.string("name"),
                            extra = extras.toList()
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

    fun supports(resource: String, type: String, id: String): Boolean {
        val resourceDefinition = manifest.resources.firstOrNull { it.name == resource } ?: return false
        val resourceTypes = resourceDefinition.types.ifEmpty { manifest.types }
        if (resourceTypes.isNotEmpty() && type !in resourceTypes) return false
        val prefixes = resourceDefinition.idPrefixes ?: manifest.idPrefixes.orEmpty()
        return prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
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
            return StremioStream(
                url = json.string("url"),
                ytId = json.string("ytId"),
                infoHash = json.string("infoHash"),
                fileIdx = json.intValue("fileIdx"),
                externalUrl = json.string("externalUrl"),
                name = json.string("name"),
                title = json.string("title"),
                description = json.string("description"),
                subtitles = subtitles,
                headers = behaviorHints?.stringMap("proxyHeaders").orEmpty(),
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
        fun fromAuthResult(result: JSONObject): StremioUser? {
            val authKey = result.string("authKey") ?: result.string("token") ?: return null
            val user = result.optJSONObject("user")
            return StremioUser(
                authKey = authKey,
                id = user?.string("_id") ?: user?.string("id") ?: result.string("_id"),
                email = user?.string("email") ?: result.string("email"),
            )
        }

        fun fromUserResult(result: JSONObject, authKey: String): StremioUser {
            return StremioUser(
                authKey = authKey,
                id = result.string("_id") ?: result.string("id"),
                email = result.string("email"),
            )
        }
    }
}
