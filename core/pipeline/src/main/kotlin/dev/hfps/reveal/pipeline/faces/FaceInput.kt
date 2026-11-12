@file:Suppress("MagicNumber", "NestedBlockDepth")

package dev.hfps.reveal.pipeline.faces

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.Luma
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min

/**
 * The image the face detector sees (docs/adr/0010, "Faces"): a function of the photo only, never of the recipe, so
 * faces are found once at open and Auto stays idempotent.
 *
 * The input is area-averaged to [FaceDetector.DETECT_SIDE] px on its long side, rendered by the CPU reference with
 * the default recipe (as-shot white balance, the Standard look; identity for a JPEG), brightened when it is dark (the
 * median luminance raised towards [TARGET_MEDIAN], at most [MAX_GAIN] times: a 2-stop under-exposed RAW still shows
 * its faces) and encoded as sRGB.
 */
object FaceInput {
    private const val TARGET_MEDIAN = 0.18
    private const val MAX_GAIN = 8.0
    private const val HISTOGRAM_BINS = 1024

    /** The detection image of [input] (the upright linear proxy, or any copy of the photo). */
    fun from(input: PipelineInput): DetectionImage {
        val small = downscale(input, FaceDetector.DETECT_SIDE)
        val rgb = CpuDevelop.renderLinear(small, Recipe())
        val gain = gain(rgb)
        val m = ColorSpaces.SRGB.fromXyz * ColorSpaces.REC2020.toXyz
        val k = DoubleArray(9) { m[it / 3, it % 3] }
        val out = FloatArray(rgb.size)
        // Rows in parallel; per pixel the same arithmetic as `Mat3.times`, without its allocation.
        IntStream.range(0, small.height).parallel().forEach { y ->
            for (i in y * small.width until (y + 1) * small.width) {
                val o = i * 3
                val r = rgb[o] * gain
                val g = rgb[o + 1] * gain
                val b = rgb[o + 2] * gain
                out[o] = encode((k[0] * r + k[1] * g + k[2] * b).coerceIn(0.0, 1.0)).toFloat()
                out[o + 1] = encode((k[3] * r + k[4] * g + k[5] * b).coerceIn(0.0, 1.0)).toFloat()
                out[o + 2] = encode((k[6] * r + k[7] * g + k[8] * b).coerceIn(0.0, 1.0)).toFloat()
            }
        }
        return DetectionImage(small.width, small.height, out)
    }

    /** Gain that lifts the median display luminance towards [TARGET_MEDIAN] (never darkens). */
    internal fun gain(rgb: FloatArray): Double {
        val bins = IntArray(HISTOGRAM_BINS)
        val n = rgb.size / 3
        for (i in 0 until n) {
            val y = Luma.of(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]).coerceIn(0f, 1f)
            bins[min((y * HISTOGRAM_BINS).toInt(), HISTOGRAM_BINS - 1)]++
        }
        var acc = 0
        var median = HISTOGRAM_BINS - 1
        for (b in bins.indices) {
            acc += bins[b]
            if (acc * 2 >= n) {
                median = b
                break
            }
        }
        val y = (median + 0.5) / HISTOGRAM_BINS
        return (TARGET_MEDIAN / y).coerceIn(1.0, MAX_GAIN)
    }

    internal fun encode(v: Double): Double =
        if (v <= 0.0031308) v * 12.92 else 1.055 * StrictMath.pow(v, 1 / 2.4) - 0.055

    /** Area average of [input] to at most [maxSide] px on its long side (whole-pixel blocks, so it stays cheap). */
    fun downscale(input: PipelineInput, maxSide: Int): PipelineInput {
        val long = max(input.width, input.height)
        if (long <= maxSide) return input
        val w = max(1, (input.width.toLong() * maxSide / long).toInt())
        val h = max(1, (input.height.toLong() * maxSide / long).toInt())
        val out = FloatArray(w * h * 3)
        val src = input.linearRgb
        IntStream.range(0, h).parallel().forEach { y ->
            val y0 = (y.toLong() * input.height / h).toInt()
            val y1 = max(y0 + 1, ((y + 1L) * input.height / h).toInt())
            for (x in 0 until w) {
                val x0 = (x.toLong() * input.width / w).toInt()
                val x1 = max(x0 + 1, ((x + 1L) * input.width / w).toInt())
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        val i = (yy * input.width + xx) * 3
                        r += src[i]
                        g += src[i + 1]
                        b += src[i + 2]
                    }
                }
                val k = 1.0 / ((y1 - y0) * (x1 - x0))
                val o = (y * w + x) * 3
                out[o] = (r * k).toFloat()
                out[o + 1] = (g * k).toFloat()
                out[o + 2] = (b * k).toFloat()
            }
        }
        return PipelineInput(
            input.kind,
            w,
            h,
            out,
            input.cameraToXyz,
            input.asShotMultipliers?.copyOf(),
            input.saturation,
            input.noise,
            input.lens,
        )
    }
}
