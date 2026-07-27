package net.kaltner.foreman

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.Intent
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import java.text.DateFormat
import java.util.Date
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
            foremanViewModel.openSessionFromNotification(it)
        }
    }
}

internal enum class Screen { Setup, Sessions, Detail }

internal enum class SessionAction { Archive, Delete }

internal data class PendingSessionAction(
    val sessionId: String,
    val sessionTitle: String,
    val action: SessionAction,
)

internal fun sessionDisplayTitle(session: SessionSummary?): String =
    session?.title?.ifBlank { "Untitled session" } ?: "Session"

internal fun sessionCanBeManaged(status: String): Boolean =
    status != "working" && status != "waiting"

internal fun sessionActionSupported(capabilities: Set<String>, action: SessionAction): Boolean =
    capabilities.contains(if (action == SessionAction.Archive) "archive" else "delete")

internal fun sessionActionCanBeConfirmed(
    connected: Boolean,
    capabilities: Set<String>,
    action: SessionAction,
): Boolean = connected && sessionActionSupported(capabilities, action)

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

internal fun UiState.withSynchronizedSessions(
    sessions: List<SessionSummary>,
    repositories: List<RepositoryInfo>,
    selectedSessionId: String?,
    selectedSession: SessionSummary?,
): UiState =
    copy(
        sessions = sessions,
        repositories = repositories,
        selected = selectedSession,
        screen =
            if (selectedSessionId != null && selectedSession != null) {
                Screen.Detail
            } else if (screen == Screen.Detail) {
                Screen.Sessions
            } else {
                screen
            },
        loading = false,
        error = null,
    )

internal fun UiState.withDiscoveredSessions(discovered: List<SessionSummary>): UiState {
    val known = sessions.mapTo(mutableSetOf()) { it.id }
    val additions = discovered.filter { known.add(it.id) }
    return if (additions.isEmpty()) this else copy(sessions = additions + sessions)
}

internal fun UiState.shouldDiscoverSession(sessionId: String, eventKind: String): Boolean =
    connected && eventKind == "status" && sessions.none { it.id == sessionId }

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
    val host: String = "",
    val pairingKey: String = "",
    val deviceName: String = "Android",
    val connected: Boolean = false,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val repositories: List<RepositoryInfo> = emptyList(),
    val selected: SessionSummary? = null,
    val showNewSession: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val accentColor: AccentColor = AccentColor.Purple,
    val followNewMessages: Boolean = true,
    val monitorActiveTurns: Boolean = false,
    val pendingSessionAction: PendingSessionAction? = null,
    val capabilities: Set<String> = emptySet(),
    val accessLevels: List<AccessLevelInfo> = emptyList(),
    val composerAccessLevel: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val composerModel: String? = null,
    val composerEffort: String? = null,
)

