package com.lagradost.cloudstream3.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.stremio.StremioAddon
import com.lagradost.cloudstream3.stremio.StremioRepository
import com.lagradost.cloudstream3.stremio.redactStremioSecrets
import com.lagradost.cloudstream3.stremio.stremioScope
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
        stremioScope.launch {
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
                        addonUrl = ""
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
                                addon.manifest.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                addon.manifest.description.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                addon.transportUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

        if (busy) CircularProgressIndicator()
        if (status.isNotBlank()) {
            Text(status, color = MaterialTheme.colorScheme.primary)
        }
    }
}
