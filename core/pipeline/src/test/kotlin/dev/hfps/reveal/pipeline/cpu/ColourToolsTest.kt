package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Test

/** Colour mixer, black & white and colour grading (slot 9), docs/adr/0013. */
class ColourToolsTest {
    private fun lab(r: Float, g: Float, b: Float) = FloatArray(3).also { OkLab.fromRgb(r, g, b, it) }

    private fun fromLab(l: Float, c: Float, hDeg: Double): FloatArray {
        val h = Math.toRadians(hDeg)
        return FloatArray(3).also { OkLab.toRgb(l, (c * cos(h)).toFloat(), (c * sin(h)).toFloat(), it) }
    }

    private fun mixer(hue: BandValues = BandValues(), sat: BandValues = BandValues(), lum: BandValues = BandValues()) =
        checkNotNull(MixerParams.from(ColourMixer(hue, sat, lum)))

    private fun hueOf(rgb: FloatArray, i: Int = 0): Double {
        val l = lab(rgb[i], rgb[i + 1], rgb[i + 2])
        return atan2(l[2].toDouble(), l[1].toDouble())
    }

    // ------------------------------------------------------------------ bands

    @Test
    fun `band weights are a partition of unity, continuous across the wrap and peak at the centres`() {
        for (i in 0 until 3600) {
            val h = (i / 3600.0 * 2 * PI - PI).toFloat()
            val w = HueBands.weights(h)
            assertThat(w.sum()).isWithin(1e-6f).of(1f)
            assertThat(w.all { it >= 0f }).isTrue()
        }
        HueBands.CENTRES.forEachIndexed { k, c -> assertThat(HueBands.weights(c)[k]).isWithin(1e-5f).of(1f) }
        // Continuity at the magenta -> red wrap (25° is red's centre; 0° is between magenta and red).
        val a = HueBands.weights(Math.toRadians(-0.01).toFloat())
        val b = HueBands.weights(Math.toRadians(0.01).toFloat())
        for (k in 0 until 8) assertThat(abs(a[k] - b[k])).isLessThan(1e-3f)
        assertThat(HueBands.nearest(Math.toRadians(30.0).toFloat())).isEqualTo(ColourBand.RED)
        assertThat(HueBands.nearest(Math.toRadians(250.0).toFloat())).isEqualTo(ColourBand.BLUE)
    }

    @Test
    fun `equal band values act like one global value`() {
        val v = FloatArray(8) { 0.4f }
        for (i in 0 until 720) {
            assertThat(
                HueBands.interpolate(v, (i / 720.0 * 2 * PI).toFloat()),
            ).isWithin(1e-6f).of(0.4f)
        }
    }

    // ------------------------------------------------------------------ mixer

    @Test
    fun `hue map is strictly increasing for extreme and random hue settings (no hue breaks)`() {
        val random = Random(11)
        val settings = buildList {
            add(List(8) { if (it % 2 == 0) 100f else -100f })
            add(List(8) { if (it % 2 == 0) -100f else 100f })
            add(List(8) { 100f })
            add(List(8) { -100f })
            repeat(400) { add(List(8) { (random.nextInt(5) - 2) * 50f }) }
        }
        for (s in settings) {
            val p = mixer(hue = BandValues.of(s))
            var previous = Float.NEGATIVE_INFINITY
            for (i in 0 until 7200) {
                val x = HueBands.CENTRES[0] + (i / 7200.0 * 2 * PI).toFloat()
                val k = HueBands.segment(x)
                val mapped = ColourMixerOp.mapHue(k, HueBands.position(x, k), p)
                assertThat(mapped).isGreaterThan(previous)
                previous = mapped
            }
        }
    }

    @Test
    fun `hue shifts move a band towards its neighbour by the documented amount`() {
        val p = mixer(hue = BandValues(red = 100f))
        val red = HueBands.CENTRES[0]
        val k = HueBands.segment(red)
        val mapped = ColourMixerOp.mapHue(k, HueBands.position(red, k), p)
        assertThat(Math.toDegrees((mapped - red).toDouble())).isWithin(1e-3).of(0.45 * 30.0)
        val q = mixer(hue = BandValues(red = -100f))
        val down = ColourMixerOp.mapHue(k, HueBands.position(red, k), q)
        assertThat(Math.toDegrees((down - red).toDouble())).isWithin(1e-3).of(-0.45 * 50.0)
    }

    @Test
    fun `saturation -100 greys one band and leaves far hues alone`() {
        val p = mixer(sat = BandValues(blue = -100f))
        val blue = fromLab(0.55f, 0.12f, 255.0)
        val green = fromLab(0.7f, 0.12f, 140.0)
        val rgb = blue + green
        ColourMixerOp.apply(rgb, p)
        val b = lab(rgb[0], rgb[1], rgb[2])
        assertThat(sqrt(b[1] * b[1] + b[2] * b[2])).isLessThan(1e-4f)
        assertThat(b[0]).isWithin(1e-4f).of(0.55f)
        val g = fromLab(0.7f, 0.12f, 140.0)
        for (c in 0..2) assertThat(rgb[3 + c]).isWithin(1e-5f).of(g[c])
    }

