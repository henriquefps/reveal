@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

// CPU reference of the develop operators. Each one works in place on interleaved RGB floats (`width*height*3`) and
// is mirrored line for line by a GLSL ES 3.20 function in `gl/Shaders.kt`; the parity test holds them together.
// Formulas are documented here once; the shader comments only point back to them.

/** Stage 1: raw white balance. `out = in * mul` per channel (G = 1), applied to un-balanced camera RGB. */
object RawWhiteBalanceOp {
    /** Multiplies every pixel by [mul] (R, G, B). */
    fun apply(rgb: FloatArray, mul: FloatArray) {
        var i = 0
        while (i < rgb.size) {
            rgb[i] *= mul[0]
            rgb[i + 1] *= mul[1]
            rgb[i + 2] *= mul[2]
            i += CHANNELS
        }
    }
}

/**
 * Stage 2: camera matrix. `out = M * in` with M ([WhiteBalanceSolution.balancedToWorking]) row-major, taking
 * balanced camera RGB to linear Rec.2020 (camera to XYZ, Bradford adaptation to D65, XYZ to Rec.2020).
 */
object CameraMatrixOp {
    /** Applies the row-major 3x3 [m] to every pixel. */
    fun apply(rgb: FloatArray, m: FloatArray) {
        var i = 0
        while (i < rgb.size) {
            val r = rgb[i]
            val g = rgb[i + 1]
            val b = rgb[i + 2]
            rgb[i] = m[0] * r + m[1] * g + m[2] * b
            rgb[i + 1] = m[3] * r + m[4] * g + m[5] * b
            rgb[i + 2] = m[6] * r + m[7] * g + m[8] * b
            i += CHANNELS
        }
    }
}

/** Stage 3: exposure. `out = in * 2^EV`, a linear gain on scene-linear data (no clipping, headroom is kept). */
object ExposureOp {
    /** Linear gain for [ev] stops. */
    fun gain(ev: Float): Float = StrictMath.pow(2.0, ev.toDouble()).toFloat()

    /** Multiplies every value by [gain]. */
    fun apply(rgb: FloatArray, gain: Float) {
        for (i in rgb.indices) rgb[i] *= gain
    }
}

/**
 * Stage 4: the "Standard" base tone curve, per channel. Identity up to the knee `k`, then a shoulder that is
 * continuous with slope 1 at the knee (C1; the second derivative also vanishes there) and approaches 1.0 without
 * ever reaching it, so highlights never hard-clip inside the curve:
 *
 * ```
 * f(x) = x                                          for x <= k
 * f(x) = k + (1 - k) * g(u),  u = (x - k)/(1 - k),  g(u) = u / (1 + u^8)^(1/8)      for x > k
 * ```
 *
 * `k = 0.8`, so the linear part keeps almost all of the range untouched (a neutral base look) and an input of 1.0
 * (the reference white after WB) maps to 0.983 (sRGB 252). `(1 + u^8)^(1/8)` is three square roots, which is exact
 * and cheap on both CPU and GPU. Negative inputs (out-of-gamut or noise) pass through unchanged and are handled by
 * the output transform; inputs above 10000 are clamped before the power so it cannot overflow float32.
 */
object BaseToneOp {
    /** Knee of the Standard curve, in linear working-space units. */
    const val KNEE: Float = 0.8f

    private const val MAX_INPUT = 1e4f

    /** The curve for one value. */
    fun curve(x: Float, knee: Float = KNEE): Float {
        if (x <= knee) return x
        val u = (min(x, MAX_INPUT) - knee) / (1f - knee)
        val u2 = u * u
        val u4 = u2 * u2
        val u8 = u4 * u4
        val denominator = sqrt(sqrt(sqrt(1f + u8)))
        return knee + (1f - knee) * (u / denominator)
    }

    /** Applies [curve] to every channel. */
    fun apply(rgb: FloatArray, knee: Float = KNEE) {
        for (i in rgb.indices) rgb[i] = curve(rgb[i], knee)
    }
}

/** Display encodings the output transform can target. */
enum class OutputSpace(internal val space: ColorSpaces.Rgb) {
    /** sRGB (IEC 61966-2-1). */
    SRGB(ColorSpaces.SRGB),

    /** Display P3 primaries with the sRGB transfer curve (what Android's wide-gamut displays take). */
    DISPLAY_P3(ColorSpaces.DISPLAY_P3),
}

