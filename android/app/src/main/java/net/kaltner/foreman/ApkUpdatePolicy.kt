package net.kaltner.foreman

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

internal const val FOREMAN_APPLICATION_ID = "net.kaltner.foreman"
internal const val OFFICIAL_APK_RELEASE_SOURCE_LABEL = "Official Foreman GitHub releases"
internal const val OFFICIAL_RELEASES_API = "https://api.github.com/repos/mkaltner/foreman/releases/tags/"
internal const val OFFICIAL_RELEASE_DOWNLOAD_PREFIX =
    "https://github.com/mkaltner/foreman/releases/download/"
internal const val MAX_APK_BYTES = 200L * 1024L * 1024L
internal const val MAX_RELEASE_METADATA_BYTES = 64L * 1024L
internal const val MAX_LINUX_ARCHIVE_BYTES = 300L * 1024L * 1024L

@Serializable
internal data class AndroidReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
)

@Serializable
internal data class AndroidReleaseAssets(
    val version: String,
    val tag: String,
    val releaseNotesUrl: String,
    val apk: AndroidReleaseAsset,
    val checksumManifest: AndroidReleaseAsset,
    val checksumSignature: AndroidReleaseAsset,
    val releaseCertificate: AndroidReleaseAsset,
)

internal class ApkUpdateValidationException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

private fun apkValidationFailure(code: String, message: String): Nothing =
    throw ApkUpdateValidationException(code, message)

internal fun selectAndroidReleaseAssets(
    target: ForemanRelease,
    tag: String,
    draft: Boolean,
    prerelease: Boolean,
    assets: List<AndroidReleaseAsset>,
    publishedAt: String? = "1970-01-01T00:00:00Z",
): AndroidReleaseAssets {
    if (
        draft || prerelease || publishedAt == null ||
        parseSemVer(target.version)?.prerelease?.isNotEmpty() != false
    ) {
        apkValidationFailure("releaseNotStable", "The selected Android release is not a published stable release.")
    }
    if (
        tag != target.tag || tag != "v${target.version}" || !target.artifactAvailable ||
        target.releaseNotesUrl != "https://github.com/mkaltner/foreman/releases/tag/$tag"
    ) {
        apkValidationFailure("releaseMismatch", "The Android release metadata does not match the available version.")
    }
    if (assets.size > 50) {
        apkValidationFailure("tooManyAssets", "The Android release contains too many assets to validate safely.")
    }
    val expectedApk = "foreman-$tag.apk"
    val expectedArchive = "foreman-linux-$tag.tar.gz"
    val expectedNames =
        setOf(
            expectedApk,
            expectedArchive,
            "SHA256SUMS",
            "SHA256SUMS.sig",
            "foreman-release-cert.pem",
        )
    val names = assets.map(AndroidReleaseAsset::name)
    val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) {
        apkValidationFailure("duplicateAssets", "The Android release contains duplicate assets.")
    }
    if (names.toSet() != expectedNames || names.size != expectedNames.size) {
        val apkCandidates = names.filter { it.endsWith(".apk", ignoreCase = true) }
        val code = when {
            expectedApk !in names -> "missingApk"
            apkCandidates.size != 1 -> "ambiguousApk"
            else -> "unexpectedAssets"
        }
        apkValidationFailure(code, "The Android release does not contain the exact expected Foreman asset set.")
    }
    val byName = assets.associateBy(AndroidReleaseAsset::name)
    fun asset(name: String, maximum: Long): AndroidReleaseAsset {
        val value = byName[name]
            ?: apkValidationFailure("missingAsset", "The Android release is missing a required verification asset.")
        if (value.size <= 0L) {
            apkValidationFailure("emptyAsset", "The Android release contains an empty asset.")
        }
        if (value.size > maximum) {
            apkValidationFailure("oversizedAsset", "The Android release contains an unexpectedly large asset.")
        }
        val expectedUrl = "$OFFICIAL_RELEASE_DOWNLOAD_PREFIX$tag/$name"
        if (value.downloadUrl != expectedUrl) {
            apkValidationFailure("untrustedAssetUrl", "The Android release contains an unexpected download source.")
        }
        return value
    }
    asset(expectedArchive, MAX_LINUX_ARCHIVE_BYTES)
    return AndroidReleaseAssets(
        version = target.version,
        tag = tag,
        releaseNotesUrl = target.releaseNotesUrl,
        apk = asset(expectedApk, MAX_APK_BYTES),
        checksumManifest = asset("SHA256SUMS", MAX_RELEASE_METADATA_BYTES),
        checksumSignature = asset("SHA256SUMS.sig", MAX_RELEASE_METADATA_BYTES),
        releaseCertificate = asset("foreman-release-cert.pem", MAX_RELEASE_METADATA_BYTES),
    )
}

internal fun normalizedSha256(value: String): String? =
    value.lowercase().takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class VerifiedReleaseManifest(
    val certificateFingerprint: String,
    val checksums: Map<String, String>,
)

