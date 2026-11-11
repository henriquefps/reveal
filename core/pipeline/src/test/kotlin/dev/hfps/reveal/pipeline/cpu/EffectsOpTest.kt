@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Test

/**
 * The Effects (docs/adr/0014) on synthetic images: defaults are the identity; texture and clarity change local
 * contrast without halos at strong edges; dehaze moves a hazy image towards the haze-free truth and negative dehaze
 * adds haze; the vignette follows the crop and its shape sliders; grain is deterministic, zero-mean and the same
 * pattern at every output size. Measured values are printed (`EFFECTS ...`) for the ADR.
 */
class EffectsOpTest {
    private fun lightness(input: PipelineInput, recipe: Recipe, scale: Float = 1f): FloatArray {
        val rgb = CpuDevelop.renderLinear(input, recipe, scale)
        return lightnessOf(rgb)
    }

    private fun lightnessOf(rgb: FloatArray): FloatArray {
        val t = FloatArray(3)
        return FloatArray(rgb.size / 3) {
            OkLab.fromRgb(rgb[it * 3], rgb[it * 3 + 1], rgb[it * 3 + 2], t)
            t[0]
        }
    }

    private fun std(l: FloatArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var s = 0.0
        var s2 = 0.0
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val v = l[y * w + x].toDouble()
                s += v
                s2 += v * v
                n++
            }
        }
        val m = s / n
        return sqrt(max(s2 / n - m * m, 0.0))
    }

    private fun mean(l: FloatArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var s = 0.0
        for (y in y0 until y1) for (x in x0 until x1) s += l[y * w + x]
        return s / ((x1 - x0) * (y1 - y0))
    }

    @Test
    fun `defaults and shaping sliders without an amount are the identity`() {
        val shaping = Effects(vignetteMidpoint = 10f, vignetteRoundness = 80f, vignetteFeather = 90f, grainSize = 90f)
        val params = EffectsParams.from(shaping, 1f)
        assertThat(params.localActive).isFalse()
        assertThat(params.post.active).isFalse()
        val image = SyntheticImages.scene(96, 64)
        assertThat(CpuDevelop.render(image, Recipe(effects = shaping))).isEqualTo(CpuDevelop.render(image, Recipe()))
        assertThat(CpuDevelop.renderLinear(image, Recipe(effects = shaping)))
            .isEqualTo(CpuDevelop.renderLinear(image, Recipe()))
    }

    @Test
    fun `texture changes medium detail and leaves strong edges alone`() {
        val w = 192
        val h = 128
        val card = SyntheticImages.noisyCard(w, h, seed = 1, noise = 0f)
        val base = lightness(card, Recipe())
        val more = lightness(card, Recipe(effects = Effects(texture = 100f)))
        val less = lightness(card, Recipe(effects = Effects(texture = -100f)))
        // The textured bottom quarter (period 8 px), away from the edge.
        val stdBase = std(base, w, 8, w / 2 - 12, h * 3 / 4 + 4, h - 4)
        val stdMore = std(more, w, 8, w / 2 - 12, h * 3 / 4 + 4, h - 4)
        val stdLess = std(less, w, 8, w / 2 - 12, h * 3 / 4 + 4, h - 4)
        // The step edge (mid rows, no texture there): the change on the pixels next to it.
        var halo = 0f
        for (y in h / 2 - 4 until h / 2 + 4) {
            for (dx in -6..5) {
                val i = y * w + w / 2 + dx
                halo = max(halo, max(abs(more[i] - base[i]), abs(less[i] - base[i])))
            }
        }
        println("EFFECTS texture: texture std $stdBase -> +100 $stdMore, -100 $stdLess; edge change max $halo")
        assertThat(stdMore).isGreaterThan(stdBase * 1.3)
        assertThat(stdLess).isLessThan(stdBase * 0.6)
        assertThat(halo).isLessThan(0.01f)
    }

    @Test
    fun `clarity raises midtone local contrast without a halo at a strong edge`() {
        val w = 768
        val h = 256
        // 0.06 / 0.24: a two-stop edge with a +-0.25 stop texture of period 8 px on both sides.
        val image = SyntheticImages.stepEdge(w, h, 0.06f, 0.24f, texture = 0.25f)
        val base = lightness(image, Recipe())
        val more = lightness(image, Recipe(effects = Effects(clarity = 100f)))
        val less = lightness(image, Recipe(effects = Effects(clarity = -100f)))
        val y0 = h / 4
        val y1 = h * 3 / 4
        val farL = w / 4 - 32
        val farR = w * 3 / 4
        val stdBase = std(base, w, farL, farL + 64, y0, y1)
        val stdMore = std(more, w, farL, farL + 64, y0, y1)
        val stdLess = std(less, w, farL, farL + 64, y0, y1)

        // Halo: the local mean shift (a window of whole texture periods) next to the edge minus far from it.
        fun shift(img: FloatArray, x0: Int) = mean(img, w, x0, x0 + 16, y0, y1) - mean(base, w, x0, x0 + 16, y0, y1)
        var halo = 0.0
        for (img in listOf(more, less)) {
            halo = maxOf(halo, abs(shift(img, w / 2 - 16) - shift(img, farL)))
            halo = maxOf(halo, abs(shift(img, w / 2) - shift(img, farR)))
        }
        println("EFFECTS clarity: texture std $stdBase -> +100 $stdMore, -100 $stdLess; halo (mean shift) $halo")
        assertThat(stdMore).isGreaterThan(stdBase * 1.25)
        assertThat(stdLess).isLessThan(stdBase * 0.8)
        assertThat(halo).isLessThan(0.01)
    }

    @Test
    fun `dehaze moves a hazy image towards the haze-free truth and negative dehaze adds haze`() {
        val w = 384
        val h = 256
        val hazy = SyntheticImages.hazy(w, h)
        val truth = CpuDevelop.renderLinear(SyntheticImages.hazy(w, h, truth = true), Recipe())
        val before = CpuDevelop.renderLinear(hazy, Recipe())
        val after = CpuDevelop.renderLinear(hazy, Recipe(effects = Effects(dehaze = 100f)))
        val added = CpuDevelop.renderLinear(hazy, Recipe(effects = Effects(dehaze = -100f)))
        fun rms(a: FloatArray): Double {
            var s = 0.0
            // The lower, hazier-than-nothing land rows (the sky band is mostly haze colour already).
            val from = (h * 0.2f).toInt() * w * 3
            for (i in from until a.size) s += (a[i] - truth[i]).toDouble().let { it * it }
            return sqrt(s / (a.size - from))
        }
        fun darkChannel(a: FloatArray): Double {
            var s = 0.0
            for (i in 0 until a.size / 3) s += minOf(a[i * 3], minOf(a[i * 3 + 1], a[i * 3 + 2]))
            return s / (a.size / 3)
        }
        val grid = EffectsGrid.build(DetailOp.toLab(before, w * h), LowResGrid(w, h))
        println(
            "EFFECTS dehaze: RMS to truth ${rms(before)} -> +100 ${rms(after)}; dark channel ${darkChannel(before)}" +
                " -> +100 ${darkChannel(after)}, -100 ${darkChannel(added)}; air ${grid.air.toList()}",
        )
        assertThat(rms(after)).isLessThan(0.6 * rms(before))
        assertThat(darkChannel(after)).isLessThan(0.6 * darkChannel(before))
        assertThat(darkChannel(added)).isGreaterThan(darkChannel(before) + 0.05)
        // The atmospheric light is the haze's colour: bright and bluish.
        assertThat(grid.air[2]).isGreaterThan(grid.air[0])
        assertThat(grid.air[1]).isGreaterThan(0.5f)
    }

    @Test
    fun `the vignette darkens the corners of the crop, keeps the centre and its shape follows the sliders`() {
        val w = 300
        val h = 200
        val flat = PipelineInput.linearSrgb(w, h, FloatArray(w * h * 3) { 0.18f })
        val dark = CpuDevelop.renderGeometry(flat, Recipe(effects = Effects(vignetteAmount = -100f)))
        val l = lightnessOf(dark)
        val centre = l[(h / 2) * w + w / 2]
        val corner = l[0]
        val edge = l[(h / 2) * w]
        val base = lightnessOf(CpuDevelop.renderGeometry(flat, Recipe()))[0]
        println("EFFECTS vignette -100: centre $centre, edge middle $edge, corner $corner (flat $base)")
        assertThat(abs(centre - base)).isLessThan(1e-4f)
        assertThat(corner).isLessThan(0.35f * base)
        assertThat(edge).isLessThan(base)
        assertThat(edge).isGreaterThan(corner)
        // Post-crop: the vignette of a cropped render is centred on the crop.
        val crop =
            Recipe(geometry = Geometry(crop = CropRect(0f, 0f, 0.5f, 1f)), effects = Effects(vignetteAmount = -80f))
        val mapping = CpuDevelop.mapping(flat, crop)
        val cl = lightnessOf(CpuDevelop.renderGeometry(flat, crop, mapping))
        val ow = mapping.outputWidth
        val oh = mapping.outputHeight
        assertThat(abs(cl[(oh / 2) * ow + ow / 2] - base)).isLessThan(1e-4f)
        assertThat(cl[0]).isWithin(1e-5f).of(cl[ow - 1])
        // Roundness +100 is a circle in pixels: equal distances from the centre get equal masks.
        val round = lightnessOf(
            CpuDevelop.renderGeometry(
                flat,
                Recipe(effects = Effects(vignetteAmount = -100f, vignetteRoundness = 100f)),
            ),
        )
        val r = 80
        assertThat(round[(h / 2) * w + w / 2 + r]).isWithin(2e-3f).of(round[(h / 2 + r) * w + w / 2])
        // Highlights protection keeps a bright pixel brighter under the same darkening.
        val bright = PipelineInput.linearSrgb(w, h, FloatArray(w * h * 3) { 0.85f })
        val plain = lightnessOf(CpuDevelop.renderGeometry(bright, Recipe(effects = Effects(vignetteAmount = -100f))))
        val kept = lightnessOf(
            CpuDevelop.renderGeometry(
                bright,
                Recipe(effects = Effects(vignetteAmount = -100f, vignetteHighlights = 100f)),
            ),
        )
        assertThat(kept[0]).isGreaterThan(plain[0] + 0.1f)
        // Positive amounts lighten towards white.
        val light = lightnessOf(CpuDevelop.renderGeometry(flat, Recipe(effects = Effects(vignetteAmount = 100f))))
        assertThat(light[0]).isGreaterThan(base + 0.2f)
    }

    @Test
    fun `grain is deterministic, zero mean and the same pattern at every output size`() {
        val p = PostParams.from(Effects(grainAmount = 100f, grainSize = 50f, grainRoughness = 50f))
        // The same normalised point at 2x the size: o' = 2 o and gk' = gk / 2, so the lattice point is identical.
        val gk = p.grainScale(1000)
        val gk2 = p.grainScale(2000)
        assertThat(gk2).isEqualTo(gk / 2f)
        for (i in 0 until 50) {
            val o = 3.5f + i * 7f
            val a = floatArrayOf(0.2f, 0.2f, 0.2f)
            val b = floatArrayOf(0.2f, 0.2f, 0.2f)
            PostCropOp.pixel(a, o, o * 0.5f, 1000f, 500f, gk, p)
            PostCropOp.pixel(b, 2f * o, o, 2000f, 1000f, gk2, p)
            assertThat(a).isEqualTo(b)
        }
        val w = 256
        val h = 256
        val grey = FloatArray(w * h * 3) { 0.18f }
        val once = grey.copyOf()
        val twice = grey.copyOf()
        PostCropOp.apply(once, w, h, p)
        PostCropOp.apply(twice, w, h, p)
        assertThat(once).isEqualTo(twice)
        val l = lightnessOf(once)
        val base = lightnessOf(grey)[0]
        val m = mean(l, w, 0, w, 0, h)
        val s = std(l, w, 0, w, 0, h)
        val half = FloatArray(w * h * 3) { 0.18f }
        PostCropOp.apply(half, w, h, PostParams.from(Effects(grainAmount = 50f, grainSize = 50f, grainRoughness = 50f)))
        val sHalf = std(lightnessOf(half), w, 0, w, 0, h)
        println("EFFECTS grain 100: lightness mean ${m - base}, std $s; amount 50 std $sHalf")
        assertThat(abs(m - base)).isLessThan(0.1 * s)
        assertThat(s).isGreaterThan(0.01)
        assertThat(sHalf / s).isWithin(0.05).of(0.5)
    }

    @Test
    fun `local effects at a proxy scale approximate the full resolution result`() {
        val w = 384
        val h = 256
        val full = SyntheticImages.noisyCard(w, h, seed = 2, noise = 0.03f)
        val small = halve(full)
        val effects = Effects(texture = 60f, clarity = 60f, dehaze = 30f)
        val fullThenSmall = halveL(lightness(full, Recipe(effects = effects)), w, h)
        val proxy = lightness(small, Recipe(effects = effects), 2f)
        val baseline = halveL(lightness(full, Recipe()), w, h)
        val proxyBase = lightness(small, Recipe(), 2f)
        var d = 0.0
        var d0 = 0.0
        for (i in proxy.indices) {
            d += (fullThenSmall[i] - proxy[i]).toDouble().let { it * it }
            d0 += (baseline[i] - proxyBase[i]).toDouble().let { it * it }
        }
        val rms = sqrt(d / proxy.size)
        val rms0 = sqrt(d0 / proxy.size)
        println("EFFECTS proxy vs 100 %: L RMS $rms (without effects $rms0)")
        assertThat(rms).isLessThan(rms0 + 0.01)
    }

    @Suppress("NestedBlockDepth")
    private fun halve(image: PipelineInput): PipelineInput {
        val w = image.width / 2
        val h = image.height / 2
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0 until 3) {
                    var s = 0f
                    for (k in 0 until 4) {
                        s += image.linearRgb[((2 * y + k / 2) * image.width + 2 * x + k % 2) * 3 + c]
                    }
                    out[(y * w + x) * 3 + c] = s / 4f
                }
            }
        }
        return PipelineInput(image.kind, w, h, out, image.cameraToXyz, image.asShotMultipliers, image.saturation)
    }

    private fun halveL(l: FloatArray, w: Int, h: Int): FloatArray {
        val hw = w / 2
        return FloatArray(hw * (h / 2)) {
            val x = it % hw
            val y = it / hw
            (
                l[2 * y * w + 2 * x] + l[2 * y * w + 2 * x + 1] + l[(2 * y + 1) * w + 2 * x] +
                    l[(2 * y + 1) * w + 2 * x + 1]
                ) /
                4f
        }
    }
}
