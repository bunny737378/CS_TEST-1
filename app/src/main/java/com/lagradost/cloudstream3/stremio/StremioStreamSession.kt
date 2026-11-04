package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StremioEpisodeStreamsState(
    val episodeIndex: Int = 0,
    val episode: ResultEpisode? = null,
    val episodeData: StremioEpisodeData? = null,
    val showTitle: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val isLoading: Boolean = false,
    val allStreams: List<StremioResolvedStream> = emptyList(),
    val addonNamesWithStreams: List<String> = emptyList(),
    val selectedAddonFilter: String? = null, // null == "All"
    val errorMessage: String? = null,
) {
    val visibleStreams: List<StremioResolvedStream>
        get() = if (selectedAddonFilter == null) {
            allStreams
        } else {
            allStreams.filter { it.addonName == selectedAddonFilter }
        }
}

/**
 * Shared active session between `ResultViewModel2`, `StremioStreamsFragment`, and `GeneratorPlayer`
 * so switching episodes inside the player seamlessly returns to the Stream Selector screen
 * and loads streams for the newly chosen episode.
 */
object StremioStreamSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fetchJob: Job? = null

    var episodes: List<ResultEpisode> = emptyList()
        private set
    var loadResponse: LoadResponse? = null
        private set
    var syncData: HashMap<String, String>? = null
        private set

    private val _state = MutableStateFlow(StremioEpisodeStreamsState())
    val state: StateFlow<StremioEpisodeStreamsState> = _state.asStateFlow()

    fun startSession(
        episodes: List<ResultEpisode>,
        initialIndex: Int,
        loadResponse: LoadResponse?,
        syncData: HashMap<String, String>? = null,
    ) {
        this.episodes = episodes
        this.loadResponse = loadResponse
        this.syncData = syncData
        selectEpisode(initialIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)))
    }

    fun selectEpisode(index: Int, forceReload: Boolean = true) {
        val targetEpisode = episodes.getOrNull(index) ?: return
        val epData = StremioJsonParser.decodeEpisodeData(targetEpisode.data)

        if (!forceReload && _state.value.episodeIndex == index && _state.value.allStreams.isNotEmpty()) {
            return
        }

        fetchJob?.cancel()
        _state.value = StremioEpisodeStreamsState(
            episodeIndex = index,
            episode = targetEpisode,
            episodeData = epData,
            showTitle = loadResponse?.name ?: targetEpisode.headerName,
            posterUrl = loadResponse?.posterUrl ?: targetEpisode.poster,
            backdropUrl = loadResponse?.backgroundPosterUrl ?: loadResponse?.posterUrl,
            isLoading = true,
            allStreams = emptyList(),
            addonNamesWithStreams = emptyList(),
            selectedAddonFilter = null,
            errorMessage = null,
        )

        if (epData == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                errorMessage = "Missing Stremio metadata payload for this item.",
            )
            return
        }

        fetchJob = scope.launch {
            try {
                val collected = ArrayList<StremioResolvedStream>()
                val addonOrder = LinkedHashSet<String>()

                StremioAddonManager.queryStreamsLive(epData) { addonName, addonStreams ->
                    synchronized(collected) {
                        collected.addAll(addonStreams)
                        if (addonStreams.isNotEmpty()) {
                            addonOrder.add(addonName)
                        }
                        _state.value = _state.value.copy(
                            allStreams = collected.toList(),
                            addonNamesWithStreams = addonOrder.toList(),
                        )
                    }
                }

                val hasAnyStreamAddon = StremioAddonManager.addons.value.any {
                    it.enabled && it.supportsCapability(StremioAddonCapability.STREAM)
                }

                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = when {
                        collected.isNotEmpty() -> null
                        !hasAnyStreamAddon -> "No Stream addons installed or enabled. Add a Stremio stream addon in Settings → Addons."
                        else -> "No streams found from enabled Stremio addons."
                    },
                )
            } catch (t: Throwable) {
                logError(t)
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = t.localizedMessage ?: "Failed to load Stremio streams.",
                )
            }
        }
    }

    fun refreshCurrentEpisode() {
        selectEpisode(_state.value.episodeIndex, forceReload = true)
    }

    fun selectAddonFilter(addonName: String?) {
        _state.value = _state.value.copy(selectedAddonFilter = addonName)
    }
}
