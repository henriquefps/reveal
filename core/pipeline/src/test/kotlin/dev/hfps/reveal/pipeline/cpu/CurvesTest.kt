package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/** The tone curve (slot 8), docs/adr/0013. */
class CurvesTest {
    private val steps = listOf(-100f, -50f, 0f, 50f, 100f)

    // ------------------------------------------------------------------ MonotoneSpline

    @Test
    fun `spline interpolates its points and is flat outside them`() {
        val points = listOf(CurvePoint(0.1f, 0.05f), CurvePoint(0.4f, 0.5f), CurvePoint(0.7f, 0.6f), CurvePoint(1f, 1f))
        val s = checkNotNull(MonotoneSpline.of(points))
        points.forEach { assertThat(s.eval(it.x.toDouble())).isWithin(1e-6).of(it.y.toDouble()) }
        assertThat(s.eval(0.0)).isWithin(1e-9).of(0.05)
        assertThat(MonotoneSpline.of(Curves.LINEAR)).isNull()
        assertThat(MonotoneSpline.of(emptyList())).isNull()
    }

    @Test
    fun `spline is monotone on monotone data and never overshoots, even on steep random points`() {
        val random = Random(7)
        repeat(300) {
            val n = 2 + random.nextInt(Curves.MAX_POINTS - 1)
            val xs = (0 until n).map { random.nextDouble() }.sorted().distinct().toDoubleArray()
            if (xs.size < 2) return@repeat
            val ys = xs.indices.map { random.nextDouble() }.sorted().toDoubleArray()
            val s = MonotoneSpline(xs, ys)
            var previous = s.eval(0.0)
            for (i in 1..4000) {
                val y = s.eval(i / 4000.0)
                assertThat(y).isAtLeast(previous - 1e-12)
                assertThat(y).isIn(com.google.common.collect.Range.closed(ys.first() - 1e-12, ys.last() + 1e-12))
                previous = y
            }
        }
    }

    @Test
    fun `non-monotone points turn smoothly with a flat tangent at the turn and stay in range`() {
        val s = MonotoneSpline(doubleArrayOf(0.0, 0.5, 1.0), doubleArrayOf(0.0, 1.0, 0.0))
        assertThat(s.eval(0.5)).isWithin(1e-12).of(1.0)
        for (i in 0..1000) assertThat(s.eval(i / 1000.0)).isIn(com.google.common.collect.Range.closed(0.0, 1.0))
    }

    // ------------------------------------------------------------------ parametric

