package net.kaltner.foreman

import java.math.BigInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

private const val RELEASE_NOTES_PREFIX = "https://github.com/mkaltner/foreman/releases/tag/"
private val isoTimestampPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$")
private val semVerPattern = Regex(
    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
        "(?:-((?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)" +
        "(?:\\.(?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?" +
        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
)

@Serializable
internal data class ForemanRelease(
    val version: String,
    val tag: String,
    val title: String,
    val publishedAt: String,
    val releaseNotesUrl: String,
    val artifactAvailable: Boolean,
)

@Serializable
internal data class ComponentReleaseUpdates(
    val supportedRelease: ForemanRelease? = null,
    val newestRelease: ForemanRelease? = null,
)

@Serializable
internal data class ReleaseComponents(
    val server: ComponentReleaseUpdates,
    val android: ComponentReleaseUpdates,
)

@Serializable
internal data class ReleaseUpdateSnapshot(
    val observedAt: String? = null,
    val stale: Boolean,
    val refreshStatus: String,
    val unavailableReason: String? = null,
    val components: ReleaseComponents,
)

@Serializable
internal data class CachedReleaseUpdateInfo(
    val serverVersion: String? = null,
    val serverReleaseBuild: Boolean? = null,
    val snapshot: ReleaseUpdateSnapshot,
)

