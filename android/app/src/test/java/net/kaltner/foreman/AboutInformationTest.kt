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

        assertEquals(releaseVersion, BuildConfig.VERSION_NAME)
        assertTrue(BuildConfig.FOREMAN_BUILD_COMMIT.isNotBlank())
    }

    @Test
    fun disconnectedAboutStillContainsAndroidClientBuild() {
        val information = aboutVersionInformation(null, false, "1.2.3", "abc123def456")

        assertEquals("Unavailable while disconnected", information.server)
        assertEquals("1.2.3 · abc123def456", information.client)
    }

    @Test
    fun differingServerAndClientVersionsStayDistinct() {
        val information = aboutVersionInformation("0.9.0", true, "1.0.0", "unknown")

        assertEquals("0.9.0", information.server)
        assertEquals("1.0.0", information.client)
        assertFalse(information.server == information.client)
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
