package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StremioVideoGenerator(
    episodes: List<ResultEpisode>,
    val selectedStream: StremioResolvedStream,
    val episodeData: StremioEpisodeData?,
    val page: LoadResponse? = null,
) : VideoGenerator<ResultEpisode>(episodes) {

    override val hasCache: Boolean = false
    override val canSkipLoading: Boolean = false

    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var subtitleFetchJob: Job? = null

    private val _subtitleAddons = MutableStateFlow<List<StremioSubtitleAddonResult>>(emptyList())
    val subtitleAddons: StateFlow<List<StremioSubtitleAddonResult>> = _subtitleAddons.asStateFlow()

    override fun getId(index: Int): Int? {
        return videos.getOrNull(index)?.id
    }

    fun ensureSubtitlesLoaded(): List<StremioSubtitleAddonResult> {
        val epData = episodeData ?: return emptyList()
        if (_subtitleAddons.value.isEmpty()) {
            val enabledSubAddons = StremioAddonManager.addons.value.filter {
                it.enabled && it.supportsCapability(StremioAddonCapability.SUBTITLES)
            }
            if (enabledSubAddons.isNotEmpty()) {
                _subtitleAddons.value = enabledSubAddons.map { addon ->
                    StremioSubtitleAddonResult(
                        addonId = addon.id,
                        addonName = addon.name,
                        subtitles = emptyList(),
                        isLoading = true,
                    )
                }
            }
        }
        if (subtitleFetchJob?.isActive == true ||
            (_subtitleAddons.value.isNotEmpty() && _subtitleAddons.value.none { it.isLoading })
        ) {
            return _subtitleAddons.value
        }
        val hints = selectedStream.stream.behaviorHints
        subtitleFetchJob = subtitleScope.launch {
            runCatching {
                StremioAddonManager.querySubtitleAddons(
                    episodeData = epData,
                    videoHash = hints.videoHash,
                    videoSize = hints.videoSize,
                    filename = hints.filename,
                    onProgress = { updated ->
                        _subtitleAddons.value = updated
                    },
                )
            }.onFailure { logError(it) }
        }
        return _subtitleAddons.value
    }

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        ensureSubtitlesLoaded()

        var emittedAny = false
        StremioStreamLinkResolver.emitResolvedStream(
            resolved = selectedStream,
            episodeData = episodeData,
            subtitleCallback = { subFile ->
                subtitleCallback(
                    SubtitleData(
                        originalName = subFile.lang,
                        nameSuffix = "",
                        url = subFile.url,
                        origin = SubtitleOrigin.URL,
                        mimeType = subFile.url.toSubtitleMimeType(),
                        headers = subFile.headers ?: emptyMap(),
                        languageCode = subFile.lang,
                    )
                )
            },
            callback = { link ->
                emittedAny = true
                callback(link to null)
            },
        )
        return emittedAny
    }
}
