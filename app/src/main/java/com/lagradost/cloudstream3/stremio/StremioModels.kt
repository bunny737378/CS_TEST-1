package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.TvType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The 4 official Stremio Addon capability types, ordered left-to-right for the Addons tabs.
 */
enum class StremioAddonCapability(val resourceName: String, val displayTitle: String) {
    CATALOG("catalog", "Catalog"),
    METADATA("meta", "Metadata"),
    STREAM("stream", "Stream"),
    SUBTITLES("subtitles", "Subtitles"),
}

enum class StremioStreamSourceType(val badgeLabel: String) {
    TORRENT("TORRENT"),
    DIRECT("DIRECT"),
    HLS("HLS"),
    DASH("DASH"),
    LIVE("LIVE"),
    YOUTUBE("YOUTUBE"),
    EXTERNAL("EXTERNAL"),
}

data class StremioResourceSpec(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

data class StremioCatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    val optionsLimit: Int = 1,
)

data class StremioCatalogDef(
    val type: String,
    val id: String,
    val name: String?,
    val extra: List<StremioCatalogExtra> = emptyList(),
) {
    fun supportsExtra(extraName: String): Boolean =
        extra.any { it.name.equals(extraName, ignoreCase = true) }

    val supportsSearch: Boolean
        get() = supportsExtra("search")

    val supportsSkip: Boolean
        get() = supportsExtra("skip")

    /**
     * Feed catalogs can be fetched without mandatory extra parameters (like required `search`).
     */
    val isFeedCatalog: Boolean
        get() = extra.none { it.isRequired }
}

data class StremioConfigField(
    val key: String,
    val type: String, // "text", "number", "password", "checkbox", "select"
    val defaultValue: String? = null,
    val title: String? = null,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
)

data class StremioBehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

data class StremioManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val resources: List<StremioResourceSpec> = emptyList(),
    val catalogs: List<StremioCatalogDef> = emptyList(),
    val config: List<StremioConfigField> = emptyList(),
    val behaviorHints: StremioBehaviorHints = StremioBehaviorHints(),
    val rawJson: String = "{}",
) {
    val capabilities: Set<StremioAddonCapability>
        get() {
            val result = linkedSetOf<StremioAddonCapability>()
            val resourceNames = resources.map { it.name.lowercase() }.toSet()
            if ("catalog" in resourceNames || catalogs.isNotEmpty()) {
                result.add(StremioAddonCapability.CATALOG)
            }
            if ("meta" in resourceNames) {
                result.add(StremioAddonCapability.METADATA)
            }
            if ("stream" in resourceNames) {
                result.add(StremioAddonCapability.STREAM)
            }
            if ("subtitles" in resourceNames) {
                result.add(StremioAddonCapability.SUBTITLES)
            }
            return result
        }

    val hasFeedCatalogs: Boolean
        get() = catalogs.any { it.isFeedCatalog }

    val hasSearchCatalogs: Boolean
        get() = catalogs.any { it.supportsSearch }

    val isConfigurable: Boolean
        get() = behaviorHints.configurable || behaviorHints.configurationRequired || config.isNotEmpty()

    /**
     * Checks whether this manifest supports [resource] ("meta", "stream", "subtitles", "catalog")
     * for the given Stremio [type] ("movie", "series", "channel", "tv", "anime") and item [id].
     */
    fun supportsResource(resource: String, type: String, id: String): Boolean {
        if (resource == "catalog") {
            return catalogs.any { it.type == type && it.id == id }
        }
        val matchingSpecs = resources.filter { it.name.equals(resource, ignoreCase = true) }
        if (matchingSpecs.isEmpty()) return false

        return matchingSpecs.any { spec ->
            val effectiveTypes = spec.types?.takeIf { it.isNotEmpty() } ?: types
            val typeMatches = effectiveTypes.isEmpty() ||
                effectiveTypes.any { t ->
                    t.equals(type, ignoreCase = true) ||
                        (type.equals("anime", ignoreCase = true) && t.equals("series", ignoreCase = true))
                }
            if (!typeMatches) return@any false

            val effectivePrefixes = (spec.idPrefixes ?: idPrefixes)?.filter { it.isNotEmpty() }
            if (effectivePrefixes.isNullOrEmpty()) {
                true
            } else {
                effectivePrefixes.any { prefix -> id.startsWith(prefix) }
            }
        }
    }
}

