package net.kaltner.foreman

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdatesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun release(version: String, available: Boolean = true) =
        ForemanRelease(
            version = version,
            tag = "v$version",
            title = "Foreman $version",
            publishedAt = "2026-08-29T04:47:19Z",
            releaseNotesUrl = "https://github.com/mkaltner/foreman/releases/tag/v$version",
            artifactAvailable = available,
        )

    private fun snapshot(
        serverSupported: ForemanRelease? = release("1.0.0"),
        serverNewest: ForemanRelease? = serverSupported,
        androidSupported: ForemanRelease? = serverSupported,
        androidNewest: ForemanRelease? = androidSupported,
        stale: Boolean = false,
    ) = ReleaseUpdateSnapshot(
        observedAt = "2026-08-30T00:00:00Z",
        stale = stale,
        refreshStatus = if (stale) "unavailable" else "idle",
        components = ReleaseComponents(
            server = ComponentReleaseUpdates(serverSupported, serverNewest),
            android = ComponentReleaseUpdates(androidSupported, androidNewest),
        ),
    )

    @Test
    fun semVerOrderingIncludesPrereleaseRules() {
        assertTrue(parseSemVer("1.10.0")!! > parseSemVer("1.9.0")!!)
        assertTrue(parseSemVer("1.0.0-beta.11")!! > parseSemVer("1.0.0-beta.2")!!)
        assertTrue(parseSemVer("1.0.0")!! > parseSemVer("1.0.0-rc.1")!!)
        assertTrue(parseSemVer("999999999999999999.0.0")!! > parseSemVer("2.0.0")!!)
        assertEquals(parseSemVer("1.0.0+one"), parseSemVer("1.0.0+two"))
        assertNull(parseSemVer("1.0"))
        assertNull(parseSemVer("1.0.0-alpha.01"))
    }

    @Test
    fun installedVersionStatesAreDistinct() {
        val discovery = snapshot()
        val component = discovery.components.server
        assertEquals(UpdateStatusKind.UpToDate, componentUpdateStatus("1.0.0", true, discovery, component, "server").kind)
        assertEquals(UpdateStatusKind.UpdateAvailable, componentUpdateStatus("0.9.0", true, discovery, component, "server").kind)
        assertEquals(UpdateStatusKind.NewerThanLatest, componentUpdateStatus("1.1.0", true, discovery, component, "server").kind)
        assertEquals(UpdateStatusKind.Development, componentUpdateStatus("1.0.0", false, discovery, component, "server").kind)
        assertEquals(UpdateStatusKind.Prerelease, componentUpdateStatus("1.1.0-beta.1", true, discovery, component, "server").kind)
        assertEquals(UpdateStatusKind.Unavailable, componentUpdateStatus("source", true, discovery, component, "server").kind)
    }

    @Test
    fun serverAndInstalledApkUseTheirOwnVersionsAndComponentReleases() {
        val discovery = snapshot(
            serverSupported = release("1.1.0"),
            serverNewest = release("1.1.0"),
            androidSupported = release("1.2.0"),
            androidNewest = release("1.2.0"),
        )
        assertEquals(
            "1.1.0",
            componentUpdateStatus("1.0.0", true, discovery, discovery.components.server, "server").release?.version,
        )
        assertEquals(
            "1.2.0",
            componentUpdateStatus("1.1.0", true, discovery, discovery.components.android, "Android APK").release?.version,
        )
    }

    @Test
    fun incompleteNewestReleaseIsReportedButNotOfferedAsSupported() {
        val discovery = snapshot(
            serverSupported = release("1.0.0"),
            serverNewest = release("1.0.2", false),
        )
        val status = componentUpdateStatus("1.0.2", true, discovery, discovery.components.server, "server")
        assertEquals(UpdateStatusKind.ArtifactUnavailable, status.kind)
        assertEquals("1.0.2", status.release?.version)
    }

    @Test
    fun cachedProjectionRoundTripsForRecreationAndProcessRelaunch() {
        val info = CachedReleaseUpdateInfo("1.0.0", true, snapshot(stale = true))
        val restored = json.decodeFromString<CachedReleaseUpdateInfo>(json.encodeToString(info))
        assertEquals(info, restored)
        assertEquals(info.snapshot, validatedReleaseUpdates(restored.snapshot))
    }

    @Test
    fun unsafeReleaseNoteLinkAndMalformedRemoteVersionAreRejected() {
        val unsafe = release("1.0.0").copy(releaseNotesUrl = "https://example.com/download")
        assertNull(validatedReleaseUpdates(snapshot(serverNewest = unsafe)))
        val malformed = release("1.0.0").copy(version = "latest", tag = "vlatest")
        assertNull(validatedReleaseUpdates(snapshot(androidNewest = malformed)))
    }

    @Test
    fun disconnectedCachedSnapshotRetainsSeparateServerAndApkInformation() {
        val discovery = snapshot(
            serverSupported = release("1.1.0"),
            androidSupported = release("1.2.0"),
            stale = true,
        )
        val info = CachedReleaseUpdateInfo("1.0.0", true, discovery)
        assertTrue(info.snapshot.stale)
        assertEquals("1.1.0", info.snapshot.components.server.supportedRelease?.version)
        assertEquals("1.2.0", info.snapshot.components.android.supportedRelease?.version)
    }

    @Test
    fun releaseCheckResultIsDiscardedAfterHostSwitch() {
        assertTrue(releaseCheckStillApplies("host-a", "host-a", 4L, 4L))
        assertEquals(false, releaseCheckStillApplies("host-a", "host-b", 4L, 4L))
        assertEquals(false, releaseCheckStillApplies("host-a", "host-a", 4L, 5L))
    }
}
