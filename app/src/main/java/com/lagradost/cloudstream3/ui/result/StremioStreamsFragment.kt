package com.lagradost.cloudstream3.ui.result

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.stremio.StremioLanguageCatalog
import com.lagradost.cloudstream3.stremio.StremioProvider
import com.lagradost.cloudstream3.stremio.StremioSettings
import com.lagradost.cloudstream3.stremio.StremioStreamOption
import com.lagradost.cloudstream3.stremio.StremioStreamPresentation
import com.lagradost.cloudstream3.stremio.StremioStreamSortCriterion
import com.lagradost.cloudstream3.stremio.StremioStreamsInfo
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.StremioStreamGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native-style Stremio detail + all-streams picker before CloudStream playback. */
class StremioStreamsFragment : BaseComposeFragment() {
    @Composable
    override fun Content() {
        StremioStreamsContent()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.stremio_streams)
    }

    @Composable
    private fun StremioStreamsContent() {
        val args = remember { arguments }
        val episode = remember(args) {
            args?.getString("episode")?.let { json ->
                runCatching { parseJson<ResultEpisode>(json) }.getOrNull()
            }
        }
        val initialInfo = remember(args) {
            StremioStreamsInfo.fromJson(args?.getString("info"))
        }
        val loadError = stringResource(R.string.stremio_streams_error)
        val scope = rememberCoroutineScope()

        var info by remember { mutableStateOf(initialInfo) }
        var options by remember { mutableStateOf(emptyList<StremioStreamOption>()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedProvider by remember { mutableStateOf<String?>(null) }
        var sortCriterion by remember { mutableStateOf(StremioSettings.getSortCriterion()) }
        val preferredQuality = remember { StremioSettings.getPreferredQuality() }

        LaunchedEffect(episode) {
            if (episode == null) {
                loading = false
                error = null
                return@LaunchedEffect
            }
            loading = true
            error = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val streamOptions = StremioProvider.loadStreamOptions(episode.data)
                    val fallback = if (
                        initialInfo == null ||
                        initialInfo.poster == null ||
                        initialInfo.background == null ||
                        initialInfo.plot == null
                    ) {
                        StremioProvider.loadStreamsInfo(episode.data, episode)
                    } else {
                        null
                    }
                    streamOptions to fallback
                }
                options = loaded.first
                loaded.second?.let { fallback ->
                    info = initialInfo?.withFallback(fallback) ?: fallback
                }
            } catch (t: Throwable) {
                error = t.message?.takeIf { it.isNotBlank() } ?: loadError
            } finally {
                loading = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun syncData(): HashMap<String, String> =
            args?.getSerializable("syncData") as? HashMap<String, String> ?: HashMap()

        suspend fun play(option: StremioStreamOption) {
            val selectedEpisode = episode ?: return
            val link = option.toExtractorLink()
            val subtitleData: List<SubtitleData> = option.subtitleFiles()
                .map { PlayerSubtitleHelper.getSubtitleData(it) }
            val generator = StremioStreamGenerator(
                episodes = listOf(selectedEpisode),
                link = link,
                subtitles = subtitleData,
            )
            withContext(Dispatchers.Main) {
                activity?.navigate(
                    R.id.global_to_navigation_player,
                    GeneratorPlayer.newInstance(generator, 0, syncData())
                )
            }
        }

        val providerChips = remember(options) {
            listOf("All") + options.map { it.addonTitle }.distinct()
        }
        val visibleOptions = remember(options, selectedProvider, sortCriterion, preferredQuality) {
            val filtered = options.filter { option ->
                selectedProvider == null || option.addonTitle == selectedProvider
            }
            StremioStreamPresentation.sortOptions(filtered, sortCriterion, preferredQuality)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                StremioHero(info)
            }
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    info?.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                        Text(
                            text = plot,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 5,
                        )
                    }
                    val detailLine = listOfNotNull(
                        info?.genres?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        info?.cast?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        info?.contentRating,
                    ).joinToString("  |  ")
                    if (detailLine.isNotBlank()) {
                        Text(
                            text = detailLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                var sortExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { sortExpanded = true }) {
                        Text("Sort: ${sortCriterion.label}")
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                    ) {
                        StremioStreamSortCriterion.entries.forEach { criterion ->
                            DropdownMenuItem(
                                text = { Text(criterion.label) },
                                onClick = {
                                    sortCriterion = criterion
                                    StremioSettings.setSortCriterion(criterion)
                                    sortExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providerChips.forEach { chip ->
                        val selected = if (chip == "All") {
                            selectedProvider == null
                        } else {
                            selectedProvider == chip
                        }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedProvider = chip.takeUnless { it == "All" }
                            },
                            label = { Text(chip) },
                        )
                    }
                }
            }
            when {
                loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                episode == null -> item {
                    Text(
                        text = stringResource(R.string.stremio_streams_error),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error != null -> item {
                    Text(
                        text = error.orEmpty(),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                visibleOptions.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.stremio_streams_empty),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    item {
                        Text(
                            text = stringResource(R.string.stremio_streams_found) +
                                " (${visibleOptions.size})",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(visibleOptions, key = { it.key }) { option ->
                        StremioStreamCard(option) {
                            scope.launch { play(option) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StremioHero(info: StremioStreamsInfo?) {
    val backgroundUrl = info?.background ?: info?.poster ?: info?.episodePoster
    val metadata = listOfNotNull(
        info?.runtime,
        info?.year?.toString(),
        info?.rating?.let { "$it IMDb" },
        info?.releaseInfo,
    ).joinToString(" · ")
    val seasonPart = info?.season?.let { "S${it.toString().padStart(2, '0')}" }
    val episodePart = info?.episodeNumber?.let { "E${it.toString().padStart(2, '0')}" }
    val episodeLabel = listOfNotNull(seasonPart, episodePart).joinToString("") +
        (info?.episodeTitle?.let { " – $it" } ?: "")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color(0xFF0B0E13)),
    ) {
        AsyncImage(
            model = backgroundUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC0B0E13), Color(0xFF0B0E13))
                    )
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AsyncImage(
                model = info?.poster ?: info?.episodePoster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(104.dp)
                    .height(156.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                info?.logo?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(42.dp)
                            .fillMaxWidth(),
                    )
                } ?: Text(
                    text = info?.title.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
                if (episodeLabel.isNotBlank()) {
                    Text(
                        text = episodeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
                info?.episodeDescription?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD0D0D0),
                        maxLines = 3,
                    )
                }
            }
        }
    }
}

@Composable
private fun StremioStreamCard(
    option: StremioStreamOption,
    onPlay: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF4CAF50), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", color = Color.White)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Badge(option.quality ?: "Auto")
                    Badge(option.addonTitle)
                }
                Text(
                    text = option.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                option.cleanDescription?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "👤 ${option.seeds ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "💾 ${option.size ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "⚙ ${option.origin ?: option.addonTitle}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val flags = StremioLanguageCatalog.flagLabel(option.languageCodes)
                        .ifBlank { StremioLanguageCatalog.textFlags(option.description) }
                    if (flags.isNotBlank()) {
                        Text(flags, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
