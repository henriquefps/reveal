package dev.hfps.reveal.feature.editor.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a finger must rest on the photo before "hold = show original" kicks in. */
internal const val HOLD_ORIGINAL_DELAY_MS: Long = 350L

private const val DOUBLE_TAP_ZOOM_MS = 260
private const val MIN_FLING_SPEED = 80f

/**
 * Pinch zoom, one-finger pan when zoomed, fling, double-tap fit <-> 100 %, hold-to-show-original and tap for the
 * photo canvas. [onHold] is called with `true` when the hold starts and `false` when it ends; [onTap] on a single
 * tap (after the double-tap timeout).
 */
@Composable
internal fun Modifier.canvasGestures(
    state: ZoomState,
    scope: CoroutineScope,
    onTap: () -> Unit = {},
    onHold: (Boolean) -> Unit,
): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val currentHold by rememberUpdatedState(onHold)
    val currentTap by rememberUpdatedState(onTap)
    return this
        .pointerInput(state) { detectDoubleTap(state, scope, { currentTap() }) { currentHold(it) } }
        .pointerInput(state, density) { detectZoomPanFling(state, scope, slop, density) }
}

/** Animates the double-tap zoom (fit <-> 100 %) around the view pixel ([focusX], [focusY]). */
suspend fun ZoomState.animateDoubleTap(focusX: Float, focusY: Float) {
    Animatable(scale).animateTo(doubleTapTarget(), tween(DOUBLE_TAP_ZOOM_MS)) {
        scaleTo(value, focusX, focusY)
    }
}

private suspend fun PointerInputScope.detectDoubleTap(
    state: ZoomState,
    scope: CoroutineScope,
    onTap: () -> Unit,
    onHold: (Boolean) -> Unit,
) {
    var zoomJob: Job? = null
    detectTapGestures(
        onPress = {
            val hold = scope.launch {
                delay(HOLD_ORIGINAL_DELAY_MS)
                onHold(true)
            }
            try {
                tryAwaitRelease()
            } finally {
                hold.cancel()
                onHold(false)
            }
        },
        onTap = { onTap() },
        onDoubleTap = { tap ->
            zoomJob?.cancel()
            zoomJob = scope.launch { state.animateDoubleTap(tap.x, tap.y) }
        },
    )
}

@Suppress("CyclomaticComplexMethod")
private suspend fun PointerInputScope.detectZoomPanFling(
    state: ZoomState,
    scope: CoroutineScope,
    slop: Float,
    density: Density,
) {
    var fling: Job? = null
    val decay = splineBasedDecay<Float>(density)
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        fling?.cancel()
        val tracker = VelocityTracker()
        var pastSlop = false
        var zoom = 1f
        var pan = Offset.Zero
        var lastEvent: PointerEvent
        do {
            val event = awaitPointerEvent()
            lastEvent = event
            val consumedElsewhere = event.changes.any { it.isConsumed }
            if (!consumedElsewhere) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val zoomMotion = abs(1f - zoom) * event.calculateCentroidSize(useCurrent = false)
                    if (zoomMotion > slop || pan.getDistance() > slop) pastSlop = true
                } else {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        state.transform(zoomChange, centroid.x, centroid.y, panChange.x, panChange.y)
                    }
                    if (event.changes.size == 1) tracker.addPosition(event.changes[0].uptimeMillis, centroid)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!consumedElsewhere && event.changes.any { it.pressed })
        if (pastSlop && lastEvent.changes.size == 1 && lastEvent.changes[0].changedToUp()) {
            val velocity = tracker.calculateVelocity()
            if (abs(velocity.x) > MIN_FLING_SPEED || abs(velocity.y) > MIN_FLING_SPEED) {
                fling = scope.launch {
                    launch { flingAxis(velocity.x, decay) { state.panBy(it, 0f) } }
                    launch { flingAxis(velocity.y, decay) { state.panBy(0f, it) } }
                }
            }
        }
    }
}

private suspend fun flingAxis(
    velocity: Float,
    decay: androidx.compose.animation.core.DecayAnimationSpec<Float>,
    pan: (Float) -> Unit,
) {
    var last = 0f
    AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(decay) {
        pan(value - last)
        last = value
    }
}
