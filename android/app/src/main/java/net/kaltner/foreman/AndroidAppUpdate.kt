package net.kaltner.foreman

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val UPDATE_PREFERENCES = "foreman_android_app_update"
private const val UPDATE_OPERATION_KEY = "operation"
private const val UPDATE_DIRECTORY = "android-updates"
private const val MAX_RELEASE_RESPONSE_BYTES = 512L * 1024L
private const val STALE_UPDATE_MILLIS = 7L * 24L * 60L * 60L * 1000L

@Serializable
private data class GithubReleaseAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GithubReleaseAsset>,
)

@Serializable
internal data class PersistedApkUpdateOperation(
    val id: String,
    val target: ForemanRelease,
    val phase: ApkUpdatePhase,
    val progress: Int = 0,
    val message: String? = null,
    val assets: AndroidReleaseAssets? = null,
    val apkSha256: String? = null,
    val targetVersionCode: Long? = null,
    val installerClaimed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

internal class ApkUpdateStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    val root: File = File(context.filesDir, UPDATE_DIRECTORY)

    fun load(): PersistedApkUpdateOperation? =
        preferences.getString(UPDATE_OPERATION_KEY, null)?.let { encoded ->
            runCatching { json.decodeFromString<PersistedApkUpdateOperation>(encoded) }.getOrNull()
        }

    fun save(operation: PersistedApkUpdateOperation) {
        preferences.edit().putString(UPDATE_OPERATION_KEY, json.encodeToString(operation)).commit()
    }

    fun operationDirectory(operationId: String): File {
        require(operationId.matches(Regex("^apk_[0-9a-f]{24}$")))
        return File(root, operationId)
    }

    fun apkFile(operation: PersistedApkUpdateOperation): File? =
        operation.assets?.apk?.name?.let { File(operationDirectory(operation.id), it) }

    fun clearFiles(operationId: String) {
        val directory = operationDirectory(operationId)
        if (directory.parentFile == root) directory.deleteRecursively()
    }

    fun clearOperation(operationId: String) {
        clearFiles(operationId)
        preferences.edit().remove(UPDATE_OPERATION_KEY).commit()
    }

    fun cleanupStale(keepOperationId: String?, now: Long = System.currentTimeMillis()) {
        if (!root.exists()) return
        root.listFiles()?.forEach { candidate ->
            if (
                candidate.isDirectory && candidate.name != keepOperationId &&
                now - candidate.lastModified() > STALE_UPDATE_MILLIS
            ) {
                candidate.deleteRecursively()
            }
        }
    }
}

internal interface ApkPackageInspector {
    fun installed(): InstalledApkIdentity
    fun archive(file: File): InstalledApkIdentity
}

internal class AndroidApkPackageInspector(private val context: Context) : ApkPackageInspector {
    override fun installed(): InstalledApkIdentity =
        identity(
            context.packageManager.getPackageInfo(
                context.packageName,
                signingFlags(),
            ),
        )

    override fun archive(file: File): InstalledApkIdentity {
        return try {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
                ?: throw ApkUpdateValidationException("invalidApk", "The downloaded file is not a valid Android package.")
            identity(info)
        } catch (error: ApkUpdateValidationException) {
            throw error
        } catch (_: Exception) {
            throw ApkUpdateValidationException("invalidApk", "The downloaded file is not a valid signed Android package.")
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): InstalledApkIdentity {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo
                    ?: throw ApkUpdateValidationException("unsignedApk", "The Android package is unsigned.")
                if (signing.hasMultipleSigners()) signing.apkContentsSigners?.toList().orEmpty()
                else signing.apkContentsSigners?.toList().orEmpty()
            } else {
                info.signatures?.toList().orEmpty()
            }
        if (signatures.size != 1) {
            throw ApkUpdateValidationException("ambiguousApkSigner", "The Android package does not have one trusted current signer.")
        }
        val versionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return InstalledApkIdentity(
            packageName = info.packageName.orEmpty(),
            versionName = info.versionName.orEmpty(),
            versionCode = versionCode,
            signingCertificateSha256 = sha256(signatures.single().toByteArray()),
        )
    }
}

