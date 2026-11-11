package dev.hfps.reveal.input

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PalmRejectionPolicyTest {
    private val policy = PalmRejectionPolicy()
    private val finger = TouchPointer(0, pen = false)
    private val pen = TouchPointer(1, pen = true)
    private val palm = TouchPointer(2, pen = false)

    private fun on(action: Int, index: Int, vararg pointers: TouchPointer) =
        policy.onEvent(TouchFrame(action, index, pointers.toList()))

    @Test
    fun fingersAloneArePassedThrough() {
        assertThat(on(ACTION_DOWN, 0, finger).single().passThrough).isTrue()
        assertThat(on(ACTION_POINTER_DOWN, 1, finger, palm).single().passThrough).isTrue()
        assertThat(on(ACTION_MOVE, 0, finger, palm).single().passThrough).isTrue()
        assertThat(on(ACTION_UP, 0, finger).single().passThrough).isTrue()
    }

    @Test
    fun aFingerDuringAPenStrokeIsIgnored() {
        assertThat(on(ACTION_DOWN, 0, pen)).containsExactly(TouchOutput(ACTION_DOWN, setOf(1)))
        // The palm lands, moves and lifts: the pen's stroke only ever sees its own pointer.
        assertThat(on(ACTION_POINTER_DOWN, 1, pen, palm)).containsExactly(TouchOutput(ACTION_MOVE, setOf(1)))
        assertThat(on(ACTION_MOVE, 0, pen, palm)).containsExactly(TouchOutput(ACTION_MOVE, setOf(1)))
        assertThat(on(ACTION_POINTER_UP, 1, pen, palm)).containsExactly(TouchOutput(ACTION_MOVE, setOf(1)))
        assertThat(on(ACTION_UP, 0, pen)).containsExactly(TouchOutput(ACTION_UP, setOf(1)))
        // Afterwards fingers work again.
        assertThat(on(ACTION_DOWN, 0, finger).single().passThrough).isTrue()
    }

    @Test
    fun aPenLandingCancelsTheFingersGesture() {
        on(ACTION_DOWN, 0, finger)
        val out = on(ACTION_POINTER_DOWN, 1, finger, pen)
        assertThat(out).containsExactly(
            TouchOutput(ACTION_CANCEL, emptySet()),
            TouchOutput(ACTION_DOWN, setOf(1)),
        ).inOrder()
        assertThat(on(ACTION_MOVE, 0, finger, pen)).containsExactly(TouchOutput(ACTION_MOVE, setOf(1)))
    }

    @Test
    fun fingersLeftWhenThePenLiftsStayIgnoredUntilTheyLift() {
        on(ACTION_DOWN, 0, pen)
        on(ACTION_POINTER_DOWN, 1, pen, palm)
        assertThat(on(ACTION_POINTER_UP, 0, pen, palm)).containsExactly(TouchOutput(ACTION_UP, setOf(1)))
        assertThat(on(ACTION_MOVE, 0, palm)).isEmpty()
        assertThat(on(ACTION_UP, 0, palm)).isEmpty()
        assertThat(on(ACTION_DOWN, 0, finger).single().passThrough).isTrue()
    }

    @Test
    fun thePenCanLandAgainOnAPalmThatStayed() {
        on(ACTION_DOWN, 0, pen)
        on(ACTION_POINTER_DOWN, 1, pen, palm)
        on(ACTION_POINTER_UP, 0, pen, palm)
        // The palm's gesture was never delivered: no cancel, just the pen's new stroke.
        assertThat(on(ACTION_POINTER_DOWN, 1, palm, pen)).containsExactly(TouchOutput(ACTION_DOWN, setOf(1)))
    }

    @Test
    fun aCancelEndsThePenStroke() {
        on(ACTION_DOWN, 0, pen)
        assertThat(on(ACTION_CANCEL, 0, pen)).containsExactly(TouchOutput(ACTION_CANCEL, setOf(1)))
        assertThat(on(ACTION_DOWN, 0, finger).single().passThrough).isTrue()
    }
}
