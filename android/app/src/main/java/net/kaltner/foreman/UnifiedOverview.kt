package net.kaltner.foreman

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MAX_ANDROID_HOST_CONNECTIONS = 2
const val ANDROID_OVERVIEW_POLL_INTERVAL_MS = 60_000L
const val ANDROID_DASHBOARD_RECENT_WINDOW_MS = 60 * 60 * 1000L

@Serializable
data class GlobalSessionIdentity(
    val hostId: String,
    val sessionId: String,
    val provider: String = PROVIDER_CODEX,
)

@Serializable
data class OverviewAttentionItem(
    val hostId: String,
    val sessionId: String,
    val approvalId: String? = null,
    val sessionTitle: String,
    val repository: String,
    val type: String,
    val startedAt: Long? = null,
    val provider: String = PROVIDER_CODEX,
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
    val codexActive: Int = 0,
    val claudeActive: Int = 0,
    val claudeUnavailable: Boolean = false,
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
    val provider: String = PROVIDER_CODEX,
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

data class AndroidDashboardProjection(
    val active: List<SessionSummary>,
    val attention: List<SessionSummary>,
    val recent: List<SessionSummary>,
    val waitingCount: Int,
    val failedCount: Int,
    val oldestTurn: SessionSummary?,
)

internal fun projectAndroidDashboard(
    sessions: List<SessionSummary>,
    now: Long = System.currentTimeMillis(),
    requestSessionIds: Set<String> = emptySet(),
): AndroidDashboardProjection {
    fun latestFirst(values: List<SessionSummary>) =
        values.sortedByDescending { epochMillis(it.lastActivity ?: it.terminalAt) ?: 0L }

    val active = latestFirst(sessions.filter { it.status == "working" && it.source != "external" })
    val attention =
        latestFirst(
            sessions.filter {
                it.status == "waiting" || it.status == "failed" || it.attention ||
                    (sessionProvider(it) == PROVIDER_CODEX && it.id in requestSessionIds)
            },
        )
    val recent =
        latestFirst(
            sessions.filter {
                it.status in setOf("completed", "failed", "interrupted") &&
                    epochMillis(it.terminalAt)?.let { completedAt ->
                        now - completedAt in 0..ANDROID_DASHBOARD_RECENT_WINDOW_MS
                    } == true
            },
        )
    val oldestTurn =
        sessions.filter { it.status == "working" || it.status == "waiting" }
            .mapNotNull { session ->
                epochMillis(session.activeTurnStartedAt)?.let { startedAt -> session to startedAt }
            }
            .minByOrNull { it.second }
            ?.first
    return AndroidDashboardProjection(
        active = active,
        attention = attention,
        recent = recent,
        waitingCount =
            sessions.count {
                it.status != "failed" &&
                    (it.status == "waiting" || it.attention ||
                        (sessionProvider(it) == PROVIDER_CODEX && it.id in requestSessionIds))
            },
        failedCount = sessions.count { it.status == "failed" },
        oldestTurn = oldestTurn,
    )
}

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
    val active = sessions.filter { it.status == "working" && it.source != "external" }
    val waiting = sessions.filter {
        (it.status == "waiting" || it.attention) &&
            !(sessionProvider(it) == PROVIDER_CLAUDE_CODE && it.source == "external")
    }
    val failed = sessions.filter { it.status == "failed" }
    val oldest = (active + waiting).mapNotNull { session ->
        epochMillis(session.activeTurnStartedAt)?.let { session to it }
    }.minByOrNull { it.second }
    val latest = sessions.filter { it.status in setOf("completed", "failed", "interrupted", "idle") }
        .mapNotNull { session -> epochMillis(session.terminalAt)?.let { session to it } }
        .maxByOrNull { it.second }
    val attention = buildList {
        pending.forEach { approval ->
            val session = sessions.firstOrNull { it.matches(PROVIDER_CODEX, approval.sessionId) }
            add(
                OverviewAttentionItem(
                    hostId = hostId,
                    sessionId = approval.sessionId,
                    approvalId = approval.id,
                    sessionTitle = session?.title ?: "Codex session",
                    repository = session?.repository.orEmpty(),
                    type = "approval",
                    startedAt = epochMillis(approval.startedAt ?: approval.createdAt),
                    provider = PROVIDER_CODEX,
                ),
            )
        }
        pendingInputs.forEach { input ->
            val session = sessions.firstOrNull { it.matches(PROVIDER_CODEX, input.sessionId) }
            add(
                OverviewAttentionItem(
                    hostId = hostId,
                    sessionId = input.sessionId,
                    approvalId = input.id,
                    sessionTitle = session?.title ?: "Codex session",
                    repository = session?.repository.orEmpty(),
                    type = "input",
                    startedAt = epochMillis(input.createdAt),
                    provider = PROVIDER_CODEX,
                ),
            )
        }
        waiting.filterNot { it.id in requestSessions }.forEach { session ->
            add(
                OverviewAttentionItem(
                    hostId = hostId,
                    sessionId = session.id,
                    sessionTitle = session.title,
                    repository = session.repository,
                    type = if (session.waitType == "input") "input" else "approval",
                    startedAt = epochMillis(session.activeTurnStartedAt ?: session.lastActivity),
                    provider = sessionProvider(session),
                ),
            )
        }
        failed.forEach { session ->
            add(
                OverviewAttentionItem(
                    hostId = hostId,
                    sessionId = session.id,
                    sessionTitle = session.title,
                    repository = session.repository,
                    type = "failed",
                    startedAt = epochMillis(session.terminalAt ?: session.lastActivity),
                    provider = sessionProvider(session),
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
        codexActive = active.count { sessionProvider(it) == PROVIDER_CODEX },
        claudeActive = active.count { sessionProvider(it) == PROVIDER_CLAUDE_CODE },
        waiting = waiting.size,
        failed = failed.size,
        oldestTurn = oldest?.let { OverviewTurn(hostId, it.first.id, it.first.title, it.second, sessionProvider(it.first)) },
        latestCompletion = latest?.let { OverviewTurn(hostId, it.first.id, it.first.title, it.second, sessionProvider(it.first)) },
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
    sessionIdentityKey(SessionIdentity(identity.hostId, identity.provider, identity.sessionId))

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