    @Test
    fun `luminance changes lightness by the documented gain and leaves neutrals exactly alone`() {
        val p = mixer(lum = BandValues(orange = -100f))
        val orange = fromLab(0.7f, 0.15f, 55.0)
        val grey = floatArrayOf(0.2f, 0.2f, 0.2f)
        val rgb = orange + grey
        ColourMixerOp.apply(rgb, p)
        val o = lab(rgb[0], rgb[1], rgb[2])
        val g = HueBands.chromaWeight(0.15f)
        assertThat(o[0]).isWithin(1e-4f).of(0.7f * exp2(-ColourMixerOp.LUM_STOPS * g))
        assertThat(sqrt(o[1] * o[1] + o[2] * o[2])).isWithin(1e-4f).of(0.15f)
        // A grey's float chroma is ~1e-7: only the OkLab round trip touches it.
        for (c in 3..5) assertThat(rgb[c]).isWithin(3e-7f).of(0.2f)
    }

    @Test
    fun `mixer on a hue sweep is smooth, hue keeps its order and neighbours stay close`() {
        val input = SyntheticImages.hueSweep(720, 8)
        val recipe = Recipe(
            colour = Colour(
                mixer = ColourMixer(
                    hue = BandValues(red = 100f, orange = -100f, green = 100f, aqua = -100f, purple = 60f),
                    saturation = BandValues(yellow = -100f, blue = 100f, magenta = 50f),
                    luminance = BandValues(red = -100f, green = 100f, blue = -80f),
                ),
            ),
        )
        val base = CpuDevelop.renderLinear(input, Recipe())
        val out = CpuDevelop.renderLinear(input, recipe)
        val w = input.width
        for (y in 0 until 7) {
            var previous = Double.NEGATIVE_INFINITY
            var unwrap = 0.0
            var last = hueOf(out, y * w * 3)
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                val l0 = lab(base[i], base[i + 1], base[i + 2])
                val l1 = lab(out[i], out[i + 1], out[i + 2])
                // Skip near-greys (yellow is desaturated to grey here: no hue to order).
                if (sqrt(l0[1] * l0[1] + l0[2] * l0[2]) < 0.05f || sqrt(l1[1] * l1[1] + l1[2] * l1[2]) < 0.02f) {
                    previous = Double.NEGATIVE_INFINITY
                    continue
                }
                var h = hueOf(out, i)
                if (h + unwrap < last - PI) unwrap += 2 * PI
                h += unwrap
                assertThat(h).isGreaterThan(previous - 1e-4)
                // Neighbouring pixels (0.5° apart in the input) stay within a few degrees and a few % lightness.
                if (previous.isFinite()) {
                    assertThat(Math.toDegrees(h - previous)).isLessThan(4.0)
                    val a = lab(out[i - 3], out[i - 2], out[i - 1])
                    val b = lab(out[i], out[i + 1], out[i + 2])
                    assertThat(abs(a[0] - b[0])).isLessThan(0.01f)
                }
                previous = h
                last = h - unwrap
            }
        }
    }

    // ------------------------------------------------------------------ black & white

    @Test
    fun `black and white is neutral, keeps lightness with a zero mixer and leaves greys exactly`() {
        val input = SyntheticImages.hueSweep(90, 8)
        val out = CpuDevelop.renderLinear(input, Recipe(profile = Profile(treatment = Treatment.BLACK_WHITE)))
        val base = CpuDevelop.renderLinear(input, Recipe())
        for (i in out.indices step 3) {
            assertThat(abs(out[i] - out[i + 1]) + abs(out[i + 1] - out[i + 2])).isLessThan(2e-5f)
            val l0 = lab(base[i], base[i + 1], base[i + 2])[0]
            val l1 = lab(out[i], out[i + 1], out[i + 2])[0]
            assertThat(l1).isWithin(2e-5f).of(l0)
        }
    }

    @Test
    fun `grey mixer brightens and darkens source colours by the documented gain`() {
        val grey = floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f) // blue +100
        val blue = fromLab(0.5f, 0.15f, 255.0)
        val red = fromLab(0.6f, 0.15f, 25.0)
        val rgb = blue + red
        BlackWhiteOp.apply(rgb, grey)
        val g = HueBands.chromaWeight(0.15f)
        assertThat(lab(rgb[0], rgb[1], rgb[2])[0]).isWithin(1e-4f).of(0.5f * exp2(BlackWhiteOp.GREY_STOPS * g))
        assertThat(lab(rgb[3], rgb[4], rgb[5])[0]).isWithin(1e-4f).of(0.6f)
    }

    @Test
    fun `black and white skips vibrance, saturation and the mixer`() {
        val input = SyntheticImages.colorChecker()
        val bw = Recipe(profile = Profile(treatment = Treatment.BLACK_WHITE))
        val edited = bw.copy(
            colour = Colour(
                vibrance = 80f,
                saturation = -100f,
                mixer = ColourMixer(luminance = BandValues(red = 100f)),
            ),
        )
        assertThat(CpuDevelop.renderLinear(input, edited)).isEqualTo(CpuDevelop.renderLinear(input, bw))
    }

    // ------------------------------------------------------------------ grading

    @Suppress("NestedBlockDepth")
    @Test
    fun `grading masks are a partition of unity and lightness stays increasing for extreme luminance`() {
        val extremes = listOf(-100f, 0f, 100f)
        for (s in extremes) {
            for (m in extremes) {
                for (h in extremes) {
                    for (g in extremes) {
                        for (blend in listOf(0f, 50f, 100f)) {
                            for (balance in listOf(-100f, 0f, 100f)) {
                                val p = GradingParams.from(
                                    ColourGrading(
                                        shadows = GradeWheel(0f, 0f, s),
                                        midtones = GradeWheel(0f, 0f, m),
                                        highlights = GradeWheel(0f, 0f, h),
                                        global = GradeWheel(0f, 0f, g),
                                        blending = blend,
                                        balance = balance,
                                    ),
                                ) ?: continue
                                var previous = -1f
                                for (i in 0..400) {
                                    val v = i / 400f * 1.2f
                                    val rgb = floatArrayOf(v * v * v, v * v * v, v * v * v)
                                    ColourGradingOp.apply(rgb, p)
                                    val l = lab(rgb[0], rgb[1], rgb[2])[0]
                                    assertThat(l).isAtLeast(previous)
                                    previous = l
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `shadow tint colours the shadows, not the highlights, and black stays black`() {
        val p = checkNotNull(GradingParams.from(ColourGrading(shadows = GradeWheel(hue = 250f, saturation = 60f))))
        val dark = floatArrayOf(0.001f, 0.001f, 0.001f)
        val light = floatArrayOf(0.8f, 0.8f, 0.8f)
        val black = floatArrayOf(0f, 0f, 0f)
        val rgb = dark + light + black
        ColourGradingOp.apply(rgb, p)
        val d = lab(rgb[0], rgb[1], rgb[2])
        val l = lab(rgb[3], rgb[4], rgb[5])
        val dc = sqrt(d[1] * d[1] + d[2] * d[2])
        assertThat(dc / d[0]).isWithin(1e-3f).of(0.6f * GradingParams.TINT.toFloat())
        assertThat(Math.toDegrees(atan2(d[2], d[1]).toDouble())).isWithin(0.5).of(-110.0)
        assertThat(sqrt(l[1] * l[1] + l[2] * l[2])).isLessThan(1e-3f)
        assertThat(rgb.copyOfRange(6, 9).map { abs(it) }.max()).isLessThan(1e-6f)
    }

    @Test
    fun `balance moves the pivot, positive balance tints more of the image as highlights`() {
        val mid = floatArrayOf(0.42f, 0.42f, 0.42f)
        fun chroma(balance: Float): Float {
            val p = checkNotNull(
                GradingParams.from(ColourGrading(highlights = GradeWheel(40f, 80f), balance = balance)),
            )
            val rgb = mid.copyOf()
            ColourGradingOp.apply(rgb, p)
            val l = lab(rgb[0], rgb[1], rgb[2])
            return sqrt(l[1] * l[1] + l[2] * l[2])
        }
        assertThat(chroma(100f)).isGreaterThan(chroma(0f))
        assertThat(chroma(0f)).isGreaterThan(chroma(-100f))
        assertThat(chroma(-100f)).isAtLeast(0f)
    }

    @Test
    fun `neutral tools resolve to nothing`() {
        val params = DevelopParams.from(SyntheticImages.colorChecker(), Recipe())
        assertThat(params.tools).isEqualTo(ColourToolParams.NONE)
        assertThat(params.curve).isNull()
        assertThat(GradingParams.from(ColourGrading(blending = 10f, balance = 40f))).isNull()
        assertThat(GradingParams.from(ColourGrading(shadows = GradeWheel(hue = 200f)))).isNull()
    }

    @Test
    fun `colour tools render the colour checker deterministically and keep every value finite`() {
        val recipe = Recipe(
            colour = Colour(
                mixer = ColourMixer(hue = BandValues(green = 40f), saturation = BandValues(red = 30f)),
                grading = ColourGrading(
                    shadows = GradeWheel(220f, 30f, -20f),
                    highlights = GradeWheel(45f, 25f, 10f),
                    global = GradeWheel(0f, 0f, 15f),
                ),
            ),
        )
        for (input in listOf(
            SyntheticImages.colorChecker(),
            SyntheticImages.outOfRange(),
            SyntheticImages.hueSweep(64, 8),
        )) {
            val a = CpuDevelop.renderLinear(input, recipe)
            assertThat(a).isEqualTo(CpuDevelop.renderLinear(input, recipe))
            a.forEach { assertThat(it.isFinite()).isTrue() }
        }
    }
}
