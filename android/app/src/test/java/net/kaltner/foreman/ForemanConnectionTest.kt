package net.kaltner.foreman

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ForemanConnectionTest {
    @Test
    fun wireMessagesAlwaysIncludeProtocolVersion() {
        val encoded =
            Json.encodeToString(
                WireMessage(version = 1, id = "one", type = "hello"),
            )
        assertTrue(encoded.contains("\"version\":1"))
    }

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
        assertEquals(16 * 1024 * 1024, MAX_FRAME_BYTES)
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
    fun imageSizingAndEncodedLimitsAreDeterministic() {
        assertEquals(1600 to 900, scaledImageSize(1600, 900))
        assertEquals(2048 to 1024, scaledImageSize(4096, 2048))
        assertEquals(4, imageSampleSize(10_000, 5_000))
        assertEquals(
            8,
            encodedImageBytes(
                listOf(
                    ImagePayload("image/jpeg", "YWJj"),
                    ImagePayload("image/png", "ZGVm"),
                ),
            ),
        )
        assertEquals(6, maximumDecodedImageBytes(8))
        val bounded = BoundedImageOutputStream(3)
        bounded.write(byteArrayOf(1, 2, 3))
        assertThrows(ImageBudgetExceeded::class.java) {
            bounded.write(4)
        }
        assertEquals(3, bounded.size())
    }

    @Test
    fun routeSelectionKeepsOnlySupportedEffortAndForwardsPromptOverrides() {
        val model =
            ModelInfo(
                id = "gpt-test",
                displayName = "GPT Test",
                reasoningEfforts = listOf("low", "high"),
                defaultReasoningEffort = "high",
                inputModalities = listOf("text", "image"),
            )
        assertEquals("low", compatibleEffort(model, "low"))
        assertEquals("high", compatibleEffort(model, "ultra"))
        val session =
            SessionSummary(
                id = "thread-1",
                repository = "/projects/example",
                title = "Example",
                status = "idle",
            )
        val payload =
            turnPayload(
                session,
                "Inspect",
                listOf(ImagePayload("image/jpeg", "YWJj")),
                steering = false,
                accessLevel = "auto",
                model = model.id,
                effort = "high",
            )
        assertEquals("gpt-test", payload.getValue("model").jsonPrimitive.content)
        assertEquals("high", payload.getValue("reasoningEffort").jsonPrimitive.content)
        assertEquals("auto", payload.getValue("accessLevel").jsonPrimitive.content)
        assertEquals(
            "YWJj",
            payload.getValue("images").jsonArray.single().jsonObject
                .getValue("data").jsonPrimitive.content,
        )

        val steering =
            turnPayload(
                session.copy(status = "working", activeTurnId = "turn-1"),
                "",
                listOf(ImagePayload("image/png", "YWJj")),
                steering = true,
                accessLevel = "full",
                model = model.id,
                effort = "high",
            )
        assertEquals("turn-1", steering.getValue("turnId").jsonPrimitive.content)
        assertFalse(steering.containsKey("model"))
        assertFalse(steering.containsKey("reasoningEffort"))
        assertFalse(steering.containsKey("accessLevel"))

        val accessLevels =
            listOf(
                AccessLevelInfo("ask", "Ask for approval"),
                AccessLevelInfo("auto", "Approve for me"),
                AccessLevelInfo("full", "Full access"),
            )
        val routed =
            UiState(composerAccessLevel = "ask")
                .withAccessLevelsAndSessionAccess(
                    accessLevels,
                    session.copy(accessLevel = "full"),
                )
        assertEquals("full", routed.composerAccessLevel)
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
                            buildJsonObject {
                                put("server", "Foreman")
                                put(
                                    "capabilities",
                                    buildJsonObject {
                                        put("archive", true)
                                        put("delete", false)
                                    },
                                )
                            }
                        }
                    FrameCodec.write(
                        socket.getOutputStream(),
                        json.encodeToString(
                            WireMessage(
                                version = 1,
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
            assertEquals(setOf("archive"), client.capabilities)
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
        assertEquals("", session.activityLabel)
        assertEquals("", session.activityText)
    }

    @Test
    fun sessionDisplayTitleUsesTheCodexTitleInsteadOfTheRepositoryName() {
        val session =
            SessionSummary(
                id = "thread-1",
                repository = "/home/user",
                title = "Fix the Foreman header",
                status = "working",
            )

        assertEquals("Fix the Foreman header", sessionDisplayTitle(session))
        assertEquals("Untitled session", sessionDisplayTitle(session.copy(title = "")))
        assertEquals("Session", sessionDisplayTitle(null))
    }

    @Test
    fun parsesSupportedMarkdownBlocksAndRejectsUnsafeLinks() {
        val blocks =
            parseMarkdown(
                """
                # Heading

                A **bold** paragraph.

                - first
                2. second

                ```kotlin
                val answer = 42
                ```
                """.trimIndent(),
            )

        assertEquals(MarkdownBlock.Heading(1, "Heading"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("A **bold** paragraph."), blocks[1])
        assertEquals(MarkdownBlock.ListItem("\u2022", "first"), blocks[2])
        assertEquals(MarkdownBlock.ListItem("2.", "second"), blocks[3])
        assertEquals(MarkdownBlock.Code("kotlin", "val answer = 42"), blocks[4])
        assertEquals("https://example.com/docs", safeMarkdownUrl("https://example.com/docs"))
        assertNull(safeMarkdownUrl("file:///etc/passwd"))
        assertNull(safeMarkdownUrl("javascript:alert(1)"))
        assertNull(safeMarkdownUrl("intent://settings"))
    }

    @Test
    fun stylesInlineMarkdownForCompactLiveStatus() {
        val rendered =
            styledInlineMarkdown(
                "**Implementing bulk session insertion helper**",
                color = Color.White,
                linkColor = Color.Blue,
                codeColor = Color.Gray,
            )

        assertEquals("Implementing bulk session insertion helper", rendered.text)
        assertTrue(
            rendered.spanStyles.any {
                it.item.fontWeight == FontWeight.Bold &&
                    rendered.text.substring(it.start, it.end) ==
                    "Implementing bulk session insertion helper"
            },
        )
    }

    @Test
    fun activityEventsRestoreWorkingStatusAndActiveSessionsCannotBeManaged() {
        assertTrue(eventShowsWorkingActivity("assistant.delta"))
        assertTrue(eventShowsWorkingActivity("item"))
        assertTrue(eventShowsWorkingActivity("activity"))
        assertFalse(eventShowsWorkingActivity("status"))
        assertFalse(sessionCanBeManaged("working"))
        assertFalse(sessionCanBeManaged("waiting"))
        assertTrue(sessionCanBeManaged("completed"))
        assertTrue(sessionActionSupported(setOf("archive"), SessionAction.Archive))
        assertFalse(sessionActionSupported(setOf("archive"), SessionAction.Delete))
        assertFalse(sessionActionSupported(emptySet(), SessionAction.Archive))
        assertTrue(sessionActionCanBeConfirmed(true, setOf("delete"), SessionAction.Delete))
        assertFalse(sessionActionCanBeConfirmed(false, setOf("delete"), SessionAction.Delete))
        assertFalse(sessionActionCanBeConfirmed(true, emptySet(), SessionAction.Delete))
    }

    @Test
    fun foregroundSynchronizationReplacesStaleOverviewAndSelectedStatus() {
        val stale =
            UiState(
                screen = Screen.Detail,
                loading = true,
                sessions =
                    listOf(
                        SessionSummary(
                            id = "thread-1",
                            repository = "/projects/example",
                            title = "Example",
                            status = "idle",
                        ),
                    ),
                selected =
                    SessionSummary(
                        id = "thread-1",
                        repository = "/projects/example",
                        title = "Example",
                        status = "interrupted",
                    ),
            )
        val working =
            SessionSummary(
                id = "thread-1",
                repository = "/projects/example",
                title = "Example",
                status = "working",
                activeTurnId = "turn-live",
            )

        val synchronized =
            stale.withSynchronizedSessions(
                sessions = listOf(working),
                repositories = emptyList(),
                selectedSessionId = working.id,
                selectedSession = working,
            )

        assertEquals("working", synchronized.sessions.single().status)
        assertEquals("working", synchronized.selected?.status)
        assertEquals("turn-live", synchronized.selected?.activeTurnId)
        assertEquals(Screen.Detail, synchronized.screen)
        assertFalse(synchronized.loading)
    }

    @Test
    fun choosesCompactLiveActivityLabels() {
        val base =
            SessionSummary(
                id = "thread-1",
                repository = "/projects/example",
                title = "Example",
                status = "working",
            )
        assertEquals("Thinking", liveActivityLabel(base))
        assertEquals(
            "Planning",
            liveActivityLabel(base.copy(activityLabel = "Planning")),
        )
        assertEquals(
            "Running command",
            liveActivityLabel(
                base.copy(
                    messages =
                        listOf(
                            ConversationItem(
                                id = "command-1",
                                kind = "command",
                                description = "git status",
                                status = "inProgress",
                            ),
                        ),
                ),
            ),
        )
        assertEquals(
            "Searching",
            liveActivityLabel(
                base.copy(
                    messages =
                        listOf(
                            ConversationItem(
                                id = "search-1",
                                kind = "tool",
                                description = "Web search",
                                status = "inProgress",
                            ),
                        ),
                ),
            ),
        )
        assertEquals(
            "Planning direct main update strategy",
            liveActivityMessage(
                base.copy(
                    activityText =
                        "Checking repository state\nPlanning direct main update strategy",
                ),
            ),
        )
        assertNull(liveActivityMessage(base))
    }

    @Test
    fun mapsOnlyAttentionAndTerminalStatusesToNotifications() {
        assertEquals(
            "Foreman needs your attention",
            monitorOutcome("waiting")?.title,
        )
        assertEquals("Foreman turn completed", monitorOutcome("completed")?.title)
        assertEquals("Foreman turn failed", monitorOutcome("failed")?.title)
        assertEquals("Foreman turn interrupted", monitorOutcome("interrupted")?.title)
        assertEquals(null, monitorOutcome("working"))
        assertEquals(null, monitorOutcome("unknown"))
    }

    @Test
    fun monitorLifecycleRequiresActiveConfirmationBeforeCompleting() {
        val lifecycle = MonitorLifecycle()

        lifecycle.monitor("session-1", active = false)
        assertNull(lifecycle.status("session-1", "completed"))
        assertTrue(lifecycle.contains("session-1"))

        lifecycle.monitor("session-1", active = true)
        lifecycle.monitor("session-1", active = false)
        assertEquals(
            "Foreman turn completed",
            lifecycle.status("session-1", "completed")?.title,
        )
        assertTrue(lifecycle.isEmpty())
    }

    @Test
    fun promptFailureCancellationPreventsLateStaleNotifications() {
        val lifecycle = MonitorLifecycle()

        lifecycle.monitor("session-1", active = false)
        assertTrue(lifecycle.cancel("session-1"))
        assertNull(lifecycle.status("session-1", "working"))
        assertNull(lifecycle.status("session-1", "completed"))
        assertFalse(lifecycle.contains("session-1"))
    }

    @Test
    fun monitorLifecycleCleansUpOnlyTheCompletedSession() {
        val lifecycle = MonitorLifecycle()
        lifecycle.monitor("session-1", active = true)
        lifecycle.monitor("session-2", active = true)

        assertEquals(
            "Foreman needs your attention",
            lifecycle.status("session-1", "waiting")?.title,
        )
        assertFalse(lifecycle.contains("session-1"))
        assertTrue(lifecycle.contains("session-2"))
        assertEquals(setOf("session-2"), lifecycle.sessionIds())
    }

    @Test
    fun reconnectBackoffCapsAndResetsDeterministically() {
        val lifecycle = MonitorLifecycle()

        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            List(6) { lifecycle.nextReconnectDelay() },
        )
        lifecycle.resetReconnectDelay()
        assertEquals(2_000L, lifecycle.nextReconnectDelay())
    }
}
