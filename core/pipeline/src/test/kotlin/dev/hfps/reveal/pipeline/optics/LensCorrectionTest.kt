package dev.hfps.reveal.pipeline.optics

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.Optics
import kotlin.math.hypot
import kotlin.math.pow
import org.junit.Test

class LensCorrectionTest {
    /** The public CC0 ZV-E10 sample's blocks (E PZ 16-50mm at 29 mm), as `SonyLensCorrection` reads them. */
    private val zvDistortion = listOf(11, 21, 2, -22, -49, -84, -124, -168, -208, -247, -279, -305, 0, 0, 0, 0, 0)
    private val zvVignetting = listOf(11, 0, 0, 128, 352, 736, 1184, 1920, 3200, 4768, 6528, 8384, 0, 0, 0, 0, 0)
    private val zvChromatic = listOf(
        22, -512, -512, -384, -384, -256, -256, -256, -128, 0, 128, 256,
        256, 768, 1024, 1280, 1408, 1408, 1408, 1280, 1024, 768, 512, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )
    private val zv = checkNotNull(SonyLensProfile.of("E PZ 16-50mm", zvDistortion, zvVignetting, zvChromatic))

    @Test
    fun `sony knots give the golden values`() {
        // Golden numbers (docs/adr/0015): knots at i / (n - 1), D * 2^-14, R/B * 2^-21, 2^(0.5 - 2^(V * 2^-13 - 1)).
        assertThat(zv.distortion(0.0)).isWithin(1e-12).of(1.00128173828125)
        assertThat(zv.distortion(0.5)).isWithin(1e-12).of(0.992431640625)
        assertThat(zv.distortion(0.77)).isWithin(1e-12).of(0.985638427734375)
        assertThat(zv.distortion(1.0)).isWithin(1e-12).of(0.98138427734375)
        assertThat(zv.distortion(1.2)).isWithin(1e-12).of(0.98138427734375)
        assertThat(zv.vignetting(0.0)).isWithin(1e-12).of(1.0)
        assertThat(zv.vignetting(0.5)).isWithin(1e-9).of(0.964139813391277)
        assertThat(zv.vignetting(1.0)).isWithin(1e-9).of(0.6991246873217006)
        assertThat(zv.chromatic(0, 0.5)).isWithin(1e-12).of(0.9998779296875)
        assertThat(zv.chromatic(2, 0.5)).isWithin(1e-12).of(1.00067138671875)
        assertThat(zv.chromatic(1, 0.5)).isEqualTo(1.0)
    }

    @Test
    fun `malformed sony blocks are dropped`() {
        assertThat(SonyLensProfile.of("x", listOf(11, 1, 2), null, null)).isNull()
        assertThat(SonyLensProfile.of("x", listOf(17) + List(17) { 0 }, null, null)).isNull()
        assertThat(SonyLensProfile.of("x", null, null, listOf(21) + List(21) { 0 })).isNull()
        val onlyVignetting = checkNotNull(SonyLensProfile.of("x", null, zvVignetting, null))
        assertThat(onlyVignetting.hasDistortion).isFalse()
        assertThat(onlyVignetting.hasVignetting).isTrue()
    }

    @Test
    fun `warp is null when nothing moves`() {
        assertThat(LensWarp.of(zv, Optics(), 600, 400)).isNull()
        assertThat(LensWarp.of(null, Optics(lensCorrections = true), 600, 400)).isNull()
        assertThat(LensWarp.of(zv, Optics(lensCorrections = true, distortionPercent = 0f), 600, 400)).isNull()
    }

    @Test
    fun `auto-scale keeps the whole border inside the source and touches it`() {
        for (optics in listOf(
            Optics(lensCorrections = true),
            Optics(lensCorrections = true, removeCa = true),
            Optics(lensCorrections = true, distortionPercent = 200f),
        )) {
            val warp = checkNotNull(LensWarp.of(zv, optics, 6000, 4000))
            var worst = 0.0
            val half = hypot(6000.0, 4000.0) / 2
            // Border points: x on the long edges, y on the short edges.
            for (k in 0..400) {
                val x = -3000.0 + 6000.0 * k / 400
                val y = -2000.0 + 4000.0 * k / 400
                for ((px, py) in listOf(x to 2000.0, 3000.0 to y)) {
                    val r = hypot(px, py) / half
                    for (c in 0 until 3) {
                        val l = warp.lambda(c, r.toFloat()).toDouble()
                        worst = maxOf(worst, maxOf(kotlin.math.abs(px * l) / 3000.0, kotlin.math.abs(py * l) / 2000.0))
                    }
                }
            }
            assertThat(worst).isAtMost(1.0 + 1e-4)
            assertThat(worst).isAtLeast(1.0 - 1e-3)
        }
    }

