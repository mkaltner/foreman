package net.kaltner.foreman

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForemanConnectionTest {
    @Test
    fun parsesSupportedHostForms() {
        assertEquals(HostPort("192.168.1.59", 8765), parseHost("192.168.1.59"))
        assertEquals(HostPort("codex.local", 9999), parseHost("codex.local:9999"))
        assertEquals(HostPort("::1", 8765), parseHost("[::1]"))
        assertThrows(IllegalArgumentException::class.java) { parseHost("http://codex.local") }
        assertThrows(IllegalArgumentException::class.java) { parseHost("codex.local:99999") }
    }

    @Test
    fun framingRoundTripsAndRejectsOversizeInput() {
        val output = ByteArrayOutputStream()
        FrameCodec.write(output, """{"version":1}""")
        assertEquals(
            """{"version":1}""",
            FrameCodec.read(ByteArrayInputStream(output.toByteArray())),
        )
        assertThrows(java.io.IOException::class.java) {
            FrameCodec.read(ByteArrayInputStream("12345\n".toByteArray()), maximum = 4)
        }
    }

    @Test
    fun pairsOverRawTcp() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            server.accept().use { socket ->
                repeat(2) {
                    val request =
                        json.decodeFromString<WireMessage>(
                            FrameCodec.read(socket.getInputStream())!!,
                        )
                    val payload =
                        if (request.type == "pair") {
                            buildJsonObject { put("deviceToken", "fmt_test") }
                        } else {
                            buildJsonObject { put("server", "Foreman") }
                        }
                    FrameCodec.write(
                        socket.getOutputStream(),
                        json.encodeToString(
                            WireMessage(
                                id = request.id,
                                type = "${request.type}.result",
                                payload = payload,
                            ),
                        ),
                    )
                }
            }
        }
        val client = ForemanClient(this, {}, {})
        try {
            assertEquals(
                "fmt_test",
                client.pair("127.0.0.1:${server.localPort}", "fmp_test", "Phone"),
            )
        } finally {
            client.close()
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun mapsSessionListPayload() {
        val json = Json { ignoreUnknownKeys = true }
        val session =
            json.decodeFromString<SessionSummary>(
                """
                {
                  "id":"thread-1",
                  "repository":"/projects/example",
                  "title":"First prompt",
                  "status":"working",
                  "lastActivity":123,
                  "attention":false
                }
                """.trimIndent(),
            )
        assertEquals("thread-1", session.id)
        assertEquals("working", session.status)
        assertEquals(emptyList<ConversationItem>(), session.messages)
    }
}
