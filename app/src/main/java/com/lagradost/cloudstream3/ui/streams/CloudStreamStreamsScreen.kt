package com.lagradost.cloudstream3.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.RoundedShape
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.WhiteFilterChip
import com.lagradost.cloudstream4.compose.WhiteIconButton
import com.lagradost.cloudstream4.compose.focusOutline
import com.mihon.material.AppBar
import com.mihon.material.Scaffold
import com.mihon.presentation.LocalBackPress
import java.util.Locale

private const val SIZE_GB = 1024L * 1024L * 1024L

private data class SizeBucket(
    val index: Long,
    val label: String,
    val minBytes: Long,
    val maxBytesExclusive: Long,
)

object CloudStreamStreamsScreen : Screen {
    @Composable
    override fun Content() {
        val state by CloudStreamStreamSession.state.collectAsState()
        val handleBack = LocalBackPress.current

        var selectedProvider by remember(state.sessionId) { mutableStateOf<String?>(null) }
        var selectedQuality by remember(state.sessionId) { mutableStateOf<String?>(null) }
        var selectedAudio by remember(state.sessionId) { mutableStateOf<String?>(null) }
        var selectedSize by remember(state.sessionId) { mutableStateOf<Long?>(null) }

        val providers = remember(state.candidates) {
            state.candidates.map { it.providerName }.distinct().sortedBy { it.lowercase() }
        }
        val qualities = remember(state.candidates) {
            state.candidates.mapNotNull { it.qualityLabel }.distinct().sortedWith(
                compareByDescending<String> { qualityRank(it) }.thenBy { it }
            )
        }
        val audioLanguages = remember(state.candidates) {
            state.candidates.flatMap { it.audioLanguages }.distinct().sortedBy { it.lowercase() }
        }
        val sizeBuckets = remember(state.candidates) { buildSizeBuckets(state.candidates) }

        val visibleCandidates = remember(
            state.candidates,
            selectedProvider,
            selectedQuality,
            selectedAudio,
            selectedSize,
            sizeBuckets,
        ) {
            state.candidates.filter { candidate ->
                (selectedProvider == null || candidate.providerName == selectedProvider) &&
                    (selectedQuality == null || candidate.qualityLabel == selectedQuality) &&
                    (selectedAudio == null || selectedAudio in candidate.audioLanguages) &&
                    (selectedSize == null || candidate.sizeBytes?.let { size ->
                        sizeBuckets.firstOrNull { it.index == selectedSize }?.let { bucket ->
                            size >= bucket.minBytes && size < bucket.maxBytesExclusive
                        }
                    } == true)
            }
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Select Stream",
                    navigateUp = handleBack,
                    actions = {
                        WhiteIconButton(
                            enabled = !state.isLoading,
                            onClick = { CloudStreamStreamSession.retry() },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh streams",
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                EpisodeInfoBanner(state)

                if (state.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onBackground,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                if (providers.size > 1) {
                    FilterRow(
                        title = "Provider",
                        allLabel = "All",
                        selected = selectedProvider,
                        options = providers,
                        onSelect = { selectedProvider = it },
                    )
                }
                if (qualities.isNotEmpty()) {
                    FilterRow(
                        title = "Quality",
                        allLabel = "All",
                        selected = selectedQuality,
                        options = qualities,
                        onSelect = { selectedQuality = it },
                    )
                }
                if (audioLanguages.isNotEmpty()) {
                    FilterRow(
                        title = "Audio",
                        allLabel = "All",
                        selected = selectedAudio,
                        options = audioLanguages,
                        onSelect = { selectedAudio = it },
                    )
                }
                if (sizeBuckets.isNotEmpty()) {
                    FilterRow(
                        title = "Size",
                        allLabel = "All",
                        selected = selectedSize?.let { index -> sizeBuckets.firstOrNull { it.index == index }?.label },
                        options = sizeBuckets.map { it.label },
                        onSelect = { label ->
                            selectedSize = if (selectedSize?.let { index ->
                                    sizeBuckets.firstOrNull { it.index == index }?.label
                                } == label
                            ) null else sizeBuckets.firstOrNull { it.label == label }?.index
                        },
                    )
                }

                if (providers.size > 1 || qualities.isNotEmpty() || audioLanguages.isNotEmpty() || sizeBuckets.isNotEmpty()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        thickness = 1.dp,
                    )
                }

                when {
                    state.isLoading && state.candidates.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = "Searching this provider for streams…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    visibleCandidates.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_outline_info_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(38.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = state.errorMessage
                                        ?: if (state.candidates.isEmpty()) "No streams available."
                                        else "No streams match the selected filters.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (state.candidates.isEmpty()) {
                                    BlackButton(
                                        text = "Retry",
                                        onClick = { CloudStreamStreamSession.retry() },
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = visibleCandidates,
                                key = { it.id },
                            ) { candidate ->
                                StreamCandidateCard(
                                    candidate = candidate,
                                    enabled = !state.isLoading,
                                    onClick = {
                                        val generator = CloudStreamStreamSession.createSelectedGenerator(candidate)
                                            ?: return@StreamCandidateCard
                                        activity?.navigate(
                                            R.id.global_to_navigation_player,
                                            GeneratorPlayer.newInstance(
                                                generator,
                                                state.episodeIndex,
                                                CloudStreamStreamSession.currentSyncData(),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EpisodeInfoBanner(state: CloudStreamStreamState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = state.episodeTitle.ifBlank { state.title },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (state.isLoading) "Loading available streams…" else "${state.candidates.size} stream(s) available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun FilterRow(
        title: String,
        allLabel: String,
        selected: String?,
        options: List<String>,
        onSelect: (String?) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$title:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WhiteFilterChip(
                selected = selected == null,
                label = allLabel,
                onClick = { onSelect(null) },
            )
            options.forEach { option ->
                WhiteFilterChip(
                    selected = selected == option,
                    label = option,
                    onClick = { onSelect(option) },
                )
            }
        }
    }

    @Composable
    private fun StreamCandidateCard(
        candidate: CloudStreamStreamCandidate,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedShape())
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .focusOutline()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.providerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val tags = buildList {
                candidate.qualityLabel?.let(::add)
                candidate.audioLanguages.forEach(::add)
                candidate.sizeBytes?.let { add(formatSize(it)) }
                add(candidate.typeLabel)
            }.distinct()
            if (tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tags.take(5).forEach { tag ->
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun buildSizeBuckets(candidates: List<CloudStreamStreamCandidate>): List<SizeBucket> {
    val sizes = candidates.mapNotNull { it.sizeBytes }.filter { it > 0L }
    if (sizes.isEmpty()) return emptyList()

    val minimum = sizes.minOrNull() ?: return emptyList()
    val groups = sizes.map { it / SIZE_GB }.distinct().sorted()
    return groups.map { group ->
        val minBytes = if (group == 0L) minimum else group * SIZE_GB
        val maxBytes = (group + 1L) * SIZE_GB
        val label = if (group == 0L) {
            "${formatSize(minimum)}–1 GB"
        } else {
            "$group–${group + 1L} GB"
        }
        SizeBucket(group, label, minBytes, maxBytes)
    }
}

private fun qualityRank(quality: String): Int {
    val normalized = quality.lowercase()
    return when {
        "4k" in normalized || "2160" in normalized -> 2160
        "1440" in normalized -> 1440
        "1080" in normalized -> 1080
        "720" in normalized -> 720
        "480" in normalized -> 480
        "360" in normalized -> 360
        "240" in normalized -> 240
        "auto" in normalized -> 0
        else -> -1
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (bytes < SIZE_GB) {
        if (mb < 10.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f MB", mb)
    } else {
        val gb = bytes.toDouble() / SIZE_GB.toDouble()
        String.format(Locale.US, "%.1f GB", gb).replace(".0 GB", " GB")
    }
}
