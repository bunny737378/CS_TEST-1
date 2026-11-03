package com.lagradost.cloudstream3.stremio

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Locale

/**
 * Stream presentation and playback resolution adapted from stremio-native's
 * StreamOption, StreamParser, StreamSortCriterion, StreamsSheet and StremioCore.directUrl.
 */
data class ParsedStreamMetadata(
    val seeds: Int?,
    val size: String?,
    val origin: String?,
    val cleanDescription: String?,
)

data class StremioStreamOption(
    val key: String,
    val index: Int,
    val addon: StremioAddon,
    val addonTitle: String,
    val stream: StremioStream,
    val name: String,
    val description: String?,
    val cleanDescription: String?,
    val quality: String?,
    val seeds: Int?,
    val size: String?,
    val origin: String?,
    val sizeBytes: Long,
    val qualityRank: Int,
    val languageCodes: Set<String>,
    val isSelectedLanguage: Boolean,
    val playbackUrl: String,
    val linkType: ExtractorLinkType?,
    val subtitles: List<StremioSubtitle>,
) {
    suspend fun toExtractorLink(): ExtractorLink {
        // Capture before entering ExtractorLink's initializer, whose `quality` is an Int.
        val streamQuality = quality
        return newExtractorLink(
            source = addonTitle,
            name = name,
            url = playbackUrl,
            type = linkType,
        ) {
            this.headers = stream.headers
            this.quality = qualityValue(streamQuality)
            val referer = stream.headers.entries
                .firstOrNull { it.key.equals("Referer", true) || it.key.equals("referer", true) }
                ?.value.orEmpty()
            this.referer = referer
        }
    }

    suspend fun subtitleFiles(): List<SubtitleFile> = subtitles.distinctBy { it.url }
        .mapNotNull { subtitle ->
            val resolved = StremioApi.resolveUrl(addon, subtitle.url) ?: subtitle.url
            if (resolved.isBlank()) return@mapNotNull null
            newSubtitleFile(subtitle.lang, resolved) {
                this.headers = stream.headers
            }
        }
}

object StremioStreamPresentation {
    private val SIZE_REGEX =
        Regex("""([\d.,]+)\s*(B|KB|MB|GB|TB)""", RegexOption.IGNORE_CASE)
    private val QUALITY_REGEX =
        Regex("""(2160p|1080p|720p|480p|4k)""", RegexOption.IGNORE_CASE)
    private val CLEANUP_REGEXES = listOf(
        Regex("""👤\s*\d+"""),
        Regex("""👥\s*\d+"""),
        Regex("""seeds?\s*[:=]?\s*\d+""", RegexOption.IGNORE_CASE),
        Regex("""💾\uFE0F?\s*[^\n👤⚙]+"""),
        Regex("""size\s*[:=]?\s*[^\n👤⚙]+""", RegexOption.IGNORE_CASE),
        Regex("""⚙\uFE0F?\s*[^\n💾👤]+"""),
        Regex("""origin\s*[:=]?\s*[^\n💾👤]+""", RegexOption.IGNORE_CASE),
        Regex("""[👤💾⚙\uFE0F]"""),
    )

    fun playbackUrl(
        addon: StremioAddon,
        stream: StremioStream,
    ): Pair<String, ExtractorLinkType?>? {
        stream.url?.let { raw ->
            val resolved = StremioApi.resolveUrl(addon, raw) ?: raw
            if (resolved.isNotBlank()) return resolved to linkTypeFor(resolved)
        }
        stream.infoHash?.let { hash ->
            if (hash.isNotBlank()) return magnetUrl(stream, hash) to ExtractorLinkType.MAGNET
        }
        stream.ytId?.let { id ->
            if (id.isNotBlank()) {
                return "https://www.youtube.com/watch?v=$id" to INFER_TYPE
            }
        }
        listOfNotNull(
            stream.externalUrl,
            stream.androidTvUrl,
            stream.playerFrameUrl,
        ).forEach { raw ->
            val resolved = StremioApi.resolveUrl(addon, raw) ?: raw
            if (resolved.isNotBlank()) return resolved to linkTypeFor(resolved)
        }
        stream.archiveUrls.firstOrNull()?.let { raw ->
            val resolved = StremioApi.resolveUrl(addon, raw) ?: raw
            if (resolved.isNotBlank()) return resolved to linkTypeFor(resolved)
        }
        (stream.nzbUrl ?: stream.nzbUrls.firstOrNull())?.let { raw ->
            val resolved = StremioApi.resolveUrl(addon, raw) ?: raw
            if (resolved.isNotBlank()) return resolved to linkTypeFor(resolved)
        }
        return null
    }

