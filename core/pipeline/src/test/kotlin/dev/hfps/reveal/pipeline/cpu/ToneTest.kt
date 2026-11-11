package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import org.junit.Test

class ToneTest {
    private val sliderValues = listOf(-100f, -50f, 0f, 50f, 100f)

    private fun log2(v: Float) = ln(v.toDouble()) / ln(2.0)

    /** Scene-linear working-space pixels of [input] (WB, matrix, exposure), i.e. what the local tone op sees. */
    private fun scene(input: PipelineInput): FloatArray {
        val params = DevelopParams.from(input, Recipe())
        val rgb = input.linearRgb.copyOf()
        RawWhiteBalanceOp.apply(rgb, params.wbMultipliers)
        CameraMatrixOp.apply(rgb, params.cameraToWorking)
        return rgb
    }

    private fun localTone(input: PipelineInput, p: ToneParams, coef: FloatArray? = null): FloatArray {
        val grid = LowResGrid(input.width, input.height)
        val rgb = scene(input)
        val moments = LocalToneOp.moments(rgb, grid)
        LocalToneOp.apply(rgb, grid, moments, coef ?: LocalToneOp.guide(moments, grid), p)
        return rgb
    }

    /** A non-edge-aware base (a = 0, b = box mean): the classic halo-prone local operator, for comparison. */
    @Suppress("NestedBlockDepth")
    private fun boxBaseCoef(input: PipelineInput): FloatArray {
        val grid = LowResGrid(input.width, input.height)
        val coef = LocalToneOp.guide(LocalToneOp.moments(scene(input), grid), grid)
        val rgb = scene(input)
        val gw = grid.gridWidth
        val gh = grid.gridHeight
        val means = FloatArray(gw * gh * 2)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var s = 0f
                var n = 0
                for (y in gy * grid.cell until minOf((gy + 1) * grid.cell, grid.height)) {
                    for (x in gx * grid.cell until minOf((gx + 1) * grid.cell, grid.width)) {
                        val i = (y * grid.width + x) * 3
                        s += LocalToneOp.logLuminance(rgb[i], rgb[i + 1], rgb[i + 2])
                        n++
                    }
                }
                means[(gy * gw + gx) * 2 + 1] = s / n
            }
        }
        val blurred = LocalToneOp.box(LocalToneOp.box(means, gw, gh, true), gw, gh, false)
        for (i in 0 until gw * gh) coef[i * 2] = 0f
        for (i in 0 until gw * gh) coef[i * 2 + 1] = blurred[i * 2 + 1]
        return coef
    }

    @Test
    fun `default light is neutral and skips both tone operators`() {
        val p = ToneParams.from(Light())

        assertThat(p).isEqualTo(ToneParams())
        assertThat(p.localActive).isFalse()
        assertThat(p.globalActive).isFalse()
        assertThat(ToneParams.from(Light(exposureEv = 1.5f))).isEqualTo(ToneParams())
    }

    @Test
    fun `slider values are clamped to the recipe ranges`() {
        assertThat(ToneParams.from(Light(highlights = -300f))).isEqualTo(ToneParams.from(Light(highlights = -100f)))
        assertThat(ToneParams.from(Light(contrast = 1000f)).contrastGamma).isWithin(1e-5f).of(1.6245f)
    }

    @Test
    fun `local tone curve is strictly monotonic for every slider pair`() {
        for (h in sliderValues) {
            for (s in sliderValues) {
                val p = ToneParams.from(Light(highlights = h, shadows = s))
                var previous = Float.NEGATIVE_INFINITY
                var b = -20f
                while (b <= 8f) {
                    val out = b + LocalToneOp.delta(b, p)
                    assertThat(out).isGreaterThan(previous)
                    previous = out
                    b += 0.001f
                }
            }
        }
    }

    @Test
    fun `local tone curve golden values`() {
        val h = ToneParams.from(Light(highlights = -100f))
        val s = ToneParams.from(Light(shadows = 100f))
        // White comes down 1.36 stops; two and four stops over white come back into range; mid grey barely moves.
        assertThat(LocalToneOp.delta(0f, h)).isWithin(0.01f).of(-1.36f)
        assertThat(2f + LocalToneOp.delta(2f, h)).isWithin(0.01f).of(-0.51f)
        assertThat(4f + LocalToneOp.delta(4f, h)).isWithin(0.01f).of(0.29f)
        assertThat(LocalToneOp.delta(-2.47f, h)).isWithin(0.02f).of(-0.13f)
        assertThat(LocalToneOp.delta(-8f, h)).isWithin(0.01f).of(0f)
        // Deep shadows rise by the full 1.75 stops; black stays black; highlights do not move.
        assertThat(LocalToneOp.delta(-5.75f, s)).isWithin(0.01f).of(1.75f)
        assertThat(LocalToneOp.delta(-14f, s)).isEqualTo(0f)
        assertThat(LocalToneOp.delta(-1f, s)).isEqualTo(0f)
    }

    @Test
    fun `local tone keeps chromaticity`() {
        val input = SyntheticImages.scene(96, 64)
        val before = scene(input)
        val after = localTone(input, ToneParams.from(Light(highlights = -80f, shadows = 70f)))

        for (i in before.indices step 3) {
            if (before[i + 1] < 1e-3f) continue
            assertThat(after[i] / after[i + 1]).isWithin(1e-4f * abs(before[i] / before[i + 1]) + 1e-5f)
                .of(before[i] / before[i + 1])
            assertThat(after[i + 2] / after[i + 1]).isWithin(1e-4f * abs(before[i + 2] / before[i + 1]) + 1e-5f)
                .of(before[i + 2] / before[i + 1])
        }
    }

    /**
     * Halo metric: on a step edge, the spread (in stops) of the tone gain across the dark side, from the pixel
     * next to the edge to the middle of the half. A pointwise operator scores 0; a halo shows up as the near-edge
     * pixels getting a different gain than the far ones.
     */
    private fun halo(input: PipelineInput, out: FloatArray, darkSide: Boolean): Double {
        val before = scene(input)
        val w = input.width
        val row = input.height / 2
        val range = if (darkSide) 0 until w / 2 else w / 2 until w
        val gains = range.map { x ->
            val i = (row * w + x) * 3
            log2(out[i + 1] / before[i + 1])
        }
        return gains.max() - gains.min()
    }

    @Test
    fun `no halos at a high-contrast step edge, unlike a non edge-aware base`() {
        val edge = SyntheticImages.stepEdge(512, 64, dark = 0.02f, bright = 2f)
        val shadows = ToneParams.from(Light(shadows = 100f))
        val highlights = ToneParams.from(Light(highlights = -100f))

        val guidedShadows = halo(edge, localTone(edge, shadows), darkSide = true)
        val guidedHighlights = halo(edge, localTone(edge, highlights), darkSide = false)
        val boxShadows = halo(edge, localTone(edge, shadows, boxBaseCoef(edge)), darkSide = true)
        val boxHighlights = halo(edge, localTone(edge, highlights, boxBaseCoef(edge)), darkSide = false)

        println(
            "REVEAL_HALO step 0.02|2.0: guided shadows+100 $guidedShadows st, highlights-100 $guidedHighlights st; " +
                "box base shadows+100 $boxShadows st, highlights-100 $boxHighlights st",
        )
        assertThat(guidedShadows).isLessThan(0.05)
        assertThat(guidedHighlights).isLessThan(0.05)
        assertThat(boxShadows).isGreaterThan(0.5)
        assertThat(boxHighlights).isGreaterThan(0.3)

        // A moderate (2-stop) edge, around mid grey, where the guided filter is least sure it is an edge.
        val soft = SyntheticImages.stepEdge(512, 64, dark = 0.06f, bright = 0.24f)
        val both = ToneParams.from(Light(highlights = -100f, shadows = 100f))
        val softDark = halo(soft, localTone(soft, both), darkSide = true)
        val softBright = halo(soft, localTone(soft, both), darkSide = false)
        val softBoxDark = halo(soft, localTone(soft, both, boxBaseCoef(soft)), darkSide = true)
        println("REVEAL_HALO step 0.06|0.24 (h-100 s+100): guided $softDark / $softBright st, box base $softBoxDark st")
        assertThat(softDark).isLessThan(0.05)
        assertThat(softBright).isLessThan(0.05)
    }

    @Test
    fun `local texture survives a strong shadow lift`() {
        // A textured dark half (+-0.25 stops) whose base sits where the pointwise curve compresses most.
        val edge = SyntheticImages.stepEdge(512, 64, dark = 0.12f, bright = 2f, texture = 0.25f)
        val p = ToneParams.from(Light(shadows = 100f))
        val before = scene(edge)
        val out = localTone(edge, p)
        val pointwise = before.copyOf().also {
            for (i in it.indices step 3) {
                val l = LocalToneOp.logLuminance(it[i], it[i + 1], it[i + 2])
                val g = StrictMath.pow(2.0, LocalToneOp.delta(l, p).toDouble()).toFloat()
                for (c in 0 until 3) it[i + c] *= g
            }
        }

        fun amplitude(rgb: FloatArray): Double {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (y in 16 until 48) {
                for (x in 64 until 192) {
                    val v = log2(rgb[(y * 512 + x) * 3 + 1])
                    lo = minOf(lo, v)
                    hi = max(hi, v)
                }
            }
            return hi - lo
        }
        val original = amplitude(before)
        println(
            "REVEAL_TEXTURE amplitude stops: original $original, guided ${amplitude(out)}, " +
                "pointwise ${amplitude(pointwise)}",
        )
        // EPS trades this against halos (docs/adr/0007): 1/32 keeps about 80 % of a +-0.25-stop texture.
        assertThat(amplitude(out) / original).isAtLeast(0.75)
        assertThat(amplitude(pointwise) / original).isLessThan(amplitude(out) / original)
    }

    @Test
    fun `highlights darken the sky and shadows lift the foreground of the demo scene`() {
        val input = SyntheticImages.scene(120, 80)
        val base = CpuDevelop.renderLinear(input, Recipe())
        val dark = CpuDevelop.renderLinear(input, Recipe(light = Light(highlights = -100f)))
        val lift = CpuDevelop.renderLinear(input, Recipe(light = Light(shadows = 100f)))
        val sky = (10 * 120 + 30) * 3
        val ground = (70 * 120 + 30) * 3

        assertThat(dark[sky + 1]).isLessThan(base[sky + 1] * 0.8f)
        assertThat(dark[ground + 1]).isWithin(base[ground + 1] * 0.03f).of(base[ground + 1])
        assertThat(lift[ground + 1]).isGreaterThan(base[ground + 1] * 1.5f)
        assertThat(lift[sky + 1]).isWithin(base[sky + 1] * 0.05f).of(base[sky + 1])
    }

    @Suppress("NestedBlockDepth")
    @Test
    fun `global curve keeps its fixed points and is monotonic for every slider triple`() {
        for (c in sliderValues) {
            val contrastOnly = ToneParams.from(Light(contrast = c))
            assertThat(GlobalToneOp.curve(0f, contrastOnly)).isEqualTo(0f)
            assertThat(GlobalToneOp.curve(1f, contrastOnly)).isEqualTo(1f)
            assertThat(GlobalToneOp.curve(GlobalToneOp.PIVOT, contrastOnly)).isWithin(1e-6f).of(GlobalToneOp.PIVOT)
            for (w in sliderValues) {
                for (b in sliderValues) {
                    val p = ToneParams.from(Light(contrast = c, whites = w, blacks = b))
                    var previous = Float.NEGATIVE_INFINITY
                    var x = 0f
                    while (x <= 1.5f) {
                        val y = GlobalToneOp.curve(x, p)
                        assertThat(y).isAtLeast(previous)
                        previous = y
                        x += 0.0005f
                    }
                }
            }
        }
    }

    @Test
    fun `global curve golden values`() {
        val p0 = GlobalToneOp.PIVOT
        assertThat(p0).isWithin(1e-6f).of(0.5646216f)
        val plus = ToneParams.from(Light(contrast = 50f))
        // +50 contrast: a quarter-lightness darkens, three-quarter lightness brightens.
        assertThat(GlobalToneOp.curve(0.25f, plus)).isLessThan(0.25f)
        assertThat(GlobalToneOp.curve(0.8f, plus)).isGreaterThan(0.8f)
        // Hermite segment below the pivot (t = 0.25/p0, tangents p0/g and p0*g), slope g at the pivot.
        val g = 1.2746f
        val t = 0.25f / p0
        val expected =
            (t * t * t - 2 * t * t + t) * p0 / g + (-2 * t * t * t + 3 * t * t) * p0 + (t * t * t - t * t) * p0 * g
        assertThat(GlobalToneOp.curve(0.25f, plus)).isWithin(1e-5f).of(expected)
        val slope = (GlobalToneOp.curve(p0 + 1e-3f, plus) - GlobalToneOp.curve(p0 - 1e-3f, plus)) / 2e-3f
        assertThat(slope).isWithin(0.01f).of(g)
        assertThat(GlobalToneOp.curve(1f, ToneParams.from(Light(whites = 100f)))).isWithin(1e-6f).of(1.12f)
        assertThat(GlobalToneOp.curve(1f, ToneParams.from(Light(whites = -100f)))).isWithin(1e-6f).of(0.88f)
        assertThat(GlobalToneOp.curve(0f, ToneParams.from(Light(blacks = 100f)))).isWithin(1e-6f).of(0.2f)
        assertThat(GlobalToneOp.curve(0.1f, ToneParams.from(Light(blacks = -100f)))).isLessThan(0f)
    }

    @Test
    fun `global tone preserves hue and lifts black to neutral grey`() {
        val rgb = floatArrayOf(0.30f, 0.10f, 0.05f, 0f, 0f, 0f, 0.6f, 0.62f, 0.7f)
        val before = rgb.copyOf()

        GlobalToneOp.apply(rgb, ToneParams.from(Light(contrast = 60f, blacks = 50f)))

        // The orange pixel keeps its channel ratios (pure gain: no neutral remainder at this luminance).
        assertThat(rgb[0] / rgb[1]).isWithin(1e-4f).of(before[0] / before[1])
        assertThat(rgb[2] / rgb[1]).isWithin(1e-4f).of(before[2] / before[1])
        // Black is lifted to a neutral grey.
        assertThat(rgb[3]).isGreaterThan(0f)
        assertThat(rgb[3]).isEqualTo(rgb[4])
        assertThat(rgb[4]).isEqualTo(rgb[5])
    }

    @Test
    fun `contrast spreads tones around mid grey on a ramp, monotonic in the render`() {
        val ramp = SyntheticImages.rawRamp(256, 1, max = 1.2f)
        val flat = CpuDevelop.renderLinear(ramp, Recipe(light = Light(contrast = -50f)))
        val punchy = CpuDevelop.renderLinear(ramp, Recipe(light = Light(contrast = 50f)))

        for (i in 1 until 256) {
            assertThat(punchy[i * 3 + 1]).isAtLeast(punchy[(i - 1) * 3 + 1])
            assertThat(flat[i * 3 + 1]).isAtLeast(flat[(i - 1) * 3 + 1])
        }
        val dark = 10 * 3 + 1
        val bright = 200 * 3 + 1
        assertThat(punchy[bright] - punchy[dark]).isGreaterThan(flat[bright] - flat[dark])
    }

    @Test
    fun `the local operator looks the same at proxy and at twice the resolution`() {
        // Resolution independence: the grid scales with the image, so a 2x render downsampled matches the 1x one.
        val small = SyntheticImages.scene(240, 160)
        val large = SyntheticImages.scene(480, 320)
        val recipe = Recipe(light = Light(highlights = -100f, shadows = 100f))
        val a = CpuDevelop.renderLinear(small, recipe)
        val b = CpuDevelop.renderLinear(large, recipe)
        val aBase = CpuDevelop.renderLinear(small, Recipe())
        val bBase = CpuDevelop.renderLinear(large, Recipe())

        var diff = 0.0
        var baseDiff = 0.0
        for (y in 0 until 160) {
            for (x in 0 until 240) {
                val i = (y * 240 + x) * 3 + 1
                val j = ((2 * y) * 480 + 2 * x) * 3 + 1
                diff += abs(log2(max(a[i], 1e-4f)) - log2(max(b[j], 1e-4f)))
                baseDiff += abs(log2(max(aBase[i], 1e-4f)) - log2(max(bBase[j], 1e-4f)))
            }
        }
        val extra = (diff - baseDiff) / (240 * 160)
        println("REVEAL_SCALE mean extra |log2 diff| between 1x and 2x: $extra stops")
        assertThat(extra).isLessThan(0.03)
    }
}
