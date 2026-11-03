package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lagradost.cloudstream3.R
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings

/** Searchable-settings entry for the Stremio Addons page. */
object StremioAddonsScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.stremio_addons)

    @Composable
    override fun getPreferences(): List<Preference> = emptyList()

    @Composable
    override fun Content() {
        StremioAddonsContent()
    }
}
