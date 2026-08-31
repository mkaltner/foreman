package net.kaltner.foreman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLifecycleTest {
    private val filters =
        SessionSearchFilters(
            query = "foreman",
            scope = SessionDiscoveryScope.Normal,
            provider = PROVIDER_CODEX,
            repository = "/projects/foreman",
            status = SessionSearchStatus.Completed,
            dateRange = SessionDateRange.Custom,
            dateFrom = "2026-08-01",
            dateTo = "2026-08-31",
            pinnedOnly = true,
        )
    private val archived =
        SessionSummary(
            id = "archive-me",
            repository = "/projects/foreman",
            title = "Archive me",
            status = "completed",
            provider = PROVIDER_CODEX,
        )
    private val retained =
        SessionSummary(
            id = "keep-me",
            repository = "/projects/foreman",
            title = "Keep me",
            status = "idle",
            provider = PROVIDER_CODEX,
        )

    @Test
    fun selectedArchiveKeepsNormalFiltersClosesDetailAndSettlesRequestState() {
        val before =
            UiState(
                screen = Screen.Detail,
                sessions = listOf(archived, retained),
                selected = archived,
                loading = true,
                submitting = true,
                pendingSessionAction = PendingSessionAction(
                    archived.id,
                    archived.title,
                    SessionAction.Archive,
                ),
                searchFilters = filters,
                searchResults = listOf(SessionSearchResult(archived), SessionSearchResult(retained)),
                highlightedItemId = "matched-item",
                focusedApprovalId = "approval",
            )

        val afterEvent = before.afterSessionArchived(PROVIDER_CODEX, archived.id)

        assertEquals(filters, afterEvent.searchFilters)
        assertEquals(SessionDiscoveryScope.Normal, afterEvent.searchFilters.scope)
        assertEquals(Screen.Sessions, afterEvent.screen)
        assertNull(afterEvent.selected)
        assertFalse(afterEvent.loading)
        assertFalse(afterEvent.submitting)
        assertNull(afterEvent.pendingSessionAction)
        assertNull(afterEvent.highlightedItemId)
        assertNull(afterEvent.focusedApprovalId)
        assertEquals(listOf(retained), afterEvent.sessions)
        assertEquals(listOf(retained.id), afterEvent.searchResults.map { it.session.id })
    }

    @Test
    fun lifecycleBeforeArchiveResponseIsIdempotentAndDoesNotChangePersistedFilters() {
        val persistedFilters = filters
        val requestStarted =
            UiState(
                screen = Screen.Detail,
                sessions = listOf(archived),
                selected = archived,
                loading = true,
                submitting = true,
                pendingSessionAction = PendingSessionAction(
                    archived.id,
                    archived.title,
                    SessionAction.Archive,
                ),
                searchFilters = persistedFilters,
            )

        val afterLifecycleEvent = requestStarted.afterSessionArchived(PROVIDER_CODEX, archived.id)
        val afterRequestResponse = afterLifecycleEvent.afterSessionArchived(PROVIDER_CODEX, archived.id)
        val recreated = UiState(screen = Screen.Sessions, searchFilters = persistedFilters)

        assertEquals(afterLifecycleEvent, afterRequestResponse)
        assertEquals(persistedFilters, afterRequestResponse.searchFilters)
        assertEquals(SessionDiscoveryScope.Normal, recreated.searchFilters.scope)
        assertFalse(afterRequestResponse.loading)
        assertFalse(afterRequestResponse.submitting)
        assertTrue(afterRequestResponse.sessions.isEmpty())
    }

    @Test
    fun unselectedArchivePreservesCurrentSelectionAndFilters() {
        val before =
            UiState(
                screen = Screen.Detail,
                sessions = listOf(archived, retained),
                selected = retained,
                searchFilters = filters,
                searchResults = listOf(SessionSearchResult(archived), SessionSearchResult(retained)),
            )

        val after = before.afterSessionArchived(PROVIDER_CODEX, archived.id)

        assertEquals(retained, after.selected)
        assertEquals(Screen.Detail, after.screen)
        assertEquals(filters, after.searchFilters)
        assertEquals(listOf(retained), after.sessions)
        assertEquals(listOf(retained.id), after.searchResults.map { it.session.id })
    }

    @Test
    fun externalSelectedArchiveSettlesLoadingWithoutInventingAFilterChange() {
        val before =
            UiState(
                screen = Screen.Detail,
                sessions = listOf(archived),
                selected = archived,
                loading = true,
                submitting = false,
                pendingSessionAction = null,
                searchFilters = filters,
            )

        val after = before.afterSessionArchived(PROVIDER_CODEX, archived.id)

        assertEquals(Screen.Sessions, after.screen)
        assertNull(after.selected)
        assertFalse(after.loading)
        assertEquals(filters, after.searchFilters)
        assertEquals(SessionDiscoveryScope.Normal, after.searchFilters.scope)
    }

    @Test
    fun archivedIdentityClearsOnlyItsOwnRememberedSession() {
        assertTrue(
            archivedSessionMatchesRemembered(
                PROVIDER_CODEX,
                archived.id,
                PROVIDER_CODEX,
                archived.id,
            ),
        )
        assertFalse(
            archivedSessionMatchesRemembered(
                PROVIDER_CODEX,
                archived.id,
                PROVIDER_CLAUDE_CODE,
                archived.id,
            ),
        )
        assertFalse(
            archivedSessionMatchesRemembered(
                PROVIDER_CODEX,
                archived.id,
                PROVIDER_CODEX,
                retained.id,
            ),
        )
    }

    @Test
    fun explicitlySelectedArchivedScopeRemainsArchived() {
        val archivedFilters = filters.copy(scope = SessionDiscoveryScope.Archived)
        val state = UiState(screen = Screen.Sessions, searchFilters = archivedFilters)

        val after = state.afterSessionArchived(PROVIDER_CODEX, "external-session")

        assertEquals(archivedFilters, after.searchFilters)
        assertEquals(SessionDiscoveryScope.Archived, after.searchFilters.scope)
    }
}
