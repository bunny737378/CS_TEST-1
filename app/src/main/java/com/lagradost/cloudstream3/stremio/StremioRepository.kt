package com.lagradost.cloudstream3.stremio

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local bridge state for the standalone Stremio integration.
 *
 * Account metadata and the installed addon collection are stored locally. When a Stremio
 * account is signed in, addon changes are also pushed to the official addonCollectionSet
 * endpoint so the same collection is available in other Stremio clients.
 */
object StremioRepository {
    private const val PREF_NAME = "stremio_bridge"
    private const val KEY_AUTH = "auth_key"
    private const val KEY_EMAIL = "email"
    private const val KEY_SELECTED = "account_selected"
    private const val KEY_ADDONS = "addons"

    /** App-level scope: recomposing the settings screen must not cancel an in-flight login. */
    val stremioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Official default addons used when a Stremio account has no installed collection yet. */
    private val DEFAULT_ADDON_URLS = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://v3-channels.strem.io/manifest.json",
        "https://watchhub.strem.io/manifest.json",
        "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json",
        "https://opensubtitles.strem.io/stremio/v1",
    )

    private fun prefs(): SharedPreferences? {
        val context = CloudStreamApp.context ?: (getContext() as? Context)
        return context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences =
        prefs() ?: throw IllegalStateException("Stremio account storage is not ready")

    private fun getString(key: String): String? =
        prefs()?.getString(key, null)?.takeIf { it.isNotBlank() }

    private fun setString(key: String, value: String?) {
        val editor = requirePrefs().edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.commit()
    }

    private fun setBoolean(key: String, value: Boolean) {
        requirePrefs().edit().putBoolean(key, value).commit()
    }

    fun getAuthKey(): String? = getString(KEY_AUTH)
    fun getEmail(): String? = getString(KEY_EMAIL)
    fun isLoggedIn(): Boolean = !getAuthKey().isNullOrBlank()
    fun isSelected(): Boolean = prefs()?.getBoolean(KEY_SELECTED, false) == true

    private fun saveAccount(authKey: String, email: String) {
        setString(KEY_AUTH, authKey)
        setString(KEY_EMAIL, email)
        setBoolean(KEY_SELECTED, true)
    }

    fun logout() {
        setString(KEY_AUTH, null)
        setString(KEY_EMAIL, null)
        setBoolean(KEY_SELECTED, false)
    }

    fun getAddons(): List<StremioAddon> {
        val raw = getString(KEY_ADDONS) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { StremioAddon.fromDescriptor(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAddons(addons: List<StremioAddon>) {
        val array = JSONArray()
        addons.forEach { addon ->
            runCatching { array.put(JSONObject(addon.rawJson)) }
        }
        setString(KEY_ADDONS, array.toString())
    }

    private fun isLocalAddon(addon: StremioAddon): Boolean {
        val url = addon.transportUrl.lowercase()
        return url.contains("127.0.0.1") || url.contains("localhost")
    }

    private suspend fun pushToServer(): Boolean {
        val authKey = getAuthKey() ?: return false
        return StremioApi.addonCollectionSet(
            authKey,
            getAddons().map { it.descriptorMap() }
        )
    }

    private fun Throwable.safeMessage(): String {
        val raw = message?.takeIf { it.isNotBlank() } ?: toString()
        return raw.redactStremioSecrets().take(300)
    }

    /** Returns null on success, or a real API/transport error for the settings UI. */
    suspend fun login(email: String, password: String): String? = try {
        val user = StremioApi.login(email, password)
        saveAccount(user.authKey, user.email ?: email.trim())
        null
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        t.safeMessage()
    }

    suspend fun register(email: String, password: String): String? = try {
        val user = StremioApi.register(email, password)
        saveAccount(user.authKey, user.email ?: email.trim())
        null
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        t.safeMessage()
    }

    suspend fun loginWithAuthKey(authKey: String): String? = try {
        val user = StremioApi.loginWithAuthKey(authKey)
        saveAccount(user.authKey, user.email ?: "")
        null
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        t.safeMessage()
    }

    /** Pull the account collection, then install the official defaults on a fresh account. */
    suspend fun bootstrapAccount(): Boolean {
        val synced = syncFromServer()
        val existing = getAddons()
        if (synced && existing.isNotEmpty()) return true
        val installed = installDefaults()
        return installed > 0 || existing.isNotEmpty()
    }

    suspend fun syncFromServer(): Boolean {
        val authKey = getAuthKey() ?: return false
        val descriptors = StremioApi.addonCollectionGet(authKey) ?: return false
        val addons = descriptors.mapNotNull { StremioAddon.fromDescriptor(it) }
            .filterNot { isLocalAddon(it) }
            .distinctBy { it.transportUrl }
        saveAddons(addons)
        return addons.isNotEmpty()
    }

    suspend fun installDefaults(): Int {
        var installed = 0
        for (url in DEFAULT_ADDON_URLS) {
            val normalized = StremioApi.normalizeTransportUrl(url)
            if (getAddons().any { it.transportUrl == normalized }) continue
            val addon = StremioApi.fetchManifest(url) ?: continue
            val current = getAddons().toMutableList()
            current.add(addon)
            saveAddons(current)
            installed++
        }
        if (installed > 0) pushToServer()
        return installed
    }

    suspend fun installAddon(url: String): Boolean {
        val addon = StremioApi.fetchManifest(url) ?: return false
        val normalized = StremioApi.normalizeTransportUrl(url)
        val current = getAddons()
            .filterNot { it.transportUrl == normalized || it.transportUrl == addon.transportUrl }
            .toMutableList()
        current.add(addon)
        saveAddons(current)
        pushToServer()
        return true
    }

    suspend fun uninstallAddon(transportUrl: String): Boolean {
        val current = getAddons()
        val updated = current.filterNot {
            it.transportUrl == transportUrl ||
                it.transportUrl.trimEnd('/') == transportUrl.trimEnd('/')
        }
        if (updated.size == current.size) return false
        saveAddons(updated)
        pushToServer()
        return true
    }
}
