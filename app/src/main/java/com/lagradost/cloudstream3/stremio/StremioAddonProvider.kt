package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * One CloudStream-like provider per installed search-capable Stremio addon.
 *
 * APIHolder keeps distinct providers by (lang, name, mainUrl, class), so this class is
 * instantiated once per addon and has a stable per-addon name and mainUrl.
 */
class StremioAddonProvider(
    val addon: StremioAddon,
) : MainAPI() {
    override var name: String = StremioBridge.providerName(addon)
    override var mainUrl: String = StremioApi.resourceBase(addon.transportUrl)
    override var lang: String =
        StremioLanguageCatalog.toBcp47(StremioSettings.getInterfaceLanguage())
    override val hasMainPage: Boolean = addon.manifest.catalogs.isNotEmpty()
    override var sequentialMainPage: Boolean = true
    override var sequentialMainPageDelay: Long = 100L
    override var sequentialMainPageScrollDelay: Long = 100L
    override val supportedTypes: Set<TvType> = addon.manifest.catalogs
        .map { StremioProvider.toTvType(it.type) }
        .toSet()
        .ifEmpty { StremioProvider.supportedTypes }

    override val mainPage: List<MainPageData>
        get() = StremioProvider.providerMainPage(addon)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? =
        StremioProvider.getMainPage(this, addon, page, request)

    override suspend fun search(query: String): List<SearchResponse>? =
        StremioProvider.search(this, addon, query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        StremioProvider.search(this, addon, query, page)

    override suspend fun load(url: String): LoadResponse? =
        StremioProvider.load(this, addon, url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = StremioProvider.loadLinksFromOptions(data, subtitleCallback, callback)
}
