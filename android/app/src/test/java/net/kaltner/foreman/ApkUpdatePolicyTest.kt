package net.kaltner.foreman

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApkUpdatePolicyTest {
    private fun release(version: String = "1.1.0") =
        ForemanRelease(
            version = version,
            tag = "v$version",
            title = "Foreman $version",
            publishedAt = "2026-08-31T00:00:00Z",
            releaseNotesUrl = "https://github.com/mkaltner/foreman/releases/tag/v$version",
            artifactAvailable = true,
        )

    private fun assets(version: String = "1.1.0"): List<AndroidReleaseAsset> {
        val tag = "v$version"
        fun asset(name: String, size: Long = 100) =
            AndroidReleaseAsset(name, size, "$OFFICIAL_RELEASE_DOWNLOAD_PREFIX$tag/$name")
        return listOf(
            asset("foreman-$tag.apk", 10_000),
            asset("foreman-linux-$tag.tar.gz", 20_000),
            asset("SHA256SUMS"),
            asset("SHA256SUMS.sig"),
            asset("foreman-release-cert.pem"),
        )
    }

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (error: ApkUpdateValidationException) {
            assertEquals(code, error.code)
        }
    }

    @Test
    fun exactApkAndVerificationAssetsAreSelected() {
        val selected = selectAndroidReleaseAssets(release(), "v1.1.0", false, false, assets())
        assertEquals("foreman-v1.1.0.apk", selected.apk.name)
        assertEquals("SHA256SUMS", selected.checksumManifest.name)
    }

    @Test
    fun missingDuplicateEmptyUnexpectedAndMismatchedAssetsFailClosed() {
        expectCode("missingApk") {
            selectAndroidReleaseAssets(release(), "v1.1.0", false, false, assets().drop(1).filterNot { it.name.endsWith(".apk") })
        }
        expectCode("duplicateAssets") {
            selectAndroidReleaseAssets(release(), "v1.1.0", false, false, assets() + assets().first())
        }
        expectCode("emptyAsset") {
            selectAndroidReleaseAssets(release(), "v1.1.0", false, false, assets().map { if (it.name.endsWith(".apk")) it.copy(size = 0) else it })
        }
        expectCode("unexpectedAssets") {
            selectAndroidReleaseAssets(release(), "v1.1.0", false, false, assets().dropLast(1) + AndroidReleaseAsset("other.txt", 1, "$OFFICIAL_RELEASE_DOWNLOAD_PREFIX/v1.1.0/other.txt"))
        }
        expectCode("releaseMismatch") {
            selectAndroidReleaseAssets(release(), "v1.2.0", false, false, assets())
        }
        expectCode("untrustedAssetUrl") {
            selectAndroidReleaseAssets(release(), "v1.1.0", false, false, assets().map { if (it.name.endsWith(".apk")) it.copy(downloadUrl = "https://example.com/app.apk") else it })
        }
        expectCode("releaseNotStable") {
            selectAndroidReleaseAssets(release(), "v1.1.0", false, false, assets(), publishedAt = null)
        }
    }

    @Test
    fun checksumManifestRequiresExactPayloadEntries() {
        val manifest = fixtureManifest()
        val parsed = parseChecksumManifest(manifest, "foreman-v1.1.0.apk", "foreman-linux-v1.1.0.tar.gz")
        assertEquals("a".repeat(64), parsed["foreman-v1.1.0.apk"])
        expectCode("duplicateChecksum") {
            val duplicate = "${"a".repeat(64)}  foreman-v1.1.0.apk\n".repeat(2).toByteArray()
            parseChecksumManifest(duplicate, "foreman-v1.1.0.apk", "foreman-linux-v1.1.0.tar.gz")
        }
        expectCode("checksumAssetMismatch") {
            val unexpected =
                "${"a".repeat(64)}  foreman-v1.1.0.apk\n" +
                    "${"b".repeat(64)}  unexpected.tar.gz\n"
            parseChecksumManifest(unexpected.toByteArray(), "foreman-v1.1.0.apk", "foreman-linux-v1.1.0.tar.gz")
        }
    }

    @Test
    fun signedManifestMatchesPinnedAndInstalledCertificate() {
        val verified =
            verifySignedReleaseManifest(
                certificateBytes = decode(CERTIFICATE),
                signatureBytes = decode(SIGNATURE),
                manifestBytes = fixtureManifest(),
                expectedCertificateFingerprint = CERTIFICATE_SHA256,
                installedCertificateFingerprint = CERTIFICATE_SHA256,
                expectedApkName = "foreman-v1.1.0.apk",
                expectedArchiveName = "foreman-linux-v1.1.0.tar.gz",
            )
        assertEquals(CERTIFICATE_SHA256, verified.certificateFingerprint)
        expectCode("releaseCertificateMismatch") {
            verifySignedReleaseManifest(
                decode(CERTIFICATE), decode(SIGNATURE), fixtureManifest(), "0".repeat(64),
                CERTIFICATE_SHA256, "foreman-v1.1.0.apk", "foreman-linux-v1.1.0.tar.gz",
            )
        }
        expectCode("manifestSignatureMismatch") {
            verifySignedReleaseManifest(
                decode(CERTIFICATE), decode(SIGNATURE), fixtureManifest() + byteArrayOf(1), CERTIFICATE_SHA256,
                CERTIFICATE_SHA256, "foreman-v1.1.0.apk", "foreman-linux-v1.1.0.tar.gz",
            )
        }
    }

    @Test
    fun apkPackageSignerAndBothVersionsMustMatchAndAdvance() {
        val installed = identity("1.0.3", 10)
        validateDownloadedApkIdentity(installed, identity("1.1.0", 11), "1.1.0", CERTIFICATE_SHA256)
        expectCode("sameVersion") {
            validateDownloadedApkIdentity(installed, identity("1.0.3", 10), "1.0.3", CERTIFICATE_SHA256)
        }
        expectCode("downgrade") {
            validateDownloadedApkIdentity(installed, identity("1.0.2", 9), "1.0.2", CERTIFICATE_SHA256)
        }
        expectCode("apkVersionMismatch") {
            validateDownloadedApkIdentity(installed, identity("1.1.1", 11), "1.1.0", CERTIFICATE_SHA256)
        }
        expectCode("apkSignerMismatch") {
            validateDownloadedApkIdentity(installed, identity("1.1.0", 11, "f".repeat(64)), "1.1.0", CERTIFICATE_SHA256)
        }
        expectCode("apkSignerMismatch") {
            validateDownloadedApkIdentity(installed, identity("1.1.0", 11, ""), "1.1.0", CERTIFICATE_SHA256)
        }
        expectCode("packageMismatch") {
            validateDownloadedApkIdentity(installed, identity("1.1.0", 11).copy(packageName = "invalid.app"), "1.1.0", CERTIFICATE_SHA256)
        }
    }

    @Test
    fun downloadTransitionsSupportRetryAndCancellation() {
        assertEquals(ApkUpdatePhase.Discovering, nextApkDownloadPhase(ApkUpdatePhase.Idle, ApkDownloadEvent.Start))
        assertEquals(ApkUpdatePhase.Downloading, nextApkDownloadPhase(ApkUpdatePhase.Discovering, ApkDownloadEvent.MetadataSelected))
        assertEquals(ApkUpdatePhase.Interrupted, nextApkDownloadPhase(ApkUpdatePhase.Downloading, ApkDownloadEvent.Interrupted))
        assertEquals(ApkUpdatePhase.Discovering, nextApkDownloadPhase(ApkUpdatePhase.Interrupted, ApkDownloadEvent.Retry))
        assertEquals(ApkUpdatePhase.Canceled, nextApkDownloadPhase(ApkUpdatePhase.Downloading, ApkDownloadEvent.Cancel))
        assertNull(nextApkDownloadPhase(ApkUpdatePhase.Ready, ApkDownloadEvent.Start))
    }

    @Test
    fun unknownAppPermissionTransitionsOnlyAfterVerifiedUpdate() {
        assertEquals(ApkUpdatePhase.Failed, installerPhaseAfterRequest(false, true))
        assertEquals(ApkUpdatePhase.ExplainingPermission, installerPhaseAfterRequest(true, true))
        assertEquals(ApkUpdatePhase.AwaitingInstaller, installerPhaseAfterRequest(true, false))
        assertEquals(ApkUpdatePhase.AwaitingInstaller, installerPhaseAfterPermission(true))
        assertEquals(ApkUpdatePhase.Ready, installerPhaseAfterPermission(false))
    }

    @Test
    fun concurrentDownloadsAndInstallerLaunchesAreSuppressed() {
        val gate = ApkUpdateConcurrencyGate()
        assertTrue(gate.claimDownload())
        assertFalse(gate.claimDownload())
        gate.releaseDownload()
        assertTrue(gate.claimDownload())
        assertTrue(gate.claimInstaller())
        assertFalse(gate.claimInstaller())
        gate.releaseInstaller()
        assertTrue(gate.claimInstaller())
    }

    @Test
    fun pendingVerifiedUpdateRestoresAcrossRecreationAndReconcilesReplacement() {
        assertEquals(
            ApkUpdatePhase.Ready,
            restoredApkUpdatePhase(ApkUpdatePhase.ExplainingPermission, true, 10, 11),
        )
        assertEquals(
            ApkUpdatePhase.AwaitingInstaller,
            restoredApkUpdatePhase(ApkUpdatePhase.AwaitingInstaller, true, 10, 11),
        )
        assertEquals(
            ApkUpdatePhase.Interrupted,
            restoredApkUpdatePhase(ApkUpdatePhase.Downloading, false, 10, null),
        )
        assertEquals(
            ApkUpdatePhase.Completed,
            restoredApkUpdatePhase(ApkUpdatePhase.AwaitingInstaller, true, 11, 11),
        )
        assertEquals(
            ApkUpdatePhase.Canceled,
            restoredApkUpdatePhase(ApkUpdatePhase.Canceled, false, 10, null),
        )
    }

    private fun identity(version: String, code: Long, signer: String = CERTIFICATE_SHA256) =
        InstalledApkIdentity(FOREMAN_APPLICATION_ID, version, code, signer)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    private fun fixtureManifest(): ByteArray = decode(MANIFEST)

    companion object {
        private const val CERTIFICATE_SHA256 = "bab46b001ed7aae080b63152d3eb24c550b422866b0d4ceede4ef1404b83b013"
        private const val CERTIFICATE = "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUREekNDQWZlZ0F3SUJBZ0lVUUlLZDNibTg3RE1zQTlRVUNXaE5lbExGcXhVd0RRWUpLb1pJaHZjTkFRRUwKQlFBd0Z6RVZNQk1HQTFVRUF3d01SbTl5WlcxaGJpQlVaWE4wTUI0WERUSTJNRGd6TVRFME16VXpObG9YRFRNMgpNRGd5T0RFME16VXpObG93RnpFVk1CTUdBMVVFQXd3TVJtOXlaVzFoYmlCVVpYTjBNSUlCSWpBTkJna3Foa2lHCjl3MEJBUUVGQUFPQ0FROEFNSUlCQ2dLQ0FRRUFyT2dWakhqSUI0UW9ySjJDVEZ6R3ZaUzFldG1UQk1JNktGQ0oKZVBJSzg4KzRRZDdGdGlvWUdHbUt3aHA5RDdmS2dqMkFjZjV3d01PempiOERwNjdIaGw2Kys1KzF1dmZZMzlRVgo4VUlTQzMybTJ6K2pybDRhN2lxMllTUjFkTUJEb3RaanBWK09HdTR2a0JYbUhSSTd6c1FhNERNcHo3MUlvZUxQCndnd0NMVVExY05GVitIbFR1UE5pRDR2TkYyVnRXYnd1VEExdmx6THpRdHkrRkZhOUM3ZDJ0VFREZXRScUxKMEsKb0hEOFhWbEZ0ejlWejFMU0ovRTFIWVJhWTJwUGZnZG1EbkoyZjhRTkVJOGdkNGh3NU16WlNBcFRoSVNqV0UzaApqb3RRSHREdURYK1lUYkthMnVqUFAxZTErWWFQNWlYdXovbEJLUHR1dDVqcUhkV1J5d0lEQVFBQm8xTXdVVEFkCkJnTlZIUTRFRmdRVUVIUGhpOVV0bmlDdGtFb3EwbXZyOVZtNkJhOHdId1lEVlIwakJCZ3dGb0FVRUhQaGk5VXQKbmlDdGtFb3EwbXZyOVZtNkJhOHdEd1lEVlIwVEFRSC9CQVV3QXdFQi96QU5CZ2txaGtpRzl3MEJBUXNGQUFPQwpBUUVBV2ZsanlQQWplZEE4eDVvREFGdC9BazhhZnVHcGFQY0YzdU9mOVhSVVJ5Nm5jaWJmNGp0aE5DUHIvcmxwClVZVW9MQ2hwVkFubExiSWVaaFZpSFV1dUVLdWx3cDBML21SVldSY2s1a1FxUmQrQjlDdHBaU0l2eTdjOGtkUlUKT2JubHN5RnhUZHVBZ3YvOHZJdEs1b3Bnckg3NEF0S0NlcW9NaVU0c0o4M3hxUXhwUkRJUGpYVWR0dGF2cUlIbQp5UFB3bkRuZWxvZ1J1eERYLzllcTUxRW4xbnJXdkxNQnR0ZHNxVEJUeVVMdkkxNlo2SkJ4MzJIZnVqSEttL2NCCmloMTNRTGpzOGc3OUpPMnk0bWRLeUYrd3lHL0VENHhaWWxhUzRsZnJUOW52OFRiS0RCYlBVSVFZMVA5YUZOcWwKZE5yNjJpWVkzTHVET2VRbUVmUTBCZHpKY1E9PQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCg=="
        private const val SIGNATURE = "bc554SCb4lWgfqp04RcN2DGafSLaScOLok5SA37AcCkPJx2YS5OPwM652/TzeXdZtdwJyBNbcuyornTCJlwVsDpqvLQ4xSZI0KmBcIoFYr/hNc4NHNBkayoJmJnw90fNn9jYhz0ERdsAqX/kejUX1vtrv1KBrjNJjE7l3/xON1cEpGb3MiPN3d1uowgRZCrRs5+w+TtLXshp/OQWmRklQ1QxwMN/XbhJWsrtQ0uxncCkm+OfpZ8wUnQf4iGhc6wxUWZ3RLf9ZHAo0Emoc26I9Ze2l7KsOVUInkaZaze8cIkiQZ26iTwo62QtGcP3r/Tnl/zIMm0emSRkRC97gX8dBw=="
        private const val MANIFEST = "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYSAgZm9yZW1hbi12MS4xLjAuYXBrCmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmIgIGZvcmVtYW4tbGludXgtdjEuMS4wLnRhci5nego="
    }
}
