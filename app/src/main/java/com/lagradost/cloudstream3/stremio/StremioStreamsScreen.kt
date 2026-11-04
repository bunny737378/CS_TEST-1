package com.lagradost.cloudstream3.stremio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.CloudStreamApp.Companion.openBrowser
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

object StremioStreamsScreen : Screen {

    @Composable
    override fun Content() {
        val state by StremioStreamSession.state.collectAsState()
        val handleBack = LocalBackPress.current
        val context = LocalContext.current

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { scrollBehavior ->
                AppBar(
                    title = state.showTitle.ifBlank { "Select Stream" },
                    navigateUp = handleBack,
                    actions = {
                        WhiteIconButton(
                            enabled = !state.isLoading,
                            onClick = { StremioStreamSession.refreshCurrentEpisode() }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh streams",
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Clean Episode / Media Context Header
                EpisodeInfoBanner(state = state)

                if (state.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onBackground,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                // Addon Filter Bar: "All" first, followed by each addon that returned streams
                if (state.addonNamesWithStreams.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WhiteFilterChip(
                            selected = state.selectedAddonFilter == null,
                            label = "All (${state.allStreams.size})",
                            onClick = { StremioStreamSession.selectAddonFilter(null) },
                        )

                        state.addonNamesWithStreams.forEach { addonName ->
                            val count = state.allStreams.count { it.addonName == addonName }
                            WhiteFilterChip(
                                selected = state.selectedAddonFilter == addonName,
                                label = "$addonName ($count)",
                                onClick = { StremioStreamSession.selectAddonFilter(addonName) },
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        thickness = 1.dp,
                    )
                }

                val visibleStreams = state.visibleStreams

                when {
                    visibleStreams.isEmpty() && state.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
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
                                    text = "Fetching streams from addons...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    visibleStreams.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
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
                                    text = state.errorMessage ?: "No streams available.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                BlackButton(
                                    text = "Retry",
                                    onClick = { StremioStreamSession.refreshCurrentEpisode() },
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(
                                items = visibleStreams,
                                key = { idx, item ->
                                    "${item.addonId}:${idx}:${item.stream.infoHash ?: item.stream.url ?: item.stream.ytId ?: item.stream.externalUrl}"
                                }
                            ) { _, resolvedStream ->
                                StremioStreamCard(
                                    resolved = resolvedStream,
                                    onClick = {
                                        val s = resolvedStream.stream
                                        if (resolvedStream.sourceType == StremioStreamSourceType.EXTERNAL &&
                                            s.url.isNullOrBlank() &&
                                            s.infoHash.isNullOrBlank() &&
                                            s.ytId.isNullOrBlank() &&
                                            !s.externalUrl.isNullOrBlank()
                                        ) {
                                            openBrowser(s.externalUrl)
                                        } else {
                                            val generator = StremioVideoGenerator(
                                                episodes = StremioStreamSession.episodes,
                                                selectedStream = resolvedStream,
                                                episodeData = state.episodeData,
                                                page = StremioStreamSession.loadResponse,
                                            )
                                            activity?.navigate(
                                                R.id.global_to_navigation_player,
                                                GeneratorPlayer.newInstance(
                                                    generator,
                                                    state.episodeIndex,
                                                    StremioStreamSession.syncData,
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EpisodeInfoBanner(state: StremioEpisodeStreamsState) {
        val ep = state.episode
        val seasonNum = ep?.season ?: state.episodeData?.season
        val episodeNum = (ep?.episode?.takeIf { it > 0 }) ?: state.episodeData?.episode
        val epTitle = ep?.name?.takeIf { it.isNotBlank() && it != state.showTitle }

        val subtitleLine = buildString {
            if (seasonNum != null && episodeNum != null) {
                append("Season $seasonNum · Episode $episodeNum")
                if (!epTitle.isNullOrBlank()) {
                    append(" — $epTitle")
                }
            } else if (episodeNum != null) {
                append("Episode $episodeNum")
                if (!epTitle.isNullOrBlank()) {
                    append(" — $epTitle")
                }
            } else if (!epTitle.isNullOrBlank()) {
                append(epTitle)
            } else {
                append("Select a stream to start playback")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val previewImage = ep?.poster ?: state.posterUrl
            if (!previewImage.isNullOrBlank()) {
                AsyncImage(
                    model = previewImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = subtitleLine,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val statusText = when {
                    state.isLoading && state.allStreams.isEmpty() -> "Searching enabled stream addons..."
                    state.isLoading -> "${state.allStreams.size} stream(s) found · still loading..."
                    else -> "${state.allStreams.size} stream(s) available from ${state.addonNamesWithStreams.size} addon(s)"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun StremioStreamCard(
        resolved: StremioResolvedStream,
        onClick: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedShape())
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .focusOutline()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!resolved.addonLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = resolved.addonLogo,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }

                Text(
                    text = resolved.headerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                resolved.qualityBadge?.let { q ->
                    StreamTagPill(
                        text = q,
                        inverted = true,
                    )
                }

                StreamTagPill(
                    text = resolved.sourceType.badgeLabel,
                    inverted = false,
                )
            }

            Text(
                text = resolved.primaryTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            resolved.secondaryDetails?.let { details ->
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (resolved.stream.subtitles.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.closed_caption_24px),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${resolved.stream.subtitles.size} embedded subtitle(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun StreamTagPill(
        text: String,
        inverted: Boolean,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (inverted) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.background
                    }
                )
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (inverted) {
                    MaterialTheme.colorScheme.background
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
        }
    }
}