internal fun verifySignedReleaseManifest(
    certificateBytes: ByteArray,
    signatureBytes: ByteArray,
    manifestBytes: ByteArray,
    expectedCertificateFingerprint: String,
    installedCertificateFingerprint: String,
    expectedApkName: String,
    expectedArchiveName: String,
): VerifiedReleaseManifest {
    val pinned = normalizedSha256(expectedCertificateFingerprint)
        ?: apkValidationFailure("invalidTrustAnchor", "This Foreman build has an invalid update trust anchor.")
    val installed = normalizedSha256(installedCertificateFingerprint)
        ?: apkValidationFailure("invalidInstalledSigner", "The installed Foreman signing identity is invalid.")
    val certificate = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateBytes.inputStream()) as X509Certificate
    } catch (_: Exception) {
        apkValidationFailure("invalidReleaseCertificate", "The release signing certificate is invalid.")
    }
    val certificateFingerprint = sha256(certificate.encoded)
    if (certificateFingerprint != pinned || certificateFingerprint != installed) {
        apkValidationFailure("releaseCertificateMismatch", "The release signing certificate does not match this Foreman installation.")
    }
    val validSignature = try {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(manifestBytes)
            verify(signatureBytes)
        }
    } catch (_: Exception) {
        false
    }
    if (!validSignature) {
        apkValidationFailure("manifestSignatureMismatch", "The signed release checksum data could not be verified.")
    }
    return VerifiedReleaseManifest(
        certificateFingerprint,
        parseChecksumManifest(manifestBytes, expectedApkName, expectedArchiveName),
    )
}

internal fun parseChecksumManifest(
    manifestBytes: ByteArray,
    expectedApkName: String,
    expectedArchiveName: String,
): Map<String, String> {
    if (manifestBytes.isEmpty() || manifestBytes.size > MAX_RELEASE_METADATA_BYTES) {
        apkValidationFailure("invalidChecksumManifest", "The release checksum data is empty or too large.")
    }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(manifestBytes))
            .toString()
    } catch (_: Exception) {
        apkValidationFailure("invalidChecksumManifest", "The release checksum data is not valid UTF-8.")
    }
    val pattern = Regex("^([0-9A-Fa-f]{64}) [ *]([^/\\\\]+)$")
    val lines = text.split('\n').let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it }
    if (lines.size != 2 || lines.any(String::isEmpty)) {
        apkValidationFailure("invalidChecksumManifest", "The release checksum data must contain exactly two entries.")
    }
    val checksums = linkedMapOf<String, String>()
    lines.forEach { line ->
        val match = pattern.matchEntire(line)
            ?: apkValidationFailure("invalidChecksumManifest", "The release checksum data is malformed.")
        val name = match.groupValues[2]
        if (checksums.put(name, match.groupValues[1].lowercase()) != null) {
            apkValidationFailure("duplicateChecksum", "The release checksum data contains a duplicate asset.")
        }
    }
    val expected = setOf(expectedApkName, expectedArchiveName)
    if (checksums.keys != expected) {
        apkValidationFailure("checksumAssetMismatch", "The signed checksum data does not contain the exact release payloads.")
    }
    return checksums
}

internal data class InstalledApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingCertificateSha256: String,
)

internal fun validateDownloadedApkIdentity(
    installed: InstalledApkIdentity,
    downloaded: InstalledApkIdentity,
    targetVersion: String,
    trustedCertificateSha256: String,
) {
    if (downloaded.packageName != FOREMAN_APPLICATION_ID || installed.packageName != FOREMAN_APPLICATION_ID) {
        apkValidationFailure("packageMismatch", "The downloaded package is not the Foreman Android app.")
    }
    val trusted = normalizedSha256(trustedCertificateSha256)
        ?: apkValidationFailure("invalidTrustAnchor", "This Foreman build has an invalid update trust anchor.")
    if (installed.signingCertificateSha256 != trusted || downloaded.signingCertificateSha256 != trusted) {
        apkValidationFailure("apkSignerMismatch", "The downloaded APK signing identity does not match installed Foreman.")
    }
    if (downloaded.versionName != targetVersion) {
        apkValidationFailure("apkVersionMismatch", "The downloaded APK version does not match the selected release.")
    }
    val installedSemVer = parseSemVer(installed.versionName)
        ?: apkValidationFailure("installedVersionInvalid", "The installed Foreman version cannot be updated automatically.")
    val targetSemVer = parseSemVer(targetVersion)
        ?: apkValidationFailure("targetVersionInvalid", "The selected Foreman release version is invalid.")
    if (targetSemVer == installedSemVer || downloaded.versionCode == installed.versionCode) {
        apkValidationFailure("sameVersion", "Foreman does not reinstall the same Android app version.")
    }
    if (targetSemVer < installedSemVer || downloaded.versionCode < installed.versionCode) {
        apkValidationFailure("downgrade", "Foreman does not install Android app downgrades.")
    }
    if (targetSemVer <= installedSemVer || downloaded.versionCode <= installed.versionCode) {
        apkValidationFailure("versionNotNewer", "The Android app update must have a newer version name and version code.")
    }
}

