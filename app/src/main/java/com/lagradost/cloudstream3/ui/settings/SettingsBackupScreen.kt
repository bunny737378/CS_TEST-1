package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.services.BackupWorkManager
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.BackupUtils.restorePrompt
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.safefile.SafeFile
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState
import kotlinx.collections.immutable.persistentListOf

/**
 * Backup, extension maintenance and diagnostic tools.
 * App self-update checks, release downloads and APK installers are intentionally absent.
 */
object SettingsBackupScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.pref_category_backup)

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()

        val selectFileSelector =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                val context = CloudStreamApp.context ?: return@rememberLauncherForActivityResult

                try {
                    val appSettings = AppSettings(context)
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                    context.contentResolver.takePersistableUriPermission(uri, flags)

                    val filePath = SafeFile.fromUri(context, uri)?.filePath()
                    val visual = filePath ?: uri.toString()
                    appSettings.backup.path.set(uri.toString())
                    appSettings.backup.visualPath.set(visual)
                } catch (t: Throwable) {
                    logError(t)
                }
            }

        val visualBackupPath by settings.backup.visualPath.collectAsState()
        var showDialog by remember { mutableStateOf(false) }

        if (showDialog) {
            com.lagradost.cloudstream3.ui.settings.logcat.LogcatDialog {
                showDialog = false
            }
        }

        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_backup),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.backup_settings),
                        icon = painterResource(R.drawable.save_as_24px),
                        onClick = {
                            BackupUtils.backup(activity)
                        }
                    ),
                    Preference.PreferenceItem.ListPreference(
                        title = stringResource(R.string.backup_frequency),
                        icon = painterResource(R.drawable.save_clock_24px),
                        entries = integerArrayResource(R.array.periodic_work_values).zip(
                            stringArrayResource(R.array.periodic_work_names)
                        ).toMap(),
                        preference = settings.backup.frequency,
                        onValueChanged = { value ->
                            BackupWorkManager.enqueuePeriodicWork(
                                CloudStreamApp.context,
                                value.toLong()
                            )
                            return@ListPreference true
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.backup_path_title),
                        icon = painterResource(R.drawable.folder_24px),
                        subtitle = visualBackupPath,
                        onClick = {
                            selectFileSelector.launch(Uri.EMPTY)
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.restore_settings),
                        icon = painterResource(R.drawable.restore_page_24px),
                        onClick = {
                            activity?.restorePrompt()
                        }
                    ),
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_extensions),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.automatic_plugin_updates),
                        icon = painterResource(R.drawable.extension_24px),
                        preference = settings.plugins.autoUpdate,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.test_extensions),
                        subtitle = stringResource(R.string.test_extensions_summary),
                        icon = painterResource(R.drawable.baseline_network_ping_24),
                        onClick = {
                            activity?.navigate(R.id.navigation_test_providers)
                        }
                    )
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_actions),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.show_log_cat),
                        icon = painterResource(R.drawable.article_24px),
                        onClick = {
                            showDialog = true
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.redo_setup_process),
                        icon = painterResource(R.drawable.construction_24px),
                        onClick = {
                            activity?.navigate(R.id.navigation_setup_language)
                        }
                    ),
                )
            )
        )
    }
}