/** Output transform settings; dithering is on by default with a fixed seed so renders are reproducible. */
data class OutputSettings(
    val space: OutputSpace = OutputSpace.SRGB,
    val dither: Boolean = true,
    val ditherSeed: Int = DEFAULT_DITHER_SEED,
) {
    companion object {
        /** Default dither seed. */
        const val DEFAULT_DITHER_SEED: Int = 0x5EED
    }
}

/** Deterministic dithering noise: a 32-bit integer hash of the pixel position and a seed, identical on CPU and GPU. */
object Dither {
    private const val GOLDEN_RATIO_32 = -1640531527 // 0x9E3779B9
    private const val MULT_A = 0x7feb352d
    private const val MULT_B = -2073254261 // 0x846ca68b
    private const val SHIFT_16 = 16
    private const val SHIFT_15 = 15
    private const val SHIFT_8 = 8
    private const val UNIT = 1f / 16777216f

    /** The "lowbias32" integer hash (Chris Wellons, 2018), 32-bit wrapping arithmetic. */
    fun hash(value: Int): Int {
        var v = value
        v = v xor (v ushr SHIFT_16)
        v *= MULT_A
        v = v xor (v ushr SHIFT_15)
        v *= MULT_B
        v = v xor (v ushr SHIFT_16)
        return v
    }

    /** Noise in [0, 1) for pixel ([x], [y]) and [seed]: `(hash(x + hash(y + seed * 0x9E3779B9)) >> 8) / 2^24`. */
    fun noise(x: Int, y: Int, seed: Int): Float {
        val h = hash(x + hash(y + seed * GOLDEN_RATIO_32))
        return (h ushr SHIFT_8) * UNIT
    }
}

/**
 * Last stage: output transform. Working space (linear Rec.2020) to the display space's linear RGB with one 3x3
 * (both D65, so no adaptation), clamp to [0, 1] (gamut and range clipping; a perceptual gamut mapping is later
 * work), the sRGB OETF `v <= 0.0031308 ? 12.92 v : 1.055 v^(1/2.4) - 0.055` (IEC 61966-2-1, also used by Display
 * P3), then quantisation to 8 bits with dithering: `q = floor(255 v + n)`, `n` in [0, 1) from [Dither], which
 * rounds each pixel up or down at random with probability equal to its fractional part (unbiased, no banding).
 */
object OutputTransformOp {
    private const val SRGB_LINEAR_CUT = 0.0031308f
    private const val SRGB_LINEAR_SLOPE = 12.92f
    private const val SRGB_A = 1.055f
    private const val SRGB_B = 0.055f
    private const val SRGB_GAMMA = 1.0 / 2.4
    private const val LEVELS = 255f
    private const val ALPHA_OPAQUE: Byte = -1

    /** Row-major matrix from the working space to [space]'s linear RGB. */
    fun workingToOutput(space: OutputSpace): Mat3 = space.space.fromXyz * ColorSpaces.REC2020.toXyz

    /** sRGB OETF of a value already clamped to [0, 1]. */
    fun oetf(v: Float): Float = if (v <=
        SRGB_LINEAR_CUT
    ) {
        SRGB_LINEAR_SLOPE * v
    } else {
        SRGB_A * StrictMath.pow(v.toDouble(), SRGB_GAMMA).toFloat() -
            SRGB_B
    }

    /**
     * Converts the working-space image [rgb] (`width*height*3`) to RGBA8 (`width*height*4` bytes, top row first),
     * dithering by output pixel position.
     */
    fun apply(rgb: FloatArray, width: Int, height: Int, settings: OutputSettings = OutputSettings()): ByteArray {
        val m = workingToOutput(settings.space).toFloatArray()
        val out = ByteArray(width * height * RGBA)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * CHANNELS
                val r = rgb[i]
                val g = rgb[i + 1]
                val b = rgb[i + 2]
                val n = if (settings.dither) Dither.noise(x, y, settings.ditherSeed) else HALF
                val o = (y * width + x) * RGBA
                for (c in 0 until CHANNELS) {
                    val lin = (m[c * CHANNELS] * r + m[c * CHANNELS + 1] * g + m[c * CHANNELS + 2] * b).coerceIn(0f, 1f)
                    out[o + c] = floor(oetf(lin) * LEVELS + n).toInt().coerceIn(0, 255).toByte()
                }
                out[o + CHANNELS] = ALPHA_OPAQUE
            }
        }
        return out
    }

    private const val HALF = 0.5f
    private const val RGBA = 4
}

private const val CHANNELS = 3
