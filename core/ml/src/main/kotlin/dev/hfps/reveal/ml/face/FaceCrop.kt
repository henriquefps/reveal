@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml.face

import dev.hfps.reveal.ml.MlImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The rotated square region of an image a face landmark model sees (MediaPipe's face ROI, docs/adr/0021 "M4/C"):
 * centred on ([cx], [cy]), [side] pixels wide, turned by [angle] radians (image axes, y down: the direction of the
 * crop's x axis is (cos angle, sin angle)), so the eyes lie level in the crop. Image coordinates are continuous pixels
 * (pixel i covers i..i+1, its centre at i + 0.5).
 */
data class FaceCrop(val cx: Float, val cy: Float, val side: Float, val angle: Float) {
    init {
        require(side > 0f) { "crop must not be empty" }
    }

    private val c = cos(angle)
    private val s = sin(angle)

    /** The image x of the crop point ([u], [v]) of an [n] x [n] crop (continuous crop pixels). */
    fun imageX(u: Float, v: Float, n: Int): Float {
        val k = side / n
        return cx + c * (u - n / 2f) * k - s * (v - n / 2f) * k
    }

    /** The image y of the crop point ([u], [v]) of an [n] x [n] crop. */
    fun imageY(u: Float, v: Float, n: Int): Float {
        val k = side / n
        return cy + s * (u - n / 2f) * k + c * (v - n / 2f) * k
    }

    /**
     * The crop of [image] as an [n] x [n] interleaved RGB array: each crop pixel centre mapped into the image and
     * sampled bilinearly (explicit weights, edges clamped).
     */
    fun sample(image: MlImage, n: Int): FloatArray {
        val out = FloatArray(n * n * 3)
        val w = image.width
        val h = image.height
        val rgb = image.rgb
        for (v in 0 until n) {
            for (u in 0 until n) {
                val x = imageX(u + 0.5f, v + 0.5f, n) - 0.5f
                val y = imageY(u + 0.5f, v + 0.5f, n) - 0.5f
                val xc = x.coerceIn(0f, (w - 1).toFloat())
                val yc = y.coerceIn(0f, (h - 1).toFloat())
                val x0 = floor(xc).toInt()
                val y0 = floor(yc).toInt()
                val x1 = min(x0 + 1, w - 1)
                val y1 = min(y0 + 1, h - 1)
                val fx = xc - x0
                val fy = yc - y0
                val o = (v * n + u) * 3
                for (ch in 0 until 3) {
                    val a = rgb[(y0 * w + x0) * 3 + ch] * (1 - fx) + rgb[(y0 * w + x1) * 3 + ch] * fx
                    val b = rgb[(y1 * w + x0) * 3 + ch] * (1 - fx) + rgb[(y1 * w + x1) * 3 + ch] * fx
                    out[o + ch] = a * (1 - fy) + b * fy
                }
            }
        }
        return out
    }

    companion object {
        /** MediaPipe's ROI margin: the crop is 1.5 x the face box's long side. */
        const val SCALE = 1.5f

        /**
         * The crop of a detected face (BlazeFace, ADR 0010): the box ([left]..[bottom], image pixels) scaled by
         * [SCALE] on its long side, turned so the eye keypoints ([rightEyeX], [rightEyeY]: the subject's right eye,
         * on the image's left) and ([leftEyeX], [leftEyeY]) are level.
         */
        @Suppress("LongParameterList")
        fun fromDetection(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            rightEyeX: Float,
            rightEyeY: Float,
            leftEyeX: Float,
            leftEyeY: Float,
        ): FaceCrop = FaceCrop(
            cx = (left + right) / 2,
            cy = (top + bottom) / 2,
            side = max(right - left, bottom - top) * SCALE,
            angle = atan2(leftEyeY - rightEyeY, leftEyeX - rightEyeX),
        )

        /**
         * The crop around landmarks already found (MediaPipe's tracking ROI): turned by the line from point [from]
         * to point [to] (the outer eye corners), centred on the landmarks' box in that turned frame, [SCALE] x its
         * long side.
         */
        fun fromLandmarks(xs: FloatArray, ys: FloatArray, from: Int, to: Int): FaceCrop {
            val angle = atan2(ys[to] - ys[from], xs[to] - xs[from])
            val c = cos(angle)
            val s = sin(angle)
            var u0 = Float.MAX_VALUE
            var u1 = -Float.MAX_VALUE
            var v0 = Float.MAX_VALUE
            var v1 = -Float.MAX_VALUE
            for (i in xs.indices) {
                val u = c * xs[i] + s * ys[i]
                val v = -s * xs[i] + c * ys[i]
                u0 = min(u0, u)
                u1 = max(u1, u)
                v0 = min(v0, v)
                v1 = max(v1, v)
            }
            val uc = (u0 + u1) / 2
            val vc = (v0 + v1) / 2
            return FaceCrop(c * uc - s * vc, s * uc + c * vc, max(u1 - u0, v1 - v0) * SCALE, angle)
        }
    }
}
