package dev.hfps.reveal.ml.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cartoon face's landmarks for the tests: every contour of [FaceParts] is an ellipse at its feature's place, in
 * units of [scale] around ([cx], [cy]); the other points sit at the centre.
 */
object SyntheticFace {
    class Feature(val x: Float, val y: Float, val rx: Float, val ry: Float)

    val RIGHT_EYE = Feature(-1f, -0.5f, 0.5f, 0.2f)
    val LEFT_EYE = Feature(1f, -0.5f, 0.5f, 0.2f)
    val RIGHT_IRIS = Feature(-1f, -0.5f, 0.15f, 0.15f)
    val LEFT_IRIS = Feature(1f, -0.5f, 0.15f, 0.15f)
    val LIPS_OUTER = Feature(0f, 1f, 0.8f, 0.35f)
    val LIPS_INNER = Feature(0f, 1f, 0.6f, 0.12f)
    val RIGHT_BROW = Feature(-1f, -1f, 0.6f, 0.12f)
    val LEFT_BROW = Feature(1f, -1f, 0.6f, 0.12f)

    fun landmarks(cx: Float, cy: Float, scale: Float, presence: Float = 0.99f): FaceLandmarks {
        val xs = FloatArray(478) { cx }
        val ys = FloatArray(478) { cy }
        fun ring(idx: IntArray, f: Feature, start: Int = 0) {
            val n = idx.size - start
            for (k in 0 until n) {
                val a = 2 * PI * k / n
                xs[idx[start + k]] = cx + scale * (f.x + f.rx * cos(a).toFloat())
                ys[idx[start + k]] = cy + scale * (f.y + f.ry * sin(a).toFloat())
            }
        }
        ring(FaceParts.LIPS_OUTER, LIPS_OUTER)
        ring(FaceParts.LIPS_INNER, LIPS_INNER)
        ring(FaceParts.RIGHT_EYE, RIGHT_EYE)
        ring(FaceParts.LEFT_EYE, LEFT_EYE)
        ring(FaceParts.RIGHT_BROW, RIGHT_BROW)
        ring(FaceParts.LEFT_BROW, LEFT_BROW)
        for ((idx, f) in listOf(FaceParts.RIGHT_IRIS to RIGHT_IRIS, FaceParts.LEFT_IRIS to LEFT_IRIS)) {
            xs[idx[0]] = cx + scale * f.x
            ys[idx[0]] = cy + scale * f.y
            ring(idx, f, start = 1)
        }
        // The outer eye corners (the face's scale) are the first points of the eye rings: (-0.5, -0.5), (1.5, -0.5).
        return FaceLandmarks(xs, ys, presence)
    }
}
