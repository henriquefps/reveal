package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import dev.hfps.reveal.pipeline.optics.VignetteTable
import dev.hfps.reveal.pipeline.recipe.Optics
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Test

class OpticsOpsTest {
    private val zv = checkNotNull(
        SonyLensProfile.of(
            "E PZ 16-50mm",
            null,
            listOf(11, 0, 0, 128, 352, 736, 1184, 1920, 3200, 4768, 6528, 8384),
            null,
        ),
    )

    @Test
    fun `vignetting correction flattens the lens's own falloff`() {
        val w = 90
        val h = 60
        val half = hypot(w.toDouble(), h.toDouble()) / 2
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = hypot(x + 0.5 - w / 2.0, y + 0.5 - h / 2.0) / half
                val v = (0.5 * zv.vignetting(r)).toFloat()
                rgb.fill(v, (y * w + x) * 3, (y * w + x) * 3 + 3)
            }
        }
        VignetteOp.apply(rgb, w, h, checkNotNull(VignetteTable.of(zv, Optics(lensCorrections = true))))
        // The table interpolates linearly every 1.5/255 of radius: within 1e-4 of flat.
        for (v in rgb) assertThat(v.toDouble()).isWithin(1e-4).of(0.5)
    }

    @Test
    fun `vignetting of a tile equals the same pixels of the whole image`() {
        val table = checkNotNull(VignetteTable.of(zv, Optics(lensCorrections = true)))
        val whole = FloatArray(40 * 30 * 3) { 0.3f }
        VignetteOp.apply(whole, 40, 30, table)
        val tile = FloatArray(10 * 8 * 3) { 0.3f }
        VignetteOp.apply(tile, 10, 8, table, originX = 25, originY = 17, imageWidth = 40, imageHeight = 30)
        for (y in 0 until 8) {
            for (x in 0 until 10) {
                assertThat(tile[(y * 10 + x) * 3]).isEqualTo(whole[((y + 17) * 40 + x + 25) * 3])
            }
        }
    }

    /** A dark/bright vertical edge with a purple fringe on the bright side, a purple patch far away, a green fringe. */
    private fun fringeScene(w: Int, h: Int): FloatArray {
        val rgb = FloatArray(w * h * 3)
        val purple = floatArrayOf(0.35f, 0.12f, 0.55f)
        val green = floatArrayOf(0.2f, 0.45f, 0.12f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = when {
                    x < 20 -> floatArrayOf(0.02f, 0.02f, 0.02f)
                    x < 22 && y < h / 2 -> purple
                    x < 22 -> green
                    x in 34..55 && y in 3..20 -> purple
                    else -> floatArrayOf(0.8f, 0.8f, 0.8f)
                }
                c.copyInto(rgb, (y * w + x) * 3)
            }
        }
        return rgb
    }

    private fun chroma(rgb: FloatArray, w: Int, x: Int, y: Int): Float {
        val lab = FloatArray(3)
        val i = (y * w + x) * 3
        OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], lab)
        return sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    }

    @Test
    fun `defringe desaturates fringes at edges only`() {
        val w = 60
        val h = 24
        val before = fringeScene(w, h)
        val after = before.copyOf()
        DefringeOp.apply(after, w, h, DefringeParams.from(Optics(purpleAmount = 20f), 1f))
        // The purple fringe at the edge loses almost all its colour.
        assertThat(chroma(after, w, 20, 4)).isLessThan(0.05f * chroma(before, w, 20, 4))
        // Inside a purple object, further than the radius from any edge, the colour stays (only its rim, which is an
        // edge, is desaturated: the operator cannot tell a purple rim from a fringe, as in other editors).
        assertThat(chroma(after, w, 44, 12)).isWithin(1e-3f).of(chroma(before, w, 44, 12))
        // Green fringe untouched by the purple amount; neutral pixels untouched.
        assertThat(chroma(after, w, 21, 20)).isWithin(1e-3f).of(chroma(before, w, 21, 20))
        // Neutral pixels only see the float16 storage of OkLab (as on the GPU).
        assertThat(after[(2 * w + 40) * 3]).isWithin(1e-3f).of(0.8f)
        val green = before.copyOf()
        DefringeOp.apply(green, w, h, DefringeParams.from(Optics(greenAmount = 20f), 1f))
        assertThat(chroma(green, w, 21, 20)).isLessThan(0.05f * chroma(before, w, 21, 20))
        assertThat(chroma(green, w, 20, 4)).isWithin(1e-3f).of(chroma(before, w, 20, 4))
    }

    @Test
    fun `defringe is the identity at amount zero and keeps lightness`() {
        val w = 60
        val h = 24
        val rgb = fringeScene(w, h)
        val copy = rgb.copyOf()
        DefringeOp.apply(copy, w, h, DefringeParams.from(Optics(), 1f))
        assertThat(copy).isEqualTo(rgb)
        DefringeOp.apply(copy, w, h, DefringeParams.from(Optics(purpleAmount = 20f), 1f))
        val a = FloatArray(3)
        val b = FloatArray(3)
        val i = (4 * w + 20) * 3
        OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], a)
        OkLab.fromRgb(copy[i], copy[i + 1], copy[i + 2], b)
        assertThat(b[0]).isWithin(2e-3f).of(a[0])
    }

    @Test
    fun `defringe radius scales with the proxy and reach covers it`() {
        val full = DefringeParams.from(Optics(purpleAmount = 5f), 1f)
        assertThat(full.radius).isEqualTo(3)
        assertThat(full.reach).isEqualTo(3)
        assertThat(DefringeParams.from(Optics(purpleAmount = 5f), 2.5f).radius).isEqualTo(1)
        assertThat(DefringeParams.from(Optics(), 1f).reach).isEqualTo(0)
    }
}
