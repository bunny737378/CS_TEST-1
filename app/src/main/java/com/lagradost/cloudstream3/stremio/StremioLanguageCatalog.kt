package com.lagradost.cloudstream3.stremio

import java.util.Locale

/**
 * ISO-639 language handling adapted from stremio-native's LanguageCatalog and
 * app/src/main/assets/languageNames.json. It is used for language settings,
 * stream-language priority and language flags on stream cards.
 */
object StremioLanguageCatalog {
    const val ANY = "any"
    const val LOCAL = "local"

    private val ALIASES: Map<String, String> = linkedMapOf(
        "en" to "eng", "eng" to "eng", "english" to "eng", "englishus" to "eng",
        "englishuk" to "eng", "uk" to "eng", "us" to "eng", "gb" to "eng",
        "hi" to "hin", "hin" to "hin", "hindi" to "hin", "in" to "hin",
        "bn" to "ben", "ben" to "ben", "bengali" to "ben", "bangla" to "ben",
        "ta" to "tam", "tam" to "tam", "tamil" to "tam",
        "te" to "tel", "tel" to "tel", "telugu" to "tel",
        "mr" to "mar", "mar" to "mar", "marathi" to "mar",
        "es" to "spa", "spa" to "spa", "spanish" to "spa", "esp" to "spa",
        "esla" to "spa", "mx" to "spa", "argentina" to "spa",
        "fr" to "fre", "fre" to "fre", "fra" to "fre", "french" to "fre",
        "de" to "ger", "ger" to "ger", "deu" to "ger", "german" to "ger",
        "it" to "ita", "ita" to "ita", "italian" to "ita",
        "pt" to "por", "por" to "por", "portuguese" to "por",
        "pb" to "pob", "pob" to "pob", "ptbr" to "pob", "brazilian" to "pob",
        "br" to "pob", "portuguesebrazil" to "pob",
        "ru" to "rus", "rus" to "rus", "russian" to "rus",
        "ja" to "jpn", "jp" to "jpn", "jpn" to "jpn", "japanese" to "jpn",
        "ko" to "kor", "kor" to "kor", "korean" to "kor", "kr" to "kor",
        "zh" to "zho", "zho" to "zho", "chi" to "zho", "chinese" to "zho",
        "cn" to "zho", "mandarin" to "zho",
        "th" to "tha", "tha" to "tha", "thai" to "tha",
        "ar" to "ara", "ara" to "ara", "arabic" to "ara", "ae" to "ara", "sa" to "ara",
        "tr" to "tur", "tur" to "tur", "turkish" to "tur",
        "nl" to "nld", "nld" to "nld", "dut" to "nld", "dutch" to "nld",
        "pl" to "pol", "pol" to "pol", "polish" to "pol",
        "id" to "ind", "ind" to "ind", "indonesian" to "ind",
        "vi" to "vie", "vie" to "vie", "vietnamese" to "vie",
        "ms" to "msa", "msa" to "msa", "malay" to "msa",
        "sv" to "swe", "swe" to "swe", "swedish" to "swe",
        "no" to "nor", "nor" to "nor", "norwegian" to "nor",
        "da" to "dan", "dan" to "dan", "danish" to "dan",
        "fi" to "fin", "fin" to "fin", "finnish" to "fin",
        "el" to "ell", "ell" to "ell", "gre" to "ell", "greek" to "ell",
        "he" to "heb", "heb" to "heb", "hebrew" to "heb",
        "ua" to "ukr", "ukr" to "ukr", "ukrainian" to "ukr",
        "cs" to "ces", "ces" to "ces", "czech" to "ces",
        "ro" to "ron", "ron" to "ron", "rum" to "ron", "romanian" to "ron",
        "hu" to "hun", "hun" to "hun", "hungarian" to "hun",
        "bg" to "bul", "bul" to "bul", "bulgarian" to "bul",
        "hr" to "hrv", "hrv" to "hrv", "croatian" to "hrv",
        "sr" to "srp", "srp" to "srp", "serbian" to "srp",
        "sk" to "slk", "slk" to "slk", "slovak" to "slk",
        "sl" to "slv", "slv" to "slv", "slovenian" to "slv",
        "fa" to "fas", "fas" to "fas", "per" to "fas", "persian" to "fas", "farsi" to "fas",
        "ur" to "urd", "urd" to "urd", "urdu" to "urd",
        "af" to "afr", "afr" to "afr", "afrikaans" to "afr",
    )

    private val COUNTRY_TO_CODE: Map<String, String> = linkedMapOf(
        "US" to "eng", "GB" to "eng", "AU" to "eng", "CA" to "eng", "NZ" to "eng",
        "IN" to "hin", "FR" to "fre", "DE" to "ger", "IT" to "ita", "ES" to "spa",
        "MX" to "spa", "AR" to "spa", "BR" to "pob", "PT" to "por", "RU" to "rus",
        "JP" to "jpn", "KR" to "kor", "CN" to "zho", "TH" to "tha", "TR" to "tur",
        "NL" to "nld", "PL" to "pol", "AE" to "ara", "SA" to "ara",
        "ID" to "ind", "VN" to "vie", "MY" to "msa", "SE" to "swe", "NO" to "nor",
        "DK" to "dan", "FI" to "fin", "GR" to "ell", "IL" to "heb", "UA" to "ukr",
        "CZ" to "ces", "RO" to "ron", "HU" to "hun", "BG" to "bul", "HR" to "hrv",
        "RS" to "srp", "SK" to "slk", "SI" to "slv", "IR" to "fas", "PK" to "urd",
        "ZA" to "afr",
    )

