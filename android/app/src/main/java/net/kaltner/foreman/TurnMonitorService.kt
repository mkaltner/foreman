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
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class MonitorOutcome(val title: String, val detail: String)

internal fun monitorOutcome(status: String): MonitorOutcome? =
    when (status) {
        "waiting" -> MonitorOutcome("Foreman needs your attention", "A monitored session is waiting for you.")
        "completed" -> MonitorOutcome("Foreman turn completed", "A monitored turn finished successfully.")
        "failed" -> MonitorOutcome("Foreman turn failed", "A monitored turn failed.")
        "interrupted" -> MonitorOutcome("Foreman turn interrupted", "A monitored turn was interrupted.")
        "idle" -> MonitorOutcome("Foreman turn is no longer active", "Tap to open the session.")
        else -> null
    }

class TurnMonitorService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val monitored = ConcurrentHashMap<String, Boolean>()
    private lateinit var client: ForemanClient
    private var reconnectJob: Job? = null
    @Volatile private var connected = false

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
            monitored.clear()
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_SESSION_ID)?.let(monitored::remove)
            if (monitored.isEmpty()) stopMonitoring() else showForeground(reconnecting = false)
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (intent?.action != ACTION_MONITOR || sessionId.isNullOrBlank()) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        val active = intent.getBooleanExtra(EXTRA_ACTIVE, false)
        monitored[sessionId] = monitored[sessionId] == true || active
        showForeground(reconnecting = false)
        scope.launch {
            runCatching { connectAndSync(setOf(sessionId)) }
                .onFailure { scheduleReconnect() }
        }
        return START_NOT_STICKY
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
                val saved = TokenStore(this).load()
                if (saved == null) {
                    sessionIds.forEach { finishMonitoring(it, "failed", "Pair Foreman again to monitor turns.") }
                    return
                }
                client.authenticate(saved.host, saved.token)
                connected = true
            }

            sessionIds.filter { monitored.containsKey(it) }.forEach { sessionId ->
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
                if (status == "working") monitored[sessionId] = true
                if (monitored[sessionId] == true && monitorOutcome(status) != null) {
                    finishMonitoring(sessionId, status)
                }
            }
            if (monitored.isNotEmpty()) showForeground(reconnecting = false)
        }
    }

    private fun handleEvent(message: WireMessage) {
        if (message.type != "session.event") return
        val sessionId = message.payload["sessionId"]?.jsonPrimitive?.content ?: return
        if (!monitored.containsKey(sessionId)) return
        val event = message.eventObject()
        if (event["kind"]?.jsonPrimitive?.content != "status") return
        val status = event["status"]?.jsonPrimitive?.content ?: return
        if (status == "working") monitored[sessionId] = true
        if (monitored[sessionId] == true && monitorOutcome(status) != null) {
            finishMonitoring(sessionId, status)
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (monitored.isEmpty() || reconnectJob?.isActive == true) return
        reconnectJob =
            scope.launch {
                var retryDelay = 2_000L
                while (isActive && monitored.isNotEmpty()) {
                    showForeground(reconnecting = true)
                    delay(retryDelay)
                    val restored = runCatching {
                        connected = false
                        connectAndSync(monitored.keys.toSet())
                    }.isSuccess
                    if (restored) return@launch
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            }
    }

    private fun finishMonitoring(sessionId: String, status: String, detail: String? = null) {
        monitored.remove(sessionId) ?: return
        val outcome = monitorOutcome(status) ?: return
        val notification = resultNotification(sessionId, outcome.copy(detail = detail ?: outcome.detail))
        notificationManager.notify(resultNotificationId(sessionId), notification)
        if (monitored.isEmpty()) {
            stopMonitoring()
        } else {
            showForeground(reconnecting = false)
        }
    }

    private fun showForeground(reconnecting: Boolean) {
        val count = monitored.size
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

    private fun resultNotification(sessionId: String, outcome: MonitorOutcome): Notification =
        notificationBuilder(RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(outcome.title)
            .setContentText(outcome.detail)
            .setContentIntent(openSessionIntent(sessionId))
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
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openSessionIntent(sessionId: String): PendingIntent =
        PendingIntent.getActivity(
            this,
            sessionId.hashCode(),
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
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
        private const val EXTRA_ACTIVE = "net.kaltner.foreman.extra.ACTIVE"
        private const val ACTION_MONITOR = "net.kaltner.foreman.action.MONITOR"
        private const val ACTION_CANCEL = "net.kaltner.foreman.action.CANCEL_MONITOR"
        private const val ACTION_STOP_ALL = "net.kaltner.foreman.action.STOP_MONITORING"
        private const val MONITOR_CHANNEL = "foreman_monitoring"
        private const val RESULT_CHANNEL = "foreman_turn_updates"
        private const val FOREGROUND_NOTIFICATION_ID = 1001

        fun monitor(context: Context, sessionId: String, active: Boolean = true) {
            val intent =
                Intent(context, TurnMonitorService::class.java)
                    .setAction(ACTION_MONITOR)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_ACTIVE, active)
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

        private fun resultNotificationId(sessionId: String): Int =
            2_000 + (sessionId.hashCode() and 0x00ffffff)
    }
}
