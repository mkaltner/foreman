package net.kaltner.foreman

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAppUpdateViewModelTest {
    private lateinit var application: Application
    private lateinit var store: ApkUpdateStore
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        store = ApkUpdateStore(application)
        store.load()?.let { store.clearOperation(it.id) }
        store.root.deleteRecursively()
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.load()?.let { store.clearOperation(it.id) }
        store.root.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun cancellationWinsOverLateDownloadWorkAndCleansOperationFiles() = runTest(dispatcher) {
        val source = BlockingDownloadSource()
        val inspector = FakePackageInspector()
        val viewModel = viewModel(source, inspector)

        viewModel.start(targetRelease())
        runCurrent()
        source.started.await()
        val operation = requireNotNull(store.load())
        assertTrue(store.operationDirectory(operation.id).exists())

        viewModel.cancel()
        source.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(ApkUpdatePhase.Canceled, viewModel.state.value.phase)
        assertEquals(ApkUpdatePhase.Canceled, store.load()?.phase)
        assertFalse(store.operationDirectory(operation.id).exists())
    }

    @Test
    fun permissionAndInstallerResultsResumeOnceWithoutAnotherDownload() = runTest(dispatcher) {
        val inspector = FakePackageInspector()
        seedVerifiedOperation(ApkUpdatePhase.Ready)
        val viewModel = viewModel(FailingReleaseSource(), inspector)
        var installerRequests = 0
        val deliver: (InstallerRequest?) -> Unit = { if (it != null) installerRequests += 1 }

        viewModel.requestInstall(permissionRequired = true, deliver)
        assertEquals(ApkUpdatePhase.ExplainingPermission, viewModel.state.value.phase)
        assertEquals(0, installerRequests)
        viewModel.beginPermissionRequest()
        assertEquals(ApkUpdatePhase.AwaitingPermission, viewModel.state.value.phase)

        viewModel.permissionResult(granted = false, deliver)
        assertEquals(ApkUpdatePhase.Ready, viewModel.state.value.phase)
        assertEquals(0, installerRequests)

        viewModel.requestInstall(permissionRequired = true, deliver)
        viewModel.beginPermissionRequest()
        viewModel.permissionResult(granted = true, deliver)
        viewModel.permissionResult(granted = true, deliver)
        advanceUntilIdle()
        assertEquals(ApkUpdatePhase.AwaitingInstaller, viewModel.state.value.phase)
        assertEquals(1, installerRequests)

        viewModel.installerResult(accepted = false)
        assertEquals(ApkUpdatePhase.Ready, viewModel.state.value.phase)
        viewModel.requestInstall(permissionRequired = false, deliver)
        viewModel.requestInstall(permissionRequired = false, deliver)
        advanceUntilIdle()
        assertEquals(ApkUpdatePhase.AwaitingInstaller, viewModel.state.value.phase)
        assertEquals(2, installerRequests)
    }

    @Test
    fun verifiedUpdateAndInterruptedPartialRestoreAcrossViewModelRecreation() = runTest(dispatcher) {
        val verified = seedVerifiedOperation(ApkUpdatePhase.ExplainingPermission)
        val readyViewModel = viewModel(FailingReleaseSource(), FakePackageInspector())
        assertEquals(ApkUpdatePhase.Ready, readyViewModel.state.value.phase)
        assertTrue(store.apkFile(verified)?.exists() == true)

        store.clearOperation(verified.id)
        val interrupted = persistedOperation(ApkUpdatePhase.Downloading, assets = releaseAssets())
        store.save(interrupted)
        val partial = File(store.operationDirectory(interrupted.id), "${interrupted.assets!!.apk.name}.part")
        partial.parentFile?.mkdirs()
        partial.writeBytes(byteArrayOf(1, 2, 3))
        val source = BlockingDiscoverySource()
        val interruptedViewModel = viewModel(source, FakePackageInspector())
        assertEquals(ApkUpdatePhase.Interrupted, interruptedViewModel.state.value.phase)

        interruptedViewModel.retry()
        assertEquals(ApkUpdatePhase.Discovering, interruptedViewModel.state.value.phase)
        assertTrue(partial.exists())
        runCurrent()
        interruptedViewModel.cancel()
        source.release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ApkUpdatePhase.Canceled, interruptedViewModel.state.value.phase)
    }

    @Test
    fun acceptedInstallerReconcilesReplacementAndDeletesVerifiedApk() = runTest(dispatcher) {
        val operation = seedVerifiedOperation(ApkUpdatePhase.AwaitingInstaller)
        val inspector = FakePackageInspector()
        val viewModel = viewModel(FailingReleaseSource(), inspector)
        assertEquals(ApkUpdatePhase.AwaitingInstaller, viewModel.state.value.phase)

        viewModel.installerResult(accepted = true)
        assertEquals(ApkUpdatePhase.AwaitingInstaller, viewModel.state.value.phase)
        inspector.current = identity("1.1.0", 11)
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(ApkUpdatePhase.Completed, viewModel.state.value.phase)
        assertEquals(ApkUpdatePhase.Completed, store.load()?.phase)
        assertFalse(store.apkFile(operation)?.exists() == true)
    }

    private fun viewModel(source: ApkReleaseSource, inspector: ApkPackageInspector) =
        AndroidAppUpdateViewModel(application, source, inspector, store, dispatcher)

    private fun seedVerifiedOperation(phase: ApkUpdatePhase): PersistedApkUpdateOperation {
        val payload = "verified-test-apk".toByteArray()
        val assets = releaseAssets(apkSize = payload.size.toLong())
        val operation =
            persistedOperation(
                phase = phase,
                assets = assets,
                apkSha256 = sha256(payload),
                targetVersionCode = 11,
                installerClaimed = phase == ApkUpdatePhase.AwaitingInstaller,
            )
        val apk = File(store.operationDirectory(operation.id), assets.apk.name)
        apk.parentFile?.mkdirs()
        apk.writeBytes(payload)
        store.save(operation)
        return operation
    }

    private fun persistedOperation(
        phase: ApkUpdatePhase,
        assets: AndroidReleaseAssets? = null,
        apkSha256: String? = null,
        targetVersionCode: Long? = null,
        installerClaimed: Boolean = false,
    ) = PersistedApkUpdateOperation(
        id = "apk_1234567890abcdef12345678",
        target = targetRelease(),
        phase = phase,
        progress = if (apkSha256 == null) 30 else 100,
        assets = assets,
        apkSha256 = apkSha256,
        targetVersionCode = targetVersionCode,
        installerClaimed = installerClaimed,
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private fun targetRelease() =
        ForemanRelease(
            version = "1.1.0",
            tag = "v1.1.0",
            title = "Foreman 1.1.0",
            publishedAt = "2026-08-31T00:00:00Z",
            releaseNotesUrl = "https://github.com/mkaltner/foreman/releases/tag/v1.1.0",
            artifactAvailable = true,
        )

    private fun releaseAssets(apkSize: Long = 1_024L): AndroidReleaseAssets {
        fun asset(name: String, size: Long) =
            AndroidReleaseAsset(name, size, "$OFFICIAL_RELEASE_DOWNLOAD_PREFIX/v1.1.0/$name")
        return AndroidReleaseAssets(
            version = "1.1.0",
            tag = "v1.1.0",
            releaseNotesUrl = "https://github.com/mkaltner/foreman/releases/tag/v1.1.0",
            apk = asset("foreman-v1.1.0.apk", apkSize),
            checksumManifest = asset("SHA256SUMS", 100),
            checksumSignature = asset("SHA256SUMS.sig", 100),
            releaseCertificate = asset("foreman-release-cert.pem", 100),
        )
    }

    private fun identity(version: String, code: Long) =
        InstalledApkIdentity(
            FOREMAN_APPLICATION_ID,
            version,
            code,
            BuildConfig.FOREMAN_ANDROID_SIGNING_CERTIFICATE_SHA256,
        )

    private inner class FakePackageInspector : ApkPackageInspector {
        var current = identity("1.0.3", 10)
        override fun installed(): InstalledApkIdentity = current
        override fun archive(file: File): InstalledApkIdentity = identity("1.1.0", 11)
    }

    private inner class BlockingDownloadSource : ApkReleaseSource {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun discover(target: ForemanRelease): AndroidReleaseAssets = releaseAssets()

        override suspend fun download(
            asset: AndroidReleaseAsset,
            destination: File,
            progress: (Long, Long) -> Unit,
        ) {
            destination.parentFile?.mkdirs()
            File(destination.parentFile, "${destination.name}.part").writeBytes(byteArrayOf(1, 2, 3))
            started.complete(Unit)
            release.await()
        }
    }

    private inner class BlockingDiscoverySource : ApkReleaseSource {
        val release = CompletableDeferred<Unit>()
        override suspend fun discover(target: ForemanRelease): AndroidReleaseAssets {
            release.await()
            return releaseAssets()
        }

        override suspend fun download(
            asset: AndroidReleaseAsset,
            destination: File,
            progress: (Long, Long) -> Unit,
        ) = error("download should not begin")
    }

    private class FailingReleaseSource : ApkReleaseSource {
        override suspend fun discover(target: ForemanRelease): AndroidReleaseAssets =
            error("network should not be used")

        override suspend fun download(
            asset: AndroidReleaseAsset,
            destination: File,
            progress: (Long, Long) -> Unit,
        ) = error("network should not be used")
    }
}
