package net.kaltner.foreman

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val DEFAULT_PORT = 8765
const val MAX_FRAME_BYTES = 16 * 1024 * 1024

data class HostPort(val host: String, val port: Int)

fun parseHost(value: String): HostPort {
    val text = value.trim()
    require(text.isNotEmpty()) { "Host is required" }
    require(!text.contains("://")) { "Enter a host name or IP address, not a URL" }
    if (text.startsWith("[")) {
        val end = text.indexOf(']')
        require(end > 1) { "Invalid IPv6 host" }
        val host = text.substring(1, end)
        val port = if (end + 1 < text.length) {
            require(text[end + 1] == ':') { "Invalid host" }
            text.substring(end + 2).toPort()
        } else {
            DEFAULT_PORT
        }
        return HostPort(host, port)
    }
    val colonCount = text.count { it == ':' }
    return if (colonCount == 1) {
        val separator = text.lastIndexOf(':')
        HostPort(text.substring(0, separator).ifBlank { error("Host is required") }, text.substring(separator + 1).toPort())
    } else {
        require(colonCount == 0) { "Wrap an IPv6 address in [brackets]" }
        HostPort(text, DEFAULT_PORT)
    }
}

private fun String.toPort(): Int {
    val port = toIntOrNull()
    require(port != null && port in 1..65535) { "Invalid port" }
    return port
}

object FrameCodec {
    fun read(input: InputStream, maximum: Int = MAX_FRAME_BYTES): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                if (buffer.size() == 0) return null
                throw EOFException("Connection closed during a frame")
            }
            if (byte == '\n'.code) {
                return buffer.toString(StandardCharsets.UTF_8.name())
            }
            if (buffer.size() >= maximum) throw IOException("Frame is too large")
            buffer.write(byte)
        }
    }

    fun write(output: OutputStream, text: String, maximum: Int = MAX_FRAME_BYTES) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maximum) { "Frame is too large" }
        output.write(bytes)
        output.write('\n'.code)
        output.flush()
    }
}

@Serializable
data class WireMessage(
    val version: Int,
    val id: String? = null,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RepositoryInfo(
    val id: String,
    val name: String,
    val path: String,
    val branch: String,
    val dirty: Boolean,
)

@Serializable
data class ImagePayload(
    val mimeType: String,
    val data: String,
)

@Serializable
data class ConversationItem(
    val id: String,
    val kind: String,
    val text: String = "",
    val description: String = "",
    val status: String = "",
    val exitCode: Int? = null,
    val turnId: String? = null,
    val images: List<ImagePayload> = emptyList(),
    val imageCount: Int = 0,
)

@Serializable
data class SessionSummary(
    val id: String,
    val repository: String,
    val title: String,
    val status: String,
    val lastActivity: Long? = null,
    val attention: Boolean = false,
    val messages: List<ConversationItem> = emptyList(),
    val activeTurnId: String? = null,
    val activityLabel: String = "",
    val activityText: String = "",
    val model: String? = null,
    val reasoningEffort: String? = null,
    val accessLevel: String? = null,
)

@Serializable
data class SessionSearchMatch(
    val kind: String,
    val snippet: String,
    val turnId: String? = null,
    val itemId: String? = null,
)

@Serializable
data class SessionSearchResult(
    val session: SessionSummary,
    val matches: List<SessionSearchMatch> = emptyList(),
)

@Serializable
data class AccessLevelInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
)

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val reasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val visible: Boolean = true,
    val isDefault: Boolean = false,
    val inputModalities: List<String> = emptyList(),
)

internal fun liveActivityLabel(session: SessionSummary): String {
    if (session.activityLabel.isNotBlank()) return session.activityLabel
    val activeItem =
        session.messages.lastOrNull {
            it.status.equals("inProgress", ignoreCase = true) ||
                it.status.equals("running", ignoreCase = true)
        }
    return when {
        activeItem?.kind == "command" -> "Running command"
        activeItem?.kind == "tool" &&
            activeItem.description.startsWith("Web search", ignoreCase = true) -> "Searching"
        activeItem?.kind == "tool" -> "Using tool"
        else -> "Thinking"
    }
}

