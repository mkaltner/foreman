package net.kaltner.foreman

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutInformationTest {
    @Test
    fun clientVersionIsDerivedFromSharedReleaseProperties() {
        val releaseFile =
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "release.properties") }
                .first { it.isFile }
        val releaseVersion =
            releaseFile.readLines().first { it.startsWith("foremanVersion=") }.substringAfter('=')
        val releaseBuild =
            releaseFile.readLines().first { it.startsWith("releaseBuild=") }.substringAfter('=').toBooleanStrict()

        assertEquals(releaseVersion, BuildConfig.VERSION_NAME)
        assertEquals(releaseBuild, BuildConfig.FOREMAN_RELEASE_BUILD)
        assertTrue(BuildConfig.FOREMAN_BUILD_COMMIT.isNotBlank())
    }

    @Test
    fun disconnectedAboutStillContainsAndroidClientBuild() {
        val information = aboutVersionInformation(null, false, "1.2.3", "abc123def456", false)

        assertEquals("Unavailable while disconnected", information.server)
        assertEquals("1.2.3 (development build) · abc123def456", information.client)
    }

    @Test
    fun disconnectedAboutLabelsRetainedServerVersionAsLastConnected() {
        val information = aboutVersionInformation("1.0.1", false, "1.0.2", "unknown", false)

        assertEquals("1.0.1 (last connected)", information.server)
        assertEquals("1.0.2 (development build)", information.client)
    }

    @Test
    fun differingServerAndClientVersionsStayDistinct() {
        val information = aboutVersionInformation("0.9.0", true, "1.0.2", "unknown", false)

        assertEquals("0.9.0", information.server)
        assertEquals("1.0.2 (development build)", information.client)
        assertFalse(information.server == information.client)
    }

    @Test
    fun officialReleaseBuildDoesNotUseDevelopmentLabel() {
        assertEquals("1.0.2 · abc123def456", clientBuildDescription("1.0.2", "abc123def456", true))
    }

    @Test
    fun aboutLinksUsePublicHttpsTargets() {
        assertEquals(
            listOf(
                "GitHub repository" to "https://github.com/mkaltner/foreman",
                "Current releases" to "https://github.com/mkaltner/foreman/releases",
                "License" to "https://github.com/mkaltner/foreman/blob/main/LICENSE",
                "Third-party notices" to "https://github.com/mkaltner/foreman/blob/main/THIRD_PARTY_NOTICES.md",
            ),
            foremanAboutLinks,
        )
        assertTrue(foremanAboutLinks.all { (_, url) -> url.startsWith("https://") })
    }
}
