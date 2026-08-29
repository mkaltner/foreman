package net.kaltner.foreman

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement

internal data class MonitorOutcome(
    val title: String,
    val detail: String,
    val event: NotificationEvent,
)

internal fun monitorOutcome(status: String): MonitorOutcome? =
    when (status) {
        "waiting" -> MonitorOutcome("Foreman needs your attention", "A monitored session needs approval or input.", NotificationEvent.Approval)
        "completed" -> MonitorOutcome("Foreman turn completed", "A monitored turn finished successfully.", NotificationEvent.Completion)
        "failed" -> MonitorOutcome("Foreman turn failed", "A monitored turn failed. Open Foreman for details.", NotificationEvent.Failure)
        "interrupted" -> MonitorOutcome("Foreman turn interrupted", "A monitored turn was interrupted.", NotificationEvent.Interruption)
        "idle" -> MonitorOutcome("Foreman turn completed", "A monitored turn is no longer active.", NotificationEvent.Completion)
        else -> null
    }

internal fun approvalNotificationText(): MonitorOutcome =
    MonitorOutcome("Foreman needs your attention", "A monitored session needs approval or input.", NotificationEvent.Approval)

internal fun longRunningNotificationText(): MonitorOutcome =
    MonitorOutcome(
        "Foreman turn is still running",
        "A monitored turn passed your long-running threshold.",
        NotificationEvent.LongRunning,
    )

internal const val FOREGROUND_NOTIFICATION_ID = 1001

internal fun outcomeNotificationId(
    hostId: String,
    sessionId: String,
): Int =
    parseProviderSessionKey(sessionId)?.let { (provider, rawSessionId) ->
        providerNotificationId(hostId, provider, rawSessionId)
    } ?: providerNotificationId(hostId, PROVIDER_CODEX, sessionId)

internal data class GlobalTurnCandidate(
    val provider: String,
    val sessionId: String,
    val status: String,
    val repository: String?,
    val turnId: String?,
    val startedAt: Long?,
)

internal fun enabledMonitorProviders(providers: Iterable<JsonElement>): Set<String> =
    providers.mapNotNullTo(linkedSetOf()) { raw ->
        val provider = raw.jsonObject
        val id = provider["id"]?.jsonPrimitive?.content ?: return@mapNotNullTo null
        val enabled = provider["enabled"]?.jsonPrimitive?.booleanOrNull != false
        val available = provider["available"]?.jsonPrimitive?.booleanOrNull == true
        id.takeIf {
            enabled && available && it in setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE)
        }
    }