internal interface ApkReleaseSource {
    suspend fun discover(target: ForemanRelease): AndroidReleaseAssets
    suspend fun download(asset: AndroidReleaseAsset, destination: File, progress: (Long, Long) -> Unit)
}

internal class GithubApkReleaseSource(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ApkReleaseSource {
    override suspend fun discover(target: ForemanRelease): AndroidReleaseAssets = withContext(Dispatchers.IO) {
        val endpoint = "$OFFICIAL_RELEASES_API${target.tag}"
        val response = readBounded(endpoint, MAX_RELEASE_RESPONSE_BYTES)
        val release = try {
            json.decodeFromString<GithubRelease>(response.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw ApkUpdateValidationException("malformedRelease", "GitHub returned malformed Foreman release metadata.")
        }
        selectAndroidReleaseAssets(
            target = target,
            tag = release.tag,
            draft = release.draft,
            prerelease = release.prerelease,
            assets = release.assets.map { AndroidReleaseAsset(it.name, it.size, it.downloadUrl) },
            publishedAt = release.publishedAt,
        )
    }

    override suspend fun download(
        asset: AndroidReleaseAsset,
        destination: File,
        progress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        destination.parentFile?.mkdirs()
        if (destination.isFile && destination.length() == asset.size) {
            progress(asset.size, asset.size)
            return@withContext
        }
        if (destination.exists()) destination.delete()
        val partial = File(destination.parentFile, "${destination.name}.part")
        val etagFile = File(destination.parentFile, "${destination.name}.etag")
        var offset = partial.takeIf(File::isFile)?.length() ?: 0L
        if (offset < 0L || offset > asset.size) {
            partial.delete()
            etagFile.delete()
            offset = 0L
        }
        val headers = mutableMapOf<String, String>()
        if (offset > 0L) {
            headers["Range"] = "bytes=$offset-"
            etagFile.takeIf(File::isFile)?.readText()?.takeIf { it.length <= 256 }
                ?.let { headers["If-Range"] = it }
        }
        val connection = openHttps(asset.downloadUrl, headers)
        try {
            val status = connection.responseCode
            if (status !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                throw IOException("The official release download returned HTTP $status.")
            }
            val append = status == HttpURLConnection.HTTP_PARTIAL && offset > 0L
            if (!append) {
                offset = 0L
                partial.delete()
            }
            connection.getHeaderField("ETag")
                ?.takeIf { it.length <= 256 && '\r' !in it && '\n' !in it }
                ?.let(etagFile::writeText)
            val responseLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (responseLength < 0L || offset + responseLength > asset.size || asset.size <= 0L) {
                throw ApkUpdateValidationException("downloadSizeMismatch", "The release download size is unexpected.")
            }
            FileOutputStream(partial, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = offset
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        if (received > asset.size) {
                            throw ApkUpdateValidationException("downloadTooLarge", "The release download exceeded its declared size.")
                        }
                        progress(received, asset.size)
                    }
                    output.fd.sync()
                }
            }
            if (partial.length() != asset.size) {
                throw IOException("The release download was interrupted before it completed.")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("The previous app update file could not be replaced safely.")
            }
            if (!partial.renameTo(destination)) {
                throw IOException("The completed app update file could not be committed safely.")
            }
            etagFile.delete()
            Unit
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun readBounded(url: String, maximum: Long): ByteArray {
        val connection = openHttps(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("The official release source returned HTTP ${connection.responseCode}.")
            }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (length > maximum) throw IOException("The official release response is too large.")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximum) throw IOException("The official release response is too large.")
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(
        initialUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpsURLConnection {
        var current = initialUrl
        repeat(6) {
            val uri = runCatching { URI(current) }.getOrNull()
                ?: throw IOException("The release source URL is invalid.")
            if (
                uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443) ||
                !allowedReleaseHost(uri.host.orEmpty())
            ) {
                throw IOException("The release source is not an authenticated Foreman HTTPS endpoint.")
            }
            val connection = URL(current).openConnection() as? HttpsURLConnection
                ?: throw IOException("The release source is not HTTPS.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Foreman-Android/${BuildConfig.VERSION_NAME}")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val status = connection.responseCode
            if (status !in setOf(301, 302, 303, 307, 308)) return connection
            val redirect = connection.getHeaderField("Location")
            connection.disconnect()
            current = redirect ?: throw IOException("The release source returned an invalid redirect.")
        }
        throw IOException("The release source returned too many redirects.")
    }

    private fun allowedReleaseHost(host: String): Boolean =
        host == "api.github.com" || host == "github.com" ||
            host == "release-assets.githubusercontent.com" ||
            host == "objects.githubusercontent.com"
}

internal data class InstallerRequest(val apk: File)

internal class AndroidAppUpdateViewModel(
    application: Application,
    private val source: ApkReleaseSource,
    private val inspector: ApkPackageInspector,
    private val store: ApkUpdateStore,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        GithubApkReleaseSource(),
        AndroidApkPackageInspector(application),
        ApkUpdateStore(application),
    )

    private val installed = inspector.installed()
    private val _state = MutableStateFlow(
        ApkUpdateUiState(
            installedVersionName = installed.versionName,
            installedVersionCode = installed.versionCode,
        ),
    )
    val state = _state.asStateFlow()
    @Volatile private var operation: PersistedApkUpdateOperation? = store.load()
    private var updateJob: Job? = null
    private val concurrency = ApkUpdateConcurrencyGate()

    init {
        restoreOperation()
        store.cleanupStale(operation?.id)
    }

    @Synchronized
    fun start(target: ForemanRelease) {
        if (updateJob?.isActive == true || _state.value.busy || !concurrency.claimDownload()) return
        if (!BuildConfig.FOREMAN_RELEASE_BUILD) {
            setTransientFailure("Development builds cannot install stable Android app updates automatically.")
            concurrency.releaseDownload()
            return
        }
        val installedVersion = parseSemVer(installed.versionName)
        val targetVersion = parseSemVer(target.version)
        if (installedVersion == null || targetVersion == null || targetVersion <= installedVersion) {
            setTransientFailure("The selected Android app release is not newer than the installed version.")
            concurrency.releaseDownload()
            return
        }
        operation?.let { store.clearFiles(it.id) }
        val now = System.currentTimeMillis()
        operation =
            PersistedApkUpdateOperation(
                id = "apk_${java.util.UUID.randomUUID().toString().replace("-", "").take(24)}",
                target = target,
                phase = ApkUpdatePhase.Discovering,
                createdAt = now,
                updatedAt = now,
            ).also(store::save)
        publish()
        launchUpdate()
    }

    @Synchronized
    fun retry() {
        val current = operation ?: return
        if (updateJob?.isActive == true || _state.value.busy || !concurrency.claimDownload()) return
        val resumeInterrupted = current.phase == ApkUpdatePhase.Interrupted && current.assets != null
        if (!resumeInterrupted) store.clearFiles(current.id)
        updateOperation(
            current.copy(
                phase = ApkUpdatePhase.Discovering,
                progress = 0,
                message = null,
                assets = if (resumeInterrupted) current.assets else null,
                apkSha256 = if (resumeInterrupted) current.apkSha256 else null,
                targetVersionCode = if (resumeInterrupted) current.targetVersionCode else null,
                installerClaimed = false,
            ),
        )
        launchUpdate()
    }

    @Synchronized
    fun cancel() {
        val canceledJob = updateJob
        canceledJob?.cancel()
        updateJob = null
        concurrency.releaseDownload()
        val current = operation ?: return
        store.clearFiles(current.id)
        updateOperation(
            current.copy(
                phase = ApkUpdatePhase.Canceled,
                progress = 0,
                message = "The Android app update was canceled. No installer was opened.",
                assets = null,
                apkSha256 = null,
                targetVersionCode = null,
                installerClaimed = false,
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            canceledJob?.join()
            if (operation?.id == current.id && operation?.phase == ApkUpdatePhase.Canceled) {
                store.clearFiles(current.id)
            }
        }
    }

    fun requestInstall(permissionRequired: Boolean, deliver: (InstallerRequest?) -> Unit) {
        val current = operation ?: run { deliver(null); return }
        val apk = store.apkFile(current)
        if (
            current.phase == ApkUpdatePhase.AwaitingInstaller && current.apkSha256 != null &&
            apk?.isFile == true
        ) {
            updateOperation(
                current.copy(
                    phase = ApkUpdatePhase.Ready,
                    installerClaimed = false,
                    message = "Reopening Android’s installer for the already verified APK.",
                ),
            )
            concurrency.releaseInstaller()
            prepareInstaller(deliver)
            return
        }
        if (current.phase != ApkUpdatePhase.Ready || current.apkSha256 == null || apk?.isFile != true) {
            deliver(null)
            return
        }
        if (permissionRequired) {
            updateOperation(
                current.copy(
                    phase = ApkUpdatePhase.ExplainingPermission,
                    message = "Android must allow Foreman to open this verified APK. You will still confirm installation in Android’s system installer.",
                ),
            )
            deliver(null)
        } else {
            prepareInstaller(deliver)
        }
    }

    fun beginPermissionRequest() {
        val current = operation ?: return
        if (current.phase != ApkUpdatePhase.ExplainingPermission) return
        updateOperation(
            current.copy(
                phase = ApkUpdatePhase.AwaitingPermission,
                message = "Allow installs from Foreman, then return to continue this verified update.",
            ),
        )
    }

    fun permissionResult(granted: Boolean, deliver: (InstallerRequest?) -> Unit) {
        val current = operation ?: run { deliver(null); return }
        if (current.phase !in setOf(ApkUpdatePhase.AwaitingPermission, ApkUpdatePhase.ExplainingPermission)) {
            deliver(null)
            return
        }
        if (!granted) {
            updateOperation(
                current.copy(
                    phase = ApkUpdatePhase.Ready,
                    installerClaimed = false,
                    message = "Install permission was not granted. The verified APK is retained; try again when ready.",
                ),
            )
            deliver(null)
        } else {
            updateOperation(current.copy(phase = ApkUpdatePhase.Ready, message = null))
            prepareInstaller(deliver)
        }
    }

    fun installerLaunchFailed() {
        concurrency.releaseInstaller()
        val current = operation ?: return
        if (current.phase == ApkUpdatePhase.AwaitingInstaller) {
            updateOperation(
                current.copy(
                    phase = ApkUpdatePhase.Ready,
                    installerClaimed = false,
                    message = "Android’s package installer could not be opened. The verified APK is ready to retry.",
                ),
            )
        }
    }

    fun installerResult(accepted: Boolean) {
        concurrency.releaseInstaller()
        val current = operation ?: return
        if (current.phase != ApkUpdatePhase.AwaitingInstaller) return
        if (accepted) {
            updateOperation(
                current.copy(message = "Android accepted the request. Foreman will reconcile after the app is replaced or reopened."),
            )
        } else {
            updateOperation(
                current.copy(
                    phase = ApkUpdatePhase.Ready,
                    installerClaimed = false,
                    message = "Installation was canceled. The verified APK is ready; no download is required to retry.",
                ),
            )
        }
    }

    fun onForeground() {
        val current = operation ?: return
        val targetCode = current.targetVersionCode ?: return
        viewModelScope.launch {
            val freshInstalled = withContext(Dispatchers.IO) {
                runCatching(inspector::installed).getOrNull()
            } ?: return@launch
            if (
                operation?.id == current.id && freshInstalled.versionCode >= targetCode &&
                freshInstalled.versionName == current.target.version
            ) {
                withContext(Dispatchers.IO) { store.clearFiles(current.id) }
                updateOperation(
                    current.copy(
                        phase = ApkUpdatePhase.Completed,
                        progress = 100,
                        message = "Foreman Android was updated successfully. Saved hosts and preferences were preserved.",
                        installerClaimed = false,
                    ),
                )
            }
        }
    }

    private fun launchUpdate() {
        updateJob = viewModelScope.launch {
            try {
                runUpdate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApkUpdateValidationException) {
                currentCoroutineContext().ensureActive()
                failOperation(error.message, invalidPayload = true)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                interruptOperation("The Android app download was interrupted or unavailable. Check the connection and retry safely.")
            } finally {
                concurrency.releaseDownload()
            }
        }
    }

    private suspend fun runUpdate() {
        var current = operation ?: return
        val assets = source.discover(current.target)
        current = current.copy(assets = assets, phase = ApkUpdatePhase.Downloading, progress = 2, message = null)
        updateOperation(current)
        val directory = store.operationDirectory(current.id).apply { mkdirs() }
        val manifestFile = File(directory, assets.checksumManifest.name)
        val signatureFile = File(directory, assets.checksumSignature.name)
        val certificateFile = File(directory, assets.releaseCertificate.name)
        source.download(assets.releaseCertificate, certificateFile) { _, _ -> }
        source.download(assets.checksumManifest, manifestFile) { _, _ -> }
        source.download(assets.checksumSignature, signatureFile) { _, _ -> }
        current = current.copy(phase = ApkUpdatePhase.Verifying, progress = 8)
        updateOperation(current)
        val installedIdentity = withContext(Dispatchers.IO) { inspector.installed() }
        val expectedApkName = assets.apk.name
        val expectedArchiveName = "foreman-linux-${assets.tag}.tar.gz"
        val manifest = withContext(Dispatchers.IO) {
            verifySignedReleaseManifest(
                certificateBytes = certificateFile.readBytes(),
                signatureBytes = signatureFile.readBytes(),
                manifestBytes = manifestFile.readBytes(),
                expectedCertificateFingerprint = BuildConfig.FOREMAN_ANDROID_SIGNING_CERTIFICATE_SHA256,
                installedCertificateFingerprint = installedIdentity.signingCertificateSha256,
                expectedApkName = expectedApkName,
                expectedArchiveName = expectedArchiveName,
            )
        }
        current = current.copy(phase = ApkUpdatePhase.Downloading, progress = 10)
        updateOperation(current)
        val apkFile = File(directory, assets.apk.name)
        source.download(assets.apk, apkFile) { received, total ->
            val progress = (10 + (received * 80L / total)).toInt().coerceIn(10, 90)
            updateDownloadProgress(current.id, progress)
        }
        current = operation ?: return
        updateOperation(current.copy(phase = ApkUpdatePhase.Verifying, progress = 92))
        val expectedChecksum = manifest.checksums[assets.apk.name]
            ?: throw ApkUpdateValidationException("missingApkChecksum", "The signed release data has no APK checksum.")
        val actualChecksum = withContext(Dispatchers.IO) { sha256(apkFile) }
        if (actualChecksum != expectedChecksum) {
            throw ApkUpdateValidationException("apkChecksumMismatch", "The downloaded APK failed signed checksum verification.")
        }
        val downloadedIdentity = withContext(Dispatchers.IO) { inspector.archive(apkFile) }
        validateDownloadedApkIdentity(
            installedIdentity,
            downloadedIdentity,
            assets.version,
            BuildConfig.FOREMAN_ANDROID_SIGNING_CERTIFICATE_SHA256,
        )
        updateOperation(
            (operation ?: return).copy(
                phase = ApkUpdatePhase.Ready,
                progress = 100,
                message = "Downloaded and verified Foreman ${assets.version}. Android will require explicit installation confirmation.",
                apkSha256 = actualChecksum,
                targetVersionCode = downloadedIdentity.versionCode,
                installerClaimed = false,
            ),
        )
    }

    private fun prepareInstaller(deliver: (InstallerRequest?) -> Unit) {
        if (!concurrency.claimInstaller()) {
            deliver(null)
            return
        }
        viewModelScope.launch {
            val current = operation
            val apk = current?.let(store::apkFile)
            val valid = withContext(Dispatchers.IO) {
                current != null && current.phase == ApkUpdatePhase.Ready &&
                    current.apkSha256 != null && apk?.isFile == true &&
                    runCatching {
                        sha256(apk) == current.apkSha256 &&
                            inspector.archive(apk).also { downloaded ->
                                validateDownloadedApkIdentity(
                                    inspector.installed(),
                                    downloaded,
                                    current.target.version,
                                    BuildConfig.FOREMAN_ANDROID_SIGNING_CERTIFICATE_SHA256,
                                )
                            }.versionCode == current.targetVersionCode
                    }.getOrDefault(false)
            }
            if (!valid || current == null || apk == null) {
                concurrency.releaseInstaller()
                failOperation("The verified APK changed or is no longer available. Download it again.", invalidPayload = true)
                deliver(null)
                return@launch
            }
            if (current.installerClaimed || operation?.phase != ApkUpdatePhase.Ready) {
                concurrency.releaseInstaller()
                deliver(null)
                return@launch
            }
            updateOperation(
                current.copy(
                    phase = ApkUpdatePhase.AwaitingInstaller,
                    installerClaimed = true,
                    message = "Waiting for Android’s system package installer. Foreman cannot confirm or bypass this step.",
                ),
            )
            deliver(InstallerRequest(apk))
        }
    }

    private fun restoreOperation() {
        val current = operation ?: return
        val apk = store.apkFile(current)
        val phase = restoredApkUpdatePhase(
            current.phase,
            apk?.isFile == true && current.apkSha256 != null,
            installed.versionCode,
            current.targetVersionCode,
        )
        val message = when (phase) {
            ApkUpdatePhase.Interrupted -> "The previous Android app download was interrupted. Retry to resume safely."
            ApkUpdatePhase.Ready -> "The verified Android app update was restored and is ready to install."
            ApkUpdatePhase.AwaitingInstaller -> "Android installation is still pending. Reopen the installer only if it is no longer visible."
            ApkUpdatePhase.Completed -> "Foreman Android was updated successfully. Saved hosts and preferences were preserved."
            ApkUpdatePhase.Failed -> "The saved Android app update could not be restored. Download it again."
            else -> current.message
        }
        if (phase == ApkUpdatePhase.Completed || phase == ApkUpdatePhase.Failed) store.clearFiles(current.id)
        updateOperation(current.copy(phase = phase, message = message, installerClaimed = phase == ApkUpdatePhase.AwaitingInstaller))
    }

    private fun interruptOperation(message: String) {
        val current = operation ?: return
        updateOperation(
            current.copy(
                phase = ApkUpdatePhase.Interrupted,
                message = message,
                installerClaimed = false,
            ),
        )
    }

    private fun failOperation(message: String, invalidPayload: Boolean) {
        val current = operation ?: return
        if (invalidPayload) store.clearFiles(current.id)
        updateOperation(
            current.copy(
                phase = ApkUpdatePhase.Failed,
                message = message,
                assets = if (invalidPayload) null else current.assets,
                apkSha256 = null,
                targetVersionCode = null,
                installerClaimed = false,
            ),
        )
    }

    private fun setTransientFailure(message: String) {
        _state.value =
            ApkUpdateUiState(
                phase = ApkUpdatePhase.Failed,
                installedVersionName = installed.versionName,
                installedVersionCode = installed.versionCode,
                message = message,
            )
    }

    @Synchronized
    private fun updateOperation(next: PersistedApkUpdateOperation) {
        val stamped = next.copy(updatedAt = System.currentTimeMillis(), progress = next.progress.coerceIn(0, 100))
        operation = stamped
        store.save(stamped)
        publish()
    }

    @Synchronized
    private fun updateDownloadProgress(operationId: String, progress: Int) {
        val current = operation ?: return
        if (
            current.id == operationId && current.phase == ApkUpdatePhase.Downloading &&
            progress > current.progress
        ) {
            updateOperation(current.copy(progress = progress))
        }
    }

    private fun publish() {
        val current = operation
        _state.value =
            ApkUpdateUiState(
                phase = current?.phase ?: ApkUpdatePhase.Idle,
                installedVersionName = installed.versionName,
                installedVersionCode = installed.versionCode,
                targetVersion = current?.target?.version,
                progress = current?.progress ?: 0,
                message = current?.message,
                verified = current?.apkSha256 != null,
            )
    }
}
