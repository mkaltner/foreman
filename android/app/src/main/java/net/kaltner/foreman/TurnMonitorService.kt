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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    fun cancel(sessionId: String): Boolean = monitored.remove(sessionId) != null

    @Synchronized
    fun status(sessionId: String, status: String): MonitorOutcome? {
        if (!monitored.containsKey(sessionId)) return null
        if (status == "working") {
            monitored[sessionId] = true
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
    private val approvalNotifications = linkedMapOf<String, String>()
    private val repositoryIdentities = linkedMapOf<String, String>()
    private val activeTurns = linkedMapOf<String, ActiveTurn>()
    private val longRunningJobs = linkedMapOf<String, Job>()
    private val longRunningNotified = linkedSetOf<String>()
    private var monitoredHostId: String? = null
    @Volatile private var connected = false

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
            lifecycle.clear()
            clearLongRunningState()
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REFRESH_PREFERENCES) {
            if (lifecycle.isEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            activeTurns.keys.forEach(::scheduleLongRunning)
            return START_REDELIVER_INTENT
        }

        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_SESSION_ID)?.let {
                lifecycle.cancel(it)
                clearTurnState(it)
            }
            if (lifecycle.isEmpty()) stopMonitoring() else showForeground(reconnecting = false)
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val hostId = intent?.getStringExtra(EXTRA_HOST_ID)
        if (intent?.action != ACTION_MONITOR || sessionId.isNullOrBlank() || hostId.isNullOrBlank()) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (monitoredHostId != null && monitoredHostId != hostId) {
            lifecycle.clear()
            approvalNotifications.clear()
            clearLongRunningState()
            client.close()
            connected = false
        }
        monitoredHostId = hostId

        val active = intent.getBooleanExtra(EXTRA_ACTIVE, false)
        intent.getStringExtra(EXTRA_REPOSITORY_ID)?.let { repositoryIdentities[sessionId] = it }
        lifecycle.monitor(sessionId, active)
        if (active) {
            recordActiveTurn(
                sessionId,
                intent.getStringExtra(EXTRA_TURN_ID),
                intent.getLongExtra(EXTRA_STARTED_AT, 0L).takeIf { it > 0L },
            )
        }
        showForeground(reconnecting = false)
        scope.launch {
            runCatching { connectAndSync(setOf(sessionId)) }
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

            sessionIds.filter(lifecycle::contains).forEach { sessionId ->
                client.request(
                    "session.subscribe",
                    buildJsonObject { put("sessionId", sessionId) },
                )
                val response =
                    client.request(
                        "session.read",
                        buildJsonObject { put("sessionId", sessionId) },
                    )
                val session = response.payload["session"]?.jsonObject ?: return@forEach
                val status = session["status"]?.jsonPrimitive?.content ?: return@forEach
                session["repository"]?.jsonPrimitive?.content?.let {
                    if (repositoryIdentities[sessionId].isNullOrBlank()) {
                        repositoryIdentities[sessionId] = normalizeRepositoryIdentity(it)
                    }
                }
                if (status == "working") {
                    recordActiveTurn(
                        sessionId,
                        session["activeTurnId"]?.jsonPrimitive?.content,
                        session["activeTurnStartedAt"]?.jsonPrimitive?.content?.toLongOrNull(),
                    )
                }
                lifecycle.status(sessionId, status)?.let { finishMonitoring(sessionId, it) }
            }
            if ("approvals" in client.capabilities) {
                client.request("approval.list").payload["approvals"]?.jsonArray?.forEach { raw ->
                    val approval = raw.jsonObject
                    val approvalId = approval["id"]?.jsonPrimitive?.content ?: return@forEach
                    val sessionId = approval["sessionId"]?.jsonPrimitive?.content ?: return@forEach
                    notifyApproval(sessionId, approvalId)
                }
            }
            if ("structuredInput" in client.capabilities) {
                client.request("input.list").payload["inputs"]?.jsonArray?.forEach { raw ->
                    val input = raw.jsonObject
                    val inputId = input["id"]?.jsonPrimitive?.content ?: return@forEach
                    val sessionId = input["sessionId"]?.jsonPrimitive?.content ?: return@forEach
                    notifyApproval(sessionId, inputId)
                }
            }
            lifecycle.resetReconnectDelay()
            if (!lifecycle.isEmpty()) showForeground(reconnecting = false)
        }
    }

    private fun handleEvent(message: WireMessage) {
        if (message.type in setOf("approval.requested", "approval.updated", "approval.resolved")) {
            val approval = message.payload["approval"]?.jsonObject ?: return
            val approvalId = approval["id"]?.jsonPrimitive?.content ?: return
            val sessionId = approval["sessionId"]?.jsonPrimitive?.content ?: return
            if (message.type == "approval.resolved" || approval["status"]?.jsonPrimitive?.content in setOf("resolved", "expired")) {
                approvalNotifications.remove(approvalId)
                monitoredHostId?.let { notificationManager.cancel(approvalNotificationId(it, approvalId)) }
            } else {
                notifyApproval(sessionId, approvalId)
            }
            return
        }
        if (message.type in setOf("input.requested", "input.updated", "input.resolved")) {
            val input = message.payload["input"]?.jsonObject ?: return
            val inputId = input["id"]?.jsonPrimitive?.content ?: return
            val sessionId = input["sessionId"]?.jsonPrimitive?.content ?: return
            if (message.type == "input.resolved" || input["status"]?.jsonPrimitive?.content in setOf("resolved", "expired")) {
                approvalNotifications.remove(inputId)
                monitoredHostId?.let { notificationManager.cancel(approvalNotificationId(it, inputId)) }
            } else {
                notifyApproval(sessionId, inputId)
            }
            return
        }
        if (message.type != "session.event") return
        val sessionId = message.payload["sessionId"]?.jsonPrimitive?.content ?: return
        if (!lifecycle.contains(sessionId)) return
        val event = message.eventObject()
        if (event["kind"]?.jsonPrimitive?.content != "status") return
        val status = event["status"]?.jsonPrimitive?.content ?: return
        if (status == "working") {
            recordActiveTurn(
                sessionId,
                event["turnId"]?.jsonPrimitive?.content,
                event["startedAt"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
        lifecycle.status(sessionId, status)?.let { finishMonitoring(sessionId, it) }
        if (status !in setOf("working", "waiting")) clearApprovalNotifications(sessionId)
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (lifecycle.isEmpty() || reconnectJob?.isActive == true) return
        reconnectJob =
            scope.launch {
                while (isActive && !lifecycle.isEmpty()) {
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
            if (approvalNotifications.containsValue(sessionId)) return
        } else {
            clearTurnState(sessionId)
        }
        val preferences = NotificationPreferenceStore(this).load(hostId)
        if (preferences.shouldNotify(outcome.event, repositoryId)) {
            val notification = resultNotification(hostId, sessionId, outcome.copy(detail = detail ?: outcome.detail))
            notificationManager.notify(resultNotificationId(hostId, sessionId), notification)
        }
        if (lifecycle.isEmpty()) {
            stopMonitoring()
        } else {
            showForeground(reconnecting = false)
        }
    }

    @Synchronized
    private fun notifyApproval(sessionId: String, approvalId: String) {
        if (!lifecycle.contains(sessionId) || approvalId in approvalNotifications) return
        val hostId = monitoredHostId ?: return
        approvalNotifications[approvalId] = sessionId
        val preferences = NotificationPreferenceStore(this).load(hostId)
        if (!preferences.shouldNotify(NotificationEvent.Approval, repositoryIdentities[sessionId].orEmpty())) return
        notificationManager.cancel(resultNotificationId(hostId, sessionId))
        val outcome = approvalNotificationText()
        val notification =
            notificationBuilder(RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(outcome.title)
                .setContentText(outcome.detail)
                .setContentIntent(openSessionIntent(hostId, sessionId, approvalId))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
        notificationManager.notify(approvalNotificationId(hostId, approvalId), notification)
    }

    @Synchronized
    private fun clearApprovalNotifications(sessionId: String) {
        val ids = approvalNotifications.filterValues { it == sessionId }.keys.toList()
        ids.forEach {
            approvalNotifications.remove(it)
            monitoredHostId?.let { hostId -> notificationManager.cancel(approvalNotificationId(hostId, it)) }
        }
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

    private fun failMonitoring(sessionId: String, detail: String) {
        if (!lifecycle.cancel(sessionId)) return
        finishMonitoring(sessionId, requireNotNull(monitorOutcome("failed")), detail)
    }

    private fun showForeground(reconnecting: Boolean) {
        val count = lifecycle.size()
        if (count == 0) return
        val text =
            if (reconnecting) {
                "Reconnecting to Foreman…"
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

    private fun resultNotification(hostId: String, sessionId: String, outcome: MonitorOutcome): Notification =
        notificationBuilder(RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(outcome.title)
            .setContentText(outcome.detail)
            .setContentIntent(openSessionIntent(hostId, sessionId))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

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

    private fun openSessionIntent(hostId: String, sessionId: String, approvalId: String? = null): PendingIntent =
        PendingIntent.getActivity(
            this,
            "$hostId:$sessionId".hashCode(),
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_HOST_ID, hostId)
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
                description = "Shows when Foreman is monitoring active Codex turns"
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL,
                "Turn updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when a monitored Codex turn finishes or needs attention"
            },
        )
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        const val EXTRA_SESSION_ID = "net.kaltner.foreman.extra.SESSION_ID"
        const val EXTRA_HOST_ID = "net.kaltner.foreman.extra.HOST_ID"
        const val EXTRA_APPROVAL_ID = "net.kaltner.foreman.extra.APPROVAL_ID"
        private const val EXTRA_ACTIVE = "net.kaltner.foreman.extra.ACTIVE"
        private const val EXTRA_REPOSITORY_ID = "net.kaltner.foreman.extra.REPOSITORY_ID"
        private const val EXTRA_TURN_ID = "net.kaltner.foreman.extra.TURN_ID"
        private const val EXTRA_STARTED_AT = "net.kaltner.foreman.extra.STARTED_AT"
        private const val ACTION_MONITOR = "net.kaltner.foreman.action.MONITOR"
        private const val ACTION_CANCEL = "net.kaltner.foreman.action.CANCEL_MONITOR"
        private const val ACTION_STOP_ALL = "net.kaltner.foreman.action.STOP_MONITORING"
        private const val ACTION_REFRESH_PREFERENCES = "net.kaltner.foreman.action.REFRESH_NOTIFICATION_PREFERENCES"
        private const val MONITOR_CHANNEL = "foreman_monitoring"
        private const val RESULT_CHANNEL = "foreman_turn_updates"
        private const val FOREGROUND_NOTIFICATION_ID = 1001

        fun monitor(
            context: Context,
            hostId: String,
            sessionId: String,
            active: Boolean = true,
            repositoryId: String = "",
            turnId: String? = null,
            startedAt: Long? = null,
        ) {
            val intent =
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_MONITOR)
                    .putExtra(EXTRA_HOST_ID, hostId)
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

        fun cancel(context: Context, sessionId: String) {
            context.startService(
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_CANCEL)
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

        private fun resultNotificationId(hostId: String, sessionId: String): Int =
            2_000 + ("$hostId:$sessionId".hashCode() and 0x00ffffff)

        private fun approvalNotificationId(hostId: String, approvalId: String): Int =
            30_000_000 + ("$hostId:$approvalId".hashCode() and 0x00ffffff)

        private fun longRunningNotificationId(hostId: String, sessionId: String): Int =
            60_000_000 + ("$hostId:$sessionId".hashCode() and 0x00ffffff)
    }
}

private fun timestampMillis(value: Long?): Long? =
    value?.takeIf { it > 0L }?.let { if (it < 10_000_000_000L) it * 1_000L else it }

private fun normalizeRepositoryIdentity(value: String): String =
    value.trim().replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