internal data class ParsedSemVer(
    val core: List<BigInteger>,
    val prerelease: List<String>,
) : Comparable<ParsedSemVer> {
    override fun compareTo(other: ParsedSemVer): Int {
        core.indices.forEach { index ->
            core[index].compareTo(other.core[index]).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        repeat(maxOf(prerelease.size, other.prerelease.size)) { index ->
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            if (left == right) return@repeat
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && rightNumeric -> BigInteger(left).compareTo(BigInteger(right))
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }
        return 0
    }
}

internal fun parseSemVer(value: String): ParsedSemVer? {
    if (value.toByteArray().size > 80) return null
    val match = semVerPattern.matchEntire(value) ?: return null
    return ParsedSemVer(
        core = match.groupValues.slice(1..3).map(::BigInteger),
        prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList(),
    )
}

internal enum class UpdateStatusKind {
    UpToDate,
    UpdateAvailable,
    Checking,
    Unavailable,
    Development,
    Prerelease,
    NewerThanLatest,
    ArtifactUnavailable,
}

internal data class ComponentUpdateStatus(
    val kind: UpdateStatusKind,
    val label: String,
    val detail: String? = null,
    val release: ForemanRelease? = null,
)

internal fun componentUpdateStatus(
    installedVersion: String?,
    releaseBuild: Boolean?,
    discovery: ReleaseUpdateSnapshot?,
    component: ComponentReleaseUpdates?,
    artifactLabel: String,
): ComponentUpdateStatus {
    if (releaseBuild == false) {
        return ComponentUpdateStatus(
            UpdateStatusKind.Development,
            "Development or source-checkout build",
            "Stable update comparisons are not applied to this build.",
        )
    }
    val installed = installedVersion?.let(::parseSemVer)
        ?: return ComponentUpdateStatus(
            UpdateStatusKind.Unavailable,
            "Check unavailable",
            "The installed version is unknown or malformed.",
        )
    if (installed.prerelease.isNotEmpty()) {
        return ComponentUpdateStatus(
            UpdateStatusKind.Prerelease,
            "Prerelease build",
            "Prereleases are not treated as ordinary stable updates.",
        )
    }
    if (discovery == null || component == null) {
        return ComponentUpdateStatus(
            UpdateStatusKind.Unavailable,
            "Check unavailable",
            "No validated release information is cached.",
        )
    }
    if (discovery.observedAt == null && discovery.refreshStatus == "checking") {
        return ComponentUpdateStatus(UpdateStatusKind.Checking, "Checking…")
    }
    val supported = component.supportedRelease
    val newest = component.newestRelease
    val supportedVersion = supported?.version?.let(::parseSemVer)
    val newestVersion = newest?.version?.let(::parseSemVer)
        ?: return ComponentUpdateStatus(
            UpdateStatusKind.Unavailable,
            "Check unavailable",
            "No valid stable release was found.",
        )
    if (supported != null && supportedVersion != null && installed < supportedVersion) {
        return ComponentUpdateStatus(
            UpdateStatusKind.UpdateAvailable,
            "Update available · ${supported.version}",
            "A complete stable $artifactLabel release is available.",
            supported,
        )
    }
    if (!newest.artifactAvailable && installed <= newestVersion) {
        return ComponentUpdateStatus(
            UpdateStatusKind.ArtifactUnavailable,
            "Release ${newest.version} exists, but its $artifactLabel artifact or checksum is unavailable",
            supported?.let { "${it.version} is the newest complete supported release." }
                ?: "No complete supported release is available.",
            newest,
        )
    }
    if (installed > newestVersion) {
        return ComponentUpdateStatus(
            UpdateStatusKind.NewerThanLatest,
            "Installed version is newer than the latest published stable release",
            "Latest published: ${newest.version}. No downgrade is recommended.",
        )
    }
    return ComponentUpdateStatus(
        UpdateStatusKind.UpToDate,
        "Up to date",
        supported?.let { "Latest supported: ${it.version}." },
    )
}

private fun validRelease(release: ForemanRelease?, supported: Boolean): ForemanRelease? {
    if (release == null) return null
    return release.takeIf {
        it.version.length <= 80 && parseSemVer(it.version) != null &&
            it.tag == "v${it.version}" && it.title.isNotBlank() && it.title.length <= 160 &&
            it.publishedAt.length <= 40 && isoTimestampPattern.matches(it.publishedAt) &&
            it.releaseNotesUrl == "$RELEASE_NOTES_PREFIX${it.tag}" &&
            (!supported || it.artifactAvailable)
    }
}

internal fun validatedReleaseUpdates(snapshot: ReleaseUpdateSnapshot?): ReleaseUpdateSnapshot? {
    if (snapshot == null || snapshot.refreshStatus !in setOf("idle", "checking", "unavailable")) return null
    if (snapshot.observedAt != null &&
        (snapshot.observedAt.length > 40 || !isoTimestampPattern.matches(snapshot.observedAt))
    ) return null
    if (snapshot.unavailableReason != null && snapshot.unavailableReason.length > 80) return null
    fun component(value: ComponentReleaseUpdates): ComponentReleaseUpdates? {
        val supported = validRelease(value.supportedRelease, true)
        val newest = validRelease(value.newestRelease, false)
        if (value.supportedRelease != null && supported == null) return null
        if (value.newestRelease != null && newest == null) return null
        return ComponentReleaseUpdates(supported, newest)
    }
    val server = component(snapshot.components.server) ?: return null
    val android = component(snapshot.components.android) ?: return null
    return snapshot.copy(components = ReleaseComponents(server, android))
}

internal fun decodeReleaseUpdates(json: Json, element: JsonElement?): ReleaseUpdateSnapshot? =
    runCatching { element?.let { json.decodeFromJsonElement<ReleaseUpdateSnapshot>(it) } }
        .getOrNull()
        .let(::validatedReleaseUpdates)

@Serializable
internal data class ServerUpdateBlocker(
    val category: String,
    val count: Int,
)

@Serializable
internal data class ServerUpdateOperation(
    val id: String,
    val phase: String,
    val currentVersion: String,
    val targetVersion: String,
    val source: String,
    val sourceUrl: String,
    val releaseNotesUrl: String,
    val progress: Int,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String? = null,
    val resultCode: String? = null,
    val message: String? = null,
    val recoveryCommand: String? = null,
)

@Serializable
internal data class ServerUpdateCheck(
    val currentVersion: String,
    val releaseBuild: Boolean,
    val source: String,
    val sourceUrl: String,
    val updateAvailable: Boolean,
    val target: ForemanRelease? = null,
    val blockers: List<ServerUpdateBlocker> = emptyList(),
    val operation: ServerUpdateOperation? = null,
)

internal val terminalServerUpdatePhases = setOf(
    "succeeded", "rolledBack", "recoveryRequired", "blocked", "failed", "interrupted",
)
private val serverUpdatePhases = terminalServerUpdatePhases + setOf(
    "downloading", "verifying", "staging", "activationScheduled",
    "activating", "restarting", "healthChecking", "rollingBack",
)
private val blockerCategories = setOf(
    "workingSession", "waitingSession", "pendingApproval", "pendingInput",
)
private const val OFFICIAL_RELEASE_SOURCE = "https://github.com/mkaltner/foreman/releases"
private const val OFFICIAL_RELEASE_SOURCE_LABEL = "Official Foreman GitHub releases"

internal fun validatedServerUpdateOperation(value: ServerUpdateOperation?): ServerUpdateOperation? =
    value?.takeIf {
        it.id.matches(Regex("^fmu_[A-Za-z0-9_-]{16,80}$")) &&
            it.phase in serverUpdatePhases &&
            parseSemVer(it.currentVersion) != null &&
            parseSemVer(it.targetVersion)?.prerelease?.isEmpty() == true &&
            it.source == OFFICIAL_RELEASE_SOURCE_LABEL && it.sourceUrl == OFFICIAL_RELEASE_SOURCE &&
            it.releaseNotesUrl == "${RELEASE_NOTES_PREFIX}v${it.targetVersion}" &&
            it.progress in 0..100 && isoTimestampPattern.matches(it.createdAt) && isoTimestampPattern.matches(it.updatedAt) &&
            (it.completedAt == null || isoTimestampPattern.matches(it.completedAt)) &&
            (it.message == null || it.message.isNotEmpty() && it.message.length <= 500) &&
            (it.resultCode == null || it.resultCode.isNotEmpty() && it.resultCode.length <= 80) &&
            (it.recoveryCommand == null || it.recoveryCommand == "foreman update --recover")
    }

internal fun validatedServerUpdateCheck(value: ServerUpdateCheck?): ServerUpdateCheck? =
    value?.takeIf {
        parseSemVer(it.currentVersion) != null && it.source == OFFICIAL_RELEASE_SOURCE_LABEL &&
            it.sourceUrl == OFFICIAL_RELEASE_SOURCE &&
            it.blockers.all { blocker -> blocker.category in blockerCategories && blocker.count in 1..10_000 } &&
            (it.operation == null || validatedServerUpdateOperation(it.operation) != null) &&
            (!it.updateAvailable || it.target != null) &&
            (it.target == null || validRelease(it.target, supported = it.updateAvailable) != null)
    }

internal fun decodeServerUpdateOperation(json: Json, element: JsonElement?): ServerUpdateOperation? =
    runCatching { element?.let { json.decodeFromJsonElement<ServerUpdateOperation>(it) } }
        .getOrNull()
        .let(::validatedServerUpdateOperation)

internal fun decodeServerUpdateCheck(json: Json, element: JsonElement?): ServerUpdateCheck? =
    runCatching { element?.let { json.decodeFromJsonElement<ServerUpdateCheck>(it) } }
        .getOrNull()
        .let(::validatedServerUpdateCheck)

internal fun serverUpdatePhaseLabel(phase: String): String =
    mapOf(
        "downloading" to "Downloading",
        "verifying" to "Verifying signature",
        "staging" to "Staging",
        "activationScheduled" to "Activation scheduled",
        "activating" to "Activating",
        "restarting" to "Restarting Foreman",
        "healthChecking" to "Health checking",
        "rollingBack" to "Rolling back",
        "succeeded" to "Update complete",
        "rolledBack" to "Previous version restored",
        "recoveryRequired" to "Recovery required",
        "blocked" to "Blocked by active work",
        "failed" to "Update failed",
        "interrupted" to "Update interrupted",
    )[phase] ?: "Update"
