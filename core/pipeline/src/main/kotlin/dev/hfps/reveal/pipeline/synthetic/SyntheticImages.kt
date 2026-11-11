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

    /**
     * A hue sweep for the colour tools: OkLab hue 0..360° along x, rows from dark to light (L 0.35..0.85) at chroma
     * [chroma], plus a neutral bottom row band (chroma 0); as linear sRGB (Ottosson's sRGB OkLab matrices), so a few
     * strong colours fall slightly outside sRGB (negative components), which the operators must handle.
     */
    fun hueSweep(width: Int, height: Int, chroma: Double = 0.12): PipelineInput {
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        val greyRows = maxOf(1, height / 8)
        for (y in 0 until height) {
            val neutral = y >= height - greyRows
            val l = 0.35 + 0.5 * y / (height - 1).coerceAtLeast(1)
            for (x in 0 until width) {
                val h = 2 * PI * x / width
                val c = if (neutral) 0.0 else chroma
                val rgb = okLabToLinearSrgb(l, c * cos(h), c * sin(h))
                val i = (y * width + x) * PipelineInput.CHANNELS
                for (k in 0 until PipelineInput.CHANNELS) data[i + k] = rgb[k].toFloat()
            }
        }
        return PipelineInput.linearSrgb(width, height, data)
    }

    /** Ottosson's OkLab to linear sRGB. */
    private fun okLabToLinearSrgb(l: Double, a: Double, b: Double): DoubleArray {
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l1 * l1 * l1
        val m3 = m1 * m1 * m1
        val s3 = s1 * s1 * s1
        return doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
        )
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
     * A neutral scene-referred step edge (RAW-like, camera neutral under the as-shot light): [dark] on the left half,
     * [bright] on the right, each modulated by a fine texture of +-[texture] stops (a 2D sine of period 8 px), so
     * tests can measure halos next to the edge and whether local texture survives a tone change.
     */
    fun stepEdge(width: Int, height: Int, dark: Float, bright: Float, texture: Float = 0f): PipelineInput {
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val level = if (x < width / 2) dark else bright
                val t = sin(x * TEXTURE_FREQ) * sin(y * TEXTURE_FREQ)
                val v = level * StrictMath.pow(2.0, (texture * t).toDouble()).toFloat()
                for (c in 0 until PipelineInput.CHANNELS) {
                    data[(y * width + x) * PipelineInput.CHANNELS + c] = v / TEST_AS_SHOT[c]
                }
            }
        }
        return PipelineInput(InputKind.SCENE_REFERRED, width, height, data, TEST_CAMERA_TO_XYZ, TEST_AS_SHOT)
    }

    /**
     * A high-ISO-like test card for the Detail operator (docs/adr/0012), RAW-like with the invented camera: a mid-grey
     * left half and a brighter, slightly warm right half (a step edge to keep sharp), a thin bright line and a fine
     * texture of +-[texture] stops (a 2D sine of period 8 px) on the bottom quarter, plus seeded Gaussian noise of
     * [noise] (relative, per channel and independent: luminance and colour noise) on every pixel. Without noise the
     * clean image ([noise] = 0) is the truth NR is measured against.
     */
    @Suppress("LongParameterList")
    fun noisyCard(width: Int, height: Int, seed: Int, noise: Float = 0.12f, texture: Float = 0.3f): PipelineInput {
        val random = Random(seed)
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val right = x >= width / 2
                val base = if (right) floatArrayOf(0.42f, 0.36f, 0.30f) else floatArrayOf(0.12f, 0.12f, 0.12f)
                val line = if (y == height / 3 && x in width / 8 until width * 7 / 8) 3f else 1f
                val t = if (y >= height * 3 / 4) sin(x * TEXTURE_FREQ) * sin(y * TEXTURE_FREQ) else 0f
                val gain = line * StrictMath.pow(2.0, (texture * t).toDouble()).toFloat()
                for (c in 0 until PipelineInput.CHANNELS) {
                    val n = 1f + noise * gaussian(random)
                    data[(y * width + x) * PipelineInput.CHANNELS + c] = base[c] * gain * n / TEST_AS_SHOT[c]
                }
            }
        }
        return PipelineInput(InputKind.SCENE_REFERRED, width, height, data, TEST_CAMERA_TO_XYZ, TEST_AS_SHOT)
    }

    /**
     * A hazy landscape for Dehaze (docs/adr/0014), display-referred linear sRGB: a haze-free scene `J` (saturated
     * colour patches, shadows, a sky band) seen through the haze model `I = J t + A (1 - t)` with the atmospheric light
     * [HAZE_AIR] and a transmission `t = exp(-2.2 * depth)` that falls towards the top (far away). [truth] = true
     * returns `J`, what a perfect dehaze recovers.
     */
    @Suppress("NestedBlockDepth")
    fun hazy(width: Int, height: Int, truth: Boolean = false): PipelineInput {
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val fy = y.toFloat() / height
                val depth = 1f - fy
                val t = StrictMath.exp(-HAZE_DENSITY * depth.toDouble()).toFloat()
                val j = when {
                    fy < 0.15f -> floatArrayOf(0.25f, 0.4f, 0.75f)

                    else -> {
                        val patch = (x * 6 / width + (y * 4 / height)) % 4
                        val shade = if ((x / 12 + y / 12) % 2 == 0) 1f else 0.35f
                        when (patch) {
                            0 -> floatArrayOf(0.5f, 0.08f, 0.05f)
                            1 -> floatArrayOf(0.06f, 0.35f, 0.08f)
                            2 -> floatArrayOf(0.05f, 0.1f, 0.45f)
                            else -> floatArrayOf(0.3f, 0.25f, 0.04f)
                        }.map { it * shade }.toFloatArray()
                    }
                }
                val i = (y * width + x) * PipelineInput.CHANNELS
                for (c in 0 until PipelineInput.CHANNELS) {
                    data[i + c] = if (truth) j[c] else j[c] * t + HAZE_AIR[c] * (1f - t)
                }
            }
        }
        return PipelineInput.linearSrgb(width, height, data)
    }

    /** The atmospheric light of [hazy] (linear sRGB, slightly blue). */
    val HAZE_AIR: FloatArray = floatArrayOf(0.78f, 0.82f, 0.88f)
    private const val HAZE_DENSITY = 2.2

    /** A standard normal sample (Box-Muller), deterministic for a seeded [random]. */
    private fun gaussian(random: Random): Float {
        val u = random.nextDouble().coerceAtLeast(1e-12)
        val v = random.nextDouble()
        return (sqrt(-2.0 * StrictMath.log(u)) * StrictMath.cos(2.0 * PI * v)).toFloat()
    }

    /**
     * Clipped highlights as a decoded RAW would deliver them (saturation 1.0, every channel clipped at 1.0): a dim
     * blue-grey background with two Gaussian light blobs in raw camera units. The left blob's colour (raw
     * `HIGHLIGHT_COLOUR`) peaks at 2.4x the clip in green, so green and red clip in its core while blue survives;
     * the right blob peaks at 6x and clips every channel. [truth] = true returns the unclipped values (what a sensor
     * with more headroom would have recorded), for measuring the reconstruction error.
     */
    @Suppress("NestedBlockDepth")
    fun clippedHighlights(width: Int, height: Int, truth: Boolean = false): PipelineInput {
        val data = FloatArray(width * height * PipelineInput.CHANNELS)
        val sigma = 0.16f * height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * PipelineInput.CHANNELS
                val a = blob(x - width * 0.3f, y - height * 0.5f, sigma) * PARTIAL_PEAK
                val b = blob(x - width * 0.75f, y - height * 0.5f, sigma * 0.6f) * FULL_PEAK
                for (c in 0 until PipelineInput.CHANNELS) {
                    val v = HIGHLIGHT_BACKGROUND[c] + (a + b) * HIGHLIGHT_COLOUR[c]
                    data[i + c] = if (truth) v else minOf(v, 1f)
                }
            }
        }
        return PipelineInput(
            InputKind.SCENE_REFERRED,
            width,
            height,
            data,
            TEST_CAMERA_TO_XYZ,
            TEST_AS_SHOT,
            saturation = if (truth) null else 1f,
        )
    }

    private fun blob(dx: Float, dy: Float, sigma: Float): Float =
        StrictMath.exp((-(dx * dx + dy * dy) / (2f * sigma * sigma)).toDouble()).toFloat()

    /** Raw colour of the highlight blobs (green brightest, as on a real sensor), normalised to green = 1. */
    val HIGHLIGHT_COLOUR: FloatArray = floatArrayOf(0.55f, 1f, 0.3f)
    private val HIGHLIGHT_BACKGROUND = floatArrayOf(0.04f, 0.08f, 0.09f)
    private const val PARTIAL_PEAK = 2.4f
    private const val FULL_PEAK = 6f
    private const val TEXTURE_FREQ = 2f * PI.toFloat() / 8f

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