    fun buildOption(
        addon: StremioAddon,
        index: Int,
        stream: StremioStream,
        addonSubtitles: List<StremioSubtitle>,
        selectedLanguage: String,
        preferredQuality: String,
    ): StremioStreamOption? {
        val playback = playbackUrl(addon, stream) ?: return null
        val addonTitle = addon.manifest.name
        val name = stream.name?.takeIf { it.isNotBlank() }
            ?: stream.title?.takeIf { it.isNotBlank() }
            ?: stream.description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: addonTitle
        val description = stream.description?.takeIf { it.isNotBlank() } ?: stream.title
        val parsed = parse(description)
        val languageText = listOfNotNull(name, stream.title, description, parsed.cleanDescription)
            .joinToString("\n")
        val languageCodes = LinkedHashSet(
            StremioLanguageCatalog.detectLanguages(languageText)
        )
        stream.subtitles.forEach { subtitle ->
            StremioLanguageCatalog.priorityCode(subtitle.lang)?.let(languageCodes::add)
        }
        val priority = StremioLanguageCatalog.priorityCode(selectedLanguage)
        val quality = qualityOf(languageText)
        return StremioStreamOption(
            key = "$index-${addon.manifest.id}-$name-${description.orEmpty()}",
            index = index,
            addon = addon,
            addonTitle = addonTitle,
            stream = stream,
            name = name,
            description = description,
            cleanDescription = parsed.cleanDescription,
            quality = quality,
            seeds = parsed.seeds,
            size = parsed.size,
            origin = parsed.origin,
            sizeBytes = parseSizeBytes(parsed.size),
            qualityRank = qualityRank(quality, preferredQuality),
            languageCodes = languageCodes,
            isSelectedLanguage = priority != null && languageCodes.contains(priority),
            playbackUrl = playback.first,
            linkType = playback.second,
            subtitles = (stream.subtitles + addonSubtitles).distinctBy { it.url },
        )
    }

    fun sortOptions(
        options: List<StremioStreamOption>,
        criterion: StremioStreamSortCriterion = StremioSettings.getSortCriterion(),
        preferredQuality: String = StremioSettings.getPreferredQuality(),
    ): List<StremioStreamOption> {
        val comparator = compareByDescending<StremioStreamOption> { it.isSelectedLanguage }
            .thenByDescending { option ->
                when (criterion) {
                    StremioStreamSortCriterion.DEFAULT -> (option.seeds ?: 0).toLong()
                    StremioStreamSortCriterion.LANGUAGE ->
                        if (option.isSelectedLanguage) 1L else 0L
                    StremioStreamSortCriterion.SEEDS -> (option.seeds ?: 0).toLong()
                    StremioStreamSortCriterion.SIZE -> option.sizeBytes
                    StremioStreamSortCriterion.QUALITY ->
                        qualityRank(option.quality, preferredQuality).toLong()
                }
            }
            .thenByDescending { it.qualityRank }
            .thenBy { it.index }
        return options.sortedWith(comparator)
    }

    fun parse(description: String?): ParsedStreamMetadata {
        val text = description.orEmpty()
        val seeds = Regex("""👤\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""👥\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""seeds?\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
        val size = Regex("""💾\uFE0F?\s*([^\n👤⚙]+)""")
            .find(text)?.groupValues?.get(1)?.trim()
            ?: Regex("""size\s*[:=]?\s*([^\n👤⚙]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()
        val origin = Regex("""⚙\uFE0F?\s*([^\n💾👤]+)""")
            .find(text)?.groupValues?.get(1)?.trim()
            ?: Regex("""origin\s*[:=]?\s*([^\n💾👤]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()
        var clean = text
        CLEANUP_REGEXES.forEach { clean = clean.replace(it, "") }
        clean = clean.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return ParsedStreamMetadata(
            seeds = seeds,
            size = size?.takeIf { it.isNotBlank() },
            origin = origin?.takeIf { it.isNotBlank() },
            cleanDescription = clean.takeIf { it.isNotBlank() },
        )
    }

    fun parseSizeBytes(size: String?): Long {
        val match = SIZE_REGEX.find(size.orEmpty()) ?: return 0L
        val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return 0L
        val multiplier = when (match.groupValues[2].lowercase(Locale.US)) {
            "kb" -> 1024.0
            "mb" -> 1024.0 * 1024.0
            "gb" -> 1024.0 * 1024.0 * 1024.0
            "tb" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (value * multiplier).toLong()
    }

    fun qualityOf(text: String?): String? =
        when (val raw = QUALITY_REGEX.find(text.orEmpty())?.value?.lowercase(Locale.US)) {
            "4k" -> "2160p"
            else -> raw
        }

    fun qualityRank(quality: String?, preferredQuality: String): Int {
        val base = when (quality?.lowercase(Locale.US)) {
            "2160p" -> 8
            "1080p" -> 6
            "720p" -> 5
            "480p" -> 4
            else -> 0
        }
        val preferredBonus = if (
            !preferredQuality.equals("Any", true) &&
            quality?.equals(preferredQuality, true) == true
        ) 4 else 0
        return base + preferredBonus
    }

    private fun linkTypeFor(url: String): ExtractorLinkType? = when {
        url.startsWith("magnet:", true) -> ExtractorLinkType.MAGNET
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        else -> INFER_TYPE
    }

    private fun magnetUrl(stream: StremioStream, hash: String): String {
        val displayName = listOfNotNull(stream.name, stream.title, stream.filename)
            .firstOrNull { it.isNotBlank() } ?: hash
        val encodedName = runCatching {
            URLEncoder.encode(displayName, "UTF-8")
        }.getOrDefault(hash)
        val index = stream.fileIdx?.let { "&index=$it" }.orEmpty()
        val trackers = (stream.sources + stream.announce)
            .distinct()
            .filter { it.isNotBlank() }
            .joinToString("") { tracker ->
                val encoded = runCatching {
                    URLEncoder.encode(tracker, "UTF-8")
                }.getOrDefault(tracker)
                "&tr=$encoded"
            }
        return "magnet:?xt=urn:btih:$hash&dn=$encodedName$index$trackers"
    }
}

private fun qualityValue(quality: String?): Int = when (quality?.lowercase(Locale.US)) {
    "2160p" -> Qualities.P2160.value
    "1080p" -> Qualities.P1080.value
    "720p" -> Qualities.P720.value
    "480p" -> Qualities.P480.value
    else -> Qualities.Unknown.value
}
