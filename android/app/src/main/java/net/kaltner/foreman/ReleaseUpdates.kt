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
