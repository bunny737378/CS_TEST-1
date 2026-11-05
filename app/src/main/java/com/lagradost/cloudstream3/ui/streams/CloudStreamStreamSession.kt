package com.lagradost.cloudstream3.ui.streams

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP
import com.lagradost.cloudstream3.ui.player.SelectedStreamVideoGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val GIGABYTE = 1024L * 1024L * 1024L

/** One playable source resolved from a regular CloudStream provider. */
data class CloudStreamStreamCandidate(
    val id: String,
    val link: VideoLink,
    val providerName: String,
    val title: String,
    val typeLabel: String,
    val qualityLabel: String?,
    val audioLanguages: List<String>,
    val sizeBytes: Long?,
)

data class CloudStreamStreamState(
    val sessionId: Long = 0L,
    val isLoading: Boolean = false,
    val episodeIndex: Int = 0,
    val title: String = "",
    val episodeTitle: String = "",
    val posterUrl: String? = null,
    val candidates: List<CloudStreamStreamCandidate> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Loads all in-app-playable links for the clicked CloudStream episode before opening the player.
 * The singleton keeps the generator and selected link in memory while the selector is on screen;
 * no provider URLs or headers are placed in a navigation Bundle.
 */
object CloudStreamStreamSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var generator: VideoGenerator<*>? = null
    private var loadResponse: LoadResponse? = null
    private var syncData: HashMap<String, String>? = null
    private var subtitles: Set<SubtitleData> = emptySet()

    private val _state = MutableStateFlow(CloudStreamStreamState())
    val state: StateFlow<CloudStreamStreamState> = _state.asStateFlow()

    fun startSession(
        generator: VideoGenerator<*>,
        initialIndex: Int,
        loadResponse: LoadResponse?,
        syncData: HashMap<String, String>? = null,
    ) {
        val index = initialIndex.coerceIn(0, (generator.videos.size - 1).coerceAtLeast(0))
        val selectedMeta = generator.videos.getOrNull(index) as? ResultEpisode
        this.generator = generator
        this.loadResponse = loadResponse
        this.syncData = syncData
        subtitles = emptySet()
        loadJob?.cancel()

        val episodeTitle = selectedMeta?.let { episode ->
            listOfNotNull(
                episode.season?.let { "Season $it" },
                episode.episode.takeIf { episode.season != null }?.let { "Episode $it" },
                episode.name?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { episode.headerName }
        }.orEmpty()

        _state.value = CloudStreamStreamState(
            sessionId = System.nanoTime(),
            isLoading = true,
            episodeIndex = index,
            title = loadResponse?.name ?: selectedMeta?.headerName.orEmpty().ifBlank { "Select Stream" },
            episodeTitle = episodeTitle,
            posterUrl = selectedMeta?.poster ?: loadResponse?.posterUrl,
        )

        loadJob = scope.launch {
            val lock = Any()
            val foundLinks = LinkedHashMap<String, VideoLink>()
            val foundSubtitles = LinkedHashSet<SubtitleData>()
            var loadError: Throwable? = null

            try {
                generator.generateLinks(
                    clearCache = true,
                    sourceTypes = LOADTYPE_INAPP,
                    callback = { pair ->
                        val key = streamKey(pair) ?: return@generateLinks
                        synchronized(lock) { foundLinks.putIfAbsent(key, pair) }
                    },
                    subtitleCallback = { subtitle ->
                        synchronized(lock) { foundSubtitles.add(subtitle) }
                    },
                    offset = index,
                    isCasting = false,
                )
            } catch (t: Throwable) {
                loadError = t
                logError(t)
            }

            val linksSnapshot: List<VideoLink>
            val subtitlesSnapshot: Set<SubtitleData>
            synchronized(lock) {
                linksSnapshot = foundLinks.values.toList()
                subtitlesSnapshot = foundSubtitles.toSet()
            }
            subtitles = subtitlesSnapshot

            val candidates = linksSnapshot.mapIndexed { candidateIndex, link ->
                toCandidate(candidateIndex, link)
            }
            _state.value = _state.value.copy(
                isLoading = false,
                candidates = candidates,
                errorMessage = when {
                    candidates.isNotEmpty() -> null
                    loadError != null -> loadError?.localizedMessage ?: "Unable to load streams."
                    else -> "No playable streams were found for this episode."
                },
            )
        }
    }

    fun retry() {
        val currentGenerator = generator ?: return
        val currentResponse = loadResponse
        val currentSyncData = syncData
        startSession(
            generator = currentGenerator,
            initialIndex = _state.value.episodeIndex,
            loadResponse = currentResponse,
            syncData = currentSyncData,
        )
    }

    fun createSelectedGenerator(candidate: CloudStreamStreamCandidate): VideoGenerator<*>? {
        val delegate = generator ?: return null
        @Suppress("UNCHECKED_CAST")
        val typedDelegate = delegate as VideoGenerator<Any>
        return SelectedStreamVideoGenerator(
            delegate = typedDelegate,
            selectedIndex = _state.value.episodeIndex,
            selectedLink = candidate.link,
            selectedSubtitles = subtitles,
            page = loadResponse,
        )
    }

    fun currentSyncData(): HashMap<String, String>? = syncData?.let(::HashMap)

    private fun streamKey(pair: VideoLink): String? {
        val link = pair.first
        if (link != null) return "${link.source}\u0000${link.name}\u0000${link.url}"
        val uri = pair.second ?: return null
        return "${uri.name}\u0000${uri.uri}\u0000${uri.id}"
    }

    private fun toCandidate(index: Int, pair: VideoLink): CloudStreamStreamCandidate {
        val link = pair.first
        val uri = pair.second
        val title = link?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri?.displayName?.takeIf { it.isNotBlank() }
            ?: uri?.name?.takeIf { it.isNotBlank() }
            ?: link?.url?.substringBefore('?')?.substringAfterLast('/')
            ?: "Stream ${index + 1}"
        val details = buildString {
            append(title)
            link?.url?.let { append(' ').append(it.substringBefore('?').substringAfterLast('/')) }
        }

        return CloudStreamStreamCandidate(
            id = streamKey(pair) ?: index.toString(),
            link = pair,
            providerName = link?.source?.trim()?.takeIf { it.isNotEmpty() }
                ?: uri?.name?.takeIf { it.isNotBlank() }
                ?: "Unknown provider",
            title = title,
            typeLabel = link?.type?.name ?: "FILE",
            qualityLabel = link?.let { getQualityLabel(it) },
            audioLanguages = link?.let { getAudioLanguages("${it.name} ${it.source}") }.orEmpty(),
            sizeBytes = link?.let { parseSizeBytes(details) },
        )
    }

    private fun getQualityLabel(link: ExtractorLink): String? {
        val title = listOf(link.name, link.source).joinToString(" ")
        val parsed = Regex("\\b(2160p|4k|uhd|1440p|1080p|720p|480p|360p)\\b", RegexOption.IGNORE_CASE)
            .find(title)?.value
        if (parsed != null) return if (parsed.equals("uhd", true) || parsed.equals("4k", true)) "4K" else parsed.lowercase()

        return Qualities.getStringByInt(link.quality)
            .takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
    }

    private fun getAudioLanguages(title: String): List<String> {
        val languageNames = listOf(
            "Arabic", "Bengali", "Chinese", "Dutch", "English", "French", "German", "Gujarati",
            "Hindi", "Indonesian", "Italian", "Japanese", "Kannada", "Korean", "Malayalam",
            "Mandarin", "Marathi", "Persian", "Polish", "Portuguese", "Punjabi", "Russian",
            "Spanish", "Swedish", "Tamil", "Telugu", "Thai", "Turkish", "Ukrainian", "Urdu", "Vietnamese",
        )
        val found = languageNames.filter { language ->
            Regex("\\b${Regex.escape(language)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)
        }.toMutableList()

        when {
            Regex("\\bdual[ -]?audio\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> found.add("Dual Audio")
            Regex("\\bmulti[ -]?audio\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> found.add("Multi Audio")
        }
        return found.distinctBy { it.lowercase() }
    }

    private fun parseSizeBytes(text: String): Long? {
        val match = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(TB|TIB|GB|GIB|MB|MIB|KB|KIB)\\b")
            .find(text) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "TB", "TIB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            "GB", "GIB" -> GIGABYTE.toDouble()
            "MB", "MIB" -> 1024.0 * 1024.0
            else -> 1024.0
        }
        return (amount * multiplier).toLong().takeIf { it > 0L }
    }
}
