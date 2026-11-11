package dev.hfps.reveal.feature.editor.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The gesture layer of the photo canvas, driven with real touch input but without any GL. */
@RunWith(AndroidJUnit4::class)
class CanvasGesturesTest {
    @get:Rule
    val rule = createComposeRule()

    private val zoom = ZoomState()
    private val holds = mutableListOf<Boolean>()

    private fun show() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            Box(
                Modifier
                    .size(400.dp, 600.dp)
                    .testTag(TAG)
                    .onSizeChanged { zoom.setViewSize(it.width, it.height) }
                    .canvasGestures(zoom, scope) { holds += it },
            )
        }
        rule.runOnIdle { zoom.setImageSize(IMAGE_W, IMAGE_H) }
    }

    @Test
    fun doubleTapTogglesBetweenFitAnd100Percent() {
        show()
        val fit = zoom.fitScale
        assertThat(zoom.scale).isWithin(1e-4f).of(fit)

        rule.onNodeWithTag(TAG).performTouchInput { doubleClick() }
        rule.waitUntil(TIMEOUT_MS) { zoom.scale > zoom.oneToOneScale - 1e-3f }
        assertThat(zoom.scale).isWithin(1e-3f).of(zoom.oneToOneScale)

        rule.onNodeWithTag(TAG).performTouchInput { doubleClick() }
        rule.waitUntil(TIMEOUT_MS) { zoom.scale < fit + 1e-3f }
        assertThat(zoom.scale).isWithin(1e-3f).of(fit)
    }

    @Test
    fun pinchOutZoomsIn() {
        show()
        val before = zoom.scale

        rule.onNodeWithTag(TAG).performTouchInput {
            val c = center
            pinch(c - Offset(40f, 0f), c + Offset(40f, 0f), c - Offset(200f, 0f), c + Offset(200f, 0f))
        }
        rule.waitForIdle()

        assertThat(zoom.scale).isGreaterThan(before * 2f)
    }

    @Test
    fun oneFingerPanMovesTheViewOnlyWhenZoomed() {
        show()
        val fitCenterX = zoom.centerX
        rule.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertThat(zoom.centerX).isEqualTo(fitCenterX)

        rule.runOnIdle { zoom.scaleTo(zoom.oneToOneScale, zoom.viewCenterX, zoom.viewCenterY) }
        val zoomedCenterX = zoom.centerX
        rule.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertThat(zoom.centerX).isGreaterThan(zoomedCenterX)
    }

    @Test
    fun holdShowsTheOriginalUntilRelease() {
        show()

        rule.onNodeWithTag(TAG).performTouchInput {
            down(center)
            advanceEventTime(HOLD_ORIGINAL_DELAY_MS + HOLD_MARGIN_MS)
        }
        rule.waitUntil(TIMEOUT_MS) { holds.contains(true) }
        rule.onNodeWithTag(TAG).performTouchInput { up() }
        rule.waitForIdle()

        assertThat(holds.last()).isFalse()
    }

    private companion object {
        const val TAG = "canvas"
        const val IMAGE_W = 2400
        const val IMAGE_H = 1600
        const val TIMEOUT_MS = 5_000L
        const val HOLD_MARGIN_MS = 100L
    }
}
