package net.kaltner.foreman

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

internal data class AccentTones(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
    val secondary: Color,
)

internal data class AccentPalette(val light: AccentTones, val dark: AccentTones)

internal fun accentPalette(color: AccentColor): AccentPalette =
    when (color) {
        AccentColor.Purple ->
            AccentPalette(
                light =
                    AccentTones(
                        Color(0xFF6C2BD9),
                        Color.White,
                        Color(0xFFE9DDFF),
                        Color(0xFF22005D),
                        Color(0xFF4A18A8),
                    ),
                dark =
                    AccentTones(
                        Color(0xFFCBB4FF),
                        Color(0xFF381E72),
                        Color(0xFF4A18A8),
                        Color(0xFFEADDFF),
                        Color(0xFF9D76F2),
                    ),
            )
        AccentColor.Blue ->
            AccentPalette(
                light =
                    AccentTones(
                        Color(0xFF2563EB),
                        Color.White,
                        Color(0xFFDBEAFE),
                        Color(0xFF172554),
                        Color(0xFF1D4ED8),
                    ),
                dark =
                    AccentTones(
                        Color(0xFF93C5FD),
                        Color(0xFF1E3A8A),
                        Color(0xFF1E40AF),
                        Color(0xFFDBEAFE),
                        Color(0xFF60A5FA),
                    ),
            )
        AccentColor.Teal ->
            AccentPalette(
                light =
                    AccentTones(
                        Color(0xFF0F766E),
                        Color.White,
                        Color(0xFFCCFBF1),
                        Color(0xFF042F2E),
                        Color(0xFF0D9488),
                    ),
                dark =
                    AccentTones(
                        Color(0xFF5EEAD4),
                        Color(0xFF134E4A),
                        Color(0xFF115E59),
                        Color(0xFFCCFBF1),
                        Color(0xFF2DD4BF),
                    ),
            )
        AccentColor.Green ->
            AccentPalette(
                light =
                    AccentTones(
                        Color(0xFF15803D),
                        Color.White,
                        Color(0xFFDCFCE7),
                        Color(0xFF052E16),
                        Color(0xFF166534),
                    ),
                dark =
                    AccentTones(
                        Color(0xFF86EFAC),
                        Color(0xFF14532D),
                        Color(0xFF166534),
                        Color(0xFFDCFCE7),
                        Color(0xFF4ADE80),
                    ),
            )
        AccentColor.Orange ->
            AccentPalette(
                light =
                    AccentTones(
                        Color(0xFFC2410C),
                        Color.White,
                        Color(0xFFFFEDD5),
                        Color(0xFF431407),
                        Color(0xFFEA580C),
                    ),
                dark =
                    AccentTones(
                        Color(0xFFFDBA74),
                        Color(0xFF7C2D12),
                        Color(0xFF9A3412),
                        Color(0xFFFFEDD5),
                        Color(0xFFFB923C),
                    ),
            )
        AccentColor.Red ->
            AccentPalette(
                light =
                    AccentTones(
                        Color(0xFFB91C1C),
                        Color.White,
                        Color(0xFFFEE2E2),
                        Color(0xFF450A0A),
                        Color(0xFFDC2626),
                    ),
                dark =
                    AccentTones(
                        Color(0xFFFCA5A5),
                        Color(0xFF7F1D1D),
                        Color(0xFF991B1B),
                        Color(0xFFFEE2E2),
                        Color(0xFFF87171),
                    ),
            )
        AccentColor.Pink ->
            AccentPalette(
                light =
                    AccentTones(
                        Color(0xFFBE185D),
                        Color.White,
                        Color(0xFFFCE7F3),
                        Color(0xFF500724),
                        Color(0xFFDB2777),
                    ),
                dark =
                    AccentTones(
                        Color(0xFFF9A8D4),
                        Color(0xFF831843),
                        Color(0xFF9D174D),
                        Color(0xFFFCE7F3),
                        Color(0xFFF472B6),
                    ),
            )
    }

internal fun mutedAccentContainer(tones: AccentTones, darkTheme: Boolean): Color =
    tones.primary
        .copy(alpha = if (darkTheme) 0.14f else 0.10f)
        .compositeOver(if (darkTheme) Color(0xFF3D3A43) else Color(0xFFE7E0EB))

internal fun foremanColorScheme(accentColor: AccentColor, darkTheme: Boolean) =
    accentPalette(accentColor).let { palette ->
        val tones = if (darkTheme) palette.dark else palette.light
        if (darkTheme) {
            darkColorScheme(
                primary = tones.primary,
                onPrimary = tones.onPrimary,
                primaryContainer = tones.container,
                onPrimaryContainer = tones.onContainer,
                secondary = tones.secondary,
                secondaryContainer = mutedAccentContainer(tones, darkTheme = true),
                onSecondaryContainer = tones.onContainer,
                background = Color(0xFF111827),
                surface = Color(0xFF182235),
                surfaceVariant = Color(0xFF374151),
                onBackground = Color(0xFFF6F7F9),
                onSurface = Color(0xFFF6F7F9),
            )
        } else {
            lightColorScheme(
                primary = tones.primary,
                onPrimary = tones.onPrimary,
                primaryContainer = tones.container,
                onPrimaryContainer = tones.onContainer,
                secondary = tones.secondary,
                secondaryContainer = mutedAccentContainer(tones, darkTheme = false),
                onSecondaryContainer = tones.onContainer,
                background = Color(0xFFF6F7F9),
                surface = Color.White,
                surfaceVariant = Color(0xFFE7E0EB),
                onBackground = Color(0xFF111827),
                onSurface = Color(0xFF111827),
            )
        }
    }

class MainActivity : ComponentActivity() {
    private val foremanViewModel: ForemanViewModel by viewModels()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            foremanViewModel.setMonitorActiveTurns(granted)
            if (!granted) foremanViewModel.notificationPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ForemanApp(foremanViewModel, ::requestTurnMonitoring)
        }
        openNotificationSession(intent)
    }

    override fun onResume() {
        super.onResume()
        foremanViewModel.onNotificationPermissionState(notificationPermissionGranted())
        foremanViewModel.onForeground()
    }

    override fun onStop() {
        foremanViewModel.onBackground()
        super.onStop()
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

    private fun openNotificationSession(intent: Intent?) {
        intent?.getStringExtra(TurnMonitorService.EXTRA_SESSION_ID)?.let {
            foremanViewModel.openSessionFromNotification(
                intent.getStringExtra(TurnMonitorService.EXTRA_HOST_ID),
                intent.getStringExtra(TurnMonitorService.EXTRA_PROVIDER) ?: PROVIDER_CODEX,
                it,
                intent.getStringExtra(TurnMonitorService.EXTRA_APPROVAL_ID),
            )
        }
    }
}

internal enum class Screen { Setup, Overview, Dashboard, Sessions, Detail, Diagnostics }

