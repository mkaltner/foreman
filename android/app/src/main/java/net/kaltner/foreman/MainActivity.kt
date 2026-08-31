package net.kaltner.foreman

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val composerKeyboardOptions =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        autoCorrectEnabled = true,
        keyboardType = KeyboardType.Text,
    )

class MainActivity : ComponentActivity() {
    private val foremanViewModel: ForemanViewModel by viewModels()
    private val androidAppUpdateViewModel: AndroidAppUpdateViewModel by viewModels()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            foremanViewModel.setMonitorActiveTurns(granted)
            if (!granted) foremanViewModel.notificationPermissionDenied()
        }
    private val unknownAppsPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            androidAppUpdateViewModel.permissionResult(canInstallUnknownApps(), ::launchInstaller)
        }
    private val packageInstaller =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            androidAppUpdateViewModel.installerResult(result.resultCode == Activity.RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ForemanApp(
                foremanViewModel,
                ::requestTurnMonitoring,
                androidAppUpdateViewModel,
                ::requestAndroidAppInstall,
                ::requestUnknownAppsPermission,
            )
        }
        openNotificationSession(intent)
    }

    override fun onResume() {
        super.onResume()
        TurnMonitorService.acknowledgeAttention(applicationContext)
        foremanViewModel.onNotificationPermissionState(notificationPermissionGranted())
        foremanViewModel.onForeground()
        androidAppUpdateViewModel.onForeground()
    }

    override fun onStop() {
        foremanViewModel.onBackground()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        foremanViewModel.onWindowFocusChanged(hasFocus)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNotificationSession(intent)
    }

    private fun requestTurnMonitoring(enabled: Boolean) {
        if (!enabled) {
            foremanViewModel.setMonitorActiveTurns(false)
        } else if (notificationPermissionGranted()) {
            foremanViewModel.setMonitorActiveTurns(true)
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun canInstallUnknownApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun requestAndroidAppInstall() {
        androidAppUpdateViewModel.requestInstall(!canInstallUnknownApps(), ::launchInstaller)
    }

    private fun requestUnknownAppsPermission() {
        if (canInstallUnknownApps()) {
            androidAppUpdateViewModel.permissionResult(true, ::launchInstaller)
            return
        }
        androidAppUpdateViewModel.beginPermissionRequest()
        val intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
        try {
            unknownAppsPermission.launch(intent)
        } catch (_: ActivityNotFoundException) {
            androidAppUpdateViewModel.permissionResult(false, ::launchInstaller)
        }
    }

    private fun launchInstaller(request: InstallerRequest?) {
        request ?: return
        val uri =
            FileProvider.getUriForFile(
                this,
                "$packageName.update-files",
                request.apk,
            )
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try {
            packageInstaller.launch(intent)
        } catch (_: ActivityNotFoundException) {
            androidAppUpdateViewModel.installerLaunchFailed()
        }
    }

    private fun openNotificationSession(intent: Intent?) {
        val sessionId = intent?.getStringExtra(TurnMonitorService.EXTRA_SESSION_ID)
        if (sessionId != null) {
            foremanViewModel.openSessionFromNotification(
                intent.getStringExtra(TurnMonitorService.EXTRA_HOST_ID),
                intent.getStringExtra(TurnMonitorService.EXTRA_PROVIDER) ?: PROVIDER_CODEX,
                sessionId,
                intent.getStringExtra(TurnMonitorService.EXTRA_APPROVAL_ID),
            )
        } else if (intent?.getBooleanExtra(TurnMonitorService.EXTRA_OPEN_ATTENTION, false) == true) {
            foremanViewModel.openAttentionFromNotification(
                intent.getStringExtra(TurnMonitorService.EXTRA_HOST_ID),
            )
        }
    }
}

internal enum class Screen { Setup, Overview, Dashboard, Sessions, Detail, Diagnostics }

internal data class RememberedSessionTarget(
    val provider: String,
    val sessionId: String,
)

internal fun rememberedSessionTarget(
    provider: String,
    sessionId: String?,
): RememberedSessionTarget? =
    sessionId
        ?.takeIf { supportedProvider(provider) && it.isNotBlank() && it.length <= 1000 }
        ?.let { RememberedSessionTarget(provider, it) }

internal fun restorationDestination(target: RememberedSessionTarget?): Screen =
    if (target == null) Screen.Sessions else Screen.Detail

internal fun restorableSessionSummary(
    target: RememberedSessionTarget,
    providers: List<ProviderInfo>,
    sessions: List<SessionSummary>,
): SessionSummary? {
    val providerAvailable = providers.any {
        it.id == target.provider && it.enabled && it.available
    }
    return sessions.firstOrNull { it.matches(target.provider, target.sessionId) }
        ?.takeIf { providerAvailable }
}

internal fun rememberedSessionForEntry(
    target: RememberedSessionTarget?,
    authoritative: Boolean,
    providers: List<ProviderInfo>,
    sessions: List<SessionSummary>,
    nonAuthoritativeProviders: Set<String> = emptySet(),
): RememberedSessionTarget? =
    target?.takeIf { remembered ->
        if (!authoritative) return@takeIf true
        val provider = providers.firstOrNull { it.id == remembered.provider }
            ?: return@takeIf false
        provider.enabled && (
            !provider.available ||
                provider.id in nonAuthoritativeProviders ||
                sessions.any { it.matches(remembered.provider, remembered.sessionId) }
        )
    }

internal fun retainedSessionPreferenceIds(
    listedSessionIds: Set<String>,
    persistedSessionIds: Set<String>,
    nonAuthoritativeProviders: Set<String>,
): Set<String> = listedSessionIds + persistedSessionIds.filterTo(mutableSetOf()) { key ->
    parseProviderSessionKey(key)?.first in nonAuthoritativeProviders
}

internal fun sessionProvidersWithoutAuthoritativeLists(
    providers: List<ProviderInfo>,
    failedProviders: Set<String>,
): Set<String> = failedProviders + providers
    .filter { supportedProvider(it.id) && it.enabled && !it.available }
    .map { it.id }

internal fun shouldStopMonitoringForNotificationPermission(
    monitoringEnabled: Boolean,
    permissionGranted: Boolean,
): Boolean = monitoringEnabled && !permissionGranted

internal fun reconnectDestination(current: Screen, selectedSessionId: String?): Screen =
    when {
        selectedSessionId != null -> Screen.Detail
        current == Screen.Dashboard -> Screen.Dashboard
        else -> Screen.Sessions
    }

internal fun focusedSessionPresenceKey(
    focused: Boolean,
    screen: Screen,
    selected: SessionSummary?,
): String? =
    selected
        ?.takeIf { focused && screen == Screen.Detail }
        ?.let { providerSessionKey(sessionProvider(it), it.id) }

internal fun sessionPresenceSyncPending(
    initialized: Boolean,
    published: String?,
    desired: String?,
): Boolean = !initialized || published != desired

internal fun dashboardBackDestination(): Screen = Screen.Overview

internal data class OverviewReturnTarget(
    val hostId: String,
    val screen: Screen,
    val sessionId: String? = null,
    val provider: String = PROVIDER_CODEX,
)

internal fun overviewReturnTarget(
    current: Screen,
    activeHostId: String?,
    selectedSessionId: String?,
    selectedProvider: String = PROVIDER_CODEX,
): OverviewReturnTarget? {
    val hostId = activeHostId ?: return null
    return when (current) {
        Screen.Dashboard, Screen.Sessions -> OverviewReturnTarget(hostId, current)
        Screen.Detail ->
            selectedSessionId?.let { OverviewReturnTarget(hostId, Screen.Detail, it, selectedProvider) }
                ?: OverviewReturnTarget(hostId, Screen.Sessions)
        Screen.Setup, Screen.Overview, Screen.Diagnostics -> null
    }
}

internal fun validatedOverviewReturnTarget(
    target: OverviewReturnTarget,
    sessions: List<SessionSummary>,
): OverviewReturnTarget =
    if (
        target.screen == Screen.Detail &&
        target.sessionId?.let { id -> sessions.any { it.matches(target.provider, id) } } != true
    ) {
        OverviewReturnTarget(target.hostId, Screen.Sessions)
    } else {
        target
    }

internal class OverviewNavigationState {
    private var returnTarget: OverviewReturnTarget? = null

    fun capture(current: Screen, activeHostId: String?, selectedSessionId: String?, selectedProvider: String = PROVIDER_CODEX) {
        returnTarget = overviewReturnTarget(current, activeHostId, selectedSessionId, selectedProvider)
    }

    fun hasReturnTarget(): Boolean = returnTarget != null

    fun invalidateForHost(activeHostId: String?) {
        if (returnTarget?.hostId != activeHostId) returnTarget = null
    }

    fun consume(activeHostId: String?): OverviewReturnTarget? {
        val target = returnTarget
        returnTarget = null
        return target?.takeIf { it.hostId == activeHostId }
    }

    fun clear() {
        returnTarget = null
    }
}

internal data class HostNavigationChoice(
    val generation: Long,
    val hostId: String,
    val screen: Screen,
    val sessionId: String? = null,
    val provider: String = PROVIDER_CODEX,
    val focusedApprovalId: String? = null,
)

internal class HostNavigationState {
    private var generation = 0L
    private var choice: HostNavigationChoice? = null

    fun choose(
        hostId: String,
        screen: Screen,
        sessionId: String? = null,
        provider: String = PROVIDER_CODEX,
        focusedApprovalId: String? = null,
    ): HostNavigationChoice =
        HostNavigationChoice(
            generation = ++generation,
            hostId = hostId,
            screen = screen,
            sessionId = sessionId,
            provider = provider,
            focusedApprovalId = focusedApprovalId,
        ).also { choice = it }

    fun current(hostId: String): HostNavigationChoice? = choice?.takeIf { it.hostId == hostId }

    fun isCurrent(candidate: HostNavigationChoice): Boolean = choice == candidate
}

internal enum class HostNavigationAction { Show, Reconnect, Switch }

internal fun hostNavigationAction(
    activeHostId: String?,
    activeHostConnected: Boolean,
    requestedHostId: String,
): HostNavigationAction =
    when {
        activeHostId != requestedHostId -> HostNavigationAction.Switch
        activeHostConnected -> HostNavigationAction.Show
        else -> HostNavigationAction.Reconnect
    }

internal enum class RestartPhase { Idle, Scheduling, Scheduled, Reconnecting, Succeeded, TimedOut, Failed }

internal fun restartPhaseAfterConnection(
    phase: RestartPhase,
    connected: Boolean,
): RestartPhase =
    when {
        phase == RestartPhase.Scheduled && !connected -> RestartPhase.Reconnecting
        phase == RestartPhase.Reconnecting && connected -> RestartPhase.Succeeded
        else -> phase
    }

internal fun restartProgressLabel(phase: RestartPhase): String =
    when (phase) {
        RestartPhase.Idle -> ""
        RestartPhase.Scheduling -> "Scheduling restart…"
        RestartPhase.Scheduled -> "Restart scheduled; waiting for Foreman to stop…"
        RestartPhase.Reconnecting -> "Foreman is restarting; reconnecting…"
        RestartPhase.Succeeded -> "Restart complete; Foreman is connected."
        RestartPhase.TimedOut -> "Restart timed out before Foreman returned."
        RestartPhase.Failed -> "Restart could not be scheduled."
    }

internal fun diagnosticsText(events: List<DiagnosticEvent>): String =
    events.joinToString("\n") { event ->
        val request = event.requestCategory?.let { " [$it]" }.orEmpty()
        "${event.timestamp} ${event.severity.uppercase()} ${event.category}$request: ${event.message}"
    }

internal enum class SessionAction { Archive, Restore, Delete }

internal enum class SessionHapticEvent { Completed, Attention, Failed }

internal data class PendingSessionAction(
    val sessionId: String,
    val sessionTitle: String,
    val action: SessionAction,
    val provider: String = PROVIDER_CODEX,
    val repositoryId: String? = null,
)

internal fun sessionDisplayTitle(session: SessionSummary?): String =
    session?.title?.ifBlank { "Untitled session" } ?: "Session"

internal fun sessionCanBeManaged(status: String): Boolean =
    status !in setOf("working", "waiting", "stopping")

internal fun sessionRouteEditable(session: SessionSummary?): Boolean =
    session == null || (!session.archived && !session.readOnly && sessionCanBeManaged(session.status))

internal fun sessionActionSupported(capabilities: Set<String>, action: SessionAction): Boolean =
    capabilities.contains(
        when (action) {
            SessionAction.Archive -> "archive"
            SessionAction.Restore -> "restore"
            SessionAction.Delete -> "delete"
        },
    )

internal fun sessionActionSupported(
    session: SessionSummary,
    capabilities: Set<String>,
    action: SessionAction,
): Boolean =
    if (session.archived) {
        action == SessionAction.Restore && "session.restore" in session.capabilities
    } else if (sessionProvider(session) == PROVIDER_CLAUDE_CODE) {
        action == SessionAction.Delete && "session.delete" in session.capabilities
    } else if (session.capabilities.isNotEmpty()) {
        val required = when (action) {
            SessionAction.Archive -> "session.archive"
            SessionAction.Restore -> "session.restore"
            SessionAction.Delete -> "session.delete"
        }
        required in session.capabilities
    } else {
        sessionActionSupported(capabilities, action)
    }

internal fun sessionActionCanBeConfirmed(
    connected: Boolean,
    capabilities: Set<String>,
    action: SessionAction,
): Boolean = connected && sessionActionSupported(capabilities, action)

internal fun sessionHapticEvent(previous: String?, current: String?): SessionHapticEvent? {
    if (previous == null || current == null || previous == current) return null
    if (previous != "working" && previous != "waiting") return null
    return when (current) {
        "completed", "idle" -> SessionHapticEvent.Completed
        "waiting" -> SessionHapticEvent.Attention
        "failed" -> SessionHapticEvent.Failed
        else -> null
    }
}

internal fun eventShowsWorkingActivity(kind: String): Boolean =
    kind == "assistant.delta" || kind == "item" || kind == "activity"

internal fun compatibleEffort(model: ModelInfo, current: String?): String? =
    current?.takeIf(model.reasoningEfforts::contains)
        ?: model.defaultReasoningEffort?.takeIf(model.reasoningEfforts::contains)
        ?: model.reasoningEfforts.firstOrNull()

internal fun turnPayload(
    session: SessionSummary,
    text: String,
    images: List<ImagePayload>,
    steering: Boolean,
    accessLevel: String?,
    model: String?,
    effort: String?,
) = buildJsonObject {
    put("sessionId", session.id)
    put("text", text.trim())
    if (images.isNotEmpty()) {
        put(
            "images",
            buildJsonArray {
                images.forEach { image ->
                    add(
                        buildJsonObject {
                            put("mimeType", image.mimeType)
                            put("data", image.data)
                        },
                    )
                }
            },
        )
    }
    if (steering) {
        put("turnId", requireNotNull(session.activeTurnId))
    }
}

internal fun sessionSettingsPayload(
    sessionId: String,
    accessLevel: String? = null,
    model: String? = null,
    effort: String? = null,
) = buildJsonObject {
    put("sessionId", sessionId)
    accessLevel?.let { put("accessLevel", it) }
    model?.let { put("model", it) }
    effort?.let { put("reasoningEffort", it) }
}

internal fun UiState.withAccessLevelsAndSessionAccess(
    available: List<AccessLevelInfo>,
    session: SessionSummary?,
): UiState {
    val requested = if (session == null) composerAccessLevel else session.accessLevel
    val selected =
        available.firstOrNull { it.id == requested }
            ?: if (session == null) {
                available.firstOrNull { it.id == "ask" } ?: available.firstOrNull()
            } else {
                null
            }
    return copy(
        accessLevels = available,
        composerAccessLevel = selected?.id ?: requested,
    )
}

internal fun UiState.withModelsAndSessionRoute(
    available: List<ModelInfo>,
    session: SessionSummary?,
): UiState {
    val requestedModel = if (session == null) composerModel else session.model
    val selectedModel =
        available.firstOrNull { it.id == requestedModel }
            ?: if (session == null) {
                available.firstOrNull { it.isDefault } ?: available.firstOrNull()
            } else {
                null
            }
    return copy(
        models = available,
        composerModel = selectedModel?.id ?: requestedModel,
        composerEffort =
            selectedModel?.let {
                compatibleEffort(
                    it,
                    if (session == null) composerEffort else session.reasoningEffort,
                )
            } ?: if (session == null) composerEffort else session.reasoningEffort,
    )
}

internal fun UiState.withProviderRoute(session: SessionSummary?): UiState =
    if (session != null && sessionProvider(session) == PROVIDER_CLAUDE_CODE) {
        val model = claudeModels.firstOrNull { it.id == session.model }
        val permission = claudePermissionModes.firstOrNull { it.id == session.permissionMode }
        copy(
            claudeComposerModel = model?.id ?: session.model.orEmpty(),
            claudeComposerPermissionMode = permission?.id ?: session.permissionMode.orEmpty(),
        )
    } else {
        withModelsAndSessionRoute(models, session)
            .withAccessLevelsAndSessionAccess(accessLevels, session)
    }

internal fun reconcileSelectedSession(
    previous: SessionSummary?,
    incoming: SessionSummary?,
): SessionSummary? {
    if (incoming == null || previous?.providerKey() != incoming.providerKey()) return incoming
    val messages = incoming.messages.toMutableList()
    val knownIds = messages.mapTo(mutableSetOf()) { it.id }
    previous.messages.forEachIndexed { previousIndex, item ->
        if (item.id in knownIds || item.kind !in setOf("command", "tool")) return@forEachIndexed
        val followingId =
            previous.messages.asSequence().drop(previousIndex + 1)
                .firstOrNull { it.id in knownIds }?.id
        val followingIndex = followingId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        val sameTurnAssistantIndex =
            messages.indexOfFirst { candidate ->
                item.turnId != null && candidate.turnId == item.turnId && candidate.kind == "assistant"
            }
        val precedingId =
            previous.messages.asSequence().take(previousIndex).toList().asReversed()
                .firstOrNull { it.id in knownIds }?.id
        val precedingIndex = precedingId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        val insertionIndex = when {
            followingIndex >= 0 -> followingIndex
            sameTurnAssistantIndex >= 0 -> sameTurnAssistantIndex
            precedingIndex >= 0 -> precedingIndex + 1
            else -> messages.size
        }
        messages.add(insertionIndex, item)
        knownIds += item.id
    }
    val settings = reconcileSessionSettings(previous, incoming)
    return settings.copy(messages = messages)
}

internal fun reconcileSessionSettings(
    previous: SessionSummary?,
    incoming: SessionSummary,
): SessionSummary =
    if (
        previous?.providerKey() == incoming.providerKey() &&
        (previous.settingsRevision ?: 0L) > (incoming.settingsRevision ?: 0L)
    ) {
        incoming.copy(
            model = previous.model,
            reasoningEffort = previous.reasoningEffort,
            accessLevel = previous.accessLevel,
            permissionMode = previous.permissionMode,
            settingsRevision = previous.settingsRevision,
        )
    } else {
        incoming
    }

internal fun UiState.withSynchronizedSessions(
    sessions: List<SessionSummary>,
    repositories: List<RepositoryInfo>,
    selectedSessionId: String?,
    selectedSession: SessionSummary?,
    selectedProvider: String = PROVIDER_CODEX,
    applySelection: Boolean = true,
): UiState {
    val reconciledSelected = reconcileSelectedSession(selected, selectedSession)
    val previousById = this.sessions.associateBy { it.providerKey() }
    val reconciledSessions = sessions.map { incoming ->
        reconcileSessionSettings(previousById[incoming.providerKey()], incoming)
    }
    return copy(
        sessions = reconciledSessions,
        repositories = repositories,
        selected = if (applySelection) reconciledSelected else selected,
        screen =
            if (!applySelection) {
                screen
            } else if (selectedSessionId != null && reconciledSelected?.matches(selectedProvider, selectedSessionId) == true) {
                Screen.Detail
            } else if (screen == Screen.Detail) {
                Screen.Sessions
            } else {
                screen
            },
        loading = false,
        error = null,
    )
}

internal fun UiState.withDiscoveredSessions(discovered: List<SessionSummary>): UiState {
    val known = sessions.mapTo(mutableSetOf()) { it.providerKey() }
    val additions = discovered.filter { known.add(it.providerKey()) }
    return if (additions.isEmpty()) this else copy(sessions = additions + sessions)
}

internal fun UiState.shouldDiscoverSession(
    sessionId: String,
    eventKind: String,
    provider: String = PROVIDER_CODEX,
): Boolean =
    connected && eventKind == "status" && sessions.none { it.matches(provider, sessionId) }

internal class SessionDiscoveryQueue(private val maximumAttempts: Int = 4) {
    private val remainingAttempts = linkedMapOf<String, Int>()

    init {
        require(maximumAttempts > 0)
    }

    fun enqueue(sessionId: String) {
        if (sessionId !in remainingAttempts) {
            remainingAttempts[sessionId] = maximumAttempts
        }
    }

    fun targets(): Set<String> = remainingAttempts.keys.toSet()

    fun recordAttempt(targets: Set<String>, discoveredIds: Set<String>) {
        discoveredIds.forEach(remainingAttempts::remove)
        targets.forEach { sessionId ->
            val remaining = remainingAttempts[sessionId] ?: return@forEach
            if (remaining == 1) {
                remainingAttempts.remove(sessionId)
            } else {
                remainingAttempts[sessionId] = remaining - 1
            }
        }
    }

    fun retryDelayMillis(): Long {
        val mostRemaining = remainingAttempts.values.maxOrNull() ?: return 0L
        val completedAttempts = maximumAttempts - mostRemaining
        return 250L shl (completedAttempts - 1).coerceAtLeast(0)
    }

    fun clear() {
        remainingAttempts.clear()
    }
}

internal data class UiState(
    val screen: Screen = Screen.Setup,
    val displayName: String = "",
    val host: String = "",
    val pairingKey: String = "",
    val deviceName: String = "Android",
    val connected: Boolean = false,
    val hasSavedConnection: Boolean = false,
    val savedHosts: List<SavedHostSummary> = emptyList(),
    val activeHostId: String? = null,
    val connectionStatus: String = "disconnected",
    val addingHost: Boolean = false,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val openingWorkspaceFile: String? = null,
    val workspaceFile: WorkspaceFile? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val archivedSessions: List<SessionSummary> = emptyList(),
    val archivedLoading: Boolean = false,
    val archivedError: String? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val providerCatalogLoaded: Boolean = false,
    val accountUsage: AccountUsage = AccountUsage(),
    val repositories: List<RepositoryInfo> = emptyList(),
    val selected: SessionSummary? = null,
    val composerDrafts: Map<ComposerDraftKey, String> = emptyMap(),
    val showNewSession: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val themeId: ThemeId = ThemeId.Foreman,
    val activityDetail: ActivityDetail = ActivityDetail.Focused,
    val groupSessionsByRepository: Boolean = true,
    val collapsedRepositoriesByHost: Map<String, Set<String>> = emptyMap(),
    val followNewMessages: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val monitorActiveTurns: Boolean = false,
    val notificationPreferences: NotificationPreferences = NotificationPreferences(),
    val hostNotificationOverride: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val pendingSessionAction: PendingSessionAction? = null,
    val capabilities: Set<String> = emptySet(),
    val accessLevels: List<AccessLevelInfo> = emptyList(),
    val claudeModels: List<ModelInfo> = emptyList(),
    val claudePermissionModes: List<PermissionModeInfo> = emptyList(),
    val selectedNewSessionProvider: String = PROVIDER_CODEX,
    val claudeComposerModel: String = "sonnet",
    val claudeComposerPermissionMode: String = "default",
    val composerAccessLevel: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val composerModel: String? = null,
    val composerEffort: String? = null,
    val newSessionAccessLevel: String? = null,
    val newSessionModel: String? = null,
    val newSessionEffort: String? = null,
    val newSessionClaudeModel: String = "sonnet",
    val newSessionClaudePermissionMode: String = "default",
    val repositoryRoot: String = "",
    val searchFilters: SessionSearchFilters = SessionSearchFilters(),
    val searchResults: List<SessionSearchResult> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val showSearch: Boolean = false,
    val showSearchFilters: Boolean = false,
    val pinnedSessionIds: Set<String> = emptySet(),
    val hiddenSessionIds: Set<String> = emptySet(),
    val highlightedItemId: String? = null,
    val focusedApprovalId: String? = null,
    val approvals: List<ApprovalRequest> = emptyList(),
    val submittingApprovalIds: Set<String> = emptySet(),
    val approvalErrors: Map<String, String> = emptyMap(),
    val inputs: List<InputRequest> = emptyList(),
    val submittingInputIds: Set<String> = emptySet(),
    val inputErrors: Map<String, String> = emptyMap(),
    val overviewSnapshots: Map<String, HostOverviewSnapshot> = emptyMap(),
    val foremanVersion: String? = null,
    val foremanReleaseBuild: Boolean? = null,
    val releaseUpdates: ReleaseUpdateSnapshot? = null,
    val releaseCheckLoading: Boolean = false,
    val serverUpdateCheck: ServerUpdateCheck? = null,
    val serverUpdateOperation: ServerUpdateOperation? = null,
    val serverUpdateLoading: Boolean = false,
    val serverUpdateError: String? = null,
    val codexVersion: String? = null,
    val runtimeMode: String? = null,
    val runtimeConnected: Boolean = false,
    val diagnostics: List<DiagnosticEvent> = emptyList(),
    val diagnosticsLoading: Boolean = false,
    val diagnosticsError: String? = null,
    val restartPhase: RestartPhase = RestartPhase.Idle,
)

internal data class ComposerDraftKey(
    val hostId: String,
    val sessionId: String,
    val provider: String = PROVIDER_CODEX,
)

internal fun composerDraft(
    drafts: Map<ComposerDraftKey, String>,
    hostId: String,
    sessionId: String,
    provider: String = PROVIDER_CODEX,
): String = drafts[ComposerDraftKey(hostId, sessionId, provider)].orEmpty()

internal fun updateComposerDraft(
    drafts: Map<ComposerDraftKey, String>,
    hostId: String,
    sessionId: String,
    text: String,
    provider: String = PROVIDER_CODEX,
): Map<ComposerDraftKey, String> {
    val key = ComposerDraftKey(hostId, sessionId, provider)
    return if (text.isEmpty()) drafts - key else drafts + (key to text)
}

private fun <T> reconcileSessionPendingItems(
    current: List<T>,
    refreshed: List<T>,
    sessionId: String,
    baseline: List<T>,
    itemId: (T) -> String,
    itemSessionId: (T) -> String,
): List<T> {
    val baselineById =
        baseline.filter { itemSessionId(it) == sessionId }.associateBy(itemId)
    val currentSessionIds =
        current.filter { itemSessionId(it) == sessionId }.mapTo(mutableSetOf(), itemId)
    val removedIds = baselineById.keys.filterTo(mutableSetOf()) { it !in currentSessionIds }
    val newer =
        current.filter { item ->
            itemSessionId(item) == sessionId && baselineById[itemId(item)] != item
        }
    val protectedIds = removedIds.apply { newer.mapTo(this, itemId) }
    return current.filter { itemSessionId(it) != sessionId } +
        refreshed.filter { itemSessionId(it) == sessionId && itemId(it) !in protectedIds } +
        newer
}

internal fun reconcileSessionApprovals(
    current: List<ApprovalRequest>,
    refreshed: List<ApprovalRequest>,
    sessionId: String,
    baseline: List<ApprovalRequest>,
): List<ApprovalRequest> =
    reconcileSessionPendingItems(
        current,
        refreshed,
        sessionId,
        baseline,
        ApprovalRequest::id,
        ApprovalRequest::sessionId,
    )

internal fun reconcileSessionInputs(
    current: List<InputRequest>,
    refreshed: List<InputRequest>,
    sessionId: String,
    baseline: List<InputRequest>,
): List<InputRequest> =
    reconcileSessionPendingItems(
        current,
        refreshed,
        sessionId,
        baseline,
        InputRequest::id,
        InputRequest::sessionId,
    )

internal fun storedComposerDrafts(
    hostId: String?,
    drafts: Map<String, String>,
): Map<ComposerDraftKey, String> {
    val id = hostId ?: return emptyMap()
    return drafts.mapNotNull { (key, text) ->
        parseProviderSessionKey(key)?.let { (provider, sessionId) ->
            ComposerDraftKey(id, sessionId, provider) to text
        }
    }.toMap()
}

private data class SyncSnapshot(
    val sessions: List<SessionSummary>,
    val nonAuthoritativeSessionProviders: Set<String>,
    val providers: List<ProviderInfo>,
    val accountUsage: AccountUsage,
    val repositories: List<RepositoryInfo>,
    val repositoryRoot: String,
    val models: List<ModelInfo>,
    val accessLevels: List<AccessLevelInfo>,
    val claudeModels: List<ModelInfo>,
    val claudePermissionModes: List<PermissionModeInfo>,
    val approvals: List<ApprovalRequest>,
    val inputs: List<InputRequest>,
    val foremanVersion: String?,
    val foremanReleaseBuild: Boolean?,
    val releaseUpdates: ReleaseUpdateSnapshot?,
    val serverUpdateOperation: ServerUpdateOperation?,
    val codexVersion: String?,
    val runtimeMode: String?,
    val runtimeConnected: Boolean,
)

private fun UiPreferences.searchFilters(): SessionSearchFilters =
    SessionSearchFilters(
        query = searchQuery,
        scope = searchScope,
        provider = searchProvider,
        repository = searchRepository,
        status = searchStatus,
        dateRange = searchDateRange,
        dateFrom = searchDateFrom,
        dateTo = searchDateTo,
    )

internal fun UiState.withForgottenConnection(): UiState =
    copy(
        screen = Screen.Setup,
        displayName = "",
        host = "",
        pairingKey = "",
        deviceName = "Android",
        connected = false,
        hasSavedConnection = false,
        savedHosts = emptyList(),
        activeHostId = null,
        connectionStatus = "disconnected",
        addingHost = false,
        loading = false,
        submitting = false,
        error = null,
        sessions = emptyList(),
        archivedSessions = emptyList(),
        archivedLoading = false,
        archivedError = null,
        providers = emptyList(),
        providerCatalogLoaded = false,
        accountUsage = AccountUsage(),
        repositories = emptyList(),
        selected = null,
        composerDrafts = emptyMap(),
        collapsedRepositoriesByHost = emptyMap(),
        showNewSession = false,
        notificationPreferences = NotificationPreferences(),
        hostNotificationOverride = false,
        pendingSessionAction = null,
        capabilities = emptySet(),
        accessLevels = emptyList(),
        models = emptyList(),
        claudeModels = emptyList(),
        claudePermissionModes = emptyList(),
        selectedNewSessionProvider = PROVIDER_CODEX,
        claudeComposerModel = "sonnet",
        claudeComposerPermissionMode = "default",
        searchResults = emptyList(),
        searchLoading = false,
        searchError = null,
        approvals = emptyList(),
        submittingApprovalIds = emptySet(),
        approvalErrors = emptyMap(),
        inputs = emptyList(),
        submittingInputIds = emptySet(),
        inputErrors = emptyMap(),
        overviewSnapshots = emptyMap(),
        foremanVersion = null,
        foremanReleaseBuild = null,
        releaseUpdates = null,
        releaseCheckLoading = false,
        serverUpdateCheck = null,
        serverUpdateOperation = null,
        serverUpdateLoading = false,
        serverUpdateError = null,
        codexVersion = null,
        runtimeMode = null,
        runtimeConnected = false,
        diagnostics = emptyList(),
        diagnosticsLoading = false,
        diagnosticsError = null,
        restartPhase = RestartPhase.Idle,
    )

internal fun UiState.afterSessionArchived(provider: String, sessionId: String): UiState {
    val wasSelected = selected?.matches(provider, sessionId) == true
    val matchingArchiveSubmission = pendingSessionAction?.let {
        it.provider == provider && it.sessionId == sessionId && it.action == SessionAction.Archive
    } == true
    return copy(
        sessions = sessions.filterNot { it.matches(provider, sessionId) },
        searchResults = searchResults.filterNot { it.session.matches(provider, sessionId) },
        selected = if (wasSelected) null else selected,
        screen = if (wasSelected) Screen.Sessions else screen,
        loading = if (wasSelected) false else loading,
        highlightedItemId = if (wasSelected) null else highlightedItemId,
        focusedApprovalId = if (wasSelected) null else focusedApprovalId,
        submitting = if (matchingArchiveSubmission) false else submitting,
        pendingSessionAction = if (matchingArchiveSubmission) null else pendingSessionAction,
    )
}

internal fun archivedSessionMatchesRemembered(
    provider: String,
    sessionId: String,
    rememberedProvider: String,
    rememberedSessionId: String?,
): Boolean = rememberedProvider == provider && rememberedSessionId == sessionId

internal fun releaseCheckStillApplies(
    initiatingHostId: String,
    activeHostId: String?,
    requestGeneration: Long,
    currentGeneration: Long,
): Boolean =
    initiatingHostId == activeHostId && requestGeneration == currentGeneration

internal class ForemanViewModel(application: Application) : AndroidViewModel(application) {
    private val hosts = HostStore(application)
    private val overviewStore = HostOverviewStore(application)
    private val overviewLifecycle = AndroidOverviewLifecycle()
    private val notificationPreferencesStore = NotificationPreferenceStore(application)
    private var activeHost = hosts.active()
    private var preferences = PreferenceStore(application, activeHost?.id)
    private val savedPreferences = preferences.load()
    private val savedReleaseUpdateInfo = preferences.loadReleaseUpdateInfo()
    private val initiallyRememberedSession = rememberedSessionTarget(
        savedPreferences.selectedSessionProvider,
        savedPreferences.selectedSessionId,
    )
    private val savedSearchFilters =
        SessionSearchFilters(
            query = savedPreferences.searchQuery,
            scope = savedPreferences.searchScope,
            provider = savedPreferences.searchProvider,
            repository = savedPreferences.searchRepository,
            status = savedPreferences.searchStatus,
            dateRange = savedPreferences.searchDateRange,
            dateFrom = savedPreferences.searchDateFrom,
            dateTo = savedPreferences.searchDateTo,
        )
    val state =
        MutableStateFlow(
            UiState(
                screen = if (activeHost == null) Screen.Setup else restorationDestination(initiallyRememberedSession),
                displayName = activeHost?.displayName.orEmpty(),
                host = activeHost?.tcpEndpoint().orEmpty(),
                hasSavedConnection = activeHost != null,
                savedHosts = hosts.all().map(SavedHost::summary),
                activeHostId = activeHost?.id,
                composerDrafts = storedComposerDrafts(activeHost?.id, preferences.loadDrafts()),
                themeMode = savedPreferences.themeMode,
                themeId = savedPreferences.themeId,
                activityDetail = savedPreferences.activityDetail,
                groupSessionsByRepository = savedPreferences.groupSessionsByRepository,
                collapsedRepositoriesByHost = activeHost?.id?.let { hostId ->
                    savedPreferences.collapsedRepositoryIds.takeIf { it.isNotEmpty() }
                        ?.let { mapOf(hostId to it) }
                }.orEmpty(),
                followNewMessages = savedPreferences.followNewMessages,
                hapticsEnabled = savedPreferences.hapticsEnabled,
                monitorActiveTurns = savedPreferences.monitorActiveTurns,
                notificationPreferences = notificationPreferencesStore.load(activeHost?.id),
                hostNotificationOverride = notificationPreferencesStore.hasHostOverride(activeHost?.id),
                composerAccessLevel = savedPreferences.accessLevel,
                composerModel = savedPreferences.model,
                composerEffort = savedPreferences.reasoningEffort,
                newSessionAccessLevel = savedPreferences.accessLevel,
                newSessionModel = savedPreferences.model,
                newSessionEffort = savedPreferences.reasoningEffort,
                selectedNewSessionProvider = savedPreferences.lastProvider,
                claudeComposerModel = savedPreferences.claudeModel,
                claudeComposerPermissionMode = savedPreferences.claudePermissionMode,
                newSessionClaudeModel = savedPreferences.claudeModel,
                newSessionClaudePermissionMode = savedPreferences.claudePermissionMode,
                searchFilters = savedSearchFilters,
                showSearch = sessionSearchActive(savedSearchFilters),
                pinnedSessionIds = savedPreferences.pinnedSessionIds,
                hiddenSessionIds = savedPreferences.hiddenSessionIds,
                overviewSnapshots = overviewStore.all().filterKeys { id -> hosts.load(id) != null },
                foremanVersion = savedReleaseUpdateInfo?.serverVersion,
                foremanReleaseBuild = savedReleaseUpdateInfo?.serverReleaseBuild,
                releaseUpdates = savedReleaseUpdateInfo?.snapshot,
            ),
        )
    private val json = Json { ignoreUnknownKeys = true }
    private var reconnectJob: Job? = null
    private var restartReconnectJob: Job? = null
    private var restartTimeoutJob: Job? = null
    private var restartRequested = false
    private var updateRequested = false
    private var diagnosticsReturnScreen = Screen.Sessions
    private val sessionDiscoveryLock = Any()
    private val sessionDiscoveryQueue = SessionDiscoveryQueue()
    private var sessionDiscoveryJob: Job? = null
    private var restorationProvider: String = initiallyRememberedSession?.provider ?: PROVIDER_CODEX
    private var restorationSessionId: String? = initiallyRememberedSession?.sessionId
    private var nonAuthoritativeSessionProviders = setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE)
    private var sessionOpenGeneration = 0L
    private var releaseCheckGeneration = 0L
    private var serverUpdateGeneration = 0L
    private var providerCatalogRevision = 0L
    private var sessionSyncGeneration = 0L
    private var searchJob: Job? = null
    private var archivedDiscoveryJob: Job? = null
    private var archivedDiscoveryGeneration = 0L
    private var workspaceFileJob: Job? = null
    private var lastSearchRequestKey = ""
    private val overviewNavigation = OverviewNavigationState()
    private val hostNavigation = HostNavigationState()
    private var overviewJob: Job? = null
    private var presenceSyncJob: Job? = null
    private var desiredPresenceKey: String? = null
    private var publishedPresenceKey: String? = null
    private var presenceInitialized = false
    private var windowFocused = false
    private val overviewClient = ForemanClient(viewModelScope, onEvent = {}, onDisconnect = {})
    private val client = ForemanClient(
        viewModelScope,
        onEvent = ::handleEvent,
        onDisconnect = { message ->
            presenceSyncJob?.cancel()
            presenceSyncJob = null
            presenceInitialized = false
            state.value.activeHostId?.let { hostId ->
                hosts.updateConnection(hostId, "disconnected")
            }
            synchronized(sessionDiscoveryLock) {
                sessionDiscoveryJob?.cancel()
                sessionDiscoveryJob = null
                sessionDiscoveryQueue.clear()
            }
            state.update {
                val hostId = it.activeHostId
                val snapshots = if (hostId == null) it.overviewSnapshots else it.overviewSnapshots[hostId]?.let { cached ->
                    it.overviewSnapshots + (hostId to cached.copy(connection = "disconnected"))
                } ?: it.overviewSnapshots
                it.copy(
                    connected = false,
                    connectionStatus = "disconnected",
                    savedHosts = hosts.all().map(SavedHost::summary),
                    loading = false,
                    error = message,
                    openingWorkspaceFile = null,
                    workspaceFile = null,
                    capabilities = emptySet(),
                    pendingSessionAction = null,
                    approvals = it.approvals.map { approval ->
                        if (approval.status == "pending" || approval.status == "submitting") {
                            approval.copy(status = "expired", resolution = "disconnected")
                        } else approval
                    },
                    submittingApprovalIds = emptySet(),
                    overviewSnapshots = snapshots,
                )
            }
            if (restartRequested || updateRequested) launchRestartReconnect()
            updateActiveOverview()
        },
    )

    init {
        activeHost?.let(::launchReconnect)
    }

    fun setDisplayName(value: String) = state.update { it.copy(displayName = value) }
    fun setHost(value: String) = state.update { it.copy(host = value) }
    fun setPairingKey(value: String) = state.update { it.copy(pairingKey = value) }
    fun setDeviceName(value: String) = state.update { it.copy(deviceName = value) }
    fun setNewSession(open: Boolean) = state.update {
        it.copy(showNewSession = open && it.providerCatalogLoaded)
    }

    fun setProviderEnabled(provider: String, enabled: Boolean) {
        if (state.value.submitting) return
        viewModelScope.launch {
            state.update { it.copy(submitting = true, error = null) }
            runCatching {
                val response = client.request(
                    "provider.configure",
                    buildJsonObject {
                        put("provider", provider)
                        put("enabled", enabled)
                    },
                )
                val providers = response.payload.getValue("providers").jsonArray
                    .map { json.decodeFromJsonElement<ProviderInfo>(it) }
                providerCatalogRevision += 1
                val selected = state.value.selected?.takeIf { session ->
                    providers.any {
                        it.id == sessionProvider(session) && it.enabled && it.available
                    }
                }
                val rememberedEnabled = providers.any {
                    it.id == restorationProvider && it.enabled
                }
                if (restorationSessionId != null && !rememberedEnabled) clearRememberedSession()
                state.update {
                    it.copy(
                        providers = providers,
                        providerCatalogLoaded = true,
                        selected = selected,
                        screen = if (it.selected != null && selected == null) Screen.Sessions else it.screen,
                    )
                }
                synchronizeSessions(selected?.id, selected?.let(::sessionProvider) ?: PROVIDER_CODEX)
                state.update { it.copy(submitting = false) }
            }.onFailure(::fail)
        }
    }

    fun setComposerDraft(
        hostId: String,
        sessionId: String,
        text: String,
        provider: String = PROVIDER_CODEX,
    ) {
        preferences.setDraft(provider, sessionId, text)
        state.update {
            it.copy(
                composerDrafts = updateComposerDraft(it.composerDrafts, hostId, sessionId, text, provider),
            )
        }
    }

    fun openOverview() {
        val current = state.value
        overviewNavigation.capture(
            current.screen,
            current.activeHostId,
            current.selected?.id,
            current.selected?.let(::sessionProvider) ?: PROVIDER_CODEX,
        )
        showOverview()
    }

    fun showOverview() {
        sessionOpenGeneration += 1
        state.value.activeHostId?.let { hostNavigation.choose(it, Screen.Overview) }
        state.update { it.copy(screen = dashboardBackDestination(), selected = null, loading = false, error = null) }
        synchronizeSessionPresence()
    }

    fun hasOverviewReturnTarget(): Boolean = overviewNavigation.hasReturnTarget()

    fun backFromOverview() {
        val target = consumeOverviewReturnTarget() ?: return
        navigateToOverviewReturnTarget(target)
    }

    private fun consumeOverviewReturnTarget(): OverviewReturnTarget? =
        overviewNavigation.consume(state.value.activeHostId)
            ?.let { validatedOverviewReturnTarget(it, state.value.sessions) }

    private fun navigateToOverviewReturnTarget(target: OverviewReturnTarget) {
        when (target.screen) {
            Screen.Dashboard -> showDashboard()
            Screen.Detail -> target.sessionId?.let { openSession(it, provider = target.provider) } ?: showSessions()
            Screen.Sessions -> showSessions()
            Screen.Setup, Screen.Overview, Screen.Diagnostics -> Unit
        }
    }

    fun showDashboard() {
        sessionOpenGeneration += 1
        state.value.activeHostId?.let { hostNavigation.choose(it, Screen.Dashboard) }
        state.update { it.copy(screen = Screen.Dashboard, selected = null, loading = false, error = null) }
        synchronizeSessionPresence()
    }

    fun showSessions() {
        sessionOpenGeneration += 1
        state.value.activeHostId?.let { hostNavigation.choose(it, Screen.Sessions) }
        state.update { it.copy(screen = Screen.Sessions, selected = null, loading = false, error = null) }
        synchronizeSessionPresence()
    }

    fun enterSessions() {
        val current = state.value
        val target = rememberedSessionTarget(restorationProvider, restorationSessionId)
        val remembered = rememberedSessionForEntry(
            target = target,
            authoritative = current.connected && !current.loading,
            providers = current.providers,
            sessions = current.sessions,
            nonAuthoritativeProviders = nonAuthoritativeSessionProviders,
        )
        if (remembered == null) {
            if (target != null) clearRememberedSession()
            showSessions()
            return
        }
        val hostId = current.activeHostId
        if (hostId == null) {
            showSessions()
        } else if (current.connected) {
            openSession(remembered.sessionId, provider = remembered.provider)
        } else {
            openHostSession(hostId, remembered.provider, remembered.sessionId)
        }
    }

    fun openOverviewHost(hostId: String) {
        if (hostId == state.value.activeHostId) {
            showDashboard()
        } else {
            switchHost(hostId, Screen.Dashboard)
        }
    }

    fun openOverviewSessions(hostId: String) {
        overviewNavigation.clear()
        when (hostNavigationAction(state.value.activeHostId, state.value.connected, hostId)) {
            HostNavigationAction.Show -> enterSessions()
            HostNavigationAction.Reconnect -> {
                enterSessions()
                reconnect()
            }
            HostNavigationAction.Switch -> switchHost(hostId)
        }
    }

    fun reconnectOverviewHost(hostId: String) {
        if (hostId == state.value.activeHostId) reconnect() else openOverviewHost(hostId)
    }

    fun openOverviewSession(item: OverviewAttentionItem) {
        overviewNavigation.clear()
        if (item.hostId == state.value.activeHostId && state.value.connected) {
            openSession(item.sessionId, focusedApprovalId = item.approvalId, provider = item.provider)
        } else {
            openHostSession(item.hostId, item.provider, item.sessionId, item.approvalId)
        }
    }

    fun setSearchOpen(open: Boolean) {
        state.update { it.copy(showSearch = open, showSearchFilters = false) }
    }

    fun setSearchFilters(filters: SessionSearchFilters, immediate: Boolean = false) {
        val previous = state.value.searchFilters
        val requestChanged = sessionSearchRequestKey(previous) != sessionSearchRequestKey(filters)
        val discoveryChanged = previous.scope != filters.scope || previous.provider != filters.provider
        preferences.setSessionSearch(filters)
        state.update { it.copy(searchFilters = filters, searchError = null) }
        if (discoveryChanged) refreshArchivedSessions()
        if (requestChanged) scheduleSearch(if (immediate) 0 else 300)
    }

    fun searchNow() = scheduleSearch(0)

    fun setSearchFiltersOpen(open: Boolean) {
        state.update { it.copy(showSearchFilters = open) }
    }

    fun togglePinnedSession(id: String, provider: String = PROVIDER_CODEX) {
        state.update { current ->
            val key = providerSessionKey(provider, id)
            val ids = current.pinnedSessionIds.toMutableSet().apply {
                if (!add(key)) remove(key)
            }
            preferences.setPinnedSessionIds(ids)
            current.copy(pinnedSessionIds = ids)
        }
    }

    fun toggleHiddenSession(id: String, provider: String = PROVIDER_CODEX) {
        state.update { current ->
            val key = providerSessionKey(provider, id)
            val ids = current.hiddenSessionIds.toMutableSet().apply {
                if (!add(key)) remove(key)
            }
            preferences.setHiddenSessionIds(ids)
            current.copy(hiddenSessionIds = ids)
        }
    }

    private fun scheduleSearch(delayMillis: Long) {
        searchJob?.cancel()
        val filters = state.value.searchFilters
        val query = filters.query.trim()
        if (query.isBlank()) {
            lastSearchRequestKey = ""
            state.update { it.copy(searchResults = emptyList(), searchLoading = false, searchError = null) }
            return
        }
        val requestKey = sessionSearchRequestKey(filters)
        val changed = requestKey != lastSearchRequestKey
        lastSearchRequestKey = requestKey
        val codexSearchAllowed = filters.provider.isBlank() || filters.provider == PROVIDER_CODEX
        val archivedSearchAllowed = filters.scope != SessionDiscoveryScope.Archived || state.value.providers.any {
            it.id == PROVIDER_CODEX && it.enabled && it.available && "session.archived.list" in it.capabilities
        }
        if (!state.value.connected || "search" !in state.value.capabilities || !codexSearchAllowed || !archivedSearchAllowed) {
            if (changed) state.update { it.copy(searchResults = emptyList(), searchLoading = false) }
            return
        }
        state.update {
            it.copy(
                searchResults = if (changed) emptyList() else it.searchResults,
                searchLoading = true,
                searchError = null,
            )
        }
        searchJob = viewModelScope.launch {
            delay(delayMillis)
            val bounds = sessionDateBounds(filters)
            runCatching {
                client.request(
                    "session.search",
                    buildJsonObject {
                        put("query", filters.query.trim())
                        if (filters.repository.isBlank()) put("repository", JsonNull)
                        else put("repository", filters.repository)
                        put("statuses", buildJsonArray { searchStatusProtocol(filters.status).forEach { add(JsonPrimitive(it)) } })
                        bounds.first?.let { put("dateFrom", it) } ?: put("dateFrom", JsonNull)
                        bounds.second?.let { put("dateTo", it) } ?: put("dateTo", JsonNull)
                        put("archived", filters.scope == SessionDiscoveryScope.Archived)
                        put("limit", 100)
                    },
                ).payload.getValue("results").jsonArray
                    .map { json.decodeFromJsonElement<SessionSearchResult>(it) }
            }.onSuccess { results ->
                state.update { it.copy(searchResults = results, searchLoading = false) }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                state.update {
                    it.copy(
                        searchLoading = false,
                        searchError = error.message ?: "Search failed",
                    )
                }
            }
        }
    }

    private fun refreshArchivedSessions() {
        archivedDiscoveryJob?.cancel()
        val current = state.value
        val generation = ++archivedDiscoveryGeneration
        val hostId = current.activeHostId
        if (current.searchFilters.scope != SessionDiscoveryScope.Archived || !current.connected) {
            state.update { it.copy(archivedSessions = emptyList(), archivedLoading = false, archivedError = null) }
            return
        }
        val candidates = current.providers.filter { provider ->
            provider.enabled && provider.available &&
                "session.archived.list" in provider.capabilities &&
                (current.searchFilters.provider.isBlank() || current.searchFilters.provider == provider.id)
        }
        if (candidates.isEmpty()) {
            state.update { it.copy(archivedSessions = emptyList(), archivedLoading = false, archivedError = null) }
            return
        }
        state.update { it.copy(archivedSessions = emptyList(), archivedLoading = true, archivedError = null) }
        archivedDiscoveryJob = viewModelScope.launch {
            runCatching {
                coroutineScope {
                    candidates.map { provider ->
                        async {
                            client.request(
                                "provider.session.list",
                                buildJsonObject {
                                    put("provider", provider.id)
                                    put("scope", "archived")
                                },
                            ).payload.getValue("sessions").jsonArray.map {
                                json.decodeFromJsonElement<SessionSummary>(it).copy(
                                    provider = provider.id,
                                    archived = true,
                                    readOnly = true,
                                )
                            }
                        }
                    }.flatMap { it.await() }
                }.sortedWith(
                    compareByDescending<SessionSummary> { it.lastActivity ?: 0L }
                        .thenBy { it.providerKey() },
                )
            }.onSuccess { archived ->
                if (generation != archivedDiscoveryGeneration || hostId != state.value.activeHostId) return@onSuccess
                state.update { it.copy(archivedSessions = archived, archivedLoading = false) }
                val selectedId = restorationSessionId
                if (
                    state.value.screen == Screen.Detail && state.value.selected == null && selectedId != null &&
                    archived.any { it.matches(restorationProvider, selectedId) }
                ) openSession(selectedId, provider = restorationProvider)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                if (generation == archivedDiscoveryGeneration && hostId == state.value.activeHostId) {
                    state.update {
                        it.copy(
                            archivedLoading = false,
                            archivedError = error.message ?: "Archived sessions could not be loaded",
                        )
                    }
                }
            }
        }
    }

    fun setComposerModel(id: String) {
        val current = state.value
        val model = current.models.firstOrNull { it.id == id } ?: return
        val selectedSession = current.selected ?: return
        if (!sessionRouteEditable(selectedSession) || !current.connected || "threadSettings" !in current.capabilities) return
        val effort = compatibleEffort(model, current.composerEffort)
        val previousModel = current.composerModel
        val previousEffort = current.composerEffort
        val previousSessionModel = selectedSession.model
        val previousSessionEffort = selectedSession.reasoningEffort
        val startingRevision = selectedSession.settingsRevision ?: 0L
        state.update { it.copy(composerModel = model.id, composerEffort = effort) }
        val sessionId = selectedSession.id
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "session.settings",
                    sessionSettingsPayload(sessionId, model = model.id, effort = effort),
                )
            }.onSuccess { response ->
                val acknowledged = response.payload["session"]?.let {
                    runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                }
                state.update {
                    val selected = it.selected
                    val candidate = acknowledged ?: selected?.copy(model = model.id, reasoningEffort = effort)
                    val updated = candidate?.let { value -> reconcileSessionSettings(selected, value) }
                    val focused = selected?.matches(PROVIDER_CODEX, sessionId) == true
                    it.copy(
                        submitting = false,
                        selected =
                            if (selected?.matches(PROVIDER_CODEX, sessionId) == true) {
                                updated
                            } else {
                                selected
                            },
                        sessions = it.sessions.map { session ->
                            if (session.matches(PROVIDER_CODEX, sessionId) && updated != null) {
                                val reconciled = reconcileSessionSettings(session, updated)
                                session.copy(
                                    model = reconciled.model,
                                    reasoningEffort = reconciled.reasoningEffort,
                                    accessLevel = reconciled.accessLevel,
                                    settingsRevision = reconciled.settingsRevision,
                                )
                            } else session
                        },
                        composerModel = if (focused) updated?.model ?: model.id else it.composerModel,
                        composerEffort = if (focused) updated?.reasoningEffort ?: effort else it.composerEffort,
                    )
                }
            }.onFailure { error ->
                state.update {
                    val selected = it.selected
                    val newer = (selected?.settingsRevision ?: 0L) > startingRevision
                    val focused = selected?.matches(PROVIDER_CODEX, sessionId) == true
                    it.copy(
                        submitting = false,
                        composerModel = if (!focused) it.composerModel else if (newer) selected.model else previousModel,
                        composerEffort = if (!focused) it.composerEffort else if (newer) selected.reasoningEffort else previousEffort,
                        selected =
                            if (selected?.matches(PROVIDER_CODEX, sessionId) == true && !newer) {
                                selected.copy(
                                    model = previousSessionModel,
                                    reasoningEffort = previousSessionEffort,
                                )
                            } else {
                                selected
                            },
                        error = error.message ?: "Model setting was not updated",
                    )
                }
            }
        }
    }

    fun setComposerAccessLevel(id: String) {
        val current = state.value
        if (current.accessLevels.none { it.id == id }) return
        val selectedSession = current.selected ?: return
        if (!sessionRouteEditable(selectedSession) || !current.connected || "threadSettings" !in current.capabilities) return
        val previous = current.composerAccessLevel
        val previousSessionAccess = selectedSession.accessLevel
        val startingRevision = selectedSession.settingsRevision ?: 0L
        state.update { it.copy(composerAccessLevel = id) }
        val sessionId = selectedSession.id
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "session.settings",
                    sessionSettingsPayload(sessionId, accessLevel = id),
                )
            }.onSuccess { response ->
                val acknowledged = response.payload["session"]?.let {
                    runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                }
                state.update {
                    val selected = it.selected
                    val candidate = acknowledged ?: selected?.copy(accessLevel = id)
                    val updated = candidate?.let { value -> reconcileSessionSettings(selected, value) }
                    val focused = selected?.matches(PROVIDER_CODEX, sessionId) == true
                    it.copy(
                        submitting = false,
                        selected =
                            if (selected?.matches(PROVIDER_CODEX, sessionId) == true) {
                                updated
                            } else {
                                selected
                            },
                        sessions = it.sessions.map { session ->
                            if (session.matches(PROVIDER_CODEX, sessionId) && updated != null) {
                                val reconciled = reconcileSessionSettings(session, updated)
                                session.copy(
                                    model = reconciled.model,
                                    reasoningEffort = reconciled.reasoningEffort,
                                    accessLevel = reconciled.accessLevel,
                                    settingsRevision = reconciled.settingsRevision,
                                )
                            } else session
                        },
                        composerAccessLevel = if (focused) updated?.accessLevel ?: id else it.composerAccessLevel,
                    )
                }
            }.onFailure { error ->
                state.update {
                    val selected = it.selected
                    val newer = (selected?.settingsRevision ?: 0L) > startingRevision
                    val focused = selected?.matches(PROVIDER_CODEX, sessionId) == true
                    it.copy(
                        submitting = false,
                        composerAccessLevel = if (!focused) it.composerAccessLevel else if (newer) selected.accessLevel else previous,
                        selected =
                            if (selected?.matches(PROVIDER_CODEX, sessionId) == true && !newer) {
                                selected.copy(accessLevel = previousSessionAccess)
                            } else {
                                selected
                            },
                        error = error.message ?: "Access setting was not updated",
                    )
                }
            }
        }
    }

    fun setComposerEffort(effort: String) {
        val current = state.value
        val model = current.models.firstOrNull { it.id == current.composerModel } ?: return
        if (effort !in model.reasoningEfforts) return
        val selectedSession = current.selected ?: return
        if (!sessionRouteEditable(selectedSession) || !current.connected || "threadSettings" !in current.capabilities) return
        val previous = current.composerEffort
        val previousSessionEffort = selectedSession.reasoningEffort
        val startingRevision = selectedSession.settingsRevision ?: 0L
        state.update { it.copy(composerEffort = effort) }
        val sessionId = selectedSession.id
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "session.settings",
                    sessionSettingsPayload(sessionId, model = model.id, effort = effort),
                )
            }.onSuccess { response ->
                val acknowledged = response.payload["session"]?.let {
                    runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                }
                state.update {
                    val selected = it.selected
                    val candidate = acknowledged ?: selected?.copy(reasoningEffort = effort)
                    val updated = candidate?.let { value -> reconcileSessionSettings(selected, value) }
                    val focused = selected?.matches(PROVIDER_CODEX, sessionId) == true
                    it.copy(
                        submitting = false,
                        selected =
                            if (selected?.matches(PROVIDER_CODEX, sessionId) == true) {
                                updated
                            } else {
                                selected
                            },
                        sessions = it.sessions.map { session ->
                            if (session.matches(PROVIDER_CODEX, sessionId) && updated != null) {
                                val reconciled = reconcileSessionSettings(session, updated)
                                session.copy(
                                    model = reconciled.model,
                                    reasoningEffort = reconciled.reasoningEffort,
                                    accessLevel = reconciled.accessLevel,
                                    settingsRevision = reconciled.settingsRevision,
                                )
                            } else session
                        },
                        composerEffort = if (focused) updated?.reasoningEffort ?: effort else it.composerEffort,
                    )
                }
            }.onFailure { error ->
                state.update {
                    val selected = it.selected
                    val newer = (selected?.settingsRevision ?: 0L) > startingRevision
                    val focused = selected?.matches(PROVIDER_CODEX, sessionId) == true
                    it.copy(
                        submitting = false,
                        composerEffort = if (!focused) it.composerEffort else if (newer) selected.reasoningEffort else previous,
                        selected =
                            if (selected?.matches(PROVIDER_CODEX, sessionId) == true && !newer) {
                                selected.copy(reasoningEffort = previousSessionEffort)
                            } else {
                                selected
                            },
                        error = error.message ?: "Reasoning setting was not updated",
                    )
                }
            }
        }
    }

    fun setClaudeComposerModel(id: String) {
        val current = state.value
        if (current.claudeModels.none { it.id == id }) return
        val selected = current.selected?.takeIf { sessionProvider(it) == PROVIDER_CLAUDE_CODE } ?: return
        if (!sessionRouteEditable(selected) || !current.connected) return
        val previous = current.claudeComposerModel
        val startingRevision = selected.settingsRevision ?: 0L
        state.update { it.copy(claudeComposerModel = id, submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "provider.session.settings",
                    buildJsonObject {
                        put("provider", PROVIDER_CLAUDE_CODE)
                        put("sessionId", selected.id)
                        put("repositoryId", selected.repositoryId ?: ".")
                        put("model", id)
                    },
                )
            }.onSuccess { response ->
                val acknowledged = response.payload["session"]?.let {
                    runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                } ?: selected.copy(model = id)
                state.update {
                    val focused = it.selected?.matches(PROVIDER_CLAUDE_CODE, selected.id) == true
                    val reconciled = reconcileSessionSettings(it.selected, acknowledged)
                    it.copy(
                        submitting = false,
                        selected = if (focused) reconciled else it.selected,
                        sessions = it.sessions.map { session ->
                            if (session.matches(PROVIDER_CLAUDE_CODE, selected.id)) {
                                val latest = reconcileSessionSettings(session, reconciled)
                                session.copy(
                                    model = latest.model,
                                    permissionMode = latest.permissionMode,
                                    settingsRevision = latest.settingsRevision,
                                )
                            } else session
                        },
                        claudeComposerModel = if (focused) reconciled.model ?: id else it.claudeComposerModel,
                    )
                }
            }.onFailure { error ->
                state.update {
                    val focused = it.selected?.matches(PROVIDER_CLAUDE_CODE, selected.id) == true
                    val newer = (it.selected?.settingsRevision ?: 0L) > startingRevision
                    it.copy(
                        submitting = false,
                        claudeComposerModel = if (!focused) it.claudeComposerModel else if (newer) it.selected.model.orEmpty() else previous,
                        error = error.message ?: "Claude model setting was not updated",
                    )
                }
            }
        }
    }

    fun setClaudeComposerPermissionMode(id: String) {
        val current = state.value
        if (current.claudePermissionModes.none { it.id == id }) return
        val selected = current.selected?.takeIf { sessionProvider(it) == PROVIDER_CLAUDE_CODE } ?: return
        if (!sessionRouteEditable(selected) || !current.connected) return
        val previous = current.claudeComposerPermissionMode
        val startingRevision = selected.settingsRevision ?: 0L
        state.update { it.copy(claudeComposerPermissionMode = id, submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "provider.session.settings",
                    buildJsonObject {
                        put("provider", PROVIDER_CLAUDE_CODE)
                        put("sessionId", selected.id)
                        put("repositoryId", selected.repositoryId ?: ".")
                        put("permissionMode", id)
                    },
                )
            }.onSuccess { response ->
                val acknowledged = response.payload["session"]?.let {
                    runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                } ?: selected.copy(permissionMode = id)
                state.update {
                    val focused = it.selected?.matches(PROVIDER_CLAUDE_CODE, selected.id) == true
                    val reconciled = reconcileSessionSettings(it.selected, acknowledged)
                    it.copy(
                        submitting = false,
                        selected = if (focused) reconciled else it.selected,
                        sessions = it.sessions.map { session ->
                            if (session.matches(PROVIDER_CLAUDE_CODE, selected.id)) {
                                val latest = reconcileSessionSettings(session, reconciled)
                                session.copy(
                                    model = latest.model,
                                    permissionMode = latest.permissionMode,
                                    settingsRevision = latest.settingsRevision,
                                )
                            } else session
                        },
                        claudeComposerPermissionMode = if (focused) reconciled.permissionMode ?: id else it.claudeComposerPermissionMode,
                    )
                }
            }.onFailure { error ->
                state.update {
                    val focused = it.selected?.matches(PROVIDER_CLAUDE_CODE, selected.id) == true
                    val newer = (it.selected?.settingsRevision ?: 0L) > startingRevision
                    it.copy(
                        submitting = false,
                        claudeComposerPermissionMode = if (!focused) it.claudeComposerPermissionMode else if (newer) it.selected.permissionMode.orEmpty() else previous,
                        error = error.message ?: "Claude permission setting was not updated",
                    )
                }
            }
        }
    }

    fun composerError(message: String) = state.update { it.copy(error = message) }

    fun openWorkspaceFile(target: WorkspaceFileTarget) {
        workspaceFileJob?.cancel()
        workspaceFileJob = viewModelScope.launch {
            state.update { it.copy(openingWorkspaceFile = target.path, workspaceFile = null, error = null) }
            runCatching {
                client.request(
                    "workspace.file.read",
                    buildJsonObject { put("path", target.path) },
                )
            }.onSuccess { response ->
                val path = response.payload.getValue("path").jsonPrimitive.content
                val content = response.payload.getValue("content").jsonPrimitive.content
                state.update {
                    it.copy(
                        openingWorkspaceFile = null,
                        workspaceFile = WorkspaceFile(path = path, content = content, line = target.line),
                    )
                }
            }.onFailure { error ->
                state.update {
                    it.copy(
                        openingWorkspaceFile = null,
                        error = error.message ?: "Workspace file could not be opened",
                    )
                }
            }
        }
    }

    fun closeWorkspaceFile() {
        workspaceFileJob?.cancel()
        workspaceFileJob = null
        state.update { it.copy(openingWorkspaceFile = null, workspaceFile = null) }
    }

    fun setThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        state.update { it.copy(themeMode = mode) }
    }

    fun setThemeId(themeId: ThemeId) {
        preferences.setThemeId(themeId)
        state.update { it.copy(themeId = themeId) }
    }

    fun setActivityDetail(detail: ActivityDetail) {
        preferences.setActivityDetail(detail)
        state.update { it.copy(activityDetail = detail) }
    }

    fun setGroupSessionsByRepository(enabled: Boolean) {
        preferences.setGroupSessionsByRepository(enabled)
        state.update { it.copy(groupSessionsByRepository = enabled) }
    }

    fun toggleCollapsedRepository(repositoryId: String) {
        val hostId = state.value.activeHostId ?: return
        val collapsed = toggleCollapsedRepository(
            state.value.collapsedRepositoriesByHost,
            hostId,
            repositoryId,
        )
        preferences.setCollapsedRepositoryIds(collapsed[hostId].orEmpty())
        state.update { current ->
            if (current.activeHostId == hostId) {
                current.copy(collapsedRepositoriesByHost = collapsed)
            } else current
        }
    }

    fun setFollowNewMessages(enabled: Boolean) {
        preferences.setFollowNewMessages(enabled)
        state.update { it.copy(followNewMessages = enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        preferences.setHapticsEnabled(enabled)
        state.update { it.copy(hapticsEnabled = enabled) }
    }

    fun setMonitorActiveTurns(enabled: Boolean) {
        preferences.setMonitorActiveTurns(enabled)
        state.update { it.copy(monitorActiveTurns = enabled, error = null) }
        if (enabled) {
            overviewJob?.cancel()
            overviewJob = null
            overviewClient.close()
            startGlobalTurnMonitoring()
            startOverviewPolling()
        } else {
            TurnMonitorService.stopAll(getApplication())
            refreshOverview()
        }
    }

    fun setNotificationPreferences(value: NotificationPreferences) {
        val normalized = value.normalized()
        val hostId = state.value.activeHostId
        notificationPreferencesStore.save(
            normalized,
            hostId.takeIf { state.value.hostNotificationOverride },
        )
        state.update { it.copy(notificationPreferences = normalized) }
        runCatching { TurnMonitorService.refreshPreferences(getApplication()) }
    }

    fun setHostNotificationOverride(enabled: Boolean) {
        val hostId = state.value.activeHostId ?: return
        val next =
            if (enabled) {
                state.value.notificationPreferences.also {
                    notificationPreferencesStore.save(it, hostId)
                }
            } else {
                notificationPreferencesStore.clearHostOverride(hostId)
                notificationPreferencesStore.loadGlobal()
            }
        state.update {
            it.copy(
                notificationPreferences = next,
                hostNotificationOverride = enabled,
            )
        }
        runCatching { TurnMonitorService.refreshPreferences(getApplication()) }
    }

    fun notificationPermissionDenied() {
        state.update {
            it.copy(error = "Allow notifications to monitor active turns in the background.")
        }
    }

    fun onNotificationPermissionState(granted: Boolean) {
        state.update { it.copy(notificationPermissionGranted = granted) }
        if (shouldStopMonitoringForNotificationPermission(state.value.monitorActiveTurns, granted)) {
            setMonitorActiveTurns(false)
        }
    }

    fun openSessionFromNotification(
        hostId: String?,
        provider: String,
        id: String,
        approvalId: String? = null,
    ) {
        if (hostId == null || hosts.load(hostId) == null || !supportedProvider(provider)) return
        overviewNavigation.clear()
        if (state.value.activeHostId == hostId && state.value.connected) {
            openSession(id, focusedApprovalId = approvalId, provider = provider)
        } else {
            openHostSession(hostId, provider, id, approvalId)
        }
    }

    fun openAttentionFromNotification(hostId: String?) {
        if (hostId == null || hosts.load(hostId) == null) return
        overviewNavigation.clear()
        if (state.value.activeHostId == hostId && state.value.connected) {
            showDashboard()
        } else {
            switchHost(hostId, Screen.Dashboard)
        }
    }

    private fun openHostSession(
        hostId: String,
        provider: String,
        sessionId: String,
        focusedApprovalId: String? = null,
    ) {
        val choice = hostNavigation.choose(
            hostId = hostId,
            screen = Screen.Detail,
            sessionId = sessionId,
            provider = provider,
            focusedApprovalId = focusedApprovalId,
        )
        if (state.value.activeHostId == hostId) {
            state.update {
                it.copy(
                    screen = Screen.Detail,
                    selected = null,
                    loading = false,
                    error = null,
                    focusedApprovalId = focusedApprovalId,
                )
            }
            reconnect()
        } else {
            switchHost(hostId, choice)
        }
    }

    fun connect() {
        val current = state.value
        if (current.loading) return
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            runCatching {
                if (current.addingHost) stopActiveHost()
                val endpoint = parseHost(current.host)
                require(current.pairingKey.isNotBlank()) { "Pairing key is required" }
                require(current.deviceName.isNotBlank()) { "Device name is required" }
                val token =
                    client.pair(current.host, current.pairingKey, current.deviceName)
                val saved =
                    hosts.save(
                        current.displayName.ifBlank { suggestedHostDisplayName(endpoint.host) },
                        endpoint,
                        token,
                    )
                overviewNavigation.invalidateForHost(saved.id)
                activeHost = saved
                preferences = PreferenceStore(getApplication(), saved.id)
                val restored = preferences.load()
                val remembered = rememberedSessionTarget(
                    restored.selectedSessionProvider,
                    restored.selectedSessionId,
                )
                restorationProvider = remembered?.provider ?: PROVIDER_CODEX
                restorationSessionId = remembered?.sessionId
                nonAuthoritativeSessionProviders = setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE)
                hostNavigation.choose(saved.id, Screen.Sessions)
                val filters = restored.searchFilters()
                hosts.updateConnection(
                    saved.id,
                    "connected",
                    runtimeMode = client.runtimeMode,
                    connectedAt = System.currentTimeMillis(),
                )
                state.update {
                    it.copy(
                        connected = true,
                        connectionStatus = "connected",
                        hasSavedConnection = true,
                        savedHosts = hosts.all().map { host -> host.summary() },
                        activeHostId = saved.id,
                        composerDrafts =
                            it.composerDrafts.filterKeys { key -> key.hostId != saved.id } +
                                storedComposerDrafts(saved.id, preferences.loadDrafts()),
                        displayName = saved.displayName,
                        host = saved.tcpEndpoint(),
                        screen = Screen.Sessions,
                        addingHost = false,
                        pairingKey = "",
                        capabilities = client.capabilities,
                        sessions = emptyList(),
                        archivedSessions = emptyList(),
                        archivedLoading = false,
                        archivedError = null,
                        providers = emptyList(),
                        providerCatalogLoaded = false,
                        accountUsage = AccountUsage(),
                        repositories = emptyList(),
                        selected = null,
                        approvals = emptyList(),
                        submittingApprovalIds = emptySet(),
                        approvalErrors = emptyMap(),
                        inputs = emptyList(),
                        submittingInputIds = emptySet(),
                        inputErrors = emptyMap(),
                        repositoryRoot = "",
                        searchFilters = filters,
                        searchResults = emptyList(),
                        searchLoading = false,
                        searchError = null,
                        showSearch = sessionSearchActive(filters),
                        pinnedSessionIds = restored.pinnedSessionIds,
                        hiddenSessionIds = restored.hiddenSessionIds,
                        themeMode = restored.themeMode,
                        themeId = restored.themeId,
                        activityDetail = restored.activityDetail,
                        groupSessionsByRepository = restored.groupSessionsByRepository,
                        followNewMessages = restored.followNewMessages,
                        hapticsEnabled = restored.hapticsEnabled,
                        monitorActiveTurns = restored.monitorActiveTurns,
                        notificationPreferences = notificationPreferencesStore.load(saved.id),
                        hostNotificationOverride = notificationPreferencesStore.hasHostOverride(saved.id),
                        composerAccessLevel = restored.accessLevel,
                        composerModel = restored.model,
                        composerEffort = restored.reasoningEffort,
                        newSessionAccessLevel = restored.accessLevel,
                        newSessionModel = restored.model,
                        newSessionEffort = restored.reasoningEffort,
                        selectedNewSessionProvider = restored.lastProvider,
                        claudeComposerModel = restored.claudeModel,
                        claudeComposerPermissionMode = restored.claudePermissionMode,
                        newSessionClaudeModel = restored.claudeModel,
                        newSessionClaudePermissionMode = restored.claudePermissionMode,
                    )
                }
                synchronizeSessions()
            }.onFailure(::fail)
        }
    }

    fun reconnect() {
        val saved = state.value.activeHostId?.let(hosts::load) ?: run {
            state.update { it.copy(screen = Screen.Setup) }
            return
        }
        launchReconnect(saved)
    }

    fun openDiagnostics() {
        if (!state.value.hasSavedConnection) return
        diagnosticsReturnScreen = state.value.screen.takeUnless { it == Screen.Diagnostics } ?: Screen.Sessions
        state.update { it.copy(screen = Screen.Diagnostics, diagnosticsError = null) }
        refreshDiagnostics()
    }

    fun closeDiagnostics() {
        state.update { it.copy(screen = diagnosticsReturnScreen) }
    }

    fun refreshDiagnostics() {
        if (!state.value.connected || "diagnostics" !in state.value.capabilities) return
        viewModelScope.launch {
            state.update { it.copy(diagnosticsLoading = true, diagnosticsError = null) }
            runCatching {
                client.request("diagnostics.list").payload.getValue("events").jsonArray
                    .map { json.decodeFromJsonElement<DiagnosticEvent>(it) }
            }.onSuccess { events ->
                state.update { it.copy(diagnostics = events, diagnosticsLoading = false) }
            }.onFailure { error ->
                state.update {
                    it.copy(
                        diagnosticsLoading = false,
                        diagnosticsError = error.message ?: "Diagnostics could not be loaded",
                    )
                }
            }
        }
    }

    fun restartService() {
        val current = state.value
        if (!current.connected || "remoteRestart" !in current.capabilities || restartRequested) return
        if (restartBlocked(current)) {
            state.update {
                it.copy(diagnosticsError = "Restart is unavailable while sessions are active or waiting for attention.")
            }
            return
        }
        restartRequested = true
        state.update { it.copy(restartPhase = RestartPhase.Scheduling, diagnosticsError = null) }
        viewModelScope.launch {
            runCatching { client.request("service.restart") }.onSuccess { response ->
                val scheduled = response.payload["scheduled"]?.jsonPrimitive?.content == "true"
                if (!scheduled) {
                    restartRequested = false
                    state.update { it.copy(restartPhase = RestartPhase.Failed) }
                } else {
                    state.update {
                        if (it.restartPhase == RestartPhase.Reconnecting || it.restartPhase == RestartPhase.Succeeded) it
                        else it.copy(restartPhase = RestartPhase.Scheduled)
                    }
                    if (restartRequested) {
                        restartTimeoutJob?.cancel()
                        restartTimeoutJob =
                            viewModelScope.launch {
                                delay(45_000)
                                if (restartRequested) {
                                    restartRequested = false
                                    restartReconnectJob?.cancel()
                                    state.update {
                                        it.copy(
                                            restartPhase = RestartPhase.TimedOut,
                                            connectionStatus = if (it.connected) it.connectionStatus else "disconnected",
                                        )
                                    }
                                }
                            }
                    }
                }
            }.onFailure { error ->
                if (state.value.restartPhase != RestartPhase.Reconnecting) {
                    restartRequested = false
                    state.update {
                        it.copy(
                            restartPhase = RestartPhase.Failed,
                            diagnosticsError = error.message ?: "Restart could not be scheduled",
                        )
                    }
                }
            }
        }
    }

    private fun launchRestartReconnect() {
        if ((!restartRequested && !updateRequested) || restartReconnectJob?.isActive == true) return
        reconnectJob?.cancel()
        reconnectJob = null
        state.update {
            it.copy(
                restartPhase = RestartPhase.Reconnecting,
                connectionStatus = "reconnecting",
                providerCatalogLoaded = false,
            )
        }
        restartReconnectJob =
            viewModelScope.launch {
                val deadline = System.currentTimeMillis() + if (updateRequested) 150_000 else 45_000
                val saved = state.value.activeHostId?.let(hosts::load)
                while (saved != null && (restartRequested || updateRequested) && System.currentTimeMillis() < deadline) {
                    val connected =
                        runCatching {
                            withTimeout(5_000) {
                                client.authenticate(saved.tcpEndpoint(), saved.deviceToken)
                            }
                            if (state.value.activeHostId != saved.id) error("Host changed")
                            hosts.updateConnection(
                                saved.id,
                                "connected",
                                runtimeMode = client.runtimeMode,
                                connectedAt = System.currentTimeMillis(),
                            )
                            state.update {
                                it.copy(
                                    connected = true,
                                    connectionStatus = "connected",
                                    savedHosts = hosts.all().map(SavedHost::summary),
                                    capabilities = client.capabilities,
                                    error = null,
                                )
                            }
                            synchronizeSessions(
                                state.value.selected?.id,
                                state.value.selected?.let(::sessionProvider) ?: PROVIDER_CODEX,
                            )
                        }.isSuccess
                    if (connected) {
                        restartRequested = false
                        restartTimeoutJob?.cancel()
                        state.update { it.copy(restartPhase = RestartPhase.Succeeded) }
                        if (updateRequested) refreshServerUpdateStatus(waitForTerminal = true)
                        refreshDiagnostics()
                        return@launch
                    }
                    delay(750)
                }
                restartRequested = false
                updateRequested = false
                state.update { it.copy(restartPhase = RestartPhase.TimedOut, connectionStatus = "disconnected") }
            }
    }

    fun addHost() {
        overviewNavigation.clear()
        state.update {
            it.copy(
                screen = Screen.Setup,
                addingHost = true,
                displayName = "",
                host = "",
                pairingKey = "",
                deviceName = "Android",
                error = null,
            )
        }
    }

    fun cancelAddHost() {
        val saved = activeHost
        if (saved == null) return
        state.update {
            it.copy(
                screen = Screen.Sessions,
                addingHost = false,
                displayName = saved.displayName,
                host = saved.tcpEndpoint(),
                pairingKey = "",
                error = null,
            )
        }
        if (!state.value.connected) launchReconnect(saved)
    }

    fun renameHost(hostId: String, displayName: String) {
        hosts.rename(hostId, displayName)
        activeHost = state.value.activeHostId?.let(hosts::load)
        state.update {
            it.copy(
                displayName = activeHost?.displayName ?: it.displayName,
                savedHosts = hosts.all().map { host -> host.summary() },
            )
        }
    }

    fun forgetHost() {
        state.value.activeHostId?.let(::forgetHost)
    }

    fun forgetHost(hostId: String) {
        val forgettingActive = state.value.activeHostId == hostId
        if (forgettingActive) stopActiveHost()
        notificationPreferencesStore.clearHostOverride(hostId)
        val next = hosts.forget(hostId)
        if (!forgettingActive) {
            state.update {
                it.copy(
                    savedHosts = hosts.all().map { host -> host.summary() },
                    overviewSnapshots = it.overviewSnapshots - hostId,
                    composerDrafts = it.composerDrafts.filterKeys { key -> key.hostId != hostId },
                    collapsedRepositoriesByHost = it.collapsedRepositoriesByHost - hostId,
                )
            }
            return
        }
        activeHost = next
        if (next == null) {
            restorationProvider = PROVIDER_CODEX
            restorationSessionId = null
            preferences = PreferenceStore(getApplication(), null)
            val restored = preferences.load()
            state.update {
                it.withForgottenConnection().copy(
                    themeMode = restored.themeMode,
                    themeId = restored.themeId,
                    activityDetail = restored.activityDetail,
                    groupSessionsByRepository = restored.groupSessionsByRepository,
                    followNewMessages = restored.followNewMessages,
                    hapticsEnabled = restored.hapticsEnabled,
                    monitorActiveTurns = restored.monitorActiveTurns,
                )
            }
            return
        }
        state.update {
            it.copy(
                overviewSnapshots = it.overviewSnapshots - hostId,
                composerDrafts = it.composerDrafts.filterKeys { key -> key.hostId != hostId },
                collapsedRepositoriesByHost = it.collapsedRepositoriesByHost - hostId,
            )
        }
        activateSavedHost(next)
    }

    fun switchHost(hostId: String) {
        if (hostId == state.value.activeHostId) return
        val selected = hosts.select(hostId) ?: return
        overviewNavigation.invalidateForHost(selected.id)
        stopActiveHost()
        activeHost = selected
        activateSavedHost(selected)
    }

    private fun switchHost(hostId: String, destination: Screen) {
        if (hostId == state.value.activeHostId) return
        switchHost(hostId, hostNavigation.choose(hostId, destination))
    }

    private fun switchHost(hostId: String, choice: HostNavigationChoice) {
        if (hostId == state.value.activeHostId) return
        val selected = hosts.select(hostId) ?: return
        overviewNavigation.invalidateForHost(selected.id)
        stopActiveHost()
        activeHost = selected
        activateSavedHost(selected, choice)
    }

    private fun stopActiveHost() {
        releaseCheckGeneration += 1
        serverUpdateGeneration += 1
        reconnectJob?.cancel()
        reconnectJob = null
        restartReconnectJob?.cancel()
        restartReconnectJob = null
        restartTimeoutJob?.cancel()
        restartTimeoutJob = null
        restartRequested = false
        updateRequested = false
        searchJob?.cancel()
        searchJob = null
        archivedDiscoveryJob?.cancel()
        archivedDiscoveryJob = null
        presenceSyncJob?.cancel()
        presenceSyncJob = null
        desiredPresenceKey = null
        publishedPresenceKey = null
        presenceInitialized = false
        synchronized(sessionDiscoveryLock) {
            sessionDiscoveryJob?.cancel()
            sessionDiscoveryJob = null
            sessionDiscoveryQueue.clear()
        }
        client.close()
        TurnMonitorService.stopAll(getApplication())
        state.value.activeHostId?.let { hosts.updateConnection(it, "disconnected") }
    }

    private fun activateSavedHost(saved: SavedHost, requestedChoice: HostNavigationChoice? = null) {
        providerCatalogRevision += 1
        sessionSyncGeneration += 1
        overviewNavigation.invalidateForHost(saved.id)
        preferences = PreferenceStore(getApplication(), saved.id)
        val restored = preferences.load()
        val restoredReleaseUpdateInfo = preferences.loadReleaseUpdateInfo()
        val remembered = rememberedSessionTarget(
            restored.selectedSessionProvider,
            restored.selectedSessionId,
        )
        restorationProvider = remembered?.provider ?: PROVIDER_CODEX
        restorationSessionId = remembered?.sessionId
        nonAuthoritativeSessionProviders = setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE)
        val choice = requestedChoice ?: hostNavigation.choose(
            hostId = saved.id,
            screen = restorationDestination(remembered),
            sessionId = restorationSessionId,
            provider = restorationProvider,
        )
        val filters = restored.searchFilters()
        state.update {
            it.copy(
                screen = choice.screen,
                displayName = saved.displayName,
                host = saved.tcpEndpoint(),
                pairingKey = "",
                connected = false,
                connectionStatus = "reconnecting",
                hasSavedConnection = true,
                savedHosts = hosts.all().map { host -> host.summary() },
                activeHostId = saved.id,
                composerDrafts =
                    it.composerDrafts.filterKeys { key -> key.hostId != saved.id } +
                        storedComposerDrafts(saved.id, preferences.loadDrafts()),
                addingHost = false,
                loading = false,
                submitting = false,
                error = null,
                sessions = emptyList(),
                archivedSessions = emptyList(),
                archivedLoading = false,
                archivedError = null,
                providers = emptyList(),
                providerCatalogLoaded = false,
                accountUsage = AccountUsage(),
                repositories = emptyList(),
                selected = null,
                showNewSession = false,
                pendingSessionAction = null,
                capabilities = emptySet(),
                accessLevels = emptyList(),
                models = emptyList(),
                claudeModels = emptyList(),
                claudePermissionModes = emptyList(),
                repositoryRoot = "",
                searchFilters = filters,
                searchResults = emptyList(),
                searchLoading = false,
                searchError = null,
                showSearch = sessionSearchActive(filters),
                pinnedSessionIds = restored.pinnedSessionIds,
                hiddenSessionIds = restored.hiddenSessionIds,
                collapsedRepositoriesByHost = if (restored.collapsedRepositoryIds.isEmpty()) {
                    it.collapsedRepositoriesByHost - saved.id
                } else {
                    it.collapsedRepositoriesByHost + (saved.id to restored.collapsedRepositoryIds)
                },
                highlightedItemId = null,
                focusedApprovalId = choice.focusedApprovalId,
                approvals = emptyList(),
                submittingApprovalIds = emptySet(),
                approvalErrors = emptyMap(),
                inputs = emptyList(),
                submittingInputIds = emptySet(),
                inputErrors = emptyMap(),
                foremanVersion = restoredReleaseUpdateInfo?.serverVersion,
                foremanReleaseBuild = restoredReleaseUpdateInfo?.serverReleaseBuild,
                releaseUpdates = restoredReleaseUpdateInfo?.snapshot,
                releaseCheckLoading = false,
                serverUpdateCheck = null,
                serverUpdateOperation = null,
                serverUpdateLoading = false,
                serverUpdateError = null,
                codexVersion = null,
                runtimeMode = null,
                runtimeConnected = false,
                diagnostics = emptyList(),
                diagnosticsLoading = false,
                diagnosticsError = null,
                restartPhase = RestartPhase.Idle,
                themeMode = restored.themeMode,
                themeId = restored.themeId,
                activityDetail = restored.activityDetail,
                groupSessionsByRepository = restored.groupSessionsByRepository,
                followNewMessages = restored.followNewMessages,
                hapticsEnabled = restored.hapticsEnabled,
                monitorActiveTurns = restored.monitorActiveTurns,
                notificationPreferences = notificationPreferencesStore.load(saved.id),
                hostNotificationOverride = notificationPreferencesStore.hasHostOverride(saved.id),
                composerAccessLevel = restored.accessLevel,
                composerModel = restored.model,
                composerEffort = restored.reasoningEffort,
                newSessionAccessLevel = restored.accessLevel,
                newSessionModel = restored.model,
                newSessionEffort = restored.reasoningEffort,
                selectedNewSessionProvider = restored.lastProvider,
                claudeComposerModel = restored.claudeModel,
                claudeComposerPermissionMode = restored.claudePermissionMode,
                newSessionClaudeModel = restored.claudeModel,
                newSessionClaudePermissionMode = restored.claudePermissionMode,
            )
        }
        launchReconnect(saved)
    }

    fun onForeground() {
        overviewLifecycle.onForeground()
        synchronizeSessionPresence()
        startOverviewPolling()
        val saved = state.value.activeHostId?.let(hosts::load) ?: return
        if (state.value.loading || reconnectJob?.isActive == true) return
        reconnectJob =
            viewModelScope.launch {
                if (state.value.connected) {
                    val healthy =
                        runCatching {
                            withTimeout(5_000) { client.request("ping") }
                        }.isSuccess
                    if (healthy) {
                        synchronizeSessions(
                            state.value.selected?.id,
                            state.value.selected?.let(::sessionProvider) ?: PROVIDER_CODEX,
                        )
                        return@launch
                    }
                }
                reconnectSaved(saved)
            }
    }

    fun onBackground() {
        overviewLifecycle.onBackground()
        windowFocused = false
        synchronizeSessionPresence()
        overviewJob?.cancel()
        overviewJob = null
        overviewClient.close()
    }

    private fun synchronizeSessionPresence() {
        desiredPresenceKey =
            focusedSessionPresenceKey(
                overviewLifecycle.foreground && windowFocused,
                state.value.screen,
                state.value.selected,
            )
        if (!state.value.connected || "sessionPresence" !in state.value.capabilities) {
            presenceInitialized = false
            return
        }
        if (presenceSyncJob?.isActive == true) return
        presenceSyncJob =
            viewModelScope.launch {
                var requestFailed = false
                while (true) {
                    val target = desiredPresenceKey
                    if (!sessionPresenceSyncPending(presenceInitialized, publishedPresenceKey, target)) break
                    val identity = target?.let(::parseProviderSessionKey)
                    val payload =
                        if (identity == null) {
                            buildJsonObject { }
                        } else {
                            buildJsonObject {
                                put("provider", identity.first)
                                put("sessionId", identity.second)
                            }
                        }
                    if (runCatching { client.request("session.presence", payload) }.isFailure) {
                        presenceInitialized = false
                        requestFailed = true
                        break
                    }
                    publishedPresenceKey = target
                    presenceInitialized = true
                }
                presenceSyncJob = null
                // A focus/background transition can arrive after the loop's final
                // comparison but before this job clears itself. Recheck after
                // releasing the job slot so that update cannot be lost.
                if (!requestFailed && sessionPresenceSyncPending(
                        presenceInitialized,
                        publishedPresenceKey,
                        desiredPresenceKey,
                    )
                ) {
                    synchronizeSessionPresence()
                }
            }
    }

    fun onVisibleSessionChanged() = synchronizeSessionPresence()

    fun onWindowFocusChanged(hasFocus: Boolean) {
        windowFocused = hasFocus
        synchronizeSessionPresence()
    }

    fun refreshOverview() {
        if (!overviewLifecycle.foreground) return
        overviewJob?.cancel()
        overviewJob = null
        startOverviewPolling()
    }

    private fun startOverviewPolling() {
        if (!overviewLifecycle.foreground || overviewJob?.isActive == true) return
        overviewJob = viewModelScope.launch {
            while (overviewLifecycle.foreground) {
                refreshInactiveHostOverviews()
                delay(ANDROID_OVERVIEW_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshInactiveHostOverviews() {
        // The foreground monitoring service may already own the second socket.
        // In that mode, cached inactive-host snapshots are the battery-safe fallback.
        if (state.value.monitorActiveTurns) return
        hosts.all().filterNot { it.id == state.value.activeHostId }.forEach { host ->
            if (!overviewLifecycle.beginProbe()) return
            try {
                val snapshot = runCatching {
                    overviewClient.authenticate(host.tcpEndpoint(), host.deviceToken)
                    val providers = runCatching {
                        overviewClient.request("provider.list").payload.getValue("providers").jsonArray
                            .map { json.decodeFromJsonElement<ProviderInfo>(it) }
                    }.getOrElse { defaultProviders() }
                    val codexAvailable = providers.any {
                        it.id == PROVIDER_CODEX && it.enabled && it.available
                    }
                    val codexSessions = if (codexAvailable) {
                        overviewClient.request("session.list").payload.getValue("sessions").jsonArray
                            .map { json.decodeFromJsonElement<SessionSummary>(it) }
                    } else emptyList()
                    val claudeSessions =
                        if (providers.any { it.id == PROVIDER_CLAUDE_CODE && it.enabled && it.available }) {
                            runCatching {
                                overviewClient.request(
                                    "provider.session.list",
                                    buildJsonObject { put("provider", PROVIDER_CLAUDE_CODE) },
                                ).payload.getValue("sessions").jsonArray
                                    .map { json.decodeFromJsonElement<SessionSummary>(it) }
                            }.getOrElse { emptyList() }
                        } else emptyList()
                    val sessions = codexSessions + claudeSessions
                    val approvals = if (codexAvailable) {
                        overviewClient.request("approval.list").payload.getValue("approvals").jsonArray
                            .map { json.decodeFromJsonElement<ApprovalRequest>(it) }
                    } else emptyList()
                    val inputs = if (codexAvailable) {
                        overviewClient.request("input.list").payload.getValue("inputs").jsonArray
                            .map { json.decodeFromJsonElement<InputRequest>(it) }
                    } else emptyList()
                    val service = overviewClient.request("service.status").payload
                    val codex = service["codex"]?.jsonObject
                    hosts.updateConnection(
                        host.id,
                        "disconnected",
                        runtimeMode = overviewClient.runtimeMode,
                        connectedAt = System.currentTimeMillis(),
                    )
                    projectHostOverview(
                        host.id,
                        sessions,
                        approvals,
                        connection = "checked",
                        foremanVersion = service["foremanVersion"]?.jsonPrimitive?.content,
                        codexVersion = codex?.get("version")?.jsonPrimitive?.content,
                        runtimeMode = codex?.get("mode")?.jsonPrimitive?.content,
                        runtimeConnected = codex?.get("connected")?.jsonPrimitive?.content == "true",
                        inputs = inputs,
                    ).copy(
                        claudeUnavailable = providers.any {
                            it.id == PROVIDER_CLAUDE_CODE && it.enabled && !it.available
                        },
                    )
                }.getOrElse {
                    state.value.overviewSnapshots[host.id]?.copy(connection = "disconnected")
                        ?: HostOverviewSnapshot(host.id, System.currentTimeMillis(), "disconnected")
                }
                overviewStore.save(snapshot)
                state.update { current ->
                    current.copy(
                        overviewSnapshots = current.overviewSnapshots + (host.id to snapshot),
                        savedHosts = hosts.all().map(SavedHost::summary),
                    )
                }
            } finally {
                overviewClient.close()
                overviewLifecycle.endProbe()
            }
        }
    }

    private fun updateActiveOverview() {
        val current = state.value
        val hostId = current.activeHostId ?: return
        val previous = current.overviewSnapshots[hostId]
        val snapshot = if (current.connected) {
            val projectedSessions = current.selected?.let { selected ->
                val provider = sessionProvider(selected)
                if (current.sessions.any { it.matches(provider, selected.id) }) {
                    current.sessions.map {
                        if (it.matches(provider, selected.id)) selected else it
                    }
                } else {
                    listOf(selected) + current.sessions
                }
            } ?: current.sessions
            projectHostOverview(
                hostId,
                projectedSessions,
                current.approvals,
                connection = "connected",
                foremanVersion = current.foremanVersion,
                codexVersion = current.codexVersion,
                runtimeMode = current.runtimeMode,
                runtimeConnected = current.runtimeConnected,
                inputs = current.inputs,
            ).copy(
                claudeUnavailable = current.providers.any {
                    it.id == PROVIDER_CLAUDE_CODE && it.enabled && !it.available
                },
            )
        } else {
            previous?.copy(connection = "disconnected")
                ?: HostOverviewSnapshot(hostId, System.currentTimeMillis(), "disconnected")
        }
        overviewStore.save(snapshot)
        state.update { it.copy(overviewSnapshots = it.overviewSnapshots + (hostId to snapshot)) }
    }

    private fun launchReconnect(saved: SavedHost) {
        if (state.value.loading || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch { reconnectSaved(saved) }
    }

    private suspend fun reconnectSaved(saved: SavedHost) {
        val initialChoice = hostNavigation.current(saved.id) ?: hostNavigation.choose(
            hostId = saved.id,
            screen = reconnectDestination(state.value.screen, restorationSessionId),
            sessionId = restorationSessionId,
            provider = restorationProvider,
        )
        state.update {
            it.copy(
                loading = true,
                error = null,
                connectionStatus = "reconnecting",
                providerCatalogLoaded = false,
            )
        }
        runCatching {
            client.authenticate(saved.tcpEndpoint(), saved.deviceToken)
            if (state.value.activeHostId != saved.id) return
            val choice = hostNavigation.current(saved.id) ?: initialChoice
            val selectedId = choice.sessionId.takeIf { choice.screen == Screen.Detail }
            hosts.updateConnection(
                saved.id,
                "connected",
                runtimeMode = client.runtimeMode,
                connectedAt = System.currentTimeMillis(),
            )
            state.update {
                it.copy(
                    connected = true,
                    connectionStatus = "connected",
                    savedHosts = hosts.all().map { host -> host.summary() },
                    screen = choice.screen,
                    error = null,
                    capabilities = client.capabilities,
                    focusedApprovalId = choice.focusedApprovalId,
                )
            }
            synchronizeSessions(selectedId, choice.provider, choice)
            val latestChoice = hostNavigation.current(saved.id)
            if (
                latestChoice != null &&
                latestChoice != choice &&
                latestChoice.screen == Screen.Detail &&
                latestChoice.sessionId != null
            ) {
                openSession(
                    latestChoice.sessionId,
                    focusedApprovalId = latestChoice.focusedApprovalId,
                    provider = latestChoice.provider,
                )
            }
        }.onFailure { error ->
            if (state.value.activeHostId != saved.id) return
            hosts.updateConnection(saved.id, "disconnected")
            state.update {
                it.copy(
                    connected = false,
                    connectionStatus = "disconnected",
                    savedHosts = hosts.all().map { host -> host.summary() },
                )
            }
            fail(error)
        }
    }

    fun refresh() {
        if (!state.value.connected || state.value.loading) return
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            runCatching {
                synchronizeSessions(
                    state.value.selected?.id,
                    state.value.selected?.let(::sessionProvider) ?: PROVIDER_CODEX,
                )
            }.onFailure(::fail)
        }
    }

    fun checkForUpdates() {
        val current = state.value
        if (!current.connected || current.releaseCheckLoading) return
        val hostId = current.activeHostId ?: return
        val generation = ++releaseCheckGeneration
        viewModelScope.launch {
            state.update { it.copy(releaseCheckLoading = true) }
            runCatching {
                decodeReleaseUpdates(json, client.request("release.check").payload)
                    ?: error("Foreman returned invalid release information")
            }.onSuccess { snapshot ->
                if (!releaseCheckStillApplies(hostId, state.value.activeHostId, generation, releaseCheckGeneration)) {
                    return@onSuccess
                }
                val latest = state.value
                val info = CachedReleaseUpdateInfo(
                    latest.foremanVersion,
                    latest.foremanReleaseBuild,
                    snapshot,
                )
                preferences.setReleaseUpdateInfo(info)
                state.update { it.copy(releaseUpdates = snapshot, releaseCheckLoading = false) }
            }.onFailure {
                if (releaseCheckStillApplies(hostId, state.value.activeHostId, generation, releaseCheckGeneration)) {
                    state.update { it.copy(releaseCheckLoading = false) }
                }
            }
        }
    }

    fun reviewServerUpdate() {
        val current = state.value
        if (!current.connected || "serverUpdate" !in current.capabilities || current.serverUpdateLoading) return
        val hostId = current.activeHostId ?: return
        val generation = ++serverUpdateGeneration
        viewModelScope.launch {
            state.update { it.copy(serverUpdateLoading = true, serverUpdateError = null) }
            runCatching {
                decodeServerUpdateCheck(json, client.request("update.check").payload)
                    ?: error("Foreman returned invalid server update information")
            }.onSuccess { check ->
                if (!releaseCheckStillApplies(hostId, state.value.activeHostId, generation, serverUpdateGeneration)) {
                    return@onSuccess
                }
                check.operation?.let {
                    preferences.setServerUpdateOperationId(it.id)
                }
                state.update {
                    it.copy(
                        serverUpdateCheck = check,
                        serverUpdateOperation = check.operation ?: it.serverUpdateOperation,
                        serverUpdateLoading = false,
                    )
                }
            }.onFailure { error ->
                if (releaseCheckStillApplies(hostId, state.value.activeHostId, generation, serverUpdateGeneration)) {
                    state.update {
                        it.copy(
                            serverUpdateLoading = false,
                            serverUpdateError = error.message ?: "The update could not be reviewed.",
                        )
                    }
                }
            }
        }
    }

    fun dismissServerUpdateReview() {
        state.update { it.copy(serverUpdateCheck = null, serverUpdateError = null) }
    }

    fun startServerUpdate() {
        val current = state.value
        val check = current.serverUpdateCheck ?: return
        if (
            !current.connected || "serverUpdate" !in current.capabilities || current.serverUpdateLoading ||
            !check.updateAvailable || check.blockers.isNotEmpty()
        ) return
        val hostId = current.activeHostId ?: return
        val generation = ++serverUpdateGeneration
        viewModelScope.launch {
            state.update { it.copy(serverUpdateLoading = true, serverUpdateError = null) }
            runCatching {
                val response = client.request(
                    "update.start",
                    buildJsonObject {
                        put("requestId", "android_${UUID.randomUUID().toString().replace("-", "")}")
                    },
                )
                decodeServerUpdateOperation(json, response.payload["operation"])
                    ?: error("Foreman returned an invalid update operation")
            }.onSuccess { operation ->
                if (!releaseCheckStillApplies(hostId, state.value.activeHostId, generation, serverUpdateGeneration)) {
                    return@onSuccess
                }
                preferences.setServerUpdateOperationId(operation.id)
                updateRequested = true
                state.update {
                    it.copy(
                        serverUpdateOperation = operation,
                        serverUpdateCheck = null,
                        serverUpdateLoading = false,
                    )
                }
                refreshServerUpdateStatus(waitForTerminal = true, generation = generation)
            }.onFailure { error ->
                if (releaseCheckStillApplies(hostId, state.value.activeHostId, generation, serverUpdateGeneration)) {
                    updateRequested = false
                    state.update {
                        it.copy(
                            serverUpdateLoading = false,
                            serverUpdateError = error.message ?: "The update could not be started.",
                        )
                    }
                }
            }
        }
    }

    private fun refreshServerUpdateStatus(
        waitForTerminal: Boolean = false,
        generation: Long = serverUpdateGeneration,
    ) {
        val hostId = state.value.activeHostId ?: return
        val operationId = preferences.loadServerUpdateOperationId() ?: return
        viewModelScope.launch {
            val attempts = if (waitForTerminal) 80 else 1
            repeat(attempts) {
                if (!releaseCheckStillApplies(hostId, state.value.activeHostId, generation, serverUpdateGeneration)) {
                    return@launch
                }
                val operation = runCatching {
                    decodeServerUpdateOperation(
                        json,
                        client.request(
                            "update.status",
                            buildJsonObject { put("operationId", operationId) },
                        ).payload["operation"],
                    )
                }.getOrNull()
                if (!releaseCheckStillApplies(hostId, state.value.activeHostId, generation, serverUpdateGeneration)) {
                    return@launch
                }
                if (operation != null) {
                    state.update { current -> current.copy(serverUpdateOperation = operation, serverUpdateLoading = false) }
                    if (operation.phase in terminalServerUpdatePhases) {
                        updateRequested = false
                        return@launch
                    }
                }
                if (!waitForTerminal) return@launch
                delay(1_500)
            }
            if (releaseCheckStillApplies(hostId, state.value.activeHostId, generation, serverUpdateGeneration)) {
                updateRequested = false
                state.update {
                    it.copy(serverUpdateError = "Update status timed out. Reopen About to read the durable result.")
                }
            }
        }
    }

    fun openSession(
        id: String,
        highlightedItemId: String? = null,
        focusedApprovalId: String? = null,
        provider: String = PROVIDER_CODEX,
    ) {
        if (!supportedProvider(provider)) {
            showSessions()
            state.update { it.copy(error = "This session provider is not supported.") }
            return
        }
        val generation = ++sessionOpenGeneration
        state.value.activeHostId?.let { hostId ->
            hostNavigation.choose(
                hostId = hostId,
                screen = Screen.Detail,
                sessionId = id,
                provider = provider,
                focusedApprovalId = focusedApprovalId,
            )
        }
        val approvalsAtOpen = state.value.approvals.filter { it.sessionId == id }
        val inputsAtOpen = state.value.inputs.filter { it.sessionId == id }
        val openingSummary = (state.value.archivedSessions + state.value.sessions)
            .firstOrNull { it.matches(provider, id) }
        val archived = openingSummary?.archived == true ||
            state.value.searchFilters.scope == SessionDiscoveryScope.Archived
        restorationProvider = provider
        restorationSessionId = id
        preferences.setSelectedSession(provider, id)
        viewModelScope.launch {
            state.update { it.copy(screen = Screen.Detail, loading = true, error = null, highlightedItemId = highlightedItemId, focusedApprovalId = focusedApprovalId) }
            runCatching {
                val (selected, refreshedApprovals, refreshedInputs) =
                    coroutineScope {
                        val selectedRequest = async { readSession(provider, id, archived) }
                        val approvalsRequest =
                            async {
                                if (provider == PROVIDER_CODEX && !archived) {
                                    client.request("approval.list").payload.getValue("approvals").jsonArray
                                        .map { json.decodeFromJsonElement<ApprovalRequest>(it) }
                                } else {
                                    emptyList()
                                }
                            }
                        val inputsRequest =
                            async {
                                if (provider == PROVIDER_CODEX && !archived) {
                                    runCatching {
                                        client.request("input.list").payload.getValue("inputs").jsonArray
                                            .map { json.decodeFromJsonElement<InputRequest>(it) }
                                    }.getOrNull()
                                } else {
                                    null
                                }
                            }
                        Triple(selectedRequest.await(), approvalsRequest.await(), inputsRequest.await())
                    }
                if (
                    generation != sessionOpenGeneration ||
                    state.value.screen != Screen.Detail ||
                    restorationSessionId != id ||
                    restorationProvider != provider
                ) return@runCatching
                state.update {
                    val reconciled = reconcileSelectedSession(it.selected, selected)
                    it.copy(
                        selected = reconciled,
                        loading = false,
                        approvals =
                            if (provider == PROVIDER_CODEX && !archived) {
                                reconcileSessionApprovals(
                                    it.approvals,
                                    refreshedApprovals,
                                    id,
                                    approvalsAtOpen,
                                )
                            } else {
                                it.approvals
                            },
                        inputs =
                            if (provider == PROVIDER_CODEX && !archived && refreshedInputs != null) {
                                reconcileSessionInputs(
                                    it.inputs,
                                    refreshedInputs,
                                    id,
                                    inputsAtOpen,
                                )
                            } else {
                                it.inputs
                            },
                    ).withProviderRoute(reconciled)
                }
                synchronizeSessionPresence()
                if (!archived) monitorIfActive(selected)
            }.onFailure { error ->
                if (
                    generation == sessionOpenGeneration &&
                    state.value.screen == Screen.Detail &&
                    restorationSessionId == id &&
                    restorationProvider == provider
                ) {
                    state.value.activeHostId?.let { hostNavigation.choose(it, Screen.Sessions) }
                    state.update {
                        it.copy(
                            screen = Screen.Sessions,
                            selected = null,
                            loading = false,
                            error = error.message ?: "Session could not be loaded",
                            highlightedItemId = null,
                            focusedApprovalId = null,
                        )
                    }
                    synchronizeSessionPresence()
                }
            }
        }
    }

    private suspend fun synchronizeSessions(
        selectedSessionId: String? = null,
        selectedProvider: String = PROVIDER_CODEX,
        expectedNavigation: HostNavigationChoice? = null,
    ) {
        val syncGeneration = ++sessionSyncGeneration
        val catalogRevision = providerCatalogRevision
        val synchronizedHostId = expectedNavigation?.hostId ?: state.value.activeHostId
        state.update { it.copy(loading = true, error = null) }
        val providers = client.request("provider.list").payload.getValue("providers").jsonArray
            .map { json.decodeFromJsonElement<ProviderInfo>(it) }
        val codexAvailable = providers.any {
            it.id == PROVIDER_CODEX && it.enabled && it.available
        }
        val claudeAvailable = providers.any {
            it.id == PROVIDER_CLAUDE_CODE && it.enabled && it.available
        }
        val snapshot =
            coroutineScope {
                val approvalsRequest =
                    async {
                        if (codexAvailable) client.request("approval.list") else null
                    }
                val inputsRequest =
                    async {
                        if (codexAvailable) client.request("input.list") else null
                    }
                val codexSessionsRequest = async {
                    if (codexAvailable) runCatching { listSessions(PROVIDER_CODEX) }
                    else Result.success(emptyList())
                }
                val repositoriesRequest = async { client.request("repository.list") }
                val serviceStatusRequest = async { client.request("service.status") }
                val usageRequest = async {
                    runCatching { client.request("usage.status") }.getOrNull()
                }
                val modelsRequest =
                    async {
                        if (codexAvailable) {
                            client.request("model.list")
                        } else {
                            null
                        }
                    }
                val accessRequest =
                    async {
                        if (codexAvailable) {
                            client.request("access.list")
                        } else {
                            null
                        }
                    }
                val claudeSessionsResult =
                    if (claudeAvailable) {
                        runCatching { listSessions(PROVIDER_CLAUDE_CODE) }
                    } else {
                        Result.success(emptyList())
                    }
                val codexSessionsResult = codexSessionsRequest.await()
                val sessions = codexSessionsResult.getOrDefault(emptyList()) +
                    claudeSessionsResult.getOrDefault(emptyList())
                val failedSessionProviders = buildSet {
                    if (codexAvailable && codexSessionsResult.isFailure) add(PROVIDER_CODEX)
                    if (claudeAvailable && claudeSessionsResult.isFailure) add(PROVIDER_CLAUDE_CODE)
                }
                val nonAuthoritativeSessionProviders = sessionProvidersWithoutAuthoritativeLists(
                    providers,
                    failedSessionProviders,
                )
                val claudeModels =
                    if (claudeAvailable) {
                        runCatching {
                            client.request(
                                "provider.model.list",
                                buildJsonObject { put("provider", PROVIDER_CLAUDE_CODE) },
                            ).payload.getValue("models").jsonArray
                                .map { json.decodeFromJsonElement<ModelInfo>(it) }
                        }.getOrElse { emptyList() }
                    } else emptyList()
                val claudePermissionModes =
                    if (claudeAvailable) {
                        runCatching {
                            client.request(
                                "provider.permission.list",
                                buildJsonObject { put("provider", PROVIDER_CLAUDE_CODE) },
                            ).payload.getValue("modes").jsonArray
                                .map { json.decodeFromJsonElement<PermissionModeInfo>(it) }
                        }.getOrElse { emptyList() }
                    } else emptyList()
                val repositories =
                    repositoriesRequest.await().payload.getValue("repositories").jsonArray
                        .map { json.decodeFromJsonElement<RepositoryInfo>(it) }
                val serviceStatus = serviceStatusRequest.await().payload
                val accountUsage = usageRequest.await()?.payload?.let {
                    runCatching { json.decodeFromJsonElement<AccountUsage>(it) }.getOrNull()
                } ?: AccountUsage()
                val codexStatus = serviceStatus["codex"]?.jsonObject
                val repositoryRoot = serviceStatus["repositoryRoot"]?.jsonPrimitive?.content.orEmpty()
                val models =
                    modelsRequest.await()?.payload?.get("models")?.jsonArray
                        ?.map { json.decodeFromJsonElement<ModelInfo>(it) }
                        ?.filter { it.visible }
                        ?: emptyList()
                val accessLevels =
                    accessRequest.await()?.payload?.get("levels")?.jsonArray
                        ?.map { json.decodeFromJsonElement<AccessLevelInfo>(it) }
                        ?: emptyList()
                val approvals =
                    approvalsRequest.await()?.payload?.get("approvals")?.jsonArray
                        ?.map { json.decodeFromJsonElement<ApprovalRequest>(it) }
                        ?: emptyList()
                val inputs =
                    inputsRequest.await()?.payload?.get("inputs")?.jsonArray
                        ?.map { json.decodeFromJsonElement<InputRequest>(it) }
                        ?: emptyList()
                SyncSnapshot(
                    sessions = sessions,
                    nonAuthoritativeSessionProviders = nonAuthoritativeSessionProviders,
                    providers = providers,
                    accountUsage = accountUsage,
                    repositories = repositories,
                    repositoryRoot = repositoryRoot,
                    models = models,
                    accessLevels = accessLevels,
                    claudeModels = claudeModels,
                    claudePermissionModes = claudePermissionModes,
                    approvals = approvals,
                    inputs = inputs,
                    foremanVersion = serviceStatus["foremanVersion"]?.jsonPrimitive?.content,
                    foremanReleaseBuild = serviceStatus["foremanReleaseBuild"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
                    releaseUpdates = decodeReleaseUpdates(json, serviceStatus["releaseUpdates"]),
                    serverUpdateOperation = decodeServerUpdateOperation(json, serviceStatus["serverUpdateOperation"]),
                    codexVersion = codexStatus?.get("version")?.jsonPrimitive?.content,
                    runtimeMode = codexStatus?.get("mode")?.jsonPrimitive?.content,
                    runtimeConnected = codexStatus?.get("connected")?.jsonPrimitive?.content == "true",
                )
        }
        val sessions = snapshot.sessions
        if (
            !providerCatalogResponseIsCurrent(
                synchronizedHostId,
                state.value.activeHostId,
                catalogRevision,
                providerCatalogRevision,
            ) || syncGeneration != sessionSyncGeneration
        ) return
        var selectedReadError: String? = null
        var authoritativeSelectionInvalid = false
        val archivedSelection = state.value.searchFilters.scope == SessionDiscoveryScope.Archived
        val selected = if (archivedSelection) null else selectedSessionId?.let { sessionId ->
            val target = rememberedSessionTarget(selectedProvider, sessionId)
            val provider = providers.firstOrNull { it.id == selectedProvider }
            val validated = rememberedSessionForEntry(
                target,
                authoritative = true,
                providers = providers,
                sessions = sessions,
                nonAuthoritativeProviders = snapshot.nonAuthoritativeSessionProviders,
            )
            val providerSessionsAuthoritative = provider?.available == true &&
                selectedProvider !in snapshot.nonAuthoritativeSessionProviders
            val listed = sessions.firstOrNull { it.matches(selectedProvider, sessionId) }
            if (validated == null) {
                authoritativeSelectionInvalid = true
                null
            } else if (!providerSessionsAuthoritative || listed == null) {
                selectedReadError = "${providerDisplayName(selectedProvider)} sessions are temporarily unavailable"
                null
            } else {
                runCatching { readSession(selectedProvider, sessionId) }.getOrElse { error ->
                    selectedReadError =
                        "${providerDisplayName(selectedProvider)} session history is unavailable: " +
                            (error.message ?: "provider unavailable")
                    null
                }
            }
        }
        if (
            !providerCatalogResponseIsCurrent(
                synchronizedHostId,
                state.value.activeHostId,
                catalogRevision,
                providerCatalogRevision,
            ) || syncGeneration != sessionSyncGeneration
        ) return
        nonAuthoritativeSessionProviders = snapshot.nonAuthoritativeSessionProviders
        val releaseUpdateInfo = snapshot.releaseUpdates?.let {
            CachedReleaseUpdateInfo(snapshot.foremanVersion, snapshot.foremanReleaseBuild, it)
        }
        if (releaseUpdateInfo != null) preferences.setReleaseUpdateInfo(releaseUpdateInfo)
        snapshot.serverUpdateOperation?.let { preferences.setServerUpdateOperationId(it.id) }
        val applySelection = !archivedSelection &&
            (expectedNavigation == null || hostNavigation.isCurrent(expectedNavigation))
        state.update {
            it.withSynchronizedSessions(
                    sessions = sessions,
                    repositories = snapshot.repositories,
                    selectedSessionId = selectedSessionId,
                    selectedSession = selected,
                    selectedProvider = selectedProvider,
                    applySelection = applySelection,
                )
                .copy(
                    providers = snapshot.providers,
                    providerCatalogLoaded = true,
                    accountUsage = snapshot.accountUsage,
                    repositoryRoot = snapshot.repositoryRoot,
                    claudeModels = snapshot.claudeModels,
                    claudePermissionModes = snapshot.claudePermissionModes,
                    approvals = snapshot.approvals,
                    submittingApprovalIds = emptySet(),
                    approvalErrors = emptyMap(),
                    inputs = snapshot.inputs,
                    submittingInputIds = emptySet(),
                    inputErrors = emptyMap(),
                    foremanVersion = snapshot.foremanVersion,
                    foremanReleaseBuild = snapshot.foremanReleaseBuild,
                    releaseUpdates = snapshot.releaseUpdates ?: it.releaseUpdates,
                    serverUpdateOperation = snapshot.serverUpdateOperation ?: it.serverUpdateOperation,
                    releaseCheckLoading = false,
                    codexVersion = snapshot.codexVersion,
                    runtimeMode = snapshot.runtimeMode,
                    runtimeConnected = snapshot.runtimeConnected,
                    error = selectedReadError,
                )
                .let { synchronized ->
                    if (selected != null && sessionProvider(selected) == PROVIDER_CLAUDE_CODE) {
                        synchronized.withProviderRoute(selected)
                    } else {
                        synchronized.withModelsAndSessionRoute(snapshot.models, selected)
                            .withAccessLevelsAndSessionAccess(snapshot.accessLevels, selected)
                    }
                }
        }
        if (applySelection && selectedSessionId != null && selected == null) {
            if (
                authoritativeSelectionInvalid &&
                restorationSessionId == selectedSessionId &&
                restorationProvider == selectedProvider
            ) clearRememberedSession()
            synchronizedHostId?.let { hostNavigation.choose(it, Screen.Sessions) }
        }
        val validIds = retainedSessionPreferenceIds(
            listedSessionIds = sessions.mapTo(mutableSetOf()) { it.providerKey() },
            persistedSessionIds = state.value.pinnedSessionIds + state.value.hiddenSessionIds,
            nonAuthoritativeProviders = snapshot.nonAuthoritativeSessionProviders,
        )
        preferences.retainSessionIds(validIds)
        state.update {
            it.copy(
                pinnedSessionIds = it.pinnedSessionIds.intersect(validIds),
                hiddenSessionIds = it.hiddenSessionIds.intersect(validIds),
            )
        }
        refreshArchivedSessions()
        scheduleSearch(0)
        startGlobalTurnMonitoring()
        updateActiveOverview()
        synchronizeSessionPresence()
        if (state.value.releaseUpdates?.refreshStatus == "checking") checkForUpdates()
    }

    private suspend fun listSessions(provider: String = PROVIDER_CODEX): List<SessionSummary> {
        val response =
            when (provider) {
                PROVIDER_CODEX -> client.request("session.list")
                PROVIDER_CLAUDE_CODE ->
                    client.request(
                        "provider.session.list",
                        buildJsonObject { put("provider", provider) },
                    )
                else -> error("Unsupported session provider")
            }
        return response.payload.getValue("sessions").jsonArray
            .map { json.decodeFromJsonElement<SessionSummary>(it) }
            .map { if (it.provider == null && provider != PROVIDER_CODEX) it.copy(provider = provider) else it }
    }

    private fun discoverSession(sessionId: String, provider: String = PROVIDER_CODEX) {
        if (provider == PROVIDER_CLAUDE_CODE) {
            viewModelScope.launch {
                runCatching { listSessions(provider) }.onSuccess { discovered ->
                    state.update { it.withDiscoveredSessions(discovered) }
                }
            }
            return
        }
        synchronized(sessionDiscoveryLock) {
            sessionDiscoveryQueue.enqueue(sessionId)
            if (sessionDiscoveryJob?.isActive == true) return
            sessionDiscoveryJob = viewModelScope.launch { discoverQueuedSessions() }
        }
    }

    private suspend fun discoverQueuedSessions() {
        while (true) {
            val targets =
                synchronized(sessionDiscoveryLock) {
                    sessionDiscoveryQueue.targets().ifEmpty {
                        sessionDiscoveryJob = null
                        return
                    }
                }
            val discovered = runCatching { listSessions() }.getOrNull()
            if (discovered != null) {
                state.update { it.withDiscoveredSessions(discovered) }
            }
            val retryDelay =
                synchronized(sessionDiscoveryLock) {
                    sessionDiscoveryQueue.recordAttempt(
                        targets,
                        discovered.orEmpty().mapTo(mutableSetOf()) { it.id },
                    )
                    if (sessionDiscoveryQueue.targets().isEmpty()) {
                        sessionDiscoveryJob = null
                        return
                    }
                    sessionDiscoveryQueue.retryDelayMillis()
                }
            delay(retryDelay)
        }
    }

    private suspend fun readSession(
        provider: String,
        id: String,
        archived: Boolean = false,
    ): SessionSummary {
        val summary = (state.value.archivedSessions + state.value.sessions)
            .firstOrNull { it.matches(provider, id) }
        val providerPayload = buildJsonObject {
            put("provider", provider)
            put("sessionId", id)
            if (archived) put("scope", "archived")
            if (provider == PROVIDER_CLAUDE_CODE) {
                put("repositoryId", summary?.repositoryId ?: ".")
            }
        }
        val (subscribeType, readType, payload) =
            when (provider) {
                PROVIDER_CODEX -> if (archived) Triple(
                    null,
                    "provider.session.read",
                    providerPayload,
                ) else Triple(
                    "session.subscribe",
                    "session.read",
                    buildJsonObject { put("sessionId", id) },
                )
                PROVIDER_CLAUDE_CODE -> Triple("provider.session.subscribe", "provider.session.read", providerPayload)
                else -> error("Unsupported session provider")
            }
        subscribeType?.let { client.request(it, payload) }
        val response =
            client.request(readType, payload)
        return json.decodeFromJsonElement(
            response.payload.getValue("session"),
        )
    }

    fun backToSessions() {
        sessionOpenGeneration += 1
        state.value.activeHostId?.let { hostNavigation.choose(it, Screen.Sessions) }
        state.update { it.copy(screen = Screen.Sessions, selected = null, loading = false, error = null, highlightedItemId = null, focusedApprovalId = null) }
        synchronizeSessionPresence()
        refresh()
    }

    private fun clearRememberedSession() {
        restorationProvider = PROVIDER_CODEX
        restorationSessionId = null
        preferences.setSelectedSession(PROVIDER_CODEX, null)
    }

    fun requestSessionAction(session: SessionSummary, action: SessionAction) {
        if (!sessionActionSupported(session, state.value.capabilities, action)) {
            state.update { it.copy(error = "The connected Foreman server does not support this action.") }
            return
        }
        if (!sessionCanBeManaged(session.status)) {
            state.update {
                it.copy(error = "Interrupt the active session before changing its lifecycle.")
            }
            return
        }
        state.update {
            it.copy(
                pendingSessionAction =
                    PendingSessionAction(
                        session.id,
                        session.title,
                        action,
                        sessionProvider(session),
                        session.repositoryId,
                    ),
                error = null,
            )
        }
    }

    fun dismissSessionAction() {
        if (!state.value.submitting) {
            state.update { it.copy(pendingSessionAction = null) }
        }
    }

    fun confirmSessionAction() {
        val pending = state.value.pendingSessionAction ?: return
        if (state.value.submitting) return
        viewModelScope.launch {
            val current = state.value
            val pendingSession =
                current.selected?.takeIf { it.matches(pending.provider, pending.sessionId) }
                    ?: (current.archivedSessions + current.sessions).firstOrNull {
                        it.matches(pending.provider, pending.sessionId)
                    }
            if (
                current.pendingSessionAction != pending ||
                    !current.connected || pendingSession == null ||
                    !sessionActionSupported(
                        pendingSession,
                        current.capabilities,
                        pending.action,
                    )
            ) {
                state.update {
                    it.copy(
                        pendingSessionAction = null,
                        error = "Reconnect to a server that supports this action.",
                    )
                }
                return@launch
            }
            state.update { it.copy(submitting = true, error = null) }
            runCatching {
                val response = client.request(
                    if (pending.provider == PROVIDER_CLAUDE_CODE) {
                        "provider.session.delete"
                    } else if (pending.action == SessionAction.Archive) {
                        "session.archive"
                    } else if (pending.action == SessionAction.Restore) {
                        "session.restore"
                    } else {
                        "session.delete"
                    },
                    buildJsonObject {
                        put("sessionId", pending.sessionId)
                        if (pending.provider == PROVIDER_CLAUDE_CODE) {
                            put("provider", pending.provider)
                            put("repositoryId", pending.repositoryId ?: ".")
                        }
                        if (pending.action == SessionAction.Delete) put("confirm", true)
                    },
                )
                val restored = if (pending.action == SessionAction.Restore) {
                    json.decodeFromJsonElement<SessionSummary>(response.payload.getValue("session"))
                        .copy(provider = PROVIDER_CODEX, archived = false, readOnly = false)
                } else null
                if (pending.action != SessionAction.Restore) runCatching {
                    TurnMonitorService.cancel(getApplication(), pending.sessionId, pending.provider)
                }
                if (
                    pending.action != SessionAction.Restore &&
                    restorationSessionId == pending.sessionId &&
                    restorationProvider == pending.provider
                ) clearRememberedSession()
                state.update { current ->
                    val wasSelected = current.selected?.matches(pending.provider, pending.sessionId) == true
                    val key = providerSessionKey(pending.provider, pending.sessionId)
                    if (restored != null) {
                        val filters = current.searchFilters.copy(scope = SessionDiscoveryScope.Normal)
                        preferences.setSessionSearch(filters)
                        current.copy(
                            submitting = false,
                            pendingSessionAction = null,
                            archivedSessions = current.archivedSessions.filterNot { it.matches(pending.provider, pending.sessionId) },
                            sessions = listOf(restored) + current.sessions.filterNot { it.matches(pending.provider, pending.sessionId) },
                            selected = if (wasSelected) restored else current.selected,
                            searchFilters = filters,
                        )
                    } else if (pending.action == SessionAction.Archive) {
                        current.afterSessionArchived(pending.provider, pending.sessionId).copy(
                            submitting = false,
                            pendingSessionAction = null,
                        )
                    } else {
                        val pinned = if (pending.action == SessionAction.Delete) current.pinnedSessionIds - key else current.pinnedSessionIds
                        val hidden = if (pending.action == SessionAction.Delete) current.hiddenSessionIds - key else current.hiddenSessionIds
                        if (pending.action == SessionAction.Delete) {
                            preferences.setPinnedSessionIds(pinned)
                            preferences.setHiddenSessionIds(hidden)
                        }
                        current.copy(
                            submitting = false,
                            pendingSessionAction = null,
                            sessions = current.sessions.filterNot { it.matches(pending.provider, pending.sessionId) },
                            archivedSessions = if (pending.action == SessionAction.Delete) current.archivedSessions.filterNot { it.matches(pending.provider, pending.sessionId) } else current.archivedSessions,
                            selected = if (wasSelected) null else current.selected,
                            screen = if (wasSelected) Screen.Sessions else current.screen,
                            pinnedSessionIds = pinned,
                            hiddenSessionIds = hidden,
                        )
                    }
                }
            }.onFailure(::fail)
        }
    }

    fun startSession(
        repositoryId: String,
        model: String?,
        reasoningEffort: String?,
        accessLevel: String?,
    ) {
        if (state.value.submitting) return
        preferences.setLastProvider(PROVIDER_CODEX)
        preferences.setModelRoute(model, reasoningEffort)
        preferences.setAccessLevel(accessLevel)
        viewModelScope.launch {
            state.update { it.copy(submitting = true, showNewSession = false, error = null) }
            runCatching {
                val response = client.request(
                    "session.start",
                    buildJsonObject {
                        put("repositoryId", repositoryId)
                        model?.let { put("model", it) }
                        reasoningEffort?.let { put("reasoningEffort", it) }
                        accessLevel?.let { put("accessLevel", it) }
                    },
                )
                val created =
                    json.decodeFromJsonElement<SessionSummary>(
                        response.payload.getValue("session"),
                    )
                restorationProvider = PROVIDER_CODEX
                restorationSessionId = created.id
                preferences.setSelectedSession(PROVIDER_CODEX, created.id)
                state.update {
                    it.copy(
                        submitting = false,
                        loading = false,
                        selected = created,
                        sessions =
                            listOf(created) +
                                it.sessions.filterNot { session ->
                                    session.matches(PROVIDER_CODEX, created.id)
                                },
                        composerAccessLevel = created.accessLevel ?: accessLevel,
                        composerModel = created.model ?: model,
                        composerEffort = created.reasoningEffort ?: reasoningEffort,
                        newSessionAccessLevel = accessLevel,
                        newSessionModel = model,
                        newSessionEffort = reasoningEffort,
                        selectedNewSessionProvider = PROVIDER_CODEX,
                        screen = Screen.Detail,
                    )
                }
            }.onFailure(::fail)
        }
    }

    fun startProviderSession(
        provider: String,
        repositoryId: String,
        prompt: String,
        model: String?,
        reasoningEffort: String?,
        permissionOrAccess: String?,
    ) {
        if (provider == PROVIDER_CODEX) {
            startSession(repositoryId, model, reasoningEffort, permissionOrAccess)
            return
        }
        if (state.value.submitting || prompt.isBlank()) return
        val selectedModel = model ?: "sonnet"
        val permissionMode = permissionOrAccess ?: "default"
        preferences.setLastProvider(provider)
        preferences.setClaudeRoute(selectedModel, permissionMode)
        viewModelScope.launch {
            state.update { it.copy(submitting = true, showNewSession = false, error = null) }
            runCatching {
                val response = client.request(
                    "provider.session.start",
                    buildJsonObject {
                        put("provider", PROVIDER_CLAUDE_CODE)
                        put("repositoryId", repositoryId)
                        put("text", prompt.trim())
                        put("model", selectedModel)
                        put("permissionMode", permissionMode)
                    },
                )
                val created = json.decodeFromJsonElement<SessionSummary>(
                    response.payload.getValue("session"),
                )
                restorationProvider = PROVIDER_CLAUDE_CODE
                restorationSessionId = created.id
                preferences.setSelectedSession(PROVIDER_CLAUDE_CODE, created.id)
                state.update {
                    it.copy(
                        submitting = false,
                        loading = false,
                        selected = created,
                        sessions = listOf(created) + it.sessions.filterNot { session ->
                            session.matches(PROVIDER_CLAUDE_CODE, created.id)
                        },
                        selectedNewSessionProvider = PROVIDER_CLAUDE_CODE,
                        claudeComposerModel = created.model ?: selectedModel,
                        claudeComposerPermissionMode =
                            created.permissionMode ?: permissionMode,
                        newSessionClaudeModel = selectedModel,
                        newSessionClaudePermissionMode = permissionMode,
                        screen = Screen.Detail,
                    )
                }
                monitorIfActive(created)
            }.onFailure(::fail)
        }
    }

    fun send(text: String, images: List<ImagePayload>, accepted: () -> Unit) {
        val current = state.value
        val selected = current.selected ?: return
        if (selected.archived || selected.readOnly || current.submitting || (text.isBlank() && images.isEmpty())) return
        val provider = sessionProvider(selected)
        if (provider == PROVIDER_CLAUDE_CODE &&
            (images.isNotEmpty() || selected.status in setOf("working", "waiting"))
        ) return
        val steering = provider == PROVIDER_CODEX && selected.status == "working" && selected.activeTurnId != null
        val preparedMonitor = prepareMonitor(selected, active = steering)
        viewModelScope.launch {
            state.update { it.copy(submitting = true, error = null) }
            runCatching {
                val type = when {
                    provider == PROVIDER_CLAUDE_CODE -> providerPromptOperation(selected)
                    steering -> "turn.steer"
                    else -> "turn.prompt"
                }
                val response = client.request(
                    type,
                    if (provider == PROVIDER_CLAUDE_CODE) {
                        claudePromptPayload(
                            selected,
                            text,
                            current.claudeComposerModel,
                            current.claudeComposerPermissionMode,
                        )
                    } else {
                        turnPayload(
                            selected,
                            text,
                            images,
                            steering,
                            current.composerAccessLevel,
                            current.composerModel,
                            current.composerEffort,
                        )
                    },
                )
                val turnId = response.payload["turnId"]?.jsonPrimitive?.content
                val acceptedClaudeSession =
                    if (provider == PROVIDER_CLAUDE_CODE) {
                        response.payload["session"]?.let {
                            runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                        }
                    } else {
                        null
                    }
                var monitored: SessionSummary? = null
                state.update {
                    val currentSelected = it.selected
                    val authoritativeMessages = linkedMapOf<String, ConversationItem>()
                    acceptedClaudeSession?.messages.orEmpty().forEach { item ->
                        authoritativeMessages[item.id] = item
                    }
                    currentSelected?.messages.orEmpty().forEach { item ->
                        authoritativeMessages[item.id] = item
                    }
                    val base = acceptedClaudeSession ?: currentSelected
                    val updated =
                        base?.copy(
                            messages = authoritativeMessages.values.toList(),
                            status = "working",
                            activeTurnId = turnId ?: base.activeTurnId,
                            activityLabel = "Thinking",
                            activityText = "",
                            accessLevel =
                                if (provider == PROVIDER_CLAUDE_CODE) {
                                    base.accessLevel
                                } else if (steering) {
                                    base.accessLevel
                                } else {
                                    current.composerAccessLevel
                                },
                            model =
                                if (provider == PROVIDER_CLAUDE_CODE) current.claudeComposerModel
                                else if (steering) base.model else current.composerModel,
                            permissionMode =
                                if (provider == PROVIDER_CLAUDE_CODE) current.claudeComposerPermissionMode
                                else base.permissionMode,
                            source =
                                if (provider == PROVIDER_CLAUDE_CODE) "managed"
                                else base.source,
                            reasoningEffort =
                                if (steering) {
                                    base.reasoningEffort
                                } else {
                                    current.composerEffort
                                },
                        )
                    monitored = updated
                    it.copy(
                        submitting = false,
                        selected = updated,
                    )
                }
                monitored?.let(::monitorIfActive)
                accepted()
            }.onFailure {
                if (preparedMonitor && !steering) {
                    runCatching {
                        TurnMonitorService.cancel(
                            getApplication(),
                            selected.id,
                            sessionProvider(selected),
                        )
                    }
                }
                fail(it)
            }
        }
    }

    fun interrupt() {
        val selected = state.value.selected ?: return
        if (selected.archived || selected.readOnly) return
        if (!providerInterruptEligible(selected)) return
        val turnId = selected.activeTurnId ?: return
        if (state.value.submitting) return
        viewModelScope.launch {
            state.update { it.copy(submitting = true, error = null) }
            runCatching {
                client.request(
                    if (sessionProvider(selected) == PROVIDER_CLAUDE_CODE) {
                        "provider.turn.interrupt"
                    } else {
                        "turn.interrupt"
                    },
                    buildJsonObject {
                        if (sessionProvider(selected) == PROVIDER_CLAUDE_CODE) {
                            put("provider", PROVIDER_CLAUDE_CODE)
                        }
                        put("sessionId", selected.id)
                        if (sessionProvider(selected) == PROVIDER_CODEX) put("turnId", turnId)
                    },
                )
                state.update { it.copy(submitting = false) }
            }.onFailure(::fail)
        }
    }

    fun respondToApproval(approval: ApprovalRequest, decision: JsonObject) {
        val current = state.value
        if (current.selected?.let { it.matches(PROVIDER_CODEX, approval.sessionId) && (it.archived || it.readOnly) } == true) return
        if (!current.connected || approval.id in current.submittingApprovalIds || approval.status != "pending") return
        state.update {
            it.copy(
                submittingApprovalIds = it.submittingApprovalIds + approval.id,
                approvalErrors = it.approvalErrors - approval.id,
            )
        }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "approval.respond",
                    buildJsonObject {
                        put("approvalId", approval.id)
                        put("decision", decision)
                    },
                )
                state.update { currentState ->
                    currentState.copy(
                        approvals = currentState.approvals.map {
                            if (it.id == approval.id) it.copy(status = "submitting") else it
                        },
                    )
                }
            }.onFailure { failure ->
                val message = failure.message ?: "Approval response failed"
                state.update {
                    it.copy(
                        submittingApprovalIds = it.submittingApprovalIds - approval.id,
                        approvalErrors = it.approvalErrors +
                            (approval.id to if (message.contains("already resolved", true)) "Already resolved in another client." else message),
                    )
                }
            }
        }
    }

    private fun handleApprovalEvent(message: WireMessage): Boolean {
        if (message.type !in setOf("approval.requested", "approval.updated", "approval.resolved")) return false
        val raw = message.payload["approval"] ?: return true
        val approval = runCatching { json.decodeFromJsonElement<ApprovalRequest>(raw) }.getOrNull() ?: return true
        val terminal = approval.status == "resolved" || approval.status == "expired"
        val activityAt = activityTimestamp(message.payload)
        if (state.value.sessions.none { it.matches(PROVIDER_CODEX, approval.sessionId) }) discoverSession(approval.sessionId)
        state.update { current ->
            fun updateSession(session: SessionSummary): SessionSummary =
                if (!session.matches(PROVIDER_CODEX, approval.sessionId)) session else session.copy(
                    status = if (terminal && session.status == "waiting") "working" else "waiting",
                    attention = !terminal,
                    activeTurnId = approval.turnId ?: session.activeTurnId,
                    activityLabel = if (terminal) "Approval resolved" else approvalAttentionLabel(approval.type),
                    activityText = "",
                    lastActivity = activityAt ?: session.lastActivity,
                )
            current.copy(
                approvals =
                    if (current.approvals.any { it.id == approval.id }) {
                        current.approvals.map { if (it.id == approval.id) approval else it }
                    } else {
                        current.approvals + approval
                    },
                sessions = current.sessions.map(::updateSession),
                selected = current.selected?.let(::updateSession),
                submittingApprovalIds = if (terminal) current.submittingApprovalIds - approval.id else current.submittingApprovalIds,
                approvalErrors = if (terminal) current.approvalErrors - approval.id else current.approvalErrors,
            )
        }
        if (terminal) viewModelScope.launch {
            delay(5_000)
            state.update { it.copy(
                approvals = it.approvals.filterNot { item -> item.id == approval.id },
                focusedApprovalId = it.focusedApprovalId.takeUnless { id -> id == approval.id },
            ) }
        }
        updateActiveOverview()
        return true
    }

    fun respondToInput(input: InputRequest, response: JsonObject) {
        val current = state.value
        if (current.selected?.let { it.matches(PROVIDER_CODEX, input.sessionId) && (it.archived || it.readOnly) } == true) return
        if (!current.connected || input.id in current.submittingInputIds || input.status != "pending") return
        state.update {
            it.copy(
                submittingInputIds = it.submittingInputIds + input.id,
                inputErrors = it.inputErrors - input.id,
            )
        }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "input.respond",
                    buildJsonObject {
                        put("inputId", input.id)
                        put("response", response)
                    },
                )
                state.update { currentState ->
                    currentState.copy(
                        inputs = currentState.inputs.map {
                            if (it.id == input.id) it.copy(status = "submitting") else it
                        },
                    )
                }
            }.onFailure { failure ->
                val message = failure.message ?: "Input response failed"
                state.update {
                    it.copy(
                        submittingInputIds = it.submittingInputIds - input.id,
                        inputErrors = it.inputErrors +
                            (input.id to if (message.contains("already resolved", true)) "Already resolved in another client." else message),
                    )
                }
            }
        }
    }

    private fun handleInputEvent(message: WireMessage): Boolean {
        if (message.type !in setOf("input.requested", "input.updated", "input.resolved")) return false
        val raw = message.payload["input"] ?: return true
        val input = runCatching { json.decodeFromJsonElement<InputRequest>(raw) }.getOrNull() ?: return true
        val terminal = input.status == "resolved" || input.status == "expired"
        val activityAt = activityTimestamp(message.payload)
        if (state.value.sessions.none { it.matches(PROVIDER_CODEX, input.sessionId) }) discoverSession(input.sessionId)
        state.update { current ->
            fun updateSession(session: SessionSummary): SessionSummary =
                if (!session.matches(PROVIDER_CODEX, input.sessionId)) session else session.copy(
                    status = if (terminal && session.status == "waiting") "working" else "waiting",
                    attention = !terminal,
                    activeTurnId = input.turnId ?: session.activeTurnId,
                    activityLabel = if (terminal) "Input request resolved" else inputAttentionLabel(input),
                    activityText = "",
                    lastActivity = activityAt ?: session.lastActivity,
                )
            current.copy(
                inputs = if (current.inputs.any { it.id == input.id }) {
                    current.inputs.map { if (it.id == input.id) input else it }
                } else current.inputs + input,
                sessions = current.sessions.map(::updateSession),
                selected = current.selected?.let(::updateSession),
                submittingInputIds = if (terminal) current.submittingInputIds - input.id else current.submittingInputIds,
                inputErrors = if (terminal) current.inputErrors - input.id else current.inputErrors,
            )
        }
        if (terminal) viewModelScope.launch {
            delay(5_000)
            state.update { it.copy(inputs = it.inputs.filterNot { item -> item.id == input.id }) }
        }
        updateActiveOverview()
        return true
    }

    private fun handleEvent(message: WireMessage) {
        if (handleApprovalEvent(message)) return
        if (handleInputEvent(message)) return
        if (message.type == "service.event") {
            val serverVersion = message.payload["foremanVersion"]?.jsonPrimitive?.content
            val releaseBuild = message.payload["foremanReleaseBuild"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            val releaseUpdates = decodeReleaseUpdates(json, message.payload["releaseUpdates"])
            val updateOperation = decodeServerUpdateOperation(json, message.payload["serverUpdateOperation"])
            if (releaseUpdates != null) {
                preferences.setReleaseUpdateInfo(
                    CachedReleaseUpdateInfo(serverVersion, releaseBuild, releaseUpdates),
                )
            }
            updateOperation?.let { preferences.setServerUpdateOperationId(it.id) }
            state.update {
                it.copy(
                    foremanVersion = serverVersion ?: it.foremanVersion,
                    foremanReleaseBuild = releaseBuild ?: it.foremanReleaseBuild,
                    releaseUpdates = releaseUpdates ?: it.releaseUpdates,
                    serverUpdateOperation = updateOperation ?: it.serverUpdateOperation,
                    releaseCheckLoading = false,
                )
            }
            return
        }
        if (message.type == "update.event") {
            val operation = decodeServerUpdateOperation(json, message.payload["operation"]) ?: return
            preferences.setServerUpdateOperationId(operation.id)
            state.update {
                it.copy(serverUpdateOperation = operation, serverUpdateLoading = false, serverUpdateError = null)
            }
            if (operation.phase in terminalServerUpdatePhases) updateRequested = false
            return
        }
        if (message.type == "usage.event") {
            val usage = runCatching {
                json.decodeFromJsonElement<AccountUsage>(message.payload)
            }.getOrNull() ?: return
            state.update { it.copy(accountUsage = usage) }
            return
        }
        if (message.type == "provider.event") {
            val providers = message.payload["providers"]?.jsonArray?.mapNotNull {
                runCatching { json.decodeFromJsonElement<ProviderInfo>(it) }.getOrNull()
            } ?: return
            providerCatalogRevision += 1
            val claudeAvailable =
                providers.any { it.id == PROVIDER_CLAUDE_CODE && it.enabled && it.available }
            val enabledProviders = providers.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
            val openableProviders = providers.filter { it.enabled && it.available }.mapTo(mutableSetOf()) { it.id }
            val previousProviders = state.value.providers.associateBy { it.id }
            val invalidatedProviders = providers.filter { provider ->
                provider.enabled && (
                    !provider.available ||
                        previousProviders[provider.id]?.available != provider.available
                )
            }.mapTo(mutableSetOf()) { it.id }
            nonAuthoritativeSessionProviders =
                (nonAuthoritativeSessionProviders + invalidatedProviders).intersect(enabledProviders)
            if (restorationSessionId != null && restorationProvider !in enabledProviders) {
                clearRememberedSession()
            }
            state.update { current ->
                fun availability(session: SessionSummary): SessionSummary =
                    if (sessionProvider(session) == PROVIDER_CLAUDE_CODE && !claudeAvailable &&
                        session.status !in setOf("working", "waiting")
                    ) session.copy(status = "unavailable") else session
                val selected = current.selected?.takeIf {
                    sessionProvider(it) in openableProviders
                }?.let(::availability)
                current.copy(
                    providers = providers,
                    providerCatalogLoaded = true,
                    sessions = current.sessions.filter {
                        sessionProvider(it) in openableProviders
                    }.map(::availability),
                    selected = selected,
                    screen = if (current.selected != null && selected == null) Screen.Sessions else current.screen,
                )
            }
            val current = state.value
            viewModelScope.launch {
                runCatching {
                    synchronizeSessions(
                        current.selected?.id,
                        current.selected?.let(::sessionProvider) ?: restorationProvider,
                    )
                }.onFailure(::fail)
            }
            return
        }
        if (message.type != "session.event") return
        val provider =
            message.payload["provider"]?.jsonPrimitive?.content ?: PROVIDER_CODEX
        if (!supportedProvider(provider)) return
        val sessionId = message.payload["sessionId"]?.jsonPrimitive?.content ?: return
        val identityKey = providerSessionKey(provider, sessionId)
        val event = message.eventObject()
        val kind = event["kind"]?.jsonPrimitive?.content ?: return
        val activityAt = activityTimestamp(event)
        if (kind == "lifecycle") {
            val action = event["action"]?.jsonPrimitive?.content
            if (action == "archived") {
                val wasRemembered = archivedSessionMatchesRemembered(
                    provider,
                    sessionId,
                    restorationProvider,
                    restorationSessionId,
                )
                if (wasRemembered) clearRememberedSession()
                val wasSelected = state.value.selected?.matches(provider, sessionId) == true
                if (wasSelected) sessionOpenGeneration += 1
                state.update { current ->
                    current.afterSessionArchived(provider, sessionId)
                }
                if (wasSelected) synchronizeSessionPresence()
                refreshArchivedSessions()
                updateActiveOverview()
                return
            }
            if (action == "restored") {
                val projected = event["session"]?.let {
                    runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
                }?.copy(provider = provider, archived = false, readOnly = false)
                state.update { current ->
                    val wasSelected = current.selected?.matches(provider, sessionId) == true
                    val filters = if (wasSelected) {
                        current.searchFilters.copy(scope = SessionDiscoveryScope.Normal)
                    } else current.searchFilters
                    if (wasSelected) preferences.setSessionSearch(filters)
                    current.copy(
                        archivedSessions = current.archivedSessions.filterNot { it.matches(provider, sessionId) },
                        sessions = if (projected == null) current.sessions else listOf(projected) + current.sessions.filterNot { it.matches(provider, sessionId) },
                        selected = if (projected != null && wasSelected) projected else current.selected,
                        searchFilters = filters,
                    )
                }
                scheduleSearch(0)
                if (projected == null) {
                    viewModelScope.launch {
                        runCatching { synchronizeSessions(sessionId, provider) }.onFailure(::fail)
                    }
                }
                updateActiveOverview()
                return
            }
            if (action == "removed") {
                val wasRemembered = restorationSessionId == sessionId && restorationProvider == provider
                if (wasRemembered) clearRememberedSession()
                state.update { current ->
                    val wasSelected = current.selected?.matches(provider, sessionId) == true
                    val pinned = current.pinnedSessionIds - identityKey
                    val hidden = current.hiddenSessionIds - identityKey
                    preferences.setPinnedSessionIds(pinned)
                    preferences.setHiddenSessionIds(hidden)
                    current.copy(
                        sessions = current.sessions.filterNot { it.matches(provider, sessionId) },
                        archivedSessions = current.archivedSessions.filterNot { it.matches(provider, sessionId) },
                        searchResults = current.searchResults.filterNot { it.session.matches(provider, sessionId) },
                        selected = if (wasSelected) null else current.selected,
                        screen = if (wasSelected) Screen.Sessions else current.screen,
                        pinnedSessionIds = pinned,
                        hiddenSessionIds = hidden,
                    )
                }
                updateActiveOverview()
                return
            }
            val projected = event["session"]?.let {
                runCatching { json.decodeFromJsonElement<SessionSummary>(it) }.getOrNull()
            }
            if (projected != null) {
                state.update { current ->
                    current.copy(
                        sessions = listOf(projected) + current.sessions.filterNot {
                            it.matches(sessionProvider(projected), projected.id)
                        },
                    )
                }
                scheduleSearch(0)
                updateActiveOverview()
                return
            }
        }
        if (state.value.shouldDiscoverSession(sessionId, kind, provider)) {
            discoverSession(sessionId, provider)
        }
        state.update { current ->
            val selected = current.selected
            if (selected?.matches(provider, sessionId) != true) {
                val usage = if (kind == "usage") event["tokenUsage"]?.let {
                    runCatching { json.decodeFromJsonElement<ThreadTokenUsage>(it) }.getOrNull()
                } else null
                val inferredStatus =
                    if (eventShowsWorkingActivity(kind)) {
                        "working"
                    } else if (kind == "status") {
                        event["status"]?.jsonPrimitive?.content
                    } else {
                        null
                    }
                return@update current.copy(
                    sessions =
                        current.sessions.map {
                            if (it.matches(provider, sessionId) && kind == "route") {
                                val revision = event["settingsRevision"]?.jsonPrimitive?.content?.toLongOrNull()
                                if (revision != null && (it.settingsRevision ?: 0L) > revision) it else it.copy(
                                    accessLevel = event["accessLevel"]?.jsonPrimitive?.content ?: it.accessLevel,
                                    permissionMode = event["permissionMode"]?.jsonPrimitive?.content ?: it.permissionMode,
                                    model = event["model"]?.jsonPrimitive?.content ?: it.model,
                                    reasoningEffort = event["reasoningEffort"]?.jsonPrimitive?.content ?: it.reasoningEffort,
                                    settingsRevision = revision ?: it.settingsRevision,
                                )
                            } else if (it.matches(provider, sessionId) && usage != null) {
                                it.copy(tokenUsage = usage)
                            } else if (it.matches(provider, sessionId) && inferredStatus != null) {
                                val terminal = inferredStatus in setOf("completed", "failed", "interrupted")
                                it.copy(
                                    status = inferredStatus,
                                    attention = inferredStatus == "waiting",
                                    lastActivity = activityAt ?: it.lastActivity,
                                    terminalAt =
                                        if (terminal) {
                                            event["completedAt"]?.jsonPrimitive?.content?.toLongOrNull()
                                                ?: activityAt ?: it.terminalAt
                                        } else if (inferredStatus in setOf("working", "waiting")) {
                                            null
                                        } else {
                                            it.terminalAt
                                        },
                                    statusChangedAt =
                                        if (inferredStatus != it.status) activityAt ?: it.statusChangedAt
                                        else it.statusChangedAt,
                                )
                            } else {
                                it
                            }
                        },
                )
            }
            when (kind) {
                "assistant.delta" -> {
                    val itemId = event["itemId"]?.jsonPrimitive?.content ?: "streaming"
                    val delta = event["text"]?.jsonPrimitive?.content.orEmpty()
                    val existing = selected.messages.indexOfFirst { it.id == itemId }
                    val messages = selected.messages.toMutableList()
                    if (existing >= 0) {
                        messages[existing] =
                            messages[existing].copy(text = messages[existing].text + delta)
                    } else {
                        messages +=
                            ConversationItem(
                                id = itemId,
                                kind = "assistant",
                                text = delta,
                                turnId = event["turnId"]?.jsonPrimitive?.content,
                            )
                    }
                    current.copy(
                        selected =
                            selected.copy(
                                messages = messages,
                                status = "working",
                                attention = false,
                                activeTurnId =
                                    event["turnId"]?.jsonPrimitive?.content
                                        ?: selected.activeTurnId,
                                activityLabel = "Responding",
                                activityText = "",
                                lastActivity = activityAt ?: selected.lastActivity,
                            ),
                    )
                }
                "item" -> {
                    val raw = event["item"]
                    if (raw == null || raw is JsonNull) return@update current
                    val decodedItem = runCatching {
                        json.decodeFromJsonElement<ConversationItem>(raw)
                    }.getOrNull() ?: return@update current
                    val item =
                        if (decodedItem.turnId == null) {
                            decodedItem.copy(
                                turnId = event["turnId"]?.jsonPrimitive?.content,
                            )
                        } else {
                            decodedItem
                        }
                    val messages = selected.messages.toMutableList()
                    val existing = messages.indexOfFirst { it.id == item.id }
                    if (existing >= 0) messages[existing] = item else messages += item
                    val phase = event["phase"]?.jsonPrimitive?.content
                    val nextLabel =
                        if (phase == "started") {
                            liveActivityLabel(
                                selected.copy(
                                    messages = messages,
                                    activityLabel = "",
                                ),
                            )
                        } else {
                            "Thinking"
                        }
                    val nextActivityText =
                        if (phase == "started" && item.description.isNotBlank()) {
                            item.description
                        } else {
                            ""
                        }
                    current.copy(
                        selected =
                            selected.copy(
                                messages = messages,
                                status = "working",
                                attention = false,
                                activeTurnId =
                                    event["turnId"]?.jsonPrimitive?.content
                                        ?: selected.activeTurnId,
                                activityLabel = nextLabel,
                                activityText = nextActivityText,
                                lastActivity = activityAt ?: selected.lastActivity,
                            ),
                    )
                }
                "activity" -> {
                    val label =
                        event["label"]?.jsonPrimitive?.content
                            ?: return@update current
                    val text = event["text"]?.jsonPrimitive?.content.orEmpty()
                    val append = event["append"]?.jsonPrimitive?.content == "true"
                    val activityText =
                        if (append) {
                            (selected.activityText + text).takeLast(2_000)
                        } else {
                            text
                        }
                    current.copy(
                        selected =
                            selected.copy(
                                status = "working",
                                attention = false,
                                activeTurnId =
                                    event["turnId"]?.jsonPrimitive?.content
                                        ?: selected.activeTurnId,
                                activityLabel = label,
                                activityText = activityText,
                                lastActivity = activityAt ?: selected.lastActivity,
                            ),
                    )
                }
                "status" -> {
                    val newStatus = event["status"]?.jsonPrimitive?.content ?: selected.status
                    val active =
                        if (newStatus == "working") {
                            event["turnId"]?.jsonPrimitive?.content ?: selected.activeTurnId
                        } else {
                            null
                        }
                    val terminal = newStatus in setOf("completed", "failed", "interrupted")
                    current.copy(
                        selected =
                            selected.copy(
                                status = newStatus,
                                attention = newStatus == "waiting",
                                activeTurnId = active,
                                activeTurnStartedAt =
                                    if (newStatus == "working") {
                                        event["startedAt"]?.jsonPrimitive?.content?.toLongOrNull()
                                            ?: selected.activeTurnStartedAt
                                    } else {
                                        null
                                    },
                                lastActivity = activityAt ?: selected.lastActivity,
                                terminalAt =
                                    if (terminal) {
                                        event["completedAt"]?.jsonPrimitive?.content?.toLongOrNull()
                                            ?: activityAt ?: selected.terminalAt
                                    } else if (newStatus in setOf("working", "waiting")) {
                                        null
                                    } else {
                                        selected.terminalAt
                                    },
                                statusChangedAt =
                                    if (newStatus != selected.status) activityAt ?: selected.statusChangedAt
                                    else selected.statusChangedAt,
                                waitType =
                                    if (newStatus == "waiting") {
                                        event["waitType"]?.jsonPrimitive?.content
                                    } else {
                                        null
                                    },
                                activityLabel =
                                    if (newStatus == "working") {
                                        selected.activityLabel.ifBlank { "Thinking" }
                                    } else {
                                        ""
                                    },
                                activityText =
                                    if (newStatus == "working") selected.activityText else "",
                            ),
                    )
                }
                "route" -> {
                    val settingsRevision =
                        event["settingsRevision"]?.jsonPrimitive?.content?.toLongOrNull()
                    if (
                        settingsRevision != null &&
                        (selected.settingsRevision ?: 0L) > settingsRevision
                    ) return@update current
                    val accessLevel =
                        event["accessLevel"]?.jsonPrimitive?.content ?: selected.accessLevel
                    val permissionMode =
                        event["permissionMode"]?.jsonPrimitive?.content ?: selected.permissionMode
                    val model = event["model"]?.jsonPrimitive?.content ?: selected.model
                    val reasoningEffort =
                        event["reasoningEffort"]?.jsonPrimitive?.content
                            ?: selected.reasoningEffort
                    val updated =
                        selected.copy(
                            accessLevel = accessLevel,
                            permissionMode = permissionMode,
                            model = model,
                            reasoningEffort = reasoningEffort,
                            settingsRevision = settingsRevision ?: selected.settingsRevision,
                        )
                    current.copy(
                        selected = updated,
                        sessions = current.sessions.map {
                            if (it.matches(provider, sessionId)) {
                                it.copy(
                                    accessLevel = updated.accessLevel,
                                    permissionMode = updated.permissionMode,
                                    model = updated.model,
                                    reasoningEffort = updated.reasoningEffort,
                                    settingsRevision = updated.settingsRevision,
                                )
                            } else it
                        },
                        composerAccessLevel = if (provider == PROVIDER_CODEX) accessLevel else current.composerAccessLevel,
                        composerModel = if (provider == PROVIDER_CODEX) model else current.composerModel,
                        composerEffort = if (provider == PROVIDER_CODEX) reasoningEffort else current.composerEffort,
                        claudeComposerModel = if (provider == PROVIDER_CLAUDE_CODE) model.orEmpty() else current.claudeComposerModel,
                        claudeComposerPermissionMode = if (provider == PROVIDER_CLAUDE_CODE) permissionMode.orEmpty() else current.claudeComposerPermissionMode,
                    )
                }
                "usage" -> {
                    val usage = event["tokenUsage"]?.let {
                        runCatching { json.decodeFromJsonElement<ThreadTokenUsage>(it) }.getOrNull()
                    } ?: return@update current
                    current.copy(
                        selected = selected.copy(tokenUsage = usage),
                        sessions = current.sessions.map {
                            if (it.matches(provider, sessionId)) it.copy(tokenUsage = usage) else it
                        },
                    )
                }
                else -> current
            }
        }
        if (kind == "status" && state.value.searchFilters.query.isNotBlank()) {
            scheduleSearch(0)
        }
        updateActiveOverview()
    }

    private fun fail(error: Throwable) {
        state.update {
            it.copy(
                loading = false,
                submitting = false,
                error = error.message ?: "Something went wrong",
            )
        }
    }

    private fun monitorIfActive(session: SessionSummary) {
        if (session.status == "working") prepareMonitor(session, active = true)
    }

    private fun startGlobalTurnMonitoring() {
        val current = state.value
        if (!current.monitorActiveTurns) return
        val hostId = current.activeHostId ?: return
        runCatching { TurnMonitorService.monitorAll(getApplication(), hostId) }
            .onFailure { error ->
                state.update {
                    it.copy(error = error.message ?: "Android could not start background monitoring.")
                }
            }
    }

    private fun prepareMonitor(session: SessionSummary, active: Boolean): Boolean {
        if (!state.value.monitorActiveTurns) return false
        return runCatching {
            val current = state.value
            val hostId = requireNotNull(current.activeHostId)
            val repositoryId = sessionRepositoryIdentity(
                session.repository,
                current.repositories,
                current.repositoryRoot,
            ).id
            TurnMonitorService.monitor(
                getApplication(),
                hostId,
                session.id,
                active,
                repositoryId,
                session.activeTurnId,
                session.activeTurnStartedAt,
                sessionProvider(session),
            )
        }.onFailure { error ->
            state.update {
                it.copy(error = error.message ?: "Android could not start background monitoring.")
            }
        }.isSuccess
    }

    override fun onCleared() {
        overviewJob?.cancel()
        overviewClient.close()
        client.close()
    }
}

