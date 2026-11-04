package com.lagradost.cloudstream3.stremio

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.CloudStreamApp.Companion.openBrowser
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.BlackTextField
import com.lagradost.cloudstream4.compose.RoundedShape
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.compose.WhiteFilterChip
import com.lagradost.cloudstream4.compose.WhiteIconButton
import com.lagradost.cloudstream4.compose.focusOutline
import com.mihon.material.AppBar
import com.mihon.material.Scaffold
import com.mihon.presentation.LocalBackPress
import kotlinx.coroutines.launch

object StremioAddonsScreen : Screen {

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            StremioAddonManager.ensureInitialized()
        }

        val addons by StremioAddonManager.addons.collectAsState()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val handleBack = LocalBackPress.current
        val context = LocalContext.current

        var manifestUrlInput by rememberSaveable { mutableStateOf("") }
        var isFetching by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf<String?>(null) }
        var isErrorStatus by remember { mutableStateOf(false) }

        var selectedTab by rememberSaveable { mutableStateOf(StremioAddonCapability.CATALOG) }
        var configDialogAddon by remember { mutableStateOf<InstalledStremioAddon?>(null) }
        var webConfigureAddon by remember { mutableStateOf<InstalledStremioAddon?>(null) }

        fun triggerFetch(urlToFetch: String) {
            val trimmed = urlToFetch.trim()
            if (trimmed.isEmpty() || isFetching) return
            focusManager.clearFocus()
            isFetching = true
            statusMessage = null
            isErrorStatus = false
            scope.launch {
                runCatching {
                    StremioAddonManager.fetchAndInstallAddon(trimmed)
                }.onSuccess { installed ->
                    isFetching = false
                    manifestUrlInput = ""
                    val caps = installed.manifest.capabilities
                    if (caps.isNotEmpty() && selectedTab !in caps) {
                        selectedTab = caps.first()
                    }
                    val typesLabel = caps.joinToString(", ") { it.displayTitle }
                    statusMessage = "Installed ${installed.name} ($typesLabel)"
                    isErrorStatus = false
                    showToast("Added ${installed.name}")

                    if (installed.manifest.behaviorHints.configurationRequired && installed.configValues.isEmpty()) {
                        if (installed.manifest.config.isNotEmpty()) {
                            configDialogAddon = installed
                        } else {
                            webConfigureAddon = installed
                        }
                    }
                }.onFailure { err ->
                    isFetching = false
                    isErrorStatus = true
                    statusMessage = err.localizedMessage ?: "Failed to fetch addon manifest"
                }
            }
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(R.string.category_addons),
                    navigateUp = handleBack,
                    actions = {
                        if (addons.isNotEmpty()) {
                            WhiteIconButton(
                                enabled = !isFetching,
                                onClick = {
                                    isFetching = true
                                    scope.launch {
                                        addons.forEach { addon ->
                                            runCatching { StremioAddonManager.refreshAddon(addon) }
                                        }
                                        isFetching = false
                                        showToast("Refreshed ${addons.size} addon(s)")
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = "Refresh addons",
                                )
                            }
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
                // URL Box + Fetch Button side-by-side directly under title
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BlackTextField(
                        value = manifestUrlInput,
                        placeHolder = "Enter Stremio manifest URL or stremio:// link",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { triggerFetch(manifestUrlInput) }
                        ),
                        onValueChange = { manifestUrlInput = it },
                    )

                    WhiteButton(
                        text = if (isFetching) "Fetching..." else "Fetch",
                        onClick = { triggerFetch(manifestUrlInput) },
                    )
                }

                if (isFetching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErrorStatus) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                    )
                }

                // 4 Stremio Addon Types left-to-right: Catalog | Metadata | Stream | Subtitles
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StremioAddonCapability.entries.forEach { capability ->
                        val count = addons.count { it.supportsCapability(capability) }
                        WhiteFilterChip(
                            selected = selectedTab == capability,
                            label = "${capability.displayTitle} ($count)",
                            onClick = { selectedTab = capability },
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    thickness = 1.dp,
                )

                val filteredAddons = remember(addons, selectedTab) {
                    addons.filter { it.supportsCapability(selectedTab) }
                }

                if (filteredAddons.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.extension_24px),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "No ${selectedTab.displayTitle} addons yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "Paste a Stremio addon manifest URL above and tap Fetch.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = filteredAddons,
                            key = { "${selectedTab.name}:${it.manifestUrl}" }
                        ) { addon ->
                            StremioAddonCard(
                                addon = addon,
                                onToggleEnabled = { enabled ->
                                    scope.launch {
                                        StremioAddonManager.setAddonEnabled(addon.manifestUrl, enabled)
                                    }
                                },
                                onConfigure = {
                                    if (addon.manifest.config.isNotEmpty()) {
                                        configDialogAddon = addon
                                    } else {
                                        webConfigureAddon = addon
                                    }
                                },
                                onOpenWebConfigure = {
                                    webConfigureAddon = addon
                                },
                                onRefresh = {
                                    scope.launch {
                                        runCatching { StremioAddonManager.refreshAddon(addon) }
                                            .onSuccess { showToast("Updated ${it.name}") }
                                            .onFailure { showToast(it.localizedMessage ?: "Refresh failed") }
                                    }
                                },
                                onRemove = {
                                    scope.launch {
                                        StremioAddonManager.removeAddon(addon.manifestUrl)
                                        showToast("Removed ${addon.name}")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        configDialogAddon?.let { targetAddon ->
            DeclarativeConfigDialog(
                addon = targetAddon,
                onDismiss = { configDialogAddon = null },
                onOpenWebPage = {
                    configDialogAddon = null
                    webConfigureAddon = targetAddon
                },
                onSave = { values ->
                    configDialogAddon = null
                    isFetching = true
                    scope.launch {
                        runCatching {
                            StremioAddonManager.applyDeclarativeConfig(targetAddon, values)
                        }.onSuccess { updated ->
                            isFetching = false
                            showToast("Configured ${updated.name}")
                        }.onFailure { err ->
                            isFetching = false
                            showToast(err.localizedMessage ?: "Failed to apply configuration")
                        }
                    }
                },
            )
        }

        webConfigureAddon?.let { targetAddon ->
            WebConfigureDialog(
                addon = targetAddon,
                onDismiss = { webConfigureAddon = null },
                onOpenExternalBrowser = {
                    context.openBrowser(targetAddon.configureWebUrl())
                },
                onStremioInstallIntercepted = { interceptedUrl ->
                    webConfigureAddon = null
                    triggerFetch(interceptedUrl)
                },
            )
        }
    }

    @Composable
    private fun StremioAddonCard(
        addon: InstalledStremioAddon,
        onToggleEnabled: (Boolean) -> Unit,
        onConfigure: () -> Unit,
        onOpenWebConfigure: () -> Unit,
        onRefresh: () -> Unit,
        onRemove: () -> Unit,
    ) {
        val manifest = addon.manifest
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedShape())
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .focusOutline()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!manifest.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = manifest.logo,
                            contentDescription = addon.name,
                            modifier = Modifier.size(38.dp),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.extension_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = addon.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "v${manifest.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = manifest.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Switch(
                    checked = addon.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            if (!manifest.description.isNullOrBlank()) {
                Text(
                    text = manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Capability & media type badges
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                manifest.capabilities.forEach { cap ->
                    SubtleBadge(text = cap.displayTitle, emphasized = true)
                }
                manifest.types.forEach { mediaType ->
                    SubtleBadge(text = mediaType, emphasized = false)
                }
                if (manifest.behaviorHints.p2p) {
                    SubtleBadge(text = "P2P", emphasized = false)
                }
            }

            if (manifest.behaviorHints.configurationRequired && addon.configValues.isEmpty()) {
                Text(
                    text = "Configuration required — tap Configure to finish setup.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (manifest.isConfigurable) {
                    BlackButton(
                        text = "Configure",
                        onClick = onConfigure,
                    )
                } else {
                    BlackButton(
                        text = "Configure",
                        onClick = onOpenWebConfigure,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                WhiteIconButton(onClick = onRefresh) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = "Refresh addon",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }

                WhiteIconButton(onClick = onRemove) {
                    Icon(
                        painter = painterResource(R.drawable.delete_24px),
                        contentDescription = "Remove addon",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }

    @Composable
    private fun SubtleBadge(text: String, emphasized: Boolean) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (emphasized) {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.background.copy(alpha = 0.65f)
                    }
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    @Composable
    private fun DeclarativeConfigDialog(
        addon: InstalledStremioAddon,
        onDismiss: () -> Unit,
        onOpenWebPage: () -> Unit,
        onSave: (Map<String, String>) -> Unit,
    ) {
        val values = remember(addon.manifestUrl) {
            mutableStateMapOf<String, String>().apply {
                addon.manifest.config.forEach { field ->
                    val initial = addon.configValues[field.key] ?: field.defaultValue.orEmpty()
                    put(field.key, initial)
                }
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Configure ${addon.name}",
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    addon.manifest.config.forEach { field ->
                        val currentVal = values[field.key].orEmpty()
                        val fieldLabel = buildString {
                            append(field.title ?: field.key)
                            if (field.required) append(" *")
                        }

                        when (field.type.lowercase()) {
                            "checkbox" -> {
                                val checked = currentVal.equals("true", ignoreCase = true) ||
                                    currentVal.equals("checked", ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { values[field.key] = (!checked).toString() },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { values[field.key] = it.toString() },
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = fieldLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            "select" -> {
                                var expanded by remember { mutableStateOf(false) }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = fieldLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Box {
                                        BlackButton(
                                            text = currentVal.ifEmpty {
                                                field.options.firstOrNull() ?: "Select"
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = { expanded = true },
                                        )
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                        ) {
                                            field.options.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option) },
                                                    onClick = {
                                                        values[field.key] = option
                                                        expanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = fieldLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    androidx.compose.material3.OutlinedTextField(
                                        value = currentVal,
                                        onValueChange = { values[field.key] = it },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        visualTransformation = if (field.type.equals("password", ignoreCase = true)) {
                                            PasswordVisualTransformation()
                                        } else {
                                            VisualTransformation.None
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = if (field.type.equals("number", ignoreCase = true)) {
                                                KeyboardType.Number
                                            } else if (field.type.equals("password", ignoreCase = true)) {
                                                KeyboardType.Password
                                            } else {
                                                KeyboardType.Text
                                            }
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    BlackButton(
                        text = "Open Web Configure Page",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenWebPage,
                    )
                }
            },
            confirmButton = {
                WhiteButton(
                    text = "Save",
                    onClick = { onSave(values.toMap()) },
                )
            },
            dismissButton = {
                BlackButton(
                    text = "Cancel",
                    onClick = onDismiss,
                )
            },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebConfigureDialog(
        addon: InstalledStremioAddon,
        onDismiss: () -> Unit,
        onOpenExternalBrowser: () -> Unit,
        onStremioInstallIntercepted: (String) -> Unit,
    ) {
        var isLoadingPage by remember { mutableStateOf(true) }
        val configureUrl = remember(addon.manifestUrl) { addon.configureWebUrl() }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WhiteIconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.close_24px),
                            contentDescription = "Close",
                        )
                    }
                    Text(
                        text = "Configure ${addon.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    WhiteIconButton(onClick = onOpenExternalBrowser) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_open_in_new_24),
                            contentDescription = "Open in browser",
                        )
                    }
                }

                if (isLoadingPage) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onBackground,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoadingPage = false
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val uri = request?.url ?: return false
                                    return handleConfigureRedirect(uri)
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?,
                                ): Boolean {
                                    val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                                        ?: return false
                                    return handleConfigureRedirect(uri)
                                }

                                private fun handleConfigureRedirect(uri: Uri): Boolean {
                                    val full = uri.toString()
                                    val scheme = uri.scheme?.lowercase()
                                    if (scheme == "stremio" || scheme == "stremio+http" ||
                                        full.endsWith("/manifest.json", ignoreCase = true)
                                    ) {
                                        onStremioInstallIntercepted(full)
                                        return true
                                    }
                                    return false
                                }
                            }
                            loadUrl(configureUrl)
                        }
                    }
                )
            }
        }
    }
}