internal fun reconnectDestination(current: Screen, selectedSessionId: String?): Screen =
    when {
        selectedSessionId != null -> Screen.Detail
        current == Screen.Dashboard -> Screen.Dashboard
        else -> Screen.Sessions
    }

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

internal enum class SessionAction { Archive, Delete }

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
    status != "working" && status != "waiting"

internal fun sessionActionSupported(capabilities: Set<String>, action: SessionAction): Boolean =
    capabilities.contains(if (action == SessionAction.Archive) "archive" else "delete")

internal fun sessionActionSupported(
    session: SessionSummary,
    capabilities: Set<String>,
    action: SessionAction,
): Boolean =
    if (sessionProvider(session) == PROVIDER_CLAUDE_CODE) {
        action == SessionAction.Delete && "session.delete" in session.capabilities
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
    } else {
        accessLevel?.let { put("accessLevel", it) }
        model?.let { put("model", it) }
        effort?.let { put("reasoningEffort", it) }
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
    val requested = session?.accessLevel ?: composerAccessLevel
    val selected =
        available.firstOrNull { it.id == requested }
            ?: available.firstOrNull { it.id == "ask" }
            ?: available.firstOrNull()
    return copy(
        accessLevels = available,
        composerAccessLevel = selected?.id ?: requested,
    )
}

internal fun UiState.withModelsAndSessionRoute(
    available: List<ModelInfo>,
    session: SessionSummary?,
): UiState {
    val requestedModel = session?.model ?: composerModel
    val selectedModel =
        available.firstOrNull { it.id == requestedModel }
            ?: available.firstOrNull { it.isDefault }
            ?: available.firstOrNull()
    return copy(
        models = available,
        composerModel = selectedModel?.id ?: requestedModel,
        composerEffort =
            selectedModel?.let {
                compatibleEffort(it, session?.reasoningEffort ?: composerEffort)
            } ?: session?.reasoningEffort ?: composerEffort,
    )
}

