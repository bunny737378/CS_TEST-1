package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Player generator for a stream chosen on the dedicated Stremio stream list.
 * It keeps the ResultEpisode metadata while exposing only the selected ExtractorLink.
 */
class StremioStreamGenerator(
    episodes: List<ResultEpisode>,
    private val link: ExtractorLink,
    private val subtitles: List<SubtitleData>,
) : VideoGenerator<ResultEpisode>(episodes) {
    override val hasCache = false
    override val canSkipLoading = false
    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        subtitles.forEach(subtitleCallback)
        if (sourceTypes.contains(link.type)) {
            callback(link to null)
        }
        return true
    }
}
