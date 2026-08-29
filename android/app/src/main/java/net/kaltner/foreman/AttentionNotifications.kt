package net.kaltner.foreman

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val MAX_ATTENTION_NOTIFICATION_COUNT = 99
private const val MAX_ATTENTION_HISTORY = 500
private const val ATTENTION_HOST_PREFIX = "host."

@Serializable
internal data class AttentionRequest(
    val key: String,
    val requestId: String?,
    val sessionId: String,
)

@Serializable
internal data class AttentionLedgerSnapshot(
    val pending: List<AttentionRequest> = emptyList(),
    val alerted: List<String> = emptyList(),
    val acknowledged: List<String> = emptyList(),
)

internal enum class ForegroundNotificationDestination {
    App,
    Session,
    AttentionDashboard,
}

internal data class ForegroundNotificationPresentation(
    val title: String,
    val detail: String,
    val destination: ForegroundNotificationDestination,
    val request: AttentionRequest? = null,
    val attentionCount: Int = 0,
    val badgeCount: Int = 0,
    val useAttentionChannel: Boolean = false,
)

internal fun requiresQuietForegroundPriming(
    foregroundEntryActive: Boolean,
    presentation: ForegroundNotificationPresentation,
    shouldAlert: Boolean,
): Boolean = !foregroundEntryActive && presentation.useAttentionChannel && !shouldAlert

internal fun monitoringNotificationPresentation(
    activeTurnCount: Int,
    reconnecting: Boolean,
): ForegroundNotificationPresentation =
    ForegroundNotificationPresentation(
        title = "Foreman background monitoring",
        detail =
            when {
                reconnecting -> "Reconnecting to Foreman…"
                activeTurnCount == 0 -> "Watching for active turns"
                activeTurnCount == 1 -> "Monitoring 1 active turn"
                else -> "Monitoring $activeTurnCount active turns"
            },
        destination = ForegroundNotificationDestination.App,
    )

internal fun attentionNotificationPresentation(
    requests: List<AttentionRequest>,
    acknowledgedKeys: Set<String>,
): ForegroundNotificationPresentation? {
    if (requests.isEmpty()) return null
    val count = requests.size
    val unacknowledgedCount = requests.count { it.key !in acknowledgedKeys }
    val boundedCount = unacknowledgedCount.coerceAtMost(MAX_ATTENTION_NOTIFICATION_COUNT)
    return ForegroundNotificationPresentation(
        title = "Foreman needs your attention",
        detail =
            when {
                count == 1 -> "A monitored session needs approval or input."
                count <= MAX_ATTENTION_NOTIFICATION_COUNT -> "$count requests need approval or input."
                else -> "$MAX_ATTENTION_NOTIFICATION_COUNT+ requests need approval or input."
            },
        destination =
            if (count == 1) {
                ForegroundNotificationDestination.Session
            } else {
                ForegroundNotificationDestination.AttentionDashboard
            },
        request = requests.singleOrNull(),
        attentionCount = count,
        badgeCount = boundedCount,
        useAttentionChannel = unacknowledgedCount > 0,
    )
}

internal fun eligibleAttentionRequests(
    requests: List<AttentionRequest>,
    activeSessions: Set<String>,
    focusedSessions: Set<String>,
    requireActiveMonitoring: Boolean = true,
    shouldNotify: (AttentionRequest) -> Boolean = { true },
): List<AttentionRequest> =
    requests.filter { request ->
        (!requireActiveMonitoring || request.sessionId in activeSessions) &&
            request.sessionId !in focusedSessions &&
            shouldNotify(request)
    }

