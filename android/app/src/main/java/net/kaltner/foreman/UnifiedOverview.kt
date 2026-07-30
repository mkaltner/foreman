package net.kaltner.foreman

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MAX_ANDROID_HOST_CONNECTIONS = 2
const val ANDROID_OVERVIEW_POLL_INTERVAL_MS = 60_000L

@Serializable
data class GlobalSessionIdentity(val hostId: String, val sessionId: String)

@Serializable
data class OverviewAttentionItem(
    val hostId: String,
    val sessionId: String,
    val approvalId: String? = null,
    val sessionTitle: String,
    val repository: String,
    val type: String,
    val startedAt: Long? = null,
)

@Serializable
data class HostOverviewSnapshot(
    val hostId: String,
    val observedAt: Long,
    val connection: String,
    val foremanVersion: String? = null,
    val codexVersion: String? = null,
    val runtimeMode: String? = null,
    val runtimeConnected: Boolean = false,
    val active: Int = 0,
    val waiting: Int = 0,
    val failed: Int = 0,
    val oldestTurn: OverviewTurn? = null,
    val latestCompletion: OverviewTurn? = null,
    val latestActivity: Long? = null,
    val attention: List<OverviewAttentionItem> = emptyList(),
)

@Serializable
data class OverviewTurn(
    val hostId: String,
    val sessionId: String,
    val title: String,
    val timestamp: Long,
)

data class UnifiedOverviewTotals(
    val hosts: Int,
    val connectedHosts: Int,
    val staleHosts: Int,
    val active: Int,
    val waiting: Int,
    val failed: Int,
    val oldestTurn: OverviewTurn?,
    val latestCompletion: OverviewTurn?,
)

internal fun epochMillis(value: Long?): Long? =
    value?.takeIf { it > 0 }?.let { if (it < 10_000_000_000L) it * 1000 else it }

internal fun projectHostOverview(
    hostId: String,
    sessions: List<SessionSummary>,
    approvals: List<ApprovalRequest>,
    connection: String,
    foremanVersion: String? = null,
    codexVersion: String? = null,
    runtimeMode: String? = null,
    runtimeConnected: Boolean = false,
    observedAt: Long = System.currentTimeMillis(),
    inputs: List<InputRequest> = emptyList(),
): HostOverviewSnapshot {
    val pending = approvals.filter { it.status == "pending" || it.status == "submitting" }
    val pendingInputs = inputs.filter { it.status == "pending" || it.status == "submitting" }
    val requestSessions = (pending.map { it.sessionId } + pendingInputs.map { it.sessionId }).toSet()
    val active = sessions.filter { it.status == "working" }
    val waiting = sessions.filter { it.status == "waiting" || it.attention }
    val failed = sessions.filter { it.status == "failed" }
    val oldest = (active + waiting).mapNotNull { session ->
        epochMillis(session.activeTurnStartedAt)?.let { session to it }
    }.minByOrNull { it.second }
    val latest = sessions.filter { it.status in setOf("completed", "failed", "interrupted", "idle") }
        .mapNotNull { session -> epochMillis(session.terminalAt)?.let { session to it } }
        .maxByOrNull { it.second }
    val attention = buildList {
        pending.forEach { approval ->
            val session = sessions.firstOrNull { it.id == approval.sessionId }
            add(
                OverviewAttentionItem(
                    hostId,
                    approval.sessionId,
                    approval.id,
                    session?.title ?: "Codex session",
                    session?.repository.orEmpty(),
                    "approval",
                    epochMillis(approval.startedAt ?: approval.createdAt),
                ),
            )
        }
        pendingInputs.forEach { input ->
            val session = sessions.firstOrNull { it.id == input.sessionId }
            add(
                OverviewAttentionItem(
                    hostId,
                    input.sessionId,
                    input.id,
                    session?.title ?: "Codex session",
                    session?.repository.orEmpty(),
                    "input",
                    epochMillis(input.createdAt),
                ),
            )
        }
        waiting.filterNot { it.id in requestSessions }.forEach { session ->
            add(
                OverviewAttentionItem(
                    hostId,
                    session.id,
                    sessionTitle = session.title,
                    repository = session.repository,
                    type = if (session.waitType == "input") "input" else "approval",
                    startedAt = epochMillis(session.activeTurnStartedAt ?: session.lastActivity),
                ),
            )
        }
        failed.forEach { session ->
            add(
                OverviewAttentionItem(
                    hostId,
                    session.id,
                    sessionTitle = session.title,
                    repository = session.repository,
                    type = "failed",
                    startedAt = epochMillis(session.terminalAt ?: session.lastActivity),
                ),
            )
        }
    }.sortedBy { it.startedAt ?: observedAt }
    return HostOverviewSnapshot(
        hostId = hostId,
        observedAt = observedAt,
        connection = connection,
        foremanVersion = foremanVersion,
        codexVersion = codexVersion,
        runtimeMode = runtimeMode,
        runtimeConnected = runtimeConnected,
        active = active.size,
        waiting = waiting.size,
        failed = failed.size,
        oldestTurn = oldest?.let { OverviewTurn(hostId, it.first.id, it.first.title, it.second) },
        latestCompletion = latest?.let { OverviewTurn(hostId, it.first.id, it.first.title, it.second) },
        latestActivity = sessions.mapNotNull { epochMillis(it.lastActivity) }.maxOrNull(),
        attention = attention,
    )
}

internal fun aggregateHostOverviews(
    hostIds: List<String>,
    snapshots: Map<String, HostOverviewSnapshot>,
): UnifiedOverviewTotals {
    val available = hostIds.mapNotNull(snapshots::get)
    return UnifiedOverviewTotals(
        hosts = hostIds.size,
        connectedHosts = available.count { it.connection == "connected" },
        staleHosts = hostIds.size - available.count { it.connection == "connected" },
        active = available.sumOf { it.active },
        waiting = available.sumOf { it.waiting },
        failed = available.sumOf { it.failed },
        oldestTurn = available.mapNotNull { it.oldestTurn }.minByOrNull { it.timestamp },
        latestCompletion = available.mapNotNull { it.latestCompletion }.maxByOrNull { it.timestamp },
    )
}

internal fun globalSessionKey(identity: GlobalSessionIdentity): String =
    "${identity.hostId.length}:${identity.hostId}${identity.sessionId}"

internal class AndroidOverviewLifecycle {
    var foreground: Boolean = false
        private set
    var probeActive: Boolean = false
        private set

    fun onForeground() { foreground = true }
    fun onBackground() { foreground = false; probeActive = false }
    fun beginProbe(): Boolean {
        if (!foreground || probeActive) return false
        probeActive = true
        return true
    }
    fun endProbe() { probeActive = false }
}

class HostOverviewStore(context: Context) {
    private val preferences = context.getSharedPreferences("foreman_host_overview", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): Map<String, HostOverviewSnapshot> = preferences.all.mapNotNull { (hostId, raw) ->
        (raw as? String)?.let { encoded ->
            runCatching { json.decodeFromString<HostOverviewSnapshot>(encoded) }.getOrNull()
                ?.takeIf { it.hostId == hostId }?.let { hostId to it }
        }
    }.toMap()

    fun save(snapshot: HostOverviewSnapshot) {
        preferences.edit().putString(snapshot.hostId, json.encodeToString(snapshot)).apply()
    }

    fun forget(hostId: String) {
        preferences.edit().remove(hostId).apply()
    }
}
