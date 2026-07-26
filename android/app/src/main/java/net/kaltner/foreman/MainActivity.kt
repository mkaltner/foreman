package net.kaltner.foreman

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val ForemanPurple = Color(0xFF4F46E5)
private val ForemanBackground = Color(0xFFF7F7FA)
private val UserBubble = Color(0xFFE7E5FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ForemanBackground,
                ) {
                    ForemanApp()
                }
            }
        }
    }
}

internal enum class Screen { Setup, Sessions, Detail }

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
)

internal class ForemanViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableStateFlow(UiState())
    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = TokenStore(application)
    private val client = ForemanClient(
        viewModelScope,
        onEvent = ::handleEvent,
        onDisconnect = { message ->
            state.update { it.copy(connected = false, loading = false, error = message) }
        },
    )

    init {
        tokens.load()?.let { saved ->
            state.update {
                it.copy(
                    host = saved.host,
                    deviceName = saved.deviceName,
                    loading = true,
                )
            }
            viewModelScope.launch {
                runCatching {
                    client.authenticate(saved.host, saved.token)
                    state.update {
                        it.copy(
                            connected = true,
                            screen = Screen.Sessions,
                            loading = false,
                            error = null,
                        )
                    }
                    refresh()
                }.onFailure(::fail)
            }
        }
    }

    fun setHost(value: String) = state.update { it.copy(host = value) }
    fun setPairingKey(value: String) = state.update { it.copy(pairingKey = value) }
    fun setDeviceName(value: String) = state.update { it.copy(deviceName = value) }
    fun setNewSession(open: Boolean) = state.update { it.copy(showNewSession = open) }

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
                        loading = false,
                        pairingKey = "",
                    )
                }
                refresh()
            }.onFailure(::fail)
        }
    }

    fun reconnect() {
        val saved = tokens.load() ?: run {
            state.update { it.copy(screen = Screen.Setup) }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            runCatching {
                client.authenticate(saved.host, saved.token)
                state.update { it.copy(connected = true, loading = false) }
                refresh()
                state.value.selected?.id?.let { openSession(it) }
            }.onFailure(::fail)
        }
    }

    fun refresh() {
        if (!state.value.connected) return
        viewModelScope.launch {
            state.update { it.copy(loading = true, error = null) }
            runCatching {
                val sessionsRequest = async { client.request("session.list") }
                val repositoriesRequest = async { client.request("repository.list") }
                val sessions = sessionsRequest.await().payload.getValue("sessions").jsonArray
                    .map { json.decodeFromJsonElement<SessionSummary>(it) }
                val repositories =
                    repositoriesRequest.await().payload.getValue("repositories").jsonArray
                        .map { json.decodeFromJsonElement<RepositoryInfo>(it) }
                state.update {
                    it.copy(
                        sessions = sessions,
                        repositories = repositories,
                        loading = false,
                    )
                }
            }.onFailure(::fail)
        }
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            state.update { it.copy(screen = Screen.Detail, loading = true, error = null) }
            runCatching {
                client.request(
                    "session.subscribe",
                    buildJsonObject { put("sessionId", id) },
                )
                val response = client.request(
                    "session.read",
                    buildJsonObject { put("sessionId", id) },
                )
                val selected =
                    json.decodeFromJsonElement<SessionSummary>(
                        response.payload.getValue("session"),
                    )
                state.update { it.copy(selected = selected, loading = false) }
            }.onFailure(::fail)
        }
    }

    fun backToSessions() {
        state.update { it.copy(screen = Screen.Sessions, selected = null, error = null) }
        refresh()
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
                state.update { it.copy(submitting = false) }
                openSession(created.id)
            }.onFailure(::fail)
        }
    }

    fun send(text: String, accepted: () -> Unit) {
        val current = state.value
        val selected = current.selected ?: return
        if (current.submitting || text.isBlank()) return
        viewModelScope.launch {
            state.update { it.copy(submitting = true, error = null) }
            runCatching {
                val steering =
                    selected.status == "working" && selected.activeTurnId != null
                val type = if (steering) "turn.steer" else "turn.prompt"
                val response = client.request(
                    type,
                    buildJsonObject {
                        put("sessionId", selected.id)
                        put("text", text.trim())
                        if (steering) put("turnId", selected.activeTurnId!!)
                    },
                )
                val turnId = response.payload["turnId"]?.jsonPrimitive?.content
                state.update {
                    it.copy(
                        submitting = false,
                        selected =
                            it.selected?.copy(
                                status = "working",
                                activeTurnId = turnId ?: it.selected.activeTurnId,
                            ),
                    )
                }
                accepted()
            }.onFailure(::fail)
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
        state.update { current ->
            val selected = current.selected
            if (selected?.id != sessionId) {
                return@update current.copy(
                    sessions =
                        current.sessions.map {
                            if (it.id == sessionId && kind == "status") {
                                it.copy(status = event["status"]?.jsonPrimitive?.content ?: it.status)
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
                    current.copy(selected = selected.copy(messages = messages))
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
                    current.copy(selected = selected.copy(messages = messages))
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
                        selected = selected.copy(status = newStatus, activeTurnId = active),
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

    override fun onCleared() {
        client.close()
    }
}

@Composable
private fun ForemanApp(viewModel: ForemanViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    when (state.screen) {
        Screen.Setup -> SetupScreen(state, viewModel)
        Screen.Sessions -> SessionsScreen(state, viewModel)
        Screen.Detail -> SessionDetailScreen(state, viewModel)
    }
}

@Composable
private fun SetupScreen(state: UiState, viewModel: ForemanViewModel) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Foreman", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                "Connect to Codex on your Linux host.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.DarkGray,
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
private fun SessionsScreen(state: UiState, viewModel: ForemanViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Foreman", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = state.connected) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { viewModel.setNewSession(true) }, enabled = state.connected) {
                        Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                },
            )
        },
        containerColor = ForemanBackground,
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    sessionSection("Active", active, viewModel::openSession)
                    sessionSection("Waiting", waiting, viewModel::openSession)
                    sessionSection("Recent", recent, viewModel::openSession)
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
) {
    if (sessions.isEmpty()) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.DarkGray,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
    }
    items(sessions, key = { it.id }) { session ->
        SessionCard(session) { open(session.id) }
    }
}

@Composable
private fun SessionCard(session: SessionSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifBlank { "Untitled session" },
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(session.status)
            }
            Text(
                session.repository.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray,
            )
            session.lastActivity?.let {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(it * 1000)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailScreen(state: UiState, viewModel: ForemanViewModel) {
    val selected = state.selected
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            selected?.repository?.substringAfterLast('/') ?: "Session",
                            fontWeight = FontWeight.Bold,
                        )
                        if (selected != null) {
                            Text(
                                selected.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.DarkGray,
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
                },
            )
        },
        bottomBar = {
            if (selected != null) PromptBox(
                working = selected.status == "working",
                enabled = state.connected && !state.submitting,
                send = viewModel::send,
            )
        },
        containerColor = ForemanBackground,
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
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(item: ConversationItem) {
    when (item.kind) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                item.text,
                modifier =
                    Modifier.fillMaxWidth(0.86f)
                        .background(UserBubble, RoundedCornerShape(16.dp))
                        .padding(14.dp),
            )
        }
        "assistant" -> Text(
            item.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        "command", "tool" -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (item.kind == "command") "Command" else "Tool",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ForemanPurple,
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
                    color = Color.DarkGray,
                )
            }
        }
    }
}

@Composable
private fun PromptBox(
    working: Boolean,
    enabled: Boolean,
    send: (String, () -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Surface(shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (working) "Steer this turn…" else "Message Foreman…") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            Button(
                onClick = { send(text) { text = "" } },
                enabled = enabled && text.isNotBlank(),
            ) {
                Text(if (working) "Steer" else "Send")
            }
        }
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
    Surface(color = Color(0xFFFFE4E4)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message ?: "Disconnected", modifier = Modifier.weight(1f), color = Color(0xFF991B1B))
            FilledTonalButton(onClick = reconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    if (message != null) {
        Text(message, color = Color(0xFFB91C1C), style = MaterialTheme.typography.bodySmall, modifier = modifier)
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
                                color = Color.DarkGray,
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
