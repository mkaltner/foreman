package net.kaltner.foreman

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSupportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun providerCatalogDecodesAvailabilityVersionsAndSafeReason() {
        val provider = json.decodeFromString<ProviderInfo>(
            """{"id":"claude-code","displayName":"Claude Code","available":false,"cliVersion":"2.1.220","sdkVersion":"0.3.220","nodeVersion":"20.19.0","capabilities":[],"limitations":["no-images"],"unavailableReason":"node-missing"}""",
        )

        assertTrue(provider.enabled)
        assertFalse(provider.available)
        assertEquals("2.1.220", provider.cliVersion)
        assertEquals("Node.js 20 or newer is not installed.", providerUnavailableDescription(provider.unavailableReason))
    }

    @Test
    fun providerCatalogDecodesHostEnablement() {
        val provider = json.decodeFromString<ProviderInfo>(
            """{"id":"codex","displayName":"Codex","installed":false,"enabled":false,"available":false}""",
        )

        assertFalse(provider.enabled)
        assertFalse(provider.installed ?: true)
    }

    @Test
    fun taskUsabilityRequiresEnabledAndAvailableAcrossTheProviderMatrix() {
        val codex = ProviderInfo(PROVIDER_CODEX, "Codex", available = true)
        val unavailableClaude = ProviderInfo(PROVIDER_CLAUDE_CODE, "Claude Code", available = false)

        assertTrue(providerUsableForTasks(codex))
        assertFalse(providerUsableForTasks(unavailableClaude))
        assertFalse(providerUsableForTasks(codex.copy(enabled = false)))
        assertEquals(null, soleUsableTaskProvider(listOf(codex), catalogLoaded = false))
        assertEquals(null, soleUsableTaskProvider(emptyList(), catalogLoaded = true))
        assertEquals(PROVIDER_CODEX, soleUsableTaskProvider(listOf(codex), catalogLoaded = true)?.id)
        assertFalse(shouldShowProviderIdentity(listOf(codex), catalogLoaded = true))
        assertFalse(shouldShowProviderIdentity(listOf(codex, unavailableClaude), catalogLoaded = true))
        assertEquals(null, soleUsableTaskProvider(listOf(codex, unavailableClaude.copy(available = true)), true))
        assertTrue(shouldShowProviderIdentity(listOf(codex, unavailableClaude.copy(available = true)), true))
        assertEquals(null, soleUsableTaskProvider(listOf(codex.copy(available = false), unavailableClaude), true))
    }

    @Test
    fun providerSettingsProtectTheOnlyEnabledAvailableProvider() {
        val codex = ProviderInfo(PROVIDER_CODEX, "Codex", available = true)
        val unavailableClaude = ProviderInfo(PROVIDER_CLAUDE_CODE, "Claude Code", available = false)

        assertTrue(providerMustRemainEnabled(codex, listOf(codex, unavailableClaude)))
        assertFalse(providerMustRemainEnabled(unavailableClaude, listOf(codex, unavailableClaude)))
        assertTrue(
            providerMustRemainEnabled(
                unavailableClaude,
                listOf(codex.copy(enabled = false), unavailableClaude),
            ),
        )
    }

    @Test
    fun newSessionSelectionFollowsUsableProviderTransitions() {
        val codex = ProviderInfo(PROVIDER_CODEX, "Codex", available = true)
        val unavailableClaude = ProviderInfo(PROVIDER_CLAUDE_CODE, "Claude Code", available = false)

        assertTrue(shouldShowProviderIdentity(listOf(codex), catalogLoaded = false))
        assertEquals(null, newSessionProviderSelection(listOf(codex), catalogLoaded = false, PROVIDER_CLAUDE_CODE))
        assertFalse(shouldShowProviderIdentity(listOf(codex), catalogLoaded = true))
        assertEquals(PROVIDER_CODEX, newSessionProviderSelection(listOf(codex), true, PROVIDER_CLAUDE_CODE))
        assertFalse(shouldShowProviderIdentity(listOf(codex, unavailableClaude), catalogLoaded = true))
        assertEquals(PROVIDER_CODEX, newSessionProviderSelection(listOf(codex, unavailableClaude), true, PROVIDER_CLAUDE_CODE))
        assertEquals(PROVIDER_CLAUDE_CODE, newSessionProviderSelection(listOf(codex.copy(available = false), unavailableClaude.copy(available = true)), true, PROVIDER_CODEX))
        assertEquals(null, newSessionProviderSelection(listOf(codex.copy(available = false), unavailableClaude), true, PROVIDER_CODEX))
        assertFalse(shouldShowProviderIdentity(listOf(codex, unavailableClaude.copy(enabled = false)), catalogLoaded = true))
    }

    @Test
    fun settingsDescribeConfigurationInstallationAndAvailabilitySeparately() {
        val unavailable = ProviderInfo(
            PROVIDER_CLAUDE_CODE,
            "Claude Code",
            installed = false,
            enabled = true,
            available = false,
        )

        assertEquals(
            "Disabled · Not installed · Unavailable",
            providerConfigurationStatus(unavailable.copy(enabled = false)),
        )
        assertEquals(
            "Disabled · Installed · Unavailable",
            providerConfigurationStatus(unavailable.copy(installed = true, enabled = false)),
        )
    }

    @Test
    fun relaunchHostSwitchAndStaleResponsesCannotReuseAnotherCatalog() {
        val relaunched = UiState(activeHostId = "host-a")
        assertFalse(relaunched.providerCatalogLoaded)
        assertTrue(relaunched.providers.isEmpty())
        assertTrue(providerCatalogResponseIsCurrent("host-a", "host-a", 4, 4))
        assertFalse(providerCatalogResponseIsCurrent("host-a", "host-b", 4, 4))
        assertFalse(providerCatalogResponseIsCurrent("host-a", "host-a", 3, 4))
    }

    @Test
    fun unknownProvidersAreNotSilentlyTreatedAsCodexOrClaude() {
        val session = SessionSummary("thread", "/repo", "Future", "idle", provider = "future-provider")

        assertFalse(supportedProvider("future-provider"))
        assertEquals("future-provider", sessionProvider(session))
        assertEquals("Provider", providerDisplayName("future-provider"))
    }

    @Test
    fun contextUsageCalculatesRemainingWindow() {
        val usage = contextUsageView(
            ThreadTokenUsage(
                last = TokenUsageBreakdown(totalTokens = 57_900),
                modelContextWindow = 258_000,
            ),
        )

        assertEquals(57_900L, usage?.usedTokens)
        assertEquals(200_100L, usage?.remainingTokens)
        assertEquals(22, usage?.percentUsed)
        assertEquals(78, usage?.percentRemaining)
        assertEquals("57.9k", formatTokenCount(usage!!.usedTokens))
        assertEquals("258k", formatTokenCount(usage.contextWindow))
    }

    @Test
    fun contextUsageRejectsMissingOrInvalidWindows() {
        assertEquals(null, contextUsageView(null))
        assertEquals(
            null,
            contextUsageView(
                ThreadTokenUsage(
                    last = TokenUsageBreakdown(totalTokens = 100),
                    modelContextWindow = 0,
                ),
            ),
        )
    }

    @Test
    fun accountUsageUsesMostConstrainedWindow() {
        val usage = ProviderAccountUsage(
            available = true,
            rateLimits = RateLimitSnapshot(
                primary = RateLimitWindow(usedPercent = 12.4, windowDurationMins = 300),
                secondary = RateLimitWindow(usedPercent = 34.6, windowDurationMins = 10_080),
            ),
        )

        assertEquals("65% left", accountUsageRemaining(usage))
        assertEquals(2, accountUsageWindows(usage).size)
        assertEquals(300L, accountUsageWindows(usage).first().windowDurationMins)
    }

    @Test
    fun sessionUsageAndCompactionDecodeFromProtocol() {
        val session = json.decodeFromString<SessionSummary>(
            """{"id":"thread","repository":"/repo","title":"Usage","status":"idle","messages":[{"id":"compact-1","kind":"compaction","description":"Context compacted","compactionTrigger":"auto","preTokens":900000,"postTokens":120000,"durationMs":2000}],"tokenUsage":{"total":{"totalTokens":950000},"last":{"totalTokens":120000,"cachedInputTokens":54000,"outputTokens":1100},"modelContextWindow":258000}}""",
        )

        assertEquals(258_000L, session.tokenUsage?.modelContextWindow)
        assertEquals(54_000L, session.tokenUsage?.last?.cachedInputTokens)
        assertEquals("compaction", session.messages.single().kind)
        assertEquals("auto", session.messages.single().compactionTrigger)
        assertEquals(900_000L, session.messages.single().preTokens)
    }

    @Test
    fun accountUsageDecodesBothProviders() {
        val usage = json.decodeFromString<AccountUsage>(
            """{"providers":{"codex":{"available":true,"rateLimits":{"primary":{"usedPercent":14,"windowDurationMins":300}}},"claude-code":{"available":false,"experimental":true,"availabilityReason":"Unavailable"}}}""",
        )

        assertEquals(14.0, usage.providers[PROVIDER_CODEX]?.rateLimits?.primary?.usedPercent)
        assertTrue(usage.providers[PROVIDER_CLAUDE_CODE]?.experimental == true)
        assertEquals("Unavailable", usage.providers[PROVIDER_CLAUDE_CODE]?.availabilityReason)
    }

    @Test
    fun accountUsageIncludesOnlyTaskUsableProvidersAcrossTheProviderMatrix() {
        val codex = ProviderInfo(PROVIDER_CODEX, "Codex", enabled = true, available = true)
        val claude = ProviderInfo(PROVIDER_CLAUDE_CODE, "Claude Code", enabled = true, available = true)
        val usage = AccountUsage(
            providers = mapOf(
                PROVIDER_CODEX to ProviderAccountUsage(available = true),
                PROVIDER_CLAUDE_CODE to ProviderAccountUsage(available = true),
            ),
        )

        assertEquals(listOf(PROVIDER_CODEX), taskProviderAccountUsage(listOf(codex, claude.copy(available = false)), usage).map { it.first.id })
        assertEquals(listOf(PROVIDER_CLAUDE_CODE), taskProviderAccountUsage(listOf(codex.copy(enabled = false), claude), usage).map { it.first.id })
        assertEquals(listOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE), taskProviderAccountUsage(listOf(codex, claude), usage).map { it.first.id })
        assertTrue(taskProviderAccountUsage(listOf(codex.copy(available = false), claude.copy(enabled = false)), usage).isEmpty())
    }

    @Test
    fun legacySessionWithoutProviderDecodesAsCodexIdentity() {
        val session = json.decodeFromString<SessionSummary>(
            """{"id":"same","repository":"/repo","title":"Legacy","status":"idle"}""",
        )

        assertEquals(PROVIDER_CODEX, sessionProvider(session))
        assertEquals(providerSessionKey(PROVIDER_CODEX, "same"), session.providerKey())
    }

    @Test
    fun providerIdentityIsolatedAcrossHostsAndProviders() {
        val codex = sessionIdentityKey(SessionIdentity("host-a", PROVIDER_CODEX, "same"))
        val claude = sessionIdentityKey(SessionIdentity("host-a", PROVIDER_CLAUDE_CODE, "same"))
        val otherHost = sessionIdentityKey(SessionIdentity("host-b", PROVIDER_CLAUDE_CODE, "same"))

        assertTrue(setOf(codex, claude, otherHost).size == 3)
        assertEquals(PROVIDER_CLAUDE_CODE to "same", parseProviderSessionKey(providerSessionKey(PROVIDER_CLAUDE_CODE, "same")))
    }

    @Test
    fun legacyLocalKeysMigrateToCodexScope() {
        assertEquals(providerSessionKey(PROVIDER_CODEX, "thread-1"), legacySessionKey("thread-1"))
        assertEquals(
            providerSessionKey(PROVIDER_CLAUDE_CODE, "thread-1"),
            legacySessionKey(providerSessionKey(PROVIDER_CLAUDE_CODE, "thread-1")),
        )
    }

    @Test
    fun draftsAreProviderAndHostScoped() {
        var drafts = emptyMap<ComposerDraftKey, String>()
        drafts = updateComposerDraft(drafts, "host-a", "same", "Codex draft")
        drafts = updateComposerDraft(drafts, "host-a", "same", "Claude draft", PROVIDER_CLAUDE_CODE)
        drafts = updateComposerDraft(drafts, "host-b", "same", "Other host", PROVIDER_CLAUDE_CODE)

        assertEquals("Codex draft", composerDraft(drafts, "host-a", "same"))
        assertEquals("Claude draft", composerDraft(drafts, "host-a", "same", PROVIDER_CLAUDE_CODE))
        assertEquals("Other host", composerDraft(drafts, "host-b", "same", PROVIDER_CLAUDE_CODE))
    }

    @Test
    fun externalClaudeIsResumableButNeverInterruptible() {
        val external = claudeSession(source = "external", status = "resumable", turnId = null)
        val claimedActive = external.copy(status = "working", activeTurnId = "external-run")

        assertEquals("provider.session.resume", providerPromptOperation(external))
        assertFalse(claudeInterruptEligible(external))
        assertFalse(claudeInterruptEligible(claimedActive))
        assertEquals("external active", sessionDisplayStatus(claimedActive))
    }

    @Test
    fun managedWorkingClaudeCanPromptAndInterrupt() {
        val managed = claudeSession(source = "managed", status = "working", turnId = "run-1")

        assertEquals("provider.turn.prompt", providerPromptOperation(managed))
        assertTrue(claudeInterruptEligible(managed))
        assertFalse(claudeInterruptEligible(managed.copy(status = "interrupted", activeTurnId = null)))
    }

    @Test
    fun claudePromptUsesExactModelPermissionAndRepository() {
        val session = claudeSession(source = "managed", status = "idle", turnId = null)
            .copy(repositoryId = "foreman")
        val payload = claudePromptPayload(session, "  continue  ", "haiku", "dontAsk")

        assertEquals("claude-code", payload.getValue("provider").jsonPrimitive.content)
        assertEquals("foreman", payload.getValue("repositoryId").jsonPrimitive.content)
        assertEquals("continue", payload.getValue("text").jsonPrimitive.content)
        assertEquals("haiku", payload.getValue("model").jsonPrimitive.content)
        assertEquals("dontAsk", payload.getValue("permissionMode").jsonPrimitive.content)
    }

    @Test
    fun providerNotificationIdsAndDeepLinkKeysDoNotCollide() {
        val codex = providerNotificationId("host", PROVIDER_CODEX, "same")
        val claude = providerNotificationId("host", PROVIDER_CLAUDE_CODE, "same")
        val otherHost = providerNotificationId("other", PROVIDER_CLAUDE_CODE, "same")

        assertTrue(setOf(codex, claude, otherHost).size == 3)
    }

    @Test
    fun externalSessionsDoNotEnterManagedDashboardActiveWork() {
        val external = claudeSession(source = "external", status = "working", turnId = "external")
        val managed = claudeSession(source = "managed", status = "working", turnId = "managed")
            .copy(id = "managed")
        val dashboard = projectAndroidDashboard(listOf(external, managed))

        assertEquals(listOf("managed"), dashboard.active.map { it.id })
        assertTrue(dashboard.attention.isEmpty())
    }

    @Test
    fun bridgeReconciliationStopsMonitoringResumableSessionWithoutNotification() {
        val lifecycle = MonitorLifecycle()
        val key = providerSessionKey(PROVIDER_CLAUDE_CODE, "managed")
        lifecycle.monitor(key, active = true)

        assertEquals(null, lifecycle.status(key, "resumable"))
        assertFalse(lifecycle.contains(key))
        assertTrue(lifecycle.isEmpty())
    }

    @Test
    fun legacyOverviewIdentityRestoresCodexProvider() {
        val restored = json.decodeFromString<GlobalSessionIdentity>(
            """{"hostId":"host","sessionId":"thread"}""",
        )

        assertEquals(PROVIDER_CODEX, restored.provider)
        assertEquals(
            sessionIdentityKey(SessionIdentity("host", PROVIDER_CODEX, "thread")),
            globalSessionKey(restored),
        )
    }

    private fun claudeSession(source: String, status: String, turnId: String?) =
        SessionSummary(
            id = "same",
            repository = "/repo",
            title = "Claude",
            status = status,
            provider = PROVIDER_CLAUDE_CODE,
            repositoryId = ".",
            source = source,
            activeTurnId = turnId,
            model = "sonnet",
            permissionMode = "default",
        )
}
