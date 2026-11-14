@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml.inpaint

import dev.hfps.reveal.ml.tensor.Resample
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How a crop of any size meets a model's fixed square input (docs/adr/0024 "Integration"), pure so the JVM tests and
 * the device agree:
 *
 * 1. The crop ([width] x [height], rgb interleaved in Remove's display encoding, docs/adr/0023) is resampled so its
 *    longer side is the model's [side] (explicit weights: bilinear when enlarging, area when shrinking;
 *    [Resample]); [contentWidth] x [contentHeight] at the top left.
 * 2. Values above 1 (highlights in the display encoding, which continues the sRGB curve) are divided by [highlight]
 *    = the largest known value (at least 1), so the model sees 0..1 without clipping a bright sky into a seam; the
 *    output is multiplied back. Negative values (out of gamut) are clamped to 0 for the model.
 * 3. The rest of the square is the content mirrored (reflection without repeating the edge), hole included: the
 *    mirrored hole is painted too and dropped, so the model never sees blacked-out pixels marked as known.
 * 4. The hole is grown, not shrunk, by the resampling (any coverage counts), so no pixel of the object survives.
 */
class InpaintLayout private constructor(
    val width: Int,
    val height: Int,
    val side: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val highlight: Float,
) {
    /**
     * The crop's rgb after the model: [output] (side x side x 3, NHWC 0..1) scaled back to [width] x [height] and
     * multiplied by [highlight] where [hole] > 0; elsewhere the crop's own [rgb] exactly.
     */
    fun restore(output: FloatArray, rgb: FloatArray, hole: FloatArray): FloatArray {
        require(output.size == side * side * 3) { "output must be side x side x 3" }
        require(rgb.size == width * height * 3 && hole.size == width * height) { "crop size mismatch" }
        val content = FloatArray(contentWidth * contentHeight * 3)
        for (y in 0 until contentHeight) {
            System.arraycopy(output, y * side * 3, content, y * contentWidth * 3, contentWidth * 3)
        }
        val back = Resample.interleaved(content, contentWidth, contentHeight, 3, width, height)
        val out = rgb.copyOf()
        for (i in 0 until width * height) {
            if (hole[i] > 0f) for (c in 0..2) out[i * 3 + c] = back[i * 3 + c] * highlight
        }
        return out
    }

    companion object {
        /** A hole value above this after resampling counts as hole (any coverage: the hole only grows). */
        const val HOLE_THRESHOLD: Float = 1e-4f

        /**
         * The layout of a [width] x [height] crop for a [side] x [side] model, and the model's input: NHWC
         * (rgb x (1 - hole), hole), side x side x 4.
         */
        fun prepare(
            width: Int,
            height: Int,
            rgb: FloatArray,
            hole: FloatArray,
            side: Int,
        ): Pair<InpaintLayout, FloatArray> {
            require(width > 0 && height > 0 && side > 1) { "empty crop" }
            require(rgb.size == width * height * 3 && hole.size == width * height) { "crop size mismatch" }
            val scale = side.toDouble() / max(width, height)
            val cw = (width * scale).roundToInt().coerceIn(1, side)
            val ch = (height * scale).roundToInt().coerceIn(1, side)
            var highlight = 1f
            for (i in 0 until width * height) {
                if (hole[i] <= 0f) for (c in 0..2) highlight = max(highlight, rgb[i * 3 + c])
            }
            val content = Resample.interleaved(rgb, width, height, 3, cw, ch)
            val contentHole = Resample.plane(hole, width, height, cw, ch)
            val input = FloatArray(side * side * 4)
            for (y in 0 until side) {
                val sy = mirror(y, ch)
                for (x in 0 until side) {
                    val sx = mirror(x, cw)
                    val s = sy * cw + sx
                    val h = if (contentHole[s] > HOLE_THRESHOLD) 1f else 0f
                    val o = (y * side + x) * 4
                    for (c in 0..2) input[o + c] = (content[s * 3 + c] / highlight).coerceIn(0f, 1f) * (1f - h)
                    input[o + 3] = h
                }
            }
            return InpaintLayout(width, height, side, cw, ch, highlight) to input
        }

        /** [i] reflected into 0 until [n] without repeating the edge pixel (… 2 1 0 1 2 … n-2 n-1 n-2 …). */
        internal fun mirror(i: Int, n: Int): Int {
            if (n == 1) return 0
            val period = 2 * (n - 1)
            val m = ((i % period) + period) % period
            return if (m < n) m else period - m
        }
    }
}
