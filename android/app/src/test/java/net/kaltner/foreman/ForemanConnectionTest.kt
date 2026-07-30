package net.kaltner.foreman

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Calendar
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
    fun savedHostsKeepStableIdentityPortsAndRedactTokens() {
        val host =
            SavedHost(
                id = "host-home",
                displayName = "Home server",
                host = "2001:db8::1",
                tcpPort = 9765,
                webPort = 9766,
                deviceToken = "fmt_secret",
                pairedAt = 1_720_000_000_000,
                lastConnectedAt = null,
                lastKnownStatus = "disconnected",
                runtimeMode = null,
                isDefault = true,
            )

        assertEquals("[2001:db8::1]:9765", host.tcpEndpoint())
        assertEquals("host-home", host.summary().id)
        assertFalse(host.summary().toString().contains("fmt_secret"))
        assertFalse(host.toString().contains("fmt_secret"))
        assertTrue(host.toString().contains("<redacted>"))
    }

    @Test
    fun hostDisplayNamesDescribeTheServerInsteadOfTheAndroidClient() {
        assertEquals("Local Foreman", suggestedHostDisplayName("localhost"))
        assertEquals("Local Foreman", suggestedHostDisplayName("127.0.0.1"))
        assertEquals("Local Foreman", suggestedHostDisplayName("::1"))
        assertEquals("workstation.local", suggestedHostDisplayName("workstation.local"))
        assertEquals("192.168.1.59", suggestedHostDisplayName("192.168.1.59"))
    }

    @Test
    fun accentPresetsAreDistinctAndProvideLightAndDarkRoles() {
        assertEquals(AccentColor.Purple, parseAccentColor(null))
        assertEquals(AccentColor.Purple, parseAccentColor("unsupported"))
        assertEquals(AccentColor.Teal, parseAccentColor("Teal"))

        val accents = AccentColor.values().toList()
        assertEquals(
            accents.size,
            accents.map { foremanColorScheme(it, darkTheme = false).primary }.distinct().size,
        )
        accents.forEach { accent ->
            val palette = accentPalette(accent)
            val light = foremanColorScheme(accent, darkTheme = false)
            val dark = foremanColorScheme(accent, darkTheme = true)

            assertEquals(palette.light.primary, light.primary)
            assertEquals(
                mutedAccentContainer(palette.light, darkTheme = false),
                light.secondaryContainer,
            )
            assertEquals(palette.light.onContainer, light.onSecondaryContainer)
            assertEquals(palette.dark.primary, dark.primary)
            assertEquals(
                mutedAccentContainer(palette.dark, darkTheme = true),
                dark.secondaryContainer,
            )
            assertEquals(palette.dark.onContainer, dark.onSecondaryContainer)
            assertFalse(light.primaryContainer == light.secondaryContainer)
            assertFalse(dark.primaryContainer == dark.secondaryContainer)
        }
    }

    @Test
    fun composerUsesConversationalKeyboardDefaults() {
        assertEquals(KeyboardCapitalization.Sentences, composerKeyboardOptions.capitalization)
        assertEquals(true, composerKeyboardOptions.autoCorrectEnabled)
        assertEquals(KeyboardType.Text, composerKeyboardOptions.keyboardType)
        assertTrue(UiPreferences().hapticsEnabled)
    }

    @Test
    fun forgettingConnectionClearsHostStateButPreservesUiPreferences() {
        val forgotten =
            UiState(
                screen = Screen.Detail,
                host = "foreman.local:8765",
                pairingKey = "123456",
                deviceName = "Work phone",
                connected = true,
                hasSavedConnection = true,
                loading = true,
                submitting = true,
                error = "Old connection error",
                showNewSession = true,
                themeMode = ThemeMode.Dark,
                monitorActiveTurns = true,
                pendingSessionAction =
                    PendingSessionAction("session-1", "Example", SessionAction.Archive),
                capabilities = setOf("archive", "delete"),
            ).withForgottenConnection()

        assertEquals(Screen.Setup, forgotten.screen)
        assertEquals("", forgotten.host)
        assertEquals("", forgotten.pairingKey)
        assertEquals("Android", forgotten.deviceName)
        assertFalse(forgotten.connected)
        assertFalse(forgotten.hasSavedConnection)
        assertFalse(forgotten.loading)
        assertFalse(forgotten.submitting)
        assertNull(forgotten.error)
        assertFalse(forgotten.showNewSession)
        assertNull(forgotten.pendingSessionAction)
        assertTrue(forgotten.capabilities.isEmpty())
        assertEquals(ThemeMode.Dark, forgotten.themeMode)
        assertTrue(forgotten.monitorActiveTurns)
    }

    @Test
    fun sessionHapticsOnlyFireForActiveTerminalTransitions() {
        assertEquals(SessionHapticEvent.Completed, sessionHapticEvent("working", "completed"))
        assertEquals(SessionHapticEvent.Completed, sessionHapticEvent("working", "idle"))
        assertEquals(SessionHapticEvent.Attention, sessionHapticEvent("working", "waiting"))
        assertEquals(SessionHapticEvent.Failed, sessionHapticEvent("working", "failed"))
        assertEquals(SessionHapticEvent.Failed, sessionHapticEvent("waiting", "failed"))

        assertNull(sessionHapticEvent(null, "failed"))
        assertNull(sessionHapticEvent("completed", "failed"))
        assertNull(sessionHapticEvent("working", "working"))
        assertNull(sessionHapticEvent("working", "interrupted"))
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
        assertEquals(
            "Build Foreman monitoring dashboard",
            sessionDisplayTitle(session.copy(title = "Build Foreman monitoring dashboard")),
        )
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
    fun parsesSupportedAppDirectivesWithoutLeakingThemIntoMarkdown() {
        val blocks =
            parseMarkdown(
                """
                Validation passed.

                ::git-commit{cwd="/home/user/projects/foreman"}
                ::git-push{cwd="/home/user/projects/foreman" branch="agent/android"}
                """.trimIndent(),
            )

        assertEquals(MarkdownBlock.Paragraph("Validation passed."), blocks[0])
        assertEquals(
            MarkdownBlock.AppDirective("git-commit", mapOf("cwd" to "/home/user/projects/foreman")),
            blocks[1],
        )
        assertEquals(
            MarkdownBlock.AppDirective(
                "git-push",
                mapOf("cwd" to "/home/user/projects/foreman", "branch" to "agent/android"),
            ),
            blocks[2],
        )
    }

    @Test
    fun preservesUnsupportedAppDirectivesAsMarkdown() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph("::unknown{value=\"safe\"}")),
            parseMarkdown("::unknown{value=\"safe\"}"),
        )
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
    fun linkifiesBareWebUrlsWithoutSwallowingPunctuationOrUnsafeSchemes() {
        val rendered =
            styledInlineMarkdown(
                "Open HTTPS://example.com/docs, not javascript:alert(1).",
                color = Color.White,
                linkColor = Color.Blue,
                codeColor = Color.Gray,
            )

        assertEquals("Open HTTPS://example.com/docs, not javascript:alert(1).", rendered.text)
        val links = rendered.getLinkAnnotations(0, rendered.length)
        assertEquals(1, links.size)
        assertEquals("HTTPS://example.com/docs", (links.single().item as LinkAnnotation.Url).url)
        assertEquals("HTTPS://example.com/docs", rendered.text.substring(links.single().start, links.single().end))
        assertEquals(
            "https://en.wikipedia.org/wiki/Foreman_(software)",
            trimTrailingUrlPunctuation("https://en.wikipedia.org/wiki/Foreman_(software)."),
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
    fun unknownStatusDiscoversSessionsWithoutReplacingLiveRows() {
        val live =
            SessionSummary(
                id = "thread-live",
                repository = "/projects/example",
                title = "Live",
                status = "working",
            )
        val external =
            SessionSummary(
                id = "thread-desktop",
                repository = "/projects/example",
                title = "Desktop",
                status = "working",
            )
        val state = UiState(connected = true, sessions = listOf(live))

        assertTrue(state.shouldDiscoverSession(external.id, "status"))
        assertFalse(state.shouldDiscoverSession(external.id, "activity"))
        assertFalse(state.shouldDiscoverSession(live.id, "status"))

        val discovered =
            state.withDiscoveredSessions(
                listOf(live.copy(status = "idle"), external),
            )
        assertEquals(listOf(external.id, live.id), discovered.sessions.map { it.id })
        assertEquals("working", discovered.sessions.last().status)
        assertFalse(discovered.shouldDiscoverSession(external.id, "status"))
    }

    @Test
    fun concurrentSessionDiscoveriesRemainQueued() {
        val queue = SessionDiscoveryQueue()
        queue.enqueue("thread-first")
        val firstRequest = queue.targets()

        queue.enqueue("thread-second")
        queue.recordAttempt(firstRequest, setOf("thread-first"))

        assertEquals(setOf("thread-second"), queue.targets())
        repeat(3) {
            val request = queue.targets()
            queue.recordAttempt(request, emptySet())
            assertEquals(setOf("thread-second"), queue.targets())
        }
        queue.recordAttempt(queue.targets(), emptySet())
        assertTrue(queue.targets().isEmpty())
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
    fun notificationPreferencesCoverDefaultsEveryToggleAndRepositoryInheritance() {
        val defaults = NotificationPreferences()
        assertTrue(defaults.eventEnabled(NotificationEvent.Approval, "/repo"))
        assertTrue(defaults.eventEnabled(NotificationEvent.Failure, "/repo"))
        assertTrue(defaults.eventEnabled(NotificationEvent.Completion, "/repo"))
        assertFalse(defaults.eventEnabled(NotificationEvent.Interruption, "/repo"))
        assertFalse(defaults.eventEnabled(NotificationEvent.LongRunning, "/repo"))

        val toggled = defaults.copy(
            notifyApprovals = false,
            notifyFailures = false,
            notifyCompletions = false,
            notifyInterruptions = true,
            notifyLongRunning = true,
            repositoryOverrides = mapOf(
                "/repo" to RepositoryNotificationOverride(
                    notifyCompletions = true,
                    notifyInterruptions = false,
                ),
            ),
        )
        assertFalse(toggled.eventEnabled(NotificationEvent.Approval, "/other"))
        assertFalse(toggled.eventEnabled(NotificationEvent.Failure, "/other"))
        assertFalse(toggled.eventEnabled(NotificationEvent.Completion, "/other"))
        assertTrue(toggled.eventEnabled(NotificationEvent.Interruption, "/other"))
        assertTrue(toggled.eventEnabled(NotificationEvent.LongRunning, "/other"))
        assertTrue(toggled.eventEnabled(NotificationEvent.Completion, "/repo"))
        assertFalse(toggled.eventEnabled(NotificationEvent.Interruption, "/repo"))
    }

    @Test
    fun notificationQuietHoursSupportOvernightRangesAndCriticalBypass() {
        val preferences = NotificationPreferences(
            quietHoursEnabled = true,
            quietStart = "22:00",
            quietEnd = "07:00",
            notifyInterruptions = true,
        )
        val late = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 30, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val morning = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 31, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertTrue(preferences.isQuietTime(late))
        assertFalse(preferences.isQuietTime(morning))
        assertFalse(preferences.shouldNotify(NotificationEvent.Failure, "/repo", late))
        val bypass = preferences.copy(criticalBypassQuietHours = true)
        assertTrue(bypass.shouldNotify(NotificationEvent.Approval, "/repo", late))
        assertTrue(bypass.shouldNotify(NotificationEvent.Failure, "/repo", late))
        assertFalse(bypass.shouldNotify(NotificationEvent.Interruption, "/repo", late))
    }

    @Test
    fun notificationPreferencesSurviveProcessRecreationSerialization() {
        val saved = NotificationPreferences(
            notifyInterruptions = true,
            notifyLongRunning = true,
            longRunningMinutes = 27,
            quietHoursEnabled = true,
            repositoryOverrides = mapOf(
                "/workspace/foreman" to RepositoryNotificationOverride(notifyCompletions = false),
            ),
        )
        val restored = decodeNotificationPreferences(encodeNotificationPreferences(saved))
        assertEquals(saved, restored)
        assertEquals(NotificationPreferences(), decodeNotificationPreferences("corrupt"))
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
    fun monitorLifecycleKeepsWaitingApprovalSessionsUntilTerminal() {
        val lifecycle = MonitorLifecycle()
        lifecycle.monitor("session-1", active = true)
        lifecycle.monitor("session-2", active = true)

        assertEquals(
            "Foreman needs your attention",
            lifecycle.status("session-1", "waiting")?.title,
        )
        assertTrue(lifecycle.contains("session-1"))
        assertTrue(lifecycle.contains("session-2"))
        assertEquals(setOf("session-1", "session-2"), lifecycle.sessionIds())
        lifecycle.status("session-1", "completed")
        assertEquals(setOf("session-2"), lifecycle.sessionIds())
    }

    @Test
    fun approvalPermissionSelectionBuildsOnlyTheChosenSubset() {
        val approval =
            ApprovalRequest(
                id = "apr-safe",
                sessionId = "session-1",
                type = "permission",
                title = "Permissions requested",
                createdAt = 1,
                status = "pending",
                requestedPermissions =
                    buildJsonObject {
                        put(
                            "fileSystem",
                            buildJsonObject {
                                put("write", kotlinx.serialization.json.buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("/workspace/one"))
                                    add(kotlinx.serialization.json.JsonPrimitive("/workspace/two"))
                                })
                            },
                        )
                        put("network", buildJsonObject { put("enabled", true) })
                    },
            )
        val choices = permissionChoices(approval)
        assertEquals(listOf("write-0", "write-1", "network"), choices.map { it.id })
        val selected = selectedPermissions(approval, choices, setOf("write-0"))
        assertEquals(
            listOf("/workspace/one"),
            selected["fileSystem"]!!.jsonObject["write"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(selected["network"])
    }

    @Test
    fun approvalLabelsAndNotificationTextArePrivacySafe() {
        assertEquals("Waiting for command approval", approvalAttentionLabel("command"))
        assertEquals("Waiting for file-change approval", approvalAttentionLabel("fileChange"))
        assertEquals("Waiting for permission grant", approvalAttentionLabel("permission"))
        val notification = approvalNotificationText()
        assertEquals("Foreman needs your attention", notification.title)
        assertEquals("A monitored session needs approval or input.", notification.detail)
        assertFalse(notification.detail.contains("/private"))
        assertFalse(notification.detail.contains("command"))
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

    @Test
    fun sessionSearchCombinesTranscriptRepositoryStatusAndDateFilters() {
        val repositories = listOf(
            RepositoryInfo("foreman", "foreman", "foreman", "main", false),
        )
        val active = SessionSummary(
            id = "active",
            repository = "/projects/foreman/src",
            title = "Build socket support",
            status = "working",
            lastActivity = 1_700_000_300,
        )
        val waiting = SessionSummary(
            id = "waiting",
            repository = "/home/operator",
            title = "Review release",
            status = "waiting",
            lastActivity = 1_700_000_200,
        )
        val filters = SessionSearchFilters(
            query = "websocket",
            repository = "/projects/foreman",
            status = SessionSearchStatus.Active,
            dateRange = SessionDateRange.Custom,
            dateFrom = "2023-11-14",
            dateTo = "2023-11-15",
            pinnedOnly = true,
        )
        val visible = filterSessions(
            sessions = listOf(active, waiting),
            filters = filters,
            pinnedIds = setOf("active"),
            hiddenIds = emptySet(),
            results = listOf(
                SessionSearchResult(
                    active,
                    listOf(SessionSearchMatch("user", "Add a WebSocket endpoint")),
                ),
            ),
            repositories = repositories,
            repositoryRoot = "/projects",
            nowMillis = 1_700_000_400_000,
        )
        assertEquals(listOf("active"), visible.map { it.session.id })
        assertEquals("Add a WebSocket endpoint", visible.single().matches.single().snippet)
    }

    @Test
    fun sessionSearchKeepsPinsFirstAndHiddenSessionsRestorable() {
        val sessions = listOf(
            SessionSummary("active", "/repo", "Active", "working", 300),
            SessionSummary("waiting", "/repo", "Waiting", "waiting", 200),
            SessionSummary("done", "/repo", "Done", "idle", 100),
        )
        val visible = filterSessions(
            sessions,
            SessionSearchFilters(),
            setOf("done"),
            setOf("waiting"),
            emptyList(),
            emptyList(),
            "",
        )
        assertEquals(listOf("done", "active"), visible.map { it.session.id })
        val hidden = filterSessions(
            sessions,
            SessionSearchFilters(hiddenOnly = true),
            emptySet(),
            setOf("waiting"),
            emptyList(),
            emptyList(),
            "",
        )
        assertEquals(listOf("waiting"), hidden.map { it.session.id })
    }

    @Test
    fun liveStatusChangesEnterAndLeaveAndroidFilters() {
        val session = SessionSummary("one", "/repo", "Live", "working", 100)
        val filters = SessionSearchFilters(status = SessionSearchStatus.Waiting)
        assertTrue(filterSessions(listOf(session), filters, emptySet(), emptySet(), emptyList(), emptyList(), "").isEmpty())
        val waiting = session.copy(status = "waiting", attention = true)
        assertEquals(
            listOf("one"),
            filterSessions(listOf(waiting), filters, emptySet(), emptySet(), emptyList(), emptyList(), "")
                .map { it.session.id },
        )
    }

    @Test
    fun completedFilterIncludesCanonicalIdleSummaries() {
        val completed = SessionSummary("done", "/repo", "Done", "idle", 100)
        assertEquals(
            listOf("done"),
            filterSessions(
                listOf(completed),
                SessionSearchFilters(status = SessionSearchStatus.Completed),
                emptySet(),
                emptySet(),
                emptyList(),
                emptyList(),
                "",
            ).map { it.session.id },
        )
    }

    @Test
    fun clientOnlyOrganizationFiltersDoNotChangeTranscriptRequest() {
        val base = SessionSearchFilters(query = "Socket", repository = "/repo")
        assertEquals(
            sessionSearchRequestKey(base),
            sessionSearchRequestKey(base.copy(pinnedOnly = true, hiddenOnly = true)),
        )
        assertFalse(
            sessionSearchRequestKey(base) ==
                sessionSearchRequestKey(base.copy(status = SessionSearchStatus.Active)),
        )
    }
}
