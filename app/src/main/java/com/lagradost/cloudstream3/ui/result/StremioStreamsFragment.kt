package com.lagradost.cloudstream3.ui.result

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.stremio.StremioProvider
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.StremioStreamGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dedicated all-streams picker for Stremio results before CloudStream playback. */
class StremioStreamsFragment : BaseComposeFragment() {
    @Composable
    override fun Content() {
        StremioStreamsContent()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.stremio_streams)
    }
}

@Composable
private fun StremioStreamsContent() {
    val args = remember { arguments }
    val episode = remember(args) {
        args?.getString("episode")?.let { json ->
            runCatching { parseJson<ResultEpisode>(json) }.getOrNull()
        }
    }
    val title = remember(args) {
        args?.getString("title").orEmpty()
    }
    val loadError = stringResource(R.string.stremio_streams_error)
    var links by remember { mutableStateOf(emptyList<ExtractorLink>()) }
    var subtitles by remember { mutableStateOf(emptyList<SubtitleData>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

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
                val foundLinks = ArrayList<ExtractorLink>()
                val foundSubs = ArrayList<SubtitleData>()
                StremioProvider.loadLinks(
                    data = episode.data,
                    isCasting = false,
                    subtitleCallback = { file ->
                        foundSubs.add(PlayerSubtitleHelper.getSubtitleData(file))
                    },
                    callback = { link -> foundLinks.add(link) },
                )
                foundLinks.distinctBy { it.url } to foundSubs.distinctBy { it.url }
            }
            links = loaded.first
            subtitles = loaded.second
        } catch (t: Throwable) {
            error = t.message ?: loadError
        } finally {
            loading = false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun syncData(): HashMap<String, String> =
        args?.getSerializable("syncData") as? HashMap<String, String> ?: HashMap()

    fun play(link: ExtractorLink) {
        val selectedEpisode = episode ?: return
        val generator = StremioStreamGenerator(
            episodes = listOf(selectedEpisode),
            link = link,
            subtitles = subtitles,
        )
        activity?.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(generator, 0, syncData())
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.stremio_streams),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.stremio_streams_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (loading) {
            CircularProgressIndicator()
        } else if (episode == null) {
            Text(stringResource(R.string.stremio_streams_error))
        } else if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        } else if (links.isEmpty()) {
            Text(stringResource(R.string.stremio_streams_empty))
        } else {
            Text(
                text = stringResource(R.string.stremio_streams_found) + " (${links.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            links.forEach { link ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(link.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            link.url.take(100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { play(link) }) {
                            Text(stringResource(R.string.stremio_streams_play))
                        }
                    }
                }
            }
        }
    }
}