data class InstalledStremioAddon(
    val manifestUrl: String,
    val transportBaseUrl: String,
    val manifest: StremioManifest,
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    val configValues: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false,
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name.trim().ifEmpty { manifest.id }

    fun supportsCapability(capability: StremioAddonCapability): Boolean =
        capability in manifest.capabilities

    fun configureWebUrl(): String = StremioTransport.buildConfigureWebUrl(this)
}

data class StremioMetaLink(
    val name: String,
    val category: String,
    val url: String?,
)

data class StremioTrailer(
    val source: String?,
    val type: String?,
)

data class StremioVideoItem(
    val id: String,
    val title: String?,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val streams: List<StremioStreamItem>? = null,
)

data class StremioMetaItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val imdbId: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val runtime: String? = null,
    val videos: List<StremioVideoItem> = emptyList(),
    val links: List<StremioMetaLink> = emptyList(),
    val trailers: List<StremioTrailer> = emptyList(),
    val defaultVideoId: String? = null,
) {
    fun resolveImdbId(): String? {
        if (!imdbId.isNullOrBlank() && imdbId.startsWith("tt")) return imdbId
        if (id.startsWith("tt")) return id.substringBefore(":")
        links.forEach { link ->
            val match = Regex("(tt\\d{5,12})").find(link.url ?: "")
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun toCloudStreamTvType(): TvType {
        return when (type.lowercase()) {
            "movie" -> TvType.Movie
            "series" -> TvType.TvSeries
            "anime" -> TvType.Anime
            "tv" -> TvType.Live
            "channel" -> TvType.TvSeries
            else -> TvType.Movie
        }
    }
}

data class StremioSubtitleItem(
    val id: String,
    val url: String,
    val lang: String,
    val label: String? = null,
)

data class StremioProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap(),
)

data class StremioStreamBehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val proxyHeaders: StremioProxyHeaders? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
)

