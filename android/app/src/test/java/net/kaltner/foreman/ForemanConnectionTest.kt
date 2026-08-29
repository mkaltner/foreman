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
import kotlinx.serialization.json.buildJsonArray
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
    fun focusedSessionPresenceIsProviderAwareAndOnlyPublishedFromVisibleDetail() {
        val codex = SessionSummary("same", "/repo", "Codex", "working")
        val claude = codex.copy(provider = PROVIDER_CLAUDE_CODE)

        assertEquals(
            providerSessionKey(PROVIDER_CODEX, "same"),
            focusedSessionPresenceKey(true, Screen.Detail, codex),
        )
        assertEquals(
            providerSessionKey(PROVIDER_CLAUDE_CODE, "same"),
            focusedSessionPresenceKey(true, Screen.Detail, claude),
        )
        assertNull(focusedSessionPresenceKey(false, Screen.Detail, codex))
        assertNull(focusedSessionPresenceKey(true, Screen.Sessions, codex))
        assertNull(focusedSessionPresenceKey(true, Screen.Detail, null))
    }

    @Test
    fun presencePublisherKeepsAQueuedBackgroundClearPending() {
        val focused = providerSessionKey(PROVIDER_CODEX, "same")

        assertFalse(sessionPresenceSyncPending(true, focused, focused))
        assertTrue(sessionPresenceSyncPending(true, focused, null))
        assertTrue(sessionPresenceSyncPending(false, null, null))
    }

    @Test
    fun focusedSessionProjectionRejectsMalformedOrUnknownEntries() {
        val sessions =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("provider", PROVIDER_CODEX)
                        put("sessionId", "one")
                    },
                )
                add(
                    buildJsonObject {
                        put("provider", PROVIDER_CODEX)
                        put("sessionId", "one")
                    },
                )
                add(
                    buildJsonObject {
                        put("provider", PROVIDER_CLAUDE_CODE)
                        put("sessionId", "two")
                    },
                )
                add(
                    buildJsonObject {
                        put("provider", "unknown")
                        put("sessionId", "ignored")
                    },
                )
                add(
                    buildJsonObject {
                        put("provider", PROVIDER_CODEX)
                        put("sessionId", "")
                    },
                )
            }

        assertEquals(
            setOf(
                providerSessionKey(PROVIDER_CODEX, "one"),
                providerSessionKey(PROVIDER_CLAUDE_CODE, "two"),
            ),
            focusedSessionKeys(sessions),
        )
    }

    @Test
    fun focusedActivityGroupsOnlyRoutineSuccessfulWork() {
        val messages =
            listOf(
                ConversationItem("user", "user", text = "Please test"),
                ConversationItem("command", "command", description = "git status", status = "completed", exitCode = 0),
                ConversationItem("tool", "tool", description = "Read file", status = "completed"),
                ConversationItem("failed", "command", description = "run tests", status = "completed", exitCode = 1),
                ConversationItem("assistant", "assistant", text = "I found the issue"),
                ConversationItem("approval-item", "tool", description = "Protected", status = "completed"),
            )

        val focused =
            conversationBlocks(
                messages,
                ActivityDetail.Focused,
                protectedItemIds = setOf("approval-item"),
            )
        assertEquals(5, focused.size)
        assertEquals(listOf("command", "tool"), focused[1].items.map { it.id })
        assertTrue(focused[1].collapsedActivity)
        assertEquals("failed", focused[2].items.single().id)
        assertFalse(focused[2].collapsedActivity)
        assertEquals("approval-item", focused.last().items.single().id)

        val full = conversationBlocks(messages, ActivityDetail.Full)
        assertEquals(messages.map { it.id }, full.map { it.items.single().id })
        assertTrue(full.none { it.collapsedActivity })
    }

    @Test
    fun focusedActivityProgressivelyGroupsCompletedItemsFromActiveTurn() {
        val messages =
            listOf(
                ConversationItem("read", "tool", status = "completed", turnId = "turn-current"),
                ConversationItem("bash", "command", status = "completed", exitCode = 0, turnId = "turn-current"),
                ConversationItem("failed", "command", status = "failed", exitCode = 1, turnId = "turn-current"),
            )

        val blocks = conversationBlocks(messages, ActivityDetail.Focused)

        assertEquals(2, blocks.size)
        assertTrue(blocks.first().collapsedActivity)
        assertEquals(listOf("read", "bash"), blocks.first().items.map { it.id })
        assertFalse(blocks.last().collapsedActivity)
        assertEquals("failed", blocks.last().items.single().id)
    }

    @Test
    fun androidDashboardProjectsLiveAttentionAndRecentWork() {
        val now = 2_000_000_000_000L
        val working =
            SessionSummary(
                "working",
                "/repo",
                "Working",
                "working",
                lastActivity = (now - 5_000) / 1000,
                activeTurnStartedAt = (now - 60_000) / 1000,
            )
        val waiting =
            SessionSummary(
                "waiting",
                "/repo",
                "Waiting",
                "waiting",
                lastActivity = (now - 2_000) / 1000,
                activeTurnStartedAt = (now - 120_000) / 1000,
            )
        val failed =
            SessionSummary(
                "failed",
                "/repo",
                "Failed",
                "failed",
                lastActivity = (now - 1_000) / 1000,
                terminalAt = (now - 1_000) / 1000,
            )
        val interrupted =
            SessionSummary(
                "interrupted",
                "/repo",
                "Interrupted",
                "interrupted",
                lastActivity = now - 3_000,
                terminalAt = now - 3_000,
            )
        val oldCompletion =
            SessionSummary(
                "old",
                "/repo",
                "Old",
                "completed",
                terminalAt = now - ANDROID_DASHBOARD_RECENT_WINDOW_MS - 1,
            )
        val pendingRequest =
            SessionSummary(
                "pending-request",
                "/repo",
                "Request",
                "idle",
                lastActivity = now - 500,
            )

        val dashboard =
            projectAndroidDashboard(
                listOf(working, waiting, failed, interrupted, oldCompletion, pendingRequest),
                now,
                requestSessionIds = setOf(pendingRequest.id),
            )

        assertEquals(listOf("working"), dashboard.active.map { it.id })
        assertEquals(listOf("pending-request", "failed", "waiting"), dashboard.attention.map { it.id })
        assertEquals(listOf("failed", "interrupted"), dashboard.recent.map { it.id })
        assertEquals(2, dashboard.waitingCount)
        assertEquals(1, dashboard.failedCount)
        assertEquals("waiting", dashboard.oldestTurn?.id)
    }

    @Test
    fun dashboardDestinationSurvivesReconnectUnlessOpeningAConversation() {
        assertEquals(Screen.Overview, dashboardBackDestination())
        assertEquals(Screen.Dashboard, reconnectDestination(Screen.Dashboard, null))
        assertEquals(Screen.Sessions, reconnectDestination(Screen.Overview, null))
        assertEquals(Screen.Detail, reconnectDestination(Screen.Dashboard, "thread-1"))

        val synchronized =
            UiState(screen = Screen.Dashboard, loading = true)
                .withSynchronizedSessions(emptyList(), emptyList(), null, null)
        assertEquals(Screen.Dashboard, synchronized.screen)
        assertFalse(synchronized.loading)
    }

    @Test
    fun unifiedOverviewBackTargetPreservesTheScreenOpenedByHome() {
        assertEquals(
            OverviewReturnTarget("host-a", Screen.Sessions),
            overviewReturnTarget(Screen.Sessions, "host-a", null),
        )
        assertEquals(
            OverviewReturnTarget("host-a", Screen.Dashboard),
            overviewReturnTarget(Screen.Dashboard, "host-a", null),
        )
        assertEquals(
            OverviewReturnTarget("host-a", Screen.Detail, "thread-1"),
            overviewReturnTarget(Screen.Detail, "host-a", "thread-1"),
        )
        assertEquals(
            OverviewReturnTarget("host-a", Screen.Sessions),
            overviewReturnTarget(Screen.Detail, "host-a", null),
        )
        assertNull(overviewReturnTarget(Screen.Sessions, null, null))
        assertNull(overviewReturnTarget(Screen.Overview, "host-a", null))
        assertNull(overviewReturnTarget(Screen.Setup, "host-a", null))
        assertNull(overviewReturnTarget(Screen.Diagnostics, "host-a", null))
    }

    @Test
    fun unifiedOverviewReturnLifecycleIsHostBoundAndConsumedOnce() {
        val navigation = OverviewNavigationState()

        navigation.capture(Screen.Detail, "host-a", "thread-1")
        assertTrue(navigation.hasReturnTarget())
        assertEquals(
            OverviewReturnTarget("host-a", Screen.Detail, "thread-1"),
            navigation.consume("host-a"),
        )
        assertFalse(navigation.hasReturnTarget())
        assertNull(navigation.consume("host-a"))

        navigation.capture(Screen.Sessions, "host-a", null)
        navigation.invalidateForHost("host-b")
        assertFalse(navigation.hasReturnTarget())
        assertNull(navigation.consume("host-b"))

        navigation.capture(Screen.Sessions, "host-a", null)
        assertNull(navigation.consume("host-b"))
        assertFalse(navigation.hasReturnTarget())

        navigation.capture(Screen.Sessions, "host-a", null)
        navigation.capture(Screen.Dashboard, "host-a", null)
        assertEquals(
            OverviewReturnTarget("host-a", Screen.Dashboard),
            navigation.consume("host-a"),
        )

        navigation.capture(Screen.Sessions, "host-a", null)
        navigation.clear()
        assertFalse(navigation.hasReturnTarget())
    }

    @Test
    fun restartIsBlockedForActiveSessionsAndPendingInput() {
        assertFalse(restartBlocked(UiState()))
        assertTrue(
            restartBlocked(
                UiState(sessions = listOf(SessionSummary("thread-1", "/repo", "Active", "working", 1))),
            ),
        )
        assertTrue(
            restartBlocked(
                UiState(
                    inputs =
                        listOf(
                            InputRequest(
                                id = "input-1",
                                sessionId = "thread-1",
                                source = "codex",
                                title = "Choose",
                                supported = true,
                                createdAt = 1,
                                status = "pending",
                            ),
                        ),
                ),
            ),
        )
    }

    @Test
    fun zeroFieldMcpConfirmationUsesExplicitAllowAction() {
        val input =
            InputRequest(
                id = "inp-confirm",
                sessionId = "thread-1",
                source = "mcp",
                title = "Confirmation requested",
                message = "Allow GitHub to create a pull request?",
                fields = emptyList(),
                supported = true,
                canDecline = true,
                canCancel = true,
                createdAt = 1,
                status = "pending",
            )

        assertEquals("Allow", inputSubmitLabel(input))
        assertEquals("Waiting for user input", inputAttentionLabel(input))
    }

    @Test
    fun sessionSettingsPayloadUpdatesExistingThreadRoute() {
        val payload =
            sessionSettingsPayload(
                sessionId = "thread-1",
                accessLevel = "full",
                model = "gpt-test",
                effort = "high",
            )

        assertEquals("thread-1", payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals("full", payload.getValue("accessLevel").jsonPrimitive.content)
        assertEquals("gpt-test", payload.getValue("model").jsonPrimitive.content)
        assertEquals("high", payload.getValue("reasoningEffort").jsonPrimitive.content)
    }

    @Test
    fun unifiedOverviewAggregatesFiveHostsAndIsolatesSessionCollisions() {
        fun session(id: String, status: String, startedAt: Long) =
            SessionSummary(
                id = id,
                repository = "/work/$id",
                title = id,
                status = status,
                activeTurnStartedAt = startedAt,
                lastActivity = startedAt + 10,
                terminalAt = if (status in setOf("completed", "failed")) startedAt + 20 else null,
            )
        val home = projectHostOverview("home", listOf(session("same", "working", 100)), emptyList(), "connected")
        val work = projectHostOverview("work", listOf(session("same", "failed", 200)), emptyList(), "checked")
        val snapshots = mapOf("home" to home, "work" to work)
        val totals = aggregateHostOverviews(listOf("home", "work", "three", "four", "five"), snapshots)

        assertEquals(5, totals.hosts)
        assertEquals(1, totals.connectedHosts)
        assertEquals(4, totals.staleHosts)
        assertEquals(1, totals.active)
        assertEquals(1, totals.failed)
        assertEquals("home", totals.oldestTurn?.hostId)
        assertEquals("work", totals.latestCompletion?.hostId)
        assertFalse(
            globalSessionKey(GlobalSessionIdentity("home", "same")) ==
                globalSessionKey(GlobalSessionIdentity("work", "same")),
        )
        assertEquals("work", work.attention.single().hostId)
    }

    @Test
    fun unifiedAttentionKeepsExactHostSessionAndApprovalNavigationIdentity() {
        val approval =
            ApprovalRequest(
                id = "apr-1",
                sessionId = "same",
                type = "command",
                title = "Approve",
                createdAt = 300,
                status = "pending",
            )
        val snapshot = projectHostOverview(
            "work",
            listOf(SessionSummary("same", "/work/repo", "Collision", "waiting")),
            listOf(approval),
            "connected",
        )
        assertEquals("work", snapshot.attention.single().hostId)
        assertEquals("same", snapshot.attention.single().sessionId)
        assertEquals("apr-1", snapshot.attention.single().approvalId)
    }

    @Test
    fun unifiedAttentionDistinguishesStructuredInput() {
        val input =
            InputRequest(
                id = "inp-1",
                sessionId = "same",
                source = "mcp",
                title = "Input",
                supported = false,
                canDecline = true,
                canCancel = true,
                createdAt = 301,
                status = "pending",
            )
        val snapshot = projectHostOverview(
            "work",
            listOf(SessionSummary("same", "/work/repo", "Collision", "waiting")),
            emptyList(),
            "connected",
            inputs = listOf(input),
        )
        assertEquals("input", snapshot.attention.single().type)
        assertEquals("inp-1", snapshot.attention.single().approvalId)
        assertEquals("Waiting for unsupported user input", inputAttentionLabel(input))
    }

    @Test
    fun androidOverviewLifecycleStopsProbesInBackgroundAndCapsConnections() {
        val lifecycle = AndroidOverviewLifecycle()
        assertEquals(2, MAX_ANDROID_HOST_CONNECTIONS)
        assertEquals(60_000L, ANDROID_OVERVIEW_POLL_INTERVAL_MS)
        assertFalse(lifecycle.beginProbe())
        lifecycle.onForeground()
        assertTrue(lifecycle.beginProbe())
        assertFalse(lifecycle.beginProbe())
        lifecycle.endProbe()
        assertTrue(lifecycle.beginProbe())
        lifecycle.onBackground()
        assertFalse(lifecycle.foreground)
        assertFalse(lifecycle.probeActive)
        assertFalse(lifecycle.beginProbe())
    }

    @Test
    fun wireMessagesAlwaysIncludeProtocolVersion() {
        val encoded =
            Json.encodeToString(
                WireMessage(version = 1, id = "one", type = "hello"),
            )
        assertTrue(encoded.contains("\"version\":1"))
    }

    @Test
    fun restartUiReportsSuccessOnlyAfterReconnectAndHasTimeoutState() {
        assertEquals(
            RestartPhase.Scheduled,
            restartPhaseAfterConnection(RestartPhase.Scheduled, connected = true),
        )
        assertEquals(
            RestartPhase.Reconnecting,
            restartPhaseAfterConnection(RestartPhase.Scheduled, connected = false),
        )
        assertEquals(
            RestartPhase.Succeeded,
            restartPhaseAfterConnection(RestartPhase.Reconnecting, connected = true),
        )
        assertTrue(restartProgressLabel(RestartPhase.Scheduled).contains("waiting"))
        assertTrue(restartProgressLabel(RestartPhase.Reconnecting).contains("reconnecting"))
        assertTrue(restartProgressLabel(RestartPhase.Succeeded).contains("complete"))
        assertTrue(restartProgressLabel(RestartPhase.TimedOut).contains("timed out"))
    }

    @Test
    fun androidDiagnosticsCopyUsesOnlyTheSafeProjection() {
        val text =
            diagnosticsText(
                listOf(
                    DiagnosticEvent(
                        timestamp = "2026-07-30T12:00:00+00:00",
                        severity = "warning",
                        category = "request.failed",
                        message = "Request category failed",
                        requestCategory = "service",
                    ),
                ),
            )
        assertTrue(text.contains("request.failed"))
        assertTrue(text.contains("[service]"))
        assertFalse(text.contains("prompt"))
        assertFalse(text.contains("token"))
        assertFalse(text.contains("/home/"))
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
    fun composerDraftsPreserveIndependentTextForEachHostAndSession() {
        var drafts = updateComposerDraft(emptyMap(), "host-home", "session-one", "First draft")
        drafts = updateComposerDraft(drafts, "host-home", "session-two", "Second draft")
        drafts = updateComposerDraft(drafts, "host-work", "session-one", "Work draft")

        assertEquals("First draft", composerDraft(drafts, "host-home", "session-one"))
        assertEquals("Second draft", composerDraft(drafts, "host-home", "session-two"))
        assertEquals("Work draft", composerDraft(drafts, "host-work", "session-one"))
    }

    @Test
    fun clearingSubmittedComposerDraftLeavesOtherDraftsIntact() {
        var drafts = updateComposerDraft(emptyMap(), "host-home", "same-session", "Home draft")
        drafts = updateComposerDraft(drafts, "host-work", "same-session", "Work draft")
        drafts = updateComposerDraft(drafts, "host-home", "same-session", "")

        assertEquals("", composerDraft(drafts, "host-home", "same-session"))
        assertEquals("Work draft", composerDraft(drafts, "host-work", "same-session"))
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
    fun groupsMultiParagraphBlockquotesWithoutRenderingEmptySeparators() {
        val blocks =
            parseMarkdown(
                """
                > First quoted paragraph.
                >
                > Second quoted paragraph.
                > Continued on another quoted line.

                Outside the quote.

                >
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                MarkdownBlock.Quote(
                    "First quoted paragraph.\n\nSecond quoted paragraph.\nContinued on another quoted line.",
                ),
                MarkdownBlock.Paragraph("Outside the quote."),
            ),
            blocks,
        )
    }

    @Test
    fun parsesGfmTablesAndTaskLists() {
        val blocks =
            parseMarkdown(
                """
                | Component | Status |
                | --- | --- |
                | Web | Ready |
                | Android | Working |

                - [x] Finished
                - [ ] Device verification
                """.trimIndent(),
            )

        assertEquals(
            MarkdownBlock.Table(
                headers = listOf("Component", "Status"),
                rows = listOf(listOf("Web", "Ready"), listOf("Android", "Working")),
            ),
            blocks[0],
        )
        assertEquals(MarkdownBlock.TaskItem(true, "Finished"), blocks[1])
        assertEquals(MarkdownBlock.TaskItem(false, "Device verification"), blocks[2])
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
    fun opensAbsoluteWorkspaceMarkdownLinksAndRejectsUnsafeTargets() {
        val target = WorkspaceFileTarget("/home/user/My Project/readme.md", 28)
        assertEquals(target, workspaceFileTarget("/home/user/My%20Project/readme.md:28:4"))
        assertEquals(WorkspaceFileTarget("/home/user/readme.md"), workspaceFileTarget("/home/user/readme.md"))
        assertNull(workspaceFileTarget("docs/readme.md"))
        assertNull(workspaceFileTarget("/home/user/readme.md:0"))
        assertNull(workspaceFileTarget("/home/user/readme.md?raw=1"))
        assertNull(workspaceFileTarget("/home/user/readme.md#section"))
        assertNull(workspaceFileTarget("https://example.com/readme.md"))

        var opened: WorkspaceFileTarget? = null
        val rendered =
            styledInlineMarkdown(
                "Open [the document](/home/user/My%20Project/readme.md:28:4).",
                color = Color.White,
                linkColor = Color.Blue,
                codeColor = Color.Gray,
                onOpenWorkspaceFile = { opened = it },
            )
        val link = rendered.getLinkAnnotations(0, rendered.length).single().item
        assertTrue(link is LinkAnnotation.Clickable)
        (link as LinkAnnotation.Clickable).linkInteractionListener?.onClick(link)
        assertEquals(target, opened)
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
    fun foregroundSynchronizationPreservesLiveActivityMissingFromCanonicalHistory() {
        val live =
            SessionSummary(
                id = "thread-1",
                repository = "/projects/example",
                title = "Example",
                status = "working",
                messages =
                    listOf(
                        ConversationItem("user", "user", text = "Run checks"),
                        ConversationItem("read", "tool", status = "completed"),
                        ConversationItem("failed", "command", status = "failed", exitCode = 1),
                        ConversationItem("assistant", "assistant", text = "Working"),
                    ),
            )
        val canonical =
            live.copy(
                status = "completed",
                messages =
                    listOf(
                        ConversationItem("user", "user", text = "Run checks"),
                        ConversationItem("assistant", "assistant", text = "Done"),
                    ),
            )

        val synchronized =
            UiState(screen = Screen.Detail, selected = live).withSynchronizedSessions(
                sessions = listOf(canonical.copy(messages = emptyList())),
                repositories = emptyList(),
                selectedSessionId = canonical.id,
                selectedSession = canonical,
            )

        assertEquals(listOf("user", "read", "failed", "assistant"), synchronized.selected?.messages?.map { it.id })
        assertEquals("Done", synchronized.selected?.messages?.last()?.text)
        val blocks = conversationBlocks(requireNotNull(synchronized.selected).messages, ActivityDetail.Focused)
        assertTrue(blocks.any { it.collapsedActivity && it.items.single().id == "read" })
        assertTrue(blocks.any { !it.collapsedActivity && it.items.single().id == "failed" })
    }

    @Test
    fun foregroundSynchronizationKeepsRecoveredCanonicalMessagesBeforeNewerLiveActivity() {
        val live =
            SessionSummary(
                id = "thread-1",
                repository = "/projects/example",
                title = "Example",
                status = "working",
                messages = listOf(ConversationItem("new-command", "command", status = "completed", turnId = "new-turn")),
            )
        val canonical =
            live.copy(
                status = "completed",
                messages = listOf(ConversationItem("older-user", "user", text = "Earlier prompt", turnId = "old-turn")),
            )

        val reconciled = reconcileSelectedSession(live, canonical)

        assertEquals(listOf("older-user", "new-command"), reconciled?.messages?.map { it.id })
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
    fun monitorLifecycleDiscoversExternallyStartedActiveTurns() {
        val lifecycle = MonitorLifecycle()

        assertFalse(lifecycle.monitorActive("session-1", "completed"))
        assertFalse(lifecycle.contains("session-1"))
        assertTrue(lifecycle.monitorActive("session-1", "working"))
        assertFalse(lifecycle.monitorActive("session-1", "working"))
        assertEquals(
            "Foreman turn completed",
            lifecycle.status("session-1", "completed")?.title,
        )
        assertTrue(lifecycle.isEmpty())
    }

    @Test
    fun globalMonitoringReusesTheForegroundNotificationForOutcomes() {
        val global = outcomeNotificationId("host", providerSessionKey(PROVIDER_CODEX, "session"), true)
        val singleTurn = outcomeNotificationId("host", providerSessionKey(PROVIDER_CODEX, "session"), false)

        assertEquals(FOREGROUND_NOTIFICATION_ID, global)
        assertTrue(singleTurn != FOREGROUND_NOTIFICATION_ID)
    }

    @Test
    fun globalMonitoringFiltersProvidersAndDiscoversOnlyMonitorableTurns() {
        val providers = buildJsonArray {
            add(buildJsonObject { put("id", PROVIDER_CODEX); put("enabled", true); put("available", true) })
            add(buildJsonObject { put("id", PROVIDER_CLAUDE_CODE); put("enabled", false); put("available", true) })
            add(buildJsonObject { put("id", "other"); put("enabled", true); put("available", true) })
        }
        assertEquals(setOf(PROVIDER_CODEX), enabledMonitorProviders(providers))

        val codexSessions = buildJsonArray {
            add(buildJsonObject { put("id", "working"); put("status", "working"); put("repository", "/repo") })
            add(buildJsonObject { put("id", "waiting"); put("status", "waiting") })
            add(buildJsonObject { put("id", "old"); put("status", "completed") })
        }
        val candidates = globalTurnCandidates(PROVIDER_CODEX, codexSessions)
        assertEquals(listOf("working", "waiting"), candidates.map { it.sessionId })
        assertEquals(NotificationEvent.Approval, monitorOutcome(candidates.last().status)?.event)

        val claudeSessions = buildJsonArray {
            add(buildJsonObject { put("id", "managed"); put("status", "working"); put("source", "managed") })
            add(buildJsonObject { put("id", "external"); put("status", "working"); put("source", "external") })
        }
        assertEquals(
            listOf("managed"),
            globalTurnCandidates(PROVIDER_CLAUDE_CODE, claudeSessions).map { it.sessionId },
        )
    }

    @Test
    fun globalStatusEnrollmentRequiresWatchingAnEnabledProviderAndActiveStatus() {
        val enabled = setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE)

        assertTrue(shouldEnrollGlobalTurn(true, enabled, PROVIDER_CODEX, "working"))
        assertTrue(shouldEnrollGlobalTurn(true, enabled, PROVIDER_CLAUDE_CODE, "waiting"))
        assertFalse(shouldEnrollGlobalTurn(false, enabled, PROVIDER_CODEX, "working"))
        assertFalse(shouldEnrollGlobalTurn(true, setOf(PROVIDER_CODEX), PROVIDER_CLAUDE_CODE, "working"))
        assertFalse(shouldEnrollGlobalTurn(true, enabled, PROVIDER_CODEX, "completed"))
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