internal enum class ApkUpdatePhase {
    Idle,
    Discovering,
    Downloading,
    Verifying,
    Ready,
    ExplainingPermission,
    AwaitingPermission,
    AwaitingInstaller,
    Interrupted,
    Failed,
    Canceled,
    Completed,
}

internal data class ApkUpdateUiState(
    val phase: ApkUpdatePhase = ApkUpdatePhase.Idle,
    val installedVersionName: String,
    val installedVersionCode: Long,
    val targetVersion: String? = null,
    val progress: Int = 0,
    val message: String? = null,
    val verified: Boolean = false,
) {
    val busy: Boolean
        get() = phase in setOf(
            ApkUpdatePhase.Discovering,
            ApkUpdatePhase.Downloading,
            ApkUpdatePhase.Verifying,
            ApkUpdatePhase.AwaitingPermission,
            ApkUpdatePhase.AwaitingInstaller,
        )
}

internal fun installerPhaseAfterRequest(verified: Boolean, permissionRequired: Boolean): ApkUpdatePhase {
    if (!verified) return ApkUpdatePhase.Failed
    return if (permissionRequired) ApkUpdatePhase.ExplainingPermission else ApkUpdatePhase.AwaitingInstaller
}

internal fun installerPhaseAfterPermission(granted: Boolean): ApkUpdatePhase =
    if (granted) ApkUpdatePhase.AwaitingInstaller else ApkUpdatePhase.Ready

internal enum class ApkDownloadEvent { Start, MetadataSelected, PayloadDownloaded, Verified, Interrupted, Retry, Cancel, Reject }

internal fun nextApkDownloadPhase(current: ApkUpdatePhase, event: ApkDownloadEvent): ApkUpdatePhase? =
    when (event) {
        ApkDownloadEvent.Start -> if (current in setOf(ApkUpdatePhase.Idle, ApkUpdatePhase.Canceled, ApkUpdatePhase.Completed)) ApkUpdatePhase.Discovering else null
        ApkDownloadEvent.MetadataSelected -> if (current == ApkUpdatePhase.Discovering) ApkUpdatePhase.Downloading else null
        ApkDownloadEvent.PayloadDownloaded -> if (current == ApkUpdatePhase.Downloading) ApkUpdatePhase.Verifying else null
        ApkDownloadEvent.Verified -> if (current == ApkUpdatePhase.Verifying) ApkUpdatePhase.Ready else null
        ApkDownloadEvent.Interrupted -> if (current in setOf(ApkUpdatePhase.Discovering, ApkUpdatePhase.Downloading, ApkUpdatePhase.Verifying)) ApkUpdatePhase.Interrupted else null
        ApkDownloadEvent.Retry -> if (current in setOf(ApkUpdatePhase.Interrupted, ApkUpdatePhase.Failed, ApkUpdatePhase.Canceled)) ApkUpdatePhase.Discovering else null
        ApkDownloadEvent.Cancel -> if (current in setOf(ApkUpdatePhase.Discovering, ApkUpdatePhase.Downloading, ApkUpdatePhase.Verifying, ApkUpdatePhase.Interrupted)) ApkUpdatePhase.Canceled else null
        ApkDownloadEvent.Reject -> if (current !in setOf(ApkUpdatePhase.Completed, ApkUpdatePhase.Canceled)) ApkUpdatePhase.Failed else null
    }

internal class ApkUpdateConcurrencyGate {
    private val download = AtomicBoolean(false)
    private val installer = AtomicBoolean(false)

    fun claimDownload(): Boolean = download.compareAndSet(false, true)
    fun releaseDownload() = download.set(false)
    fun claimInstaller(): Boolean = installer.compareAndSet(false, true)
    fun releaseInstaller() = installer.set(false)
}

internal fun restoredApkUpdatePhase(
    persistedPhase: ApkUpdatePhase,
    verifiedApkExists: Boolean,
    installedVersionCode: Long,
    targetVersionCode: Long?,
): ApkUpdatePhase = when {
    targetVersionCode != null && installedVersionCode >= targetVersionCode -> ApkUpdatePhase.Completed
    persistedPhase in setOf(ApkUpdatePhase.Discovering, ApkUpdatePhase.Downloading, ApkUpdatePhase.Verifying) ->
        ApkUpdatePhase.Interrupted
    persistedPhase in setOf(ApkUpdatePhase.Ready, ApkUpdatePhase.ExplainingPermission, ApkUpdatePhase.AwaitingPermission) &&
        verifiedApkExists -> ApkUpdatePhase.Ready
    persistedPhase == ApkUpdatePhase.AwaitingInstaller && verifiedApkExists -> ApkUpdatePhase.AwaitingInstaller
    persistedPhase == ApkUpdatePhase.Completed -> ApkUpdatePhase.Completed
    persistedPhase == ApkUpdatePhase.Canceled -> ApkUpdatePhase.Canceled
    persistedPhase == ApkUpdatePhase.Failed -> ApkUpdatePhase.Failed
    else -> ApkUpdatePhase.Failed
}
