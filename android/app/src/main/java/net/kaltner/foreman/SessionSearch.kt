package net.kaltner.foreman

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class SessionSearchStatus { All, Active, Waiting, Completed, Failed, Interrupted }
enum class SessionDateRange { All, Today, Last7Days, Last30Days, Custom }

data class SessionSearchFilters(
    val query: String = "",
    val repository: String = "",
    val status: SessionSearchStatus = SessionSearchStatus.All,
    val dateRange: SessionDateRange = SessionDateRange.All,
    val dateFrom: String = "",
    val dateTo: String = "",
    val pinnedOnly: Boolean = false,
    val hiddenOnly: Boolean = false,
)

data class SessionRepositoryOption(val id: String, val label: String)

data class VisibleSession(
    val session: SessionSummary,
    val matches: List<SessionSearchMatch>,
    val pinned: Boolean,
    val hidden: Boolean,
)

internal fun sessionRepositoryIdentity(
    path: String,
    repositories: List<RepositoryInfo>,
    repositoryRoot: String,
): SessionRepositoryOption {
    val cwd = normalizeSessionPath(path)
    val match = repositories.map { repository ->
        val absolute =
            if (repository.path.startsWith('/')) repository.path
            else "${repositoryRoot.trimEnd('/')}/${repository.path}"
        repository to normalizeSessionPath(absolute)
    }.sortedByDescending { it.second.length }
        .firstOrNull { (_, root) -> cwd == root || cwd.startsWith("$root/") }
    return if (match != null) {
        SessionRepositoryOption(match.second, "Repository: ${match.first.name}")
    } else {
        SessionRepositoryOption(cwd, "Workspace: ${cwd.ifBlank { "(unknown)" }}")
    }
}

internal fun sessionRepositoryOptions(
    sessions: List<SessionSummary>,
    repositories: List<RepositoryInfo>,
    repositoryRoot: String,
): List<SessionRepositoryOption> =
    sessions.map { sessionRepositoryIdentity(it.repository, repositories, repositoryRoot) }
        .distinctBy { it.id }
        .sortedBy { it.label.lowercase() }

internal fun filterSessions(
    sessions: List<SessionSummary>,
    filters: SessionSearchFilters,
    pinnedIds: Set<String>,
    hiddenIds: Set<String>,
    results: List<SessionSearchResult>,
    repositories: List<RepositoryInfo>,
    repositoryRoot: String,
    nowMillis: Long = System.currentTimeMillis(),
): List<VisibleSession> {
    val normalizedPinnedIds = pinnedIds.mapTo(linkedSetOf(), ::legacySessionKey)
    val normalizedHiddenIds = hiddenIds.mapTo(linkedSetOf(), ::legacySessionKey)
    // The protocol's transcript search remains Codex-only. Claude sessions still
    // participate in local title/workspace/status/pin filtering.
    val remote = results.associateBy { it.session.providerKey() }
    val source = linkedMapOf<String, SessionSummary>()
    sessions.forEach { source[it.providerKey()] = it }
    results.forEach { result ->
        val key = result.session.providerKey()
        source[key] = source[key]?.let { result.session.copy(
            status = it.status,
            attention = it.attention,
            lastActivity = it.lastActivity,
            activeTurnId = it.activeTurnId,
        ) } ?: result.session
    }
    val query = filters.query.trim().lowercase()
    val bounds = sessionDateBounds(filters, nowMillis)
    return source.values.mapNotNull { session ->
        val key = session.providerKey()
        val hidden = key in normalizedHiddenIds
        if (filters.hiddenOnly != hidden) return@mapNotNull null
        if (filters.pinnedOnly && key !in normalizedPinnedIds) return@mapNotNull null
        val identity = sessionRepositoryIdentity(session.repository, repositories, repositoryRoot)
        if (filters.repository.isNotBlank() && identity.id != filters.repository) return@mapNotNull null
        if (!sessionStatusMatches(session.status, filters.status)) return@mapNotNull null
        val activity = session.lastActivity?.let { if (it > 10_000_000_000) it / 1000 else it }
        if (bounds.first != null && (activity == null || activity < bounds.first!!)) return@mapNotNull null
        if (bounds.second != null && (activity == null || activity > bounds.second!!)) return@mapNotNull null
        val localMatch = "${session.title}\n${identity.label}\n${identity.id}\n${session.repository}"
            .lowercase().contains(query)
        if (query.isNotBlank() && !localMatch && key !in remote) return@mapNotNull null
        VisibleSession(session, remote[key]?.matches.orEmpty(), key in normalizedPinnedIds, hidden)
    }.sortedWith(
        compareByDescending<VisibleSession> { it.pinned }
            .thenBy { if (it.session.status == "waiting" || it.session.attention) 0 else if (it.session.status == "working") 1 else 2 }
            .thenByDescending { it.session.lastActivity ?: 0L }
            .thenBy { it.session.id },
    )
}

internal fun sessionDateBounds(
    filters: SessionSearchFilters,
    nowMillis: Long = System.currentTimeMillis(),
): Pair<Long?, Long?> {
    if (filters.dateRange == SessionDateRange.All) return null to null
    if (filters.dateRange == SessionDateRange.Custom) {
        return parseLocalDate(filters.dateFrom, false) to parseLocalDate(filters.dateTo, true)
    }
    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (filters.dateRange == SessionDateRange.Last7Days) add(Calendar.DAY_OF_YEAR, -6)
        if (filters.dateRange == SessionDateRange.Last30Days) add(Calendar.DAY_OF_YEAR, -29)
    }
    return calendar.timeInMillis / 1000 to null
}

internal fun sessionSearchActive(filters: SessionSearchFilters): Boolean =
    filters.query.isNotBlank() || filters.repository.isNotBlank() ||
        filters.status != SessionSearchStatus.All || filters.dateRange != SessionDateRange.All ||
        filters.pinnedOnly || filters.hiddenOnly

internal fun sessionSearchRequestKey(filters: SessionSearchFilters): String =
    listOf(
        filters.query.trim().lowercase(),
        filters.repository,
        filters.status.name,
        filters.dateRange.name,
        filters.dateFrom,
        filters.dateTo,
    ).joinToString("\u0000")

internal fun searchStatusProtocol(status: SessionSearchStatus): List<String> = when (status) {
    SessionSearchStatus.All -> emptyList()
    SessionSearchStatus.Active -> listOf("active")
    SessionSearchStatus.Waiting -> listOf("waiting")
    SessionSearchStatus.Completed -> listOf("completed")
    SessionSearchStatus.Failed -> listOf("failed")
    SessionSearchStatus.Interrupted -> listOf("interrupted")
}

private fun sessionStatusMatches(actual: String, selected: SessionSearchStatus): Boolean = when (selected) {
    SessionSearchStatus.All -> true
    SessionSearchStatus.Active -> actual == "working"
    SessionSearchStatus.Waiting -> actual == "waiting"
    SessionSearchStatus.Completed -> actual == "completed" || actual == "idle"
    SessionSearchStatus.Failed -> actual == "failed"
    SessionSearchStatus.Interrupted -> actual == "interrupted"
}

private fun parseLocalDate(value: String, end: Boolean): Long? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return null
    val parsed: Date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(value) ?: return null
    val calendar = Calendar.getInstance().apply {
        time = parsed
        if (end) {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }
    }
    return calendar.timeInMillis / 1000
}

private fun normalizeSessionPath(value: String): String = value.trimEnd('/').ifBlank { "/" }
