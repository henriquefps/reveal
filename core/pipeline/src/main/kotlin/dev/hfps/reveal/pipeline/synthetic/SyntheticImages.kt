@file:Suppress("MagicNumber", "ReturnCount")

package dev.hfps.reveal.pipeline.synthetic

import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.Mat3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Procedural test images for unit tests, parity tests and the debug canvas. No licences involved: everything is
 * generated. Values are linear.
 */
object SyntheticImages {
    /** A 3x3 camera matrix of a plausible but invented sensor (strong cross-talk), un-balanced, D65 calibrated. */
    val TEST_CAMERA_TO_XYZ: Mat3 = Mat3(
        0.6322, 0.2864, 0.0605,
        0.2284, 0.8350, -0.0634,
        0.0372, -0.2156, 1.1050,
    )

    /** As-shot multipliers of the invented camera: the ones that make D65 light neutral (G = 1). */
    val TEST_AS_SHOT: FloatArray = TEST_CAMERA_TO_XYZ.inverse().times(ColorSpaces.D65_XYZ).let { cam ->
        floatArrayOf((cam[1] / cam[0]).toFloat(), 1f, (cam[1] / cam[2]).toFloat())
    }

    /** A horizontal grey ramp from 0 to [max] (linear), [height] rows, as linear sRGB. */
    fun grayRamp(width: Int, height: Int, max: Float = 1f): PipelineInput {
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = max * x / (width - 1).coerceAtLeast(1)
                val i = (y * width + x) * PipelineInput.CHANNELS
                data[i] = v
                data[i + 1] = v
                data[i + 2] = v
            }
        }
        return PipelineInput.linearSrgb(width, height, data)
    }

    /**
     * Colour-checker-like chart: a 6x4 grid of patches (approximate ColorChecker Classic sRGB values, linearised),
     * [cell] pixels per patch, as linear sRGB.
     */
    fun colorChecker(cell: Int = 4): PipelineInput {
        val cols = CHECKER_COLS
        val rows = CHECKER_ROWS
        val width = cols * cell
        val height = rows * cell
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val patch = CHECKER_SRGB8[(y / cell) * cols + x / cell]
                val i = (y * width + x) * PipelineInput.CHANNELS
                for (c in 0 until PipelineInput.CHANNELS) data[i + c] = srgbToLinear(patch[c] / MAX8)
            }
        }
        return PipelineInput.linearSrgb(width, height, data)
    }

    /** A horizontal ramp from 0 to [max] as RAW-like scene-referred data (camera neutral under the as-shot light). */
    fun rawRamp(width: Int, height: Int, max: Float): PipelineInput {
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = max * x / (width - 1).coerceAtLeast(1)
                for (c in 0 until PipelineInput.CHANNELS) {
                    data[(y * width + x) * PipelineInput.CHANNELS + c] = v / TEST_AS_SHOT[c]
                }
            }
        }
        return PipelineInput(InputKind.SCENE_REFERRED, width, height, data, TEST_CAMERA_TO_XYZ, TEST_AS_SHOT)
    }

    /**
     * What a JPEG decoder hands over: [width] x 3 patches, every grey level 0..255 in sRGB 8-bit (so the expected
     * output is the source itself) followed by the colour-checker patches, linearised, as display-referred sRGB.
     * Returns the input and its 8-bit sRGB source values (RGBA8, opaque).
     */
    fun srgb8Chart(): Pair<PipelineInput, ByteArray> {
        val patches = mutableListOf<IntArray>()
        for (v in 0..255) patches += intArrayOf(v, v, v)
        for (v in 0..255 step 5) patches += intArrayOf(v, 255 - v, (v * 3) % 256)
        patches += CHECKER_SRGB8
        val data = FloatArray(patches.size * PipelineInput.CHANNELS)
        val source = ByteArray(patches.size * 4)
        patches.forEachIndexed { i, p ->
            for (c in 0 until PipelineInput.CHANNELS) {
                data[i * PipelineInput.CHANNELS + c] = srgbToLinear(p[c] / MAX8)
                source[i * 4 + c] = p[c].toByte()
            }
            source[i * 4 + 3] = -1
        }
        return PipelineInput.linearSrgb(patches.size, 1, data) to source
    }

    /** Values outside [0, 1]: negative (out-of-gamut), exactly white and far over white, as linear sRGB. */
    fun outOfRange(): PipelineInput {
        val colours = listOf(
            floatArrayOf(-0.20f, 0.50f, 0.10f),
            floatArrayOf(1.0f, 1.0f, 1.0f),
            floatArrayOf(4.0f, 2.0f, 1.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(-0.05f, -0.05f, -0.05f),
            floatArrayOf(16.0f, 16.0f, 16.0f),
            floatArrayOf(0.001f, 0.002f, 0.004f),
            floatArrayOf(0.18f, 0.18f, 0.18f),
        )
        val data = FloatArray(colours.size * PipelineInput.CHANNELS)
        colours.forEachIndexed { i, c -> c.copyInto(data, i * PipelineInput.CHANNELS) }
        return PipelineInput.linearSrgb(colours.size, 1, data)
    }

    /** Uniform random noise in [0, 1) per channel as a RAW-like input with the invented camera, seeded. */
    fun rawNoise(width: Int, height: Int, seed: Int): PipelineInput {
        val random = Random(seed)
        val data = FloatArray(width * height * PipelineInput.CHANNELS) { random.nextFloat() }
        return PipelineInput(InputKind.SCENE_REFERRED, width, height, data, TEST_CAMERA_TO_XYZ, TEST_AS_SHOT)
    }

    /**
     * A demo "scene" as RAW-like linear camera data: sky gradient, sun with over-white core, hills, a colourful
     * foreground of rings, a grey ramp and a colour-checker strip. Meant to look like a photo when developed.
     */
    fun scene(width: Int, height: Int): PipelineInput {
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        val sx = width * SUN_X
        val sy = height * SUN_Y
        for (y in 0 until height) {
            for (x in 0 until width) {
                val fx = x.toFloat() / width
                val fy = y.toFloat() / height
                val rgb = scenePixel(x, y, fx, fy, sx, sy, height)
                // Scene colours are linear sRGB; convert into the invented camera's un-balanced response.
                val cam = SCENE_TO_CAMERA.times(rgb[0].toDouble(), rgb[1].toDouble(), rgb[2].toDouble())
                val i = (y * width + x) * PipelineInput.CHANNELS
                for (c in 0 until PipelineInput.CHANNELS) data[i + c] = cam[c].toFloat()
            }
        }
        return PipelineInput(InputKind.SCENE_REFERRED, width, height, data, TEST_CAMERA_TO_XYZ, TEST_AS_SHOT)
    }

    private val SCENE_TO_CAMERA: Mat3 = TEST_CAMERA_TO_XYZ.inverse() * ColorSpaces.SRGB.toXyz

    @Suppress("LongParameterList", "MagicNumber")
    private fun scenePixel(x: Int, y: Int, fx: Float, fy: Float, sx: Float, sy: Float, h: Int): FloatArray {
        val horizon = 0.62f
        if (fy > 0.9f) {
            // Strip: grey ramp (left) and chart-like hue sweep (right).
            return if (fx < 0.5f) {
                val v = fx * 2f
                floatArrayOf(v, v, v)
            } else {
                hue((fx - 0.5f) * 2f, 0.9f, 0.5f)
            }
        }
        if (fy > horizon) {
            val hill = horizon + 0.05f * sin(fx * 9f) + 0.03f * sin(fx * 23f + 1f)
            if (fy > hill) {
                val shade = 0.06f + 0.10f * (fy - hill)
                val rings = 0.5f + 0.5f * sin(sqrt((fx - 0.3f) * (fx - 0.3f) + (fy - 0.8f) * (fy - 0.8f)) * 90f)
                val base = hue(fx * 1.5f + rings * 0.2f, 0.6f, 0.35f)
                return floatArrayOf(base[0] * shade * 3f, base[1] * shade * 3f, base[2] * shade * 3f)
            }
        }
        val d = sqrt((x - sx) * (x - sx) + (y - sy) * (y - sy)) / h
        val glow = 6f * (0.02f / (d + 0.02f)) * (0.02f / (d + 0.02f))
        val skyT = fy / horizon
        val r = 0.10f + 0.55f * skyT + glow
        val g = 0.22f + 0.35f * skyT + glow * 0.9f
        val b = 0.55f - 0.10f * skyT + glow * 0.7f
        return floatArrayOf(r, g, b)
    }

    private fun hue(t: Float, saturation: Float, value: Float): FloatArray {
        val a = 2f * PI.toFloat() * t
        val r = value * (1f - saturation + saturation * (0.5f + 0.5f * cos(a)))
        val g = value * (1f - saturation + saturation * (0.5f + 0.5f * cos(a - 2.0944f)))
        val b = value * (1f - saturation + saturation * (0.5f + 0.5f * cos(a + 2.0944f)))
        return floatArrayOf(abs(r), abs(g), abs(b))
    }

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else StrictMath.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private const val CHECKER_COLS = 6
    private const val CHECKER_ROWS = 4
    private const val MAX8 = 255f
    private const val SUN_X = 0.72f
    private const val SUN_Y = 0.28f

    /** Approximate ColorChecker Classic patches in sRGB 8-bit (X-Rite published sRGB values, rounded). */
    private val CHECKER_SRGB8: List<IntArray> = listOf(
        intArrayOf(115, 82, 68), intArrayOf(194, 150, 130), intArrayOf(98, 122, 157),
        intArrayOf(87, 108, 67), intArrayOf(133, 128, 177), intArrayOf(103, 189, 170),
        intArrayOf(214, 126, 44), intArrayOf(80, 91, 166), intArrayOf(193, 90, 99),
        intArrayOf(94, 60, 108), intArrayOf(157, 188, 64), intArrayOf(224, 163, 46),
        intArrayOf(56, 61, 150), intArrayOf(70, 148, 73), intArrayOf(175, 54, 60),
        intArrayOf(231, 199, 31), intArrayOf(187, 86, 149), intArrayOf(8, 133, 161),
        intArrayOf(243, 243, 242), intArrayOf(200, 200, 200), intArrayOf(160, 160, 160),
        intArrayOf(122, 122, 121), intArrayOf(85, 85, 85), intArrayOf(52, 52, 52),
    )
}
