@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// CPU reference of the profile (slot 6) and colour (slot 9) operators, docs/adr/0008. Mirrored line for line by the
// GLSL in `gl/ColourShaders.kt`; GpuParityTest holds them together.

/**
 * The resolved profile (slot 6) for one recipe: what [ProfileOp] needs. Scene-referred input only; for
 * display-referred input [enabled] is false and the stage is the identity (ADR 0002, slot 6).
 *
 * @property pre row-major 3x3 from linear Rec.2020 into the curve's space, with the profile gain (baseline exposure)
 *   folded in.
 * @property curve the per-channel tone curve applied in that space.
 * @property post row-major 3x3 from the curve's space back to linear Rec.2020.
 * @property hueChroma 7 Fourier coefficients of the hue-dependent chroma gain (OkLab), see [HueTreatment].
 * @property hueShift 7 Fourier coefficients of the hue rotation in radians.
 * @property hueEnabled false when every hue coefficient is 0 (the stage skips the OkLab round trip).
 */
class ProfileParams(
    val enabled: Boolean,
    val pre: FloatArray,
    val curve: ToneCurve,
    val post: FloatArray,
    val hueChroma: FloatArray,
    val hueShift: FloatArray,
) {
    val hueEnabled: Boolean get() = hueChroma.any { it != 0f } || hueShift.any { it != 0f }

    override fun equals(other: Any?): Boolean = other is ProfileParams && enabled == other.enabled &&
        pre.contentEquals(other.pre) && curve == other.curve && post.contentEquals(other.post) &&
        hueChroma.contentEquals(other.hueChroma) && hueShift.contentEquals(other.hueShift)

    override fun hashCode(): Int {
        var h = enabled.hashCode()
        h = h * HASH + pre.contentHashCode()
        h = h * HASH + curve.hashCode()
        h = h * HASH + post.contentHashCode()
        h = h * HASH + hueChroma.contentHashCode()
        return h * HASH + hueShift.contentHashCode()
    }

    companion object {
        private const val HASH = 31

        /** The identity profile (display-referred input). */
        val IDENTITY: ProfileParams = ProfileParams(
            enabled = false,
            pre = Mat3.IDENTITY.toFloatArray(),
            curve = ToneCurve(FloatArray(ToneCurve.KNOTS), FloatArray(ToneCurve.KNOTS)),
            post = Mat3.IDENTITY.toFloatArray(),
            hueChroma = FloatArray(HueTreatment.COEFFICIENTS),
            hueShift = FloatArray(HueTreatment.COEFFICIENTS),
        )
    }
}

/**
 * Hue-dependent chroma gain and hue rotation in OkLab, as order-3 Fourier series of the hue angle `h`:
 *
 * ```
 * gain(h) = max(0, 1 + c0 + c1 cos h + c2 sin h + c3 cos 2h + c4 sin 2h + c5 cos 3h + c6 sin 3h)
 * dh(h)   =        d0 + d1 cos h + d2 sin h + d3 cos 2h + d4 sin 2h + d5 cos 3h + d6 sin 3h      (radians)
 * a' = gain C cos(h + dh),  b' = gain C sin(h + dh),  L' = L
 * ```
 *
 * `cos kh`, `sin kh` come from `a/C`, `b/C` by the double/triple-angle identities (no `atan2`). Neutral pixels
 * (`C = 0`) are untouched, lightness is untouched. A raised-cosine lobe `amp ((1 + cos(h - h0))/2)^3` is exactly an
 * order-3 series ([lobe]), which is how the designed looks are written.
 */
object HueTreatment {
    /** Coefficients per series. */
    const val COEFFICIENTS: Int = 7

    private const val MIN_CHROMA = 1e-7f