private data class AndroidAppUpdateActions(
    val state: ApkUpdateUiState,
    val start: (ForemanRelease) -> Unit,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val install: () -> Unit,
    val requestPermission: () -> Unit,
    val dismissPermission: () -> Unit,
)

private val LocalAndroidAppUpdate = staticCompositionLocalOf<AndroidAppUpdateActions> {
    error("Android app update actions were not provided")
}

@Composable
private fun ForemanApp(
    viewModel: ForemanViewModel = viewModel(),
    requestTurnMonitoring: (Boolean) -> Unit = viewModel::setMonitorActiveTurns,
    androidAppUpdateViewModel: AndroidAppUpdateViewModel = viewModel(),
    requestAndroidAppInstall: () -> Unit = {},
    requestUnknownAppsPermission: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val androidAppUpdateState by androidAppUpdateViewModel.state.collectAsState()
    LaunchedEffect(
        state.screen,
        state.selected?.id,
        state.selected?.let(::sessionProvider),
        state.connected,
    ) {
        viewModel.onVisibleSessionChanged()
    }
    val systemDark = isSystemInDarkTheme()
    val darkTheme =
        when (state.themeMode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(
        LocalAndroidAppUpdate provides
            AndroidAppUpdateActions(
                state = androidAppUpdateState,
                start = androidAppUpdateViewModel::start,
                retry = androidAppUpdateViewModel::retry,
                cancel = androidAppUpdateViewModel::cancel,
                install = requestAndroidAppInstall,
                requestPermission = requestUnknownAppsPermission,
                dismissPermission = {
                    androidAppUpdateViewModel.permissionResult(false) {}
                },
            ),
    ) {
    ForemanTheme(themeId = state.themeId, darkTheme = darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state.screen) {
                Screen.Setup -> SetupScreen(state, viewModel, requestTurnMonitoring)
                Screen.Overview -> UnifiedOverviewScreen(state, viewModel, requestTurnMonitoring)
                Screen.Dashboard -> HostDashboardScreen(state, viewModel, requestTurnMonitoring)
                Screen.Sessions -> SessionsScreen(state, viewModel, requestTurnMonitoring)
                Screen.Detail -> SessionDetailScreen(state, viewModel, requestTurnMonitoring)
                Screen.Diagnostics -> DiagnosticsScreen(state, viewModel)
            }
            state.pendingSessionAction?.let { pending ->
                SessionActionDialog(
                    pending = pending,
                    busy = state.submitting,
                    onConfirm = viewModel::confirmSessionAction,
                    onDismiss = viewModel::dismissSessionAction,
                )
            }
            state.openingWorkspaceFile?.let { path ->
                WorkspaceFileLoadingDialog(path = path, onDismiss = viewModel::closeWorkspaceFile)
            }
            state.workspaceFile?.let { file ->
                WorkspaceFileDialog(
                    file = file,
                    onOpenWorkspaceFile = viewModel::openWorkspaceFile,
                    onDismiss = viewModel::closeWorkspaceFile,
                )
            }
        }
    }
    }
}

