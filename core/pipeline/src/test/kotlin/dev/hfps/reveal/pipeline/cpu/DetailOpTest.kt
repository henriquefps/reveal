@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Test

/**
 * The Detail operator (slot 11, docs/adr/0012) on the synthetic high-ISO card: noise reduction removes most of the
 * noise while keeping the step edge and the line, colour NR removes colour noise, sharpening raises edge contrast and
 * masking keeps it off flat areas; defaults are the identity; the proxy (scale-aware) approximates 100 %.
 * Measured values are printed (`DETAIL ...`) for the ADR.
 */
class DetailOpTest {
    private val clean = SyntheticImages.noisyCard(W, H, seed = 1, noise = 0f)
    private val noisy = SyntheticImages.noisyCard(W, H, seed = 1)

    private fun lab(input: PipelineInput, detail: Detail, scale: Float = 1f): FloatArray {
        val rgb = CpuDevelop.renderLinear(input, Recipe(detail = detail), scale)
        val out = FloatArray(rgb.size)
        val t = FloatArray(3)
        for (i in 0 until rgb.size / 3) {
            OkLab.fromRgb(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2], t)
            t.copyInto(out, i * 3)
        }
        return out
    }

    /** RMS of `a - b` over channel [c] (0 = L, 1-2 = a, b) in the flat parts (rows 4..H/4, away from the edge). */
    private fun flatRms(a: FloatArray, b: FloatArray, c: Int, w: Int = W, h: Int = H): Double {
        var sum = 0.0
        var n = 0
        for (y in 4 until h / 4) {
            for (x in 4 until w - 4) {
                if (abs(x - w / 2) < 8 || y == h / 3) continue
                val i = (y * w + x) * 3
                val d = if (c == 0) (a[i] - b[i]).toDouble() else {
                    val da = a[i + 1] - b[i + 1]
                    val db = a[i + 2] - b[i + 2]
                    sqrt((da * da + db * db).toDouble())
                }
                sum += d * d
                n++
            }
        }
        return sqrt(sum / n)
    }

    /** L contrast across the step edge on row [y]: mean of 3 px right of it minus 3 px left of it. */
    private fun edgeStep(l: FloatArray, y: Int): Float {
        var left = 0f
        var right = 0f
        for (k in 1..3) {
            left += l[(y * W + W / 2 - k) * 3]
            right += l[(y * W + W / 2 + k - 1) * 3]
        }
        return (right - left) / 3f
    }

    @Test
    fun `defaults and shaping sliders without an amount are the identity`() {
        assertThat(DetailParams.from(Detail(), 1f).active).isFalse()
        val shaped = Detail(sharpenRadius = 3f, sharpenDetail = 0f, sharpenMasking = 80f, nrLuminanceDetail = 0f)
        assertThat(DetailParams.from(shaped, 1f).active).isFalse()
        val rgb = noisy.linearRgb.copyOf()
        DetailOp.apply(rgb, W, H, DetailParams.from(shaped, 1f))
        assertThat(rgb).isEqualTo(noisy.linearRgb)
    }

    @Test
    fun `luminance noise reduction removes most noise and keeps the edge`() {
        val truth = lab(clean, Detail())
        val before = lab(noisy, Detail())
        val after = lab(noisy, Detail(nrLuminance = 60f))
        val rmsBefore = flatRms(before, truth, 0)
        val rmsAfter = flatRms(after, truth, 0)
        val edgeTruth = edgeStep(truth, H / 2)
        val edgeAfter = edgeStep(after, H / 2)
        println("DETAIL luminance NR 60: flat L RMS $rmsBefore -> $rmsAfter, edge step $edgeTruth -> $edgeAfter")
        assertThat(rmsAfter).isLessThan(rmsBefore * 0.4)
        assertThat(abs(edgeAfter - edgeTruth)).isLessThan(0.1f * edgeTruth)
        // Higher Luminance detail keeps more (of the noise and the texture).
        val detailed = lab(noisy, Detail(nrLuminance = 60f, nrLuminanceDetail = 100f))
        assertThat(flatRms(detailed, truth, 0)).isGreaterThan(rmsAfter)
    }

    @Test
    fun `colour noise reduction removes colour noise`() {
        val truth = lab(clean, Detail())
        val before = lab(noisy, Detail())
        val after = lab(noisy, Detail(nrColour = 60f))
        val rmsBefore = flatRms(before, truth, 1)
        val rmsAfter = flatRms(after, truth, 1)
        println("DETAIL colour NR 60: flat chroma RMS $rmsBefore -> $rmsAfter")
        assertThat(rmsAfter).isLessThan(rmsBefore * 0.4)
        // Luminance is left alone by colour NR (only its float16 storage).
        assertThat(flatRms(after, before, 0)).isLessThan(2e-3)
    }

    @Test
    fun `sharpening raises edge contrast and masking keeps flat areas untouched`() {
        val base = lab(clean, Detail())
        val sharp = lab(clean, Detail(sharpenAmount = 100f))
        // Overshoot next to the edge: the bright side gets brighter right at the edge.
        val y = H / 2
        val right = sharp[(y * W + W / 2) * 3] - base[(y * W + W / 2) * 3]
        val left = sharp[(y * W + W / 2 - 1) * 3] - base[(y * W + W / 2 - 1) * 3]
        println("DETAIL sharpen 100: edge pixels change $left / $right")
        assertThat(right).isGreaterThan(0.01f)
        assertThat(left).isLessThan(-0.01f)
        val noisyBase = lab(noisy, Detail())
        val everywhere = lab(noisy, Detail(sharpenAmount = 100f))
        val masked = lab(noisy, Detail(sharpenAmount = 100f, sharpenMasking = 100f))
        val flatAll = flatRms(everywhere, noisyBase, 0)
        val flatMasked = flatRms(masked, noisyBase, 0)
        println("DETAIL masking 100: flat L change $flatAll -> $flatMasked")
        assertThat(flatMasked).isLessThan(flatAll * 0.25)
    }

    @Test
    fun `low detail suppresses halos and texture coring`() {
        val p0 = DetailParams.from(Detail(sharpenAmount = 100f, sharpenDetail = 0f), 1f)
        val p100 = DetailParams.from(Detail(sharpenAmount = 100f, sharpenDetail = 100f), 1f)
        // A large high-pass (an edge) is limited more at detail 0; a tiny one (texture) is cored away.
        assertThat(DetailOp.shape(0.2f, p0)).isLessThan(DetailOp.shape(0.2f, p100))
        assertThat(DetailOp.shape(0.002f, p0)).isLessThan(0.3f * DetailOp.shape(0.002f, p100))
        // Monotonic in h.
        var last = -1f
        for (i in -100..100) {
            val v = DetailOp.shape(i / 200f, p0)
            assertThat(v).isAtLeast(last)
            last = v
        }
    }

    @Test
    fun `levels telescope back to the input without shrinkage`() {
        // A threshold so small that every detail is kept: only float16 storage remains.
        val p = DetailParams.from(Detail(nrLuminance = 0.01f), 1f)
        val rgb = noisy.linearRgb.copyOf()
        CpuDevelop.renderLinear(noisy, Recipe()).copyInto(rgb)
        val before = rgb.copyOf()
        DetailOp.apply(rgb, W, H, p)
        var worst = 0f
        for (i in rgb.indices) worst = maxOf(worst, abs(rgb[i] - before[i]) / maxOf(abs(before[i]), 0.05f))
        println("DETAIL telescoping: worst relative change $worst")
        assertThat(worst).isLessThan(4e-3f)
    }

    @Test
    fun `scale keeps full resolution lengths`() {
        val full = DetailParams.from(Detail(nrLuminance = 50f, sharpenAmount = 50f), 1f)
        assertThat(full.step).containsExactly(1, 2, 4, 8, 16).inOrder()
        assertThat(full.blurRadius).isEqualTo(3)
        assertThat(full.reach).isEqualTo(2 * 31 + 3)
        val proxy = DetailParams.from(Detail(nrLuminance = 50f, sharpenAmount = 50f), 2.5f)
        // Level 0 (1 px at 100 %) is below a proxy pixel: averaged away by the proxy's binning.
        assertThat(proxy.step).containsExactly(0, 1, 2, 3, 6).inOrder()
        // Radius 1 px is 0.4 proxy px: held at 0.5 with the amount reduced in proportion.
        assertThat(proxy.sharpenGain).isWithin(1e-6f).of(full.sharpenGain * 0.8f)
        assertThat(proxy.gradientScale).isWithin(1e-6f).of(0.4f)
        val widest = DetailParams.from(
            Detail(nrLuminance = 1f, nrColour = 1f, nrColourSmoothness = 100f, sharpenAmount = 1f, sharpenRadius = 3f,
                sharpenMasking = 1f),
            1f,
        )
        assertThat(widest.reach).isEqualTo(DetailParams.MAX_REACH)
    }

    @Test
    fun `the proxy approximates the full resolution result`() {
        val k = 2
        val small = halve(noisy)
        val detail = Detail(nrLuminance = 50f, nrColour = 50f, sharpenAmount = 60f)
        // Full resolution developed then reduced, against the proxy developed at its scale.
        val fullThenSmall = halveLab(lab(noisy, detail))
        val proxy = lab(small, detail, k.toFloat())
        val baseline = halveLab(lab(noisy, Detail()))
        val proxyBase = lab(small, Detail(), k.toFloat())
        val sw = W / k
        val sh = H / k
        val withDetail = flatRms(fullThenSmall, proxy, 0, sw, sh)
        val without = flatRms(baseline, proxyBase, 0, sw, sh)
        // The noise left in each (against the clean card reduced the same way).
        val truth = halveLab(lab(clean, Detail()))
        val noiseFull = flatRms(fullThenSmall, truth, 0, sw, sh)
        val noiseProxy = flatRms(proxy, truth, 0, sw, sh)
        println(
            "DETAIL proxy vs 100 %: L RMS $withDetail (without Detail $without); residual noise full $noiseFull, " +
                "proxy $noiseProxy",
        )
        assertThat(noiseProxy).isLessThan(2.0 * noiseFull + 0.002)
        assertThat(withDetail).isLessThan(without + 0.01)
    }

    private fun halve(image: PipelineInput): PipelineInput {
        val w = image.width / 2
        val h = image.height / 2
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            var s = 0f
            for (dy in 0..1) for (dx in 0..1) s += image.linearRgb[((2 * y + dy) * image.width + 2 * x + dx) * 3 + c]
            out[(y * w + x) * 3 + c] = s / 4f
        }
        return PipelineInput(image.kind, w, h, out, image.cameraToXyz, image.asShotMultipliers, image.saturation)
    }

    private fun halveLab(lab: FloatArray): FloatArray {
        val w = W / 2
        val h = H / 2
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            var s = 0f
            for (dy in 0..1) for (dx in 0..1) s += lab[((2 * y + dy) * W + 2 * x + dx) * 3 + c]
            out[(y * w + x) * 3 + c] = s / 4f
        }
        return out
    }

    private companion object {
        const val W = 192
        const val H = 128
    }
}
