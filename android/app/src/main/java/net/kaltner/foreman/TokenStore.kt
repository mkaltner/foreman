package net.kaltner.foreman

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedConnection(val host: String, val deviceName: String, val token: String)

enum class ThemeMode { System, Light, Dark }

enum class AccentColor { Purple, Blue, Teal, Green, Orange, Red, Pink }

internal fun parseAccentColor(value: String?): AccentColor =
    AccentColor.values().firstOrNull { it.name == value } ?: AccentColor.Purple

data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
    val accentColor: AccentColor = AccentColor.Purple,
    val followNewMessages: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val monitorActiveTurns: Boolean = false,
    val accessLevel: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val searchQuery: String = "",
    val searchRepository: String = "",
    val searchStatus: SessionSearchStatus = SessionSearchStatus.All,
    val searchDateRange: SessionDateRange = SessionDateRange.All,
    val searchDateFrom: String = "",
    val searchDateTo: String = "",
    val pinnedSessionIds: Set<String> = emptySet(),
    val hiddenSessionIds: Set<String> = emptySet(),
)

class PreferenceStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("foreman_preferences", Context.MODE_PRIVATE)

    fun load(): UiPreferences =
        UiPreferences(
            themeMode =
                runCatching {
                    ThemeMode.valueOf(
                        preferences.getString("themeMode", ThemeMode.System.name)!!,
                    )
                }.getOrDefault(ThemeMode.System),
            accentColor = parseAccentColor(preferences.getString("accentColor", null)),
            followNewMessages = preferences.getBoolean("followNewMessages", true),
            hapticsEnabled = preferences.getBoolean("hapticsEnabled", true),
            monitorActiveTurns = preferences.getBoolean("monitorActiveTurns", false),
            accessLevel = preferences.getString("accessLevel", null),
            model = preferences.getString("model", null),
            reasoningEffort = preferences.getString("reasoningEffort", null),
            searchQuery = preferences.getString("sessionSearchQuery", "").orEmpty().take(500),
            searchRepository = preferences.getString("sessionSearchRepository", "").orEmpty(),
            searchStatus = enumPreference("sessionSearchStatus", SessionSearchStatus.All),
            searchDateRange = enumPreference("sessionSearchDateRange", SessionDateRange.All),
            searchDateFrom = preferences.getString("sessionSearchDateFrom", "").orEmpty(),
            searchDateTo = preferences.getString("sessionSearchDateTo", "").orEmpty(),
            pinnedSessionIds = sessionIds("pinnedSessionIds"),
            hiddenSessionIds = sessionIds("hiddenSessionIds"),
        )

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString("themeMode", mode.name).apply()
    }

    fun setAccentColor(color: AccentColor) {
        preferences.edit().putString("accentColor", color.name).apply()
    }

    fun setFollowNewMessages(enabled: Boolean) {
        preferences.edit().putBoolean("followNewMessages", enabled).apply()
    }

    fun setHapticsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("hapticsEnabled", enabled).apply()
    }

    fun setMonitorActiveTurns(enabled: Boolean) {
        preferences.edit().putBoolean("monitorActiveTurns", enabled).apply()
    }

    fun setModelRoute(model: String?, reasoningEffort: String?) {
        preferences.edit()
            .putString("model", model)
            .putString("reasoningEffort", reasoningEffort)
            .apply()
    }

    fun setAccessLevel(accessLevel: String?) {
        preferences.edit().putString("accessLevel", accessLevel).apply()
    }

    fun setSessionSearch(filters: SessionSearchFilters) {
        preferences.edit()
            .putString("sessionSearchQuery", filters.query.take(500))
            .putString("sessionSearchRepository", filters.repository)
            .putString("sessionSearchStatus", filters.status.name)
            .putString("sessionSearchDateRange", filters.dateRange.name)
            .putString("sessionSearchDateFrom", filters.dateFrom)
            .putString("sessionSearchDateTo", filters.dateTo)
            .apply()
    }

    fun setPinnedSessionIds(ids: Set<String>) {
        preferences.edit().putStringSet("pinnedSessionIds", ids.toList().takeLast(1000).toSet()).apply()
    }

    fun setHiddenSessionIds(ids: Set<String>) {
        preferences.edit().putStringSet("hiddenSessionIds", ids.toList().takeLast(1000).toSet()).apply()
    }

    fun retainSessionIds(ids: Set<String>) {
        setPinnedSessionIds(sessionIds("pinnedSessionIds").intersect(ids))
        setHiddenSessionIds(sessionIds("hiddenSessionIds").intersect(ids))
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, fallback.name)!!) }
            .getOrDefault(fallback)

    private fun sessionIds(key: String): Set<String> =
        preferences.getStringSet(key, emptySet()).orEmpty()
            .filterTo(linkedSetOf()) { it.length <= 100 }
}

class TokenStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("foreman_connection", Context.MODE_PRIVATE)
    private val alias = "foreman_device_token"

    fun save(host: String, deviceName: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("host", host)
            .putString("deviceName", deviceName)
            .putString("tokenIv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("tokenData", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): SavedConnection? {
        val host = preferences.getString("host", null) ?: return null
        val name = preferences.getString("deviceName", null) ?: "Android"
        val iv = preferences.getString("tokenIv", null) ?: return null
        val data = preferences.getString("tokenData", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            SavedConnection(
                host,
                name,
                cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
                    .toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
