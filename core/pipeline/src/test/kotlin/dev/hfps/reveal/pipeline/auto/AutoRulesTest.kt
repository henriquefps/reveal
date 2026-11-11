@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.auto

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.Luma
import dev.hfps.reveal.pipeline.cpu.ProfileLooks
import dev.hfps.reveal.pipeline.cpu.ProfileOp
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import org.junit.Test

/**
 * Auto's rules on fixtures of analysis values (docs/adr/0010): histograms built from Gaussian bumps in stops, the
 * real Standard tone table, chosen chroma. Plus determinism and idempotence on synthetic images end to end.
 */
class AutoRulesTest {
    /** Standard's neutral tone table, as [AutoAnalyser] builds it. */
    private val standardTable = DoubleArray(AutoAnalysis.BINS) { i ->
        val grey = 2.0.pow(AutoAnalysis.centre(i)).toFloat()
        val g = floatArrayOf(grey, grey, grey)
        ProfileOp.apply(g, ProfileLooks.resolve(Profile()))
        Luma.of(g[0], g[1], g[2]).toDouble()
    }

    private val identityTable = DoubleArray(AutoAnalysis.BINS) { 2.0.pow(AutoAnalysis.centre(it)) }

    /** A histogram of Gaussian bumps (centre in stops, sigma in stops, peak weight: mass = weight * sigma). */
    private fun hist(vararg bumps: Triple<Double, Double, Double>): DoubleArray {
        val h = DoubleArray(AutoAnalysis.BINS) { i ->
            val s = AutoAnalysis.centre(i)
            bumps.sumOf { (c, sigma, w) -> w * exp(-(s - c) * (s - c) / (2 * sigma * sigma)) }
        }
        val sum = h.sum()
        return DoubleArray(h.size) { h[it] / sum }
    }

    private fun fixture(
        vararg bumps: Triple<Double, Double, Double>,
        chroma: Double = 0.11,
        scene: Boolean = true,
    ): AutoAnalysis {
        val h = hist(*bumps)
        return AutoAnalysis(h, h.copyOf(), if (scene) standardTable else identityTable, 0.0, chroma, scene)
    }

    private fun normal(centre: Double, sigma: Double = 1.5) = fixture(Triple(centre, sigma, 1.0))

    @Test
    fun `exposure puts the key on the target grey`() {
        val p = AutoParams()
        for (key in listOf(-6.0, -4.5, -3.0)) {
            val a = normal(key)
            val ev = AutoRules.exposure(a, p)
            assertThat(a.display(a.percentile(0.5) + ev)).isWithin(0.004).of(p.targetGrey)
        }
    }

    @Test
    fun `under-exposure is corrected by the same amount and nothing else changes`() {
        val base = AutoRules.compute(normal(-4.5))
        val dark = AutoRules.compute(normal(-6.0))
        assertThat(dark.exposureEv - base.exposureEv).isWithin(0.011f).of(1.5f)
        assertThat(dark.copy(exposureEv = 0f)).isEqualTo(base.copy(exposureEv = 0f))
    }

    @Test
    fun `exposure is limited`() {
        val p = AutoParams()
        assertThat(AutoRules.exposure(normal(-12.0), p)).isEqualTo(p.maxExposure)
        assertThat(AutoRules.exposure(normal(2.0), p)).isEqualTo(-p.maxExposure)
    }

    @Test
    fun `a well-exposed, moderate scene gets small edits`() {
        // About 5 stops from the 0.5th to the 99.5th percentile: nothing near white or black.
        val r = AutoRules.compute(normal(AutoRules.run0Key(), 1.0))
        assertThat(abs(r.exposureEv)).isLessThan(0.05f)
        assertThat(r.highlights).isEqualTo(0f)
        assertThat(r.shadows).isEqualTo(0f)
        assertThat(abs(r.contrast)).isAtMost(20f)
    }