internal class ForemanViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PreferenceStore(application)
    private val savedPreferences = preferences.load()
    val state =
        MutableStateFlow(
            UiState(
                themeMode = savedPreferences.themeMode,
                accentColor = savedPreferences.accentColor,
                followNewMessages = savedPreferences.followNewMessages,
                monitorActiveTurns = savedPreferences.monitorActiveTurns,
                composerAccessLevel = savedPreferences.accessLevel,
                composerModel = savedPreferences.model,
                composerEffort = savedPreferences.reasoningEffort,
            ),
        )
    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = TokenStore(application)
    private var reconnectJob: Job? = null
    private val sessionDiscoveryLock = Any()
    private val sessionDiscoveryQueue = SessionDiscoveryQueue()
    private var sessionDiscoveryJob: Job? = null
    private var notificationSessionId: String? = null
    private val client = ForemanClient(
        viewModelScope,
        onEvent = ::handleEvent,
        onDisconnect = { message ->
            synchronized(sessionDiscoveryLock) {
                sessionDiscoveryJob?.cancel()
                sessionDiscoveryJob = null
                sessionDiscoveryQueue.clear()
            }
            state.update {
                it.copy(
                    connected = false,
                    loading = false,
                    error = message,
                    capabilities = emptySet(),
                    pendingSessionAction = null,
                )
            }
        },
    )

    init {
        tokens.load()?.let { saved ->
            state.update {
                it.copy(
                    host = saved.host,
                    deviceName = saved.deviceName,
                )
            }
            launchReconnect(saved)
        }
    }

    fun setHost(value: String) = state.update { it.copy(host = value) }
    fun setPairingKey(value: String) = state.update { it.copy(pairingKey = value) }
    fun setDeviceName(value: String) = state.update { it.copy(deviceName = value) }
    fun setNewSession(open: Boolean) = state.update { it.copy(showNewSession = open) }

    fun setComposerModel(id: String) {
        state.update { current ->
            val model = current.models.firstOrNull { it.id == id } ?: return@update current
            val effort = compatibleEffort(model, current.composerEffort)
            preferences.setModelRoute(model.id, effort)
            current.copy(composerModel = model.id, composerEffort = effort)
        }
    }

    fun setComposerAccessLevel(id: String) {
        state.update { current ->
            if (current.accessLevels.none { it.id == id }) return@update current
            preferences.setAccessLevel(id)
            current.copy(composerAccessLevel = id)
        }
    }

    fun setComposerEffort(effort: String) {
        state.update { current ->
            val model =
                current.models.firstOrNull { it.id == current.composerModel }
                    ?: return@update current
            if (effort !in model.reasoningEfforts) return@update current
            preferences.setModelRoute(model.id, effort)
            current.copy(composerEffort = effort)
        }
    }

    fun composerError(message: String) = state.update { it.copy(error = message) }

    fun setThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        state.update { it.copy(themeMode = mode) }
    }

    fun setAccentColor(color: AccentColor) {
        preferences.setAccentColor(color)
        state.update { it.copy(accentColor = color) }
    }

    fun setFollowNewMessages(enabled: Boolean) {
        preferences.setFollowNewMessages(enabled)
        state.update { it.copy(followNewMessages = enabled) }
    }

    fun setMonitorActiveTurns(enabled: Boolean) {
        preferences.setMonitorActiveTurns(enabled)
        state.update { it.copy(monitorActiveTurns = enabled, error = null) }
        if (enabled) {
            state.value.selected?.let(::monitorIfActive)
        } else {
            TurnMonitorService.stopAll(getApplication())
        }
    }

    fun notificationPermissionDenied() {
        state.update {
            it.copy(error = "Allow notifications to monitor active turns in the background.")
        }
    }

    fun onNotificationPermissionState(granted: Boolean) {
        if (!granted && state.value.monitorActiveTurns) setMonitorActiveTurns(false)
    }

    fun openSessionFromNotification(id: String) {
        notificationSessionId = id
        if (state.value.connected) {
            notificationSessionId = null
            openSession(id)
        }
    }

    fun connect() {
        val current = state.value
        if (current.loading) return
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            runCatching {
                parseHost(current.host)
                require(current.pairingKey.isNotBlank()) { "Pairing key is required" }
                require(current.deviceName.isNotBlank()) { "Device name is required" }
                val token =
                    client.pair(current.host, current.pairingKey, current.deviceName)
                tokens.save(current.host.trim(), current.deviceName.trim(), token)
                state.update {
                    it.copy(
                        connected = true,
                        screen = Screen.Sessions,
                        pairingKey = "",
                        capabilities = client.capabilities,
                    )
                }
                synchronizeSessions()
            }.onFailure(::fail)
        }
    }

    fun reconnect() {
        val saved = tokens.load() ?: run {
            state.update { it.copy(screen = Screen.Setup) }
            return
        }
        launchReconnect(saved)
    }

    fun onForeground() {
        val saved = tokens.load() ?: return
        if (state.value.loading || reconnectJob?.isActive == true) return
        reconnectJob =
            viewModelScope.launch {
                if (state.value.connected) {
                    val healthy =
                        runCatching {
                            withTimeout(5_000) { client.request("ping") }
                        }.isSuccess
                    if (healthy) {
                        synchronizeSessions(state.value.selected?.id)
                        return@launch
                    }
                }
                reconnectSaved(saved)
            }
    }

    private fun launchReconnect(saved: SavedConnection) {
        if (state.value.loading || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch { reconnectSaved(saved) }
    }

    private suspend fun reconnectSaved(saved: SavedConnection) {
        val selectedId = notificationSessionId ?: state.value.selected?.id
        state.update { it.copy(loading = true, error = null) }
        runCatching {
            client.authenticate(saved.host, saved.token)
            state.update {
                it.copy(
                    connected = true,
                    screen = if (selectedId == null) Screen.Sessions else Screen.Detail,
                    error = null,
                    capabilities = client.capabilities,
                )
            }
            synchronizeSessions(selectedId)
            notificationSessionId = null
        }.onFailure(::fail)
    }

    fun refresh() {
        if (!state.value.connected || state.value.loading) return
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            runCatching { synchronizeSessions(state.value.selected?.id) }.onFailure(::fail)
        }
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            state.update { it.copy(screen = Screen.Detail, loading = true, error = null) }
            runCatching {
                val selected = readSession(id)
                state.update {
                    it.copy(selected = selected, loading = false)
                        .withModelsAndSessionRoute(it.models, selected)
                        .withAccessLevelsAndSessionAccess(it.accessLevels, selected)
                }
                monitorIfActive(selected)
            }.onFailure(::fail)
        }
    }

    private suspend fun synchronizeSessions(selectedSessionId: String? = null) {
        state.update { it.copy(loading = true, error = null) }
        val (sessions, repositories, catalogs) =
            coroutineScope {
                val sessionsRequest = async { listSessions() }
                val repositoriesRequest = async { client.request("repository.list") }
                val modelsRequest =
                    async {
                        if ("models" in client.capabilities) {
                            client.request("model.list")
                        } else {
                            null
                        }
                    }
                val accessRequest =
                    async {
                        if ("access" in client.capabilities) {
                            client.request("access.list")
                        } else {
                            null
                        }
                    }
                val sessions = sessionsRequest.await()
                val repositories =
                    repositoriesRequest.await().payload.getValue("repositories").jsonArray
                        .map { json.decodeFromJsonElement<RepositoryInfo>(it) }
                val models =
                    modelsRequest.await()?.payload?.get("models")?.jsonArray
                        ?.map { json.decodeFromJsonElement<ModelInfo>(it) }
                        ?.filter { it.visible }
                        ?: emptyList()
                val accessLevels =
                    accessRequest.await()?.payload?.get("levels")?.jsonArray
                        ?.map { json.decodeFromJsonElement<AccessLevelInfo>(it) }
                        ?: emptyList()
                Triple(sessions, repositories, models to accessLevels)
            }
        val (models, accessLevels) = catalogs
        val selected = selectedSessionId?.let { readSession(it) }
        state.update {
            it.withSynchronizedSessions(
                    sessions = sessions,
                    repositories = repositories,
                    selectedSessionId = selectedSessionId,
                    selectedSession = selected,
                )
                .withModelsAndSessionRoute(models, selected)
                .withAccessLevelsAndSessionAccess(accessLevels, selected)
        }
        selected?.let(::monitorIfActive)
    }

    private suspend fun listSessions(): List<SessionSummary> =
        client.request("session.list").payload.getValue("sessions").jsonArray
            .map { json.decodeFromJsonElement<SessionSummary>(it) }

    private fun discoverSession(sessionId: String) {
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

    private suspend fun readSession(id: String): SessionSummary {
        client.request(
            "session.subscribe",
            buildJsonObject { put("sessionId", id) },
        )
        val response =
            client.request(
                "session.read",
                buildJsonObject { put("sessionId", id) },
            )
        return json.decodeFromJsonElement(
            response.payload.getValue("session"),
        )
    }

    fun backToSessions() {
        state.update { it.copy(screen = Screen.Sessions, selected = null, error = null) }
        refresh()
    }

    fun requestSessionAction(session: SessionSummary, action: SessionAction) {
        if (!sessionActionSupported(state.value.capabilities, action)) {
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
                    PendingSessionAction(session.id, session.title, action),
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
            if (
                current.pendingSessionAction != pending ||
                    !sessionActionCanBeConfirmed(
                        current.connected,
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
                    if (pending.action == SessionAction.Archive) {
                        "session.archive"
                    } else {
                        "session.delete"
                    },
                    buildJsonObject {
                        put("sessionId", pending.sessionId)
                        if (pending.action == SessionAction.Delete) put("confirm", true)
                    },
                )
                runCatching { TurnMonitorService.cancel(getApplication(), pending.sessionId) }
                state.update { current ->
                    val wasSelected = current.selected?.id == pending.sessionId
                    current.copy(
                        submitting = false,
                        pendingSessionAction = null,
                        sessions = current.sessions.filterNot { it.id == pending.sessionId },
                        selected = if (wasSelected) null else current.selected,
                        screen = if (wasSelected) Screen.Sessions else current.screen,
                    )
                }
            }.onFailure(::fail)
        }
    }

    fun startSession(repository: RepositoryInfo) {
        if (state.value.submitting) return
        viewModelScope.launch {
            state.update { it.copy(submitting = true, showNewSession = false, error = null) }
            runCatching {
                val response = client.request(
                    "session.start",
                    buildJsonObject { put("repositoryId", repository.id) },
                )
                val created =
                    json.decodeFromJsonElement<SessionSummary>(
                        response.payload.getValue("session"),
                    )
                state.update {
                    it.copy(
                        submitting = false,
                        loading = false,
                        selected = created,
                        sessions =
                            listOf(created) +
                                it.sessions.filterNot { session -> session.id == created.id },
                        screen = Screen.Detail,
                    )
                }
            }.onFailure(::fail)
        }
    }

    fun send(text: String, images: List<ImagePayload>, accepted: () -> Unit) {
        val current = state.value
        val selected = current.selected ?: return
        if (current.submitting || (text.isBlank() && images.isEmpty())) return
        val steering = selected.status == "working" && selected.activeTurnId != null
        val preparedMonitor = prepareMonitor(selected, active = steering)
        viewModelScope.launch {
            state.update { it.copy(submitting = true, error = null) }
            runCatching {
                val type = if (steering) "turn.steer" else "turn.prompt"
                val response = client.request(
                    type,
                    turnPayload(
                        selected,
                        text,
                        images,
                        steering,
                        current.composerAccessLevel,
                        current.composerModel,
                        current.composerEffort,
                    ),
                )
                val turnId = response.payload["turnId"]?.jsonPrimitive?.content
                var monitored: SessionSummary? = null
                state.update {
                    val updated =
                        it.selected?.copy(
                            status = "working",
                            activeTurnId = turnId ?: it.selected.activeTurnId,
                            activityLabel = "Thinking",
                            activityText = "",
                            accessLevel =
                                if (steering) {
                                    it.selected.accessLevel
                                } else {
                                    current.composerAccessLevel
                                },
                            model =
                                if (steering) it.selected.model else current.composerModel,
                            reasoningEffort =
                                if (steering) {
                                    it.selected.reasoningEffort
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
                    runCatching { TurnMonitorService.cancel(getApplication(), selected.id) }
                }
                fail(it)
            }
        }
    }

    fun interrupt() {
        val selected = state.value.selected ?: return
        val turnId = selected.activeTurnId ?: return
        if (state.value.submitting) return
        viewModelScope.launch {
            state.update { it.copy(submitting = true, error = null) }
            runCatching {
                client.request(
                    "turn.interrupt",
                    buildJsonObject {
                        put("sessionId", selected.id)
                        put("turnId", turnId)
                    },
                )
                state.update { it.copy(submitting = false) }
            }.onFailure(::fail)
        }
    }

    private fun handleEvent(message: WireMessage) {
        if (message.type != "session.event") return
        val sessionId = message.payload["sessionId"]?.jsonPrimitive?.content ?: return
        val event = message.eventObject()
        val kind = event["kind"]?.jsonPrimitive?.content ?: return
        if (state.value.shouldDiscoverSession(sessionId, kind)) {
            discoverSession(sessionId)
        }
        state.update { current ->
            val selected = current.selected
            if (selected?.id != sessionId) {
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
                            if (it.id == sessionId && kind == "route") {
                                it.copy(
                                    accessLevel =
                                        event["accessLevel"]?.jsonPrimitive?.content
                                            ?: it.accessLevel,
                                    model = event["model"]?.jsonPrimitive?.content ?: it.model,
                                    reasoningEffort =
                                        event["reasoningEffort"]?.jsonPrimitive?.content
                                            ?: it.reasoningEffort,
                                )
                            } else if (it.id == sessionId && inferredStatus != null) {
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
                    val item = runCatching {
                        json.decodeFromJsonElement<ConversationItem>(raw)
                    }.getOrNull() ?: return@update current
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
                else -> current
            }
        }
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

    private fun prepareMonitor(session: SessionSummary, active: Boolean): Boolean {
        if (!state.value.monitorActiveTurns) return false
        return runCatching {
            TurnMonitorService.monitor(getApplication(), session.id, active)
        }.onFailure { error ->
            state.update {
                it.copy(error = error.message ?: "Android could not start background monitoring.")
            }
        }.isSuccess
    }

    override fun onCleared() {
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
                Screen.Sessions -> SessionsScreen(state, viewModel, requestTurnMonitoring)
                Screen.Detail -> SessionDetailScreen(state, viewModel, requestTurnMonitoring)
            }
            state.pendingSessionAction?.let { pending ->
                SessionActionDialog(
                    pending = pending,
                    busy = state.submitting,
                    onConfirm = viewModel::confirmSessionAction,
                    onDismiss = viewModel::dismissSessionAction,
                )
            }
        }
    }
}

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
                value = state.pairingKey,
                onValueChange = viewModel::setPairingKey,
                label = { Text("Pairing key") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsScreen(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Foreman", fontWeight = FontWeight.Bold) },
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.connected) {
                ConnectionBanner(state.error, viewModel::reconnect)
            } else {
                ErrorText(state.error, Modifier.padding(horizontal = 16.dp))
            }
            if (state.loading && state.sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val active = state.sessions.filter { it.status == "working" }
                val waiting = state.sessions.filter { it.status == "waiting" || it.attention }
                val recent = state.sessions.filterNot { it in active || it in waiting }
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
                        sessionSection(
                            "Active",
                            active,
                            viewModel::openSession,
                            viewModel::requestSessionAction,
                            state.capabilities,
                        )
                        sessionSection(
                            "Waiting",
                            waiting,
                            viewModel::openSession,
                            viewModel::requestSessionAction,
                            state.capabilities,
                        )
                        sessionSection(
                            "Recent",
                            recent,
                            viewModel::openSession,
                            viewModel::requestSessionAction,
                            state.capabilities,
                        )
                    }
                }
            }
        }
    }
    if (state.showNewSession) {
        NewSessionDialog(
            state.repositories,
            onDismiss = { viewModel.setNewSession(false) },
            onSelect = viewModel::startSession,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sessionSection(
    title: String,
    sessions: List<SessionSummary>,
    open: (String) -> Unit,
    action: (SessionSummary, SessionAction) -> Unit,
    capabilities: Set<String>,
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
    items(sessions, key = { it.id }) { session ->
        SessionCard(
            session = session,
            onClick = { open(session.id) },
            onAction = { action(session, it) },
            capabilities = capabilities,
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    onClick: () -> Unit,
    onAction: (SessionAction) -> Unit,
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
                StatusPill(session.status)
                SessionActionsMenu(
                    enabled = sessionCanBeManaged(session.status),
                    archiveSupported = sessionActionSupported(capabilities, SessionAction.Archive),
                    deleteSupported = sessionActionSupported(capabilities, SessionAction.Delete),
                    onAction = onAction,
                )
            }
            Text(
                session.repository.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailScreen(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
) {
    val selected = state.selected
    val listState = rememberLazyListState()
    val lastMessage = selected?.messages?.lastOrNull()

    BackHandler(onBack = viewModel::backToSessions)

    LaunchedEffect(selected?.id) {
        selected?.let {
            listState.scrollToItem(
                it.messages.size + if (it.status == "working") 1 else 0,
            )
        }
    }
    LaunchedEffect(
        state.followNewMessages,
        selected?.messages?.size,
        lastMessage?.text,
        selected?.status,
        selected?.activityLabel,
        selected?.activityText,
    ) {
        if (state.followNewMessages) {
            selected?.let {
                listState.scrollToItem(
                    it.messages.size + if (it.status == "working") 1 else 0,
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
                                selected.status,
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
                    if (selected?.status == "working" && selected.activeTurnId != null) {
                        IconButton(onClick = viewModel::interrupt, enabled = !state.submitting) {
                            Icon(Icons.Default.Stop, contentDescription = "Interrupt")
                        }
                    }
                    if (selected != null) {
                        SessionActionsMenu(
                            enabled =
                                sessionCanBeManaged(selected.status) && !state.submitting,
                            archiveSupported =
                                sessionActionSupported(state.capabilities, SessionAction.Archive),
                            deleteSupported =
                                sessionActionSupported(state.capabilities, SessionAction.Delete),
                            onAction = { viewModel.requestSessionAction(selected, it) },
                        )
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
            if (selected != null) PromptBox(
                working = selected.status == "working",
                enabled = state.connected && !state.submitting,
                accessLevels = state.accessLevels,
                accessLevelId = state.composerAccessLevel,
                models = state.models,
                modelId = state.composerModel,
                effort = state.composerEffort,
                selectAccessLevel = viewModel::setComposerAccessLevel,
                selectModel = viewModel::setComposerModel,
                selectEffort = viewModel::setComposerEffort,
                showError = viewModel::composerError,
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
                        Text(
                            selected.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    itemsIndexed(
                        selected.messages,
                        key = { index, item -> "${item.id}-$index" },
                    ) { _, item ->
                        ConversationRow(item)
                    }
                    if (selected.status == "working") {
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
private fun ConversationRow(item: ConversationItem) {
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
        "assistant" -> MarkdownText(
            text = item.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            contentColor = MaterialTheme.colorScheme.onBackground,
        )
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
    working: Boolean,
    enabled: Boolean,
    accessLevels: List<AccessLevelInfo>,
    accessLevelId: String?,
    models: List<ModelInfo>,
    modelId: String?,
    effort: String?,
    selectAccessLevel: (String) -> Unit,
    selectModel: (String) -> Unit,
    selectEffort: (String) -> Unit,
    showError: (String) -> Unit,
    send: (String, List<ImagePayload>, () -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(emptyList<ImagePayload>()) }
    var processing by remember { mutableStateOf(false) }
    var showAccessLevels by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showEfforts by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedAccessLevel = accessLevels.firstOrNull { it.id == accessLevelId }
    val selectedModel = models.firstOrNull { it.id == modelId }
    val imageSupported =
        selectedModel == null ||
            selectedModel.inputModalities.isEmpty() ||
            "image" in selectedModel.inputModalities
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
                accessLevels = accessLevels,
                selectedAccessLevel = selectedAccessLevel,
                accessLevelId = accessLevelId,
                models = models,
                selectedModel = selectedModel,
                modelId = modelId,
                effort = effort,
                enabled = enabled && !working,
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
                    onValueChange = { text = it },
                    placeholder = if (working) "Steer this turn…" else "Message Foreman…",
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
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach images")
                        }
                    },
                )
                Button(
                    onClick = {
                        send(text, images) {
                            text = ""
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
                    Text(if (working) "Steer" else "Send")
                }
            }
        }
    }
    if (showAccessLevels) {
        AlertDialog(
            onDismissRequest = { showAccessLevels = false },
            title = { Text("Choose access level") },
            text = {
                Column {
                    accessLevels.forEach { level ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    showAccessLevels = false
                                    if (level.id == "full") {
                                        confirmFullAccess = true
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
                                        if (level.id == "full") {
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
    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Choose model") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(models, key = { it.id }) { model ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selectModel(model.id)
                                    showModels = false
                                }
                                .padding(vertical = 12.dp),
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
                        top = 0.dp,
                        end = 12.dp,
                        bottom = 0.dp,
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
                    ButtonDefaults.textButtonColors(
                        contentColor =
                            if (accessLevelId == "full") {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    ),
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
                    selectedAccessLevel?.displayName ?: accessLevelId ?: "Access",
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
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
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
                            text = { Text(option.replaceFirstChar { it.uppercase() }) },
                            onClick = { selectEffort(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UiSettingsMenu(
    state: UiState,
    viewModel: ForemanViewModel,
    requestTurnMonitoring: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showingAccentColors by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = {
                showingAccentColors = false
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
            } else {
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
                    text = { Text("Notify for active turns") },
                    leadingIcon = {
                        Checkbox(
                            checked = state.monitorActiveTurns,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        requestTurnMonitoring(!state.monitorActiveTurns)
                    },
                )
            }
        }
    }
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
    onDismiss: () -> Unit,
    onSelect: (RepositoryInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            if (repositories.isEmpty()) {
                Text("No Git repositories found below the configured root.")
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(repositories, key = { it.id }) { repository ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onSelect(repository) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(repository.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${repository.path} · ${repository.branch}" +
                                    if (repository.dirty) " · dirty" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
