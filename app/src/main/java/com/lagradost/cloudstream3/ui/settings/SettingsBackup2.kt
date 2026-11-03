package com.lagradost.cloudstream3.ui.settings

import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.mihon.presentation.settings.SearchableSettings

/** Glue code connecting the navigation graph to the backup/tools settings screen. */
class SettingsBackup2 : BaseComposeFragment(), SearchableSettings by SettingsBackupScreen