    @Test
    fun `amount scales the deviation from identity`() {
        val full = checkNotNull(LensWarp.of(zv, Optics(lensCorrections = true), 600, 400))
        val double = checkNotNull(LensWarp.of(zv, Optics(lensCorrections = true, distortionPercent = 200f), 600, 400))
        // Before auto-scale: l = 1 + a (d - 1); compare shapes through their ratio to the centre value.
        val shapeFull = full.lambda(1, 1f) / full.lambda(1, 0f) - 1
        val shapeDouble = double.lambda(1, 1f) / double.lambda(1, 0f) - 1
        assertThat(shapeDouble.toDouble()).isWithin(2e-3).of(2.0 * shapeFull)
    }

    @Test
    fun `chromatic tables differ per channel only when CA is removed`() {
        val plain = checkNotNull(LensWarp.of(zv, Optics(lensCorrections = true), 600, 400))
        assertThat(plain.chromatic).isFalse()
        assertThat(plain.lambda(0, 0.9f)).isEqualTo(plain.lambda(1, 0.9f))
        val ca = checkNotNull(LensWarp.of(zv, Optics(lensCorrections = true, removeCa = true), 600, 400))
        assertThat(ca.chromatic).isTrue()
        val ratio = ca.lambda(2, 0.5f) / ca.lambda(1, 0.5f)
        assertThat(ratio.toDouble()).isWithin(2e-6).of(1.00067138671875)
        // CA alone (no profile CA data) uses the recipe's estimate.
        val auto = checkNotNull(LensWarp.of(null, Optics(removeCa = true, caRed = 5f, caBlue = -3f), 600, 400))
        assertThat((auto.lambda(0, 0.7f) / auto.lambda(1, 0.7f)).toDouble()).isWithin(1e-6).of(1.0005)
        assertThat((auto.lambda(2, 0.7f) / auto.lambda(1, 0.7f)).toDouble()).isWithin(1e-6).of(0.9997)
    }

    @Test
    fun `vignette table inverts the illumination`() {
        val table = checkNotNull(VignetteTable.of(zv, Optics(lensCorrections = true)))
        assertThat(table.gain(0f).toDouble()).isWithin(1e-6).of(1.0)
        assertThat(table.gain(1f).toDouble()).isWithin(1e-5).of(1 / 0.6991246873217006)
        val half = checkNotNull(VignetteTable.of(zv, Optics(lensCorrections = true, vignettingPercent = 50f)))
        assertThat(half.gain(1f).toDouble()).isWithin(1e-5).of(1 / 0.6991246873217006.pow(0.5))
        assertThat(VignetteTable.of(zv, Optics())).isNull()
        assertThat(VignetteTable.of(zv, Optics(lensCorrections = true, vignettingPercent = 0f))).isNull()
    }

    @Test
    fun `lensfun models keep the centre and follow lensfun's formulas`() {
        // ptlens a, b, c of a calibration at crop 1.5 on a 3:2 sensor, used on the same camera.
        val p = LensfunProfile(
            "test", LensfunDistortion.PTLENS, listOf(0.01, -0.03, 0.0), LensfunTca.LINEAR, listOf(1.0003, 0.9998),
            listOf(-0.3, 0.1, -0.05), 1.5, 1.5, 1.5,
        )
        assertThat(p.distortion(0.0)).isWithin(1e-12).of(1.0)
        // Hugin radius of a half-diagonal radius r: r * hypot(1.5, 1); d = 1 - a - b - c.
        val r = 0.8
        val rho = r * hypot(1.5, 1.0)
        val d = 1 - 0.01 + 0.03
        val x = rho / d
        assertThat(p.distortion(r)).isWithin(1e-12).of((0.01 * x * x * x - 0.03 * x * x + d) / d)
        assertThat(p.chromatic(0, r)).isEqualTo(1.0003)
        assertThat(p.chromatic(2, r)).isEqualTo(0.9998)
        assertThat(p.vignetting(1.0)).isWithin(1e-12).of(1 - 0.3 + 0.1 - 0.05)
        // A full-frame calibration on an APS-C camera reads the inner part of the curve.
        val ff = p.copy(cropCalibration = 1.0)
        assertThat(ff.vignetting(1.0)).isWithin(1e-12).of(p.vignetting(1.0 / 1.5))
        val poly3 = p.copy(distortionModel = LensfunDistortion.POLY3, distortionTerms = listOf(-0.02))
        assertThat(poly3.distortion(0.0)).isWithin(1e-12).of(1.0)
        assertThat(poly3.distortion(r)).isWithin(1e-12).of(1 - 0.02 * rho * rho / 1.02.pow(3))
    }

    @Test
    fun `table lookup interpolates linearly and clamps`() {
        val t = FloatArray(LensTables.SIZE) { it.toFloat() }
        assertThat(LensTables.lookup(t, 1, 0, 0f)).isEqualTo(0f)
        assertThat(LensTables.lookup(t, 1, 0, LensTables.R_MAX)).isEqualTo((LensTables.SIZE - 1).toFloat())
        assertThat(LensTables.lookup(t, 1, 0, 10f)).isEqualTo((LensTables.SIZE - 1).toFloat())
        val r = 2.5f / LensTables.PER_RADIUS
        assertThat(LensTables.lookup(t, 1, 0, r).toDouble()).isWithin(1e-4).of(2.5)
    }
}
