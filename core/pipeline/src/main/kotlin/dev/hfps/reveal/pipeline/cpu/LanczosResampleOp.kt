package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import kotlin.math.ceil
import kotlin.math.floor

/**
 * High-quality downscaling of the export (docs/adr/0009, "Resampling"): a separable **Lanczos-3** filter on linear
 * light in the working space, before the output transform (slot 13 of ADR 0002, ahead of the geometry operators that
 * will share the same resampling). Rows first, then columns.
 *
 * For output pixel `o` of an axis that maps [length] source pixels starting at [offset] onto `n` output pixels, with
 * `s = length / n >= 1`:
 * - centre `c = offset + (o + 0.5) s - 0.5` (source pixel centres at integers);
 * - taps `j = ceil(c - 3s) .. floor(c + 3s)`, each read at `clamp(j, 0, size - 1)` (the image edge repeats; a crop
 *   reads real pixels beyond its border);
 * - weight `L((j - c) / s)`, `L(x) = sinc(x) sinc(x / 3)` for `|x| < 3`, normalised by the sum of the weights.
 *
 * The kernel is stretched by `s`, so it low-passes at the output's Nyquist frequency (no aliasing). Weights in
 * float32 with `StrictMath.sin`; the GPU (`ResampleShaders`) mirrors it line for line. Lanczos rings by a few percent
 * at hard edges: on linear light the overshoot is clamped only by the output transform (as with every operator).
 */
object LanczosResampleOp {
    /** Lobes of the kernel. */
    const val LOBES: Int = 3

    /** Kernel `L(x)`. */
    @Suppress("ReturnCount")
    fun kernel(x: Float): Float {
        if (x == 0f) return 1f
        if (x <= -LOBES || x >= LOBES) return 0f
        val px = PI_F * x
        val num = LOBES * StrictMath.sin(px.toDouble()).toFloat() * StrictMath.sin((px / LOBES).toDouble()).toFloat()
        return num / (px * px)
    }

    /** Source centre of output pixel [o]. */
    fun centre(o: Int, offset: Int, scale: Float): Float = offset + (o + HALF) * scale - HALF

    /** First and last tap (unclamped) of output pixel [o]. */
    fun tapRange(o: Int, offset: Int, scale: Float): IntRange {
        val c = centre(o, offset, scale)
        val r = LOBES * scale
        return ceil(c - r).toInt()..floor(c + r).toInt()
    }

    /**
     * Source pixels an axis needs for output pixels [outRange], clamped to [0, size); one pixel wider on each side so
     * a GPU whose `ceil`/`floor` lands one tap further still reads inside the region (that tap's weight is 0).
     */
    fun sourceSpan(outRange: IntRange, offset: Int, scale: Float, size: Int): IntRange {
        val first = (tapRange(outRange.first, offset, scale).first - 1).coerceIn(0, size - 1)
        val last = (tapRange(outRange.last, offset, scale).last + 1).coerceIn(0, size - 1)
        return first..last
    }

    /**
     * Resamples the region ([x], [y], [w] x [h]) of an interleaved RGB image of [width] x [height] to [outWidth] x
     * [outHeight]; returns `outWidth*outHeight*3` floats.
     */
    @Suppress("LongParameterList")
    fun resample(
        rgb: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        outWidth: Int,
        outHeight: Int,
    ): FloatArray {
        val c = PipelineInput.CHANNELS
        val sx = w.toFloat() / outWidth
        val sy = h.toFloat() / outHeight
        // Rows: every source row, resampled horizontally.
        val rows = FloatArray(outWidth * height * c)
        for (row in 0 until height) {
            for (o in 0 until outWidth) {
                accumulate(o, x, sx, width) { j, wgt, acc ->
                    val i = (row * width + j) * c
                    acc[0] += wgt * rgb[i]
                    acc[1] += wgt * rgb[i + 1]
                    acc[2] += wgt * rgb[i + 2]
                }.copyInto(rows, (row * outWidth + o) * c)
            }
        }
        val out = FloatArray(outWidth * outHeight * c)
        for (o in 0 until outHeight) {
            for (col in 0 until outWidth) {
                accumulate(o, y, sy, height) { j, wgt, acc ->
                    val i = (j * outWidth + col) * c
                    acc[0] += wgt * rows[i]
                    acc[1] += wgt * rows[i + 1]
                    acc[2] += wgt * rows[i + 2]
                }.copyInto(out, (o * outWidth + col) * c)
            }
        }
        return out
    }

    /** Weighted sum of the taps of [o] (in tap order), divided by the sum of the weights. */
    private inline fun accumulate(
        o: Int,
        offset: Int,
        scale: Float,
        size: Int,
        add: (j: Int, weight: Float, acc: FloatArray) -> Unit,
    ): FloatArray {
        val acc = FloatArray(PipelineInput.CHANNELS)
        val cen = centre(o, offset, scale)
        var sum = 0f
        for (j in tapRange(o, offset, scale)) {
            val wgt = kernel((j - cen) / scale)
            sum += wgt
            add(j.coerceIn(0, size - 1), wgt, acc)
        }
        for (k in acc.indices) acc[k] /= sum
        return acc
    }

    private const val PI_F = Math.PI.toFloat()
    private const val HALF = 0.5f
}
