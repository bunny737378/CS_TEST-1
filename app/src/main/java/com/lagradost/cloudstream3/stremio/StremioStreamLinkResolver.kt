package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

object StremioStreamLinkResolver {

    /**
     * Converts a single [StremioResolvedStream] into one or more playable [ExtractorLink]s
     * and emits its inline `stream.subtitles` (if any) via [subtitleCallback].
     */
    suspend fun emitResolvedStream(
        resolved: StremioResolvedStream,
        episodeData: StremioEpisodeData?,
        subtitleCallback: suspend (SubtitleFile) -> Unit,
        callback: suspend (ExtractorLink) -> Unit,
    ) {
        val s = resolved.stream
        val displaySourceName = buildList {
            add(resolved.addonName)
            s.name?.trim()?.replace(Regex("\\s*\n+\\s*"), " ")?.takeIf {
                it.isNotEmpty() && !it.equals(resolved.addonName, ignoreCase = true)
            }?.let { add(it) }
        }.joinToString(" · ")

        // Emit inline stream subtitles attached directly to this Stremio stream
        for (sub in s.subtitles) {
            val langLabel = formatSubtitleDisplayName(sub)
            subtitleCallback(newSubtitleFile(langLabel, sub.url))
        }

        val reqHeaders = s.behaviorHints.proxyHeaders?.request.orEmpty()
        val refererHeader = reqHeaders.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
            .orEmpty()

        val inferredQuality = inferQualityValue(resolved)

        when {
            !s.infoHash.isNullOrBlank() -> {
                val magnetUrl = buildStremioMagnetUri(s, episodeData)
                callback(
                    newExtractorLink(
                        source = resolved.addonName,
                        name = displaySourceName,
                        url = magnetUrl,
                        type = ExtractorLinkType.MAGNET,
                    ) {
                        this.quality = inferredQuality
                        this.headers = reqHeaders
                        this.referer = refererHeader
                    }
                )
            }

            !s.ytId.isNullOrBlank() -> {
                val ytUrl = "https://www.youtube.com/watch?v=${s.ytId.trim()}"
                loadExtractor(
                    url = ytUrl,
                    referer = null,
                    subtitleCallback = { sub -> kotlinx.coroutines.runBlocking { subtitleCallback(sub) } },
                    callback = { link -> kotlinx.coroutines.runBlocking { callback(link) } },
                )
            }

            !s.url.isNullOrBlank() -> {
                val rawUrl = s.url.trim()
                val linkType = when {
                    rawUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                    rawUrl.substringBefore("?").endsWith(".torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                    rawUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
                        rawUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                    rawUrl.substringBefore("?").endsWith(".mpd", ignoreCase = true) ||
                        rawUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                val finalUrl = if (linkType == ExtractorLinkType.MAGNET || linkType == ExtractorLinkType.TORRENT) {
                    appendTorrentHintsToUrl(rawUrl, s, episodeData)
                } else {
                    rawUrl
                }

                callback(
                    newExtractorLink(
                        source = resolved.addonName,
                        name = displaySourceName,
                        url = finalUrl,
                        type = linkType,
                    ) {
                        this.quality = inferredQuality
                        this.headers = reqHeaders
                        this.referer = refererHeader
                    }
                )
            }
        }
    }

    /**
     * Builds a rich magnet URI containing Stremio `infoHash`, `sources` trackers,
     * optional `fileIdx` (`index=`), `filename` hint, and `season`/`episode` hints.
     */
    fun buildStremioMagnetUri(
        stream: StremioStreamItem,
        episodeData: StremioEpisodeData?,
    ): String {
        val hash = stream.infoHash!!.trim().lowercase()
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(hash)

        val displayTitle = stream.behaviorHints.filename
            ?: episodeData?.title
            ?: stream.title?.lineSequence()?.firstOrNull()
        if (!displayTitle.isNullOrBlank()) {
            sb.append("&dn=").append(URLEncoder.encode(displayTitle, "UTF-8"))
        }

        val trackers = linkedSetOf<String>()
        for (src in stream.sources) {
            when {
                src.startsWith("tracker:", ignoreCase = true) -> {
                    val tr = src.substring("tracker:".length).trim()
                    if (tr.isNotEmpty()) trackers.add(tr)
                }
                src.startsWith("udp://", ignoreCase = true) ||
                    src.startsWith("http://", ignoreCase = true) ||
                    src.startsWith("https://", ignoreCase = true) ||
                    src.startsWith("ws://", ignoreCase = true) ||
                    src.startsWith("wss://", ignoreCase = true) -> {
                    trackers.add(src.trim())
                }
            }
        }
        for (tr in FAST_PUBLIC_TRACKERS) {
            trackers.add(tr)
        }
        for (tr in trackers) {
            sb.append("&tr=").append(URLEncoder.encode(tr, "UTF-8"))
        }

        if (stream.fileIdx != null && stream.fileIdx >= 0) {
            sb.append("&index=").append(stream.fileIdx)
        }
        if (!stream.behaviorHints.filename.isNullOrBlank()) {
            sb.append("&stremioFilename=")
                .append(URLEncoder.encode(stream.behaviorHints.filename, "UTF-8"))
        }
        if (episodeData?.season != null) {
            sb.append("&stremioSeason=").append(episodeData.season)
        }
        if (episodeData?.episode != null) {
            sb.append("&stremioEpisode=").append(episodeData.episode)
        }
        return sb.toString()
    }

    private fun appendTorrentHintsToUrl(
        rawUrl: String,
        stream: StremioStreamItem,
        episodeData: StremioEpisodeData?,
    ): String {
        if (!rawUrl.startsWith("magnet:", ignoreCase = true)) return rawUrl
        val extras = buildString {
            if (stream.fileIdx != null && !rawUrl.contains("index=")) {
                append("&index=").append(stream.fileIdx)
            }
            if (!stream.behaviorHints.filename.isNullOrBlank() && !rawUrl.contains("stremioFilename=")) {
                append("&stremioFilename=")
                    .append(URLEncoder.encode(stream.behaviorHints.filename, "UTF-8"))
            }
            if (episodeData?.season != null && !rawUrl.contains("stremioSeason=")) {
                append("&stremioSeason=").append(episodeData.season)
            }
            if (episodeData?.episode != null && !rawUrl.contains("stremioEpisode=")) {
                append("&stremioEpisode=").append(episodeData.episode)
            }
        }
        return rawUrl + extras
    }

    fun inferQualityValue(resolved: StremioResolvedStream): Int {
        return when (resolved.qualityBadge) {
            "4K" -> Qualities.P2160.value
            "1440p" -> Qualities.P1440.value
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            else -> getQualityFromName(
                listOfNotNull(
                    resolved.stream.name,
                    resolved.stream.title,
                    resolved.stream.description,
                ).joinToString(" ")
            )
        }
    }

    fun formatSubtitleDisplayName(sub: StremioSubtitleItem, addonName: String? = null): String {
        val cleanLang = fromTagToLanguageName(sub.lang.trim())
            ?: sub.lang.trim().ifEmpty { "Unknown" }
        val customLabel = sub.label?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(cleanLang, ignoreCase = true) && !it.equals(sub.lang, ignoreCase = true)
        }
        return buildString {
            append(cleanLang)
            if (customLabel != null) {
                append(" · ").append(customLabel)
            }
            if (!addonName.isNullOrBlank()) {
                append(" (").append(addonName).append(")")
            }
        }
    }

    fun toSubtitleData(sub: StremioSubtitleItem, addonName: String? = null): SubtitleData {
        val display = formatSubtitleDisplayName(sub, addonName)
        return SubtitleData(
            originalName = display,
            nameSuffix = "",
            url = sub.url,
            origin = SubtitleOrigin.URL,
            mimeType = sub.url.toSubtitleMimeType(),
            headers = emptyMap(),
            languageCode = sub.lang,
        )
    }

    val FAST_PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
    )
}