internal fun globalTurnCandidates(provider: String, sessions: Iterable<JsonElement>): List<GlobalTurnCandidate> =
    sessions.mapNotNull { raw ->
        val session = raw.jsonObject
        val status = session["status"]?.jsonPrimitive?.content ?: return@mapNotNull null
        if (status !in setOf("working", "waiting")) return@mapNotNull null
        if (
            provider == PROVIDER_CLAUDE_CODE &&
                session["source"]?.jsonPrimitive?.content == "external"
        ) return@mapNotNull null
        GlobalTurnCandidate(
            provider = provider,
            sessionId = session["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
            status = status,
            repository = session["repository"]?.jsonPrimitive?.content,
            turnId = session["activeTurnId"]?.jsonPrimitive?.content,
            startedAt = session["activeTurnStartedAt"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }

internal fun shouldEnrollGlobalTurn(
    monitorAllTurns: Boolean,
    enabledProviders: Set<String>,
    provider: String,
    status: String,
): Boolean =
    monitorAllTurns && provider in enabledProviders && status in setOf("working", "waiting")

internal fun focusedSessionKeys(sessions: Iterable<JsonElement>): Set<String> =
    sessions.mapNotNullTo(linkedSetOf()) { raw ->
        val presence = raw.jsonObject
        val provider = presence["provider"]?.jsonPrimitive?.content ?: return@mapNotNullTo null
        val sessionId = presence["sessionId"]?.jsonPrimitive?.content ?: return@mapNotNullTo null
        providerSessionKey(provider, sessionId).takeIf {
            provider in setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE) && sessionId.isNotBlank()
        }
    }

internal class MonitorLifecycle(
    private val initialReconnectDelay: Long = 2_000L,
    private val maximumReconnectDelay: Long = 30_000L,
) {
    private val monitored = linkedMapOf<String, Boolean>()
    private var reconnectDelay = initialReconnectDelay

    @Synchronized
    fun monitor(sessionId: String, active: Boolean) {
        monitored[sessionId] = monitored[sessionId] == true || active
    }

    @Synchronized
    fun monitorActive(sessionId: String, status: String): Boolean {
        if (status !in setOf("working", "waiting") || monitored.containsKey(sessionId)) return false
        monitored[sessionId] = true
        return true
    }

    @Synchronized
    fun cancel(sessionId: String): Boolean = monitored.remove(sessionId) != null

    @Synchronized
    fun status(sessionId: String, status: String): MonitorOutcome? {
        if (!monitored.containsKey(sessionId)) return null
        if (status == "working") {
            monitored[sessionId] = true
            return null
        }
        if (status in setOf("resumable", "unavailable")) {
            monitored.remove(sessionId)
            return null
        }
        if (monitored[sessionId] != true) return null
        val outcome = monitorOutcome(status) ?: return null
        if (status != "waiting") monitored.remove(sessionId)
        return outcome
    }

    @Synchronized
    fun contains(sessionId: String): Boolean = monitored.containsKey(sessionId)

    @Synchronized
    fun sessionIds(): Set<String> = monitored.keys.toSet()

    @Synchronized
    fun size(): Int = monitored.size

    @Synchronized
    fun isEmpty(): Boolean = monitored.isEmpty()

    @Synchronized
    fun clear() = monitored.clear()

    @Synchronized
    fun nextReconnectDelay(): Long {
        val current = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(maximumReconnectDelay)
        return current
    }

    @Synchronized
    fun resetReconnectDelay() {
        reconnectDelay = initialReconnectDelay
    }
}

class TurnMonitorService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val lifecycle = MonitorLifecycle()
    private lateinit var client: ForemanClient
    private var reconnectJob: Job? = null
    private var approvalNotifications = AttentionNotificationLedger()
    private var attentionLedgerHostId: String? = null
    private val repositoryIdentities = linkedMapOf<String, String>()
    private val activeTurns = linkedMapOf<String, ActiveTurn>()
    private val longRunningJobs = linkedMapOf<String, Job>()
    private val longRunningNotified = linkedSetOf<String>()
    @Volatile private var enabledProviders: Set<String> = emptySet()
    private var monitoredHostId: String? = null
    @Volatile private var monitorAllTurns = false
    @Volatile private var connected = false
    @Volatile private var focusedSessions: Set<String> = emptySet()
    @Volatile private var foregroundStarted = false
    private var foregroundOutcomeResetJob: Job? = null

    private data class ActiveTurn(
        val turnKey: String,
        val startedAtMillis: Long,
    )

    override fun onCreate() {
        super.onCreate()
        serviceCreated = true
        createNotificationChannels()
        client =
            ForemanClient(
                scope,
                onEvent = ::handleEvent,
                onDisconnect = {
                    connected = false
                    scheduleReconnect()
                },
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACKNOWLEDGE_ATTENTION) {
            monitoredHostId?.let { hostId ->
                loadAttentionLedger(hostId, force = true)
                approvalNotifications.acknowledgePending()
                persistAttentionLedger()
                foregroundOutcomeResetJob?.cancel()
                if (!lifecycle.isEmpty() || monitorAllTurns) showForeground(reconnecting = false)
            }
            if (lifecycle.isEmpty() && !monitorAllTurns) stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_ALL) {
            monitorAllTurns = false
            lifecycle.clear()
            detachAttentionState()
            clearLongRunningState()
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REFRESH_PREFERENCES) {
            if (lifecycle.isEmpty() && !monitorAllTurns) {
                stopSelf()
                return START_NOT_STICKY
            }
            activeTurns.keys.forEach(::scheduleLongRunning)
            refreshAttentionNotification()
            return START_REDELIVER_INTENT
        }

        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId ->
                val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: PROVIDER_CODEX
                val key = providerSessionKey(provider, sessionId)
                lifecycle.cancel(key)
                clearTurnState(key)
            }
            if (lifecycle.isEmpty() && !monitorAllTurns) stopMonitoring() else showForeground(reconnecting = false)
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val provider = intent?.getStringExtra(EXTRA_PROVIDER) ?: PROVIDER_CODEX
        val hostId = intent?.getStringExtra(EXTRA_HOST_ID)
        val monitorAll = intent?.action == ACTION_MONITOR_ALL && !hostId.isNullOrBlank()
        val monitorSession = intent?.action == ACTION_MONITOR && !sessionId.isNullOrBlank() && !hostId.isNullOrBlank()
        if (!monitorAll && !monitorSession) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (monitoredHostId != null && monitoredHostId != hostId) {
            lifecycle.clear()
            detachAttentionState()
            clearLongRunningState()
            client.close()
            connected = false
        }
        monitoredHostId = hostId
        loadAttentionLedger(requireNotNull(hostId))
        if (monitorAll) monitorAllTurns = true

        if (monitorAll) {
            showForeground(reconnecting = false)
            scope.launch {
                runCatching { connectAndSync(emptySet()) }
                    .onFailure { scheduleReconnect() }
            }
            return START_REDELIVER_INTENT
        }

        requireNotNull(sessionId)
        val sessionKey = providerSessionKey(provider, sessionId)

        val active = intent.getBooleanExtra(EXTRA_ACTIVE, false)
        intent.getStringExtra(EXTRA_REPOSITORY_ID)?.let { repositoryIdentities[sessionKey] = it }
        lifecycle.monitor(sessionKey, active)
        if (active) {
            recordActiveTurn(
                sessionKey,
                intent.getStringExtra(EXTRA_TURN_ID),
                intent.getLongExtra(EXTRA_STARTED_AT, 0L).takeIf { it > 0L },
            )
        }
        showForeground(reconnecting = false)
        scope.launch {
            runCatching { connectAndSync(setOf(sessionKey)) }
                .onFailure { scheduleReconnect() }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconnectJob?.cancel()
        foregroundOutcomeResetJob?.cancel()
        foregroundStarted = false
        serviceCreated = false
        client.close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connectAndSync(sessionIds: Set<String>) {
        connectionMutex.withLock {
            if (!connected) {
                val saved = monitoredHostId?.let { HostStore(this).load(it) }
                if (saved == null) {
                    sessionIds.forEach {
                        failMonitoring(it, "Pair Foreman again to monitor turns.")
                    }
                    return
                }
                client.authenticate(saved.tcpEndpoint(), saved.deviceToken)
                connected = true
            }

            if ("sessionPresence" in client.capabilities) {
                val presence = client.request("session.presence")
                updateFocusedSessions(presence.payload["sessions"]?.jsonArray.orEmpty())
            } else {
                focusedSessions = emptySet()
            }

            if (monitorAllTurns) discoverActiveTurns()

            sessionIds.filter(lifecycle::contains).forEach { sessionKey ->
                val (provider, sessionId) = parseProviderSessionKey(sessionKey) ?: return@forEach
                val repositoryId = repositoryIdentities[sessionKey].orEmpty().ifBlank { "." }
                client.request(
                    if (provider == PROVIDER_CLAUDE_CODE) "provider.session.subscribe" else "session.subscribe",
                    buildJsonObject {
                        if (provider == PROVIDER_CLAUDE_CODE) put("provider", provider)
                        put("sessionId", sessionId)
                    },
                )
                val response =
                    client.request(
                        if (provider == PROVIDER_CLAUDE_CODE) "provider.session.read" else "session.read",
                        buildJsonObject {
                            if (provider == PROVIDER_CLAUDE_CODE) {
                                put("provider", provider)
                                put("repositoryId", repositoryId)
                            }
                            put("sessionId", sessionId)
                        },
                    )
                val session = response.payload["session"]?.jsonObject ?: return@forEach
                val status = session["status"]?.jsonPrimitive?.content ?: return@forEach
                session["repository"]?.jsonPrimitive?.content?.let {
                    if (repositoryIdentities[sessionKey].isNullOrBlank()) {
                        repositoryIdentities[sessionKey] = normalizeRepositoryIdentity(it)
                    }
                }
                if (status == "working") {
                    recordActiveTurn(
                        sessionKey,
                        session["activeTurnId"]?.jsonPrimitive?.content,
                        session["activeTurnStartedAt"]?.jsonPrimitive?.content?.toLongOrNull(),
                    )
                }
                lifecycle.status(sessionKey, status)?.let { finishMonitoring(sessionKey, it) }
                if (!lifecycle.contains(sessionKey)) clearTurnState(sessionKey)
            }
            val staleExplicitRequests = approvalNotifications.explicitKeys()
            val syncedExplicitRequests = linkedSetOf<String>()
            if ("approvals" in client.capabilities) {
                client.request("approval.list").payload["approvals"]?.jsonArray?.forEach { raw ->
                    val approval = raw.jsonObject
                    val approvalId = approval["id"]?.jsonPrimitive?.content ?: return@forEach
                    val sessionId = approval["sessionId"]?.jsonPrimitive?.content ?: return@forEach
                    recordAttentionRequest(
                        explicitAttentionRequest(
                            "approval",
                            approvalId,
                            providerSessionKey(PROVIDER_CODEX, sessionId),
                        ),
                    )?.let(syncedExplicitRequests::add)
                }
            }
            if ("structuredInput" in client.capabilities) {
                client.request("input.list").payload["inputs"]?.jsonArray?.forEach { raw ->
                    val input = raw.jsonObject
                    val inputId = input["id"]?.jsonPrimitive?.content ?: return@forEach
                    val sessionId = input["sessionId"]?.jsonPrimitive?.content ?: return@forEach
                    recordAttentionRequest(
                        explicitAttentionRequest(
                            "input",
                            inputId,
                            providerSessionKey(PROVIDER_CODEX, sessionId),
                        ),
                    )?.let(syncedExplicitRequests::add)
                }
            }
            approvalNotifications.clearKeys(staleExplicitRequests - syncedExplicitRequests)
            approvalNotifications.clearKeys(
                approvalNotifications.pendingRequests()
                    .filterNot { lifecycle.contains(it.sessionId) }
                    .map(AttentionRequest::key),
            )
            persistAttentionLedger()
            refreshAttentionNotification()
            lifecycle.resetReconnectDelay()
            if (lifecycle.isEmpty() && !monitorAllTurns) stopMonitoring() else showForeground(reconnecting = false)
        }
    }

    private suspend fun discoverActiveTurns() {
        val providers = client.request("provider.list").payload["providers"]?.jsonArray.orEmpty()
        enabledProviders = enabledMonitorProviders(providers)
        enabledProviders.toList().forEach { provider ->
            val response = client.request(
                if (provider == PROVIDER_CLAUDE_CODE) "provider.session.list" else "session.list",
                if (provider == PROVIDER_CLAUDE_CODE) {
                    buildJsonObject { put("provider", provider) }
                } else {
                    buildJsonObject { }
                },
            )
            globalTurnCandidates(provider, response.payload["sessions"]?.jsonArray.orEmpty()).forEach { session ->
                val sessionKey = providerSessionKey(session.provider, session.sessionId)
                if (!lifecycle.monitorActive(sessionKey, session.status)) return@forEach
                session.repository?.let {
                    repositoryIdentities[sessionKey] = normalizeRepositoryIdentity(it)
                }
                if (session.status == "working") {
                    recordActiveTurn(
                        sessionKey,
                        session.turnId,
                        session.startedAt,
                    )
                } else {
                    monitorOutcome(session.status)?.let { finishMonitoring(sessionKey, it) }
                }
            }
        }
    }

    private fun handleEvent(message: WireMessage) {
        if (message.type == "session.presence.event") {
            updateFocusedSessions(message.payload["sessions"]?.jsonArray.orEmpty())
            return
        }
        if (message.type == "provider.event") {
            message.payload["providers"]?.jsonArray?.let { providers ->
                enabledProviders = enabledMonitorProviders(providers)
            }
            return
        }
        if (message.type in setOf("approval.requested", "approval.updated", "approval.resolved")) {
            val approval = message.payload["approval"]?.jsonObject ?: return
            val approvalId = approval["id"]?.jsonPrimitive?.content ?: return
            val sessionId = approval["sessionId"]?.jsonPrimitive?.content ?: return
            val request = explicitAttentionRequest(
                "approval",
                approvalId,
                providerSessionKey(PROVIDER_CODEX, sessionId),
            )
            if (message.type == "approval.resolved" || approval["status"]?.jsonPrimitive?.content in setOf("resolved", "expired")) {
                resolveAttentionRequest(request)
            } else {
                recordAttentionRequest(request)
                refreshAttentionNotification()
            }
            return
        }
        if (message.type in setOf("input.requested", "input.updated", "input.resolved")) {
            val input = message.payload["input"]?.jsonObject ?: return
            val inputId = input["id"]?.jsonPrimitive?.content ?: return
            val sessionId = input["sessionId"]?.jsonPrimitive?.content ?: return
            val request = explicitAttentionRequest(
                "input",
                inputId,
                providerSessionKey(PROVIDER_CODEX, sessionId),
            )
            if (message.type == "input.resolved" || input["status"]?.jsonPrimitive?.content in setOf("resolved", "expired")) {
                resolveAttentionRequest(request)
            } else {
                recordAttentionRequest(request)
                refreshAttentionNotification()
            }
            return
        }
        if (message.type != "session.event") return
        val provider = message.payload["provider"]?.jsonPrimitive?.content ?: PROVIDER_CODEX
        val sessionId = message.payload["sessionId"]?.jsonPrimitive?.content ?: return
        val sessionKey = providerSessionKey(provider, sessionId)
        val event = message.eventObject()
        if (event["kind"]?.jsonPrimitive?.content != "status") return
        val status = event["status"]?.jsonPrimitive?.content ?: return
        val discovered =
            shouldEnrollGlobalTurn(monitorAllTurns, enabledProviders, provider, status) &&
                lifecycle.monitorActive(sessionKey, status)
        if (!lifecycle.contains(sessionKey)) return
        if (status !in setOf("working", "waiting")) clearApprovalNotifications(sessionKey)
        if (status == "working") {
            clearAttentionNotifications(sessionKey)
            recordActiveTurn(
                sessionKey,
                event["turnId"]?.jsonPrimitive?.content,
                event["startedAt"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
        if (discovered && status == "waiting") {
            monitorOutcome(status)?.let { finishMonitoring(sessionKey, it) }
        } else {
            lifecycle.status(sessionKey, status)?.let { finishMonitoring(sessionKey, it) }
        }
        if (status == "working" && monitorAllTurns) showForeground(reconnecting = false)
        if (!lifecycle.contains(sessionKey)) {
            clearTurnState(sessionKey)
            if (lifecycle.isEmpty() && !monitorAllTurns) stopMonitoring()
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if ((lifecycle.isEmpty() && !monitorAllTurns) || reconnectJob?.isActive == true) return
        reconnectJob =
            scope.launch {
                while (isActive && (!lifecycle.isEmpty() || monitorAllTurns)) {
                    showForeground(reconnecting = true)
                    delay(lifecycle.nextReconnectDelay())
                    val restored = runCatching {
                        connected = false
                        connectAndSync(lifecycle.sessionIds())
                    }.isSuccess
                    if (restored) return@launch
                }
            }
    }

    private fun finishMonitoring(sessionId: String, outcome: MonitorOutcome, detail: String? = null) {
        val hostId = monitoredHostId ?: return
        val repositoryId = repositoryIdentities[sessionId].orEmpty()
        if (outcome.event == NotificationEvent.Approval) {
            longRunningJobs.remove(sessionId)?.cancel()
            if (approvalNotifications.containsSession(sessionId)) return
            val provider = parseProviderSessionKey(sessionId)?.first ?: PROVIDER_CODEX
            if (
                provider == PROVIDER_CODEX &&
                    ("approvals" in client.capabilities || "structuredInput" in client.capabilities)
            ) {
                return
            }
            recordAttentionRequest(statusAttentionRequest(sessionId))
            refreshAttentionNotification()
            return
        } else {
            clearTurnState(sessionId)
        }
        val preferences = NotificationPreferenceStore(this).load(hostId)
        val shouldNotify =
            sessionId !in focusedSessions && preferences.shouldNotify(outcome.event, repositoryId)
        val monitoringContinues = !lifecycle.isEmpty() || monitorAllTurns
        if (!monitoringContinues) {
            stopMonitoring()
            if (shouldNotify) {
                notificationManager.notify(
                    outcomeNotificationId(hostId, sessionId),
                    resultNotification(
                        hostId,
                        sessionId,
                        outcome.copy(detail = detail ?: outcome.detail),
                    ),
                )
            }
        } else if (shouldNotify) {
            showForegroundOutcome(sessionId, outcome.copy(detail = detail ?: outcome.detail))
        } else {
            showForeground(reconnecting = false)
        }
    }

    @Synchronized
    private fun recordAttentionRequest(request: AttentionRequest): String? {
        if (!lifecycle.contains(request.sessionId)) return null
        val hostId = monitoredHostId ?: return null
        approvalNotifications.replaceStatusRequest(request)
        request.requestId?.let { notificationManager.cancel(approvalNotificationId(hostId, it)) }
        notificationManager.cancel(outcomeNotificationId(hostId, request.sessionId))
        persistAttentionLedger()
        return request.key
    }

    @Synchronized
    private fun resolveAttentionRequest(request: AttentionRequest) {
        approvalNotifications.clear(request.key)
        request.requestId?.let { requestId ->
            monitoredHostId?.let { hostId -> notificationManager.cancel(approvalNotificationId(hostId, requestId)) }
        }
        persistAttentionLedger()
        refreshAttentionNotification()
    }

    @Synchronized
    private fun clearApprovalNotifications(sessionId: String) {
        approvalNotifications.clearSession(sessionId).forEach { request ->
            request.requestId?.let { requestId ->
                monitoredHostId?.let { hostId -> notificationManager.cancel(approvalNotificationId(hostId, requestId)) }
            }
        }
        persistAttentionLedger()
        refreshAttentionNotification()
    }

    @Synchronized
    private fun clearAttentionNotifications(sessionId: String) {
        monitoredHostId?.let { hostId ->
            notificationManager.cancel(outcomeNotificationId(hostId, sessionId))
        }
        clearApprovalNotifications(sessionId)
    }

    @Synchronized
    private fun recordActiveTurn(sessionId: String, turnId: String?, rawStartedAt: Long?) {
        val startedAt = timestampMillis(rawStartedAt) ?: return
        val turnKey = "$sessionId:${turnId ?: startedAt}"
        val existing = activeTurns[sessionId]
        if (existing?.turnKey != turnKey) {
            longRunningJobs.remove(sessionId)?.cancel()
            existing?.let { longRunningNotified.remove(it.turnKey) }
            activeTurns[sessionId] = ActiveTurn(turnKey, startedAt)
        }
        scheduleLongRunning(sessionId)
    }

    @Synchronized
    private fun scheduleLongRunning(sessionId: String) {
        longRunningJobs.remove(sessionId)?.cancel()
        val active = activeTurns[sessionId] ?: return
        if (active.turnKey in longRunningNotified) return
        val hostId = monitoredHostId ?: return
        val repositoryId = repositoryIdentities[sessionId].orEmpty()
        val preferences = NotificationPreferenceStore(this).load(hostId)
        if (!preferences.eventEnabled(NotificationEvent.LongRunning, repositoryId)) return
        val dueAt = active.startedAtMillis + preferences.longRunningMinutes * 60_000L
        longRunningJobs[sessionId] = scope.launch {
            delay((dueAt - System.currentTimeMillis()).coerceAtLeast(0L))
            val stillActive = synchronized(this@TurnMonitorService) {
                activeTurns[sessionId]?.turnKey == active.turnKey && lifecycle.contains(sessionId)
            }
            if (!stillActive) return@launch
            synchronized(this@TurnMonitorService) {
                longRunningJobs.remove(sessionId)
                longRunningNotified.add(active.turnKey)
            }
            val current = NotificationPreferenceStore(this@TurnMonitorService).load(hostId)
            if (sessionId in focusedSessions) return@launch
            if (!current.shouldNotify(NotificationEvent.LongRunning, repositoryId)) return@launch
            notificationManager.cancel(longRunningNotificationId(hostId, sessionId))
            showForegroundOutcome(sessionId, longRunningNotificationText())
        }
    }

    @Synchronized
    private fun clearTurnState(sessionId: String) {
        longRunningJobs.remove(sessionId)?.cancel()
        activeTurns.remove(sessionId)?.let { longRunningNotified.remove(it.turnKey) }
        repositoryIdentities.remove(sessionId)
        monitoredHostId?.let { notificationManager.cancel(longRunningNotificationId(it, sessionId)) }
    }

    @Synchronized
    private fun clearLongRunningState() {
        longRunningJobs.values.forEach(Job::cancel)
        longRunningJobs.clear()
        activeTurns.clear()
        longRunningNotified.clear()
        repositoryIdentities.clear()
    }

    @Synchronized
    private fun updateFocusedSessions(sessions: Iterable<JsonElement>) {
        val next = focusedSessionKeys(sessions)
        val newlyFocused = next - focusedSessions
        val newlyUnfocused = focusedSessions - next
        focusedSessions = next
        val hostId = monitoredHostId ?: return
        newlyFocused.forEach { sessionId ->
            notificationManager.cancel(outcomeNotificationId(hostId, sessionId))
            notificationManager.cancel(longRunningNotificationId(hostId, sessionId))
        }
        if (newlyFocused.isNotEmpty() || newlyUnfocused.isNotEmpty()) refreshAttentionNotification()
    }

    private fun failMonitoring(sessionId: String, detail: String) {
        if (!lifecycle.cancel(sessionId)) return
        finishMonitoring(sessionId, requireNotNull(monitorOutcome("failed")), detail)
    }

    @Synchronized
    private fun loadAttentionLedger(hostId: String, force: Boolean = false) {
        if (!force && attentionLedgerHostId == hostId) return
        approvalNotifications = AttentionNotificationLedger(AttentionNotificationStore(this).load(hostId))
        attentionLedgerHostId = hostId
    }

    @Synchronized
    private fun persistAttentionLedger() {
        val hostId = attentionLedgerHostId ?: return
        AttentionNotificationStore(this).save(hostId, approvalNotifications.snapshot())
    }

    @Synchronized
    private fun detachAttentionState() {
        val hostId = attentionLedgerHostId
        approvalNotifications.pendingRequests().forEach { request ->
            request.requestId?.let { requestId ->
                hostId?.let { notificationManager.cancel(approvalNotificationId(it, requestId)) }
            }
        }
        // Detaching/stopping monitoring must not erase acknowledgment history.
        // HostStore.forget is the sole owner of durable host-state deletion.
        notificationManager.cancel(STANDALONE_ATTENTION_NOTIFICATION_ID)
        attentionLedgerHostId = null
        approvalNotifications = AttentionNotificationLedger()
    }

    @Synchronized
    private fun refreshAttentionNotification() {
        foregroundOutcomeResetJob?.cancel()
        foregroundOutcomeResetJob = null
        if (lifecycle.isEmpty() && !monitorAllTurns) {
            removeForegroundNotification()
            showStandaloneAttentionIfNeeded()
            stopSelf()
        } else {
            showForeground(reconnecting = false)
        }
    }

    @Synchronized
    private fun eligibleAttentionRequests(requireActiveMonitoring: Boolean = true): List<AttentionRequest> {
        val hostId = monitoredHostId ?: return emptyList()
        val preferences = NotificationPreferenceStore(this).load(hostId)
        return eligibleAttentionRequests(
            requests = approvalNotifications.pendingRequests(),
            activeSessions = lifecycle.sessionIds(),
            focusedSessions = focusedSessions,
            requireActiveMonitoring = requireActiveMonitoring,
        ) { request ->
            preferences.shouldNotify(
                    NotificationEvent.Approval,
                    repositoryIdentities[request.sessionId].orEmpty(),
                )
        }
    }

    @Synchronized
    private fun showForeground(reconnecting: Boolean) {
        val count = lifecycle.size()
        if (count == 0 && !monitorAllTurns) return
        val requests = eligibleAttentionRequests()
        val presentation =
            attentionNotificationPresentation(requests, approvalNotifications.acknowledgedKeys())
                ?: monitoringNotificationPresentation(count, reconnecting)
        val shouldAlert = approvalNotifications.claimAlerts(requests).isNotEmpty()
        if (shouldAlert) persistAttentionLedger()
        startForegroundPresentation(presentation, shouldAlert)
    }

    @Synchronized
    private fun showForegroundOutcome(sessionId: String, outcome: MonitorOutcome) {
        if (lifecycle.isEmpty() && !monitorAllTurns) return
        val request = statusAttentionRequest(sessionId)
        val presentation =
            ForegroundNotificationPresentation(
                title = outcome.title,
                detail = outcome.detail,
                destination = ForegroundNotificationDestination.Session,
                request = request,
                badgeCount = 1,
                useAttentionChannel = true,
            )
        startForegroundPresentation(presentation, shouldAlert = true)
        foregroundOutcomeResetJob?.cancel()
        foregroundOutcomeResetJob =
            scope.launch {
                delay(FOREGROUND_OUTCOME_DURATION_MILLIS)
                showForeground(reconnecting = false)
            }
    }

    private fun startForegroundNotification(notification: Notification) {
        notificationManager.cancel(STANDALONE_ATTENTION_NOTIFICATION_ID)
        foregroundStarted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundPresentation(
        presentation: ForegroundNotificationPresentation,
        shouldAlert: Boolean,
    ) {
        val foregroundEntryActive =
            notificationManager.activeNotifications.any {
                it.id == FOREGROUND_NOTIFICATION_ID && it.tag == null
            }
        // Channel sound settings are immutable and user-controlled. If Android no
        // longer has our entry, establish it on the quiet channel before restoring
        // already-alerted attention content on the alert channel.
        if (requiresQuietForegroundPriming(foregroundEntryActive, presentation, shouldAlert)) {
            startForegroundNotification(
                foregroundNotification(
                    presentation.copy(useAttentionChannel = false, badgeCount = 0),
                    shouldAlert = false,
                ),
            )
        }
        startForegroundNotification(foregroundNotification(presentation, shouldAlert))
    }

    @Synchronized
    private fun showStandaloneAttentionIfNeeded() {
        val requests = eligibleAttentionRequests(requireActiveMonitoring = false)
        val presentation =
            attentionNotificationPresentation(requests, approvalNotifications.acknowledgedKeys())
        if (presentation == null) {
            notificationManager.cancel(STANDALONE_ATTENTION_NOTIFICATION_ID)
            return
        }
        val shouldAlert = approvalNotifications.claimAlerts(requests).isNotEmpty()
        if (shouldAlert) persistAttentionLedger()
        val standaloneEntryActive =
            notificationManager.activeNotifications.any {
                it.id == STANDALONE_ATTENTION_NOTIFICATION_ID && it.tag == null
            }
        if (requiresQuietForegroundPriming(standaloneEntryActive, presentation, shouldAlert)) {
            notificationManager.notify(
                STANDALONE_ATTENTION_NOTIFICATION_ID,
                standaloneAttentionNotification(
                    presentation.copy(useAttentionChannel = false, badgeCount = 0),
                    shouldAlert = false,
                ),
            )
        }
        notificationManager.notify(
            STANDALONE_ATTENTION_NOTIFICATION_ID,
            standaloneAttentionNotification(presentation, shouldAlert),
        )
    }

    private fun standaloneAttentionNotification(
        presentation: ForegroundNotificationPresentation,
        shouldAlert: Boolean,
    ): Notification =
        notificationBuilder(if (presentation.useAttentionChannel) RESULT_CHANNEL else MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(presentation.title)
            .setContentText(presentation.detail)
            .setContentIntent(contentIntent(presentation))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setNumber(presentation.badgeCount)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setBadgeIconType(
                        if (presentation.badgeCount > 0) Notification.BADGE_ICON_SMALL else Notification.BADGE_ICON_NONE,
                    )
                }
            }
            .applyAlertBehavior(shouldAlert, presentation.useAttentionChannel)
            .build()

    private fun foregroundNotification(
        presentation: ForegroundNotificationPresentation,
        shouldAlert: Boolean,
    ): Notification =
        notificationBuilder(if (presentation.useAttentionChannel) RESULT_CHANNEL else MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(presentation.title)
            .setContentText(presentation.detail)
            .setContentIntent(contentIntent(presentation))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setNumber(presentation.badgeCount)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setBadgeIconType(
                        if (presentation.badgeCount > 0) Notification.BADGE_ICON_SMALL else Notification.BADGE_ICON_NONE,
                    )
                }
            }
            .applyAlertBehavior(shouldAlert, presentation.useAttentionChannel)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    "Stop",
                    stopIntent(),
                ).build(),
            )
            .build()

    @Suppress("DEPRECATION")
    private fun Notification.Builder.applyAlertBehavior(
        shouldAlert: Boolean,
        attentionChannel: Boolean,
    ): Notification.Builder =
        apply {
            setOnlyAlertOnce(!shouldAlert)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && shouldAlert) {
                @Suppress("DEPRECATION")
                setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                @Suppress("DEPRECATION")
                setPriority(Notification.PRIORITY_DEFAULT)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                setDefaults(0)
                setSound(null)
                setVibrate(null)
                @Suppress("DEPRECATION")
                setPriority(if (attentionChannel) Notification.PRIORITY_DEFAULT else Notification.PRIORITY_LOW)
            }
        }

    private fun contentIntent(presentation: ForegroundNotificationPresentation): PendingIntent =
        when (presentation.destination) {
            ForegroundNotificationDestination.App -> openAppIntent()
            ForegroundNotificationDestination.AttentionDashboard ->
                openAttentionIntent(requireNotNull(monitoredHostId))
            ForegroundNotificationDestination.Session -> {
                val request = requireNotNull(presentation.request)
                val (provider, rawSessionId) =
                    parseProviderSessionKey(request.sessionId) ?: (PROVIDER_CODEX to request.sessionId)
                openSessionIntent(
                    requireNotNull(monitoredHostId),
                    provider,
                    rawSessionId,
                    request.requestId,
                )
            }
        }

    private fun resultNotification(
        hostId: String,
        sessionId: String,
        outcome: MonitorOutcome,
    ): Notification {
        val (provider, rawSessionId) =
            parseProviderSessionKey(sessionId) ?: (PROVIDER_CODEX to sessionId)
        return notificationBuilder(RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(outcome.title)
            .setContentText(outcome.detail)
            .setContentIntent(openSessionIntent(hostId, provider, rawSessionId))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .applyAlertBehavior(shouldAlert = true, attentionChannel = true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun notificationBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_APP)
                .apply { monitoredHostId?.let { putExtra(EXTRA_HOST_ID, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openSessionIntent(
        hostId: String,
        provider: String,
        sessionId: String,
        approvalId: String? = null,
    ): PendingIntent =
        PendingIntent.getActivity(
            this,
            "$hostId:$provider:$sessionId".hashCode(),
            Intent(this, MainActivity::class.java)
                .setAction("$ACTION_OPEN_SESSION:$hostId:$provider:$sessionId")
                .putExtra(EXTRA_HOST_ID, hostId)
                .putExtra(EXTRA_PROVIDER, provider)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .apply { approvalId?.let { putExtra(EXTRA_APPROVAL_ID, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAttentionIntent(hostId: String): PendingIntent =
        PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_ATTENTION)
                .putExtra(EXTRA_HOST_ID, hostId)
                .putExtra(EXTRA_OPEN_ATTENTION, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, TurnMonitorService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @Suppress("DEPRECATION")
    private fun stopMonitoring() {
        foregroundOutcomeResetJob?.cancel()
        foregroundOutcomeResetJob = null
        notificationManager.cancel(STANDALONE_ATTENTION_NOTIFICATION_ID)
        removeForegroundNotification()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun removeForegroundNotification() {
        if (!foregroundStarted) return
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL,
                "Background monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when Foreman is monitoring active turns"
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL,
                "Turn updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when a monitored turn finishes or needs attention"
            },
        )
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        const val EXTRA_SESSION_ID = "net.kaltner.foreman.extra.SESSION_ID"
        const val EXTRA_HOST_ID = "net.kaltner.foreman.extra.HOST_ID"
        const val EXTRA_PROVIDER = "net.kaltner.foreman.extra.PROVIDER"
        const val EXTRA_APPROVAL_ID = "net.kaltner.foreman.extra.APPROVAL_ID"
        const val EXTRA_OPEN_ATTENTION = "net.kaltner.foreman.extra.OPEN_ATTENTION"
        private const val EXTRA_ACTIVE = "net.kaltner.foreman.extra.ACTIVE"
        private const val EXTRA_REPOSITORY_ID = "net.kaltner.foreman.extra.REPOSITORY_ID"
        private const val EXTRA_TURN_ID = "net.kaltner.foreman.extra.TURN_ID"
        private const val EXTRA_STARTED_AT = "net.kaltner.foreman.extra.STARTED_AT"
        private const val ACTION_MONITOR = "net.kaltner.foreman.action.MONITOR"
        private const val ACTION_MONITOR_ALL = "net.kaltner.foreman.action.MONITOR_ALL"
        private const val ACTION_CANCEL = "net.kaltner.foreman.action.CANCEL_MONITOR"
        private const val ACTION_STOP_ALL = "net.kaltner.foreman.action.STOP_MONITORING"
        private const val ACTION_REFRESH_PREFERENCES = "net.kaltner.foreman.action.REFRESH_NOTIFICATION_PREFERENCES"
        private const val ACTION_ACKNOWLEDGE_ATTENTION = "net.kaltner.foreman.action.ACKNOWLEDGE_ATTENTION"
        private const val ACTION_OPEN_APP = "net.kaltner.foreman.action.OPEN_APP"
        private const val ACTION_OPEN_ATTENTION = "net.kaltner.foreman.action.OPEN_ATTENTION"
        private const val ACTION_OPEN_SESSION = "net.kaltner.foreman.action.OPEN_SESSION"
        private const val MONITOR_CHANNEL = "foreman_monitoring"
        private const val RESULT_CHANNEL = "foreman_turn_updates"
        private const val STANDALONE_ATTENTION_NOTIFICATION_ID = 1002
        private const val FOREGROUND_OUTCOME_DURATION_MILLIS = 5_000L
        @Volatile private var serviceCreated = false

        fun monitor(
            context: Context,
            hostId: String,
            sessionId: String,
            active: Boolean = true,
            repositoryId: String = "",
            turnId: String? = null,
            startedAt: Long? = null,
            provider: String = PROVIDER_CODEX,
        ) {
            val intent =
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_MONITOR)
                    .putExtra(EXTRA_HOST_ID, hostId)
                    .putExtra(EXTRA_PROVIDER, provider)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_ACTIVE, active)
                    .putExtra(EXTRA_REPOSITORY_ID, repositoryId)
                    .apply {
                        turnId?.let { putExtra(EXTRA_TURN_ID, it) }
                        startedAt?.let { putExtra(EXTRA_STARTED_AT, it) }
                    }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun monitorAll(context: Context, hostId: String) {
            val intent =
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_MONITOR_ALL)
                    .putExtra(EXTRA_HOST_ID, hostId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(
            context: Context,
            sessionId: String,
            provider: String = PROVIDER_CODEX,
        ) {
            context.startService(
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_PROVIDER, provider)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
            )
        }

        fun stopAll(context: Context) {
            context.startService(
                Intent(context, TurnMonitorService::class.java).setAction(ACTION_STOP_ALL),
            )
        }

        fun refreshPreferences(context: Context) {
            context.startService(
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_REFRESH_PREFERENCES),
            )
        }

        fun acknowledgeAttention(context: Context) {
            val pendingChanged = AttentionNotificationStore(context).acknowledgeAllPending()
            if (pendingChanged) {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(STANDALONE_ATTENTION_NOTIFICATION_ID)
            }
            if (!pendingChanged && !serviceCreated) return
            context.startService(
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_ACKNOWLEDGE_ATTENTION),
            )
        }

        private fun approvalNotificationId(hostId: String, approvalId: String): Int =
            30_000_000 + ("$hostId:$approvalId".hashCode() and 0x00ffffff)

        private fun longRunningNotificationId(hostId: String, sessionId: String): Int =
            parseProviderSessionKey(sessionId)?.let { (provider, rawSessionId) ->
                providerNotificationId(hostId, provider, rawSessionId, 60_000_000)
            } ?: providerNotificationId(hostId, PROVIDER_CODEX, sessionId, 60_000_000)
    }
}

private fun timestampMillis(value: Long?): Long? =
    value?.takeIf { it > 0L }?.let { if (it < 10_000_000_000L) it * 1_000L else it }

private fun normalizeRepositoryIdentity(value: String): String =
    value.trim().replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
