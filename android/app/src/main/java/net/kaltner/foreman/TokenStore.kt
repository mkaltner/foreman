package net.kaltner.foreman

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SavedHost(
    val id: String,
    val displayName: String,
    val host: String,
    val tcpPort: Int,
    val webPort: Int,
    val deviceToken: String,
    val pairedAt: Long,
    val lastConnectedAt: Long?,
    val lastKnownStatus: String,
    val runtimeMode: String?,
    val isDefault: Boolean,
) {
    override fun toString(): String =
        "SavedHost(id=$id, displayName=$displayName, host=$host, tcpPort=$tcpPort, " +
            "webPort=$webPort, deviceToken=<redacted>, pairedAt=$pairedAt, " +
            "lastConnectedAt=$lastConnectedAt, lastKnownStatus=$lastKnownStatus, " +
            "runtimeMode=$runtimeMode, isDefault=$isDefault)"
}

data class SavedHostSummary(
    val id: String,
    val displayName: String,
    val host: String,
    val tcpPort: Int,
    val webPort: Int,
    val pairedAt: Long,
    val lastConnectedAt: Long?,
    val lastKnownStatus: String,
    val runtimeMode: String?,
    val isDefault: Boolean,
)

fun SavedHost.summary(): SavedHostSummary =
    SavedHostSummary(
        id,
        displayName,
        host,
        tcpPort,
        webPort,
        pairedAt,
        lastConnectedAt,
        lastKnownStatus,
        runtimeMode,
        isDefault,
    )

internal fun SavedHost.tcpEndpoint(): String =
    if (host.contains(':')) "[$host]:$tcpPort" else "$host:$tcpPort"

internal fun suggestedHostDisplayName(host: String): String =
    if (host.trim().lowercase() in setOf("localhost", "127.0.0.1", "::1")) {
        "Local Foreman"
    } else {
        host.trim().ifBlank { "Foreman host" }
    }

@Serializable
private data class HostRecord(
    val id: String,
    val displayName: String,
    val host: String,
    val tcpPort: Int = 8765,
    val webPort: Int = 8766,
    val pairedAt: Long,
    val lastConnectedAt: Long? = null,
    val lastKnownStatus: String = "disconnected",
    val runtimeMode: String? = null,
    val isDefault: Boolean = false,
)

