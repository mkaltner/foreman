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
    replaceForegroundWatcher: Boolean,
): Int =
    if (replaceForegroundWatcher) {
        FOREGROUND_NOTIFICATION_ID
    } else {
        parseProviderSessionKey(sessionId)?.let { (provider, rawSessionId) ->
            providerNotificationId(hostId, provider, rawSessionId)
        } ?: providerNotificationId(hostId, PROVIDER_CODEX, sessionId)
    }

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

internal class ApprovalNotificationLedger {
    private val pending = linkedMapOf<String, String>()
    private val displayed = linkedSetOf<String>()

    fun record(approvalId: String, sessionId: String) {
        pending[approvalId] = sessionId
    }

    fun shouldDisplay(approvalId: String, focusedSessions: Set<String>): Boolean {
        val sessionId = pending[approvalId] ?: return false
        return approvalId !in displayed && sessionId !in focusedSessions
    }

    fun markDisplayed(approvalId: String) {
        if (approvalId in pending) displayed.add(approvalId)
    }

    fun containsSession(sessionId: String): Boolean = pending.containsValue(sessionId)

    fun pendingForSession(sessionId: String): List<String> =
        pending.filterValues { it == sessionId }.keys.toList()

    fun hideSession(sessionId: String): List<String> =
        pendingForSession(sessionId).also(displayed::removeAll)

    fun clear(approvalId: String) {
        pending.remove(approvalId)
        displayed.remove(approvalId)
    }

    fun clearSession(sessionId: String): List<String> =
        pendingForSession(sessionId).also { ids -> ids.forEach(::clear) }

