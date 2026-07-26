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

data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
    val followNewMessages: Boolean = true,
    val monitorActiveTurns: Boolean = false,
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
            followNewMessages = preferences.getBoolean("followNewMessages", true),
            monitorActiveTurns = preferences.getBoolean("monitorActiveTurns", false),
        )

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString("themeMode", mode.name).apply()
    }

    fun setFollowNewMessages(enabled: Boolean) {
        preferences.edit().putBoolean("followNewMessages", enabled).apply()
    }

    fun setMonitorActiveTurns(enabled: Boolean) {
        preferences.edit().putBoolean("monitorActiveTurns", enabled).apply()
    }
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
        preferences.edit().clear().apply()
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