class HostStore(private val context: Context) {
    private val preferences =
        context.getSharedPreferences(CONNECTION_PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val alias = "foreman_device_token"

    init {
        migrateLegacyConnection()
        migrateAndroidNamedDefaultHost()
    }

    fun all(): List<SavedHost> =
        records().mapNotNull { record ->
            decryptToken(record.id)?.let { record.savedHost(it) }
        }

    fun active(): SavedHost? {
        val hosts = all()
        val activeId = preferences.getString(ACTIVE_HOST_KEY, null)
        return hosts.firstOrNull { it.id == activeId }
            ?: hosts.firstOrNull { it.isDefault }
            ?: hosts.firstOrNull()
    }

    fun load(hostId: String): SavedHost? = all().firstOrNull { it.id == hostId }

    fun save(
        displayName: String,
        endpoint: HostPort,
        deviceToken: String,
        webPort: Int = 8766,
    ): SavedHost {
        val existing = records()
        val record =
            HostRecord(
                id = UUID.randomUUID().toString(),
                displayName = displayName.trim().ifBlank { suggestedHostDisplayName(endpoint.host) },
                host = endpoint.host,
                tcpPort = endpoint.port,
                webPort = webPort,
                pairedAt = System.currentTimeMillis(),
                isDefault = existing.isEmpty(),
            )
        encryptToken(record.id, deviceToken)
        writeRecords(existing + record, record.id)
        return record.savedHost(deviceToken)
    }

    fun select(hostId: String): SavedHost? {
        val selected = load(hostId) ?: return null
        preferences.edit().putString(ACTIVE_HOST_KEY, hostId).commit()
        return selected
    }

    fun rename(hostId: String, displayName: String) {
        val name = displayName.trim()
        if (name.isBlank()) return
        writeRecords(records().map { if (it.id == hostId) it.copy(displayName = name) else it })
    }

    fun updateConnection(
        hostId: String,
        status: String,
        runtimeMode: String? = null,
        connectedAt: Long? = null,
    ) {
        writeRecords(
            records().map {
                if (it.id == hostId) {
                    it.copy(
                        lastKnownStatus = status,
                        runtimeMode = runtimeMode ?: it.runtimeMode,
                        lastConnectedAt = connectedAt ?: it.lastConnectedAt,
                    )
                } else {
                    it
                }
            },
        )
    }

    fun forget(hostId: String): SavedHost? {
        val remaining = records().filterNot { it.id == hostId }
        preferences.edit()
            .remove(tokenIvKey(hostId))
            .remove(tokenDataKey(hostId))
            .commit()
        context.getSharedPreferences(preferenceFile(hostId), Context.MODE_PRIVATE)
            .edit().clear().commit()
        HostOverviewStore(context).forget(hostId)
        AttentionNotificationStore(context).forget(hostId)
        val activeId = preferences.getString(ACTIVE_HOST_KEY, null)
        val nextActive =
            if (activeId == hostId) {
                remaining.firstOrNull { it.isDefault }?.id ?: remaining.firstOrNull()?.id
            } else {
                activeId
            }
        writeRecords(remaining, nextActive)
        return nextActive?.let(::load)
    }

    private fun records(): List<HostRecord> {
        val encoded = preferences.getString(HOSTS_KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<HostRecord>>(encoded) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.host.isNotBlank() && validPort(it.tcpPort) && validPort(it.webPort) }
    }

    private fun writeRecords(records: List<HostRecord>, activeHostId: String? = null) {
        val defaultId = records.firstOrNull { it.isDefault }?.id ?: records.firstOrNull()?.id
        val normalized = records.map { it.copy(isDefault = it.id == defaultId) }
        val selected =
            activeHostId?.takeIf { candidate -> normalized.any { it.id == candidate } }
                ?: preferences.getString(ACTIVE_HOST_KEY, null)
                    ?.takeIf { candidate -> normalized.any { it.id == candidate } }
                ?: defaultId
        preferences.edit()
            .putString(HOSTS_KEY, json.encodeToString(normalized))
            .apply { if (selected == null) remove(ACTIVE_HOST_KEY) else putString(ACTIVE_HOST_KEY, selected) }
            .commit()
    }

    private fun migrateLegacyConnection() {
        if (preferences.contains(HOSTS_KEY)) return
        val rawHost = preferences.getString("host", null) ?: return
        val token = decryptLegacyToken() ?: return
        val endpoint = runCatching { parseHost(rawHost) }.getOrNull() ?: return
        val id = UUID.randomUUID().toString()
        val record =
            HostRecord(
                id = id,
                displayName = suggestedHostDisplayName(endpoint.host),
                host = endpoint.host,
                tcpPort = endpoint.port,
                webPort = 8766,
                pairedAt = System.currentTimeMillis(),
                isDefault = true,
            )
        encryptToken(id, token)
        writeRecords(listOf(record), id)
        copyLegacyPreferences(id)
        preferences.edit()
            .remove("host")
            .remove("deviceName")
            .remove("tokenIv")
            .remove("tokenData")
            .commit()
    }

    private fun migrateAndroidNamedDefaultHost() {
        val stored = records()
        val legacyDefault = stored.firstOrNull { it.isDefault && it.displayName == "Android" }
            ?: return
        writeRecords(
            stored.map { record ->
                if (record.id == legacyDefault.id) {
                    record.copy(displayName = suggestedHostDisplayName(record.host))
                } else {
                    record
                }
            },
        )
    }

    private fun copyLegacyPreferences(hostId: String) {
        val legacy = context.getSharedPreferences(LEGACY_UI_PREFERENCES, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val target = context.getSharedPreferences(preferenceFile(hostId), Context.MODE_PRIVATE)
        val editor = target.edit()
        legacy.all.forEach { (key, value) -> editor.putPreference(key, value) }
        editor.commit()
        legacy.edit().clear().commit()
    }

    private fun encryptToken(hostId: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(tokenIvKey(hostId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(tokenDataKey(hostId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }

    private fun decryptToken(hostId: String): String? =
        decrypt(
            preferences.getString(tokenIvKey(hostId), null),
            preferences.getString(tokenDataKey(hostId), null),
        )

    private fun decryptLegacyToken(): String? =
        decrypt(
            preferences.getString("tokenIv", null),
            preferences.getString("tokenData", null),
        )

    private fun decrypt(iv: String?, data: String?): String? {
        if (iv == null || data == null) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
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

    private fun HostRecord.savedHost(token: String): SavedHost =
        SavedHost(
            id,
            displayName,
            host,
            tcpPort,
            webPort,
            token,
            pairedAt,
            lastConnectedAt,
            lastKnownStatus,
            runtimeMode,
            isDefault,
        )

    private fun tokenIvKey(hostId: String) = "tokenIv.$hostId"
    private fun tokenDataKey(hostId: String) = "tokenData.$hostId"

    companion object {
        private const val CONNECTION_PREFERENCES = "foreman_connection"
        private const val LEGACY_UI_PREFERENCES = "foreman_preferences"
        private const val HOSTS_KEY = "hosts.v2"
        private const val ACTIVE_HOST_KEY = "activeHostId"

        fun preferenceFile(hostId: String) = "foreman_preferences.$hostId"
    }
}

class PreferenceStore(context: Context, hostId: String?) {
    private val preferences =
        context.getSharedPreferences(
            hostId?.let(HostStore::preferenceFile) ?: "foreman_preferences",
            Context.MODE_PRIVATE,
        )

    fun load(): UiPreferences =
        UiPreferences(
            themeMode =
                runCatching {
                    ThemeMode.valueOf(
                        preferences.getString("themeMode", ThemeMode.System.name)!!,
                    )
                }.getOrDefault(ThemeMode.System),
            accentColor = parseAccentColor(preferences.getString("accentColor", null)),
            activityDetail = enumPreference("activityDetail", ActivityDetail.Focused),
            groupSessionsByRepository = preferences.getBoolean("groupSessionsByRepository", true),
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
            collapsedRepositoryIds =
                preferences.getStringSet("collapsedRepositoryIds", emptySet()).orEmpty()
                    .filterTo(linkedSetOf()) { it.length <= 1000 },
            lastProvider = preferences.getString("lastProvider", PROVIDER_CODEX)
                ?.takeIf { it in setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE) }
                ?: PROVIDER_CODEX,
            claudeModel = preferences.getString("claudeModel", "sonnet") ?: "sonnet",
            claudePermissionMode =
                preferences.getString("claudePermissionMode", "default") ?: "default",
            selectedSessionProvider =
                preferences.getString("selectedSessionProvider", PROVIDER_CODEX)
                    ?.takeIf(::supportedProvider)
                    ?: PROVIDER_CODEX,
            selectedSessionId = preferences.getString("selectedSessionId", null)
                ?.takeIf { it.isNotBlank() && it.length <= 1000 },
        )

    fun setThemeMode(mode: ThemeMode) { preferences.edit().putString("themeMode", mode.name).apply() }
    fun setAccentColor(color: AccentColor) { preferences.edit().putString("accentColor", color.name).apply() }
    fun setActivityDetail(detail: ActivityDetail) { preferences.edit().putString("activityDetail", detail.name).apply() }
    fun setGroupSessionsByRepository(enabled: Boolean) { preferences.edit().putBoolean("groupSessionsByRepository", enabled).apply() }
    fun setFollowNewMessages(enabled: Boolean) { preferences.edit().putBoolean("followNewMessages", enabled).apply() }
    fun setHapticsEnabled(enabled: Boolean) { preferences.edit().putBoolean("hapticsEnabled", enabled).apply() }
    fun setMonitorActiveTurns(enabled: Boolean) { preferences.edit().putBoolean("monitorActiveTurns", enabled).apply() }
    fun setModelRoute(model: String?, reasoningEffort: String?) {
        preferences.edit().putString("model", model).putString("reasoningEffort", reasoningEffort).apply()
    }
    fun setAccessLevel(accessLevel: String?) { preferences.edit().putString("accessLevel", accessLevel).apply() }
    fun setClaudeRoute(model: String, permissionMode: String) {
        preferences.edit()
            .putString("claudeModel", model)
            .putString("claudePermissionMode", permissionMode)
            .apply()
    }
    fun setLastProvider(provider: String) {
        preferences.edit().putString("lastProvider", provider).apply()
    }
    fun setSelectedSession(provider: String, sessionId: String?) {
        preferences.edit()
            .putString("selectedSessionProvider", provider)
            .putString("selectedSessionId", sessionId)
            .commit()
    }
    fun loadDrafts(): Map<String, String> =
        preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith("draft.")) return@mapNotNull null
            val rawKey = key.removePrefix("draft.")
            val providerKey = legacySessionKey(rawKey)
            (value as? String)?.take(100_000)?.let { providerKey to it }
        }.toMap()

    fun loadAccountUsage(): AccountUsage =
        decodeStoredAccountUsage(preferences.getString("accountUsage.v1", null))

    fun setAccountUsage(usage: AccountUsage) {
        preferences.edit().putString(
            "accountUsage.v1",
            encodeStoredAccountUsage(usage),
        ).commit()
    }

    fun setDraft(provider: String, sessionId: String, text: String) {
        val key = "draft.${providerSessionKey(provider, sessionId)}"
        if (text.isEmpty()) preferences.edit().remove(key).apply()
        else preferences.edit().putString(key, text.take(100_000)).apply()
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
    fun setCollapsedRepositoryIds(ids: Set<String>) {
        preferences.edit()
            .putStringSet("collapsedRepositoryIds", ids.toList().takeLast(1000).toSet())
            .apply()
    }
    fun retainSessionIds(ids: Set<String>) {
        setPinnedSessionIds(sessionIds("pinnedSessionIds").intersect(ids))
        setHiddenSessionIds(sessionIds("hiddenSessionIds").intersect(ids))
    }
    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, fallback.name)!!) }.getOrDefault(fallback)
    private fun sessionIds(key: String): Set<String> =
        preferences.getStringSet(key, emptySet()).orEmpty()
            .asSequence()
            .filter { it.length <= 300 }
            .map(::legacySessionKey)
            .toCollection(linkedSetOf())
}

enum class ThemeMode { System, Light, Dark }
enum class AccentColor { Purple, Blue, Teal, Green, Orange, Red, Pink }
internal fun parseAccentColor(value: String?): AccentColor =
    AccentColor.values().firstOrNull { it.name == value } ?: AccentColor.Purple

data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
    val accentColor: AccentColor = AccentColor.Purple,
    val activityDetail: ActivityDetail = ActivityDetail.Focused,
    val groupSessionsByRepository: Boolean = true,
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
    val collapsedRepositoryIds: Set<String> = emptySet(),
    val lastProvider: String = PROVIDER_CODEX,
    val claudeModel: String = "sonnet",
    val claudePermissionMode: String = "default",
    val selectedSessionProvider: String = PROVIDER_CODEX,
    val selectedSessionId: String? = null,
)

private fun validPort(value: Int) = value in 1..65535

private fun SharedPreferences.Editor.putPreference(key: String, value: Any?) {
    when (value) {
        is String -> putString(key, value)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}