    @Suppress("NestedBlockDepth")
    @Test
    fun `parametric curve is strictly increasing with fixed ends for every slider combination and split`() {
        val splits = listOf(Triple(25f, 50f, 75f), Triple(5f, 10f, 15f), Triple(85f, 90f, 95f), Triple(10f, 50f, 60f))
        for ((a, b, c) in splits) {
            for (h in steps) {
                for (l in steps) {
                    for (d in steps) {
                        for (sh in steps) {
                            val p = ParametricCurve(h, l, d, sh, a, b, c)
                            val s = ParametricCurveShape.spline(p) ?: continue
                            assertThat(s.eval(0.0)).isWithin(1e-12).of(0.0)
                            assertThat(s.eval(1.0)).isWithin(1e-12).of(1.0)
                            var previous = 0.0
                            for (i in 1..2000) {
                                val y = s.eval(i / 2000.0)
                                assertThat(y).isGreaterThan(previous)
                                previous = y
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `region sliders move their region's middle by the documented amount`() {
        // Shadows +100 lifts the middle of the shadow region (0.125) by 0.45 of the gap to the darks knot (0.25).
        val lift = checkNotNull(ParametricCurveShape.spline(ParametricCurve(shadows = 100f)))
        assertThat(lift.eval(0.125)).isWithin(1e-9).of(0.125 + 0.45 * 0.25)
        // Highlights -100 lowers 0.875 by 0.45 of the gap to the lights knot (0.25).
        val lower = checkNotNull(ParametricCurveShape.spline(ParametricCurve(highlights = -100f)))
        assertThat(lower.eval(0.875)).isWithin(1e-9).of(0.875 - 0.45 * 0.25)
        // Moving the shadows split moves the shadows knot.
        val moved = checkNotNull(ParametricCurveShape.spline(ParametricCurve(shadows = 100f, splitShadows = 40f)))
        assertThat(moved.eval(0.2)).isGreaterThan(0.2 + 0.1)
        assertThat(ParametricCurveShape.spline(ParametricCurve(splitShadows = 40f))).isNull()
    }

    @Test
    fun `splits are kept in order and apart`() {
        val s = ParametricCurveShape.splits(
            ParametricCurve(splitShadows = 80f, splitMidtones = 20f, splitHighlights = 99f),
        )
        assertThat(s[1] - s[0]).isAtLeast(ParametricCurveShape.MIN_GAP - 1e-12)
        assertThat(s[2] - s[1]).isAtLeast(ParametricCurveShape.MIN_GAP - 1e-12)
        assertThat(s[2]).isAtMost(1 - ParametricCurveShape.MIN_GAP + 1e-12)
    }

    // ------------------------------------------------------------------ lookup table

    @Suppress("NestedBlockDepth")
    @Test
    fun `lookup table follows the composed splines within 2e-4`() {
        val curves = listOf(
            Curves(rgb = Curves.MEDIUM_CONTRAST),
            Curves(rgb = Curves.STRONG_CONTRAST, red = listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 0.9f))),
            Curves(parametric = ParametricCurve(80f, -40f, 60f, -100f, 15f, 30f, 90f)),
            // Steep: almost a step between 0.48 and 0.52.
            Curves(
                blue = listOf(
                    CurvePoint(0f, 0f),
                    CurvePoint(0.48f, 0.05f),
                    CurvePoint(0.52f, 0.95f),
                    CurvePoint(1f, 1f),
                ),
            ),
        )
        for (c in curves) {
            val lut = checkNotNull(CurveLut.of(c))
            val p = ParametricCurveShape.spline(c.parametric)
            val rgb = MonotoneSpline.of(c.rgb)
            val ch = listOf(c.red, c.green, c.blue).map { MonotoneSpline.of(it) }
            for (i in 0..5000) {
                val e = i / 5000.0
                val base = rgb?.eval(p?.eval(e) ?: e) ?: (p?.eval(e) ?: e)
                for (k in 0..2) {
                    val want = ch[k]?.eval(base) ?: base
                    val got = lut.eval(e.toFloat(), k)
                    // The steep blue curve is the worst case: a slope of ~22 over 0.04 sampled every 1/511.
                    val tol = if (k == 2 && c.blue.isNotEmpty()) 4e-3 else 2e-4
                    assertThat(abs(got - want)).isLessThan(tol)
                }
            }
        }
        assertThat(CurveLut.of(Curves())).isNull()
        assertThat(CurveLut.of(Curves(rgb = Curves.LINEAR))).isNull()
    }

    @Suppress("NestedBlockDepth")
    @Test
    fun `the operator's sqrt-sampled linear table matches the exact composition`() {
        val curves = listOf(
            Curves(rgb = Curves.STRONG_CONTRAST, blue = listOf(CurvePoint(0f, 0.08f), CurvePoint(1f, 0.9f))),
            Curves(parametric = ParametricCurve(80f, -40f, 60f, -100f, 15f, 30f, 90f)),
            Curves(rgb = listOf(CurvePoint(0.1f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(0.9f, 1f))),
        )
        for (c in curves) {
            val lut = checkNotNull(CurveLut.of(c))
            var worst = 0f
            for (i in 0..20000) {
                val v = (i / 20000f).let { it * it }
                for (k in 0..2) {
                    val exact = CurveOp.decode(lut.eval(CurveOp.encode(v), k))
                    worst = maxOf(worst, abs(CurveOp.encode(lut.apply(v, k)) - CurveOp.encode(exact)))
                }
            }
            // Under a third of an 8-bit level in encoded terms.
            assertThat(worst).isLessThan(1e-3f)
        }
    }

    @Test
    fun `lookup extends with slope 1 outside the encoded range and stays continuous`() {
        val lut = checkNotNull(CurveLut.of(Curves(rgb = listOf(CurvePoint(0f, 0.2f), CurvePoint(1f, 0.8f)))))
        assertThat(lut.eval(0f, 0)).isWithin(1e-6f).of(0.2f)
        assertThat(lut.eval(-0.1f, 0)).isWithin(1e-6f).of(0.1f)
        assertThat(lut.eval(1f, 1)).isWithin(1e-6f).of(0.8f)
        assertThat(lut.eval(1.5f, 1)).isWithin(1e-6f).of(1.3f)
    }

    // ------------------------------------------------------------------ operator

    @Test
    fun `transfer round trips, negatives included`() {
        for (v in listOf(-2f, -0.5f, -0.001f, 0f, 0.0005f, 0.003f, 0.18f, 0.5f, 1f, 4f)) {
            assertThat(CurveOp.decode(CurveOp.encode(v))).isWithin(2e-6f + 2e-6f * abs(v)).of(v)
        }
        assertThat(CurveOp.encode(0.18f)).isWithin(1e-4f).of(0.4613f)
    }

    @Test
    fun `point curve maps mid grey where the curve says, on a grey ramp`() {
        val input = SyntheticImages.grayRamp(256, 2)
        val lifted =
            Recipe(
                light = Light(
                    curve = Curves(rgb = listOf(CurvePoint(0f, 0f), CurvePoint(0.4613f, 0.6f), CurvePoint(1f, 1f))),
                ),
            )
        val base = CpuDevelop.renderLinear(input, Recipe())
        val out = CpuDevelop.renderLinear(input, lifted)
        // The grey that encodes to 0.4613 (linear 0.18) lands on the encoded 0.6 (linear 0.3185).
        var best = 0
        for (x in 0 until 256) if (abs(base[x * 3] - 0.18f) < abs(base[best * 3] - 0.18f)) best = x
        assertThat(CurveOp.encode(out[best * 3])).isWithin(0.01f).of(CurveOp.encode(base[best * 3]) * 0.6f / 0.4613f)
        // Monotone along the ramp, greys stay grey.
        for (x in 1 until 256) {
            assertThat(out[x * 3]).isAtLeast(out[(x - 1) * 3])
            assertThat(abs(out[x * 3] - out[x * 3 + 2])).isLessThan(1e-6f)
        }
    }

    @Test
    fun `channel curves shift colour balance by tone and contrast presets steepen the mid-tones`() {
        val input = SyntheticImages.grayRamp(64, 1)
        val warmer =
            Recipe(
                light = Light(
                    curve = Curves(red = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))),
                ),
            )
        val out = CpuDevelop.renderLinear(input, warmer)
        val mid = 32 * 3
        assertThat(out[mid]).isGreaterThan(out[mid + 1])
        assertThat(out[mid + 1]).isWithin(1e-6f).of(out[mid + 2])
        val strong = CpuDevelop.renderLinear(input, Recipe(light = Light(curve = Curves(rgb = Curves.STRONG_CONTRAST))))
        val plain = CpuDevelop.renderLinear(input, Recipe())
        // Pixels 10..18 of the linear ramp encode to about 0.43..0.60, the mid-tones the S-curve steepens.
        val slope = { a: FloatArray -> CurveOp.encode(a[18 * 3]) - CurveOp.encode(a[10 * 3]) }
        assertThat(slope(strong)).isGreaterThan(slope(plain) * 1.15f)
    }

    @Test
    fun `out-of-range values stay finite and ordered`() {
        val input = SyntheticImages.outOfRange()
        val recipe =
            Recipe(
                light = Light(
                    curve = Curves(rgb = Curves.STRONG_CONTRAST, parametric = ParametricCurve(shadows = 100f)),
                ),
            )
        val out = CpuDevelop.renderLinear(input, recipe)
        out.forEach { assertThat(it.isFinite()).isTrue() }
        // Every channel is a monotone function of its input: check on a dense line through the range.
        val lut = checkNotNull(CurveLut.of(recipe.light.curve))
        var previous = Float.NEGATIVE_INFINITY
        for (i in -300..1600) {
            val v = i / 100f
            val y = CurveOp.decode(lut.eval(CurveOp.encode(v), 0))
            assertThat(y).isAtLeast(previous)
            previous = y
        }
    }
}