@Composable
private fun WorkspaceFileLoadingDialog(path: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Opening document") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(path, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun WorkspaceFileDialog(
    file: WorkspaceFile,
    onOpenWorkspaceFile: (WorkspaceFileTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val markdown = file.path.endsWith(".md", ignoreCase = true) || file.path.endsWith(".markdown", ignoreCase = true)
    var preview by remember(file.path, file.line) { mutableStateOf(markdown && file.line == null) }
    val sourceState = rememberLazyListState()
    LaunchedEffect(file.path, file.line, preview) {
        if (!preview && file.line != null) sourceState.scrollToItem((file.line - 1).coerceAtLeast(0))
    }
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        file.path + (file.line?.let { ":$it" } ?: ""),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    if (markdown) {
                        TextButton(onClick = { preview = true }, enabled = !preview) { Text("Preview") }
                        TextButton(onClick = { preview = false }, enabled = preview) { Text("Source") }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close document") }
                }
                HorizontalDivider()
                if (preview) {
                    SelectionContainer {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        ) {
                            MarkdownText(
                                text = file.content,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onOpenWorkspaceFile = onOpenWorkspaceFile,
                            )
                        }
                    }
                } else {
                    val lines = remember(file.path, file.content) { file.content.split('\n') }
                    SelectionContainer {
                        LazyColumn(state = sourceState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(lines) { index, line ->
                                val number = index + 1
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .background(
                                                if (number == file.line) MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent,
                                            ).padding(horizontal = 12.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        number.toString(),
                                        modifier = Modifier.width(44.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        line,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(state: UiState, viewModel: ForemanViewModel) {
    val context = LocalContext.current
    var confirmRestart by remember { mutableStateOf(false) }
    val restartBlocked = restartBlocked(state)
    BackHandler(onBack = viewModel::closeDiagnostics)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeDiagnostics) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refreshDiagnostics,
                        enabled = state.connected && !state.diagnosticsLoading,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh diagnostics")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Sanitized, in-memory host events only. Prompts, commands, tokens, paths, approvals, logs, and traces are excluded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CopyFeedbackButton(
                        onCopy = {
                            val clipboard =
                                context.getSystemService(ClipboardManager::class.java)
                                    ?: error("Clipboard access is unavailable")
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "Foreman diagnostics",
                                    diagnosticsText(state.diagnostics),
                                ),
                            )
                        },
                        enabled = state.diagnostics.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = { confirmRestart = true },
                        enabled =
                            state.connected && "remoteRestart" in state.capabilities &&
                                !restartBlocked &&
                                state.restartPhase !in setOf(
                                    RestartPhase.Scheduling,
                                    RestartPhase.Scheduled,
                                    RestartPhase.Reconnecting,
                                ),
                        modifier = Modifier.weight(1f),
                    ) { Text("Restart Foreman") }
                }
            }
            if ("remoteRestart" !in state.capabilities) {
                item {
                    Text(
                        "Remote restart is disabled on this host.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if ("remoteRestart" in state.capabilities && restartBlocked) {
                item {
                    Text(
                        "Restart is unavailable while sessions are active or waiting for attention.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.restartPhase != RestartPhase.Idle) {
                item {
                    Text(
                        restartProgressLabel(state.restartPhase),
                        color =
                            when (state.restartPhase) {
                                RestartPhase.Succeeded -> LocalForemanColors.current.success
                                RestartPhase.Failed, RestartPhase.TimedOut -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            state.diagnosticsError?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            if (state.diagnosticsLoading) {
                item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            } else if (state.diagnostics.isEmpty() && state.diagnosticsError == null) {
                item { Text("No diagnostic events are available.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.diagnostics) { event ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(event.category, fontWeight = FontWeight.Bold)
                            Text(
                                event.severity.uppercase(),
                                color = if (event.severity == "info") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(event.message)
                        event.requestCategory?.let {
                            Text("Request category: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            event.timestamp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart Foreman?") },
            text = {
                Text(
                    "This briefly disconnects clients, which will reconnect automatically. Desktop Codex is not restarted or stopped.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestart = false
                        viewModel.restartService()
                    },
                    enabled = !restartBlocked,
                ) { Text("Restart") }
            },
        )
    }
}

internal fun restartBlocked(state: UiState): Boolean =
    state.sessions.any { it.status == "working" || it.status == "waiting" } ||
        state.approvals.any { it.status == "pending" || it.status == "submitting" } ||
        state.inputs.any { it.status == "pending" || it.status == "submitting" }

@Composable
private fun SetupScreen(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        UiSettingsMenu(
            state = state,
            viewModel = viewModel,
            requestTurnMonitoring = requestTurnMonitoring,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        if (state.addingHost) {
            TextButton(
                onClick = viewModel::cancelAddHost,
                modifier = Modifier.align(Alignment.TopStart),
            ) { Text("Cancel") }
        }
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, top = 56.dp, end = 28.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.foreman_logo),
                    contentDescription = "Foreman logo",
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Foreman",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "MONITOR. ORCHESTRATE. COMMAND.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                "Connect to Codex on your Linux host.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::setHost,
                label = { Text("Host") },
                placeholder = { Text("192.168.1.59:8765") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text("Host display name") },
                placeholder = { Text("Home server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.pairingKey,
                onValueChange = viewModel::setPairingKey,
                label = { Text("Pairing code") },
                placeholder = { Text("6-digit code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.deviceName,
                onValueChange = viewModel::setDeviceName,
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(state.error)
            Button(
                onClick = viewModel::connect,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text("Connect")
                }
            }
        }
    }
}

private fun overviewAge(timestamp: Long?, now: Long = System.currentTimeMillis()): String {
    if (timestamp == null) return "never"
    val elapsed = (now - timestamp).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> "just now"
        elapsed < 3_600_000 -> "${elapsed / 60_000}m ago"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000}h ago"
        else -> "${elapsed / 86_400_000}d ago"
    }
}

private fun overviewElapsed(timestamp: Long?, now: Long = System.currentTimeMillis()): String {
    if (timestamp == null) return "—"
    val elapsed = (now - timestamp).coerceAtLeast(0)
    val hours = elapsed / 3_600_000
    val minutes = (elapsed % 3_600_000) / 60_000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedOverviewScreen(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
) {
    val totals = aggregateHostOverviews(state.savedHosts.map { it.id }, state.overviewSnapshots)
    val attention = state.savedHosts.flatMap { host ->
        state.overviewSnapshots[host.id]?.attention.orEmpty().map { host to it }
    }.sortedBy { it.second.startedAt ?: Long.MAX_VALUE }
    var renameHost by remember { mutableStateOf<SavedHostSummary?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var forgetHost by remember { mutableStateOf<SavedHostSummary?>(null) }
    BackHandler(
        enabled = viewModel.hasOverviewReturnTarget(),
        onBack = viewModel::backFromOverview,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unified overview", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Image(
                        painter = painterResource(R.drawable.foreman_logo),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp).size(36.dp).clip(RoundedCornerShape(9.dp)),
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::refreshOverview) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh all hosts")
                    }
                    UiSettingsMenu(state, viewModel, requestTurnMonitoring)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("ALL SAVED HOSTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Status is aggregated on this device; hosts remain independently paired.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (totals.staleHosts > 0) Text("Aggregate counts include ${totals.staleHosts} stale host snapshot${if (totals.staleHosts == 1) "" else "s"}.", color = LocalForemanColors.current.warning, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewMetric("Hosts online", "${totals.connectedHosts}/${totals.hosts}", Modifier.weight(1f))
                        OverviewMetric("Active", totals.active.toString(), Modifier.weight(1f))
                        OverviewMetric("Waiting", totals.waiting.toString(), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewMetric("Failed", totals.failed.toString(), Modifier.weight(1f))
                        OverviewMetric("Longest", overviewElapsed(totals.oldestTurn?.timestamp), Modifier.weight(1f))
                        OverviewMetric("Latest", overviewAge(totals.latestCompletion?.timestamp), Modifier.weight(1f))
                    }
                }
            }
            item { Text("Saved hosts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(state.savedHosts, key = { it.id }) { host ->
                val snapshot = state.overviewSnapshots[host.id]
                HostOverviewCard(
                    host,
                    snapshot,
                    onOpenDashboard = { viewModel.openOverviewHost(host.id) },
                    onOpenSessions = { viewModel.openOverviewSessions(host.id) },
                    onReconnect = { viewModel.reconnectOverviewHost(host.id) },
                    onRename = { renameHost = host; renameValue = host.displayName },
                    onForget = { forgetHost = host },
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Needs attention", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(attention.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (attention.isEmpty()) {
                item { Text("Nothing in the latest host snapshots needs attention.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(attention, key = { (host, item) -> "${host.id}:${item.provider}:${item.sessionId}:${item.approvalId ?: item.type}" }) { (host, item) ->
                    val stale = state.overviewSnapshots[host.id]?.connection != "connected"
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.type.uppercase(), color = if (item.type == "failed") LocalForemanColors.current.failure else LocalForemanColors.current.attention, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(overviewAge(item.startedAt) + if (stale) " · STALE" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(item.sessionTitle, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                ProviderBadge(item.provider)
                            }
                            Text("${host.displayName} · ${item.repository.substringAfterLast('/').ifBlank { "Workspace" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { viewModel.openOverviewSession(item) }, modifier = Modifier.align(Alignment.End)) { Text("Open") }
                        }
                    }
                }
            }
            item { Text("Android uses one active-host connection plus one sequential foreground health probe. Inactive snapshots are always marked stale; probes stop in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    renameHost?.let { host ->
        AlertDialog(
            onDismissRequest = { renameHost = null },
            title = { Text("Rename ${host.displayName}") },
            text = { OutlinedTextField(renameValue, { renameValue = it }, label = { Text("Display name") }, singleLine = true) },
            dismissButton = { TextButton(onClick = { renameHost = null }) { Text("Cancel") } },
            confirmButton = { TextButton(enabled = renameValue.isNotBlank(), onClick = { viewModel.renameHost(host.id, renameValue); renameHost = null }) { Text("Save") } },
        )
    }
    forgetHost?.let { host ->
        AlertDialog(
            onDismissRequest = { forgetHost = null },
            title = { Text("Forget ${host.displayName}?") },
            text = { Text("This removes this host and its encrypted token from this device.") },
            dismissButton = { TextButton(onClick = { forgetHost = null }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = { viewModel.forgetHost(host.id); forgetHost = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Forget") } },
        )
    }
}

@Composable
private fun OverviewMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostDashboardScreen(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
) {
    val pendingApprovals = state.approvals.filter { it.status == "pending" || it.status == "submitting" }
    val pendingInputs = state.inputs.filter { it.status == "pending" || it.status == "submitting" }
    val showProviderIdentity = shouldShowProviderIdentity(state.providers, state.providerCatalogLoaded)
    val dashboard =
        projectAndroidDashboard(
            state.sessions,
            requestSessionIds =
                (pendingApprovals.map { it.sessionId } + pendingInputs.map { it.sessionId }).toSet(),
        )

    BackHandler(onBack = viewModel::showOverview)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Dashboard", fontWeight = FontWeight.Bold)
                        Text(
                            state.displayName.ifBlank { "Foreman host" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::openOverview) {
                        Icon(Icons.Default.Home, contentDescription = "All hosts")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::enterSessions) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sessions")
                    }
                    IconButton(onClick = viewModel::refresh, enabled = state.connected && !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh dashboard")
                    }
                    UiSettingsMenu(state, viewModel, requestTurnMonitoring)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.connected) {
                ConnectionBanner(state.error, viewModel::reconnect)
            } else {
                ErrorText(state.error, Modifier.padding(horizontal = 16.dp))
            }
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { DashboardHealthCard(state) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OverviewMetric("Active", dashboard.active.size.toString(), Modifier.weight(1f))
                                OverviewMetric("Waiting", dashboard.waitingCount.toString(), Modifier.weight(1f))
                            }
                            val providerActivity = if (showProviderIdentity) state.providers.filter { it.enabled }.joinToString(" · ") { provider ->
                                val label = provider.displayName.removeSuffix(" Code")
                                "$label ${dashboard.active.count { sessionProvider(it) == provider.id }}"
                            } else ""
                            if (providerActivity.isNotBlank()) {
                                Text(
                                    "$providerActivity active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OverviewMetric("Failed", dashboard.failedCount.toString(), Modifier.weight(1f))
                                OverviewMetric("Recent", dashboard.recent.size.toString(), Modifier.weight(1f))
                            }
                        }
                    }
                    dashboard.oldestTurn?.let { oldest ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text("OLDEST ACTIVE TURN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text(sessionDisplayTitle(oldest), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${liveActivityLabel(oldest)} · ${overviewElapsed(epochMillis(oldest.activeTurnStartedAt))}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(onClick = { viewModel.openSession(oldest.id, provider = sessionProvider(oldest)) }, modifier = Modifier.align(Alignment.End)) {
                                        Text("Open")
                                    }
                                }
                            }
                        }
                    }
                    dashboardSectionHeader("Needs attention", dashboard.attention.size)
                    if (dashboard.attention.isEmpty()) {
                        item { DashboardEmptyState("No sessions currently need attention.") }
                    } else {
                        items(dashboard.attention, key = { "attention:${it.providerKey()}" }) { session ->
                            val approval = pendingApprovals.firstOrNull {
                                sessionProvider(session) == PROVIDER_CODEX && it.sessionId == session.id
                            }
                            val input = pendingInputs.firstOrNull {
                                sessionProvider(session) == PROVIDER_CODEX && it.sessionId == session.id
                            }
                            val requestId = approval?.id ?: input?.id
                            DashboardSessionCard(
                                session = session,
                                repositoryLabel = sessionRepositoryIdentity(session.repository, state.repositories, state.repositoryRoot).label,
                                detail =
                                    when {
                                        input != null -> "Waiting for input"
                                        approval != null -> "Waiting for approval"
                                        else -> dashboardSessionDetail(session)
                                    },
                                showProviderIdentity = showProviderIdentity,
                                onOpen = { viewModel.openSession(session.id, focusedApprovalId = requestId, provider = sessionProvider(session)) },
                            )
                        }
                    }
                    dashboardSectionHeader("Active work", dashboard.active.size)
                    if (dashboard.active.isEmpty()) {
                        item { DashboardEmptyState("No active sessions on this host.") }
                    } else {
                        items(dashboard.active, key = { "active:${it.providerKey()}" }) { session ->
                            DashboardSessionCard(
                                session = session,
                                repositoryLabel = sessionRepositoryIdentity(session.repository, state.repositories, state.repositoryRoot).label,
                                detail = liveActivityMessage(session) ?: liveActivityLabel(session),
                                showProviderIdentity = showProviderIdentity,
                                onOpen = { viewModel.openSession(session.id, provider = sessionProvider(session)) },
                            )
                        }
                    }
                    dashboardSectionHeader("Recently completed", dashboard.recent.size)
                    if (dashboard.recent.isEmpty()) {
                        item { DashboardEmptyState("No terminal turns were observed in the last hour.") }
                    } else {
                        items(dashboard.recent, key = { "recent:${it.providerKey()}" }) { session ->
                            DashboardSessionCard(
                                session = session,
                                repositoryLabel = sessionRepositoryIdentity(session.repository, state.repositories, state.repositoryRoot).label,
                                detail = session.failureSummary ?: session.activityText.ifBlank { session.activityLabel.ifBlank { "Turn finished" } },
                                showProviderIdentity = showProviderIdentity,
                                onOpen = { viewModel.openSession(session.id, provider = sessionProvider(session)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHealthCard(state: UiState) {
    val enabledProviders = state.providers.filter { it.enabled }
    val codexEnabled = enabledProviders.any { it.id == PROVIDER_CODEX }
    val runtime =
        when {
            !state.runtimeConnected -> "Runtime unavailable"
            state.runtimeMode == "shared" -> "Shared Desktop runtime"
            state.runtimeMode == "fallback" -> "Foreman-managed runtime"
            else -> "Runtime connected"
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HOST HEALTH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(state.host.ifBlank { "No endpoint" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (state.connected) "CONNECTED" else "DISCONNECTED",
                    color = if (state.connected) LocalForemanColors.current.success else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (codexEnabled) Text(runtime, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Foreman ${state.foremanVersion ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            enabledProviders.forEach { provider ->
                val version = when (provider.id) {
                    PROVIDER_CODEX -> state.codexVersion ?: provider.version ?: provider.cliVersion
                    else -> provider.cliVersion ?: provider.version ?: provider.sdkVersion
                }
                Text(
                    "${provider.displayName} ${version ?: "—"} · ${if (provider.available) "available" else "unavailable"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dashboardSectionHeader(title: String, count: Int) {
    item {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardEmptyState(message: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
        Text(
            message,
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashboardSessionCard(
    session: SessionSummary,
    repositoryLabel: String,
    detail: String,
    showProviderIdentity: Boolean,
    onOpen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sessionDisplayTitle(session),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showProviderIdentity) {
                    Spacer(Modifier.width(8.dp))
                    ProviderBadge(sessionProvider(session))
                    Spacer(Modifier.width(6.dp))
                }
                StatusPill(sessionDisplayStatus(session))
            }
            Text(repositoryLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                overviewAge(epochMillis(session.lastActivity ?: session.terminalAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dashboardSessionDetail(session: SessionSummary): String =
    when {
        session.status == "failed" -> session.failureSummary ?: "The latest turn failed."
        session.waitType == "input" -> "Waiting for input"
        session.status == "waiting" || session.attention -> "Waiting for approval"
        else -> session.activityText.ifBlank { session.activityLabel }
    }

@Composable
private fun HostOverviewCard(
    host: SavedHostSummary,
    snapshot: HostOverviewSnapshot?,
    onOpenDashboard: () -> Unit,
    onOpenSessions: () -> Unit,
    onReconnect: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    val live = snapshot?.connection == "connected"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(host.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${host.host}:${host.tcpPort}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(snapshot?.connection ?: host.lastKnownStatus, color = if (live) LocalForemanColors.current.success else LocalForemanColors.current.warning, style = MaterialTheme.typography.labelMedium)
            }
            if (!live) Text("STALE · Last connected ${overviewAge(host.lastConnectedAt)} · checked ${overviewAge(snapshot?.observedAt)}", color = LocalForemanColors.current.warning, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Foreman ${snapshot?.foremanVersion ?: "—"} · Codex ${snapshot?.codexVersion ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("${if (snapshot?.runtimeMode == "shared") "Shared Desktop" else if (snapshot?.runtimeMode == "fallback") "Foreman-managed runtime" else "Runtime unknown"}${if (snapshot != null && !snapshot.runtimeConnected) " · unavailable" else ""}", style = MaterialTheme.typography.bodySmall)
            Text("${snapshot?.active ?: 0} active · ${snapshot?.waiting ?: 0} waiting · ${snapshot?.failed ?: 0} failed${if (!live) " (stale)" else ""}")
            Text(
                "Codex ${snapshot?.codexActive ?: 0} · Claude ${snapshot?.claudeActive ?: 0}" +
                    if (snapshot?.claudeUnavailable == true) " · Claude unavailable" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Oldest ${overviewElapsed(snapshot?.oldestTurn?.timestamp)} · Latest activity ${overviewAge(snapshot?.latestActivity)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onOpenDashboard, modifier = Modifier.weight(1f)) {
                    Text("View dashboard")
                }
                FilledTonalButton(onClick = onOpenSessions, modifier = Modifier.weight(1f)) {
                    Text("View sessions")
                }
            }
            if (!live) {
                FilledTonalButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Reconnect host")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onRename) { Text("Edit") }
                TextButton(onClick = onForget, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Forget") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsScreen(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
) {
    val collapsedRepositoryIds = state.activeHostId
        ?.let(state.collapsedRepositoriesByHost::get)
        .orEmpty()
    val showProviderIdentity = shouldShowProviderIdentity(state.providers, state.providerCatalogLoaded)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { HostSelectorMenu(state, viewModel) },
                navigationIcon = {
                    Image(
                        painter = painterResource(R.drawable.foreman_logo),
                        contentDescription = null,
                        modifier =
                            Modifier.padding(start = 12.dp)
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp)),
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::openOverview) {
                        Icon(Icons.Default.Home, contentDescription = "Unified overview")
                    }
                    IconButton(onClick = { viewModel.setSearchOpen(!state.showSearch) }) {
                        Icon(
                            if (state.showSearch) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (state.showSearch) "Close search" else "Search sessions",
                        )
                    }
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = state.connected && !state.loading,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { viewModel.setNewSession(true) }, enabled = state.connected && state.providerCatalogLoaded) {
                        Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                    UiSettingsMenu(state, viewModel, requestTurnMonitoring)
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        bottomBar = {
            AccountUsageDock(state.accountUsage, state.providers, state.providerCatalogLoaded)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (!state.connected) {
                ConnectionBanner(state.error, viewModel::reconnect)
            } else {
                ErrorText(state.error, Modifier.padding(horizontal = 16.dp))
            }
            if (state.showSearch) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = state.searchFilters.query,
                        onValueChange = { viewModel.setSearchFilters(state.searchFilters.copy(query = it)) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search titles and transcripts") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.searchLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else if (state.searchFilters.query.isNotEmpty()) IconButton(onClick = { viewModel.setSearchFilters(state.searchFilters.copy(query = ""), true) }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchNow() }),
                    )
                    IconButton(onClick = { viewModel.setSearchFiltersOpen(true) }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Session filters")
                    }
                }
            }
            if (state.showSearch) {
                Text(
                    "Transcript search covers Codex. Claude Code sessions are filtered by title, workspace, state, pins, and hidden status only.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val displayedSessions = if (state.searchFilters.scope == SessionDiscoveryScope.Archived) {
                state.archivedSessions
            } else state.sessions
            if ((state.loading || state.archivedLoading) && displayedSessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val visible = filterSessions(
                    displayedSessions,
                    state.searchFilters,
                    state.pinnedSessionIds,
                    state.hiddenSessionIds,
                    state.searchResults,
                    state.repositories,
                    state.repositoryRoot,
                )
                val pinned = visible.filter { it.pinned }
                val remaining = visible.filterNot { it.pinned }
                val waiting = remaining.filter { it.session.status == "waiting" || it.session.attention }
                val active = remaining.filter {
                    it.session.status == "working" && !it.session.attention &&
                        it.session.source != "external"
                }
                val recent = remaining.filterNot { it in active || it in waiting }
                val repositoryGroups = repositorySessionGroups(
                    visible,
                    state.repositories,
                    state.repositoryRoot,
                )
                PullToRefreshBox(
                    isRefreshing = state.loading || state.archivedLoading,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.searchError != null || state.archivedError != null) item { ErrorText(state.searchError ?: state.archivedError, Modifier.fillMaxWidth()) }
                        if (visible.isEmpty() && !state.searchLoading && !state.archivedLoading) item {
                            Column(
                                Modifier.fillParentMaxSize().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(if (sessionSearchActive(state.searchFilters)) "No matching sessions" else "No sessions yet", fontWeight = FontWeight.Bold)
                                Text("Try clearing a filter or using a shorter substring.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.groupSessionsByRepository) {
                            repositoryGroups.forEach { group ->
                                repositorySessionSection(
                                    group,
                                    group.repository.id in collapsedRepositoryIds,
                                    { viewModel.toggleCollapsedRepository(group.repository.id) },
                                    { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                    viewModel::requestSessionAction,
                                    { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                    { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                    state.capabilities,
                                    state.repositories,
                                    state.repositoryRoot,
                                    showProviderIdentity,
                                )
                            }
                        } else {
                            sessionSection(
                                "Pinned", pinned,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot, showProviderIdentity,
                            )
                            sessionSection(
                                "Waiting", waiting,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot, showProviderIdentity,
                            )
                            sessionSection(
                                "Active", active,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot, showProviderIdentity,
                            )
                            sessionSection(
                                "Recent", recent,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot, showProviderIdentity,
                            )
                        }
                    }
                }
            }
        }
    }
    if (state.showNewSession) {
        NewSessionDialog(
            repositories = state.repositories,
            repositoryRoot = state.repositoryRoot,
            providers = state.providers,
            providerCatalogLoaded = state.providerCatalogLoaded,
            models = state.models,
            accessLevels = state.accessLevels,
            claudeModels = state.claudeModels,
            claudePermissionModes = state.claudePermissionModes,
            initialProvider = state.selectedNewSessionProvider,
            initialModel = state.newSessionModel,
            initialEffort = state.newSessionEffort,
            initialAccessLevel = state.newSessionAccessLevel,
            initialClaudeModel = state.newSessionClaudeModel,
            initialClaudePermissionMode = state.newSessionClaudePermissionMode,
            onDismiss = { viewModel.setNewSession(false) },
            onStart = viewModel::startProviderSession,
        )
    }
    if (state.showSearchFilters) {
        SessionFilterDialog(
            filters = state.searchFilters,
            repositories = sessionRepositoryOptions(
                if (state.searchFilters.scope == SessionDiscoveryScope.Archived) state.archivedSessions else state.sessions,
                state.repositories,
                state.repositoryRoot,
            ),
            providers = state.providers,
            onChange = { viewModel.setSearchFilters(it, true) },
            onDismiss = { viewModel.setSearchFiltersOpen(false) },
        )
    }
}

@Composable
private fun AccountUsageDock(
    usage: AccountUsage,
    providers: List<ProviderInfo>,
    providerCatalogLoaded: Boolean,
) {
    val visible = providers.filter { it.enabled }.mapNotNull { provider ->
        usage.providers[provider.id]?.let { provider to it }
    }
    if (visible.isEmpty()) return
    val showProviderIdentity = shouldShowProviderIdentity(providers, providerCatalogLoaded)
    var open by remember { mutableStateOf(false) }
    val usedPercent = visible.flatMap { accountUsageWindows(it.second) }
        .maxOfOrNull { it.usedPercent }?.roundToInt()?.coerceIn(0, 100) ?: 0
    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = true }
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UsageRing(usedPercent, 28.dp)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    visible.forEach { (provider, providerUsage) ->
                        Text(
                            (if (showProviderIdentity) "${provider.displayName.removeSuffix(" Code")} " else "") +
                                accountUsageRemaining(providerUsage),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text("Account usage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (open) AccountUsageDialog(visible, showProviderIdentity, onDismiss = { open = false })
}

@Composable
private fun AccountUsageDialog(
    providers: List<Pair<ProviderInfo, ProviderAccountUsage>>,
    showProviderIdentity: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account usage") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                providers.forEachIndexed { providerIndex, (provider, usage) ->
                    if (providerIndex > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (showProviderIdentity || usage.experimental) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (showProviderIdentity) Text(provider.displayName, fontWeight = FontWeight.Bold)
                            if (usage.experimental) Text("EXPERIMENTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        val windows = accountUsageWindows(usage)
                        if (windows.isEmpty()) {
                            Text(
                                usage.availabilityReason ?: "Usage is unavailable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            windows.forEachIndexed { index, window ->
                                val remaining = (100 - window.usedPercent).roundToInt().coerceIn(0, 100)
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(rateLimitLabel(window.windowDurationMins, index), style = MaterialTheme.typography.labelMedium)
                                        Text("$remaining% left", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                    }
                                    LinearProgressIndicator(
                                        progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    window.resetsAt?.let {
                                        Text(
                                            "Resets ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        usage.observedAt?.let {
                            Text(
                                "Last observed ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it * 1000))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun rateLimitLabel(durationMinutes: Long?, index: Int): String =
    when (durationMinutes) {
        10_080L -> "Weekly limit"
        null -> if (index == 0) "Primary limit" else "Secondary limit"
        else -> when {
            durationMinutes > 0 && durationMinutes % 60 == 0L -> "${durationMinutes / 60}-hour limit"
            durationMinutes > 0 -> "$durationMinutes-minute limit"
            else -> if (index == 0) "Primary limit" else "Secondary limit"
        }
    }

@Composable
private fun UsageRing(percentUsed: Int, size: Dp) {
    CircularProgressIndicator(
        progress = { (percentUsed / 100f).coerceIn(0f, 1f) },
        modifier = Modifier.size(size),
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun SessionContextUsageAction(session: SessionSummary, state: UiState) {
    val usage = contextUsageView(session.tokenUsage) ?: return
    var open by remember(session.providerKey()) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.padding(horizontal = 3.dp).clickable { open = true },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            UsageRing(usage.percentUsed, 22.dp)
            Text(
                "${usage.percentRemaining}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    if (open) {
        SessionInfoDialog(session, usage, state, onDismiss = { open = false })
    }
}

@Composable
private fun SessionInfoDialog(
    session: SessionSummary,
    usage: ContextUsageView,
    state: UiState,
    onDismiss: () -> Unit,
) {
    val provider = sessionProvider(session)
    val models = if (provider == PROVIDER_CLAUDE_CODE) state.claudeModels else state.models
    val model = models.firstOrNull { it.id == session.model }?.displayName ?: session.model ?: "—"
    val access = if (provider == PROVIDER_CLAUDE_CODE) {
        state.claudePermissionModes.firstOrNull { it.id == session.permissionMode }?.displayName
            ?: session.permissionMode
    } else {
        state.accessLevels.firstOrNull { it.id == session.accessLevel }?.displayName
            ?: session.accessLevel
    }
    val turnCount = session.messages.mapNotNull { it.turnId }.distinct().size
    val compactions = session.messages.filter { it.kind == "compaction" }
    val latestCompaction = compactions.lastOrNull()
    val total = session.tokenUsage?.total
    val last = session.tokenUsage?.last
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("SESSION INFO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Context window")
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatTokenCount(usage.usedTokens)} / ${formatTokenCount(usage.contextWindow)} tokens")
                    Text("${usage.percentRemaining}% left", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { usage.percentUsed / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatTokenCount(usage.remainingTokens)} tokens remain. Conversation history normally compacts automatically before the window is exhausted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SessionUsageRow("Model", model)
                session.reasoningEffort?.takeIf { provider == PROVIDER_CODEX }?.let {
                    SessionUsageRow("Reasoning", it.replaceFirstChar(Char::uppercase))
                }
                access?.let { SessionUsageRow(if (provider == PROVIDER_CODEX) "Access" else "Permission", it) }
                SessionUsageRow(
                    "Transcript",
                    "${session.messages.size} items" + if (turnCount > 0) " · $turnCount ${if (turnCount == 1) "turn" else "turns"}" else "",
                )
                SessionUsageRow("Compactions", compactions.size.toString())
                latestCompaction?.let { SessionUsageRow("Last compaction", compactionDetail(it)) }
                total?.let { SessionUsageRow("Session tokens", "${formatTokenCount(it.totalTokens)} total") }
                last?.cachedInputTokens?.let { SessionUsageRow("Cached input", formatTokenCount(it)) }
                last?.outputTokens?.let { SessionUsageRow("Last output", formatTokenCount(it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SessionUsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun compactionDetail(item: ConversationItem): String {
    val trigger = when (item.compactionTrigger) {
        "manual" -> "Manual"
        "auto" -> "Automatic"
        else -> "Completed"
    }
    return if (item.preTokens != null && item.postTokens != null) {
        "$trigger · ${formatTokenCount(item.preTokens)} → ${formatTokenCount(item.postTokens)}"
    } else trigger
}

private fun compactDuration(durationMs: Long): String =
    if (durationMs < 1_000) "${durationMs}ms" else "${String.format(java.util.Locale.US, "%.1f", durationMs / 1_000.0).removeSuffix(".0")}s"

private fun androidx.compose.foundation.lazy.LazyListScope.repositorySessionSection(
    group: RepositorySessionGroup,
    collapsed: Boolean,
    toggleCollapsed: () -> Unit,
    open: (String, String?, String) -> Unit,
    action: (SessionSummary, SessionAction) -> Unit,
    pin: (String, String) -> Unit,
    hide: (String, String) -> Unit,
    capabilities: Set<String>,
    repositories: List<RepositoryInfo>,
    repositoryRoot: String,
    showProviderIdentity: Boolean,
) {
    item(key = "repository:${group.repository.id}") {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = toggleCollapsed),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    group.repository.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        "${group.sessions.size} ${if (collapsed) "›" else "⌄"}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    if (!collapsed) {
        sessionCards(
            group.sessions,
            open,
            action,
            pin,
            hide,
            capabilities,
            repositories,
            repositoryRoot,
            showProviderIdentity,
            renderContext = SessionCardRenderContext(
                groupSessionsByRepository = true,
                repositoryGroupId = group.repository.id,
            ),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sessionSection(
    title: String,
    sessions: List<VisibleSession>,
    open: (String, String?, String) -> Unit,
    action: (SessionSummary, SessionAction) -> Unit,
    pin: (String, String) -> Unit,
    hide: (String, String) -> Unit,
    capabilities: Set<String>,
    repositories: List<RepositoryInfo>,
    repositoryRoot: String,
    showProviderIdentity: Boolean,
) {
    if (sessions.isEmpty()) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
    }
    sessionCards(sessions, open, action, pin, hide, capabilities, repositories, repositoryRoot, showProviderIdentity)
}

private fun androidx.compose.foundation.lazy.LazyListScope.sessionCards(
    sessions: List<VisibleSession>,
    open: (String, String?, String) -> Unit,
    action: (SessionSummary, SessionAction) -> Unit,
    pin: (String, String) -> Unit,
    hide: (String, String) -> Unit,
    capabilities: Set<String>,
    repositories: List<RepositoryInfo>,
    repositoryRoot: String,
    showProviderIdentity: Boolean,
    renderContext: SessionCardRenderContext = SessionCardRenderContext(),
) {
    items(sessions, key = { it.session.providerKey() }) { visible ->
        val session = visible.session
        val provider = sessionProvider(session)
        SessionCard(
            session = session,
            matches = visible.matches,
            pinned = visible.pinned,
            hidden = visible.hidden,
            repositoryLabel = sessionCardRepositoryLabel(
                session,
                repositories,
                repositoryRoot,
                renderContext,
            ),
            onClick = { open(session.id, visible.matches.firstOrNull { it.itemId != null }?.itemId, provider) },
            onAction = { action(session, it) },
            onPin = { pin(provider, session.id) },
            onHide = { hide(provider, session.id) },
            capabilities = capabilities,
            showProviderIdentity = showProviderIdentity,
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    matches: List<SessionSearchMatch>,
    pinned: Boolean,
    hidden: Boolean,
    repositoryLabel: String?,
    onClick: () -> Unit,
    onAction: (SessionAction) -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    capabilities: Set<String>,
    showProviderIdentity: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (session.archived) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (session.archived) BorderStroke(1.dp, MaterialTheme.colorScheme.secondary) else null,
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                sessionDisplayTitle(session),
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showProviderIdentity) {
                    ProviderBadge(sessionProvider(session))
                    Spacer(Modifier.width(6.dp))
                }
                StatusPill(if (session.archived) "Archived" else sessionDisplayStatus(session))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPin, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (pinned) "Unpin session" else "Pin session",
                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onHide, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = if (hidden) "Restore session" else "Hide session")
                }
                SessionActionsMenu(
                    enabled = sessionCanBeManaged(session.status),
                    archiveSupported = sessionActionSupported(session, capabilities, SessionAction.Archive),
                    restoreSupported = sessionActionSupported(session, capabilities, SessionAction.Restore),
                    deleteSupported = sessionActionSupported(session, capabilities, SessionAction.Delete),
                    onAction = onAction,
                )
            }
            if (sessionProvider(session) == PROVIDER_CLAUDE_CODE && session.source == "external") {
                Text(
                    (if (session.status == "working") "External active" else "Resumable") +
                        " · Not live-attached",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            repositoryLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            matches.take(3).forEach { match ->
                Text(
                    "${match.kind.replaceFirstChar { it.uppercase() }} · ${match.snippet}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            session.lastActivity?.let {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(it * 1000)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionFilterDialog(
    filters: SessionSearchFilters,
    repositories: List<SessionRepositoryOption>,
    providers: List<ProviderInfo>,
    onChange: (SessionSearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session filters") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val availableProviders = providers.filter { it.enabled && it.available }
                val archivedProviders = availableProviders.filter { "session.archived.list" in it.capabilities }
                SessionFilterMenu(
                    label = "Sessions",
                    selected = if (filters.scope == SessionDiscoveryScope.Archived) "Archived" else "Normal",
                    options = listOf(SessionDiscoveryScope.Normal.name to "Normal") +
                        if (archivedProviders.isNotEmpty()) listOf(SessionDiscoveryScope.Archived.name to "Archived") else emptyList(),
                    onSelect = { onChange(filters.copy(scope = SessionDiscoveryScope.valueOf(it))) },
                )
                if (archivedProviders.isEmpty()) {
                    Text(
                        "Archived discovery is unavailable because no enabled provider advertises support.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SessionFilterMenu(
                    label = "Provider",
                    selected = availableProviders.firstOrNull { it.id == filters.provider }?.displayName ?: "All enabled providers",
                    options = listOf("" to "All enabled providers") + availableProviders.map { it.id to it.displayName },
                    onSelect = { onChange(filters.copy(provider = it)) },
                )
                if (filters.scope == SessionDiscoveryScope.Archived && filters.provider.isNotBlank() && archivedProviders.none { it.id == filters.provider }) {
                    Text(
                        "${providers.firstOrNull { it.id == filters.provider }?.displayName ?: "This provider"} does not expose archived sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SessionFilterMenu(
                    label = "Repository or workspace",
                    selected = repositories.firstOrNull { it.id == filters.repository }?.label ?: "All repositories and workspaces",
                    options = listOf("" to "All repositories and workspaces") + repositories.map { it.id to it.label },
                    onSelect = { onChange(filters.copy(repository = it)) },
                )
                SessionFilterMenu(
                    label = "Status",
                    selected = filters.status.name.replace("All", "All statuses"),
                    options = SessionSearchStatus.values().map { it.name to if (it == SessionSearchStatus.All) "All statuses" else it.name },
                    onSelect = { onChange(filters.copy(status = SessionSearchStatus.valueOf(it))) },
                )
                SessionFilterMenu(
                    label = "Date",
                    selected = when (filters.dateRange) {
                        SessionDateRange.All -> "Any time"
                        SessionDateRange.Today -> "Today"
                        SessionDateRange.Last7Days -> "Last 7 days"
                        SessionDateRange.Last30Days -> "Last 30 days"
                        SessionDateRange.Custom -> "Custom"
                    },
                    options = listOf(
                        SessionDateRange.All.name to "Any time",
                        SessionDateRange.Today.name to "Today",
                        SessionDateRange.Last7Days.name to "Last 7 days",
                        SessionDateRange.Last30Days.name to "Last 30 days",
                        SessionDateRange.Custom.name to "Custom",
                    ),
                    onSelect = { onChange(filters.copy(dateRange = SessionDateRange.valueOf(it))) },
                )
                if (filters.dateRange == SessionDateRange.Custom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = filters.dateFrom,
                            onValueChange = { onChange(filters.copy(dateFrom = it.take(10))) },
                            label = { Text("From") },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = filters.dateTo,
                            onValueChange = { onChange(filters.copy(dateTo = it.take(10))) },
                            label = { Text("To") },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filters.pinnedOnly, onCheckedChange = { onChange(filters.copy(pinnedOnly = it)) })
                    Text("Pinned only")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filters.hiddenOnly, onCheckedChange = { onChange(filters.copy(hiddenOnly = it)) })
                    Text("Hidden sessions")
                }
                Text(
                    "All active criteria are combined. Pins change ordering; hidden sessions appear only here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { onChange(SessionSearchFilters()); onDismiss() }) { Text("Clear") }
        },
    )
}

@Composable
private fun SessionFilterMenu(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = { expanded = false; onSelect(id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailScreen(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
) {
    val selected = state.selected
    val readOnly = selected?.archived == true || selected?.readOnly == true
    val selectedProvider = selected?.let(::sessionProvider) ?: PROVIDER_CODEX
    val selectedApprovals = state.approvals.filter {
        !readOnly && selectedProvider == PROVIDER_CODEX && it.sessionId == selected?.id
    }
    val selectedInputs = state.inputs.filter {
        !readOnly && selectedProvider == PROVIDER_CODEX && it.sessionId == selected?.id
    }
    val messages = selected?.messages.orEmpty()
    val messageItemIds = messages.mapTo(mutableSetOf()) { it.id }
    val protectedItemIds =
        buildSet {
            state.highlightedItemId?.let(::add)
            selectedApprovals.mapNotNullTo(this) { it.itemId }
            selectedInputs.mapNotNullTo(this) { it.itemId }
        }
    val displayBlocks =
        conversationBlocks(messages, state.activityDetail, protectedItemIds)
    val detachedApprovals =
        selectedApprovals.filter { it.itemId == null || it.itemId !in messageItemIds }
    val detachedInputs =
        selectedInputs.filter { it.itemId == null || it.itemId !in messageItemIds }
    val listState = rememberLazyListState()
    val lastMessage = selected?.messages?.lastOrNull()
    val hapticFeedback = LocalHapticFeedback.current
    var previousStatus by remember(selected?.id) { mutableStateOf(selected?.status) }

    BackHandler(onBack = viewModel::backToSessions)

    LaunchedEffect(selected?.id, selected?.status, state.hapticsEnabled) {
        val event = sessionHapticEvent(previousStatus, selected?.status)
        previousStatus = selected?.status
        if (!state.hapticsEnabled) return@LaunchedEffect
        when (event) {
            SessionHapticEvent.Completed ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            SessionHapticEvent.Attention ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            SessionHapticEvent.Failed ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Reject)
            null -> Unit
        }
    }

    LaunchedEffect(selected?.id, state.focusedApprovalId, selectedApprovals.size) {
        selected?.let {
            val focusedItemId =
                selectedApprovals.firstOrNull { approval -> approval.id == state.focusedApprovalId }?.itemId
                    ?: selectedInputs.firstOrNull { input -> input.id == state.focusedApprovalId }?.itemId
            val matchedIndex =
                displayBlocks.indexOfFirst { block ->
                    block.items.any { item -> item.id == (focusedItemId ?: state.highlightedItemId) }
                }
            listState.scrollToItem(
                if (matchedIndex >= 0) matchedIndex + 1
                else displayBlocks.size + detachedApprovals.size + detachedInputs.size,
            )
        }
    }
    LaunchedEffect(
        state.followNewMessages,
        state.activityDetail,
        state.highlightedItemId,
        selected?.messages?.size,
        lastMessage?.text,
        selected?.status,
        selected?.activityLabel,
        selected?.activityText,
        selectedApprovals.size,
        selectedInputs.size,
    ) {
        if (state.followNewMessages && state.highlightedItemId == null) {
            selected?.let {
                listState.scrollToItem(
                    displayBlocks.size + detachedApprovals.size + detachedInputs.size +
                        if (it.status == "working") 1 else 0,
                )
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            sessionDisplayTitle(selected),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected != null) {
                            Text(
                                if (readOnly) "Archived · Read only" else sessionDisplayStatus(selected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::backToSessions) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selected != null) SessionContextUsageAction(selected, state)
                    UiSettingsMenu(
                        state = state,
                        viewModel = viewModel,
                        requestTurnMonitoring = requestTurnMonitoring,
                        session = selected,
                        onSessionAction = { action ->
                            selected?.let { viewModel.requestSessionAction(it, action) }
                        },
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        bottomBar = {
            if (selected != null && !readOnly) PromptBox(
                text = state.activeHostId?.let {
                    composerDraft(state.composerDrafts, it, selected.id, selectedProvider)
                }.orEmpty(),
                provider = selectedProvider,
                resumableExternal = selected.source == "external",
                working = selected.status in setOf("working", "waiting", "stopping"),
                interruptible = providerInterruptEligible(selected),
                interruptEnabled = state.connected && !state.submitting,
                routeEnabled = state.connected && !state.submitting && sessionRouteEditable(selected),
                routeLocked = !sessionRouteEditable(selected),
                enabled = state.connected && !state.submitting &&
                    !(selectedProvider == PROVIDER_CLAUDE_CODE && selected.status in setOf("working", "waiting")) &&
                    selectedApprovals.none { it.status == "pending" || it.status == "submitting" } &&
                    selectedInputs.none { it.status == "pending" || it.status == "submitting" },
                accessLevels =
                    if (selectedProvider == PROVIDER_CLAUDE_CODE) {
                        state.claudePermissionModes.map {
                            AccessLevelInfo(it.id, it.displayName, it.description)
                        }
                    } else state.accessLevels,
                accessLevelId =
                    if (selectedProvider == PROVIDER_CLAUDE_CODE) state.claudeComposerPermissionMode
                    else state.composerAccessLevel,
                models = if (selectedProvider == PROVIDER_CLAUDE_CODE) state.claudeModels else state.models,
                modelId = if (selectedProvider == PROVIDER_CLAUDE_CODE) state.claudeComposerModel else state.composerModel,
                effort = if (selectedProvider == PROVIDER_CLAUDE_CODE) null else state.composerEffort,
                hapticsEnabled = state.hapticsEnabled,
                selectAccessLevel = if (selectedProvider == PROVIDER_CLAUDE_CODE) viewModel::setClaudeComposerPermissionMode else viewModel::setComposerAccessLevel,
                selectModel = if (selectedProvider == PROVIDER_CLAUDE_CODE) viewModel::setClaudeComposerModel else viewModel::setComposerModel,
                selectEffort = viewModel::setComposerEffort,
                showError = viewModel::composerError,
                onTextChange = { text ->
                    state.activeHostId?.let { hostId ->
                        viewModel.setComposerDraft(hostId, selected.id, text, selectedProvider)
                    }
                },
                interrupt = viewModel::interrupt,
                send = viewModel::send,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.connected) ConnectionBanner(state.error, viewModel::reconnect)
            else ErrorText(state.error, Modifier.padding(horizontal = 16.dp))
            if (state.loading && selected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (selected != null) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (readOnly) {
                        item(key = "archived-read-only") {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Archived · Read only", fontWeight = FontWeight.Bold)
                                    Text("This transcript is being viewed without resuming or changing the Codex thread.")
                                    if (selected.capabilities.contains("session.restore")) {
                                        Button(
                                            onClick = { viewModel.requestSessionAction(selected, SessionAction.Restore) },
                                            enabled = state.connected && !state.submitting,
                                            modifier = Modifier.align(Alignment.End),
                                        ) { Text("Restore") }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    selected.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (shouldShowProviderIdentity(state.providers, state.providerCatalogLoaded)) {
                                    ProviderBadge(selectedProvider)
                                }
                            }
                            if (selectedProvider == PROVIDER_CLAUDE_CODE && selected.source == "external") {
                                Text(
                                    (if (selected.status == "working") "External active" else "Resumable") +
                                        " · Not live-attached. Resume in Foreman after external work stops; the external process is not attached.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (selectedProvider == PROVIDER_CLAUDE_CODE && selected.status == "waiting") {
                                Text(
                                    "Permission required in Claude Code. This request cannot yet be answered from Android.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        displayBlocks,
                        key = { index, block -> "${if (block.collapsedActivity) "activity" else "message"}-${block.items.first().id}-$index" },
                    ) { _, block ->
                        if (block.collapsedActivity) {
                            CollapsedActivityGroup(block.items)
                        } else {
                            val item = block.items.single()
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ConversationRow(item, viewModel::openWorkspaceFile)
                                selectedApprovals.filter { !readOnly && it.itemId == item.id }.forEach { approval ->
                                    ApprovalCard(
                                        approval = approval,
                                        connected = state.connected,
                                        submitting = approval.id in state.submittingApprovalIds,
                                        error = state.approvalErrors[approval.id],
                                        onRespond = { viewModel.respondToApproval(approval, it) },
                                    )
                                }
                                selectedInputs.filter { !readOnly && it.itemId == item.id }.forEach { input ->
                                    InputRequestCard(
                                        input = input,
                                        connected = state.connected,
                                        submitting = input.id in state.submittingInputIds,
                                        error = state.inputErrors[input.id],
                                        onRespond = { viewModel.respondToInput(input, it) },
                                    )
                                }
                            }
                        }
                    }
                    items(
                        if (readOnly) emptyList() else detachedApprovals,
                        key = { "approval-${it.id}" },
                    ) { approval ->
                        ApprovalCard(
                            approval = approval,
                            connected = state.connected,
                            submitting = approval.id in state.submittingApprovalIds,
                            error = state.approvalErrors[approval.id],
                            onRespond = { viewModel.respondToApproval(approval, it) },
                        )
                    }
                    items(
                        if (readOnly) emptyList() else detachedInputs,
                        key = { "input-${it.id}" },
                    ) { input ->
                        InputRequestCard(
                            input = input,
                            connected = state.connected,
                            submitting = input.id in state.submittingInputIds,
                            error = state.inputErrors[input.id],
                            onRespond = { viewModel.respondToInput(input, it) },
                        )
                    }
                    if (!readOnly && selected.status == "working" && selected.source != "external") {
                        item(key = "live-activity") {
                            LiveActivityRow(selected)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedActivityGroup(items: List<ConversationItem>) {
    var expanded by remember(items.map { it.id }) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("◇", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(
                        "Completed activity",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatActivitySummary(items),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { ConversationRow(it) }
                }
            }
        }
    }
}

@Composable
private fun LiveActivityRow(session: SessionSummary) {
    val activityMessage = liveActivityMessage(session)
    val activityTitle = activityMessage ?: "${liveActivityLabel(session)}…"
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    inlineMarkdown(
                        activityTitle,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                activityMessage?.let {
                    Text(
                        "${liveActivityLabel(session)}…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    item: ConversationItem,
    onOpenWorkspaceFile: ((WorkspaceFileTarget) -> Unit)? = null,
) {
    when (item.kind) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                modifier =
                    Modifier.fillMaxWidth(0.86f)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(16.dp),
                        )
                        .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.images.isNotEmpty()) {
                    ImageThumbnailRow(item.images)
                }
                val unavailable = item.imageCount - item.images.size
                if (unavailable > 0) {
                    Text(
                        "$unavailable image${if (unavailable == 1) "" else "s"} unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (item.text.isNotBlank()) {
                    MarkdownText(
                        text = item.text,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        "assistant" -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "FOREMAN",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
            MarkdownText(
                text = item.text,
                contentColor = MaterialTheme.colorScheme.onBackground,
                onOpenWorkspaceFile = onOpenWorkspaceFile,
            )
        }
        "command", "tool" -> {
            val tone = activityStatusTone(item)
            Card(
                modifier = Modifier.fillMaxWidth(),
                border =
                    when (tone) {
                        ActivityStatusTone.Active -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ActivityStatusTone.Attention -> BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ActivityStatusTone.Neutral -> null
                    },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (item.kind == "command") "Command" else "Tool",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        item.description,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        formatActivityOutcome(item),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            when (tone) {
                                ActivityStatusTone.Active -> MaterialTheme.colorScheme.primary
                                ActivityStatusTone.Attention -> MaterialTheme.colorScheme.error
                                ActivityStatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
        "compaction" -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("↻", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Context compacted", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Text(compactionDetail(item), style = MaterialTheme.typography.bodySmall)
                }
                item.durationMs?.let {
                    Text(compactDuration(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ImageThumbnailRow(
    images: List<ImagePayload>,
    remove: ((Int) -> Unit)? = null,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(images) { index, image ->
            Box {
                val bitmap =
                    remember(image.data) {
                        runCatching {
                            Base64.decode(image.data, Base64.DEFAULT).let {
                                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                            }
                        }.getOrNull()
                    }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(84.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Image", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                remove?.let {
                    IconButton(
                        onClick = { it(index) },
                        modifier =
                            Modifier.align(Alignment.TopEnd)
                                .size(28.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    RoundedCornerShape(14.dp),
                                ),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove image",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptBox(
    text: String,
    provider: String,
    resumableExternal: Boolean,
    working: Boolean,
    interruptible: Boolean,
    interruptEnabled: Boolean,
    routeEnabled: Boolean,
    routeLocked: Boolean,
    enabled: Boolean,
    accessLevels: List<AccessLevelInfo>,
    accessLevelId: String?,
    models: List<ModelInfo>,
    modelId: String?,
    effort: String?,
    hapticsEnabled: Boolean,
    selectAccessLevel: (String) -> Unit,
    selectModel: (String) -> Unit,
    selectEffort: (String) -> Unit,
    showError: (String) -> Unit,
    onTextChange: (String) -> Unit,
    interrupt: () -> Unit,
    send: (String, List<ImagePayload>, () -> Unit) -> Unit,
) {
    var images by remember { mutableStateOf(emptyList<ImagePayload>()) }
    var processing by remember { mutableStateOf(false) }
    var showAccessLevels by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }
    var confirmBypassPermissions by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showEfforts by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val selectedAccessLevel = accessLevels.firstOrNull { it.id == accessLevelId }
    val selectedModel = models.firstOrNull { it.id == modelId }
    val imageSupported =
        provider == PROVIDER_CODEX && (selectedModel == null ||
            selectedModel.inputModalities.isEmpty() ||
            "image" in selectedModel.inputModalities)
    val picker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_MESSAGE),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                processing = true
                runCatching {
                    val added = preparePickedImages(context, uris)
                    val combined = images + added
                    require(combined.size <= MAX_IMAGES_PER_MESSAGE) {
                        "Choose at most $MAX_IMAGES_PER_MESSAGE images"
                    }
                    require(encodedImageBytes(combined) <= MAX_ENCODED_IMAGE_BYTES) {
                        "Combined images must be at most 8 MiB"
                    }
                    combined
                }.onSuccess {
                    images = it
                }.onFailure {
                    showError(it.message ?: "The selected image could not be attached")
                }
                processing = false
            }
        }
    Surface(
        modifier = Modifier.navigationBarsPadding().imePadding(),
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            ComposerRouteRow(
                provider = provider,
                accessLevels = accessLevels,
                selectedAccessLevel = selectedAccessLevel,
                accessLevelId = accessLevelId,
                models = models,
                selectedModel = selectedModel,
                modelId = modelId,
                effort = effort,
                enabled = routeEnabled,
                showAccessLevels = { showAccessLevels = true },
                showModels = { showModels = true },
                showEfforts = { showEfforts = true },
                effortsExpanded = showEfforts,
                dismissEfforts = { showEfforts = false },
            ) {
                selectEffort(it)
                showEfforts = false
            }
            if (routeLocked) {
                Text(
                    if (provider == PROVIDER_CLAUDE_CODE) {
                        "Model and permission are available when this turn finishes."
                    } else {
                        "Model, reasoning, and access are available when this turn finishes."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (images.isNotEmpty()) {
                ImageThumbnailRow(images) { index ->
                    images = images.filterIndexed { itemIndex, _ -> itemIndex != index }
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactMessageField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = when {
                        provider == PROVIDER_CLAUDE_CODE && working -> "Claude is working…"
                        provider == PROVIDER_CLAUDE_CODE -> "Message Claude Code…"
                        working -> "Steer this turn…"
                        else -> "Message Foreman…"
                    },
                    modifier = Modifier.weight(1f),
                    leadingIcon = {
                        IconButton(
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            enabled =
                                enabled &&
                                    !processing &&
                                    imageSupported &&
                                    images.size < MAX_IMAGES_PER_MESSAGE,
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription =
                                    if (provider == PROVIDER_CLAUDE_CODE) {
                                        "Image attachments unavailable for Claude Code"
                                    } else "Attach images",
                            )
                        }
                    },
                )
                if (interruptible) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        IconButton(
                            onClick = interrupt,
                            enabled = interruptEnabled,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop current turn")
                        }
                    }
                }
                Button(
                    onClick = {
                        if (hapticsEnabled) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        }
                        send(text, images) {
                            onTextChange("")
                            images = emptyList()
                        }
                    },
                    enabled =
                        enabled &&
                            !processing &&
                            (text.isNotBlank() || images.isNotEmpty()),
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                ) {
                    Text(
                        when {
                            resumableExternal -> "Resume in Foreman"
                            provider == PROVIDER_CODEX && working -> "Steer"
                            else -> "Send"
                        },
                    )
                }
            }
        }
    }
    if (showAccessLevels) {
        AlertDialog(
            onDismissRequest = { showAccessLevels = false },
            title = {
                Text(
                    if (provider == PROVIDER_CLAUDE_CODE) "Choose permission mode"
                    else "Choose access level",
                )
            },
            text = {
                Column {
                    accessLevels.forEach { level ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    showAccessLevels = false
                                    if (level.id == "full") {
                                        confirmFullAccess = true
                                    } else if (level.id == "bypassPermissions") {
                                        confirmBypassPermissions = true
                                    } else {
                                        selectAccessLevel(level.id)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    level.displayName,
                                    fontWeight = FontWeight.SemiBold,
                                    color =
                                        if (level.id in setOf("full", "bypassPermissions")) {
                                            LocalForemanColors.current.fullAccess
                                        } else {
                                            Color.Unspecified
                                        },
                                )
                                if (level.description.isNotBlank()) {
                                    Text(
                                        level.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (level.id == accessLevelId) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccessLevels = false }) { Text("Close") }
            },
        )
    }
    if (confirmFullAccess) {
        AlertDialog(
            onDismissRequest = { confirmFullAccess = false },
            icon = {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = LocalForemanColors.current.fullAccess,
                )
            },
            title = { Text("Enable full access?") },
            text = {
                Text(
                    "Codex will be able to use the Internet and read or change any file " +
                        "available to your account without asking first.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmFullAccess = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectAccessLevel("full")
                        confirmFullAccess = false
                    },
                ) {
                    Text("Enable full access", color = LocalForemanColors.current.fullAccess)
                }
            },
        )
    }
    if (confirmBypassPermissions) {
        AlertDialog(
            onDismissRequest = { confirmBypassPermissions = false },
            icon = {
                Icon(Icons.Default.Security, contentDescription = null, tint = LocalForemanColors.current.fullAccess)
            },
            title = { Text("Bypass Claude permissions?") },
            text = {
                Text(
                    "This high-risk mode bypasses Claude Code permission checks. It is never selected automatically.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmBypassPermissions = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectAccessLevel("bypassPermissions")
                        confirmBypassPermissions = false
                    },
                ) { Text("Use high-risk mode", color = LocalForemanColors.current.fullAccess) }
            },
        )
    }
    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Choose model") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(models, key = { it.id }) { model ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selectModel(model.id)
                                    showModels = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(model.displayName, fontWeight = FontWeight.SemiBold)
                                if (model.description.isNotBlank()) {
                                    Text(
                                        model.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (model.id == modelId) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModels = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun CompactMessageField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = RoundedCornerShape(20.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        maxLines = 5,
        keyboardOptions = composerKeyboardOptions,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = { Text(placeholder) },
                leadingIcon = leadingIcon,
                colors = colors,
                contentPadding =
                    OutlinedTextFieldDefaults.contentPadding(
                        start = 4.dp,
                        top = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp,
                    ),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = shape,
                    )
                },
            )
        },
    )
}

@Composable
private fun ComposerRouteRow(
    provider: String,
    accessLevels: List<AccessLevelInfo>,
    selectedAccessLevel: AccessLevelInfo?,
    accessLevelId: String?,
    models: List<ModelInfo>,
    selectedModel: ModelInfo?,
    modelId: String?,
    effort: String?,
    enabled: Boolean,
    showAccessLevels: () -> Unit,
    showModels: () -> Unit,
    showEfforts: () -> Unit,
    effortsExpanded: Boolean,
    dismissEfforts: () -> Unit,
    selectEffort: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            TextButton(
                onClick = showAccessLevels,
                enabled = enabled && accessLevels.isNotEmpty(),
                colors =
                    if (accessLevelId in setOf("full", "bypassPermissions")) {
                        ButtonDefaults.textButtonColors(
                            contentColor = LocalForemanColors.current.fullAccess,
                            disabledContentColor = LocalForemanColors.current.fullAccess,
                        )
                    } else {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    selectedAccessLevel?.displayName ?: accessLevelId ?:
                        if (provider == PROVIDER_CLAUDE_CODE) "Permission" else "Access",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextButton(
                onClick = showModels,
                enabled = enabled && models.isNotEmpty(),
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    selectedModel?.displayName ?: modelId ?: "Default model",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (provider == PROVIDER_CODEX) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Box {
                TextButton(
                    onClick = showEfforts,
                    enabled = enabled && !selectedModel?.reasoningEfforts.isNullOrEmpty(),
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(effort?.replaceFirstChar { it.uppercase() } ?: "Default")
                }
                DropdownMenu(
                    expanded = effortsExpanded,
                    onDismissRequest = dismissEfforts,
                ) {
                    selectedModel?.reasoningEfforts?.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.replaceFirstChar { it.uppercase() })
                                    if (option.equals("ultra", ignoreCase = true)) {
                                        Text(
                                            "Consumes usage limits faster",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = { selectEffort(option) },
                            trailingIcon = {
                                if (option == effort) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostSelectorMenu(
    state: UiState,
    viewModel: ForemanViewModel,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameValue by remember(state.activeHostId, state.displayName) { mutableStateOf(state.displayName) }
    var confirmForget by remember { mutableStateOf(false) }
    val active = state.savedHosts.firstOrNull { it.id == state.activeHostId }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                if (compact) active?.displayName ?: "Hosts"
                else "${active?.displayName ?: "Hosts"} · ${state.connectionStatus}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Unified overview") },
                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                onClick = { expanded = false; viewModel.openOverview() },
            )
            HorizontalDivider()
            Text(
                "Saved hosts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            state.savedHosts.forEach { host ->
                val status = if (host.id == state.activeHostId) state.connectionStatus else host.lastKnownStatus
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(host.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${host.host}:${host.tcpPort} · $status",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (host.id == state.activeHostId) {
                            Icon(Icons.Default.Check, contentDescription = "Active host")
                        }
                    },
                    onClick = {
                        expanded = false
                        viewModel.switchHost(host.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add host") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { expanded = false; viewModel.addHost() },
            )
            if (active != null) {
                DropdownMenuItem(
                    text = { Text("Rename active host") },
                    onClick = {
                        expanded = false
                        renameValue = active.displayName
                        renameOpen = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Forget active host", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { expanded = false; confirmForget = true },
                )
            }
            Text(
                "Background monitoring follows the active host only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    if (renameOpen && active != null) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename host") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    enabled = renameValue.isNotBlank(),
                    onClick = {
                        viewModel.renameHost(active.id, renameValue)
                        renameOpen = false
                    },
                ) { Text("Save") }
            },
        )
    }
    if (confirmForget && active != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${active.displayName}?") },
            text = {
                Text("This removes only this host and its encrypted token. Other saved hosts remain available.")
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        viewModel.forgetHost(active.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Forget") }
            },
        )
    }
}

@Composable
private fun UiSettingsMenu(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    session: SessionSummary? = null,
    onSessionAction: ((SessionAction) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var showingThemes by remember { mutableStateOf(false) }
    var showingActivityDetail by remember { mutableStateOf(false) }
    var showingProviders by remember { mutableStateOf(false) }
    var showingNotifications by remember { mutableStateOf(false) }
    var showingAbout by remember { mutableStateOf(false) }
    var notificationRepositoryId by remember { mutableStateOf<String?>(null) }
    var quietStartText by remember(state.notificationPreferences.quietStart) { mutableStateOf(state.notificationPreferences.quietStart) }
    var quietEndText by remember(state.notificationPreferences.quietEnd) { mutableStateOf(state.notificationPreferences.quietEnd) }
    var confirmForgetHost by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    Box(modifier) {
        IconButton(
            onClick = {
                showingThemes = false
                showingActivityDetail = false
                showingProviders = false
                showingNotifications = false
                notificationRepositoryId = null
                expanded = true
            },
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                showingThemes = false
                showingActivityDetail = false
                showingProviders = false
                showingNotifications = false
                notificationRepositoryId = null
            },
        ) {
            if (showingThemes) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Theme",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to settings",
                        )
                    },
                    onClick = { showingThemes = false },
                )
                HorizontalDivider()
                ThemeId.entries.forEach { themeId ->
                    val selected = state.themeId == themeId
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(themeId.displayName)
                                Text(
                                    when (themeId) {
                                        ThemeId.Foreman -> "Signature violet Foreman palette"
                                        ThemeId.Harbor -> "Calm ocean blue and teal surfaces"
                                        ThemeId.Grove -> "Natural green with warm neutrals"
                                        ThemeId.Ember -> "Warm plum and clay surfaces"
                                        ThemeId.Dune -> "Warm sand and amber with earthy neutrals"
                                        ThemeId.Slate -> "Cool blue-gray surfaces with a steady blue accent"
                                        ThemeId.HighContrast -> "Maximum separation for text, controls, and status cues"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = { ThemePreview(themeId, selected) },
                        trailingIcon = {
                            if (selected) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        },
                        onClick = {
                            viewModel.setThemeId(themeId)
                            expanded = false
                            showingThemes = false
                        },
                    )
                }
            } else if (showingActivityDetail) {
                DropdownMenuItem(
                    text = { Text("Activity detail", style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
                    },
                    onClick = { showingActivityDetail = false },
                )
                HorizontalDivider()
                ActivityDetail.values().forEach { detail ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(detail.name)
                                Text(
                                    if (detail == ActivityDetail.Focused) {
                                        "Group completed commands and tools, including non-zero exits"
                                    } else {
                                        "Show every command and tool item"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            RadioButton(selected = state.activityDetail == detail, onClick = null)
                        },
                        onClick = {
                            viewModel.setActivityDetail(detail)
                            expanded = false
                            showingActivityDetail = false
                        },
                    )
                }
            } else if (showingProviders) {
                DropdownMenuItem(
                    text = { Text("Providers", style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
                    },
                    onClick = { showingProviders = false },
                )
                HorizontalDivider()
                Text(
                    "Choose which installed CLIs Foreman uses on this host. At least one available provider must remain enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                state.providers.forEach { provider ->
                    val requiredEnabled = providerMustRemainEnabled(provider, state.providers)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(provider.displayName)
                                Text(
                                    when {
                                        !provider.enabled && provider.installed == false -> "Not installed"
                                        !provider.enabled -> "Disabled"
                                        provider.available -> "Available"
                                        else -> "Unavailable"
                                    } + if (requiredEnabled) " · at least one available provider required" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            Checkbox(checked = provider.enabled, onCheckedChange = null, enabled = !requiredEnabled && !state.submitting)
                        },
                        enabled = !requiredEnabled && !state.submitting,
                        onClick = { viewModel.setProviderEnabled(provider.id, !provider.enabled) },
                    )
                }
            } else if (showingNotifications) {
                val notificationPreferences = state.notificationPreferences
                val selectedRepository = notificationRepositoryId
                DropdownMenuItem(
                    text = {
                        Text(
                            if (selectedRepository == null) "Notifications" else "Repository override",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    },
                    onClick = {
                        if (selectedRepository == null) showingNotifications = false
                        else notificationRepositoryId = null
                    },
                )
                HorizontalDivider()
                if (selectedRepository == null) {
                    Text(
                        "Permission: ${if (state.notificationPermissionGranted) "allowed" else "not allowed"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        "When enabled, Android watches all active turns on this host, including turns started from web or desktop. Android may stop background work under battery or force-stop restrictions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    SettingsCheckboxItem(
                        "Monitor all turns in background",
                        state.monitorActiveTurns,
                    ) { requestTurnMonitoring(!state.monitorActiveTurns) }
                    SettingsCheckboxItem(
                        "Override for this host",
                        state.hostNotificationOverride,
                    ) { viewModel.setHostNotificationOverride(!state.hostNotificationOverride) }
                    SettingsCheckboxItem("Approvals and input", notificationPreferences.notifyApprovals) {
                        viewModel.setNotificationPreferences(notificationPreferences.copy(notifyApprovals = !notificationPreferences.notifyApprovals))
                    }
                    SettingsCheckboxItem("Failures", notificationPreferences.notifyFailures) {
                        viewModel.setNotificationPreferences(notificationPreferences.copy(notifyFailures = !notificationPreferences.notifyFailures))
                    }
                    SettingsCheckboxItem("Completions", notificationPreferences.notifyCompletions) {
                        viewModel.setNotificationPreferences(notificationPreferences.copy(notifyCompletions = !notificationPreferences.notifyCompletions))
                    }
                    SettingsCheckboxItem("Interruptions", notificationPreferences.notifyInterruptions) {
                        viewModel.setNotificationPreferences(notificationPreferences.copy(notifyInterruptions = !notificationPreferences.notifyInterruptions))
                    }
                    SettingsCheckboxItem("Long-running turns", notificationPreferences.notifyLongRunning) {
                        viewModel.setNotificationPreferences(notificationPreferences.copy(notifyLongRunning = !notificationPreferences.notifyLongRunning))
                    }
                    if (notificationPreferences.notifyLongRunning) {
                        DropdownMenuItem(
                            text = { Text("Long-running threshold") },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${notificationPreferences.longRunningMinutes} min")
                                    IconButton(onClick = {
                                        viewModel.setNotificationPreferences(notificationPreferences.copy(longRunningMinutes = (notificationPreferences.longRunningMinutes - 5).coerceAtLeast(1)))
                                    }) { Text("−") }
                                    IconButton(onClick = {
                                        viewModel.setNotificationPreferences(notificationPreferences.copy(longRunningMinutes = (notificationPreferences.longRunningMinutes + 5).coerceAtMost(1_440)))
                                    }) { Text("+") }
                                }
                            },
                            onClick = {},
                        )
                    }
                    SettingsCheckboxItem("Quiet hours", notificationPreferences.quietHoursEnabled) {
                        viewModel.setNotificationPreferences(notificationPreferences.copy(quietHoursEnabled = !notificationPreferences.quietHoursEnabled))
                    }
                    if (notificationPreferences.quietHoursEnabled) {
                        Column(Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = quietStartText,
                                    onValueChange = { value ->
                                        if (value.length <= 5) quietStartText = value
                                        if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)) {
                                            viewModel.setNotificationPreferences(notificationPreferences.copy(quietStart = value))
                                        }
                                    },
                                    label = { Text("Start") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = quietEndText,
                                    onValueChange = { value ->
                                        if (value.length <= 5) quietEndText = value
                                        if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)) {
                                            viewModel.setNotificationPreferences(notificationPreferences.copy(quietEnd = value))
                                        }
                                    },
                                    label = { Text("End") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    SettingsCheckboxItem(
                        "Critical approval/failure bypass",
                        notificationPreferences.criticalBypassQuietHours,
                    ) {
                        viewModel.setNotificationPreferences(notificationPreferences.copy(criticalBypassQuietHours = !notificationPreferences.criticalBypassQuietHours))
                    }
                    HorizontalDivider()
                    Text(
                        "Repository/workspace overrides",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    sessionRepositoryOptions(state.sessions, state.repositories, state.repositoryRoot).forEach { repository ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(repository.label)
                                    Text(repository.id, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                            onClick = { notificationRepositoryId = repository.id },
                        )
                    }
                } else {
                    val identity = sessionRepositoryOptions(state.sessions, state.repositories, state.repositoryRoot)
                        .firstOrNull { it.id == selectedRepository }
                    Text(
                        identity?.label ?: selectedRepository,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(320.dp).padding(16.dp),
                    )
                    val override = notificationPreferences.repositoryOverrides[selectedRepository] ?: RepositoryNotificationOverride()
                    RepositoryOverrideItem("Approvals and input", override.notifyApprovals) { value ->
                        viewModel.setNotificationPreferences(notificationPreferences.withRepositoryOverride(selectedRepository, override.copy(notifyApprovals = value)))
                    }
                    RepositoryOverrideItem("Failures", override.notifyFailures) { value ->
                        viewModel.setNotificationPreferences(notificationPreferences.withRepositoryOverride(selectedRepository, override.copy(notifyFailures = value)))
                    }
                    RepositoryOverrideItem("Completions", override.notifyCompletions) { value ->
                        viewModel.setNotificationPreferences(notificationPreferences.withRepositoryOverride(selectedRepository, override.copy(notifyCompletions = value)))
                    }
                    RepositoryOverrideItem("Interruptions", override.notifyInterruptions) { value ->
                        viewModel.setNotificationPreferences(notificationPreferences.withRepositoryOverride(selectedRepository, override.copy(notifyInterruptions = value)))
                    }
                    RepositoryOverrideItem("Long-running", override.notifyLongRunning) { value ->
                        viewModel.setNotificationPreferences(notificationPreferences.withRepositoryOverride(selectedRepository, override.copy(notifyLongRunning = value)))
                    }
                    Text(
                        "Inherit uses the host or global setting. Overrides stay on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(320.dp).padding(16.dp),
                    )
                }
            } else {
                if (session != null && onSessionAction != null) {
                    val canManage = sessionCanBeManaged(session.status) && !state.submitting
                    val archiveSupported =
                        sessionActionSupported(session, state.capabilities, SessionAction.Archive)
                    val restoreSupported =
                        sessionActionSupported(session, state.capabilities, SessionAction.Restore)
                    val deleteSupported =
                        sessionActionSupported(session, state.capabilities, SessionAction.Delete)
                    if (archiveSupported || restoreSupported || deleteSupported) {
                        Text(
                            "Session",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (restoreSupported) {
                            DropdownMenuItem(
                                text = { Text("Restore session") },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                enabled = canManage,
                                onClick = {
                                    expanded = false
                                    onSessionAction(SessionAction.Restore)
                                },
                            )
                        }
                        if (archiveSupported) {
                            DropdownMenuItem(
                                text = { Text("Archive session") },
                                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                enabled = canManage,
                                onClick = {
                                    expanded = false
                                    onSessionAction(SessionAction.Archive)
                                },
                            )
                        }
                        if (deleteSupported) {
                            DropdownMenuItem(
                                text = { Text("Delete session permanently", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                enabled = canManage,
                                onClick = {
                                    expanded = false
                                    onSessionAction(SessionAction.Delete)
                                },
                            )
                        }
                        HorizontalDivider()
                    }
                }
                Text(
                    "Color mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ThemeMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.name) },
                        leadingIcon = {
                            RadioButton(
                                selected = state.themeMode == mode,
                                onClick = null,
                            )
                        },
                        onClick = {
                            viewModel.setThemeMode(mode)
                            expanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Theme") },
                    leadingIcon = { ThemePreview(state.themeId) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.themeId.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = { showingThemes = true },
                )
                DropdownMenuItem(
                    text = { Text("Activity detail") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.activityDetail.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    },
                    onClick = { showingActivityDetail = true },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Group sessions by repository")
                            Text(
                                "Active sessions appear first in each collapsible group",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        Checkbox(
                            checked = state.groupSessionsByRepository,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        viewModel.setGroupSessionsByRepository(!state.groupSessionsByRepository)
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Follow new messages") },
                    leadingIcon = {
                        Checkbox(
                            checked = state.followNewMessages,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        viewModel.setFollowNewMessages(!state.followNewMessages)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Haptic feedback") },
                    leadingIcon = {
                        Checkbox(
                            checked = state.hapticsEnabled,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        val enabled = !state.hapticsEnabled
                        hapticFeedback.performHapticFeedback(
                            if (enabled) {
                                HapticFeedbackType.ToggleOn
                            } else {
                                HapticFeedbackType.ToggleOff
                            },
                        )
                        viewModel.setHapticsEnabled(enabled)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Notifications") },
                    leadingIcon = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    trailingIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    },
                    onClick = {
                        showingNotifications = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Providers") },
                    leadingIcon = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    trailingIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    },
                    onClick = { showingProviders = true },
                )
                if (state.hasSavedConnection) {
                    DropdownMenuItem(
                        text = { Text("Diagnostics") },
                        leadingIcon = {
                            Icon(Icons.Default.Security, contentDescription = null)
                        },
                        onClick = {
                            expanded = false
                            viewModel.openDiagnostics()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("About") },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = {
                        expanded = false
                        showingAbout = true
                    },
                )
                if (state.hasSavedConnection) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Disconnect and forget host",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LinkOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            expanded = false
                            confirmForgetHost = true
                        },
                    )
                }
            }
        }
    }
    if (confirmForgetHost) {
        AlertDialog(
            onDismissRequest = { confirmForgetHost = false },
            icon = {
                Icon(
                    Icons.Default.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Disconnect and forget host?") },
            text = {
                Text(
                    "This removes ${state.host} and its encrypted token from this device. " +
                        "Foreman sessions remain on the host. You’ll need a new pairing code to reconnect.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetHost = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForgetHost = false
                        viewModel.forgetHost()
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Disconnect and forget")
                }
            },
        )
    }
    if (showingAbout) {
        AboutDialog(
            serverVersion = state.foremanVersion,
            serverReleaseBuild = state.foremanReleaseBuild,
            connected = state.connected,
            serverUpdateSupported = "serverUpdate" in state.capabilities,
            releaseUpdates = state.releaseUpdates,
            checking = state.releaseCheckLoading,
            serverUpdateCheck = state.serverUpdateCheck,
            serverUpdateOperation = state.serverUpdateOperation,
            serverUpdateLoading = state.serverUpdateLoading,
            serverUpdateError = state.serverUpdateError,
            onCheckAgain = viewModel::checkForUpdates,
            onReviewServerUpdate = viewModel::reviewServerUpdate,
            onStartServerUpdate = viewModel::startServerUpdate,
            onDismissServerUpdateReview = viewModel::dismissServerUpdateReview,
            onDismiss = { showingAbout = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutDialog(
    serverVersion: String?,
    serverReleaseBuild: Boolean?,
    connected: Boolean,
    serverUpdateSupported: Boolean,
    releaseUpdates: ReleaseUpdateSnapshot?,
    checking: Boolean,
    serverUpdateCheck: ServerUpdateCheck?,
    serverUpdateOperation: ServerUpdateOperation?,
    serverUpdateLoading: Boolean,
    serverUpdateError: String?,
    onCheckAgain: () -> Unit,
    onReviewServerUpdate: () -> Unit,
    onStartServerUpdate: () -> Unit,
    onDismissServerUpdateReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val androidAppUpdate = LocalAndroidAppUpdate.current
    val versions =
        aboutVersionInformation(
            serverVersion = serverVersion,
            connected = connected,
            clientVersion = BuildConfig.VERSION_NAME,
            clientCommit = BuildConfig.FOREMAN_BUILD_COMMIT,
            releaseBuild = BuildConfig.FOREMAN_RELEASE_BUILD,
        )
    val serverStatus =
        componentUpdateStatus(
            serverVersion,
            serverReleaseBuild,
            releaseUpdates,
            releaseUpdates?.components?.server,
            "server",
        )
    val androidStatus =
        componentUpdateStatus(
            BuildConfig.VERSION_NAME,
            BuildConfig.FOREMAN_RELEASE_BUILD,
            releaseUpdates,
            releaseUpdates?.components?.android,
            "Android APK",
        )
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("About", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.foreman_logo),
                                contentDescription = "Foreman logo",
                                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)),
                            )
                            Text("Foreman", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Created by Michael Kaltner",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                AboutVersionRow(
                                    if (connected) "Installed server" else "Last connected server",
                                    versions.server,
                                )
                                AboutUpdateStatus(serverStatus)
                                HorizontalDivider()
                                AboutVersionRow("Installed Android APK", versions.client)
                                AboutUpdateStatus(androidStatus)
                            }
                        }
                    }
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val observed = releaseUpdates?.observedAt
                            Text(
                                when {
                                    releaseUpdates?.stale == true && observed != null ->
                                        "Using stale release information observed $observed."
                                    observed != null -> "Release information checked $observed."
                                    else -> "No validated release information is cached."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(
                                onClick = onCheckAgain,
                                enabled = connected && !checking && releaseUpdates?.refreshStatus != "checking",
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (checking || releaseUpdates?.refreshStatus == "checking") "Checking…" else "Check again")
                            }
                            if (
                                serverStatus.kind == UpdateStatusKind.UpdateAvailable &&
                                    serverUpdateSupported &&
                                    (
                                        serverUpdateOperation == null ||
                                            serverUpdateOperation.phase in terminalServerUpdatePhases &&
                                            serverUpdateOperation.phase != "recoveryRequired"
                                    ) &&
                                    serverUpdateCheck == null
                            ) {
                                Button(
                                    onClick = onReviewServerUpdate,
                                    enabled = connected && !serverUpdateLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (serverUpdateLoading) "Reviewing…" else "Review server update")
                                }
                            }
                            if (
                                androidStatus.kind == UpdateStatusKind.UpdateAvailable &&
                                androidStatus.release != null &&
                                (
                                    androidAppUpdate.state.phase in setOf(
                                        ApkUpdatePhase.Idle,
                                        ApkUpdatePhase.Completed,
                                    ) ||
                                        androidAppUpdate.state.targetVersion != androidStatus.release.version
                                )
                            ) {
                                Button(
                                    onClick = { androidAppUpdate.start(androidStatus.release) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Download Android app update")
                                }
                            }
                            Text(
                                when {
                                    serverStatus.kind == UpdateStatusKind.UpdateAvailable &&
                                        androidStatus.kind == UpdateStatusKind.UpdateAvailable ->
                                        "Updates are available for both the connected server and this Android app. They are separate actions."
                                    serverStatus.kind == UpdateStatusKind.UpdateAvailable ->
                                        "The available update applies to the connected Foreman server."
                                    androidStatus.kind == UpdateStatusKind.UpdateAvailable ->
                                        "The available update applies to this Android app. It does not update the connected server."
                                    else ->
                                        "Server and Android app versions are checked separately."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    serverUpdateOperation?.let { operation ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(serverUpdatePhaseLabel(operation.phase), fontWeight = FontWeight.Bold)
                                        Text("${operation.progress}%")
                                    }
                                    LinearProgressIndicator(
                                        progress = { operation.progress / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    operation.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    operation.recoveryCommand?.let {
                                        Text("From the server, run $it.", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    if (androidAppUpdate.state.phase != ApkUpdatePhase.Idle) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "Android app update",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    AboutVersionRow(
                                        "Installed",
                                        "${androidAppUpdate.state.installedVersionName} " +
                                            "(version code ${androidAppUpdate.state.installedVersionCode})",
                                    )
                                    androidAppUpdate.state.targetVersion?.let { AboutVersionRow("Available", it) }
                                    Text(
                                        androidAppUpdatePhaseLabel(androidAppUpdate.state.phase),
                                        color = if (androidAppUpdate.state.phase == ApkUpdatePhase.Failed) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        OFFICIAL_APK_RELEASE_SOURCE_LABEL,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (
                                        androidAppUpdate.state.phase in setOf(
                                            ApkUpdatePhase.Discovering,
                                            ApkUpdatePhase.Downloading,
                                            ApkUpdatePhase.Verifying,
                                        )
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { androidAppUpdate.state.progress / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    androidAppUpdate.state.message?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    when (androidAppUpdate.state.phase) {
                                        ApkUpdatePhase.Discovering,
                                        ApkUpdatePhase.Downloading,
                                        ApkUpdatePhase.Verifying,
                                        -> FilledTonalButton(
                                            onClick = androidAppUpdate.cancel,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("Cancel download") }

                                        ApkUpdatePhase.Interrupted,
                                        ApkUpdatePhase.Failed,
                                        ApkUpdatePhase.Canceled,
                                        -> if (androidAppUpdate.state.targetVersion != null) {
                                            Button(
                                                onClick = androidAppUpdate.retry,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text("Retry download") }
                                        }

                                        ApkUpdatePhase.Ready -> Button(
                                            onClick = androidAppUpdate.install,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("Open Android installer") }

                                        ApkUpdatePhase.AwaitingInstaller -> FilledTonalButton(
                                            onClick = androidAppUpdate.install,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("Reopen Android installer") }

                                        else -> Unit
                                    }
                                    Text(
                                        "Foreman verifies the signed release and APK first. Android’s system installer always asks you to confirm installation.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    serverUpdateCheck?.let { check ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Review server update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    AboutVersionRow("Installed", check.currentVersion)
                                    AboutVersionRow("Target", check.target?.version ?: "Unavailable")
                                    Text(check.source, color = MaterialTheme.colorScheme.primary)
                                    check.target?.let { target ->
                                        TextButton(onClick = {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.releaseNotesUrl)))
                                            } catch (_: ActivityNotFoundException) {
                                                Toast.makeText(context, "No app can open release notes", Toast.LENGTH_SHORT).show()
                                            }
                                        }) { Text("Read release notes for ${target.tag}") }
                                    }
                                    if (check.blockers.isNotEmpty()) {
                                        Text("Update blocked", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                        Text(
                                            check.blockers.joinToString { blocker ->
                                                val label = when (blocker.category) {
                                                    "workingSession" -> "working session"
                                                    "waitingSession" -> "waiting session"
                                                    "pendingApproval" -> "pending approval"
                                                    else -> "pending input"
                                                }
                                                "${blocker.count} $label${if (blocker.count == 1) "" else "s"}"
                                            } + " must finish first. No transcript content is shown.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else {
                                        Text("No working, waiting, approval, or input state currently blocks activation.")
                                    }
                                    Text(
                                        "Foreman will verify and stage the release, recheck session safety, restart only foreman.service, reconnect this app, and restore the previous payload if health checking fails.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = onDismissServerUpdateReview, enabled = !serverUpdateLoading) { Text("Cancel") }
                                        Button(
                                            onClick = onStartServerUpdate,
                                            enabled = !serverUpdateLoading && check.updateAvailable && check.blockers.isEmpty(),
                                            modifier = Modifier.weight(1f),
                                        ) { Text(if (serverUpdateLoading) "Starting…" else "Install and restart") }
                                    }
                                }
                            }
                        }
                    }
                    serverUpdateError?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                    items(foremanAboutLinks) { (label, url) ->
                        FilledTonalButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
    if (androidAppUpdate.state.phase == ApkUpdatePhase.ExplainingPermission) {
        AlertDialog(
            onDismissRequest = androidAppUpdate.dismissPermission,
            icon = { Icon(Icons.Default.Security, contentDescription = null) },
            title = { Text("Allow Foreman to open the installer?") },
            text = {
                Text(
                    "Android blocks apps from opening package installers until you allow “Install unknown apps” for that app. " +
                        "This permission only lets Foreman hand the already verified APK to Android. " +
                        "Android will still show the package installer and require your confirmation.",
                )
            },
            dismissButton = {
                TextButton(onClick = androidAppUpdate.dismissPermission) { Text("Not now") }
            },
            confirmButton = {
                Button(onClick = androidAppUpdate.requestPermission) { Text("Continue to settings") }
            },
        )
    }
}

private fun androidAppUpdatePhaseLabel(phase: ApkUpdatePhase): String =
    when (phase) {
        ApkUpdatePhase.Idle -> "No app update in progress"
        ApkUpdatePhase.Discovering -> "Checking official release assets"
        ApkUpdatePhase.Downloading -> "Downloading over HTTPS"
        ApkUpdatePhase.Verifying -> "Verifying release and APK"
        ApkUpdatePhase.Ready -> "Verified and ready to install"
        ApkUpdatePhase.ExplainingPermission -> "Install permission explanation"
        ApkUpdatePhase.AwaitingPermission -> "Waiting for Install unknown apps permission"
        ApkUpdatePhase.AwaitingInstaller -> "Waiting for Android’s system installer"
        ApkUpdatePhase.Interrupted -> "Download interrupted"
        ApkUpdatePhase.Failed -> "Update rejected"
        ApkUpdatePhase.Canceled -> "Download canceled"
        ApkUpdatePhase.Completed -> "Update completed"
    }

@Composable
private fun AboutUpdateStatus(status: ComponentUpdateStatus) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(status.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        status.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status.release?.let { release ->
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseNotesUrl)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Release notes for ${release.tag}", modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun AboutVersionRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun NotificationPreferences.withRepositoryOverride(
    identity: String,
    override: RepositoryNotificationOverride,
): NotificationPreferences {
    val empty =
        override.notifyApprovals == null && override.notifyFailures == null &&
            override.notifyCompletions == null && override.notifyInterruptions == null &&
            override.notifyLongRunning == null
    val next = repositoryOverrides.toMutableMap()
    if (empty) next.remove(identity) else next[identity] = override
    return copy(repositoryOverrides = next)
}

@Composable
private fun SettingsCheckboxItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onClick,
    )
}

@Composable
private fun RepositoryOverrideItem(
    label: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            Text(
                when (value) {
                    null -> "Inherit"
                    true -> "On"
                    false -> "Off"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = {
            onChange(
                when (value) {
                    null -> true
                    true -> false
                    false -> null
                },
            )
        },
    )
}

@Composable
private fun ThemePreview(themeId: ThemeId, selected: Boolean = false) {
    val palette = foremanThemePalette(themeId).light
    Surface(
        modifier = Modifier.width(44.dp).height(24.dp),
        shape = RoundedCornerShape(7.dp),
        color = palette.background,
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(palette.surface, palette.accent, palette.accentContainer).forEach { color ->
                Box(Modifier.size(9.dp).background(color, CircleShape))
            }
        }
    }
}

@Composable
private fun SessionActionsMenu(
    enabled: Boolean,
    archiveSupported: Boolean,
    restoreSupported: Boolean,
    deleteSupported: Boolean,
    onAction: (SessionAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled && (archiveSupported || restoreSupported || deleteSupported),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Session actions")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (restoreSupported) {
                DropdownMenuItem(
                    text = { Text("Restore") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAction(SessionAction.Restore)
                    },
                )
            }
            if (archiveSupported) {
            DropdownMenuItem(
                text = { Text("Archive") },
                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAction(SessionAction.Archive)
                },
                enabled = archiveSupported,
            )
            }
            if (deleteSupported) {
            DropdownMenuItem(
                text = { Text("Delete permanently") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAction(SessionAction.Delete)
                },
                enabled = deleteSupported,
            )
            }
        }
    }
}

@Composable
private fun SessionActionDialog(
    pending: PendingSessionAction,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deleting = pending.action == SessionAction.Delete
    val restoring = pending.action == SessionAction.Restore
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deleting) "Delete session permanently?" else if (restoring) "Restore session?" else "Archive session?") },
        text = {
            Text(
                if (deleting) {
                    "\u201c${pending.sessionTitle}\u201d and any sessions it spawned will be permanently " +
                        "deleted. This cannot be undone."
                } else if (restoring) {
                    "\u201c${pending.sessionTitle}\u201d will return to normal Codex sessions."
                } else {
                    "\u201c${pending.sessionTitle}\u201d will be removed from the active list. " +
                        "It can be restored from the Codex archive."
                },
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                colors =
                    if (deleting) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
            ) {
                Text(if (deleting) "Delete permanently" else if (restoring) "Restore" else "Archive")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

@Composable
private fun ProviderBadge(provider: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            providerDisplayName(provider),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusPill(status: String) {
    val semantic = LocalForemanColors.current
    val (color, contentColor) = when (status) {
        "working" -> semantic.workingContainer to semantic.working
        "waiting" -> semantic.attentionContainer to semantic.attention
        "failed" -> semantic.failureContainer to semantic.failure
        "completed" -> semantic.successContainer to semantic.success
        "interrupted" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        when (status) {
            "working" -> "ACTIVE · Working"
            "waiting" -> "ATTENTION · Waiting"
            "failed" -> "FAILED"
            "completed" -> "COMPLETED"
            else -> status.uppercase()
        },
        color = contentColor,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun ConnectionBanner(message: String?, reconnect: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message ?: "Disconnected",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            FilledTonalButton(onClick = reconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
    }
}

@Composable
private fun NewSessionDialog(
    repositories: List<RepositoryInfo>,
    repositoryRoot: String,
    providers: List<ProviderInfo>,
    providerCatalogLoaded: Boolean,
    models: List<ModelInfo>,
    accessLevels: List<AccessLevelInfo>,
    claudeModels: List<ModelInfo>,
    claudePermissionModes: List<PermissionModeInfo>,
    initialProvider: String,
    initialModel: String?,
    initialEffort: String?,
    initialAccessLevel: String?,
    initialClaudeModel: String,
    initialClaudePermissionMode: String,
    onDismiss: () -> Unit,
    onStart: (String, String, String, String?, String?, String?) -> Unit,
) {
    val enabledProviders = providers.filter { it.enabled }
    var repositoryId by remember(repositories) { mutableStateOf(".") }
    var providerChoice by remember(enabledProviders, initialProvider) {
        mutableStateOf(
            enabledProviders.firstOrNull { it.id == initialProvider }?.id
                ?: enabledProviders.firstOrNull()?.id ?: PROVIDER_CODEX,
        )
    }
    val provider = newSessionProviderSelection(providers, providerCatalogLoaded, providerChoice)
        ?: providerChoice
    var initialPrompt by remember(provider) { mutableStateOf("") }
    var modelId by remember(models, initialModel) {
        mutableStateOf(models.firstOrNull { it.id == initialModel }?.id ?: models.firstOrNull { it.isDefault }?.id ?: models.firstOrNull()?.id)
    }
    val selectedModel = models.firstOrNull { it.id == modelId }
    var effort by remember(selectedModel, initialEffort) { mutableStateOf(selectedModel?.let { compatibleEffort(it, initialEffort) }) }
    var accessLevel by remember(accessLevels, initialAccessLevel) {
        mutableStateOf(accessLevels.firstOrNull { it.id == initialAccessLevel }?.id ?: accessLevels.firstOrNull { it.id == "ask" }?.id ?: accessLevels.firstOrNull()?.id)
    }
    var claudeModel by remember(claudeModels, initialClaudeModel) {
        mutableStateOf(
            claudeModels.firstOrNull { it.id == initialClaudeModel }?.id
                ?: claudeModels.firstOrNull()?.id ?: "sonnet",
        )
    }
    var permissionMode by remember(claudePermissionModes, initialClaudePermissionMode) {
        mutableStateOf(
            claudePermissionModes.firstOrNull {
                it.id == initialClaudePermissionMode && !it.highRisk
            }?.id ?: "default",
        )
    }
    var pendingHighRiskMode by remember { mutableStateOf<String?>(null) }
    val rootRepository = repositories.firstOrNull { it.id == "." }
    val providerInfo = enabledProviders.firstOrNull { it.id == provider }
    val providerUnavailable = providerInfo?.available != true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (providerCatalogLoaded && enabledProviders.size > 1) {
                    NewSessionOptionMenu(
                        "Provider",
                        providerInfo?.displayName ?: providerDisplayName(provider),
                        enabledProviders.map { it.id to (it.displayName + if (it.available) "" else " · unavailable") },
                    ) { providerChoice = it }
                }
                if (!providerCatalogLoaded) {
                    Text("Loading enabled providers…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (enabledProviders.isEmpty()) {
                    Text("No enabled provider is available. Enable one in provider settings.", color = MaterialTheme.colorScheme.error)
                } else if (providerUnavailable) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${providerDisplayName(provider)} is unavailable",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                providerUnavailableDescription(providerInfo?.unavailableReason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                if (repositories.isEmpty()) {
                    Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 2.dp) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No Git repositories yet", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Start in the configured workspace folder instead. You can initialize Git later if you need version control.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (repositoryRoot.isNotBlank()) {
                                Text(
                                    repositoryRoot,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Workspace", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.height(160.dp)) {
                            item(key = "workspace-root") {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { repositoryId = "." }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = repositoryId == ".", onClick = { repositoryId = "." })
                                    Column(Modifier.weight(1f)) {
                                        Text("Workspace root", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            when {
                                                rootRepository != null -> "${repositoryRoot.ifBlank { "." }} · ${rootRepository.branch}"
                                                repositoryRoot.isBlank() -> "No Git repository"
                                                else -> "$repositoryRoot · no Git repository"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                            items(repositories.filter { it.id != "." }, key = { it.id }) { repository ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { repositoryId = repository.id }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = repositoryId == repository.id, onClick = { repositoryId = repository.id })
                                    Column(Modifier.weight(1f)) {
                                        Text(repository.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${repository.path} · ${repository.branch}" + if (repository.dirty) " · dirty" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                if (provider == PROVIDER_CLAUDE_CODE && !providerUnavailable) {
                    OutlinedTextField(
                        value = initialPrompt,
                        onValueChange = { initialPrompt = it },
                        label = { Text("Initial prompt") },
                        placeholder = { Text("What should Claude work on?") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val selectedPermission = claudePermissionModes.firstOrNull { it.id == permissionMode }
                    NewSessionOptionMenu(
                        "Permission mode",
                        selectedPermission?.displayName ?: "Default",
                        claudePermissionModes.map { it.id to (it.displayName + if (it.highRisk) " · HIGH RISK" else "") },
                    ) { selected ->
                        if (claudePermissionModes.firstOrNull { it.id == selected }?.highRisk == true) {
                            pendingHighRiskMode = selected
                        } else permissionMode = selected
                    }
                    selectedPermission?.description?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    NewSessionOptionMenu(
                        "Claude model",
                        claudeModels.firstOrNull { it.id == claudeModel }?.displayName ?: claudeModel,
                        claudeModels.map { it.id to it.displayName },
                    ) { claudeModel = it }
                    Text(
                        "Claude images are not supported by the current provider protocol.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (provider == PROVIDER_CODEX && accessLevels.isNotEmpty()) {
                    val selectedAccess = accessLevels.firstOrNull { it.id == accessLevel }
                    NewSessionOptionMenu("Access", selectedAccess?.displayName ?: "Default access", accessLevels.map { it.id to it.displayName }) { accessLevel = it }
                    if (accessLevel == "full") {
                        Text(
                            "Full access allows commands outside the workspace without approval.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalForemanColors.current.fullAccess,
                        )
                    }
                }
                if (provider == PROVIDER_CODEX && models.isNotEmpty()) {
                    NewSessionOptionMenu("Model", selectedModel?.displayName ?: "Default model", models.map { it.id to it.displayName }) { selected ->
                        modelId = selected
                        effort = models.firstOrNull { it.id == selected }?.let { compatibleEffort(it, null) }
                    }
                }
                if (provider == PROVIDER_CODEX && !selectedModel?.reasoningEfforts.isNullOrEmpty()) {
                    NewSessionOptionMenu("Reasoning", effort?.replaceFirstChar { it.uppercase() } ?: "Default", selectedModel.reasoningEfforts.map { it to it.replaceFirstChar { character -> character.uppercase() } }) { effort = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !providerUnavailable &&
                    (provider == PROVIDER_CODEX || initialPrompt.isNotBlank()),
                onClick = {
                    onStart(
                        provider,
                        repositoryId,
                        initialPrompt,
                        if (provider == PROVIDER_CLAUDE_CODE) claudeModel else modelId,
                        if (provider == PROVIDER_CODEX) effort else null,
                        if (provider == PROVIDER_CLAUDE_CODE) permissionMode else accessLevel,
                    )
                },
            ) {
                Text(
                    if (provider == PROVIDER_CLAUDE_CODE && shouldShowProviderIdentity(providers, providerCatalogLoaded)) "Start Claude session"
                    else if (provider == PROVIDER_CLAUDE_CODE) "Start session"
                    else if (repositories.isEmpty()) "Start in workspace" else "Create",
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pendingHighRiskMode != null) {
        AlertDialog(
            onDismissRequest = { pendingHighRiskMode = null },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = LocalForemanColors.current.fullAccess) },
            title = { Text("Bypass Claude permissions?") },
            text = {
                Text(
                    "This high-risk mode bypasses Claude Code permission checks. It is never selected automatically.",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingHighRiskMode = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionMode = requireNotNull(pendingHighRiskMode)
                        pendingHighRiskMode = null
                    },
                ) { Text("Use high-risk mode", color = LocalForemanColors.current.fullAccess) }
            },
        )
    }
}

@Composable
private fun NewSessionOptionMenu(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                        trailingIcon = {
                            if (optionLabel == selectedLabel) Icon(Icons.Default.Check, contentDescription = "Selected")
                        },
                    )
                }
            }
        }
    }
}