    private val TOKEN_REGEX = Regex("[A-Za-z]+")

    fun options(): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()
        result[ANY] = "Any / No preference"
        result[LOCAL] = "Local / device language"
        for (twoLetter in Locale.getISOLanguages()) {
            val locale = runCatching { Locale(twoLetter) }.getOrNull() ?: continue
            val three = runCatching { locale.isO3Language.lowercase(Locale.US) }.getOrNull()
                ?.takeIf { it.length == 3 } ?: continue
            val label = locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }
                ?: twoLetter.uppercase(Locale.US)
            if (!result.containsKey(three)) result[three] = label
        }
        result["pob"] = "Portuguese (Brazil)"
        return result.toList().sortedWith(
            compareBy<Pair<String, String>> { (code, _) ->
                when (code) {
                    ANY -> "0"
                    LOCAL -> "1"
                    else -> "2"
                }
            }.thenBy { (_, label) -> label.lowercase(Locale.US) }
        )
    }

    fun toCode(value: String?): String {
        val raw = value?.trim()?.lowercase(Locale.US).orEmpty()
        if (raw.isBlank() || raw == ANY || raw == "none") return ANY
        if (raw == LOCAL) return LOCAL
        ALIASES[raw]?.let { return it }
        if (raw.length == 3 && raw.all { it in 'a'..'z' }) return raw
        val byLabel = options().firstOrNull { (_, label) ->
            label.equals(value?.trim(), true)
        }?.first
        if (byLabel != null) return byLabel
        return raw.take(3).lowercase(Locale.US)
    }

    fun isNoPreference(code: String?): Boolean = toCode(code) == ANY

    /** Resolves "local" to the device language and "any" to null. */
    fun priorityCode(code: String?): String? = when (val normalized = toCode(code)) {
        ANY -> null
        LOCAL -> runCatching { Locale.getDefault().isO3Language.lowercase(Locale.US) }
            .getOrNull()?.takeIf { it.length == 3 }
        else -> normalized
    }

    fun label(code: String?): String {
        val normalized = toCode(code)
        return when (normalized) {
            ANY -> "Any / No preference"
            LOCAL -> "Local / device language"
            "pob" -> "Portuguese (Brazil)"
            else -> options().firstOrNull { it.first == normalized }?.second
                ?: normalized.uppercase(Locale.US)
        }
    }

    fun toBcp47(code: String?): String {
        val normalized = toCode(code)
        if (normalized == ANY || normalized == LOCAL) {
            return Locale.getDefault().language.takeIf { it.length >= 2 } ?: "en"
        }
        if (normalized == "pob") return "pt"
        return Locale.getISOLanguages().firstOrNull { two ->
            runCatching { Locale(two).isO3Language.equals(normalized, true) }.getOrDefault(false)
        } ?: normalized.take(2)
    }

    fun matches(text: String?, selectedCode: String?): Boolean {
        val effective = priorityCode(selectedCode) ?: return false
        return detectLanguages(text).contains(effective)
    }

    fun detectLanguages(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        result.addAll(detectFlags(text))
        for (match in TOKEN_REGEX.findAll(text)) {
            val token = match.value.lowercase(Locale.US)
            ALIASES[token]?.let { result.add(it) }
            if (token.length == 3 && token in ALIASES.values) result.add(token)
        }
        return result
    }

    fun flagLabel(codes: Collection<String>): String =
        codes.mapNotNull(::flagFor).distinct().joinToString(" ")

    fun textFlags(text: String?): String = flagLabel(detectLanguages(text))

    private fun flagFor(code: String): String? {
        val normalized = toCode(code)
        if (normalized == ANY) return null
        val country = when (normalized) {
            "pob" -> "BR"
            "eng" -> "GB"
            "spa" -> "ES"
            "zho" -> "CN"
            "hin" -> "IN"
            "ara" -> "AE"
            "fas" -> "IR"
            "urd" -> "PK"
            "afr" -> "ZA"
            else -> Locale.getISOLanguages().firstOrNull { two ->
                runCatching { Locale(two).isO3Language.equals(normalized, true) }
                    .getOrDefault(false)
            }?.uppercase(Locale.US)
        } ?: return null
        if (country.length != 2 || !country.all { it in 'A'..'Z' }) return null
        val base = 0x1F1E6
        return String(Character.toChars(base + country[0].code - 'A'.code)) +
            String(Character.toChars(base + country[1].code - 'A'.code))
    }

    private fun detectFlags(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        var index = 0
        while (index < text.length) {
            val first = Character.codePointAt(text, index)
            index += Character.charCount(first)
            if (first !in 0x1F1E6..0x1F1FF || index >= text.length) continue
            val second = Character.codePointAt(text, index)
            if (second !in 0x1F1E6..0x1F1FF) continue
            index += Character.charCount(second)
            val country = buildString {
                append((first - 0x1F1E6 + 'A'.code).toChar())
                append((second - 0x1F1E6 + 'A'.code).toChar())
            }
            COUNTRY_TO_CODE[country]?.let { result.add(it) }
        }
        return result
    }
}