    /** Order-3 Fourier coefficients of `amp ((1 + cos(h - h0))/2)^3`, [hueDegrees] = h0. */
    fun lobe(amp: Double, hueDegrees: Double): DoubleArray {
        val h0 = Math.toRadians(hueDegrees)
        // ((1 + cos x)/2)^3 = 5/16 + 15/32 cos x + 3/16 cos 2x + 1/32 cos 3x, and cos k(h - h0) expands into
        // cos kh cos kh0 + sin kh sin kh0.
        val k = doubleArrayOf(5.0 / 16.0, 15.0 / 32.0, 3.0 / 16.0, 1.0 / 32.0)
        return doubleArrayOf(
            amp * k[0],
            amp * k[1] * StrictMath.cos(h0),
            amp * k[1] * StrictMath.sin(h0),
            amp * k[2] * StrictMath.cos(2 * h0),
            amp * k[2] * StrictMath.sin(2 * h0),
            amp * k[3] * StrictMath.cos(3 * h0),
            amp * k[3] * StrictMath.sin(3 * h0),
        )
    }

    /** Applies the treatment to the Lab pixel in [lab] (in place). */
    fun apply(lab: FloatArray, chroma: FloatArray, shift: FloatArray) {
        val a = lab[1]
        val b = lab[2]
        val c = sqrt(a * a + b * b)
        if (c <= MIN_CHROMA) return
        val c1 = a / c
        val s1 = b / c
        val c2 = c1 * c1 - s1 * s1
        val s2 = 2f * c1 * s1
        val c3 = c2 * c1 - s2 * s1
        val s3 = s2 * c1 + c2 * s1
        val gain = max(
            0f,
            1f + chroma[0] + chroma[1] * c1 + chroma[2] * s1 + chroma[3] * c2 + chroma[4] * s2 + chroma[5] * c3 +
                chroma[6] * s3,
        )
        val dh = shift[0] + shift[1] * c1 + shift[2] * s1 + shift[3] * c2 + shift[4] * s2 + shift[5] * c3 +
            shift[6] * s3
        val cd = StrictMath.cos(dh.toDouble()).toFloat()
        val sd = StrictMath.sin(dh.toDouble()).toFloat()
        lab[1] = gain * (a * cd - b * sd)
        lab[2] = gain * (a * sd + b * cd)
    }
}

/**
 * Slot 6: the profile (base look), scene-referred input only. Per pixel:
 *
 * ```
 * v = pre * rgb                     (gain + look matrix into the curve space)
 * v = (curve(v.r), curve(v.g), curve(v.b))
 * v = post * v                      (back to linear Rec.2020)
 * v = HueTreatment(v) in OkLab      (when any coefficient is non-zero)
 * ```
 *
 * Per-channel curves give the familiar filmic behaviour (bright saturated colours desaturate towards white instead
 * of clipping to a flat hue), as camera JPEG engines do; [ProfileLooks] documents every look's numbers.
 */
object ProfileOp {
    /** Applies [p] to every pixel of [rgb] in place. */
    fun apply(rgb: FloatArray, p: ProfileParams) {
        if (!p.enabled) return
        val pre = p.pre
        val post = p.post
        val lab = FloatArray(3)
        val hue = p.hueEnabled
        var i = 0
        while (i < rgb.size) {
            val r0 = rgb[i]
            val g0 = rgb[i + 1]
            val b0 = rgb[i + 2]
            val r1 = p.curve.eval(pre[0] * r0 + pre[1] * g0 + pre[2] * b0)
            val g1 = p.curve.eval(pre[3] * r0 + pre[4] * g0 + pre[5] * b0)
            val b1 = p.curve.eval(pre[6] * r0 + pre[7] * g0 + pre[8] * b0)
            rgb[i] = post[0] * r1 + post[1] * g1 + post[2] * b1
            rgb[i + 1] = post[3] * r1 + post[4] * g1 + post[5] * b1
            rgb[i + 2] = post[6] * r1 + post[7] * g1 + post[8] * b1
            if (hue) {
                OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], lab)
                HueTreatment.apply(lab, p.hueChroma, p.hueShift)
                OkLab.toRgb(lab[0], lab[1], lab[2], lab)
                rgb[i] = lab[0]
                rgb[i + 1] = lab[1]
                rgb[i + 2] = lab[2]
            }
            i += 3
        }
    }
}

