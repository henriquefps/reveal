@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.heal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The fine detail a model's fill lacks when it is upsampled (docs/adr/0024 "Integration with Remove"): a model fills
 * at the Remove crop's resolution (≤ 512 px, docs/adr/0023), so where the render is finer than the crop the fill is
 * smooth next to the photo's own grain. [sigma] (per channel, in the engine's pixel space) is the noise of the pixels
 * around the spot, measured robustly; [amount] the share of it the crop's box downscale averaged away
 * (`sqrt(1 - s²)`, s = crop pixels per render pixel). [add] puts deterministic noise at an image pixel: the same
 * key and pixel give the same value in the preview's zoom tiles and in an export at that resolution.
 */
class Grain(private val sigma: FloatArray, private val amount: Float, private val seed: Long) {
    fun add(x: Int, y: Int, out: FloatArray, o: Int) {
        for (c in 0..2) out[o + c] += gaussian(x, y, c) * sigma[c] * amount
    }

    /** About N(0, 1): the sum of four uniforms of a hash of the pixel, the channel and the seed. */
    internal fun gaussian(x: Int, y: Int, c: Int): Float {
        var h = seed xor (x.toLong() * X_MUL) xor (y.toLong() * Y_MUL) xor (c.toLong() * C_MUL)
        var sum = 0f
        repeat(4) {
            h = mix(h + GOLDEN)
            sum += (h ushr 40).toFloat() / (1L shl 24)
        }
        return (sum - 2f) * UNIT
    }

    companion object {
        private const val X_MUL = -0x61c8864680b583ebL
        private const val Y_MUL = 0x5851f42d4c957f2dL
        private const val C_MUL = 0x2545f4914f6cdd1dL
        private const val GOLDEN = -0x61c8864680b583ebL

        /** 1 / the standard deviation of the sum of four uniforms (sqrt(4 / 12)). */
        private val UNIT = sqrt(3f)

        /** The residual of white noise against the mean of its 4 neighbours is sqrt(1 + 1/4) times the noise. */
        private val RESIDUAL = sqrt(1.25f)

        /** Median absolute deviation to standard deviation, for a normal distribution. */
        private const val MAD = 1.4826f
        private const val MAX_SAMPLES = 20_000

        /** The width of the ring around the spot whose pixels give the noise level. */
        const val RING: Int = 12

        private fun mix(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /**
         * The grain for a fill sampled at [scale] crop pixels per render pixel, from [rgb] (interleaved, [w] x [h]) of
         * the area around the spot where [outside] (local x, y) is true; null when the render is not finer than the
         * crop or there is no ring to measure.
         */
        @Suppress("LongParameterList", "ReturnCount")
        fun of(rgb: FloatArray, w: Int, h: Int, outside: (Int, Int) -> Boolean, scale: Float, seed: Long): Grain? {
            if (scale >= 1f || w < 3 || h < 3) return null
            val samples = residuals(rgb, w, h, outside)
            val n = samples[0].size
            if (n < MIN_SAMPLES) return null
            val sigma = FloatArray(3) { c -> MAD * samples[c].also { it.sort() }[n / 2] / RESIDUAL }
            return Grain(sigma, sqrt(1f - scale * scale), seed)
        }

        /**
         * |pixel - mean of its 4 neighbours| per channel, at (up to [MAX_SAMPLES], evenly strided) pixels whose cross
         * lies entirely [outside] the spot.
         */
        @Suppress("NestedBlockDepth")
        private fun residuals(rgb: FloatArray, w: Int, h: Int, outside: (Int, Int) -> Boolean): Array<FloatArray> {
            fun ring(x: Int, y: Int) = outside(x, y) && outside(x - 1, y) && outside(x + 1, y) && cross(x, y, outside)
            val stride = max(1, (w - 2) * (h - 2) / MAX_SAMPLES)
            val out = Array(3) { ArrayList<Float>() }
            var i = 0
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    if (i++ % stride == 0 && out[0].size < MAX_SAMPLES && ring(x, y)) {
                        for (c in 0..2) out[c] += abs(rgb[(y * w + x) * 3 + c] - neighbours(rgb, w, x, y, c))
                    }
                }
            }
            return Array(3) { out[it].toFloatArray() }
        }

        private fun cross(x: Int, y: Int, outside: (Int, Int) -> Boolean) = outside(x, y - 1) && outside(x, y + 1)

        private fun neighbours(rgb: FloatArray, w: Int, x: Int, y: Int, c: Int): Float = (
            rgb[(y * w + x - 1) * 3 + c] + rgb[(y * w + x + 1) * 3 + c] +
                rgb[((y - 1) * w + x) * 3 + c] + rgb[((y + 1) * w + x) * 3 + c]
            ) / 4f

        private const val MIN_SAMPLES = 64
    }
}
