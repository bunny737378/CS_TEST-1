package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject

/**
 * Shared Stremio protocol adapter.
 *
 * StremioBridge registers one StremioAddonProvider per installed search-capable addon. All
 * providers delegate to this object so stream loading still aggregates every installed stream
 * addon, while search/home/result apiName stays attached to the individual addon provider.
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
            .flatMap(::providerMainPage)
            .take(16)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val key = parseJson(request.data) ?: return null
        val addon = findAddon(key.optString("a")) ?: return null
        return getMainPage(this, addon, page, request)
    }

    override suspend fun search(query: String): List<SearchResponse>? =
        searchAllAddons(this, query, 1)

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        newSearchResponseList(searchAllAddons(this, query, page), false)

    override suspend fun load(url: String): LoadResponse? {
        val key = parseJson(url) ?: return null
        val addon = findAddon(key.optString("a")) ?: return null
        return load(this, addon, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = loadLinksFromOptions(data, subtitleCallback, callback)

    // region Per-addon provider helpers

    fun providerMainPage(addon: StremioAddon): List<MainPageData> =
        addon.manifest.catalogs.mapNotNull { catalog ->
            if (catalog.type.isBlank() || catalog.id.isBlank()) return@mapNotNull null
            MainPageData(
                name = "${addon.manifest.name} — ${catalog.name ?: catalog.id}",
                data = pageKey(addon, catalog.type, catalog.id).toString(),
            )
        }

    suspend fun getMainPage(
        provider: MainAPI,
        addon: StremioAddon,
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val key = parseJson(request.data) ?: return null
        val transportUrl = key.optString("a").takeIf { it.isNotBlank() } ?: addon.transportUrl
        val type = key.optString("t")
        val catalogId = key.optString("i")
        if (type.isBlank() || catalogId.isBlank()) return null

        val resolvedAddon = findAddon(transportUrl) ?: addon
        val catalog = resolvedAddon.manifest.catalogs
            .firstOrNull { it.id == catalogId && it.type == type } ?: return null
        val results = StremioApi.fetchCatalog(resolvedAddon, catalog, page = page)
            .map { it.toSearchResponse(provider, resolvedAddon) }
        return newHomePageResponse(HomePageList(request.name, results), false)
    }

    suspend fun search(
        provider: MainAPI,
        addon: StremioAddon,
        query: String,
        page: Int,
    ): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val results = ArrayList<SearchResponse>()
        addon.manifest.catalogs
            .filter { it.supportsSearch }
            .forEach { catalog ->
                results.addAll(
                    StremioApi.fetchCatalog(addon, catalog, query, page)
                        .map { it.toSearchResponse(provider, addon) }
                )
            }
        return newSearchResponseList(results.distinctBy { it.url }, false)
    }

    suspend fun load(
        provider: MainAPI,
        addon: StremioAddon,
        url: String,
    ): LoadResponse? {
        val key = parseJson(url) ?: return null
        val transportUrl = key.optString("a").takeIf { it.isNotBlank() } ?: addon.transportUrl
        val type = key.optString("t")
        val id = key.optString("i")
        if (type.isBlank() || id.isBlank()) return null

        var resolvedAddon = findAddon(transportUrl) ?: addon
        var meta = StremioApi.fetchMeta(resolvedAddon, type, id)
        if (meta == null) {
            StremioRepository.getAddons()
                .filter { it.supports("meta", type, id) }
                .forEach { candidate ->
                    meta = StremioApi.fetchMeta(candidate, type, id)
                    if (meta != null) {
                        resolvedAddon = candidate
                        return@forEach
                    }
                }
        }
        if (meta == null) {
            StremioRepository.getAddons().forEach { candidate ->
                meta = StremioApi.fetchMeta(candidate, type, id)
                if (meta != null) {
                    resolvedAddon = candidate
                    return@forEach
                }
            }
        }
        val resolvedMeta = meta ?: return null
        val finalAddon = resolvedAddon

        val poster = StremioApi.resolveUrl(finalAddon, resolvedMeta.poster)
        val background = StremioApi.resolveUrl(finalAddon, resolvedMeta.background)
        val logo = StremioApi.resolveUrl(finalAddon, resolvedMeta.logo)
        val rating = Score.from10(resolvedMeta.imdbRating)
        val runtimeMinutes = parseRuntimeMinutes(resolvedMeta.runtime)
        val tags = resolvedMeta.genres +
            resolvedMeta.director.map { "Director: $it" } +
            resolvedMeta.writer.map { "Writer: $it" } +
            resolvedMeta.country
        val plot = listOfNotNull(
            resolvedMeta.description,
            resolvedMeta.awards?.let { "Awards: $it" },
        ).joinToString("\n\n").takeIf { it.isNotBlank() }
        val comingSoon = resolvedMeta.status.equals("upcoming", true) ||
            resolvedMeta.videos.any { it.upcoming }
        val isSeries = resolvedMeta.type.equals("series", true) || resolvedMeta.videos.isNotEmpty()

        return if (isSeries) {
            val episodes = resolvedMeta.videos.mapIndexed { index, video ->
                provider.newEpisode(
                    streamKey(finalAddon, resolvedMeta.type, resolvedMeta.id, video.id)
                ) {
                    this.name = video.name ?: "Episode ${index + 1}"
                    this.season = video.season ?: 1
                    this.episode = video.episode ?: (index + 1)
                    this.posterUrl = StremioApi.resolveUrl(finalAddon, video.thumbnail)
                    this.description = video.overview
                    this.addDate(video.released)
                }
            }
            provider.newTvSeriesLoadResponse(
                name = resolvedMeta.name,
                url = url,
                type = toTvType(resolvedMeta.type),
                episodes = episodes,
            ) {
                applyLoadMetadata(
                    poster = poster,
                    background = background,
                    logo = logo,
                    plot = plot,
                    year = resolvedMeta.year,
                    rating = rating,
                    tags = tags,
                    cast = resolvedMeta.cast,
                    contentRating = resolvedMeta.certification,
                    duration = runtimeMinutes,
                    comingSoon = comingSoon,
                )
                addImdbId(resolvedMeta.id.takeIf { it.startsWith("tt", true) })
            }
        } else {
            provider.newMovieLoadResponse(
                name = resolvedMeta.name,
                url = url,
                type = toTvType(resolvedMeta.type),
                dataUrl = streamKey(
                    finalAddon,
                    resolvedMeta.type,
                    resolvedMeta.id,
                    resolvedMeta.id,
                ),
            ) {
                applyLoadMetadata(
                    poster = poster,
                    background = background,
                    logo = logo,
                    plot = plot,
                    year = resolvedMeta.year,
                    rating = rating,
                    tags = tags,
                    cast = resolvedMeta.cast,
                    contentRating = resolvedMeta.certification,
                    duration = runtimeMinutes,
                    comingSoon = comingSoon,
                )
                addImdbId(resolvedMeta.id.takeIf { it.startsWith("tt", true) })
            }
        }
    }

    // endregion

    // region Streams / playback

    suspend fun loadStreamOptions(data: String): List<StremioStreamOption> {
        val key = parseJson(data) ?: return emptyList()
        val type = key.optString("t")
        val id = key.optString("i")
        val videoId = key.optString("v").takeIf { it.isNotBlank() } ?: id
        if (type.isBlank() || videoId.isBlank()) return emptyList()

        val addons = StremioRepository.getAddons()
        val streamAddons = addons.filter { it.supports("stream", type, videoId) }
        val subtitleAddons = addons.filter { it.supports("subtitles", type, videoId) }
        val addonSubtitles = subtitleAddons
            .flatMap { StremioApi.fetchSubtitles(it, type, videoId) }
            .distinctBy { it.url }

        val selectedLanguage = StremioSettings.getStreamLanguage()
        val preferredQuality = StremioSettings.getPreferredQuality()
        val options = ArrayList<StremioStreamOption>()
        var index = 0
        streamAddons.forEach { addon ->
            StremioApi.fetchStreams(addon, type, videoId).forEach { stream ->
                StremioStreamPresentation.buildOption(
                    addon = addon,
                    index = index++,
                    stream = stream,
                    addonSubtitles = addonSubtitles,
                    selectedLanguage = selectedLanguage,
                    preferredQuality = preferredQuality,
                )?.let(options::add)
            }
        }
        return StremioStreamPresentation.sortOptions(options.distinctBy { it.key })
    }

    suspend fun loadLinksFromOptions(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val options = loadStreamOptions(data)
        val seenSubtitles = HashSet<String>()
        options.forEach { option ->
            callback(option.toExtractorLink())
            option.subtitleFiles().forEach { subtitle ->
                if (seenSubtitles.add(subtitle.url)) subtitleCallback(subtitle)
            }
        }
        return options.isNotEmpty()
    }

    suspend fun loadStreamsInfo(data: String, episode: ResultEpisode): StremioStreamsInfo? {
        val key = parseJson(data) ?: return null
        val type = key.optString("t")
        val id = key.optString("i")
        if (type.isBlank() || id.isBlank()) return null
        val transportUrl = key.optString("a")
        val addons = StremioRepository.getAddons()
        var resolvedAddon = findAddon(transportUrl) ?: addons.firstOrNull() ?: return null
        var meta = StremioApi.fetchMeta(resolvedAddon, type, id)
        if (meta == null) {
            addons.filter { it.supports("meta", type, id) }.forEach { candidate ->
                meta = StremioApi.fetchMeta(candidate, type, id)
                if (meta != null) {
                    resolvedAddon = candidate
                    return@forEach
                }
            }
        }
        val resolvedMeta = meta ?: return null
        return StremioStreamsInfo.fromMeta(resolvedAddon, resolvedMeta, episode)
    }

    fun toTvType(type: String): TvType = when (type.lowercase()) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "cartoon" -> TvType.Cartoon
        "channel", "tv" -> TvType.Live
        "other" -> TvType.Others
        else -> TvType.Movie
    }

    // endregion

    // region Keys

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

    private fun streamKey(
        addon: StremioAddon,
        type: String,
        id: String,
        videoId: String,
    ): String = JSONObject()
        .put("a", addon.transportUrl)
        .put("t", type)
        .put("i", id)
        .put("v", videoId)
        .toString()

    private fun parseJson(value: String): JSONObject? = runCatching {
        JSONObject(value)
    }.getOrNull()

    private fun findAddon(transportUrl: String?): StremioAddon? {
        if (transportUrl.isNullOrBlank()) return null
        return StremioRepository.getAddons().firstOrNull {
            it.transportUrl == transportUrl
        } ?: StremioRepository.getAddons().firstOrNull {
            StremioApi.resourceBase(it.transportUrl) == StremioApi.resourceBase(transportUrl)
        }
    }

    private fun parseRuntimeMinutes(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        val hours = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hours > 0 || minutes > 0) return hours * 60 + minutes
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    private suspend fun searchAllAddons(
        provider: MainAPI,
        query: String,
        page: Int,
    ): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = ArrayList<SearchResponse>()
        StremioRepository.getAddons().forEach { addon ->
            addon.manifest.catalogs.filter { it.supportsSearch }.forEach { catalog ->
                results.addAll(
                    StremioApi.fetchCatalog(addon, catalog, query, page)
                        .map { it.toSearchResponse(provider, addon) }
                )
            }
        }
        return results.distinctBy { it.url }
    }

    private fun StremioMetaPreview.toSearchResponse(
        provider: MainAPI,
        addon: StremioAddon,
    ): SearchResponse {
        val url = dataUrl(addon, type, id)
        val cloudType = toTvType(type)
        val poster = StremioApi.resolveUrl(addon, poster)
        val rating = Score.from10(imdbRating)
        val responseId = id.hashCode()
        return when (cloudType) {
            TvType.TvSeries -> provider.newTvSeriesSearchResponse(
                name = name,
                url = url,
                type = cloudType,
                fix = false,
            ) {
                this.posterUrl = poster
                this.score = rating
                this.id = responseId
            }
            TvType.Anime -> provider.newAnimeSearchResponse(
                name = name,
                url = url,
                type = cloudType,
                fix = false,
            ) {
                this.posterUrl = poster
                this.score = rating
                this.id = responseId
            }
            TvType.Live -> provider.newLiveSearchResponse(
                name = name,
                url = url,
                type = cloudType,
                fix = false,
            ) {
                this.posterUrl = poster
                this.score = rating
                this.id = responseId
            }
            else -> provider.newMovieSearchResponse(
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
}

private fun LoadResponse.applyLoadMetadata(
    poster: String?,
    background: String?,
    logo: String?,
    plot: String?,
    year: Int?,
    rating: Score?,
    tags: List<String>,
    cast: List<String>,
    contentRating: String?,
    duration: Int?,
    comingSoon: Boolean,
) {
    this.posterUrl = poster
    this.backgroundPosterUrl = background
    this.logoUrl = logo
    this.plot = plot
    this.year = year
    this.score = rating
    this.tags = tags
    this.actors = cast.map { ActorData(Actor(it)) }
    this.contentRating = contentRating
    this.duration = duration
    this.comingSoon = comingSoon
}