/**
 * Slot 9 parameters: [saturation] and [vibrance] as fractions (-1..1, the recipe's -100..+100 / 100).
 * [enabled] is false when both are 0 (the stage is skipped).
 */
data class ColourParams(val saturation: Float = 0f, val vibrance: Float = 0f) {
    val enabled: Boolean get() = saturation != 0f || vibrance != 0f

    companion object {
        /** No change. */
        val NONE: ColourParams = ColourParams()
    }
}

/**
 * Slot 9: vibrance and saturation, a chroma scale in OkLab (L and hue kept exactly, so no hue shift and no
 * brightness change). With `C` the OkLab chroma, `s` = saturation and `v` = vibrance in -1..1:
 *
 * ```
 * fs = 1 + s                                     s <= 0  (-100 = grey)
 * fs = 1 + s / (1 + (C / C_SOFT)²)               s > 0   (soft: saturated colours grow less, no gamut blow-out)
 * w  = (1 - min(C / C_VIB, 1))²                  vibrance weight: muted colours most, saturated ones not at all
 * p  = 1 - SKIN_PROTECT ((1 + cos(h - SKIN_HUE))/2)^4   for v > 0 (skin hues protected), else 1
 * fv = 1 + v w p
 * C' = C fs fv
 * ```
 *
 * `C' (C)` stays increasing for every slider position (`d(C fs)/dC >= 1 - s/8`, `d(C fv)/dC >= 1 - v/3`), so the
 * order of saturation between colours never flips. `C_SOFT = 0.2` and `C_VIB = 0.25` are about the OkLab chroma of
 * a strongly saturated sRGB colour (sRGB primaries are 0.26-0.32); `SKIN_HUE = 55°` is the OkLab hue of typical
 * skin tones (light to dark skin measure 45-65°) and `SKIN_PROTECT = 0.6`. Values pushed outside Rec.2020 stay
 * negative here and are clipped by the output transform.
 */
object VibranceSaturationOp {
    const val C_SOFT: Float = 0.2f
    const val C_VIB: Float = 0.25f
    const val SKIN_HUE_DEGREES: Double = 55.0
    const val SKIN_PROTECT: Float = 0.6f
    val SKIN_COS: Float = StrictMath.cos(Math.toRadians(SKIN_HUE_DEGREES)).toFloat()
    val SKIN_SIN: Float = StrictMath.sin(Math.toRadians(SKIN_HUE_DEGREES)).toFloat()
    private const val MIN_CHROMA = 1e-7f

    /** The chroma gain for a colour of chroma [c] and hue (cos, sin) = ([ch], [sh]). */
    fun gain(c: Float, ch: Float, sh: Float, p: ColourParams): Float {
        val s = p.saturation
        val q = c / C_SOFT
        val fs = if (s <= 0f) 1f + s else 1f + s / (1f + q * q)
        val u = 1f - min(c / C_VIB, 1f)
        val w = u * u
        var protect = 1f
        if (p.vibrance > 0f) {
            val lobe = (1f + ch * SKIN_COS + sh * SKIN_SIN) * 0.5f
            val l2 = lobe * lobe
            protect = 1f - SKIN_PROTECT * (l2 * l2)
        }
        val fv = 1f + p.vibrance * w * protect
        return fs * fv
    }

    /** Applies [p] to every pixel of [rgb] in place. */
    fun apply(rgb: FloatArray, p: ColourParams) {
        if (!p.enabled) return
        val lab = FloatArray(3)
        var i = 0
        while (i < rgb.size) {
            OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], lab)
            val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            if (c > MIN_CHROMA) {
                val k = gain(c, lab[1] / c, lab[2] / c, p)
                OkLab.toRgb(lab[0], lab[1] * k, lab[2] * k, lab)
                rgb[i] = lab[0]
                rgb[i + 1] = lab[1]
                rgb[i + 2] = lab[2]
            }
            i += 3
        }
    }
}