    @Test
    fun `bright highlights are pulled and more headroom pulls harder`() {
        val key = AutoRules.run0Key()
        val none = AutoRules.compute(normal(key, 1.0))
        val some = AutoRules.compute(fixture(Triple(key, 1.0, 1.0), Triple(0.5, 0.2, 0.1)))
        val more = AutoRules.compute(fixture(Triple(key, 1.0, 1.0), Triple(1.5, 0.2, 0.1)))
        assertThat(none.highlights).isEqualTo(0f)
        assertThat(some.highlights).isLessThan(0f)
        // Further over: pulled at least as hard (the exposure's highlight protection takes part of it).
        assertThat(more.highlights).isAtMost(some.highlights)
        assertThat(more.exposureEv).isLessThan(some.exposureEv)
        assertThat(more.highlights).isAtLeast(-AutoParams().maxHighlights.toFloat())
    }

    @Test
    fun `highlight protection lowers the exposure by at most maxProtect`() {
        val p = AutoParams()
        val plain = fixture(Triple(-7.0, 1.0, 1.0))
        val backlit = fixture(Triple(-7.0, 1.0, 0.9), Triple(0.0, 0.3, 0.1))
        val ev = AutoRules.exposure(plain, p)
        val protectedEv = AutoRules.exposure(backlit, p)
        assertThat(protectedEv).isLessThan(ev)
        assertThat(ev - protectedEv).isAtMost(p.maxProtect + 0.011)
    }

    @Test
    fun `blocked shadows are lifted, within the limit`() {
        val key = AutoRules.run0Key()
        val r = AutoRules.compute(fixture(Triple(key, 1.0, 0.8), Triple(key - 7, 0.5, 0.2)))
        assertThat(r.shadows).isGreaterThan(0f)
        assertThat(r.shadows).isAtMost(AutoParams().maxShadows.toFloat())
    }

    @Test
    fun `contrast follows the spread`() {
        val key = AutoRules.run0Key()
        val flat = AutoRules.compute(normal(key, 0.5))
        val wide = AutoRules.compute(normal(key, 3.0))
        assertThat(flat.contrast).isGreaterThan(0f)
        assertThat(wide.contrast).isLessThan(flat.contrast)
    }

    @Test
    fun `vibrance is modest and never negative`() {
        val key = AutoRules.run0Key()
        val dull = AutoRules.compute(fixture(Triple(key, 1.5, 1.0), chroma = 0.03))
        val vivid = AutoRules.compute(fixture(Triple(key, 1.5, 1.0), chroma = 0.25))
        assertThat(dull.vibrance).isEqualTo(AutoParams().maxVibrance.toFloat())
        assertThat(vivid.vibrance).isEqualTo(0f)
    }

    @Test
    fun `display-referred whites are never pulled below the original`() {
        // A JPEG with 3 % of its pixels at white: no positive exposure, so nothing to recover.
        val a = fixture(Triple(-1.8, 1.0, 0.97), Triple(0.0, 0.05, 0.03), scene = false)
        val r = AutoRules.compute(a)
        assertThat(r.exposureEv).isAtMost(0f)
        assertThat(r.highlights).isEqualTo(0f)
    }

    @Test
    fun `values are whole slider steps`() {
        val r = AutoRules.compute(fixture(Triple(-5.3, 1.7, 0.9), Triple(0.7, 0.4, 0.1)))
        for (v in listOf(r.contrast, r.highlights, r.shadows, r.whites, r.blacks, r.vibrance)) {
            assertThat(v).isEqualTo(Math.round(v).toFloat())
        }
        assertThat(r.exposureEv * 100).isWithin(1e-3f).of(Math.round(r.exposureEv * 100).toFloat())
    }

    // ------------------------------------------------------------------ end to end on synthetic images

    private val scene: PipelineInput = SyntheticImages.scene(600, 400)

    @Test
    fun `analysis is deterministic`() {
        val sample = AutoAnalyser.sample(scene)
        val a = AutoAnalyser.analyse(sample, Recipe())
        val b = AutoAnalyser.analyse(AutoAnalyser.sample(scene), Recipe())
        assertThat(a.histogram.toList()).isEqualTo(b.histogram.toList())
        assertThat(a.toneTable.toList()).isEqualTo(b.toneTable.toList())
        assertThat(a.medianChroma).isEqualTo(b.medianChroma)
        assertThat(AutoRules.compute(a)).isEqualTo(AutoRules.compute(b))
    }

