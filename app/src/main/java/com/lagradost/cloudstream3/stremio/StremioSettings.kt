package com.lagradost.cloudstream3.stremio

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Native-client profile settings, adapted from stremio-native's profile.proto,
 * PlayerSettingsScreen, StreamingSettingsScreen and AndroidSettingsScreen.
 *
 * The values that change Stremio stream ordering/playback are consumed locally;
 * the remaining profile values are persisted so the Stremio settings surface
 * does not lose the user's choices.
 */
object StremioSettings {
    private const val PREF_NAME = "stremio_settings"

    private const val STREAM_LANGUAGE = "stream_language"
    private const val AUDIO_LANGUAGE = "audio_language"
    private const val SUBTITLE_LANGUAGE = "subtitle_language"
    private const val INTERFACE_LANGUAGE = "interface_language"
    private const val PREFERRED_QUALITY = "preferred_quality"
    private const val MIN_SEEDS = "min_seeds"
    private const val MIN_DOWNLOAD_SPEED_BPS = "min_download_speed_bps"
    private const val SORT_CRITERION = "sort_criterion"
    private const val AUTO_SWITCH_ON_DEAD_STREAM = "auto_switch_on_dead_stream"
    private const val PROXY_STREAMS = "proxy_streams"
    private const val SEEDING_ENABLED = "seeding_enabled"
    private const val CACHE_SIZE = "cache_size"
    private const val TORRENT_CONNECTIONS = "torrent_connections"
    private const val TRANSCODE_PROFILE = "transcode_profile"
    private const val SUBTITLES_AUTO_SELECT = "subtitles_auto_select"
    private const val SUBTITLES_SIZE = "subtitles_size"
    private const val HARDWARE_DECODING = "hardware_decoding"
    private const val PLAY_IN_BACKGROUND = "play_in_background"
    private const val AUDIO_PASSTHROUGH = "audio_passthrough"
    private const val HIDE_SPOILERS = "hide_spoilers"
    private const val MOBILE_DATA_WARNING = "mobile_data_warning"
    private const val KEEP_SCREEN_ON = "keep_screen_on"
    private const val SERVER_FOREGROUND = "server_foreground"

