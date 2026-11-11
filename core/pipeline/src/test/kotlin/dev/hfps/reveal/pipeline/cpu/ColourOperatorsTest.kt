package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Test

class ColourOperatorsTest {
    private val srgbToRec2020 = (ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz).toFloatArray()

    private fun rec2020(r: Float, g: Float, b: Float): FloatArray {
        val m = srgbToRec2020
        return floatArrayOf(
            m[0] * r + m[1] * g + m[2] * b,
            m[3] * r + m[4] * g + m[5] * b,
            m[6] * r + m[7] * g + m[8] * b,
        )
    }

    private fun lab(rgb: FloatArray, i: Int = 0) = FloatArray(3).also {
        OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], it)
    }

    private fun chroma(l: FloatArray) = sqrt(l[1] * l[1] + l[2] * l[2])

    private fun hue(l: FloatArray) = atan2(l[2], l[1])

    // ------------------------------------------------------------------ OkLab

    @Test
    fun `OkLab matches Ottosson's reference values`() {
        // Reference: sRGB red (1, 0, 0) is OkLab (0.6280, 0.2249, 0.1258); white is (1, 0, 0).
        val red = lab(rec2020(1f, 0f, 0f))
        assertThat(red[0]).isWithin(5e-4f).of(0.62796f)
        assertThat(red[1]).isWithin(5e-4f).of(0.22486f)
        assertThat(red[2]).isWithin(5e-4f).of(0.12585f)
        val white = lab(floatArrayOf(1f, 1f, 1f))
        assertThat(white[0]).isWithin(1e-4f).of(1f)
        assertThat(abs(white[1]) + abs(white[2])).isLessThan(1e-4f)
    }

    @Test
    fun `OkLab round trips, negatives included`() {
        val random = Random(3)
        repeat(500) {
            val rgb = FloatArray(3) { random.nextFloat() * 2f - 0.2f }
            val l = lab(rgb)
            val back = FloatArray(3).also { OkLab.toRgb(l[0], l[1], l[2], it) }
            for (c in 0..2) assertThat(back[c]).isWithin(1e-5f + 1e-5f * abs(rgb[c])).of(rgb[c])
        }
    }

    // ------------------------------------------------------------------ ToneCurve

    @Test
    fun `designed curves are monotone, bounded and C1 at the toe and the knots`() {
        for (look in listOf(ProfileLooks.STANDARD_FILMIC, ProfileLooks.NEUTRAL_FILMIC, ProfileLooks.VIVID_FILMIC)) {
            val curve = look.curve()
            var previous = curve.eval(-0.01f)
            var x = -0.005f
            while (x < 100f) {
                val y = curve.eval(x)
                assertThat(y).isAtLeast(previous)
                assertThat(y).isLessThan(1f)
                previous = y
                x = if (x < 1e-4f) x + 1e-4f else x * 1.003f
            }
            for (k in listOf(ToneCurve.X0, ToneCurve.knotX(5).toFloat(), ToneCurve.knotX(10).toFloat())) {
                val h = k * 1e-3f
                val left = (curve.eval(k) - curve.eval(k - h)) / h
                val right = (curve.eval(k + h) - curve.eval(k)) / h
                assertThat(abs(left - right)).isAtMost(2e-2f * maxOf(abs(left), 1e-3f))
            }
        }
    }

    @Test
    fun `Standard maps grey to grey after its gain`() {
        val f = ProfileLooks.STANDARD_FILMIC
        val grey = (ProfileLooks.GREY / f.gain).toFloat()
        val p = ProfileLooks.resolve(dev.hfps.reveal.pipeline.recipe.Profile())
        val rgb = floatArrayOf(grey, grey, grey)
        ProfileOp.apply(rgb, p)
        for (v in rgb) assertThat(v).isWithin(1e-3f).of(0.18f)
    }

    @Test
    fun `limited makes any knot set monotone and extrapolated blends stay monotone`() {
        val random = Random(9)
        val bad = DoubleArray(ToneCurve.KNOTS) { random.nextDouble() }
        val curve = ToneCurve.limited(bad, DoubleArray(ToneCurve.KNOTS) { random.nextDouble() * 5 - 1 })
        var previous = -1f
        var x = 1e-4f
        while (x < 64f) {
            val y = curve.eval(x)
            assertThat(y).isAtLeast(previous - 1e-6f)
            previous = y
            x *= 1.01f
        }
        val blend = ToneCurve.lerp(ProfileLooks.STANDARD_FILMIC.curve(), ProfileLooks.NEUTRAL_FILMIC.curve(), 2.0)
        previous = -1f
        x = 1e-4f
        while (x < 64f) {
            val y = blend.eval(x)
            assertThat(y).isAtLeast(previous - 1e-6f)
            previous = y
            x *= 1.01f
        }
    }

    // ------------------------------------------------------------------ HueTreatment

    @Test
    fun `a lobe is exactly the raised cosine cubed`() {
        val coeffs = HueTreatment.lobe(0.5, 140.0)
        for (deg in 0 until 360 step 7) {
            val h = Math.toRadians(deg.toDouble())
            val basis = doubleArrayOf(
                1.0,
                kotlin.math.cos(h),
                kotlin.math.sin(h),
                kotlin.math.cos(2 * h),
                kotlin.math.sin(2 * h),
                kotlin.math.cos(3 * h),
                kotlin.math.sin(3 * h),
            )
            val series = coeffs.indices.sumOf { coeffs[it] * basis[it] }
            val c = (1 + kotlin.math.cos(h - Math.toRadians(140.0))) / 2
            assertThat(series).isWithin(1e-12).of(0.5 * c * c * c)
        }
    }

    @Test
    fun `hue treatment leaves neutrals and lightness alone`() {
        val chroma = HueTreatment.lobe(0.3, 50.0).map { it.toFloat() }.toFloatArray()
        val shift = HueTreatment.lobe(0.2, 200.0).map { it.toFloat() }.toFloatArray()
        val grey = floatArrayOf(0.6f, 0f, 0f)
        HueTreatment.apply(grey, chroma, shift)
        assertThat(grey).isEqualTo(floatArrayOf(0.6f, 0f, 0f))
        val colour = lab(rec2020(0.2f, 0.5f, 0.1f))
        val l0 = colour[0]
        HueTreatment.apply(colour, chroma, shift)
        assertThat(colour[0]).isEqualTo(l0)
    }

    // ------------------------------------------------------------------ Vibrance / saturation

    private fun applyVs(rgb: FloatArray, sat: Float, vib: Float): FloatArray =
        rgb.copyOf().also { VibranceSaturationOp.apply(it, ColourParams(sat, vib)) }

    @Test
    fun `zero vibrance and saturation is the identity`() {
        val rgb = rec2020(0.3f, 0.2f, 0.7f)
        assertThat(applyVs(rgb, 0f, 0f)).isEqualTo(rgb)
    }

    @Test
    fun `saturation minus 100 is grey with the same OkLab lightness`() {
        val rgb = rec2020(0.8f, 0.3f, 0.1f)
        val out = applyVs(rgb, -1f, 0f)
        assertThat(out[0]).isWithin(1e-5f).of(out[1])
        assertThat(out[2]).isWithin(1e-5f).of(out[1])
        assertThat(lab(out)[0]).isWithin(1e-5f).of(lab(rgb)[0])
    }

    @Test
    fun `saturation and vibrance keep hue and lightness`() {
        val random = Random(5)
        repeat(200) {
            val rgb = FloatArray(3) { random.nextFloat() * 0.9f + 0.02f }
            for ((s, v) in listOf(0.6f to 0f, -0.4f to 0f, 0f to 1f, 0f to -0.7f, 1f to 1f)) {
                val before = lab(rgb)
                val after = lab(applyVs(rgb, s, v))
                assertThat(after[0]).isWithin(1e-4f).of(before[0])
                if (chroma(before) > 1e-3f && chroma(after) > 1e-3f) {
                    var d = hue(after) - hue(before)
                    if (d > Math.PI) d -= (2 * Math.PI).toFloat()
                    if (d < -Math.PI) d += (2 * Math.PI).toFloat()
                    assertThat(abs(d)).isLessThan(2e-3f)
                }
            }
        }
    }

    @Test
    fun `positive saturation is soft on already saturated colours and keeps their order`() {
        val muted = rec2020(0.5f, 0.45f, 0.4f)
        val vivid = rec2020(0.9f, 0.1f, 0.05f)
        val gMuted = chroma(lab(applyVs(muted, 1f, 0f))) / chroma(lab(muted))
        val gVivid = chroma(lab(applyVs(vivid, 1f, 0f))) / chroma(lab(vivid))
        assertThat(gMuted).isWithin(0.02f).of(2f)
        assertThat(gVivid).isLessThan(1.6f)
        assertThat(chroma(lab(applyVs(vivid, 1f, 0f)))).isGreaterThan(chroma(lab(applyVs(muted, 1f, 0f))))
    }

    @Test
    fun `vibrance lifts muted colours more than saturated ones and protects skin`() {
        val muted = rec2020(0.35f, 0.4f, 0.5f)
        val vivid = rec2020(0.1f, 0.2f, 0.9f)
        val gMuted = chroma(lab(applyVs(muted, 0f, 1f))) / chroma(lab(muted))
        val gVivid = chroma(lab(applyVs(vivid, 0f, 1f))) / chroma(lab(vivid))
        assertThat(gMuted).isGreaterThan(gVivid)
        // A skin tone and a blue of similar chroma: the skin is boosted less.
        val skin = rec2020(0.62f, 0.42f, 0.33f)
        val blue = rec2020(0.36f, 0.42f, 0.58f)
        val cs = chroma(lab(skin))
        val cb = chroma(lab(blue))
        assertThat(abs(cs - cb) / cs).isLessThan(0.35f)
        val gSkin = chroma(lab(applyVs(skin, 0f, 1f))) / cs
        val gBlue = chroma(lab(applyVs(blue, 0f, 1f))) / cb
        assertThat(gSkin).isLessThan(gBlue - 0.15f)
    }

    @Test
    fun `chroma stays increasing in the input chroma for every slider extreme`() {
        for ((s, v) in listOf(1f to 1f, 1f to -1f, -0.5f to 1f, 0.3f to 0.5f)) {
            var previous = -1f
            var c = 0.001f
            while (c < 0.5f) {
                val out = c * VibranceSaturationOp.gain(c, 1f, 0f, ColourParams(s, v))
                assertThat(out).isAtLeast(previous)
                previous = out
                c += 0.002f
            }
        }
    }
}
