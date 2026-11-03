package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.stremio.StremioAddon
import com.lagradost.cloudstream3.stremio.StremioApi
import com.lagradost.cloudstream3.stremio.StremioBridge
import com.lagradost.cloudstream3.stremio.StremioLanguageCatalog
import com.lagradost.cloudstream3.stremio.StremioRepository
import com.lagradost.cloudstream3.stremio.StremioSettings
import com.lagradost.cloudstream3.stremio.StremioStreamSortCriterion
import com.lagradost.cloudstream3.stremio.redactStremioSecrets
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Glue fragment for the legacy navigation graph. */
class StremioAddonsFragment : BaseComposeFragment() {
    @Composable
    override fun Content() {
        StremioAddonsContent()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.stremio_addons)
    }
}

@Composable
fun StremioAddonsContent() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var addonUrl by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(StremioRepository.isLoggedIn()) }
    var accountEmail by remember { mutableStateOf(StremioRepository.getEmail().orEmpty()) }
    var addons by remember { mutableStateOf(StremioRepository.getAddons()) }

    val languageOptions = remember { StremioLanguageCatalog.options() }
    val qualityOptions = remember {
        listOf(
            "Any" to "Any",
            "2160p" to "4K / 2160p",
            "1080p" to "1080p",
            "720p" to "720p",
            "480p" to "480p",
        )
    }
    val sortOptions = remember {
        StremioStreamSortCriterion.entries.map { it.key to it.label }
    }

    var streamLanguage by remember { mutableStateOf(StremioSettings.getStreamLanguage()) }
    var audioLanguage by remember { mutableStateOf(StremioSettings.getAudioLanguage()) }
    var subtitleLanguage by remember { mutableStateOf(StremioSettings.getSubtitleLanguage()) }
    var interfaceLanguage by remember { mutableStateOf(StremioSettings.getInterfaceLanguage()) }
    var preferredQuality by remember { mutableStateOf(StremioSettings.getPreferredQuality()) }
    var sortCriterion by remember { mutableStateOf(StremioSettings.getSortCriterion().key) }
    var minSeeds by remember { mutableStateOf(StremioSettings.getMinSeeds().toString()) }
    var minSpeedKbps by remember {
        mutableStateOf((StremioSettings.getMinDownloadSpeedBps() / 1024L).toString())
    }
    var cacheSize by remember { mutableStateOf(StremioSettings.getCacheSize()) }
    var torrentConnections by remember { mutableStateOf(StremioSettings.getTorrentConnections()) }
    var transcodeProfile by remember { mutableStateOf(StremioSettings.getTranscodeProfile()) }

    var autoSwitch by remember { mutableStateOf(StremioSettings.isAutoSwitchOnDeadStream()) }
    var proxyStreams by remember { mutableStateOf(StremioSettings.isProxyStreamsEnabled()) }
    var seeding by remember { mutableStateOf(StremioSettings.isSeedingEnabled()) }
    var subtitlesAutoSelect by remember { mutableStateOf(StremioSettings.isSubtitlesAutoSelect()) }
    var hardwareDecoding by remember { mutableStateOf(StremioSettings.isHardwareDecoding()) }
    var playInBackground by remember { mutableStateOf(StremioSettings.isPlayInBackground()) }
    var audioPassthrough by remember { mutableStateOf(StremioSettings.isAudioPassthrough()) }
    var hideSpoilers by remember { mutableStateOf(StremioSettings.isHideSpoilers()) }
    var mobileDataWarning by remember { mutableStateOf(StremioSettings.isMobileDataWarning()) }
    var keepScreenOn by remember { mutableStateOf(StremioSettings.isKeepScreenOn()) }
    var serverForeground by remember { mutableStateOf(StremioSettings.isServerInForeground()) }

    val installFailed = stringResource(R.string.stremio_install_failed)
    val loggedInText = stringResource(R.string.stremio_logged_in)
    val syncedText = stringResource(R.string.stremio_synced)
    val defaultsText = stringResource(R.string.stremio_defaults_installed)
    val installedText = stringResource(R.string.stremio_addon_installed)
    val removedText = stringResource(R.string.stremio_addon_removed)
    val loggedOutText = stringResource(R.string.stremio_logged_out)

    fun refresh() {
        loggedIn = StremioRepository.isLoggedIn()
        accountEmail = StremioRepository.getEmail().orEmpty()
        addons = StremioRepository.getAddons()
    }

    fun runBusy(block: suspend () -> String) {
        if (busy) return
        busy = true
        status = ""
        StremioRepository.stremioScope.launch {
            try {
                val result = block()
                mainHandler.post { status = result }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val message = (t.message?.takeIf { it.isNotBlank() } ?: t.toString())
                    .redactStremioSecrets()
                    .take(300)
                mainHandler.post { status = message }
            } finally {
                mainHandler.post {
                    refresh()
                    StremioBridge.syncProviders()
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.stremio_addons),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.stremio_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!loggedIn) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.stremio_email)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.stremio_password)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runBusy {
                            val error = StremioRepository.login(email, password)
                            if (error != null) return@runBusy error
                            StremioRepository.bootstrapAccount()
                            loggedInText
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                ) { Text(stringResource(R.string.stremio_login)) }
                OutlinedButton(
                    onClick = {
                        runBusy {
                            val error = StremioRepository.register(email, password)
                            if (error != null) return@runBusy error
                            StremioRepository.bootstrapAccount()
                            loggedInText
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                ) { Text(stringResource(R.string.stremio_signup)) }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = authKey,
                onValueChange = { authKey = it },
                label = { Text(stringResource(R.string.stremio_auth_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            OutlinedButton(
                onClick = {
                    runBusy {
                        val error = StremioRepository.loginWithAuthKey(authKey)
                        if (error != null) return@runBusy error
                        StremioRepository.bootstrapAccount()
                        loggedInText
                    }
                },
                enabled = !busy && authKey.isNotBlank(),
            ) { Text(stringResource(R.string.stremio_use_auth_key)) }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.stremio_account_selected),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = accountEmail.ifBlank { stringResource(R.string.stremio_account) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runBusy {
                            StremioRepository.syncFromServer()
                            syncedText
                        }
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.stremio_sync)) }
                OutlinedButton(
                    onClick = {
                        runBusy {
                            StremioRepository.installDefaults()
                            defaultsText
                        }
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.stremio_install_defaults)) }
                OutlinedButton(
                    onClick = {
                        runBusy {
                            StremioRepository.logout()
                            loggedOutText
                        }
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.stremio_logout)) }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = addonUrl,
                onValueChange = { addonUrl = it },
                label = { Text(stringResource(R.string.stremio_addon_url)) },
                placeholder = { Text("https://addon.example/manifest.json or /configure") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            Button(
                onClick = {
                    runBusy {
                        val ok = StremioRepository.installAddon(addonUrl)
                        if (!ok) return@runBusy installFailed
                        mainHandler.post { addonUrl = "" }
                        installedText
                    }
                },
                enabled = !busy && addonUrl.isNotBlank(),
            ) { Text(stringResource(R.string.stremio_install)) }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.stremio_installed_addons) + " (${addons.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            if (addons.isEmpty()) {
                Text(
                    text = stringResource(R.string.stremio_no_addons),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                addons.forEach { addon: StremioAddon ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(addon.manifest.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    addon.manifest.id,
                                    addon.manifest.version,
                                    "official".takeIf { addon.isOfficial },
                                    "protected".takeIf { addon.isProtected },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                addon.manifest.description.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                addon.transportUrl.redactStremioSecrets(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StremioApi.configureUrl(addon)?.let { url ->
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                )
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("Configure") }
                                }
                                OutlinedButton(
                                    onClick = {
                                        runBusy {
                                            StremioRepository.uninstallAddon(addon.transportUrl)
                                            removedText
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text(stringResource(R.string.stremio_uninstall)) }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Language & sorting", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsDropdown(
                    label = "Preferred stream language",
                    selected = streamLanguage,
                    options = languageOptions,
                ) {
                    streamLanguage = it
                    StremioSettings.setStreamLanguage(it)
                }
                SettingsDropdown(
                    label = "Preferred audio language",
                    selected = audioLanguage,
                    options = languageOptions,
                ) {
                    audioLanguage = it
                    StremioSettings.setAudioLanguage(it)
                }
                SettingsDropdown(
                    label = "Subtitle language",
                    selected = subtitleLanguage,
                    options = languageOptions,
                ) {
                    subtitleLanguage = it
                    StremioSettings.setSubtitleLanguage(it)
                }
                SettingsDropdown(
                    label = "Interface language",
                    selected = interfaceLanguage,
                    options = languageOptions,
                ) {
                    interfaceLanguage = it
                    StremioSettings.setInterfaceLanguage(it)
                }
                SettingsDropdown(
                    label = "Preferred quality",
                    selected = preferredQuality,
                    options = qualityOptions,
                ) {
                    preferredQuality = it
                    StremioSettings.setPreferredQuality(it)
                }
                SettingsDropdown(
                    label = "Stream sort",
                    selected = sortCriterion,
                    options = sortOptions,
                ) {
                    sortCriterion = it
                    StremioSettings.setSortCriterion(StremioStreamSortCriterion.fromKey(it))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Streaming & playback", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minSeeds,
                    onValueChange = {
                        minSeeds = it
                        StremioSettings.setMinSeeds(it.toIntOrNull() ?: 1)
                    },
                    label = { Text("Minimum seeds") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = minSpeedKbps,
                    onValueChange = {
                        minSpeedKbps = it
                        StremioSettings.setMinDownloadSpeedBps(
                            (it.toLongOrNull() ?: 50L) * 1024L
                        )
                    },
                    label = { Text("Minimum speed (KB/s)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = cacheSize,
                    onValueChange = {
                        cacheSize = it
                        StremioSettings.setCacheSize(it)
                    },
                    label = { Text("Cache size (bytes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = torrentConnections,
                    onValueChange = {
                        torrentConnections = it
                        StremioSettings.setTorrentConnections(it)
                    },
                    label = { Text("Torrent connections") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = transcodeProfile,
                    onValueChange = {
                        transcodeProfile = it
                        StremioSettings.setTranscodeProfile(it)
                    },
                    label = { Text("Transcode profile") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SettingsSwitch("Auto-switch on dead stream", autoSwitch) {
                    autoSwitch = it
                    StremioSettings.setAutoSwitchOnDeadStream(it)
                }
                SettingsSwitch("Proxy streams", proxyStreams) {
                    proxyStreams = it
                    StremioSettings.setProxyStreamsEnabled(it)
                }
                SettingsSwitch("Seeding", seeding) {
                    seeding = it
                    StremioSettings.setSeedingEnabled(it)
                }
                SettingsSwitch("Auto-select subtitles", subtitlesAutoSelect) {
                    subtitlesAutoSelect = it
                    StremioSettings.setSubtitlesAutoSelect(it)
                }
                SettingsSwitch("Hardware decoding", hardwareDecoding) {
                    hardwareDecoding = it
                    StremioSettings.setHardwareDecoding(it)
                }
                SettingsSwitch("Play in background", playInBackground) {
                    playInBackground = it
                    StremioSettings.setPlayInBackground(it)
                }
                SettingsSwitch("Audio passthrough", audioPassthrough) {
                    audioPassthrough = it
                    StremioSettings.setAudioPassthrough(it)
                }
                SettingsSwitch("Hide spoilers", hideSpoilers) {
                    hideSpoilers = it
                    StremioSettings.setHideSpoilers(it)
                }
                SettingsSwitch("Mobile data warning", mobileDataWarning) {
                    mobileDataWarning = it
                    StremioSettings.setMobileDataWarning(it)
                }
                SettingsSwitch("Keep screen on", keepScreenOn) {
                    keepScreenOn = it
                    StremioSettings.setKeepScreenOn(it)
                }
                SettingsSwitch("Server foreground service", serverForeground) {
                    serverForeground = it
                    StremioSettings.setServerInForeground(it)
                }
            }
        }

        if (busy) CircularProgressIndicator()
        if (status.isNotBlank()) {
            Text(status, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second
        ?: StremioLanguageCatalog.label(selected)
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(selectedLabel) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(options) { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelected(code)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
