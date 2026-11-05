package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object StremioAddonManager {
    private const val STORAGE_KEY = "stremio_installed_addons_v1"

    private val builtInDefaults: List<InstalledStremioAddon> by lazy {
        listOf(
            builtInAddon(
                "https://v3-cinemeta.strem.io/manifest.json",
                """{
                    "id":"com.linvo.cinemeta",
                    "version":"1.0.0",
                    "name":"Cinemeta",
                    "description":"Stremio metadata and catalogs",
                    "types":["movie","series","anime"],
                    "idPrefixes":["tt"],
                    "resources":[
                        {"name":"catalog","types":["movie","series","anime"],"idPrefixes":["tt"]},
                        {"name":"meta","types":["movie","series","anime"],"idPrefixes":["tt"]}
                    ],
                    "catalogs":[
                        {"type":"movie","id":"top","name":"Popular Movies","extra":[{"name":"search","isRequired":false}]},
                        {"type":"series","id":"top","name":"Popular Series","extra":[{"name":"search","isRequired":false}]}
                    ]
                }""",
            ),
            builtInAddon(
                "https://opensubtitles.strem.io/manifest.json",
                """{
                    "id":"org.stremio.opensubtitles",
                    "version":"1.0.0",
                    "name":"OpenSubtitles",
                    "description":"OpenSubtitles subtitle addon",
                    "types":["movie","series","anime"],
                    "idPrefixes":["tt"],
                    "resources":[{"name":"subtitles","types":["movie","series","anime"],"idPrefixes":["tt"]}]
                }""",
            ),
            builtInAddon(
                "https://opensubtitles-v3.strem.io/manifest.json",
                """{
                    "id":"org.stremio.opensubtitles-v3",
                    "version":"1.0.0",
                    "name":"OpenSubtitles V3",
                    "description":"OpenSubtitles V3 subtitle addon",
                    "types":["movie","series","anime"],
                    "idPrefixes":["tt"],
                    "resources":[{"name":"subtitles","types":["movie","series","anime"],"idPrefixes":["tt"]}]
                }""",
            ),
        )
    }

    private fun builtInAddon(manifestUrl: String, manifestJson: String): InstalledStremioAddon {
        val normalizedUrl = StremioTransport.normalizeManifestUrl(manifestUrl)
        val manifest = StremioJsonParser.parseManifest(manifestJson)
        return InstalledStremioAddon(
            manifestUrl = normalizedUrl,
            transportBaseUrl = StremioTransport.extractTransportBaseUrl(normalizedUrl),
            manifest = manifest,
            enabled = true,
            addedAt = 0L,
            isDefault = true,
        )
    }

    private val mutex = Mutex()
    private val _addons = MutableStateFlow<List<InstalledStremioAddon>>(emptyList())
    val addons: StateFlow<List<InstalledStremioAddon>> = _addons.asStateFlow()

    private val previewCache = ConcurrentHashMap<String, StremioMetaItem>()

    @Volatile
    private var initialized = false

    fun cachePreviewMeta(meta: StremioMetaItem) {
        previewCache["${meta.type.lowercase()}:${meta.id}"] = meta
    }

    fun getCachedPreviewMeta(type: String, id: String): StremioMetaItem? {
        return previewCache["${type.lowercase()}:$id"]
    }

    suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            val raw = CloudStreamApp.getKey<String>(STORAGE_KEY)
            val saved = StremioJsonParser.deserializeInstalledAddons(raw)
            val merged = mergeBuiltInDefaults(saved)
            _addons.value = merged
            if (merged != saved) {
                CloudStreamApp.setKey(STORAGE_KEY, StremioJsonParser.serializeInstalledAddons(merged))
            }
            syncProvidersToApiHolder(merged, notifyEvent = true)
            initialized = true
        }
    }

    fun hasInstalledAddonsSync(): Boolean {
        if (_addons.value.isNotEmpty() || builtInDefaults.isNotEmpty()) return true
        val raw = CloudStreamApp.getKey<String>(STORAGE_KEY)
        return !raw.isNullOrBlank() && raw.trim() != "[]"
    }

    private fun mergeBuiltInDefaults(saved: List<InstalledStremioAddon>): List<InstalledStremioAddon> {
        val merged = saved.toMutableList()
        for (default in builtInDefaults) {
            val matchingIndex = merged.indexOfFirst { addon ->
                addon.manifestUrl.equals(default.manifestUrl, ignoreCase = true) ||
                    (addon.id == default.id && sameOrigin(addon.manifestUrl, default.manifestUrl)) ||
                    (addon.isDefault && sameOrigin(addon.manifestUrl, default.manifestUrl))
            }
            if (matchingIndex >= 0) {
                merged[matchingIndex] = merged[matchingIndex].copy(isDefault = true)
            } else {
                merged.add(default)
            }
        }
        return merged
    }

    private fun isBuiltInDefault(manifestUrl: String, manifestId: String): Boolean {
        return builtInDefaults.any { default ->
            manifestUrl.equals(default.manifestUrl, ignoreCase = true) ||
                (manifestId == default.id && sameOrigin(manifestUrl, default.manifestUrl))
        }
    }

    private fun sameOrigin(firstUrl: String, secondUrl: String): Boolean {
        fun origin(url: String): String? = runCatching {
            URI(url).let { "${it.scheme}://${it.rawAuthority}" }
        }.getOrNull()
        return origin(firstUrl)?.equals(origin(secondUrl), ignoreCase = true) == true
    }

    fun isStremioProvider(apiName: String?): Boolean {
        if (apiName.isNullOrBlank()) return false
        val api = APIHolder.getApiFromNameNull(apiName)
        if (api is StremioCatalogProvider) return true
        return _addons.value.any { it.enabled && it.name == apiName }
    }

    /**
     * Fetches the Stremio manifest at [rawUrl] and installs or updates the addon.
     */
    suspend fun fetchAndInstallAddon(
        rawUrl: String,
        configValues: Map<String, String> = emptyMap(),
    ): InstalledStremioAddon {
        ensureInitialized()
        val (normalizedManifestUrl, manifest) = StremioTransport.fetchManifest(rawUrl)
        val transportBaseUrl = StremioTransport.extractTransportBaseUrl(normalizedManifestUrl)

        return mutex.withLock {
            val current = _addons.value.toMutableList()
            val existingIdx = current.indexOfFirst {
                it.id == manifest.id || it.manifestUrl.equals(normalizedManifestUrl, ignoreCase = true)
            }
            val installed = if (existingIdx >= 0) {
                val prev = current[existingIdx]
                InstalledStremioAddon(
                    manifestUrl = normalizedManifestUrl,
                    transportBaseUrl = transportBaseUrl,
                    manifest = manifest,
                    enabled = prev.enabled,
                    addedAt = prev.addedAt,
                    configValues = configValues.ifEmpty { prev.configValues },
                    isDefault = prev.isDefault || isBuiltInDefault(normalizedManifestUrl, manifest.id),
                ).also { current[existingIdx] = it }
            } else {
                InstalledStremioAddon(
                    manifestUrl = normalizedManifestUrl,
                    transportBaseUrl = transportBaseUrl,
                    manifest = manifest,
                    enabled = true,
                    addedAt = System.currentTimeMillis(),
                    configValues = configValues,
                    isDefault = isBuiltInDefault(normalizedManifestUrl, manifest.id),
                ).also { current.add(it) }
            }

            persistAndSyncLocked(current, newlyAddedOrEnabled = installed)
            installed
        }
    }

    suspend fun refreshAddon(addon: InstalledStremioAddon): InstalledStremioAddon {
        return fetchAndInstallAddon(addon.manifestUrl, addon.configValues)
    }

    suspend fun applyDeclarativeConfig(
        addon: InstalledStremioAddon,
        newConfigValues: Map<String, String>,
    ): InstalledStremioAddon {
        val configuredUrl = StremioTransport.buildConfiguredManifestUrl(addon, newConfigValues)
        return fetchAndInstallAddon(configuredUrl, newConfigValues)
    }

    suspend fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        ensureInitialized()
        mutex.withLock {
            var toggledOn: InstalledStremioAddon? = null
            val updated = _addons.value.map { item ->
                if (item.manifestUrl == manifestUrl) {
                    val copy = item.copy(enabled = enabled)
                    if (enabled) toggledOn = copy
                    copy
                } else {
                    item
                }
            }
            persistAndSyncLocked(updated, newlyAddedOrEnabled = toggledOn)
        }
    }

    suspend fun removeAddon(manifestUrl: String) {
        ensureInitialized()
        mutex.withLock {
            val updated = _addons.value.filterNot {
                it.manifestUrl.equals(manifestUrl, ignoreCase = true) && !it.isDefault
            }
            persistAndSyncLocked(updated, newlyAddedOrEnabled = null)
        }
    }

    private fun persistAndSyncLocked(
        updated: List<InstalledStremioAddon>,
        newlyAddedOrEnabled: InstalledStremioAddon?,
    ) {
        _addons.value = updated
        CloudStreamApp.setKey(STORAGE_KEY, StremioJsonParser.serializeInstalledAddons(updated))
        val registeredProviders = syncProvidersToApiHolder(updated, notifyEvent = true)

        // If the user previously customized Search filter providers, auto-include newly added
        // searchable Stremio catalog addons so they appear immediately in Search results.
        if (newlyAddedOrEnabled != null && newlyAddedOrEnabled.enabled && newlyAddedOrEnabled.manifest.hasSearchCatalogs) {
            val existingSearchPrefs = DataStoreHelper.searchPreferenceProviders
            if (existingSearchPrefs.isNotEmpty()) {
                val matchingProviderName = registeredProviders
                    .firstOrNull { it.addon.manifestUrl == newlyAddedOrEnabled.manifestUrl }
                    ?.name
                if (matchingProviderName != null && matchingProviderName !in existingSearchPrefs) {
                    DataStoreHelper.searchPreferenceProviders = existingSearchPrefs + matchingProviderName
                }
            }
        }
    }

    /**
     * Synchronizes enabled Stremio Catalog addons with `APIHolder.allProviders` and `APIHolder.apis`.
     * Uses ONLY the addon's clean `manifest.name` (e.g. `"moviebox"`), never prefixed with `"Stremio"`.
     */
    fun syncProvidersToApiHolder(
        installed: List<InstalledStremioAddon> = _addons.value,
        notifyEvent: Boolean = false,
    ): List<StremioCatalogProvider> {
        val catalogAddons = installed.filter {
            it.enabled && it.supportsCapability(StremioAddonCapability.CATALOG)
        }

        // Preserve exact addon name; only append disambiguator if two distinct addons share identical names
        val nameCounts = HashMap<String, Int>()
        val newProviders = catalogAddons.map { addon ->
            val baseName = addon.name
            val seen = (nameCounts[baseName] ?: 0) + 1
            nameCounts[baseName] = seen
            val finalName = if (seen == 1) baseName else "$baseName ($seen)"
            StremioCatalogProvider(addon = addon, providerName = finalName)
        }

        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { it is StremioCatalogProvider }
            APIHolder.allProviders.addAll(newProviders)
            APIHolder.apis = APIHolder.allProviders.distinctBy {
                it.lang + it.name + it.mainUrl + it::class.qualifiedName
            }
            APIHolder.apiMap = null
        }

        if (notifyEvent) {
            MainActivity.afterPluginsLoadedEvent.invoke(true)
        }
        return newProviders
    }

    /**
     * Resolves full `StremioMetaItem` for `(type, id)` using installed Metadata addons
     * (preferring [sourceAddonId] first), with Cinemeta fallback for `tt...` IMDb IDs.
     */
    suspend fun resolveFullMeta(
        sourceAddonId: String?,
        type: String,
        id: String,
        fallbackPreview: StremioMetaItem? = null,
    ): StremioMetaItem? {
        ensureInitialized()
        val enabledMetaAddons = _addons.value.filter {
            it.enabled && it.supportsCapability(StremioAddonCapability.METADATA)
        }

        val orderedCandidates = buildList {
            if (!sourceAddonId.isNullOrBlank()) {
                enabledMetaAddons.firstOrNull {
                    it.id == sourceAddonId && it.manifest.supportsResource("meta", type, id)
                }?.let { add(it) }
            }
            enabledMetaAddons.forEach { addon ->
                if (addon.manifest.supportsResource("meta", type, id) && addon !in this) {
                    add(addon)
                }
            }
        }

        var bestMeta: StremioMetaItem? = fallbackPreview ?: getCachedPreviewMeta(type, id)

        for (addon in orderedCandidates) {
            val fetched = runCatching {
                StremioTransport.fetchMeta(addon, type, id)
            }.onFailure { logError(it) }.getOrNull() ?: continue

            bestMeta = mergeMeta(bestMeta, fetched)
            val needsVideos = type.equals("series", ignoreCase = true) ||
                type.equals("anime", ignoreCase = true) ||
                type.equals("channel", ignoreCase = true)
            if (!needsVideos || bestMeta.videos.isNotEmpty()) {
                cachePreviewMeta(bestMeta)
                return bestMeta
            }
        }

        // If series/movie uses an IMDb `tt...` ID and no installed meta addon supplied full details/videos,
        // fall back to Stremio's official Cinemeta metadata service.
        val imdbId = bestMeta?.resolveImdbId() ?: id.takeIf { it.startsWith("tt") }?.substringBefore(":")
        if (!imdbId.isNullOrBlank()) {
            val needsMore = bestMeta == null ||
                ((type.equals("series", ignoreCase = true) || type.equals("anime", ignoreCase = true)) && bestMeta.videos.isEmpty()) ||
                bestMeta.description.isNullOrBlank()
            if (needsMore) {
                val cinemeta = runCatching {
                    StremioTransport.fetchCinemetaFallback(type, imdbId)
                }.onFailure { logError(it) }.getOrNull()
                if (cinemeta != null) {
                    bestMeta = mergeMeta(bestMeta, cinemeta)
                }
            }
        }

        bestMeta?.let { cachePreviewMeta(it) }
        return bestMeta
    }

    private fun mergeMeta(base: StremioMetaItem?, incoming: StremioMetaItem): StremioMetaItem {
        if (base == null) return incoming
        return incoming.copy(
            poster = incoming.poster ?: base.poster,
            background = incoming.background ?: base.background,
            logo = incoming.logo ?: base.logo,
            description = incoming.description ?: base.description,
            releaseInfo = incoming.releaseInfo ?: base.releaseInfo,
            imdbRating = incoming.imdbRating ?: base.imdbRating,
            imdbId = incoming.imdbId ?: base.imdbId,
            genres = incoming.genres.ifEmpty { base.genres },
            cast = incoming.cast.ifEmpty { base.cast },
            director = incoming.director.ifEmpty { base.director },
            runtime = incoming.runtime ?: base.runtime,
            videos = incoming.videos.ifEmpty { base.videos },
            links = incoming.links.ifEmpty { base.links },
            trailers = incoming.trailers.ifEmpty { base.trailers },
            defaultVideoId = incoming.defaultVideoId ?: base.defaultVideoId,
        )
    }

    /**
     * Queries all enabled Stream addons supporting [episodeData] in parallel and emits
     * [onAddonResult] progressively as each addon responds.
     */
    suspend fun queryStreamsLive(
        episodeData: StremioEpisodeData,
        onAddonResult: (addonName: String, streams: List<StremioResolvedStream>) -> Unit,
    ): List<StremioResolvedStream> = coroutineScope {
        ensureInitialized()
        val allResults = mutableListOf<StremioResolvedStream>()
        val resultsLock = Any()

        // Include inline video.streams from Metadata addon if provided
        if (episodeData.embeddedStreams.isNotEmpty()) {
            val sourceAddon = _addons.value.firstOrNull { it.id == episodeData.sourceAddonId }
            val sourceName = sourceAddon?.name ?: "Metadata"
            val inlineResolved = episodeData.embeddedStreams.map { s ->
                StremioResolvedStream(
                    addonId = sourceAddon?.id ?: "embedded",
                    addonName = sourceName,
                    addonLogo = sourceAddon?.manifest?.logo,
                    stream = s,
                    itemType = episodeData.stremioType,
                )
            }
            synchronized(resultsLock) { allResults.addAll(inlineResolved) }
            onAddonResult(sourceName, inlineResolved)
        }

        val streamAddons = _addons.value.filter {
            it.enabled && it.supportsCapability(StremioAddonCapability.STREAM)
        }

        val candidateIds = buildCandidateVideoIds(episodeData)

        streamAddons.map { addon ->
            async {
                val matchingId = candidateIds.firstOrNull { (candType, candId) ->
                    addon.manifest.supportsResource("stream", candType, candId)
                } ?: return@async

                val (targetType, targetId) = matchingId
                val streams = runCatching {
                    StremioTransport.fetchStreams(addon, targetType, targetId)
                }.onFailure { logError(it) }.getOrNull().orEmpty()

                if (streams.isNotEmpty()) {
                    val resolved = streams.map { s ->
                        StremioResolvedStream(
                            addonId = addon.id,
                            addonName = addon.name,
                            addonLogo = addon.manifest.logo,
                            stream = s,
                            itemType = episodeData.stremioType,
                        )
                    }
                    synchronized(resultsLock) { allResults.addAll(resolved) }
                    onAddonResult(addon.name, resolved)
                }
            }
        }.awaitAll()

        synchronized(resultsLock) { allResults.toList() }
    }

    /**
     * Queries all enabled Subtitle addons supporting [episodeData] and returns per-addon subtitle lists.
     */
    suspend fun querySubtitleAddons(
        episodeData: StremioEpisodeData,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        onProgress: ((List<StremioSubtitleAddonResult>) -> Unit)? = null,
    ): List<StremioSubtitleAddonResult> = coroutineScope {
        ensureInitialized()
        val subtitleAddons = _addons.value.filter {
            it.enabled && it.supportsCapability(StremioAddonCapability.SUBTITLES)
        }
        if (subtitleAddons.isEmpty()) {
            onProgress?.invoke(emptyList())
            return@coroutineScope emptyList()
        }

        val candidateIds = buildCandidateVideoIds(episodeData)
        val applicableAddons = subtitleAddons.mapNotNull { addon ->
            val match = candidateIds.firstOrNull { (candType, candId) ->
                addon.manifest.supportsResource("subtitles", candType, candId)
            }
            if (match != null) addon to match else null
        }

        if (applicableAddons.isEmpty()) {
            onProgress?.invoke(emptyList())
            return@coroutineScope emptyList()
        }

        val extra = LinkedHashMap<String, String>().apply {
            if (!videoHash.isNullOrBlank()) put("videoHash", videoHash)
            if (videoSize != null && videoSize > 0L) put("videoSize", videoSize.toString())
            if (!filename.isNullOrBlank()) put("filename", filename)
        }

        val stateByAddon = LinkedHashMap<String, StremioSubtitleAddonResult>()
        val stateLock = Any()
        for ((addon, _) in applicableAddons) {
            stateByAddon[addon.manifestUrl] = StremioSubtitleAddonResult(
                addonId = addon.id,
                addonName = addon.name,
                subtitles = emptyList(),
                isLoading = true,
            )
        }
        onProgress?.invoke(stateByAddon.values.toList())

        applicableAddons.amap { (addon, target) ->
            val (targetType, targetId) = target
            val subs = runCatching {
                StremioTransport.fetchSubtitles(addon, targetType, targetId, extra)
            }.onFailure { logError(it) }.getOrNull().orEmpty()

            synchronized(stateLock) {
                stateByAddon[addon.manifestUrl] = StremioSubtitleAddonResult(
                    addonId = addon.id,
                    addonName = addon.name,
                    subtitles = subs,
                    isLoading = false,
                )
                onProgress?.invoke(stateByAddon.values.toList())
            }
        }

        synchronized(stateLock) { stateByAddon.values.toList() }
    }

    private fun buildCandidateVideoIds(episodeData: StremioEpisodeData): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val primaryType = episodeData.stremioType
        list.add(primaryType to episodeData.videoId)
        if (primaryType.equals("anime", ignoreCase = true)) {
            list.add("series" to episodeData.videoId)
        }

        val imdb = episodeData.imdbId?.takeIf { it.startsWith("tt") }
        if (imdb != null && !episodeData.videoId.startsWith("tt")) {
            val imdbVideoId = if (
                (primaryType.equals("series", ignoreCase = true) || primaryType.equals("anime", ignoreCase = true)) &&
                episodeData.season != null && episodeData.episode != null
            ) {
                "$imdb:${episodeData.season}:${episodeData.episode}"
            } else {
                imdb
            }
            list.add((if (primaryType.equals("anime", ignoreCase = true)) "series" else primaryType) to imdbVideoId)
        }
        return list.distinct()
    }
}
