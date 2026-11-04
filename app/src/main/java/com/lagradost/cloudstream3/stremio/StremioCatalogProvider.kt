package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

class StremioCatalogProvider(
    val addon: InstalledStremioAddon,
    providerName: String = addon.name,
) : MainAPI() {

    override var name: String = providerName
    override var mainUrl: String = addon.transportBaseUrl
    override var lang: String = "en"

    val hasSearchSupport: Boolean = addon.manifest.hasSearchCatalogs

    override val hasMainPage: Boolean = addon.manifest.hasFeedCatalogs
    override val hasQuickSearch: Boolean = hasSearchSupport

    override val supportedTypes: Set<TvType> = run {
        val rawTypes = (addon.manifest.types + addon.manifest.catalogs.map { it.type })
            .map { it.lowercase() }
            .toSet()
        val mapped = linkedSetOf<TvType>()
        if ("movie" in rawTypes) mapped.add(TvType.Movie)
        if ("series" in rawTypes || "channel" in rawTypes) mapped.add(TvType.TvSeries)
        if ("anime" in rawTypes) mapped.add(TvType.Anime)
        if ("tv" in rawTypes) mapped.add(TvType.Live)
        mapped.ifEmpty {
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)
        }
    }

    private val feedCatalogs: List<StremioCatalogDef> =
        addon.manifest.catalogs.filter { it.isFeedCatalog }

    private val searchCatalogs: List<StremioCatalogDef> =
        addon.manifest.catalogs.filter { it.supportsSearch }

    private val catalogSkipOffsets = ConcurrentHashMap<String, Int>()
    private val searchSkipOffsets = ConcurrentHashMap<String, Int>()

    override val mainPage: List<MainPageData> = feedCatalogs.map { cat ->
        val rowTitle = buildCatalogRowTitle(cat, feedCatalogs.size)
        MainPageData(
            name = rowTitle,
            data = "${cat.type}:::${cat.id}",
            horizontalImages = cat.type.equals("tv", ignoreCase = true) ||
                cat.type.equals("channel", ignoreCase = true),
        )
    }

    private fun buildCatalogRowTitle(cat: StremioCatalogDef, totalRows: Int): String {
        val cleanCatName = cat.name?.trim()?.takeIf { it.isNotEmpty() } ?: cat.id
        val prettyType = when (cat.type.lowercase()) {
            "movie" -> "Movies"
            "series" -> "Series"
            "anime" -> "Anime"
            "tv" -> "Live TV"
            "channel" -> "Channels"
            else -> cat.type.replaceFirstChar { it.uppercase() }
        }
        return if (totalRows > 1 && !cleanCatName.contains(prettyType, ignoreCase = true) &&
            !cleanCatName.contains(cat.type, ignoreCase = true)
        ) {
            "$cleanCatName · $prettyType"
        } else {
            cleanCatName
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val parts = request.data.split(":::", limit = 2)
        if (parts.size != 2) return null
        val catType = parts[0]
        val catId = parts[1]
        val catalogDef = feedCatalogs.firstOrNull { it.type == catType && it.id == catId }
            ?: StremioCatalogDef(type = catType, id = catId, name = request.name)

        if (page > 1 && !catalogDef.supportsSkip) {
            return newHomePageResponse(
                HomePageList(request.name, emptyList(), request.horizontalImages),
                hasNext = false,
            )
        }

        val offsetKey = "$catType:$catId"
        val skipOffset = if (page <= 1) 0 else (catalogSkipOffsets[offsetKey] ?: ((page - 1) * 20))
        val extra = if (skipOffset > 0) mapOf("skip" to skipOffset.toString()) else emptyMap()

        val metas = StremioTransport.fetchCatalog(addon, catType, catId, extra)
        if (metas.isNotEmpty()) {
            catalogSkipOffsets[offsetKey] = skipOffset + metas.size
        }
        val items = metas.map { meta -> metaToSearchResponse(meta) }
        val hasNext = catalogDef.supportsSkip && items.isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items, request.horizontalImages),
            hasNext = hasNext,
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !hasSearchSupport) {
            return newSearchResponseList(emptyList(), false)
        }

        val targetCatalogs = if (page <= 1) {
            searchCatalogs
        } else {
            searchCatalogs.filter { it.supportsSkip }
        }
        if (targetCatalogs.isEmpty()) {
            return newSearchResponseList(emptyList(), false)
        }

        val perCatalogResults = targetCatalogs.amap { cat ->
            val key = "$trimmed|${cat.type}:${cat.id}"
            val skip = if (page <= 1) 0 else (searchSkipOffsets[key] ?: ((page - 1) * 20))
            val extra = LinkedHashMap<String, String>().apply {
                put("search", trimmed)
                if (skip > 0 && cat.supportsSkip) put("skip", skip.toString())
            }
            val metas = runCatching {
                StremioTransport.fetchCatalog(addon, cat.type, cat.id, extra)
            }.getOrDefault(emptyList())
            if (metas.isNotEmpty()) {
                searchSkipOffsets[key] = skip + metas.size
            }
            cat to metas
        }

        val seenKeys = HashSet<String>()
        val combined = ArrayList<SearchResponse>()
        var anySupportsNext = false

        for ((cat, metas) in perCatalogResults) {
            if (cat.supportsSkip && metas.isNotEmpty()) {
                anySupportsNext = true
            }
            for (meta in metas) {
                val dedupKey = "${meta.type.lowercase()}:${meta.id}"
                if (seenKeys.add(dedupKey)) {
                    combined.add(metaToSearchResponse(meta))
                }
            }
        }

        return newSearchResponseList(combined, hasNext = anySupportsNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    private fun buildItemUrl(type: String, id: String): String {
        val encType = StremioTransport.encodeComponent(type)
        val encId = StremioTransport.encodeComponent(id)
        return "${mainUrl.trimEnd('/')}/stremio-item/$encType/$encId"
    }

    private fun parseItemUrl(url: String): Pair<String, String>? {
        val marker = "/stremio-item/"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val tail = url.substring(idx + marker.length).substringBefore("?").substringBefore("#")
        val slash = tail.indexOf('/')
        if (slash <= 0 || slash >= tail.length - 1) return null
        val type = URLDecoder.decode(tail.substring(0, slash), "UTF-8")
        val id = URLDecoder.decode(tail.substring(slash + 1), "UTF-8")
        return type to id
    }

    private fun metaToSearchResponse(meta: StremioMetaItem): SearchResponse {
        StremioAddonManager.cachePreviewMeta(meta)
        val itemUrl = buildItemUrl(meta.type, meta.id)
        val parsedYear = extractYear(meta.releaseInfo)
        val parsedScore = meta.imdbRating?.toDoubleOrNull()?.let { Score.from10(it) }
        val tvType = meta.toCloudStreamTvType()

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(meta.name, itemUrl, TvType.TvSeries) {
                this.posterUrl = meta.poster ?: meta.background
                this.year = parsedYear
                this.score = parsedScore
            }
            TvType.Anime -> newAnimeSearchResponse(meta.name, itemUrl, TvType.Anime) {
                this.posterUrl = meta.poster ?: meta.background
                this.year = parsedYear
                this.score = parsedScore
            }
            TvType.Live -> newLiveSearchResponse(meta.name, itemUrl, TvType.Live) {
                this.posterUrl = meta.poster ?: meta.logo ?: meta.background
            }
            else -> newMovieSearchResponse(meta.name, itemUrl, TvType.Movie) {
                this.posterUrl = meta.poster ?: meta.background
                this.year = parsedYear
                this.score = parsedScore
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (stremioType, metaId) = parseItemUrl(url)
            ?: throw ErrorLoadingException("Unrecognized Stremio item URL")

        val preview = StremioAddonManager.getCachedPreviewMeta(stremioType, metaId)
        val meta = StremioAddonManager.resolveFullMeta(
            sourceAddonId = addon.id,
            type = stremioType,
            id = metaId,
            fallbackPreview = preview,
        ) ?: throw ErrorLoadingException("Metadata not found for $metaId")

        val tvType = meta.toCloudStreamTvType()
        val resolvedImdbId = meta.resolveImdbId()
        val yearValue = extractYear(meta.releaseInfo)
        val actorNames = (meta.cast + meta.links.filter {
            it.category.equals("actor", ignoreCase = true) || it.category.equals("cast", ignoreCase = true)
        }.map { it.name }).distinct()
        val youtubeTrailers = meta.trailers.mapNotNull { t ->
            t.source?.takeIf { it.isNotBlank() }?.let { src ->
                if (src.startsWith("http")) src else "https://www.youtube.com/watch?v=$src"
            }
        }

        val isSeriesLike = tvType == TvType.TvSeries || tvType == TvType.Anime || meta.videos.size > 1

        if (tvType == TvType.Live && meta.videos.isEmpty()) {
            val payload = StremioJsonParser.encodeEpisodeData(
                StremioEpisodeData(
                    sourceAddonId = addon.id,
                    stremioType = meta.type,
                    metaId = meta.id,
                    videoId = meta.defaultVideoId ?: meta.id,
                    imdbId = resolvedImdbId,
                    title = meta.name,
                )
            )
            return newLiveStreamLoadResponse(meta.name, url, payload) {
                this.posterUrl = meta.poster ?: meta.logo
                this.backgroundPosterUrl = meta.background ?: meta.poster
                this.plot = meta.description
                this.tags = meta.genres
            }
        }

        if (isSeriesLike && meta.videos.isNotEmpty()) {
            val sortedVideos = meta.videos.sortedWith(
                compareBy<StremioVideoItem>(
                    { v -> v.season?.let { if (it == 0) Int.MAX_VALUE else it } ?: 1 },
                    { v -> v.episode ?: 0 },
                )
            )

            val episodes = sortedVideos.map { video ->
                val epPayload = StremioJsonParser.encodeEpisodeData(
                    StremioEpisodeData(
                        sourceAddonId = addon.id,
                        stremioType = meta.type,
                        metaId = meta.id,
                        videoId = video.id,
                        imdbId = resolvedImdbId,
                        title = video.title ?: meta.name,
                        season = video.season,
                        episode = video.episode,
                        embeddedStreams = video.streams.orEmpty(),
                    )
                )
                newEpisode(url = epPayload, initializer = {
                    this.name = video.title
                    this.season = video.season
                    this.episode = video.episode
                    this.posterUrl = video.thumbnail
                    this.description = video.overview
                    addDate(video.released)
                }, fix = false)
            }

            return newTvSeriesLoadResponse(meta.name, url, tvType, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background ?: meta.poster
                this.plot = meta.description
                this.year = yearValue
                this.tags = meta.genres
                addScore(meta.imdbRating, 10)
                addDuration(meta.runtime)
                addImdbId(resolvedImdbId)
                if (actorNames.isNotEmpty()) addActors(actorNames)
                if (youtubeTrailers.isNotEmpty()) addTrailer(youtubeTrailers)
            }
        }

        val singleVideo = meta.videos.firstOrNull()
        val movieVideoId = meta.defaultVideoId ?: singleVideo?.id ?: meta.id
        val moviePayload = StremioJsonParser.encodeEpisodeData(
            StremioEpisodeData(
                sourceAddonId = addon.id,
                stremioType = meta.type,
                metaId = meta.id,
                videoId = movieVideoId,
                imdbId = resolvedImdbId,
                title = meta.name,
                embeddedStreams = singleVideo?.streams.orEmpty(),
            )
        )

        return newMovieLoadResponse(meta.name, url, TvType.Movie, moviePayload) {
            this.posterUrl = meta.poster
            this.backgroundPosterUrl = meta.background ?: meta.poster
            this.plot = meta.description
            this.year = yearValue
            this.tags = meta.genres
            addScore(meta.imdbRating, 10)
            addDuration(meta.runtime)
            addImdbId(resolvedImdbId)
            if (actorNames.isNotEmpty()) addActors(actorNames)
            if (youtubeTrailers.isNotEmpty()) addTrailer(youtubeTrailers)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val epData = StremioJsonParser.decodeEpisodeData(data) ?: return false
        val streams = StremioAddonManager.queryStreamsLive(epData) { _, _ -> }
        if (streams.isEmpty()) return false
        for (resolved in streams) {
            StremioStreamLinkResolver.emitResolvedStream(
                resolved = resolved,
                episodeData = epData,
                subtitleCallback = { subtitleCallback(it) },
                callback = { callback(it) },
            )
        }
        return true
    }

    private fun extractYear(releaseInfo: String?): Int? {
        if (releaseInfo.isNullOrBlank()) return null
        return Regex("(\\d{4})").find(releaseInfo)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