internal fun UiState.withProviderRoute(session: SessionSummary?): UiState =
    if (session != null && sessionProvider(session) == PROVIDER_CLAUDE_CODE) {
        val model =
            claudeModels.firstOrNull { it.id == session.model }
                ?: claudeModels.firstOrNull { it.id == claudeComposerModel }
                ?: claudeModels.firstOrNull()
        val permission =
            claudePermissionModes.firstOrNull { it.id == session.permissionMode }
                ?: claudePermissionModes.firstOrNull { it.id == claudeComposerPermissionMode }
                ?: claudePermissionModes.firstOrNull { it.id == "default" }
                ?: claudePermissionModes.firstOrNull()
        copy(
            claudeComposerModel = model?.id ?: session.model ?: "sonnet",
            claudeComposerPermissionMode =
                permission?.id ?: session.permissionMode ?: "default",
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
    return incoming.copy(messages = messages)
}

internal fun UiState.withSynchronizedSessions(
    sessions: List<SessionSummary>,
    repositories: List<RepositoryInfo>,
    selectedSessionId: String?,
    selectedSession: SessionSummary?,
    selectedProvider: String = PROVIDER_CODEX,
): UiState {
    val reconciledSelected = reconcileSelectedSession(selected, selectedSession)
    return copy(
        sessions = sessions,
        repositories = repositories,
        selected = reconciledSelected,
        screen =
            if (selectedSessionId != null && reconciledSelected?.matches(selectedProvider, selectedSessionId) == true) {
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
    val providers: List<ProviderInfo> = defaultProviders(),
    val accountUsage: AccountUsage = AccountUsage(),
    val repositories: List<RepositoryInfo> = emptyList(),
    val selected: SessionSummary? = null,
    val composerDrafts: Map<ComposerDraftKey, String> = emptyMap(),
    val showNewSession: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val accentColor: AccentColor = AccentColor.Purple,
    val activityDetail: ActivityDetail = ActivityDetail.Focused,
    val groupSessionsByRepository: Boolean = true,
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
    val codexVersion: String?,
    val runtimeMode: String?,
    val runtimeConnected: Boolean,
)

private fun UiPreferences.searchFilters(): SessionSearchFilters =
    SessionSearchFilters(
        query = searchQuery,
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
        providers = defaultProviders(),
        accountUsage = AccountUsage(),
        repositories = emptyList(),
        selected = null,
        composerDrafts = emptyMap(),
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
        codexVersion = null,
        runtimeMode = null,
        runtimeConnected = false,
        diagnostics = emptyList(),
        diagnosticsLoading = false,
        diagnosticsError = null,
        restartPhase = RestartPhase.Idle,
    )

internal class ForemanViewModel(application: Application) : AndroidViewModel(application) {
    private val hosts = HostStore(application)
    private val overviewStore = HostOverviewStore(application)
    private val overviewLifecycle = AndroidOverviewLifecycle()
    private val notificationPreferencesStore = NotificationPreferenceStore(application)
    private var activeHost = hosts.active()
    private var preferences = PreferenceStore(application, activeHost?.id)
    private val savedPreferences = preferences.load()
    private val savedSearchFilters =
        SessionSearchFilters(
            query = savedPreferences.searchQuery,
            repository = savedPreferences.searchRepository,
            status = savedPreferences.searchStatus,
            dateRange = savedPreferences.searchDateRange,
            dateFrom = savedPreferences.searchDateFrom,
            dateTo = savedPreferences.searchDateTo,
        )
    val state =
        MutableStateFlow(
            UiState(
                displayName = activeHost?.displayName.orEmpty(),
                host = activeHost?.tcpEndpoint().orEmpty(),
                hasSavedConnection = activeHost != null,
                savedHosts = hosts.all().map(SavedHost::summary),
                activeHostId = activeHost?.id,
                composerDrafts = storedComposerDrafts(activeHost?.id, preferences.loadDrafts()),
                themeMode = savedPreferences.themeMode,
                accentColor = savedPreferences.accentColor,
                activityDetail = savedPreferences.activityDetail,
                groupSessionsByRepository = savedPreferences.groupSessionsByRepository,
                followNewMessages = savedPreferences.followNewMessages,
                hapticsEnabled = savedPreferences.hapticsEnabled,
                monitorActiveTurns = savedPreferences.monitorActiveTurns,
                notificationPreferences = notificationPreferencesStore.load(activeHost?.id),
                hostNotificationOverride = notificationPreferencesStore.hasHostOverride(activeHost?.id),
                composerAccessLevel = savedPreferences.accessLevel,
                composerModel = savedPreferences.model,
                composerEffort = savedPreferences.reasoningEffort,
                selectedNewSessionProvider = savedPreferences.lastProvider,
                claudeComposerModel = savedPreferences.claudeModel,
                claudeComposerPermissionMode = savedPreferences.claudePermissionMode,
                searchFilters = savedSearchFilters,
                showSearch = sessionSearchActive(savedSearchFilters),
                pinnedSessionIds = savedPreferences.pinnedSessionIds,
                hiddenSessionIds = savedPreferences.hiddenSessionIds,
                overviewSnapshots = overviewStore.all().filterKeys { id -> hosts.load(id) != null },
            ),
        )
    private val json = Json { ignoreUnknownKeys = true }
    private var reconnectJob: Job? = null
    private var restartReconnectJob: Job? = null
    private var restartTimeoutJob: Job? = null
    private var restartRequested = false
    private var diagnosticsReturnScreen = Screen.Sessions
    private val sessionDiscoveryLock = Any()
    private val sessionDiscoveryQueue = SessionDiscoveryQueue()
    private var sessionDiscoveryJob: Job? = null
    private var notificationHostId: String? = null
    private var notificationProvider: String = PROVIDER_CODEX
    private var notificationSessionId: String? = null
    private var notificationApprovalId: String? = null
    private var restorationProvider: String = savedPreferences.selectedSessionProvider
    private var restorationSessionId: String? = savedPreferences.selectedSessionId
    private var searchJob: Job? = null
    private var workspaceFileJob: Job? = null
    private var lastSearchRequestKey = ""
    private val overviewNavigation = OverviewNavigationState()
    private var overviewJob: Job? = null
    private val overviewClient = ForemanClient(viewModelScope, onEvent = {}, onDisconnect = {})
    private val client = ForemanClient(
        viewModelScope,
        onEvent = ::handleEvent,
        onDisconnect = { message ->
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
            if (restartRequested) launchRestartReconnect()
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
    fun setNewSession(open: Boolean) = state.update { it.copy(showNewSession = open) }

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
                val selected = state.value.selected?.takeIf { session ->
                    providers.any {
                        it.id == sessionProvider(session) && it.enabled && it.available
                    }
                }
                state.update {
                    it.copy(
                        providers = providers,
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

    fun showOverview() = state.update { it.copy(screen = dashboardBackDestination(), selected = null, error = null) }

    fun hasOverviewReturnTarget(): Boolean = overviewNavigation.hasReturnTarget()

    fun backFromOverview() {
        val target = overviewNavigation.consume(state.value.activeHostId) ?: return
        when (target.screen) {
            Screen.Dashboard -> showDashboard()
            Screen.Detail -> target.sessionId?.let { openSession(it, provider = target.provider) } ?: showSessions()
            Screen.Sessions -> showSessions()
            Screen.Setup, Screen.Overview, Screen.Diagnostics -> Unit
        }
    }

    fun showDashboard() = state.update { it.copy(screen = Screen.Dashboard, selected = null, error = null) }

    fun showSessions() = state.update { it.copy(screen = Screen.Sessions, selected = null, error = null) }

    fun openOverviewHost(hostId: String) {
        if (hostId == state.value.activeHostId) {
            showDashboard()
        } else {
            switchHost(hostId, Screen.Dashboard)
        }
    }

    fun reconnectOverviewHost(hostId: String) {
        if (hostId == state.value.activeHostId) reconnect() else openOverviewHost(hostId)
    }

    fun openOverviewSession(item: OverviewAttentionItem) {
        overviewNavigation.clear()
        notificationHostId = item.hostId
        notificationProvider = item.provider
        notificationSessionId = item.sessionId
        notificationApprovalId = item.approvalId
        if (item.hostId == state.value.activeHostId && state.value.connected) {
            notificationHostId = null
            notificationSessionId = null
            openSession(item.sessionId, focusedApprovalId = item.approvalId, provider = item.provider)
        } else if (item.hostId == state.value.activeHostId) {
            reconnect()
        } else {
            switchHost(item.hostId)
        }
    }

    fun setSearchOpen(open: Boolean) {
        state.update { it.copy(showSearch = open, showSearchFilters = false) }
    }

    fun setSearchFilters(filters: SessionSearchFilters, immediate: Boolean = false) {
        val requestChanged = sessionSearchRequestKey(state.value.searchFilters) != sessionSearchRequestKey(filters)
        preferences.setSessionSearch(filters)
        state.update { it.copy(searchFilters = filters, searchError = null) }
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
        if (!state.value.connected || "search" !in state.value.capabilities) {
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

    fun setComposerModel(id: String) {
        val current = state.value
        val model = current.models.firstOrNull { it.id == id } ?: return
        val effort = compatibleEffort(model, current.composerEffort)
        val previousModel = current.composerModel
        val previousEffort = current.composerEffort
        val previousSessionModel = current.selected?.model
        val previousSessionEffort = current.selected?.reasoningEffort
        preferences.setModelRoute(model.id, effort)
        state.update { it.copy(composerModel = model.id, composerEffort = effort) }
        val sessionId = current.selected?.id ?: return
        if (!current.connected || "threadSettings" !in current.capabilities) return
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "session.settings",
                    sessionSettingsPayload(sessionId, model = model.id, effort = effort),
                )
            }.onSuccess {
                state.update {
                    val selected = it.selected
                    it.copy(
                        submitting = false,
                        selected =
                            if (selected?.id == sessionId) {
                                selected.copy(model = model.id, reasoningEffort = effort)
                            } else {
                                selected
                            },
                    )
                }
            }.onFailure { error ->
                preferences.setModelRoute(previousModel, previousEffort)
                state.update {
                    val selected = it.selected
                    it.copy(
                        submitting = false,
                        composerModel = previousModel,
                        composerEffort = previousEffort,
                        selected =
                            if (selected?.id == sessionId) {
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
        val previous = current.composerAccessLevel
        val previousSessionAccess = current.selected?.accessLevel
        preferences.setAccessLevel(id)
        state.update { it.copy(composerAccessLevel = id) }
        val sessionId = current.selected?.id ?: return
        if (!current.connected || "threadSettings" !in current.capabilities) return
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "session.settings",
                    sessionSettingsPayload(sessionId, accessLevel = id),
                )
            }.onSuccess {
                state.update {
                    val selected = it.selected
                    it.copy(
                        submitting = false,
                        selected =
                            if (selected?.id == sessionId) {
                                selected.copy(accessLevel = id)
                            } else {
                                selected
                            },
                    )
                }
            }.onFailure { error ->
                preferences.setAccessLevel(previous)
                state.update {
                    val selected = it.selected
                    it.copy(
                        submitting = false,
                        composerAccessLevel = previous,
                        selected =
                            if (selected?.id == sessionId) {
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
        val previous = current.composerEffort
        val previousSessionEffort = current.selected?.reasoningEffort
        preferences.setModelRoute(model.id, effort)
        state.update { it.copy(composerEffort = effort) }
        val sessionId = current.selected?.id ?: return
        if (!current.connected || "threadSettings" !in current.capabilities) return
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                client.request(
                    "session.settings",
                    sessionSettingsPayload(sessionId, model = model.id, effort = effort),
                )
            }.onSuccess {
                state.update {
                    val selected = it.selected
                    it.copy(
                        submitting = false,
                        selected =
                            if (selected?.id == sessionId) {
                                selected.copy(reasoningEffort = effort)
                            } else {
                                selected
                            },
                    )
                }
            }.onFailure { error ->
                preferences.setModelRoute(model.id, previous)
                state.update {
                    val selected = it.selected
                    it.copy(
                        submitting = false,
                        composerEffort = previous,
                        selected =
                            if (selected?.id == sessionId) {
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
        if (state.value.claudeModels.none { it.id == id }) return
        val permission = state.value.claudeComposerPermissionMode
        preferences.setClaudeRoute(id, permission)
        state.update {
            it.copy(
                claudeComposerModel = id,
                selected = it.selected?.let { selected ->
                    if (sessionProvider(selected) == PROVIDER_CLAUDE_CODE) {
                        selected.copy(model = id)
                    } else selected
                },
            )
        }
    }

    fun setClaudeComposerPermissionMode(id: String) {
        if (state.value.claudePermissionModes.none { it.id == id }) return
        val model = state.value.claudeComposerModel
        preferences.setClaudeRoute(model, id)
        state.update {
            it.copy(
                claudeComposerPermissionMode = id,
                selected = it.selected?.let { selected ->
                    if (sessionProvider(selected) == PROVIDER_CLAUDE_CODE) {
                        selected.copy(permissionMode = id)
                    } else selected
                },
            )
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

    fun setAccentColor(color: AccentColor) {
        preferences.setAccentColor(color)
        state.update { it.copy(accentColor = color) }
    }

    fun setActivityDetail(detail: ActivityDetail) {
        preferences.setActivityDetail(detail)
        state.update { it.copy(activityDetail = detail) }
    }

    fun setGroupSessionsByRepository(enabled: Boolean) {
        preferences.setGroupSessionsByRepository(enabled)
        state.update { it.copy(groupSessionsByRepository = enabled) }
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
        if (!granted && state.value.monitorActiveTurns) setMonitorActiveTurns(false)
    }

    fun openSessionFromNotification(
        hostId: String?,
        provider: String,
        id: String,
        approvalId: String? = null,
    ) {
        if (hostId == null || hosts.load(hostId) == null) return
        notificationHostId = hostId
        notificationProvider = provider
        notificationSessionId = id
        notificationApprovalId = approvalId
        if (state.value.activeHostId != hostId) {
            switchHost(hostId)
        } else if (state.value.connected) {
            notificationHostId = null
            notificationSessionId = null
            openSession(id, focusedApprovalId = approvalId, provider = provider)
        } else {
            reconnect()
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
                restorationProvider = restored.selectedSessionProvider
                restorationSessionId = restored.selectedSessionId
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
                        providers = defaultProviders(),
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
                        accentColor = restored.accentColor,
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
                        selectedNewSessionProvider = restored.lastProvider,
                        claudeComposerModel = restored.claudeModel,
                        claudeComposerPermissionMode = restored.claudePermissionMode,
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
        if (!restartRequested || restartReconnectJob?.isActive == true) return
        reconnectJob?.cancel()
        reconnectJob = null
        state.update { it.copy(restartPhase = RestartPhase.Reconnecting, connectionStatus = "reconnecting") }
        restartReconnectJob =
            viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 45_000
                val saved = state.value.activeHostId?.let(hosts::load)
                while (saved != null && restartRequested && System.currentTimeMillis() < deadline) {
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
                        refreshDiagnostics()
                        return@launch
                    }
                    delay(750)
                }
                restartRequested = false
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
                )
            }
            return
        }
        activeHost = next
        if (next == null) {
            restorationProvider = PROVIDER_CODEX
            restorationSessionId = null
            preferences = PreferenceStore(getApplication(), null)
            state.update { it.withForgottenConnection() }
            return
        }
        state.update {
            it.copy(
                overviewSnapshots = it.overviewSnapshots - hostId,
                composerDrafts = it.composerDrafts.filterKeys { key -> key.hostId != hostId },
            )
        }
        activateSavedHost(next)
    }

    fun switchHost(hostId: String, destination: Screen = Screen.Sessions) {
        if (hostId == state.value.activeHostId) return
        val selected = hosts.select(hostId) ?: return
        overviewNavigation.invalidateForHost(selected.id)
        stopActiveHost()
        activeHost = selected
        activateSavedHost(selected, destination)
    }

    private fun stopActiveHost() {
        reconnectJob?.cancel()
        reconnectJob = null
        restartReconnectJob?.cancel()
        restartReconnectJob = null
        restartTimeoutJob?.cancel()
        restartTimeoutJob = null
        restartRequested = false
        searchJob?.cancel()
        searchJob = null
        synchronized(sessionDiscoveryLock) {
            sessionDiscoveryJob?.cancel()
            sessionDiscoveryJob = null
            sessionDiscoveryQueue.clear()
        }
        client.close()
        TurnMonitorService.stopAll(getApplication())
        state.value.activeHostId?.let { hosts.updateConnection(it, "disconnected") }
    }

    private fun activateSavedHost(saved: SavedHost, destination: Screen = Screen.Sessions) {
        overviewNavigation.invalidateForHost(saved.id)
        preferences = PreferenceStore(getApplication(), saved.id)
        val restored = preferences.load()
        restorationProvider = restored.selectedSessionProvider
        restorationSessionId = restored.selectedSessionId
        val filters = restored.searchFilters()
        state.update {
            it.copy(
                screen = if (notificationSessionId == null) destination else Screen.Detail,
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
                providers = defaultProviders(),
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
                highlightedItemId = null,
                focusedApprovalId = null,
                approvals = emptyList(),
                submittingApprovalIds = emptySet(),
                approvalErrors = emptyMap(),
                inputs = emptyList(),
                submittingInputIds = emptySet(),
                inputErrors = emptyMap(),
                foremanVersion = null,
                codexVersion = null,
                runtimeMode = null,
                runtimeConnected = false,
                diagnostics = emptyList(),
                diagnosticsLoading = false,
                diagnosticsError = null,
                restartPhase = RestartPhase.Idle,
                themeMode = restored.themeMode,
                accentColor = restored.accentColor,
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
                selectedNewSessionProvider = restored.lastProvider,
                claudeComposerModel = restored.claudeModel,
                claudeComposerPermissionMode = restored.claudePermissionMode,
            )
        }
        launchReconnect(saved)
    }

    fun onForeground() {
        overviewLifecycle.onForeground()
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
        overviewJob?.cancel()
        overviewJob = null
        overviewClient.close()
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
        val selectedId =
            notificationSessionId ?: state.value.selected?.id ?: restorationSessionId
        val selectedProvider =
            if (notificationSessionId != null) notificationProvider
            else state.value.selected?.let(::sessionProvider) ?: restorationProvider
        state.update { it.copy(loading = true, error = null, connectionStatus = "reconnecting") }
        runCatching {
            client.authenticate(saved.tcpEndpoint(), saved.deviceToken)
            if (state.value.activeHostId != saved.id) return
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
                    screen = reconnectDestination(state.value.screen, selectedId),
                    error = null,
                    capabilities = client.capabilities,
                )
            }
            synchronizeSessions(selectedId, selectedProvider)
            notificationHostId = null
            notificationSessionId = null
            state.update { it.copy(focusedApprovalId = notificationApprovalId) }
            notificationApprovalId = null
        }.onFailure { error ->
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

    fun openSession(
        id: String,
        highlightedItemId: String? = null,
        focusedApprovalId: String? = null,
        provider: String = PROVIDER_CODEX,
    ) {
        restorationProvider = provider
        restorationSessionId = id
        preferences.setSelectedSession(provider, id)
        viewModelScope.launch {
            state.update { it.copy(screen = Screen.Detail, loading = true, error = null, highlightedItemId = highlightedItemId, focusedApprovalId = focusedApprovalId) }
            runCatching {
                val selected = readSession(provider, id)
                state.update {
                    it.copy(selected = selected, loading = false).withProviderRoute(selected)
                }
                monitorIfActive(selected)
            }.onFailure(::fail)
        }
    }

    private suspend fun synchronizeSessions(
        selectedSessionId: String? = null,
        selectedProvider: String = PROVIDER_CODEX,
    ) {
        state.update { it.copy(loading = true, error = null) }
        val providers = runCatching {
            client.request("provider.list").payload.getValue("providers").jsonArray
                .map { json.decodeFromJsonElement<ProviderInfo>(it) }
        }.getOrElse { defaultProviders() }
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
                    if (codexAvailable) listSessions(PROVIDER_CODEX) else emptyList()
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
                val claudeSessions =
                    if (claudeAvailable) {
                        runCatching { listSessions(PROVIDER_CLAUDE_CODE) }.getOrElse { emptyList() }
                    } else {
                        emptyList()
                    }
                val sessions = codexSessionsRequest.await() + claudeSessions
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
                    codexVersion = codexStatus?.get("version")?.jsonPrimitive?.content,
                    runtimeMode = codexStatus?.get("mode")?.jsonPrimitive?.content,
                    runtimeConnected = codexStatus?.get("connected")?.jsonPrimitive?.content == "true",
                )
        }
        val sessions = snapshot.sessions
        var selectedReadError: String? = null
        val selected = selectedSessionId?.let {
            val selectedProviderAvailable = providers.any { provider ->
                provider.id == selectedProvider && provider.enabled && provider.available
            }
            if (!selectedProviderAvailable) {
                null
            } else if (selectedProvider == PROVIDER_CLAUDE_CODE) {
                runCatching { readSession(selectedProvider, it) }.getOrElse { error ->
                    selectedReadError =
                        "Claude Code session history is unavailable: ${error.message ?: "provider unavailable"}"
                    null
                }
            } else {
                readSession(selectedProvider, it)
            }
        }
        state.update {
            it.withSynchronizedSessions(
                    sessions = sessions,
                    repositories = snapshot.repositories,
                    selectedSessionId = selectedSessionId,
                    selectedSession = selected,
                    selectedProvider = selectedProvider,
                )
                .copy(
                    providers = snapshot.providers,
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
        val validIds = sessions.mapTo(mutableSetOf()) { it.providerKey() }
        preferences.retainSessionIds(validIds)
        state.update {
            it.copy(
                pinnedSessionIds = it.pinnedSessionIds.intersect(validIds),
                hiddenSessionIds = it.hiddenSessionIds.intersect(validIds),
            )
        }
        scheduleSearch(0)
        startGlobalTurnMonitoring()
        updateActiveOverview()
    }

    private suspend fun listSessions(provider: String = PROVIDER_CODEX): List<SessionSummary> {
        val response =
            if (provider == PROVIDER_CLAUDE_CODE) {
                client.request(
                    "provider.session.list",
                    buildJsonObject { put("provider", provider) },
                )
            } else {
                client.request("session.list")
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

    private suspend fun readSession(provider: String, id: String): SessionSummary {
        val summary = state.value.sessions.firstOrNull { it.matches(provider, id) }
        val providerPayload = buildJsonObject {
            put("provider", provider)
            put("sessionId", id)
            if (provider == PROVIDER_CLAUDE_CODE) {
                put("repositoryId", summary?.repositoryId ?: ".")
            }
        }
        client.request(
            if (provider == PROVIDER_CLAUDE_CODE) "provider.session.subscribe" else "session.subscribe",
            if (provider == PROVIDER_CLAUDE_CODE) providerPayload else buildJsonObject { put("sessionId", id) },
        )
        val response =
            client.request(
                if (provider == PROVIDER_CLAUDE_CODE) "provider.session.read" else "session.read",
                if (provider == PROVIDER_CLAUDE_CODE) providerPayload else buildJsonObject { put("sessionId", id) },
            )
        return json.decodeFromJsonElement(
            response.payload.getValue("session"),
        )
    }

    fun backToSessions() {
        restorationSessionId = null
        preferences.setSelectedSession(PROVIDER_CODEX, null)
        state.update { it.copy(screen = Screen.Sessions, selected = null, error = null, highlightedItemId = null, focusedApprovalId = null) }
        refresh()
    }

    fun requestSessionAction(session: SessionSummary, action: SessionAction) {
        if (!sessionActionSupported(session, state.value.capabilities, action)) {
            state.update { it.copy(error = "The connected Foreman server does not support this action.") }
            return
        }
        if (!sessionCanBeManaged(session.status)) {
            state.update {
                it.copy(error = "Interrupt the active session before archive or delete.")
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
                    ?: current.sessions.firstOrNull {
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
                client.request(
                    if (pending.provider == PROVIDER_CLAUDE_CODE) {
                        "provider.session.delete"
                    } else if (pending.action == SessionAction.Archive) {
                        "session.archive"
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
                runCatching {
                    TurnMonitorService.cancel(getApplication(), pending.sessionId, pending.provider)
                }
                state.update { current ->
                    val wasSelected = current.selected?.matches(pending.provider, pending.sessionId) == true
                    val key = providerSessionKey(pending.provider, pending.sessionId)
                    val pinned = current.pinnedSessionIds - key
                    val hidden = current.hiddenSessionIds - key
                    preferences.setPinnedSessionIds(pinned)
                    preferences.setHiddenSessionIds(hidden)
                    current.copy(
                        submitting = false,
                        pendingSessionAction = null,
                        sessions = current.sessions.filterNot { it.matches(pending.provider, pending.sessionId) },
                        selected = if (wasSelected) null else current.selected,
                        screen = if (wasSelected) Screen.Sessions else current.screen,
                        pinnedSessionIds = pinned,
                        hiddenSessionIds = hidden,
                    )
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
        if (current.submitting || (text.isBlank() && images.isEmpty())) return
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
        if (state.value.sessions.none { it.matches(PROVIDER_CODEX, approval.sessionId) }) discoverSession(approval.sessionId)
        state.update { current ->
            fun updateSession(session: SessionSummary): SessionSummary =
                if (!session.matches(PROVIDER_CODEX, approval.sessionId)) session else session.copy(
                    status = if (terminal && session.status == "waiting") "working" else "waiting",
                    attention = !terminal,
                    activeTurnId = approval.turnId ?: session.activeTurnId,
                    activityLabel = if (terminal) "Approval resolved" else approvalAttentionLabel(approval.type),
                    activityText = "",
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
        if (state.value.sessions.none { it.matches(PROVIDER_CODEX, input.sessionId) }) discoverSession(input.sessionId)
        state.update { current ->
            fun updateSession(session: SessionSummary): SessionSummary =
                if (!session.matches(PROVIDER_CODEX, input.sessionId)) session else session.copy(
                    status = if (terminal && session.status == "waiting") "working" else "waiting",
                    attention = !terminal,
                    activeTurnId = input.turnId ?: session.activeTurnId,
                    activityLabel = if (terminal) "Input request resolved" else inputAttentionLabel(input),
                    activityText = "",
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
            val claudeAvailable =
                providers.any { it.id == PROVIDER_CLAUDE_CODE && it.enabled && it.available }
            val enabledProviders = providers.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
            state.update { current ->
                fun availability(session: SessionSummary): SessionSummary =
                    if (sessionProvider(session) == PROVIDER_CLAUDE_CODE && !claudeAvailable &&
                        session.status !in setOf("working", "waiting")
                    ) session.copy(status = "unavailable") else session
                val selected = current.selected?.takeIf {
                    sessionProvider(it) in enabledProviders
                }?.let(::availability)
                current.copy(
                    providers = providers,
                    sessions = current.sessions.filter {
                        sessionProvider(it) in enabledProviders
                    }.map(::availability),
                    selected = selected,
                    screen = if (current.selected != null && selected == null) Screen.Sessions else current.screen,
                )
            }
            return
        }
        if (message.type != "session.event") return
        val provider =
            message.payload["provider"]?.jsonPrimitive?.content ?: PROVIDER_CODEX
        val sessionId = message.payload["sessionId"]?.jsonPrimitive?.content ?: return
        val identityKey = providerSessionKey(provider, sessionId)
        val event = message.eventObject()
        val kind = event["kind"]?.jsonPrimitive?.content ?: return
        if (kind == "lifecycle") {
            val action = event["action"]?.jsonPrimitive?.content
            if (action == "removed") {
                state.update { current ->
                    val pinned = current.pinnedSessionIds - identityKey
                    val hidden = current.hiddenSessionIds - identityKey
                    preferences.setPinnedSessionIds(pinned)
                    preferences.setHiddenSessionIds(hidden)
                    current.copy(
                        sessions = current.sessions.filterNot { it.matches(provider, sessionId) },
                        searchResults = current.searchResults.filterNot { it.session.matches(provider, sessionId) },
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
                                it.copy(
                                    accessLevel =
                                        event["accessLevel"]?.jsonPrimitive?.content
                                            ?: it.accessLevel,
                                    model = event["model"]?.jsonPrimitive?.content ?: it.model,
                                    reasoningEffort =
                                        event["reasoningEffort"]?.jsonPrimitive?.content
                                            ?: it.reasoningEffort,
                                )
                            } else if (it.matches(provider, sessionId) && usage != null) {
                                it.copy(tokenUsage = usage)
                            } else if (it.matches(provider, sessionId) && inferredStatus != null) {
                                it.copy(
                                    status = inferredStatus,
                                    attention = inferredStatus == "waiting",
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
                    val accessLevel =
                        event["accessLevel"]?.jsonPrimitive?.content ?: selected.accessLevel
                    val model = event["model"]?.jsonPrimitive?.content ?: selected.model
                    val reasoningEffort =
                        event["reasoningEffort"]?.jsonPrimitive?.content
                            ?: selected.reasoningEffort
                    current.copy(
                        selected =
                            selected.copy(
                                accessLevel = accessLevel,
                                model = model,
                                reasoningEffort = reasoningEffort,
                            ),
                        composerAccessLevel = accessLevel,
                        composerModel = model,
                        composerEffort = reasoningEffort,
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

@Composable
private fun ForemanApp(
    viewModel: ForemanViewModel = viewModel(),
    requestTurnMonitoring: (Boolean) -> Unit = viewModel::setMonitorActiveTurns,
) {
    val state by viewModel.state.collectAsState()
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
    MaterialTheme(colorScheme = foremanColorScheme(state.accentColor, darkTheme)) {
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
                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    "Foreman diagnostics",
                                    diagnosticsText(state.diagnostics),
                                ),
                            )
                        },
                        enabled = state.diagnostics.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy") }
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
                                RestartPhase.Succeeded -> Color(0xFF12B76A)
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
                if (totals.staleHosts > 0) Text("Aggregate counts include ${totals.staleHosts} stale host snapshot${if (totals.staleHosts == 1) "" else "s"}.", color = Color(0xFFF79009), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                    onOpen = { viewModel.openOverviewHost(host.id) },
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
                                Text(item.type.uppercase(), color = if (item.type == "failed") MaterialTheme.colorScheme.error else Color(0xFFF79009), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(overviewAge(item.startedAt) + if (stale) " · STALE" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(item.sessionTitle, fontWeight = FontWeight.Bold)
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
                    IconButton(onClick = viewModel::showSessions) {
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
                            val providerActivity = state.providers.filter { it.enabled }.joinToString(" · ") { provider ->
                                val label = provider.displayName.removeSuffix(" Code")
                                "$label ${dashboard.active.count { sessionProvider(it) == provider.id }}"
                            }
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
            state.runtimeMode == "fallback" -> "Fallback runtime"
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
                    color = if (state.connected) Color(0xFF17B26A) else MaterialTheme.colorScheme.error,
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
                Spacer(Modifier.width(8.dp))
                ProviderBadge(sessionProvider(session))
                Spacer(Modifier.width(6.dp))
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
    onOpen: () -> Unit,
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
                Text(snapshot?.connection ?: host.lastKnownStatus, color = if (live) Color(0xFF17B26A) else Color(0xFFF79009), style = MaterialTheme.typography.labelMedium)
            }
            if (!live) Text("STALE · Last connected ${overviewAge(host.lastConnectedAt)} · checked ${overviewAge(snapshot?.observedAt)}", color = Color(0xFFF79009), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text("Foreman ${snapshot?.foremanVersion ?: "—"} · Codex ${snapshot?.codexVersion ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("${if (snapshot?.runtimeMode == "shared") "Shared Desktop" else if (snapshot?.runtimeMode == "fallback") "Fallback runtime" else "Runtime unknown"}${if (snapshot != null && !snapshot.runtimeConnected) " · unavailable" else ""}", style = MaterialTheme.typography.bodySmall)
            Text("${snapshot?.active ?: 0} active · ${snapshot?.waiting ?: 0} waiting · ${snapshot?.failed ?: 0} failed${if (!live) " (stale)" else ""}")
            Text(
                "Codex ${snapshot?.codexActive ?: 0} · Claude ${snapshot?.claudeActive ?: 0}" +
                    if (snapshot?.claudeUnavailable == true) " · Claude unavailable" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Oldest ${overviewElapsed(snapshot?.oldestTurn?.timestamp)} · Latest activity ${overviewAge(snapshot?.latestActivity)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onOpen) { Text("View dashboard") }
                if (!live) FilledTonalButton(onClick = onReconnect) { Text("Reconnect") }
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
    var collapsedRepositoryIds by remember { mutableStateOf(emptySet<String>()) }
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
                    IconButton(onClick = { viewModel.setNewSession(true) }, enabled = state.connected) {
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
            AccountUsageDock(state.accountUsage, state.providers)
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
            if (state.loading && state.sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val visible = filterSessions(
                    state.sessions,
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
                    isRefreshing = state.loading,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.searchError != null) item { ErrorText(state.searchError, Modifier.fillMaxWidth()) }
                        if (visible.isEmpty() && !state.searchLoading) item {
                            Column(
                                Modifier.fillParentMaxSize().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(if (sessionSearchActive(state.searchFilters)) "No matching sessions" else "No sessions yet", fontWeight = FontWeight.Bold)
                                Text("Try clearing a filter or using a shorter substring.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.groupSessionsByRepository && !sessionSearchActive(state.searchFilters)) {
                            repositoryGroups.forEach { group ->
                                repositorySessionSection(
                                    group,
                                    group.repository.id in collapsedRepositoryIds,
                                    {
                                        collapsedRepositoryIds = if (group.repository.id in collapsedRepositoryIds) {
                                            collapsedRepositoryIds - group.repository.id
                                        } else {
                                            collapsedRepositoryIds + group.repository.id
                                        }
                                    },
                                    { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                    viewModel::requestSessionAction,
                                    { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                    { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                    state.capabilities,
                                    state.repositories,
                                    state.repositoryRoot,
                                )
                            }
                        } else {
                            sessionSection(
                                "Pinned", pinned,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot,
                            )
                            sessionSection(
                                "Waiting", waiting,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot,
                            )
                            sessionSection(
                                "Active", active,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot,
                            )
                            sessionSection(
                                "Recent", recent,
                                { id, itemId, provider -> viewModel.openSession(id, itemId, provider = provider) },
                                viewModel::requestSessionAction,
                                { provider, id -> viewModel.togglePinnedSession(id, provider) },
                                { provider, id -> viewModel.toggleHiddenSession(id, provider) },
                                state.capabilities, state.repositories, state.repositoryRoot,
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
            models = state.models,
            accessLevels = state.accessLevels,
            claudeModels = state.claudeModels,
            claudePermissionModes = state.claudePermissionModes,
            initialProvider = state.selectedNewSessionProvider,
            initialModel = state.composerModel,
            initialEffort = state.composerEffort,
            initialAccessLevel = state.composerAccessLevel,
            initialClaudeModel = state.claudeComposerModel,
            initialClaudePermissionMode = state.claudeComposerPermissionMode,
            onDismiss = { viewModel.setNewSession(false) },
            onStart = viewModel::startProviderSession,
        )
    }
    if (state.showSearchFilters) {
        SessionFilterDialog(
            filters = state.searchFilters,
            repositories = sessionRepositoryOptions(state.sessions, state.repositories, state.repositoryRoot),
            onChange = { viewModel.setSearchFilters(it, true) },
            onDismiss = { viewModel.setSearchFiltersOpen(false) },
        )
    }
}

@Composable
private fun AccountUsageDock(usage: AccountUsage, providers: List<ProviderInfo>) {
    val visible = providers.filter { it.enabled }.mapNotNull { provider ->
        usage.providers[provider.id]?.let { provider to it }
    }
    if (visible.isEmpty()) return
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
                            "${provider.displayName.removeSuffix(" Code")} ${accountUsageRemaining(providerUsage)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text("Account usage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (open) AccountUsageDialog(visible, onDismiss = { open = false })
}

@Composable
private fun AccountUsageDialog(
    providers: List<Pair<ProviderInfo, ProviderAccountUsage>>,
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(provider.displayName, fontWeight = FontWeight.Bold)
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
                    "${formatTokenCount(usage.remainingTokens)} tokens remain. ${providerDisplayName(provider)} normally compacts the conversation automatically before the window is exhausted.",
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
) {
    item(key = "repository:${group.repository.id}") {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = toggleCollapsed).padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.repository.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${group.sessions.size} ${if (collapsed) "›" else "⌄"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!collapsed) {
        sessionCards(group.sessions, open, action, pin, hide, capabilities, repositories, repositoryRoot)
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
    sessionCards(sessions, open, action, pin, hide, capabilities, repositories, repositoryRoot)
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
) {
    items(sessions, key = { it.session.providerKey() }) { visible ->
        val session = visible.session
        val provider = sessionProvider(session)
        SessionCard(
            session = session,
            matches = visible.matches,
            pinned = visible.pinned,
            hidden = visible.hidden,
            repositoryLabel = sessionRepositoryIdentity(session.repository, repositories, repositoryRoot).label,
            onClick = { open(session.id, visible.matches.firstOrNull { it.itemId != null }?.itemId, provider) },
            onAction = { action(session, it) },
            onPin = { pin(provider, session.id) },
            onHide = { hide(provider, session.id) },
            capabilities = capabilities,
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    matches: List<SessionSearchMatch>,
    pinned: Boolean,
    hidden: Boolean,
    repositoryLabel: String,
    onClick: () -> Unit,
    onAction: (SessionAction) -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    capabilities: Set<String>,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sessionDisplayTitle(session),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                ProviderBadge(sessionProvider(session))
                Spacer(Modifier.width(6.dp))
                StatusPill(sessionDisplayStatus(session))
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
            Text(
                repositoryLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    val selectedProvider = selected?.let(::sessionProvider) ?: PROVIDER_CODEX
    val selectedApprovals = state.approvals.filter {
        selectedProvider == PROVIDER_CODEX && it.sessionId == selected?.id
    }
    val selectedInputs = state.inputs.filter {
        selectedProvider == PROVIDER_CODEX && it.sessionId == selected?.id
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
                                sessionDisplayStatus(selected),
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
            if (selected != null) PromptBox(
                text = state.activeHostId?.let {
                    composerDraft(state.composerDrafts, it, selected.id, selectedProvider)
                }.orEmpty(),
                provider = selectedProvider,
                resumableExternal = selected.source == "external",
                working = selected.status in setOf("working", "waiting"),
                interruptible = providerInterruptEligible(selected),
                routeEnabled = state.connected && !state.submitting,
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
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    selected.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                ProviderBadge(selectedProvider)
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
                                selectedApprovals.filter { it.itemId == item.id }.forEach { approval ->
                                    ApprovalCard(
                                        approval = approval,
                                        connected = state.connected,
                                        submitting = approval.id in state.submittingApprovalIds,
                                        error = state.approvalErrors[approval.id],
                                        onRespond = { viewModel.respondToApproval(approval, it) },
                                    )
                                }
                                selectedInputs.filter { it.itemId == item.id }.forEach { input ->
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
                        detachedApprovals,
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
                        detachedInputs,
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
                    if (selected.status == "working" && selected.source != "external") {
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
    val commandCount = items.count { it.kind == "command" }
    val toolCount = items.size - commandCount
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
                        "${items.size} completed activity item${if (items.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildList {
                            if (commandCount > 0) add("$commandCount command${if (commandCount == 1) "" else "s"}")
                            if (toolCount > 0) add("$toolCount tool${if (toolCount == 1) "" else "s"}")
                        }.joinToString(" · "),
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
        "command", "tool" -> Card(Modifier.fillMaxWidth()) {
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
                    buildString {
                        append(item.status)
                        item.exitCode?.let { append(" · exit $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    routeEnabled: Boolean,
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
                            enabled = routeEnabled,
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
                                            MaterialTheme.colorScheme.error
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
                    tint = MaterialTheme.colorScheme.error,
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
                    Text("Enable", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    if (confirmBypassPermissions) {
        AlertDialog(
            onDismissRequest = { confirmBypassPermissions = false },
            icon = {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error)
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
                ) { Text("Use high-risk mode", color = MaterialTheme.colorScheme.error) }
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
                            contentColor = MaterialTheme.colorScheme.error,
                            disabledContentColor = MaterialTheme.colorScheme.error,
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
    var showingAccentColors by remember { mutableStateOf(false) }
    var showingActivityDetail by remember { mutableStateOf(false) }
    var showingProviders by remember { mutableStateOf(false) }
    var showingNotifications by remember { mutableStateOf(false) }
    var notificationRepositoryId by remember { mutableStateOf<String?>(null) }
    var quietStartText by remember(state.notificationPreferences.quietStart) { mutableStateOf(state.notificationPreferences.quietStart) }
    var quietEndText by remember(state.notificationPreferences.quietEnd) { mutableStateOf(state.notificationPreferences.quietEnd) }
    var confirmForgetHost by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    Box(modifier) {
        IconButton(
            onClick = {
                showingAccentColors = false
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
                showingAccentColors = false
                showingActivityDetail = false
                showingProviders = false
                showingNotifications = false
                notificationRepositoryId = null
            },
        ) {
            if (showingAccentColors) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Accent color",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to settings",
                        )
                    },
                    onClick = { showingAccentColors = false },
                )
                HorizontalDivider()
                AccentColor.values().forEach { color ->
                    val selected = state.accentColor == color
                    DropdownMenuItem(
                        text = { Text(color.name) },
                        leadingIcon = { AccentSwatch(color, selected) },
                        trailingIcon = {
                            if (selected) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        },
                        onClick = {
                            viewModel.setAccentColor(color)
                            expanded = false
                            showingAccentColors = false
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
                                        "Group routine completed commands and tools"
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
                    "Choose which installed CLIs Foreman uses on this host. At least one provider must remain enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val enabledCount = state.providers.count { it.enabled }
                state.providers.forEach { provider ->
                    val lastEnabled = provider.enabled && enabledCount == 1
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(provider.displayName)
                                Text(
                                    when {
                                        !provider.enabled -> "Disabled"
                                        provider.available -> "Available"
                                        else -> "Unavailable"
                                    } + if (lastEnabled) " · at least one required" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            Checkbox(checked = provider.enabled, onCheckedChange = null, enabled = !lastEnabled && !state.submitting)
                        },
                        enabled = !lastEnabled && !state.submitting,
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
                    val deleteSupported =
                        sessionActionSupported(session, state.capabilities, SessionAction.Delete)
                    if (archiveSupported || deleteSupported) {
                        Text(
                            "Session",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
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
                    "Theme",
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
                    text = { Text("Accent color") },
                    leadingIcon = { AccentSwatch(state.accentColor) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.accentColor.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = { showingAccentColors = true },
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
private fun AccentSwatch(color: AccentColor, selected: Boolean = false) {
    Surface(
        modifier = Modifier.size(20.dp),
        shape = CircleShape,
        color = accentPalette(color).light.primary,
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
            ),
    ) {}
}

@Composable
private fun SessionActionsMenu(
    enabled: Boolean,
    archiveSupported: Boolean,
    deleteSupported: Boolean,
    onAction: (SessionAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled && (archiveSupported || deleteSupported),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Session actions")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Archive") },
                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAction(SessionAction.Archive)
                },
                enabled = archiveSupported,
            )
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

@Composable
private fun SessionActionDialog(
    pending: PendingSessionAction,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deleting = pending.action == SessionAction.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deleting) "Delete session permanently?" else "Archive session?") },
        text = {
            Text(
                if (deleting) {
                    "\u201c${pending.sessionTitle}\u201d and any sessions it spawned will be permanently " +
                        "deleted. This cannot be undone."
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
                Text(if (deleting) "Delete permanently" else "Archive")
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
    val color = when (status) {
        "working" -> Color(0xFF2563EB)
        "waiting" -> Color(0xFFD97706)
        "failed" -> Color(0xFFDC2626)
        "interrupted" -> Color(0xFF7C3AED)
        else -> Color(0xFF4B5563)
    }
    Text(
        status,
        color = Color.White,
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
    var provider by remember(enabledProviders, initialProvider) {
        mutableStateOf(
            enabledProviders.firstOrNull { it.id == initialProvider }?.id
                ?: enabledProviders.firstOrNull()?.id ?: PROVIDER_CODEX,
        )
    }
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
                NewSessionOptionMenu(
                    "Provider",
                    providerInfo?.displayName ?: providerDisplayName(provider),
                    enabledProviders.map { it.id to (it.displayName + if (it.available) "" else " · unavailable") },
                ) { provider = it }
                if (providerUnavailable) {
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
                            color = MaterialTheme.colorScheme.error,
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
                    if (provider == PROVIDER_CLAUDE_CODE) "Start Claude session"
                    else if (repositories.isEmpty()) "Start in workspace" else "Create",
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pendingHighRiskMode != null) {
        AlertDialog(
            onDismissRequest = { pendingHighRiskMode = null },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
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
                ) { Text("Use high-risk mode", color = MaterialTheme.colorScheme.error) }
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
