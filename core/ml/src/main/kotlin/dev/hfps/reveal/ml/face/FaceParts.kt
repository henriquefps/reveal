@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml.face

import dev.hfps.reveal.ml.Mask
import dev.hfps.reveal.ml.MaskKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Face-part masks drawn from face landmarks (docs/adr/0021 "M4/C"): the eyes' openings, the irises, the lips, the
 * mouth's inner opening (teeth) and the eyebrows, as smoothed polygons over MediaPipe's face mesh contours
 * (`face_mesh_connections`: the same index lists as MediaPipe's FACEMESH_LIPS / _LEFT_EYE / _RIGHT_EYE / _IRISES /
 * _EYEBROWS), rasterised with a soft edge ([SoftPolygon]) proportional to the face's size. Several faces unite (max).
 * "Right" and "left" are the subject's: the right eye is on the image's left.
 */
object FaceParts {
    const val RIGHT_EYE_OUTER = 33
    const val LEFT_EYE_OUTER = 263

    val LIPS_OUTER =
        intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    val LIPS_INNER =
        intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
    val RIGHT_EYE = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    val LEFT_EYE = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)
    val RIGHT_BROW = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)
    val LEFT_BROW = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)

    /** Iris centre, then its four contour points. */
    val RIGHT_IRIS = intArrayOf(468, 469, 470, 471, 472)
    val LEFT_IRIS = intArrayOf(473, 474, 475, 476, 477)

    /** The soft edge's width as a fraction of the distance between the outer eye corners (at least [MIN_FEATHER]). */
    const val FEATHER = 0.02f
    const val MIN_FEATHER = 0.75f

    private const val IRIS_POINTS = 24

    /** The [kind] mask (a face part) of [faces] on a [width] x [height] image. */
    fun mask(kind: MaskKind, faces: List<FaceLandmarks>, width: Int, height: Int): Mask {
        require(kind.isFacePart) { "$kind is not a face part" }
        val out = FloatArray(width * height)
        for (face in faces) draw(kind, face, out, width, height)
        return Mask(width, height, out)
    }

    private fun draw(kind: MaskKind, face: FaceLandmarks, out: FloatArray, w: Int, h: Int) {
        val feather = max(MIN_FEATHER, face.eyeSpan * FEATHER)
        fun poly(idx: IntArray): SoftPolygon {
            val (x, y) = SoftPolygon.smooth(
                FloatArray(idx.size) {
                    face.x[idx[it]]
                },
                FloatArray(idx.size) { face.y[idx[it]] },
            )
            return SoftPolygon(x, y, feather)
        }
        val shapes: List<SoftPolygon>
        val coverage: (Float, Float) -> Float
        when (kind) {
            MaskKind.EYES -> {
                shapes = listOf(poly(RIGHT_EYE), poly(LEFT_EYE))
                coverage = { x, y -> max(shapes[0].at(x, y), shapes[1].at(x, y)) }
            }

            MaskKind.IRISES -> {
                val eyes = listOf(poly(RIGHT_EYE), poly(LEFT_EYE))
                val irises = listOf(iris(face, RIGHT_IRIS, feather), iris(face, LEFT_IRIS, feather))
                shapes = irises
                coverage =
                    { x, y ->
                        max(min(irises[0].at(x, y), eyes[0].at(x, y)), min(irises[1].at(x, y), eyes[1].at(x, y)))
                    }
            }

            MaskKind.LIPS -> {
                val inner = poly(LIPS_INNER)
                shapes = listOf(poly(LIPS_OUTER))
                coverage = { x, y -> min(shapes[0].at(x, y), 1f - inner.at(x, y)) }
            }

            MaskKind.TEETH -> {
                shapes = listOf(poly(LIPS_INNER))
                coverage = { x, y -> shapes[0].at(x, y) }
            }

            MaskKind.EYEBROWS -> {
                shapes = listOf(poly(RIGHT_BROW), poly(LEFT_BROW))
                coverage = { x, y -> max(shapes[0].at(x, y), shapes[1].at(x, y)) }
            }

            else -> error("$kind is not a face part")
        }
        val box = SoftPolygon.box(shapes, w, h)
        for (y in box[1] until box[3]) {
            for (x in box[0] until box[2]) {
                val c = coverage(x + 0.5f, y + 0.5f)
                val i = y * w + x
                if (c > out[i]) out[i] = c
            }
        }
    }

    /** The iris as a circle through its four contour points (mean radius) around its centre. */
    private fun iris(face: FaceLandmarks, idx: IntArray, feather: Float): SoftPolygon {
        val cx = face.x[idx[0]]
        val cy = face.y[idx[0]]
        var r = 0f
        for (k in 1 until idx.size) {
            val dx = face.x[idx[k]] - cx
            val dy = face.y[idx[k]] - cy
            r += sqrt(dx * dx + dy * dy)
        }
        r /= (idx.size - 1)
        val xs = FloatArray(IRIS_POINTS) { cx + r * cos(2 * PI * it / IRIS_POINTS).toFloat() }
        val ys = FloatArray(IRIS_POINTS) { cy + r * sin(2 * PI * it / IRIS_POINTS).toFloat() }
        return SoftPolygon(xs, ys, feather)
    }
}
