package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot of native DetailSheet/StreamsSheet metadata passed to the dedicated
 * all-streams page. Kept as plain fields so it can travel through a Fragment Bundle.
 */
data class StremioStreamsInfo(
    val title: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val year: Int? = null,
    val runtime: String? = null,
    val rating: String? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val contentRating: String? = null,
    val releaseInfo: String? = null,
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episodeNumber: Int? = null,
    val episodeDescription: String? = null,
    val episodePoster: String? = null,
    val airDate: Long? = null,
    val episodeRunTime: Int? = null,
    val apiName: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        putIfNotNull("poster", poster)
        putIfNotNull("background", background)
        putIfNotNull("logo", logo)
        putIfNotNull("year", year)
        putIfNotNull("runtime", runtime)
        putIfNotNull("rating", rating)
        putIfNotNull("plot", plot)
        put("genres", JSONArray(genres))
        put("cast", JSONArray(cast))
        putIfNotNull("contentRating", contentRating)
        putIfNotNull("releaseInfo", releaseInfo)
        putIfNotNull("episodeTitle", episodeTitle)
        putIfNotNull("season", season)
        putIfNotNull("episodeNumber", episodeNumber)
        putIfNotNull("episodeDescription", episodeDescription)
        putIfNotNull("episodePoster", episodePoster)
        putIfNotNull("airDate", airDate)
        putIfNotNull("episodeRunTime", episodeRunTime)
        putIfNotNull("apiName", apiName)
    }.toString()

    fun withFallback(fallback: StremioStreamsInfo): StremioStreamsInfo = copy(
        title = title.ifBlank { fallback.title },
        poster = poster ?: fallback.poster,
        background = background ?: fallback.background,
        logo = logo ?: fallback.logo,
        year = year ?: fallback.year,
        runtime = runtime ?: fallback.runtime,
        rating = rating ?: fallback.rating,
        plot = plot ?: fallback.plot,
        genres = genres.ifEmpty { fallback.genres },
        cast = cast.ifEmpty { fallback.cast },
        contentRating = contentRating ?: fallback.contentRating,
        releaseInfo = releaseInfo ?: fallback.releaseInfo,
        episodeTitle = episodeTitle ?: fallback.episodeTitle,
        season = season ?: fallback.season,
        episodeNumber = episodeNumber ?: fallback.episodeNumber,
        episodeDescription = episodeDescription ?: fallback.episodeDescription,
        episodePoster = episodePoster ?: fallback.episodePoster,
        airDate = airDate ?: fallback.airDate,
        episodeRunTime = episodeRunTime ?: fallback.episodeRunTime,
        apiName = apiName ?: fallback.apiName,
    )

    companion object {
        fun fromJson(raw: String?): StremioStreamsInfo? {
            if (raw.isNullOrBlank()) return null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            return StremioStreamsInfo(
                title = json.optString("title").takeIf { it.isNotBlank() } ?: return null,
                poster = json.optString("poster").nullableString(),
                background = json.optString("background").nullableString(),
                logo = json.optString("logo").nullableString(),
                year = json.optInt("year", 0).takeIf { it > 0 },
                runtime = json.optString("runtime").nullableString(),
                rating = json.optString("rating").nullableString(),
                plot = json.optString("plot").nullableString(),
                genres = json.stringList("genres"),
                cast = json.stringList("cast"),
                contentRating = json.optString("contentRating").nullableString(),
                releaseInfo = json.optString("releaseInfo").nullableString(),
                episodeTitle = json.optString("episodeTitle").nullableString(),
                season = json.optInt("season", -1).takeIf { it >= 0 },
                episodeNumber = json.optInt("episodeNumber", -1).takeIf { it >= 0 },
                episodeDescription = json.optString("episodeDescription").nullableString(),
                episodePoster = json.optString("episodePoster").nullableString(),
                airDate = json.optLong("airDate", -1L).takeIf { it >= 0L },
                episodeRunTime = json.optInt("episodeRunTime", -1).takeIf { it >= 0 },
                apiName = json.optString("apiName").nullableString(),
            )
        }

        fun fromResponse(response: LoadResponse?, episode: ResultEpisode): StremioStreamsInfo =
            StremioStreamsInfo(
                title = response?.name?.takeIf { it.isNotBlank() }
                    ?: episode.headerName.ifBlank { episode.name.orEmpty() },
                poster = response?.posterUrl,
                background = response?.backgroundPosterUrl,
                logo = response?.logoUrl,
                year = response?.year,
                runtime = response?.duration?.takeIf { it > 0 }?.let { "$it min" },
                rating = response?.score?.toString(),
                plot = response?.plot,
                genres = response?.tags.orEmpty(),
                cast = response?.actors?.mapNotNull { it.actor.name }.orEmpty(),
                contentRating = response?.contentRating,
                episodeTitle = episode.name,
                season = episode.season,
                episodeNumber = episode.episode,
                episodeDescription = episode.description,
                episodePoster = episode.poster,
                airDate = episode.airDate,
                episodeRunTime = episode.runTime,
                apiName = response?.apiName,
            )

        fun fromMeta(
            addon: StremioAddon,
            meta: StremioMeta,
            episode: ResultEpisode,
        ): StremioStreamsInfo {
            val videoId = runCatching { JSONObject(episode.data).optString("v") }.getOrNull()
            val video = meta.videos.firstOrNull { it.id == videoId }
            return StremioStreamsInfo(
            title = meta.name,
            poster = StremioApi.resolveUrl(addon, meta.poster),
            background = StremioApi.resolveUrl(addon, meta.background),
            logo = StremioApi.resolveUrl(addon, meta.logo),
            year = meta.year,
            runtime = meta.runtime,
            rating = meta.imdbRating,
            plot = meta.description,
            genres = meta.genres,
            cast = meta.cast,
            contentRating = meta.certification,
            releaseInfo = meta.releaseInfo ?: meta.released,
            episodeTitle = episode.name,
            season = episode.season,
            episodeNumber = episode.episode,
            episodeDescription = episode.description ?: video?.overview,
            episodePoster = StremioApi.resolveUrl(addon, episode.poster),
            airDate = episode.airDate,
            episodeRunTime = episode.runTime,
            )
        }

        private fun String.nullableString(): String? =
            takeIf { it.isNotBlank() && !it.equals("null", true) }

        private fun JSONObject.stringList(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).nullableString()
            }
        }
    }
}

private fun JSONObject.putIfNotNull(key: String, value: Any?) {
    if (value == null) remove(key) else put(key, value)
}