data class StremioStreamItem(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sources: List<String> = emptyList(),
    val subtitles: List<StremioSubtitleItem> = emptyList(),
    val behaviorHints: StremioStreamBehaviorHints = StremioStreamBehaviorHints(),
) {
    fun resolveSourceType(itemType: String = ""): StremioStreamSourceType {
        return when {
            !infoHash.isNullOrBlank() ||
                url?.startsWith("magnet:", ignoreCase = true) == true ||
                url?.substringBefore("?")?.endsWith(".torrent", ignoreCase = true) == true ->
                StremioStreamSourceType.TORRENT
            !ytId.isNullOrBlank() -> StremioStreamSourceType.YOUTUBE
            !url.isNullOrBlank() -> {
                val clean = url.substringBefore("?").lowercase()
                when {
                    itemType.equals("tv", ignoreCase = true) -> StremioStreamSourceType.LIVE
                    clean.endsWith(".m3u8") || url.contains(".m3u8", ignoreCase = true) -> StremioStreamSourceType.HLS
                    clean.endsWith(".mpd") || url.contains(".mpd", ignoreCase = true) -> StremioStreamSourceType.DASH
                    else -> StremioStreamSourceType.DIRECT
                }
            }
            !externalUrl.isNullOrBlank() -> StremioStreamSourceType.EXTERNAL
            else -> StremioStreamSourceType.DIRECT
        }
    }

    fun detectQualityBadge(): String? {
        val combined = listOfNotNull(name, title, description, behaviorHints.filename).joinToString(" ")
        return when {
            Regex("\\b(2160p|4k|uhd)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "4K"
            Regex("\\b1440p\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "1440p"
            Regex("\\b1080p\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "1080p"
            Regex("\\b720p\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "720p"
            Regex("\\b480p\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "480p"
            Regex("\\b360p\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined) -> "360p"
            else -> null
        }
    }
}

data class StremioResolvedStream(
    val addonId: String,
    val addonName: String,
    val addonLogo: String? = null,
    val stream: StremioStreamItem,
    val itemType: String,
) {
    val sourceType: StremioStreamSourceType = stream.resolveSourceType(itemType)
    val qualityBadge: String? = stream.detectQualityBadge()

    val primaryTitle: String
        get() {
            val cleanName = stream.name?.trim()?.replace(Regex("\\s*\n+\\s*"), " · ")
            val firstDescLine = (stream.description ?: stream.title)
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
            return when {
                !firstDescLine.isNullOrEmpty() -> firstDescLine
                !cleanName.isNullOrEmpty() -> cleanName
                !stream.behaviorHints.filename.isNullOrBlank() -> stream.behaviorHints.filename
                else -> addonName
            }
        }

    val secondaryDetails: String?
        get() {
            val rawBody = (stream.description ?: stream.title)?.trim()
            if (!rawBody.isNullOrEmpty()) {
                val lines = rawBody.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.size > 1) {
                    return lines.drop(1).joinToString("\n")
                }
                val cleanName = stream.name?.trim()?.replace(Regex("\\s*\n+\\s*"), " · ")
                if (!cleanName.isNullOrEmpty() && cleanName != lines.first()) {
                    return cleanName
                }
            }
            return stream.behaviorHints.filename?.takeIf { it.isNotBlank() && it != primaryTitle }
        }

    val headerLabel: String
        get() {
            val cleanName = stream.name?.trim()?.replace(Regex("\\s*\n+\\s*"), " · ")
            return if (!cleanName.isNullOrEmpty() && !cleanName.equals(addonName, ignoreCase = true)) {
                "$addonName · $cleanName"
            } else {
                addonName
            }
        }
}

data class StremioSubtitleAddonResult(
    val addonId: String,
    val addonName: String,
    val subtitles: List<StremioSubtitleItem>,
    val isLoading: Boolean = false,
)

/**
 * Payload encoded into `LoadResponse.url` and `Episode.data` for Stremio items.
 */
data class StremioEpisodeData(
    val sourceAddonId: String?,
    val stremioType: String,
    val metaId: String,
    val videoId: String,
    val imdbId: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val embeddedStreams: List<StremioStreamItem> = emptyList(),
)

object StremioJsonParser {

    fun parseManifest(raw: String): StremioManifest {
        val json = JSONObject(raw)
        val id = json.optString("id").trim()
        val name = json.optString("name").trim()
        val version = json.optString("version", "1.0.0").trim()
        require(id.isNotEmpty() && name.isNotEmpty()) {
            "Invalid Stremio manifest: missing id or name"
        }

        val types = json.optJSONArray("types").toStringList()
        val idPrefixes = parsePrefixes(json, "idPrefixes", "idPrefix")

        val resources = mutableListOf<StremioResourceSpec>()
        val resArr = json.optJSONArray("resources")
        if (resArr != null) {
            for (i in 0 until resArr.length()) {
                val entry = resArr.opt(i)
                when (entry) {
                    is String -> {
                        if (entry.isNotBlank()) {
                            resources.add(StremioResourceSpec(name = entry.trim()))
                        }
                    }
                    is JSONObject -> {
                        val rName = entry.optString("name").trim()
                        if (rName.isNotEmpty()) {
                            resources.add(
                                StremioResourceSpec(
                                    name = rName,
                                    types = entry.optJSONArray("types")?.toStringList(),
                                    idPrefixes = parsePrefixes(entry, "idPrefixes", "idPrefix"),
                                )
                            )
                        }
                    }
                }
            }
        }

        val catalogs = mutableListOf<StremioCatalogDef>()
        val catArr = json.optJSONArray("catalogs")
        if (catArr != null) {
            for (i in 0 until catArr.length()) {
                val c = catArr.optJSONObject(i) ?: continue
                val cType = c.optString("type").trim()
                val cId = c.optString("id").trim()
                if (cType.isEmpty() || cId.isEmpty()) continue
                catalogs.add(
                    StremioCatalogDef(
                        type = cType,
                        id = cId,
                        name = c.optNullableString("name"),
                        extra = parseCatalogExtras(c),
                    )
                )
            }
        }

        val configFields = mutableListOf<StremioConfigField>()
        val cfgArr = json.optJSONArray("config")
        if (cfgArr != null) {
            for (i in 0 until cfgArr.length()) {
                val cfg = cfgArr.optJSONObject(i) ?: continue
                val key = cfg.optString("key").trim()
                if (key.isEmpty()) continue
                configFields.add(
                    StremioConfigField(
                        key = key,
                        type = cfg.optString("type", "text").trim(),
                        defaultValue = cfg.opt("default")?.toString()?.takeIf { it != "null" },
                        title = cfg.optNullableString("title"),
                        options = cfg.optJSONArray("options").toStringList(),
                        required = cfg.optBoolean("required", false),
                    )
                )
            }
        }

        val hintsJson = json.optJSONObject("behaviorHints")
        val behaviorHints = if (hintsJson != null) {
            StremioBehaviorHints(
                adult = hintsJson.optBoolean("adult", false),
                p2p = hintsJson.optBoolean("p2p", false),
                configurable = hintsJson.optBoolean("configurable", false),
                configurationRequired = hintsJson.optBoolean("configurationRequired", false),
            )
        } else {
            StremioBehaviorHints()
        }

        return StremioManifest(
            id = id,
            version = version.ifEmpty { "1.0.0" },
            name = name,
            description = json.optNullableString("description"),
            logo = json.optNullableString("logo"),
            background = json.optNullableString("background"),
            types = types,
            idPrefixes = idPrefixes,
            resources = resources,
            catalogs = catalogs,
            config = configFields,
            behaviorHints = behaviorHints,
            rawJson = raw,
        )
    }

    private fun parsePrefixes(json: JSONObject, pluralKey: String, singularKey: String): List<String>? {
        val plural = json.optJSONArray(pluralKey)?.toStringList()
        if (plural != null) return plural
        val singular = json.optNullableString(singularKey)
        if (singular != null) return listOf(singular)
        return null
    }

    private fun parseCatalogExtras(catalogJson: JSONObject): List<StremioCatalogExtra> {
        val extrasByName = linkedMapOf<String, StremioCatalogExtra>()
        val extraArr = catalogJson.optJSONArray("extra")
        if (extraArr != null) {
            for (i in 0 until extraArr.length()) {
                val item = extraArr.opt(i)
                when (item) {
                    is String -> {
                        val trimmed = item.trim()
                        if (trimmed.isNotEmpty()) {
                            extrasByName[trimmed] = StremioCatalogExtra(name = trimmed)
                        }
                    }
                    is JSONObject -> {
                        val name = item.optString("name").trim()
                        if (name.isNotEmpty()) {
                            extrasByName[name] = StremioCatalogExtra(
                                name = name,
                                isRequired = item.optBoolean("isRequired", false),
                                options = item.optJSONArray("options").toStringList(),
                                optionsLimit = item.optInt("optionsLimit", 1).coerceAtLeast(1),
                            )
                        }
                    }
                }
            }
        }
        // Support legacy extraSupported / extraRequired properties from older Stremio addons
        val legacySupported = catalogJson.optJSONArray("extraSupported").toStringList()
        val legacyRequired = catalogJson.optJSONArray("extraRequired").toStringList().toSet()
        for (name in legacySupported) {
            val existing = extrasByName[name]
            if (existing == null) {
                extrasByName[name] = StremioCatalogExtra(
                    name = name,
                    isRequired = name in legacyRequired,
                )
            } else if (name in legacyRequired && !existing.isRequired) {
                extrasByName[name] = existing.copy(isRequired = true)
            }
        }
        for (reqName in legacyRequired) {
            if (reqName !in extrasByName) {
                extrasByName[reqName] = StremioCatalogExtra(name = reqName, isRequired = true)
            }
        }
        return extrasByName.values.toList()
    }

    fun parseCatalogResponse(raw: String, fallbackType: String): List<StremioMetaItem> {
        val json = JSONObject(raw)
        val arr = json.optJSONArray("metas") ?: json.optJSONArray("metasDetailed") ?: return emptyList()
        val result = ArrayList<StremioMetaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseMetaObject(obj, fallbackType)?.let { result.add(it) }
        }
        return result
    }

    fun parseMetaResponse(raw: String, fallbackType: String): StremioMetaItem? {
        val json = JSONObject(raw)
        val metaObj = json.optJSONObject("meta") ?: json
        return parseMetaObject(metaObj, fallbackType)
    }

    fun parseMetaObject(obj: JSONObject, fallbackType: String): StremioMetaItem? {
        val id = obj.optString("id").trim()
        val name = obj.optString("name").trim()
        if (id.isEmpty() || name.isEmpty()) return null
        val type = obj.optString("type").trim().ifEmpty { fallbackType }

        val videos = mutableListOf<StremioVideoItem>()
        val videosArr = obj.optJSONArray("videos")
        if (videosArr != null) {
            for (i in 0 until videosArr.length()) {
                val v = videosArr.optJSONObject(i) ?: continue
                val vId = v.optString("id").trim()
                if (vId.isEmpty()) continue
                val embeddedStreams = v.optJSONArray("streams")?.let { parseStreamsArray(it) }
                videos.add(
                    StremioVideoItem(
                        id = vId,
                        title = v.optNullableString("title") ?: v.optNullableString("name"),
                        released = v.optNullableString("released") ?: v.optNullableString("firstAired"),
                        thumbnail = v.optNullableString("thumbnail"),
                        overview = v.optNullableString("overview") ?: v.optNullableString("description"),
                        season = v.optNullableInt("season"),
                        episode = v.optNullableInt("episode") ?: v.optNullableInt("number"),
                        streams = embeddedStreams,
                    )
                )
            }
        }

        val links = mutableListOf<StremioMetaLink>()
        val linksArr = obj.optJSONArray("links")
        if (linksArr != null) {
            for (i in 0 until linksArr.length()) {
                val l = linksArr.optJSONObject(i) ?: continue
                val lName = l.optString("name").trim()
                val lCat = l.optString("category").trim()
                if (lName.isNotEmpty()) {
                    links.add(
                        StremioMetaLink(
                            name = lName,
                            category = lCat,
                            url = l.optNullableString("url"),
                        )
                    )
                }
            }
        }

        val trailers = mutableListOf<StremioTrailer>()
        val trailersArr = obj.optJSONArray("trailers")
        if (trailersArr != null) {
            for (i in 0 until trailersArr.length()) {
                val t = trailersArr.optJSONObject(i) ?: continue
                trailers.add(
                    StremioTrailer(
                        source = t.optNullableString("source") ?: t.optNullableString("ytId"),
                        type = t.optNullableString("type"),
                    )
                )
            }
        }
        val trailerStreamsArr = obj.optJSONArray("trailerStreams")
        if (trailerStreamsArr != null) {
            for (i in 0 until trailerStreamsArr.length()) {
                val ts = trailerStreamsArr.optJSONObject(i) ?: continue
                val yt = ts.optNullableString("ytId")
                if (yt != null) {
                    trailers.add(StremioTrailer(source = yt, type = "Trailer"))
                }
            }
        }

        val behaviorHints = obj.optJSONObject("behaviorHints")
        val defaultVideoId = behaviorHints?.optNullableString("defaultVideoId")

        val genres = (obj.optJSONArray("genres").toStringList() + obj.optJSONArray("genre").toStringList()).distinct()

        return StremioMetaItem(
            id = id,
            type = type,
            name = name,
            poster = obj.optNullableString("poster"),
            posterShape = obj.optNullableString("posterShape"),
            background = obj.optNullableString("background"),
            logo = obj.optNullableString("logo"),
            description = obj.optNullableString("description") ?: obj.optNullableString("overview"),
            releaseInfo = obj.optNullableString("releaseInfo") ?: obj.opt("year")?.toString()?.takeIf { it != "null" },
            imdbRating = obj.opt("imdbRating")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
            imdbId = obj.optNullableString("imdb_id") ?: obj.optNullableString("imdbId"),
            genres = genres,
            cast = obj.optJSONArray("cast").toStringList(),
            director = obj.optJSONArray("director").toStringList(),
            runtime = obj.optNullableString("runtime"),
            videos = videos,
            links = links,
            trailers = trailers,
            defaultVideoId = defaultVideoId,
        )
    }

    fun parseStreamsResponse(raw: String): List<StremioStreamItem> {
        val json = JSONObject(raw)
        val arr = json.optJSONArray("streams") ?: return emptyList()
        return parseStreamsArray(arr)
    }

    fun parseStreamsArray(arr: JSONArray): List<StremioStreamItem> {
        val result = ArrayList<StremioStreamItem>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optNullableString("url")
            val ytId = s.optNullableString("ytId")
            val infoHash = s.optNullableString("infoHash")?.lowercase()
            val externalUrl = s.optNullableString("externalUrl")
            if (url == null && ytId == null && infoHash == null && externalUrl == null) continue

            val hintsObj = s.optJSONObject("behaviorHints")
            val proxyObj = hintsObj?.optJSONObject("proxyHeaders")
            val behaviorHints = if (hintsObj != null) {
                StremioStreamBehaviorHints(
                    notWebReady = hintsObj.optBoolean("notWebReady", false),
                    bingeGroup = hintsObj.optNullableString("bingeGroup"),
                    proxyHeaders = proxyObj?.let {
                        StremioProxyHeaders(
                            request = it.optJSONObject("request").toStringMap(),
                            response = it.optJSONObject("response").toStringMap(),
                        )
                    },
                    videoHash = hintsObj.optNullableString("videoHash"),
                    videoSize = hintsObj.optNullableLong("videoSize"),
                    filename = hintsObj.optNullableString("filename"),
                )
            } else {
                StremioStreamBehaviorHints()
            }

            // Some addons also place headers directly on the stream object
            val directHeaders = s.optJSONObject("headers").toStringMap()
            val mergedHints = if (directHeaders.isNotEmpty() && behaviorHints.proxyHeaders == null) {
                behaviorHints.copy(proxyHeaders = StremioProxyHeaders(request = directHeaders))
            } else {
                behaviorHints
            }

            result.add(
                StremioStreamItem(
                    url = url,
                    ytId = ytId,
                    infoHash = infoHash,
                    fileIdx = s.optNullableInt("fileIdx"),
                    externalUrl = externalUrl,
                    name = s.optNullableString("name"),
                    title = s.optNullableString("title"),
                    description = s.optNullableString("description"),
                    sources = s.optJSONArray("sources").toStringList(),
                    subtitles = parseSubtitlesArray(s.optJSONArray("subtitles")),
                    behaviorHints = mergedHints,
                )
            )
        }
        return result
    }

    fun parseSubtitlesResponse(raw: String): List<StremioSubtitleItem> {
        val json = JSONObject(raw)
        return parseSubtitlesArray(json.optJSONArray("subtitles"))
    }

    fun parseSubtitlesArray(arr: JSONArray?): List<StremioSubtitleItem> {
        if (arr == null) return emptyList()
        val result = ArrayList<StremioSubtitleItem>(arr.length())
        for (i in 0 until arr.length()) {
            val sub = arr.optJSONObject(i) ?: continue
            val rawUrl = sub.optNullableString("url") ?: continue
            val unwrappedUrl = unwrapStremioSubtitleUrl(rawUrl)
            val lang = sub.optNullableString("lang") ?: "und"
            val id = sub.optNullableString("id") ?: "$lang-$i"
            val label = sub.optNullableString("label") ?: sub.optNullableString("title")
            result.add(
                StremioSubtitleItem(
                    id = id,
                    url = unwrappedUrl,
                    lang = lang,
                    label = label,
                )
            )
        }
        return result
    }

    /**
     * Some Stremio subtitle addons proxy SRT files via `http://127.0.0.1:11470/subtitles.vtt?from=...`
     * assuming desktop Stremio's local Node server. Since ExoPlayer/CloudStream natively supports SRT, VTT,
     * ASS/SSA, and TTML directly, unwrap `from=` when pointing to localhost:11470.
     */
    fun unwrapStremioSubtitleUrl(url: String): String {
        if ((url.contains("127.0.0.1:11470") || url.contains("localhost:11470")) && url.contains("from=")) {
            val encodedFrom = url.substringAfter("from=").substringBefore("&")
            if (encodedFrom.isNotEmpty()) {
                return try {
                    java.net.URLDecoder.decode(encodedFrom, "UTF-8")
                } catch (_: Throwable) {
                    url
                }
            }
        }
        return url
    }

    fun serializeInstalledAddons(addons: List<InstalledStremioAddon>): String {
        val arr = JSONArray()
        for (addon in addons) {
            val obj = JSONObject()
            obj.put("manifestUrl", addon.manifestUrl)
            obj.put("transportBaseUrl", addon.transportBaseUrl)
            obj.put("manifestRaw", addon.manifest.rawJson)
            obj.put("enabled", addon.enabled)
            obj.put("addedAt", addon.addedAt)
            obj.put("isDefault", addon.isDefault)
            val cfgObj = JSONObject()
            addon.configValues.forEach { (k, v) -> cfgObj.put(k, v) }
            obj.put("configValues", cfgObj)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializeInstalledAddons(raw: String?): List<InstalledStremioAddon> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<InstalledStremioAddon>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val manifestUrl = obj.optString("manifestUrl").trim()
                val transportBaseUrl = obj.optString("transportBaseUrl").trim()
                val manifestRaw = obj.optString("manifestRaw")
                if (manifestUrl.isEmpty() || manifestRaw.isEmpty()) continue
                val manifest = runCatching { parseManifest(manifestRaw) }.getOrNull() ?: continue
                list.add(
                    InstalledStremioAddon(
                        manifestUrl = manifestUrl,
                        transportBaseUrl = transportBaseUrl.ifEmpty {
                            StremioTransport.extractTransportBaseUrl(manifestUrl)
                        },
                        manifest = manifest,
                        enabled = obj.optBoolean("enabled", true),
                        addedAt = obj.optLong("addedAt", 0L),
                        configValues = obj.optJSONObject("configValues").toStringMap(),
                        isDefault = obj.optBoolean("isDefault", false),
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun encodeEpisodeData(data: StremioEpisodeData): String {
        val obj = JSONObject()
        obj.put("stremioPayload", true)
        if (data.sourceAddonId != null) obj.put("sourceAddonId", data.sourceAddonId)
        obj.put("stremioType", data.stremioType)
        obj.put("metaId", data.metaId)
        obj.put("videoId", data.videoId)
        if (data.imdbId != null) obj.put("imdbId", data.imdbId)
        if (data.title != null) obj.put("title", data.title)
        if (data.season != null) obj.put("season", data.season)
        if (data.episode != null) obj.put("episode", data.episode)
        if (data.embeddedStreams.isNotEmpty()) {
            val streamsArr = JSONArray()
            for (s in data.embeddedStreams) {
                streamsArr.put(serializeStreamItem(s))
            }
            obj.put("embeddedStreams", streamsArr)
        }
        return obj.toString()
    }

    fun decodeEpisodeData(raw: String?): StremioEpisodeData? {
        if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
        return try {
            val obj = JSONObject(raw)
            if (!obj.optBoolean("stremioPayload", false)) return null
            StremioEpisodeData(
                sourceAddonId = obj.optNullableString("sourceAddonId"),
                stremioType = obj.optString("stremioType", "movie"),
                metaId = obj.optString("metaId"),
                videoId = obj.optString("videoId"),
                imdbId = obj.optNullableString("imdbId"),
                title = obj.optNullableString("title"),
                season = obj.optNullableInt("season"),
                episode = obj.optNullableInt("episode"),
                embeddedStreams = obj.optJSONArray("embeddedStreams")
                    ?.let { parseStreamsArray(it) }
                    ?: emptyList(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun serializeStreamItem(s: StremioStreamItem): JSONObject {
        val obj = JSONObject()
        if (s.url != null) obj.put("url", s.url)
        if (s.ytId != null) obj.put("ytId", s.ytId)
        if (s.infoHash != null) obj.put("infoHash", s.infoHash)
        if (s.fileIdx != null) obj.put("fileIdx", s.fileIdx)
        if (s.externalUrl != null) obj.put("externalUrl", s.externalUrl)
        if (s.name != null) obj.put("name", s.name)
        if (s.title != null) obj.put("title", s.title)
        if (s.description != null) obj.put("description", s.description)
        if (s.sources.isNotEmpty()) obj.put("sources", JSONArray(s.sources))
        if (s.subtitles.isNotEmpty()) {
            val subArr = JSONArray()
            for (sub in s.subtitles) {
                subArr.put(
                    JSONObject().apply {
                        put("id", sub.id)
                        put("url", sub.url)
                        put("lang", sub.lang)
                        if (sub.label != null) put("label", sub.label)
                    }
                )
            }
            obj.put("subtitles", subArr)
        }
        val hints = s.behaviorHints
        val hintsObj = JSONObject()
        hintsObj.put("notWebReady", hints.notWebReady)
        if (hints.bingeGroup != null) hintsObj.put("bingeGroup", hints.bingeGroup)
        if (hints.videoHash != null) hintsObj.put("videoHash", hints.videoHash)
        if (hints.videoSize != null) hintsObj.put("videoSize", hints.videoSize)
        if (hints.filename != null) hintsObj.put("filename", hints.filename)
        if (hints.proxyHeaders != null) {
            val proxyObj = JSONObject()
            if (hints.proxyHeaders.request.isNotEmpty()) {
                proxyObj.put("request", JSONObject(hints.proxyHeaders.request))
            }
            if (hints.proxyHeaders.response.isNotEmpty()) {
                proxyObj.put("response", JSONObject(hints.proxyHeaders.response))
            }
            hintsObj.put("proxyHeaders", proxyObj)
        }
        obj.put("behaviorHints", hintsObj)
        return obj
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key).trim()
        return if (v.isEmpty() || v == "null") null else v
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i).trim()
            if (s.isNotEmpty() && s != "null") list.add(s)
        }
        return list
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val keysIter = keys()
        while (keysIter.hasNext()) {
            val key = keysIter.next()
            val value = optString(key)
            if (key.isNotEmpty() && value.isNotEmpty() && value != "null") {
                map[key] = value
            }
        }
        return map
    }
}
