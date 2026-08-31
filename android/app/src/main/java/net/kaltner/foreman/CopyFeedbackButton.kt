package net.kaltner.foreman

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal const val COPY_FEEDBACK_DURATION_MILLIS = 1_500L

internal enum class CopyFeedbackState(val accessibilityLabel: String) {
    Idle("Copy"),
    Copying("Copying"),
    Copied("Copied"),
    Failed("Copy failed"),
}

internal class CopyFeedbackController(
    private val scope: CoroutineScope,
    private val feedbackDurationMillis: Long = COPY_FEEDBACK_DURATION_MILLIS,
) {
    var state by mutableStateOf(CopyFeedbackState.Idle)
        private set

    private var attemptInFlight = false
    private var attemptJob: Job? = null
    private var resetJob: Job? = null
    private var disposed = false

    fun copy(action: suspend () -> Unit) {
        if (disposed || attemptInFlight) return
        attemptInFlight = true
        resetJob?.cancel()
        resetJob = null
        state = CopyFeedbackState.Copying
        attemptJob =
            scope.launch {
                val result =
                    try {
                        action()
                        coroutineContext.ensureActive()
                        CopyFeedbackState.Copied
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        CopyFeedbackState.Failed
                    } finally {
                        attemptInFlight = false
                    }
                if (!disposed) showTemporary(result)
            }
    }

    fun dispose() {
        disposed = true
        attemptJob?.cancel()
        resetJob?.cancel()
        attemptJob = null
        resetJob = null
        attemptInFlight = false
    }

    private fun showTemporary(result: CopyFeedbackState) {
        state = result
        resetJob =
            scope.launch {
                delay(feedbackDurationMillis)
                if (!disposed) state = CopyFeedbackState.Idle
                resetJob = null
            }
    }
}

@Composable
internal fun CopyFeedbackButton(
    onCopy: suspend () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { CopyFeedbackController(scope) }
    DisposableEffect(controller) {
        onDispose(controller::dispose)
    }

    val state = controller.state
    FilledTonalButton(
        onClick = { controller.copy(onCopy) },
        enabled = enabled,
        modifier =
            modifier.semantics {
                contentDescription = state.accessibilityLabel
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Box(
            modifier = Modifier.width(54.dp).height(24.dp).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                CopyFeedbackState.Idle -> Text("Copy")
                CopyFeedbackState.Copying -> CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                CopyFeedbackState.Copied -> Icon(Icons.Default.Check, contentDescription = null)
                CopyFeedbackState.Failed -> Icon(Icons.Default.Close, contentDescription = null)
            }
        }
    }
}