    @Test
    fun `tapping auto twice gives the same values`() {
        val sample = AutoAnalyser.sample(scene)
        val wb = AutoWhiteBalance.analyse(scene)
        for (start in listOf(Recipe(), Recipe(light = Light(exposureEv = 2f, shadows = -40f, contrast = 60f)))) {
            for (scope in AutoScope.entries) {
                val once = AutoRules.run(sample, start, scope, wb)
                assertThat(AutoRules.run(sample, once, scope, wb)).isEqualTo(once)
            }
        }
        // The analysis ignores the Light values, so the result does not depend on where the sliders were.
        assertThat(AutoRules.run(sample, Recipe(light = Light(exposureEv = 2f)), AutoScope.LIGHT))
            .isEqualTo(AutoRules.run(sample, Recipe(), AutoScope.LIGHT))
    }

    @Test
    fun `the light panel's auto leaves colour alone, the tool's sets vibrance and auto white balance`() {
        val sample = AutoAnalyser.sample(scene)
        val wb = AutoWhiteBalance.analyse(scene)
        val start = Recipe(colour = Colour(vibrance = -30f))
        assertThat(AutoRules.run(sample, start, AutoScope.LIGHT, wb).colour).isEqualTo(start.colour)
        val all = AutoRules.run(sample, start, AutoScope.ALL, wb)
        assertThat(all.colour.whiteBalance).isEqualTo(WhiteBalance())
        assertThat(all.colour.vibrance).isAtLeast(0f)
        val autoWb = Recipe(colour = Colour(WhiteBalance(mode = WbMode.AUTO)))
        val balanced = AutoRules.run(sample, autoWb, AutoScope.ALL, wb).colour.whiteBalance
        assertThat(balanced).isEqualTo(AutoRules.whiteBalance(wb))
        assertThat(balanced.mode).isEqualTo(WbMode.AUTO)
        assertThat(balanced.temperatureK!! % 10f).isEqualTo(0f)
    }

    @Test
    fun `auto works on top of the profile`() {
        val sample = AutoAnalyser.sample(scene)
        val standard = AutoRules.run(sample, Recipe(), AutoScope.LIGHT)
        val flat = AutoRules.run(sample, Recipe(profile = Profile(BaseLook.NEUTRAL)), AutoScope.LIGHT)
        assertThat(flat.profile.base).isEqualTo(BaseLook.NEUTRAL)
        assertThat(flat.light).isNotEqualTo(standard.light)
    }

    @Test
    fun `the analysis only sees the crop`() {
        // Left half dark, right half bright: cropping to either half moves the key the other way.
        val w = 200
        val h = 100
        val px = FloatArray(w * h * 3) { i -> if ((i / 3) % w < w / 2) 0.01f else 0.3f }
        val input = PipelineInput.linearSrgb(w, h, px)
        val left = Recipe(geometry = Geometry(crop = CropRect(0f, 0f, 0.5f, 1f)))
        val right = Recipe(geometry = Geometry(crop = CropRect(0.5f, 0f, 1f, 1f)))
        val l = AutoAnalyser.analyse(input, left).percentile(0.5)
        val r = AutoAnalyser.analyse(input, right).percentile(0.5)
        assertThat(l).isWithin(0.1).of(log2(0.01))
        assertThat(r).isWithin(0.1).of(log2(0.3))
    }

    @Test
    fun `the sample is point-sampled, so clipped pixels stay clipped`() {
        val input = SyntheticImages.clippedHighlights(1600, 1000)
        val sample = AutoAnalyser.sample(input)
        assertThat(maxOf(sample.width, sample.height)).isEqualTo(AutoAnalyser.ANALYSIS_SIDE)
        assertThat(sample.linearRgb.max()).isEqualTo(input.linearRgb.max())
        assertThat(AutoAnalyser.analyse(sample, Recipe()).clippedFraction).isGreaterThan(0.0)
    }

    @Test
    fun `display tables invert`() {
        val a = normal(-4.0)
        for (y in listOf(0.01, 0.18, 0.5, 0.9)) assertThat(a.display(a.sceneFor(y))).isWithin(1e-6).of(y)
    }

    private fun log2(v: Double) = kotlin.math.ln(v) / kotlin.math.ln(2.0)

    /** The scene key that Auto leaves at exposure 0 under Standard (the target grey's scene stop). */
    private fun AutoRules.run0Key(): Double = normal(-4.0).sceneFor(AutoParams().targetGrey)
}
