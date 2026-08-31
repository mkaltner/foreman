package net.kaltner.foreman

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CopyFeedbackButtonTest {
    @Test
    fun successfulCopyShowsCopiedForExactlyOneAndAHalfSeconds() = runTest {
        val controller = CopyFeedbackController(this)

        controller.copy { }
        assertEquals(CopyFeedbackState.Copying, controller.state)
        runCurrent()
        assertEquals(CopyFeedbackState.Copied, controller.state)

        advanceTimeBy(COPY_FEEDBACK_DURATION_MILLIS - 1)
        runCurrent()
        assertEquals(CopyFeedbackState.Copied, controller.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CopyFeedbackState.Idle, controller.state)
    }

    @Test
    fun failedCopyNeverShowsCopiedAndFailureIsBounded() = runTest {
        val controller = CopyFeedbackController(this)

        controller.copy { error("Clipboard unavailable") }
        runCurrent()
        assertEquals(CopyFeedbackState.Failed, controller.state)

        advanceTimeBy(COPY_FEEDBACK_DURATION_MILLIS)
        runCurrent()
        assertEquals(CopyFeedbackState.Idle, controller.state)
    }

    @Test
    fun repeatedSuccessRestartsTheFullTimer() = runTest {
        val controller = CopyFeedbackController(this)

        controller.copy { }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        controller.copy { }
        assertEquals(CopyFeedbackState.Copying, controller.state)
        runCurrent()
        assertEquals(CopyFeedbackState.Copied, controller.state)

        advanceTimeBy(COPY_FEEDBACK_DURATION_MILLIS - 1)
        runCurrent()
        assertEquals(CopyFeedbackState.Copied, controller.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CopyFeedbackState.Idle, controller.state)
    }

    @Test
    fun overlappingAttemptsAreSuppressed() = runTest {
        val releaseCopy = CompletableDeferred<Unit>()
        var attempts = 0
        val controller = CopyFeedbackController(this)

        controller.copy {
            attempts++
            releaseCopy.await()
        }
        runCurrent()
        controller.copy { attempts++ }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(CopyFeedbackState.Copying, controller.state)

        releaseCopy.complete(Unit)
        runCurrent()
        assertEquals(CopyFeedbackState.Copied, controller.state)
    }

    @Test
    fun disposalCancelsPendingAttemptsAndResetTimers() = runTest {
        val controller = CopyFeedbackController(this)
        controller.copy { }
        runCurrent()
        assertEquals(CopyFeedbackState.Copied, controller.state)

        controller.dispose()
        advanceUntilIdle()
        assertEquals(CopyFeedbackState.Copied, controller.state)

        val releaseCopy = CompletableDeferred<Unit>()
        val pendingController = CopyFeedbackController(this)
        pendingController.copy { releaseCopy.await() }
        runCurrent()
        pendingController.dispose()
        releaseCopy.complete(Unit)
        advanceUntilIdle()
        assertEquals(CopyFeedbackState.Copying, pendingController.state)
    }

    @Test
    fun accessibilityLabelsExposeEveryCopyState() {
        assertEquals("Copy", CopyFeedbackState.Idle.accessibilityLabel)
        assertEquals("Copying", CopyFeedbackState.Copying.accessibilityLabel)
        assertEquals("Copied", CopyFeedbackState.Copied.accessibilityLabel)
        assertEquals("Copy failed", CopyFeedbackState.Failed.accessibilityLabel)
    }

    @Test
    fun controllersKeepCopyFeedbackIndependent() = runTest {
        val first = CopyFeedbackController(this)
        val second = CopyFeedbackController(this)

        first.copy { }
        runCurrent()
        assertEquals(CopyFeedbackState.Copied, first.state)
        assertEquals(CopyFeedbackState.Idle, second.state)

        advanceTimeBy(500)
        runCurrent()
        second.copy { }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(CopyFeedbackState.Idle, first.state)
        assertEquals(CopyFeedbackState.Copied, second.state)
    }
}