internal class AttentionNotificationLedger(
    snapshot: AttentionLedgerSnapshot = AttentionLedgerSnapshot(),
) {
    private val pending = linkedMapOf<String, AttentionRequest>()
    private val alerted = linkedSetOf<String>()
    private val acknowledged = linkedSetOf<String>()

    init {
        snapshot.pending.takeLast(MAX_ATTENTION_HISTORY).forEach { pending[it.key] = it }
        alerted.addAll(snapshot.alerted.takeLast(MAX_ATTENTION_HISTORY))
        acknowledged.addAll(snapshot.acknowledged.takeLast(MAX_ATTENTION_HISTORY))
        acknowledged.retainAll(pending.keys)
    }

    @Synchronized
    fun record(request: AttentionRequest): Boolean {
        val isNew = request.key !in pending
        pending[request.key] = request
        trimPending()
        return isNew
    }

    @Synchronized
    fun containsSession(sessionId: String): Boolean = pending.values.any { it.sessionId == sessionId }

    @Synchronized
    fun pendingForSession(sessionId: String): List<AttentionRequest> =
        pending.values.filter { it.sessionId == sessionId }

    @Synchronized
    fun pendingRequests(): List<AttentionRequest> = pending.values.toList()

    @Synchronized
    fun explicitKeys(): Set<String> = pending.keys.filterTo(linkedSetOf()) { it.startsWith("approval:") || it.startsWith("input:") }

    @Synchronized
    fun claimAlerts(requests: Collection<AttentionRequest>): Set<String> {
        val claimed = requests.mapNotNullTo(linkedSetOf()) { request ->
            request.key.takeIf { it !in alerted && it !in acknowledged }
        }
        alerted.addAll(claimed)
        trimHistory(alerted)
        return claimed
    }

    @Synchronized
    fun acknowledgedKeys(): Set<String> = acknowledged.toSet()

    @Synchronized
    fun acknowledgePending() {
        acknowledged.addAll(pending.keys)
        trimHistory(acknowledged)
    }

    @Synchronized
    fun clear(key: String) {
        pending.remove(key)
        acknowledged.remove(key)
    }

    @Synchronized
    fun clearSession(sessionId: String): List<AttentionRequest> =
        pendingForSession(sessionId).also { requests -> requests.forEach { clear(it.key) } }

    @Synchronized
    fun clearKeys(keys: Collection<String>): List<AttentionRequest> =
        keys.mapNotNull { pending[it] }.also { requests -> requests.forEach { clear(it.key) } }

    @Synchronized
    fun replaceStatusRequest(request: AttentionRequest) {
        val statusKey = statusAttentionKey(request.sessionId)
        val statusAlerted = statusKey in alerted
        val statusAcknowledged = statusKey in acknowledged
        clear(statusKey)
        record(request)
        if (statusAlerted) alerted.add(request.key)
        if (statusAcknowledged) acknowledged.add(request.key)
        trimHistory(alerted)
        trimHistory(acknowledged)
    }

    @Synchronized
    fun snapshot(): AttentionLedgerSnapshot =
        AttentionLedgerSnapshot(
            pending = pending.values.toList(),
            alerted = alerted.toList(),
            acknowledged = acknowledged.toList(),
        )

    @Synchronized
    fun clearAll() {
        pending.clear()
        acknowledged.clear()
    }

    private fun trimPending() {
        while (pending.size > MAX_ATTENTION_HISTORY) {
            val key = pending.keys.first()
            pending.remove(key)
            acknowledged.remove(key)
        }
    }

    private fun trimHistory(values: LinkedHashSet<String>) {
        while (values.size > MAX_ATTENTION_HISTORY) values.remove(values.first())
    }
}

internal fun explicitAttentionRequest(
    kind: String,
    requestId: String,
    sessionId: String,
): AttentionRequest = AttentionRequest(explicitAttentionKey(kind, requestId), requestId, sessionId)

internal fun statusAttentionRequest(sessionId: String): AttentionRequest =
    AttentionRequest(statusAttentionKey(sessionId), null, sessionId)

private fun explicitAttentionKey(kind: String, requestId: String): String = "$kind:$requestId"

private fun statusAttentionKey(sessionId: String): String = "status:$sessionId"

internal fun encodeAttentionLedgerSnapshot(snapshot: AttentionLedgerSnapshot): String =
    attentionNotificationJson.encodeToString(snapshot)

internal fun decodeAttentionLedgerSnapshot(value: String?): AttentionLedgerSnapshot =
    runCatching { attentionNotificationJson.decodeFromString<AttentionLedgerSnapshot>(value.orEmpty()) }
        .getOrDefault(AttentionLedgerSnapshot())

internal fun attentionStatePreferenceKey(hostId: String): String = "$ATTENTION_HOST_PREFIX$hostId"

internal class AttentionNotificationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    fun load(hostId: String): AttentionLedgerSnapshot =
        decodeAttentionLedgerSnapshot(preferences.getString(hostKey(hostId), null))

    @SuppressLint("ApplySharedPref")
    fun save(hostId: String, snapshot: AttentionLedgerSnapshot) {
        // Alert history must reach disk before the OS is asked to make sound.
        preferences.edit().putString(hostKey(hostId), encodeAttentionLedgerSnapshot(snapshot)).commit()
    }

    fun acknowledgeAllPending(): Boolean {
        var changed = false
        preferences.all.keys.filter { it.startsWith(ATTENTION_HOST_PREFIX) }.forEach { key ->
            val snapshot = decodeAttentionLedgerSnapshot(preferences.getString(key, null))
            val pendingKeys = snapshot.pending.mapTo(linkedSetOf(), AttentionRequest::key)
            if (pendingKeys.any { it !in snapshot.acknowledged }) changed = true
            save(
                key.removePrefix(ATTENTION_HOST_PREFIX),
                snapshot.copy(acknowledged = (snapshot.acknowledged + pendingKeys).takeLast(MAX_ATTENTION_HISTORY)),
            )
        }
        return changed
    }

    @SuppressLint("ApplySharedPref")
    fun forget(hostId: String) {
        preferences.edit().remove(hostKey(hostId)).commit()
    }

    private fun hostKey(hostId: String) = attentionStatePreferenceKey(hostId)

    companion object {
        private const val PREFERENCE_FILE = "foreman_attention_notification_state"
    }
}

private val attentionNotificationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