    fun clearAll() {
        pending.clear()
        displayed.clear()
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
    private val approvalNotifications = ApprovalNotificationLedger()
    private val repositoryIdentities = linkedMapOf<String, String>()
    private val activeTurns = linkedMapOf<String, ActiveTurn>()
    private val longRunningJobs = linkedMapOf<String, Job>()
    private val longRunningNotified = linkedSetOf<String>()
    @Volatile private var enabledProviders: Set<String> = emptySet()
    private var monitoredHostId: String? = null
    @Volatile private var monitorAllTurns = false
    @Volatile private var connected = false
    @Volatile private var focusedSessions: Set<String> = emptySet()

    private data class ActiveTurn(
        val turnKey: String,
        val startedAtMillis: Long,
    )

    override fun onCreate() {
        super.onCreate()
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
        if (intent?.action == ACTION_STOP_ALL) {
            monitorAllTurns = false
            lifecycle.clear()
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
            approvalNotifications.clearAll()
            clearLongRunningState()
            client.close()
            connected = false
        }
        monitoredHostId = hostId
        if (monitorAll) monitorAllTurns = true
        showForeground(reconnecting = false)

        if (monitorAll) {
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
        scope.launch {
            runCatching { connectAndSync(setOf(sessionKey)) }
                .onFailure { scheduleReconnect() }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconnectJob?.cancel()
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
            if ("approvals" in client.capabilities) {
                client.request("approval.list").payload["approvals"]?.jsonArray?.forEach { raw ->
                    val approval = raw.jsonObject
                    val approvalId = approval["id"]?.jsonPrimitive?.content ?: return@forEach
                    val sessionId = approval["sessionId"]?.jsonPrimitive?.content ?: return@forEach
                    notifyApproval(providerSessionKey(PROVIDER_CODEX, sessionId), approvalId)
                }
            }
            if ("structuredInput" in client.capabilities) {
                client.request("input.list").payload["inputs"]?.jsonArray?.forEach { raw ->
                    val input = raw.jsonObject
                    val inputId = input["id"]?.jsonPrimitive?.content ?: return@forEach
                    val sessionId = input["sessionId"]?.jsonPrimitive?.content ?: return@forEach
                    notifyApproval(providerSessionKey(PROVIDER_CODEX, sessionId), inputId)
                }
            }
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
            if (message.type == "approval.resolved" || approval["status"]?.jsonPrimitive?.content in setOf("resolved", "expired")) {
                approvalNotifications.clear(approvalId)
                monitoredHostId?.let { notificationManager.cancel(approvalNotificationId(it, approvalId)) }
            } else {
                notifyApproval(providerSessionKey(PROVIDER_CODEX, sessionId), approvalId)
            }
            return
        }
        if (message.type in setOf("input.requested", "input.updated", "input.resolved")) {
            val input = message.payload["input"]?.jsonObject ?: return
            val inputId = input["id"]?.jsonPrimitive?.content ?: return
            val sessionId = input["sessionId"]?.jsonPrimitive?.content ?: return
            if (message.type == "input.resolved" || input["status"]?.jsonPrimitive?.content in setOf("resolved", "expired")) {
                approvalNotifications.clear(inputId)
                monitoredHostId?.let { notificationManager.cancel(approvalNotificationId(it, inputId)) }
            } else {
                notifyApproval(providerSessionKey(PROVIDER_CODEX, sessionId), inputId)
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
        if (status !in setOf("working", "waiting")) clearApprovalNotifications(sessionKey)
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
        } else {
            clearTurnState(sessionId)
        }
        val preferences = NotificationPreferenceStore(this).load(hostId)
        var replacedForegroundWatcher = false
        if (sessionId !in focusedSessions && preferences.shouldNotify(outcome.event, repositoryId)) {
            replacedForegroundWatcher = monitorAllTurns && outcome.event != NotificationEvent.Approval
            val notification = resultNotification(
                hostId,
                sessionId,
                outcome.copy(detail = detail ?: outcome.detail),
                ongoing = replacedForegroundWatcher,
            )
            if (replacedForegroundWatcher) {
                notificationManager.cancel(outcomeNotificationId(hostId, sessionId, false))
            }
            notificationManager.notify(
                outcomeNotificationId(hostId, sessionId, replacedForegroundWatcher),
                notification,
            )
        }
        if (lifecycle.isEmpty() && !monitorAllTurns) {
            stopMonitoring()
        } else if (!replacedForegroundWatcher) {
            showForeground(reconnecting = false)
        }
    }

    @Synchronized
    private fun notifyApproval(sessionId: String, approvalId: String) {
        if (!lifecycle.contains(sessionId)) return
        val hostId = monitoredHostId ?: return
        approvalNotifications.record(approvalId, sessionId)
        if (!approvalNotifications.shouldDisplay(approvalId, focusedSessions)) return
        val preferences = NotificationPreferenceStore(this).load(hostId)
        if (!preferences.shouldNotify(NotificationEvent.Approval, repositoryIdentities[sessionId].orEmpty())) return
        notificationManager.cancel(outcomeNotificationId(hostId, sessionId, false))
        val outcome = approvalNotificationText()
        val (provider, rawSessionId) =
            parseProviderSessionKey(sessionId) ?: (PROVIDER_CODEX to sessionId)
        val notification =
            notificationBuilder(RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(outcome.title)
                .setContentText(outcome.detail)
                .setContentIntent(openSessionIntent(hostId, provider, rawSessionId, approvalId))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
        notificationManager.notify(approvalNotificationId(hostId, approvalId), notification)
        approvalNotifications.markDisplayed(approvalId)
    }

    @Synchronized
    private fun clearApprovalNotifications(sessionId: String) {
        approvalNotifications.clearSession(sessionId).forEach {
            monitoredHostId?.let { hostId -> notificationManager.cancel(approvalNotificationId(hostId, it)) }
        }
    }

    @Synchronized
    private fun hideApprovalNotifications(sessionId: String) {
        approvalNotifications.hideSession(sessionId).forEach {
            monitoredHostId?.let { hostId -> notificationManager.cancel(approvalNotificationId(hostId, it)) }
        }
    }

    @Synchronized
    private fun clearAttentionNotifications(sessionId: String) {
        monitoredHostId?.let { hostId ->
            notificationManager.cancel(outcomeNotificationId(hostId, sessionId, false))
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
            notificationManager.notify(
                longRunningNotificationId(hostId, sessionId),
                resultNotification(hostId, sessionId, longRunningNotificationText()),
            )
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
            notificationManager.cancel(outcomeNotificationId(hostId, sessionId, false))
            notificationManager.cancel(longRunningNotificationId(hostId, sessionId))
            hideApprovalNotifications(sessionId)
        }
        newlyUnfocused.forEach { sessionId ->
            approvalNotifications.pendingForSession(sessionId).forEach { approvalId ->
                notifyApproval(sessionId, approvalId)
            }
        }
        if (newlyFocused.isNotEmpty() && monitorAllTurns) showForeground(reconnecting = false)
    }

    private fun failMonitoring(sessionId: String, detail: String) {
        if (!lifecycle.cancel(sessionId)) return
        finishMonitoring(sessionId, requireNotNull(monitorOutcome("failed")), detail)
    }

    private fun showForeground(reconnecting: Boolean) {
        val count = lifecycle.size()
        if (count == 0 && !monitorAllTurns) return
        val text =
            if (reconnecting) {
                "Reconnecting to Foreman…"
            } else if (count == 0) {
                "Watching for active turns"
            } else if (count == 1) {
                "Monitoring 1 active turn"
            } else {
                "Monitoring $count active turns"
            }
        val notification =
            notificationBuilder(MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Foreman background monitoring")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification),
                        "Stop",
                        stopIntent(),
                    ).build(),
                )
                .build()
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

    private fun resultNotification(
        hostId: String,
        sessionId: String,
        outcome: MonitorOutcome,
        ongoing: Boolean = false,
    ): Notification {
        val (provider, rawSessionId) =
            parseProviderSessionKey(sessionId) ?: (PROVIDER_CODEX to sessionId)
        return notificationBuilder(RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(outcome.title)
            .setContentText(outcome.detail)
            .setContentIntent(openSessionIntent(hostId, provider, rawSessionId))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .apply {
                if (ongoing) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@TurnMonitorService, R.drawable.ic_notification),
                            "Stop",
                            stopIntent(),
                        ).build(),
                    )
                }
            }
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
                .putExtra(EXTRA_HOST_ID, hostId)
                .putExtra(EXTRA_PROVIDER, provider)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .apply { approvalId?.let { putExtra(EXTRA_APPROVAL_ID, it) } }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
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
        private const val EXTRA_ACTIVE = "net.kaltner.foreman.extra.ACTIVE"
        private const val EXTRA_REPOSITORY_ID = "net.kaltner.foreman.extra.REPOSITORY_ID"
        private const val EXTRA_TURN_ID = "net.kaltner.foreman.extra.TURN_ID"
        private const val EXTRA_STARTED_AT = "net.kaltner.foreman.extra.STARTED_AT"
        private const val ACTION_MONITOR = "net.kaltner.foreman.action.MONITOR"
        private const val ACTION_MONITOR_ALL = "net.kaltner.foreman.action.MONITOR_ALL"
        private const val ACTION_CANCEL = "net.kaltner.foreman.action.CANCEL_MONITOR"
        private const val ACTION_STOP_ALL = "net.kaltner.foreman.action.STOP_MONITORING"
        private const val ACTION_REFRESH_PREFERENCES = "net.kaltner.foreman.action.REFRESH_NOTIFICATION_PREFERENCES"
        private const val MONITOR_CHANNEL = "foreman_monitoring"
        private const val RESULT_CHANNEL = "foreman_turn_updates"

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
            context.stopService(Intent(context, TurnMonitorService::class.java))
        }

        fun refreshPreferences(context: Context) {
            context.startService(
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_REFRESH_PREFERENCES),
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
