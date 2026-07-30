package net.kaltner.foreman

import android.content.Context
import java.util.Calendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val notificationPreferencesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun encodeNotificationPreferences(value: NotificationPreferences): String =
    notificationPreferencesJson.encodeToString(value.normalized())

internal fun decodeNotificationPreferences(value: String?): NotificationPreferences =
    runCatching {
        notificationPreferencesJson.decodeFromString<NotificationPreferences>(value.orEmpty())
    }.getOrDefault(NotificationPreferences()).normalized()

internal enum class NotificationEvent {
    Approval,
    Failure,
    Completion,
    Interruption,
    LongRunning,
}

@Serializable
data class RepositoryNotificationOverride(
    val notifyApprovals: Boolean? = null,
    val notifyFailures: Boolean? = null,
    val notifyCompletions: Boolean? = null,
    val notifyInterruptions: Boolean? = null,
    val notifyLongRunning: Boolean? = null,
)

@Serializable
data class NotificationPreferences(
    val notifyApprovals: Boolean = true,
    val notifyFailures: Boolean = true,
    val notifyCompletions: Boolean = true,
    val notifyInterruptions: Boolean = false,
    val notifyLongRunning: Boolean = false,
    val longRunningMinutes: Int = 15,
    val quietHoursEnabled: Boolean = false,
    val quietStart: String = "22:00",
    val quietEnd: String = "07:00",
    val criticalBypassQuietHours: Boolean = false,
    val repositoryOverrides: Map<String, RepositoryNotificationOverride> = emptyMap(),
)

internal fun NotificationPreferences.normalized(): NotificationPreferences =
    copy(
        longRunningMinutes = longRunningMinutes.coerceIn(1, 1_440),
        quietStart = quietStart.takeIf(::validClockTime) ?: "22:00",
        quietEnd = quietEnd.takeIf(::validClockTime) ?: "07:00",
        repositoryOverrides = repositoryOverrides.filterKeys(String::isNotBlank).entries.toList().takeLast(250)
            .associate { it.key to it.value },
    )

internal fun NotificationPreferences.eventEnabled(
    event: NotificationEvent,
    repositoryIdentity: String,
): Boolean {
    val override = repositoryOverrides[repositoryIdentity]
    return when (event) {
        NotificationEvent.Approval -> override?.notifyApprovals ?: notifyApprovals
        NotificationEvent.Failure -> override?.notifyFailures ?: notifyFailures
        NotificationEvent.Completion -> override?.notifyCompletions ?: notifyCompletions
        NotificationEvent.Interruption -> override?.notifyInterruptions ?: notifyInterruptions
        NotificationEvent.LongRunning -> override?.notifyLongRunning ?: notifyLongRunning
    }
}

internal fun NotificationPreferences.shouldNotify(
    event: NotificationEvent,
    repositoryIdentity: String,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (!eventEnabled(event, repositoryIdentity)) return false
    if (!isQuietTime(nowMillis)) return true
    return criticalBypassQuietHours &&
        (event == NotificationEvent.Approval || event == NotificationEvent.Failure)
}

internal fun NotificationPreferences.isQuietTime(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (!quietHoursEnabled) return false
    val start = clockMinutes(quietStart) ?: return false
    val end = clockMinutes(quietEnd) ?: return false
    if (start == end) return false
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val current = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    return if (start < end) current in start until end else current >= start || current < end
}

internal class NotificationPreferenceStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    fun load(hostId: String? = null): NotificationPreferences =
        hostId?.let(::loadHostOverride) ?: loadGlobal()

    fun loadGlobal(): NotificationPreferences = decode(preferences.getString(GLOBAL_KEY, null))

    fun loadHostOverride(hostId: String): NotificationPreferences? =
        preferences.getString(hostKey(hostId), null)?.let(::decode)

    fun hasHostOverride(hostId: String?): Boolean =
        hostId != null && preferences.contains(hostKey(hostId))

    fun save(value: NotificationPreferences, hostId: String? = null) {
        preferences.edit()
            .putString(hostId?.let(::hostKey) ?: GLOBAL_KEY, encode(value))
            .apply()
    }

    fun clearHostOverride(hostId: String) {
        preferences.edit().remove(hostKey(hostId)).apply()
    }

    internal fun encode(value: NotificationPreferences): String =
        encodeNotificationPreferences(value)

    internal fun decode(value: String?): NotificationPreferences =
        decodeNotificationPreferences(value)

    private fun hostKey(hostId: String) = "host.$hostId"

    companion object {
        private const val PREFERENCE_FILE = "foreman_notification_preferences"
        private const val GLOBAL_KEY = "global.v1"
    }
}

private fun validClockTime(value: String): Boolean =
    Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)

private fun clockMinutes(value: String): Int? {
    if (!validClockTime(value)) return null
    val (hours, minutes) = value.split(':').map(String::toInt)
    return hours * 60 + minutes
}