internal fun liveActivityMessage(session: SessionSummary): String? =
    session.activityText.trim().lineSequence().lastOrNull { it.isNotBlank() }

class ForemanClient(
    private val scope: CoroutineScope,
    private val onEvent: (WireMessage) -> Unit,
    private val onDisconnect: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sequence = AtomicInteger()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<WireMessage>>()
    private val writeMutex = Mutex()
    private var socket: Socket? = null
    private var readerJob: Job? = null
    @Volatile private var closing = false
    @Volatile private var generation = 0
    var capabilities: Set<String> = emptySet()
        private set
    var runtimeMode: String? = null
        private set

    suspend fun pair(host: String, pairingKey: String, deviceName: String): String {
        open(host)
        hello()
        val result = request(
            "pair",
            buildJsonObject {
                put("pairingKey", pairingKey.trim())
                put("deviceName", deviceName.trim())
            },
        )
        return result.payload.getValue("deviceToken").jsonPrimitive.content
    }

    suspend fun authenticate(host: String, token: String) {
        open(host)
        hello()
        request("authenticate", buildJsonObject { put("deviceToken", token) })
    }

    private suspend fun hello() {
        val response = request("hello")
        runtimeMode = response.payload["codexRuntime"]?.jsonPrimitive?.content
        capabilities =
            response.payload["capabilities"]?.jsonObject?.entries
                ?.filter { (_, value) -> value.jsonPrimitive.content == "true" }
                ?.mapTo(mutableSetOf()) { it.key }
                ?: emptySet()
    }

    suspend fun request(type: String, payload: JsonObject = JsonObject(emptyMap())): WireMessage {
        val id = "android-${sequence.incrementAndGet()}"
        val deferred = CompletableDeferred<WireMessage>()
        pending[id] = deferred
        val message = WireMessage(version = 1, id = id, type = type, payload = payload)
        try {
            val output = socket?.getOutputStream() ?: error("Not connected")
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    FrameCodec.write(output, json.encodeToString(message))
                }
            }
            val response = withTimeout(120_000) { deferred.await() }
            if (response.type == "error") {
                throw IOException(
                    response.payload["message"]?.jsonPrimitive?.content ?: "Request failed",
                )
            }
            return response
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        closing = true
        generation += 1
        readerJob?.cancel()
        readerJob = null
        socket?.close()
        socket = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private suspend fun open(host: String) {
        close()
        capabilities = emptySet()
        runtimeMode = null
        closing = false
        val connectionGeneration = ++generation
        val endpoint = parseHost(host)
        val connected = withContext(Dispatchers.IO) {
            Socket().apply {
                keepAlive = true
                tcpNoDelay = true
                connect(InetSocketAddress(endpoint.host, endpoint.port), 10_000)
            }
        }
        socket = connected
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                val input = connected.getInputStream()
                while (true) {
                    val frame = FrameCodec.read(input) ?: break
                    val message = json.decodeFromString<WireMessage>(frame)
                    val id = message.id
                    if (id != null) {
                        pending.remove(id)?.complete(message)
                        continue
                    }
                    if (message.type == "error") {
                        throw IOException(
                            message.payload["message"]?.jsonPrimitive?.content
                                ?: "Foreman rejected the connection",
                        )
                    }
                    if (connectionGeneration == generation) onEvent(message)
                }
                if (!closing) throw EOFException("Foreman closed the connection")
            } catch (error: Exception) {
                if (!closing && socket === connected && connectionGeneration == generation) {
                    pending.values.forEach { it.completeExceptionally(error) }
                    pending.clear()
                    onDisconnect(error.message ?: "Disconnected")
                }
            }
        }
    }
}

fun WireMessage.eventObject(): JsonObject =
    payload["event"]?.jsonObject ?: JsonObject(emptyMap())
