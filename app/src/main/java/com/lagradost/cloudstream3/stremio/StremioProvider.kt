package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Internal CloudStream provider backed by installed Stremio addons.
 *
 * It plugs into the existing home, search, result and player flows without changing CloudStream's
 * UI. Stremio streams are exposed as regular ExtractorLinks, so playback still goes through the
 * CloudStream player and its source-selection UI.
 */
object StremioProvider : MainAPI() {
    override var name = "Stremio"
    override var mainUrl = "https://api.strem.io"
    override val hasMainPage = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L
    override var sequentialMainPageScrollDelay = 100L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Live,
        TvType.Others,
    )

    override val mainPage: List<MainPageData>
        get() = StremioRepository.getAddons()
            .flatMap { addon ->
                addon.manifest.catalogs.take(4).mapNotNull { catalog ->
                    if (catalog.type.isBlank() || catalog.id.isBlank()) return@mapNotNull null
                    MainPageData(
                        name = "${addon.manifest.name} — ${catalog.name ?: catalog.id}",
                        data = pageKey(addon, catalog.type, catalog.id).toString(),
                    )
                }
            }
            .take(16)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val key = parseJson(request.data) ?: return null
        val transportUrl = key.optString("a")
        val type = key.optString("t")
        val catalogId = key.optString("i")
        if (transportUrl.isBlank() || type.isBlank() || catalogId.isBlank()) return null

        val addon = StremioRepository.getAddons()
            .firstOrNull { it.transportUrl == transportUrl } ?: return null
        val catalog = addon.manifest.catalogs
            .firstOrNull { it.id == catalogId && it.type == type } ?: return null
        val results = StremioApi.fetchCatalog(addon, catalog, page = page)
            .map { it.toSearchResponse(addon) }
        return newHomePageResponse(HomePageList(request.name, results), false)
    }

    override suspend fun search(query: String): List<SearchResponse>? =
        searchAddons(query, 1)

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        newSearchResponseList(searchAddons(query, page), false)

    private suspend fun searchAddons(query: String, page: Int): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = ArrayList<SearchResponse>()
        StremioRepository.getAddons().take(8).forEach { addon ->
            addon.manifest.catalogs
                .filter { it.supportsSearch }
                .take(2)
                .forEach { catalog ->
                    results.addAll(
                        StremioApi.fetchCatalog(addon, catalog, query, page)
                            .map { it.toSearchResponse(addon) }
                    )
                }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val key = parseJson(url) ?: return null
        val transportUrl = key.optString("a")
        val type = key.optString("t")
        val id = key.optString("i")
        if (transportUrl.isBlank() || type.isBlank() || id.isBlank()) return null

        val addon = StremioRepository.getAddons()
            .firstOrNull { it.transportUrl == transportUrl } ?: return null
        val meta = StremioApi.fetchMeta(addon, type, id) ?: return null
        val cloudType = toTvType(meta.type)

        val poster = StremioApi.resolveUrl(addon, meta.poster)
        val background = StremioApi.resolveUrl(addon, meta.background)
        val logo = StremioApi.resolveUrl(addon, meta.logo)
        val rating = Score.from10(meta.imdbRating)
        val isSeries = meta.type.equals("series", true) || meta.videos.isNotEmpty()

        return if (isSeries) {
            val episodes = meta.videos.mapIndexed { index, video ->
                newEpisode(streamKey(meta.type, meta.id, video.id)) {
                    this.name = video.name ?: "Episode ${index + 1}"
                    this.season = video.season ?: 1
                    this.episode = video.episode ?: (index + 1)
                    this.posterUrl = StremioApi.resolveUrl(addon, video.thumbnail)
                    this.description = video.overview
                    this.addDate(video.released)
                }
            }
            newTvSeriesLoadResponse(
                name = meta.name,
                url = url,
                type = cloudType,
                episodes = episodes,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.plot = meta.description
                this.year = meta.year
                this.tags = meta.genres
                this.score = rating
                this.contentRating = meta.certification
                addImdbId(meta.id.takeIf { it.startsWith("tt", true) })
            }
        } else {
            newMovieLoadResponse(
                name = meta.name,
                url = url,
                type = cloudType,
                dataUrl = streamKey(meta.type, meta.id, meta.id),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.plot = meta.description
                this.year = meta.year
                this.tags = meta.genres
                this.score = rating
                this.contentRating = meta.certification
                addImdbId(meta.id.takeIf { it.startsWith("tt", true) })
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val key = parseJson(data) ?: return false
        val type = key.optString("t")
        val id = key.optString("i")
        val videoId = key.optString("v").takeIf { it.isNotBlank() } ?: id
        if (type.isBlank() || videoId.isBlank()) return false

        var found = false
        StremioRepository.getAddons()
            .filter { it.supports("stream", type, videoId) }
            .forEach { addon ->
                StremioApi.fetchStreams(addon, type, videoId).forEach { stream ->
                    val linkUrl = streamPlaybackUrl(addon, stream) ?: return@forEach
                    val linkType = when {
                        linkUrl.startsWith("magnet:", true) -> ExtractorLinkType.MAGNET
                        linkUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                        linkUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                        else -> INFER_TYPE
                    }
                    val link = newExtractorLink(
                        source = addon.manifest.name,
                        name = stream.name ?: stream.title ?: addon.manifest.name,
                        url = linkUrl,
                        type = linkType,
                    ) {
                        this.headers = stream.headers
                    }
                    callback(link)
                    stream.subtitles.forEach { subtitle ->
                        subtitleCallback(
                            newSubtitleFile(
                                subtitle.lang,
                                StremioApi.resolveUrl(addon, subtitle.url) ?: subtitle.url,
                            )
                        )
                    }
                    found = true
                }
            }
        return found
    }

    private fun streamPlaybackUrl(
        addon: StremioAddon,
        stream: com.lagradost.cloudstream3.stremio.StremioStream,
    ): String? {
        stream.url?.let { return StremioApi.resolveUrl(addon, it) ?: it }
        stream.infoHash?.let {
            return "magnet:?xt=urn:btih:$it" +
                (stream.fileIdx?.let { index -> "&so=$index" } ?: "")
        }
        stream.ytId?.let { return "https://www.youtube.com/watch?v=$it" }
        return null
    }

    private fun StremioMetaPreview.toSearchResponse(addon: StremioAddon): SearchResponse {
        val url = dataUrl(addon, type, id)
        val cloudType = toTvType(type)
        val poster = StremioApi.resolveUrl(addon, poster)
        val rating = Score.from10(imdbRating)
        val responseId = id.hashCode()
        return when (cloudType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(
                name = name,
                url = url,
                type = cloudType,
                fix = false,
            ) {
                this.posterUrl = poster
                this.score = rating
                this.id = responseId
            }
            TvType.Anime -> newAnimeSearchResponse(
                name = name,
                url = url,
                type = cloudType,
                fix = false,
            ) {
                this.posterUrl = poster
                this.score = rating
                this.id = responseId
            }
            TvType.Live -> newLiveSearchResponse(
                name = name,
                url = url,
                type = cloudType,
                fix = false,
            ) {
                this.posterUrl = poster
                this.score = rating
                this.id = responseId
            }
            else -> newMovieSearchResponse(
                name = name,
                url = url,
                type = cloudType,
                fix = false,
            ) {
                this.posterUrl = poster
                this.score = rating
                this.id = responseId
            }
        }
    }

    private fun toTvType(type: String): TvType = when (type.lowercase()) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "cartoon" -> TvType.Cartoon
        "channel", "tv" -> TvType.Live
        "other" -> TvType.Others
        else -> TvType.Movie
    }

    private fun dataUrl(addon: StremioAddon, type: String, id: String): String =
        JSONObject()
            .put("a", addon.transportUrl)
            .put("t", type)
            .put("i", id)
            .toString()

    private fun pageKey(addon: StremioAddon, type: String, catalogId: String): JSONObject =
        JSONObject()
            .put("a", addon.transportUrl)
            .put("t", type)
            .put("i", catalogId)

    private fun streamKey(type: String, id: String, videoId: String): String =
        JSONObject()
            .put("t", type)
            .put("i", id)
            .put("v", videoId)
            .toString()

    private fun parseJson(value: String): JSONObject? = runCatching {
        JSONObject(value)
    }.getOrNull()
}
