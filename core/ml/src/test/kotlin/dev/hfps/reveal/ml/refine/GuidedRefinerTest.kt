package dev.hfps.reveal.ml.refine

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.Mask
import dev.hfps.reveal.ml.MlImage
import kotlin.math.abs
import org.junit.Test

class GuidedRefinerTest {
    /** A guide with a sharp vertical edge at [edge]: dark left, bright right. */
    private fun edgeGuide(w: Int, h: Int, edge: Int) = MlImage(
        w,
        h,
        FloatArray(w * h * 3) { i -> if ((i / 3) % w < edge) 0.1f else 0.9f },
    )

    @Test
    fun `a coarse blurry mask snaps to the guide's edge`() {
        val w = 256
        val h = 64
        val guide = edgeGuide(w, h, 131)
        // The model's mask: 32 px wide, a soft ramp over two of its pixels (16 px of the guide) around the edge.
        val coarse = Mask(32, 8, FloatArray(32 * 8) { i -> (((i % 32) - 16f) / 2f + 0.5f).coerceIn(0f, 1f) })
        val upsampled = coarse.resized(w, h)
        val refined = GuidedRefiner.refine(guide, coarse, GuidedRefiner.Params(subsample = 2, radius = 8, eps = 1e-3f))
        // The upsampled mask is a ramp (6 % per pixel); the refined one steps where the guide does.
        val y = h / 2
        assertThat(upsampled[131, y] - upsampled[130, y]).isLessThan(0.1f)
        assertThat(refined[131, y] - refined[130, y]).isGreaterThan(0.6f)
        assertThat(refined[124, y]).isLessThan(0.15f)
        assertThat(refined[138, y]).isGreaterThan(0.8f)
    }

    @Test
    fun `a flat guide leaves a smooth mask smooth`() {
        val guide = MlImage(64, 64, FloatArray(64 * 64 * 3) { 0.5f })
        val mask = Mask(16, 16, FloatArray(256) { (it % 16) / 15f })
        val refined = GuidedRefiner.refine(guide, mask, GuidedRefiner.Params(subsample = 1, radius = 2))
        val reference = mask.resized(64, 64)
        val err = refined.values.indices.maxOf { abs(refined.values[it] - reference.values[it]) }
        assertThat(err).isLessThan(0.08f)
    }

    @Test
    fun `a mask that is a linear function of the guide is reproduced`() {
        val w = 48
        val h = 32
        val rgb = FloatArray(w * h * 3) { i -> ((i * 37 % 97) / 96f) }
        val guide = MlImage(w, h, rgb)
        val p = FloatArray(w * h) { i -> (0.2f * rgb[i * 3] + 0.5f * rgb[i * 3 + 1] + 0.1f * rgb[i * 3 + 2] + 0.1f) }
        val refined = GuidedRefiner.refine(
            guide,
            Mask(w, h, p),
            GuidedRefiner.Params(subsample = 1, radius = 3, eps = 1e-6f),
        )
        for (i in p.indices) assertThat(refined.values[i]).isWithin(2e-3f).of(p[i])
    }

    @Test
    fun `coefficients are at the reduced size and apply matches refine`() {
        val guide = edgeGuide(100, 40, 50)
        val mask = Mask(10, 4, FloatArray(40) { if (it % 10 >= 5) 1f else 0f })
        val k = GuidedRefiner.coefficients(guide, mask, GuidedRefiner.Params(subsample = 4))
        assertThat(k.width).isEqualTo(25)
        assertThat(k.height).isEqualTo(10)
        assertThat(k.rgba.size).isEqualTo(25 * 10 * 4)
        assertThat(GuidedRefiner.apply(guide, k).values).isEqualTo(
            GuidedRefiner.refine(guide, mask, GuidedRefiner.Params(subsample = 4)).values,
        )
    }
}
