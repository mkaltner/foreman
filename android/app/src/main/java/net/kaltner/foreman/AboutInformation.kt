package net.kaltner.foreman

internal const val FOREMAN_REPOSITORY_URL = "https://github.com/mkaltner/foreman"
internal const val FOREMAN_RELEASES_URL = "$FOREMAN_REPOSITORY_URL/releases"
internal const val FOREMAN_LICENSE_URL = "$FOREMAN_REPOSITORY_URL/blob/main/LICENSE"
internal const val FOREMAN_THIRD_PARTY_NOTICES_URL =
    "$FOREMAN_REPOSITORY_URL/blob/main/THIRD_PARTY_NOTICES.md"

internal data class AboutVersionInformation(
    val server: String,
    val client: String,
)

internal fun clientBuildDescription(version: String, commit: String, releaseBuild: Boolean): String {
    val identity = if (releaseBuild) version else "$version (development build)"
    return if (commit.isNotBlank() && commit != "unknown") "$identity · $commit" else identity
}

internal fun aboutVersionInformation(
    serverVersion: String?,
    connected: Boolean,
    clientVersion: String,
    clientCommit: String,
    releaseBuild: Boolean,
): AboutVersionInformation =
    AboutVersionInformation(
        server = if (connected) {
            serverVersion ?: "Unavailable"
        } else {
            serverVersion?.let { "$it (last connected)" } ?: "Unavailable while disconnected"
        },
        client = clientBuildDescription(clientVersion, clientCommit, releaseBuild),
    )

internal val foremanAboutLinks =
    listOf(
        "GitHub repository" to FOREMAN_REPOSITORY_URL,
        "Current releases" to FOREMAN_RELEASES_URL,
        "License" to FOREMAN_LICENSE_URL,
        "Third-party notices" to FOREMAN_THIRD_PARTY_NOTICES_URL,
    )
