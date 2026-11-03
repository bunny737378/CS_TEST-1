package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.APIHolder.allProviders

/**
 * Registers the standalone Stremio provider with CloudStream's existing provider registry.
 * The provider has no sourcePlugin path, so plugin reloads leave it in place.
 */
object StremioBridge {
    fun registerProvider() {
        allProviders.withLock {
            if (allProviders.none { provider -> provider.name == StremioProvider.name }) {
                allProviders.add(StremioProvider)
            }
        }
    }
}