    private fun prefs(): SharedPreferences? {
        val context = CloudStreamApp.context ?: (getContext() as? Context)
        return context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getString(key: String, fallback: String): String =
        prefs()?.getString(key, fallback) ?: fallback

    private fun setString(key: String, value: String) {
        prefs()?.edit()?.putString(key, value)?.apply()
    }

    private fun getBoolean(key: String, fallback: Boolean): Boolean =
        prefs()?.getBoolean(key, fallback) ?: fallback

    private fun setBoolean(key: String, value: Boolean) {
        prefs()?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun getInt(key: String, fallback: Int): Int =
        prefs()?.getInt(key, fallback) ?: fallback

    private fun setInt(key: String, value: Int) {
        prefs()?.edit()?.putInt(key, value)?.apply()
    }

    private fun getLong(key: String, fallback: Long): Long =
        prefs()?.getLong(key, fallback) ?: fallback

    private fun setLong(key: String, value: Long) {
        prefs()?.edit()?.putLong(key, value)?.apply()
    }

    fun getStreamLanguage(): String = getString(STREAM_LANGUAGE, StremioLanguageCatalog.ANY)
    fun setStreamLanguage(value: String) = setString(STREAM_LANGUAGE, value)

    fun getAudioLanguage(): String = getString(AUDIO_LANGUAGE, "eng")
    fun setAudioLanguage(value: String) = setString(AUDIO_LANGUAGE, value)

    fun getSubtitleLanguage(): String = getString(SUBTITLE_LANGUAGE, "eng")
    fun setSubtitleLanguage(value: String) = setString(SUBTITLE_LANGUAGE, value)

    fun getInterfaceLanguage(): String = getString(INTERFACE_LANGUAGE, "eng")
    fun setInterfaceLanguage(value: String) = setString(INTERFACE_LANGUAGE, value)

    fun getPreferredQuality(): String = getString(PREFERRED_QUALITY, "Any")
    fun setPreferredQuality(value: String) = setString(PREFERRED_QUALITY, value)

    fun getMinSeeds(): Int = getInt(MIN_SEEDS, 1)
    fun setMinSeeds(value: Int) = setInt(MIN_SEEDS, value)

    fun getMinDownloadSpeedBps(): Long = getLong(MIN_DOWNLOAD_SPEED_BPS, 50_000L)
    fun setMinDownloadSpeedBps(value: Long) = setLong(MIN_DOWNLOAD_SPEED_BPS, value)

    fun getSortCriterion(): StremioStreamSortCriterion =
        StremioStreamSortCriterion.fromKey(getString(SORT_CRITERION, "default"))
    fun setSortCriterion(value: StremioStreamSortCriterion) =
        setString(SORT_CRITERION, value.key)

    fun isAutoSwitchOnDeadStream(): Boolean = getBoolean(AUTO_SWITCH_ON_DEAD_STREAM, false)
    fun setAutoSwitchOnDeadStream(value: Boolean) = setBoolean(AUTO_SWITCH_ON_DEAD_STREAM, value)

    fun isProxyStreamsEnabled(): Boolean = getBoolean(PROXY_STREAMS, false)
    fun setProxyStreamsEnabled(value: Boolean) = setBoolean(PROXY_STREAMS, value)

    fun isSeedingEnabled(): Boolean = getBoolean(SEEDING_ENABLED, true)
    fun setSeedingEnabled(value: Boolean) = setBoolean(SEEDING_ENABLED, value)

    fun getCacheSize(): String = getString(CACHE_SIZE, "10737418240")
    fun setCacheSize(value: String) = setString(CACHE_SIZE, value)

    fun getTorrentConnections(): String = getString(TORRENT_CONNECTIONS, "200")
    fun setTorrentConnections(value: String) = setString(TORRENT_CONNECTIONS, value)

    fun getTranscodeProfile(): String = getString(TRANSCODE_PROFILE, "default")
    fun setTranscodeProfile(value: String) = setString(TRANSCODE_PROFILE, value)

    fun isSubtitlesAutoSelect(): Boolean = getBoolean(SUBTITLES_AUTO_SELECT, true)
    fun setSubtitlesAutoSelect(value: Boolean) = setBoolean(SUBTITLES_AUTO_SELECT, value)

    fun getSubtitlesSize(): Int = getInt(SUBTITLES_SIZE, 100)
    fun setSubtitlesSize(value: Int) = setInt(SUBTITLES_SIZE, value)

    fun isHardwareDecoding(): Boolean = getBoolean(HARDWARE_DECODING, true)
    fun setHardwareDecoding(value: Boolean) = setBoolean(HARDWARE_DECODING, value)

    fun isPlayInBackground(): Boolean = getBoolean(PLAY_IN_BACKGROUND, false)
    fun setPlayInBackground(value: Boolean) = setBoolean(PLAY_IN_BACKGROUND, value)

    fun isAudioPassthrough(): Boolean = getBoolean(AUDIO_PASSTHROUGH, false)
    fun setAudioPassthrough(value: Boolean) = setBoolean(AUDIO_PASSTHROUGH, value)

    fun isHideSpoilers(): Boolean = getBoolean(HIDE_SPOILERS, false)
    fun setHideSpoilers(value: Boolean) = setBoolean(HIDE_SPOILERS, value)

    fun isMobileDataWarning(): Boolean = getBoolean(MOBILE_DATA_WARNING, true)
    fun setMobileDataWarning(value: Boolean) = setBoolean(MOBILE_DATA_WARNING, value)

    fun isKeepScreenOn(): Boolean = getBoolean(KEEP_SCREEN_ON, true)
    fun setKeepScreenOn(value: Boolean) = setBoolean(KEEP_SCREEN_ON, value)

    fun isServerInForeground(): Boolean = getBoolean(SERVER_FOREGROUND, true)
    fun setServerInForeground(value: Boolean) = setBoolean(SERVER_FOREGROUND, value)
}

enum class StremioStreamSortCriterion(val key: String, val label: String) {
    DEFAULT("default", "Default"),
    LANGUAGE("language", "Language"),
    SEEDS("seeds", "Seeds"),
    SIZE("size", "Size"),
    QUALITY("quality", "Quality");

    companion object {
        fun fromKey(key: String?): StremioStreamSortCriterion =
            entries.firstOrNull { it.key.equals(key, true) } ?: DEFAULT
    }
}
