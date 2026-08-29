package net.kaltner.foreman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionNotificationsTest {
    private val first =
        explicitAttentionRequest(
            "approval",
            "approval-1",
            providerSessionKey(PROVIDER_CODEX, "session-1"),
        )
    private val second =
        explicitAttentionRequest(
            "input",
            "input-2",
            providerSessionKey(PROVIDER_CODEX, "session-2"),
        )

    @Test
    fun monitoringWithoutAttentionHasOneStableQuietOwner() {
        val presentation = monitoringNotificationPresentation(activeTurnCount = 2, reconnecting = false)

        assertEquals(1001, FOREGROUND_NOTIFICATION_ID)
        assertEquals("Foreman background monitoring", presentation.title)
        assertEquals("Monitoring 2 active turns", presentation.detail)
        assertEquals(ForegroundNotificationDestination.App, presentation.destination)
        assertFalse(presentation.useAttentionChannel)
        assertEquals(0, presentation.badgeCount)
    }

    @Test
    fun approvalAndInputReplaceTheForegroundPresentationInsteadOfAddingEntries() {
        val ledger = AttentionNotificationLedger()
        ledger.record(first)
        ledger.record(second)

        val presentation = requireNotNull(
            attentionNotificationPresentation(ledger.pendingRequests(), ledger.acknowledgedKeys()),
        )
        assertEquals(1001, FOREGROUND_NOTIFICATION_ID)
        assertEquals("Foreman needs your attention", presentation.title)
        assertEquals(2, presentation.attentionCount)
        assertEquals(2, presentation.badgeCount)
        assertTrue(presentation.useAttentionChannel)
    }

    @Test
    fun duplicateEventsReconnectAndProcessRestoreClaimOneAudibleAlertPerRequest() {
        val ledger = AttentionNotificationLedger()
        ledger.record(first)
        assertEquals(setOf(first.key), ledger.claimAlerts(listOf(first)))
        ledger.record(first)
        assertTrue(ledger.claimAlerts(listOf(first)).isEmpty())

        val restored = AttentionNotificationLedger(
            decodeAttentionLedgerSnapshot(encodeAttentionLedgerSnapshot(ledger.snapshot())),
        )
        restored.record(first)
        assertTrue(restored.claimAlerts(listOf(first)).isEmpty())
        assertEquals(listOf(first), restored.pendingRequests())
    }

    @Test
    fun explicitCardReplacesAStatusPlaceholderWithoutRealertingOrLosingAcknowledgment() {
        val ledger = AttentionNotificationLedger()
        val placeholder = statusAttentionRequest(first.sessionId)
        ledger.replaceStatusRequest(placeholder)
        ledger.claimAlerts(listOf(placeholder))
        ledger.acknowledgePending()

        ledger.replaceStatusRequest(first)

        assertEquals(listOf(first), ledger.pendingRequests())
        assertTrue(ledger.claimAlerts(listOf(first)).isEmpty())
        assertEquals(setOf(first.key), ledger.acknowledgedKeys())
    }

    @Test
    fun oneRequestRoutesToItsExactProviderSessionAndCard() {
        val presentation = requireNotNull(attentionNotificationPresentation(listOf(first), emptySet()))

        assertEquals(ForegroundNotificationDestination.Session, presentation.destination)
        assertEquals(first.sessionId, presentation.request?.sessionId)
        assertEquals("approval-1", presentation.request?.requestId)
        assertEquals(PROVIDER_CODEX, parseProviderSessionKey(first.sessionId)?.first)
    }

    @Test
    fun multipleRequestsStayCountedAndRouteToTheAttentionDashboard() {
        val presentation = requireNotNull(attentionNotificationPresentation(listOf(first, second), emptySet()))

        assertEquals(ForegroundNotificationDestination.AttentionDashboard, presentation.destination)
        assertNull(presentation.request)
        assertEquals("2 requests need approval or input.", presentation.detail)
        assertEquals(2, presentation.badgeCount)
    }

    @Test
    fun attentionCountIsAccurateAndBoundedForLauncherSurfaces() {
        val requests = (1..105).map { index ->
            explicitAttentionRequest("approval", "approval-$index", "codex:session-$index")
        }
        val presentation = requireNotNull(attentionNotificationPresentation(requests, emptySet()))

        assertEquals(105, presentation.attentionCount)
        assertEquals(MAX_ATTENTION_NOTIFICATION_COUNT, presentation.badgeCount)
        assertEquals("99+ requests need approval or input.", presentation.detail)
    }

    @Test
    fun resolvingRequestsUpdatesThenRestoresMonitoringContent() {
        val ledger = AttentionNotificationLedger()
        ledger.record(first)
        ledger.record(second)

        ledger.clear(first.key)
        val remaining = requireNotNull(
            attentionNotificationPresentation(ledger.pendingRequests(), ledger.acknowledgedKeys()),
        )
        assertEquals(1, remaining.attentionCount)
        assertEquals(second, remaining.request)

        ledger.clear(second.key)
        assertNull(attentionNotificationPresentation(ledger.pendingRequests(), ledger.acknowledgedKeys()))
        assertEquals(
            "Monitoring 2 active turns",
            monitoringNotificationPresentation(2, reconnecting = false).detail,
        )
    }

    @Test
    fun foregroundAcknowledgmentClearsBadgeWithoutResolvingServerRequests() {
        val ledger = AttentionNotificationLedger()
        ledger.record(first)
        ledger.acknowledgePending()

        val presentation = requireNotNull(
            attentionNotificationPresentation(ledger.pendingRequests(), ledger.acknowledgedKeys()),
        )
        assertEquals(listOf(first), ledger.pendingRequests())
        assertEquals(0, presentation.badgeCount)
        assertFalse(presentation.useAttentionChannel)
        assertEquals(ForegroundNotificationDestination.Session, presentation.destination)
    }

    @Test
    fun serviceRecreationRestoresAcknowledgmentAndDoesNotRealert() {
        val original = AttentionNotificationLedger()
        original.record(first)
        original.claimAlerts(listOf(first))
        original.acknowledgePending()

        val restored = AttentionNotificationLedger(original.snapshot())
        assertTrue(restored.claimAlerts(restored.pendingRequests()).isEmpty())
        val presentation = requireNotNull(
            attentionNotificationPresentation(restored.pendingRequests(), restored.acknowledgedKeys()),
        )
        assertEquals(0, presentation.badgeCount)
        assertFalse(presentation.useAttentionChannel)
    }

    @Test
    fun durableStateIsBoundedAndIsolatedByStableHostId() {
        val work = AttentionNotificationLedger()
        (1..550).forEach { index ->
            val request = explicitAttentionRequest("approval", "work-$index", "codex:session-$index")
            work.record(request)
            work.claimAlerts(listOf(request))
        }
        val personal = AttentionNotificationLedger().apply { record(first) }
        val stored = mapOf(
            attentionStatePreferenceKey("work") to encodeAttentionLedgerSnapshot(work.snapshot()),
            attentionStatePreferenceKey("personal") to encodeAttentionLedgerSnapshot(personal.snapshot()),
        )

        assertTrue(attentionStatePreferenceKey("work") != attentionStatePreferenceKey("personal"))
        val restoredWork = AttentionNotificationLedger(
            decodeAttentionLedgerSnapshot(stored.getValue(attentionStatePreferenceKey("work"))),
        )
        val restoredPersonal = AttentionNotificationLedger(
            decodeAttentionLedgerSnapshot(stored.getValue(attentionStatePreferenceKey("personal"))),
        )
        assertEquals(500, restoredWork.pendingRequests().size)
        assertEquals(listOf(first), restoredPersonal.pendingRequests())
    }

    @Test
    fun detachingAndReturningToAHostPreservesPendingAcknowledgmentState() {
        val attached = AttentionNotificationLedger()
        attached.record(first)
        attached.claimAlerts(listOf(first))
        attached.acknowledgePending()
        val durableHostSnapshot = attached.snapshot()

        val otherHostOwner = AttentionNotificationLedger()
        assertTrue(otherHostOwner.pendingRequests().isEmpty())

        val restoredHostOwner = AttentionNotificationLedger(durableHostSnapshot)
        val presentation = requireNotNull(
            attentionNotificationPresentation(
                restoredHostOwner.pendingRequests(),
                restoredHostOwner.acknowledgedKeys(),
            ),
        )
        assertEquals(listOf(first), restoredHostOwner.pendingRequests())
        assertEquals(0, presentation.badgeCount)
        assertFalse(presentation.useAttentionChannel)
        assertTrue(restoredHostOwner.claimAlerts(listOf(first)).isEmpty())
    }

    @Test
    fun unacknowledgedProcessRestorePrimesTheStableEntryQuietlyBeforeRestoringItsBadge() {
        val original = AttentionNotificationLedger()
        original.record(first)
        original.claimAlerts(listOf(first))
        val restored = AttentionNotificationLedger(original.snapshot())
        val requests = restored.pendingRequests()
        val presentation = requireNotNull(
            attentionNotificationPresentation(requests, restored.acknowledgedKeys()),
        )
        val shouldAlert = restored.claimAlerts(requests).isNotEmpty()

        assertFalse(shouldAlert)
        assertTrue(requiresQuietForegroundPriming(false, presentation, shouldAlert))
        assertFalse(requiresQuietForegroundPriming(true, presentation, shouldAlert))
    }

    @Test
    fun exactFocusedSessionSuppressionDoesNotSuppressOtherOrBackgroundedSessions() {
        val active = setOf(first.sessionId, second.sessionId)
        assertEquals(
            listOf(second),
            eligibleAttentionRequests(listOf(first, second), active, setOf(first.sessionId)),
        )
        assertEquals(
            listOf(first, second),
            eligibleAttentionRequests(listOf(first, second), active, emptySet()),
        )
    }

    @Test
    fun standaloneFallbackKeepsAnEligibleRequestWhenForegroundOwnershipEnds() {
        assertTrue(
            eligibleAttentionRequests(
                requests = listOf(first),
                activeSessions = emptySet(),
                focusedSessions = emptySet(),
                requireActiveMonitoring = true,
            ).isEmpty(),
        )
        assertEquals(
            listOf(first),
            eligibleAttentionRequests(
                requests = listOf(first),
                activeSessions = emptySet(),
                focusedSessions = emptySet(),
                requireActiveMonitoring = false,
            ),
        )
    }

    @Test
    fun disabledPreferenceOrQuietHoursLeavesAttentionOutOfTheForegroundPresentation() {
        val active = setOf(first.sessionId)
        assertTrue(
            eligibleAttentionRequests(
                listOf(first),
                active,
                emptySet(),
                shouldNotify = { false },
            ).isEmpty(),
        )
        assertFalse(NotificationPreferences(notifyApprovals = false).eventEnabled(NotificationEvent.Approval, ""))
    }

    @Test
    fun permissionDenialStopsMonitoringWhileCompletionAndFailurePreferencesRemainIndependent() {
        assertTrue(shouldStopMonitoringForNotificationPermission(true, permissionGranted = false))
        assertFalse(shouldStopMonitoringForNotificationPermission(false, permissionGranted = false))
        assertFalse(shouldStopMonitoringForNotificationPermission(true, permissionGranted = true))

        val defaults = NotificationPreferences()
        assertTrue(defaults.shouldNotify(NotificationEvent.Completion, ""))
        assertTrue(defaults.shouldNotify(NotificationEvent.Failure, ""))
        assertEquals(NotificationEvent.Completion, monitorOutcome("completed")?.event)
        assertEquals(NotificationEvent.Failure, monitorOutcome("failed")?.event)
    }

    @Test
    fun privacySafeAttentionTextNeverIncludesRequestPayloadOrPaths() {
        val presentation = requireNotNull(attentionNotificationPresentation(listOf(first), emptySet()))

        assertEquals("A monitored session needs approval or input.", presentation.detail)
        assertFalse(presentation.detail.contains("approval-1"))
        assertFalse(presentation.detail.contains("/private"))
    }
}
