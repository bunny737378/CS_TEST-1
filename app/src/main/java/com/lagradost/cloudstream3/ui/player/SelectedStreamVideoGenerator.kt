package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * A player generator that locks the episode chosen on the stream-selection page to the exact
 * link the user tapped. Other episodes continue to use the original provider generator.
 */
class SelectedStreamVideoGenerator(
    private val delegate: VideoGenerator<Any>,
    private val selectedIndex: Int,
    private val selectedLink: VideoLink,
    private val selectedSubtitles: Set<SubtitleData>,
    val page: LoadResponse?,
) : VideoGenerator<Any>(delegate.videos) {
    override val hasCache: Boolean = delegate.hasCache
    override val canSkipLoading: Boolean = delegate.canSkipLoading

    override fun getId(index: Int): Int? = delegate.getId(index)

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<com.lagradost.cloudstream3.utils.ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        if (offset == selectedIndex) {
            selectedSubtitles.forEach(subtitleCallback)
            val link = selectedLink.first
            if (link == null || link.type in sourceTypes) {
                callback(selectedLink)
            }
            return true
        }

        return delegate.generateLinks(
            clearCache = clearCache,
            sourceTypes = sourceTypes,
            callback = callback,
            subtitleCallback = subtitleCallback,
            offset = offset,
            isCasting = isCasting,
        )
    }
}
