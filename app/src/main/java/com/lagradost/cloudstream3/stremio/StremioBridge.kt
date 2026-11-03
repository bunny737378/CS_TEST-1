package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import java.net.URI

/**
 * Registers one CloudStream-like provider per installed search-capable Stremio addon.
 *
 * Dynamic providers live in both APIHolder.allProviders and APIHolder.apis, so they survive
 * MainActivity.onAllPluginsLoaded() and participate in SearchViewModel's per-provider repos.
 */
object StremioBridge {
    const val PROVIDER_PREFIX = "Stremio · "

    private val dynamicProviders = ArrayList<StremioAddonProvider>()

    /** Stable, human-readable and unique name for a per-addon provider. */
    fun providerName(addon: StremioAddon): String {
        val host = runCatching { URI(addon.transportUrl).host }.getOrNull()
        val hostSuffix = host?.let { " ($it)" }.orEmpty()
        val hash = StremioApi.resourceBase(addon.transportUrl)
            .hashCode()
            .toUInt()
            .toString(16)
            .takeLast(4)
        val displayName = addon.manifest.name.takeIf { it.isNotBlank() }
            ?: addon.manifest.id.takeIf { it.isNotBlank() }
            ?: "Addon"
        return "$PROVIDER_PREFIX$displayName$hostSuffix · $hash"
    }

    fun isStremioProviderName(name: String?): Boolean =
        name == StremioProvider.name || name?.startsWith(PROVIDER_PREFIX) == true

    /** Reconcile registered providers with the persisted addon collection. */
    @Synchronized
    fun syncProviders(notify: Boolean = true) {
        val stale = dynamicProviders.toList()
        val staleProviders: Set<MainAPI> = stale.toSet()
        allProviders.withLock {
            allProviders.removeAll(staleProviders)
            allProviders.removeAll { it === StremioProvider }
        }
        stale.forEach { APIHolder.removePluginMapping(it) }
        // Clean up the legacy singleton provider from earlier builds and rebuild apiMap.
        APIHolder.removePluginMapping(StremioProvider)
        dynamicProviders.clear()

        val fresh = StremioRepository.getAddons()
            .filter { addon -> addon.manifest.catalogs.any { it.supportsSearch } }
            .map(::StremioAddonProvider)

        fresh.forEach { provider ->
            allProviders.withLock {
                if (allProviders.none { it.name == provider.name }) {
                    allProviders.add(provider)
                }
            }
            APIHolder.addPluginMapping(provider)
            dynamicProviders.add(provider)
        }

        if (notify) afterPluginsLoadedEvent.invoke(false)
    }

    fun registerProvider() {
        syncProviders(notify = false)
    }
}
