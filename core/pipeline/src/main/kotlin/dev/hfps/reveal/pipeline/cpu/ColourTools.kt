@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import kotlin.math.sqrt

// CPU reference of the colour mixer (HSL), black & white and colour grading (slot 9, after vibrance/saturation),
// docs/adr/0013. All three work in OkLab (L, C, h) on linear Rec.2020 and are mirrored by `gl/ColourToolShaders.kt`.

/**
 * The 8 hue bands shared by the colour mixer, the grey mixer and the targeted adjustment. Band centres are OkLab hue
 * angles of typical colours of each name (sRGB red 29°, orange 53°, skin 45-65°, yellow 110°, foliage 135°, green
 * 142°, aqua 195°, sky 250°, blue 264°, purple 294°, magenta 328°).
 *
 * A per-band value becomes a function of hue by **smoothstep interpolation between neighbouring centres**: at hue `h`
 * between centres `c_k` and `c_k+1`, `t = (h - c_k) / gap_k`, `s = t² (3 - 2t)`, `V(h) = (1 - s) V_k + s V_k+1`.
 * The two weights are a partition of unity, so equal values on every band act like one global value; `V` is C1 in
 * hue (no kink a gradient could show as a band edge) and never leaves the range of the two neighbours (no ringing).
 */
object HueBands {
    /** Centres in degrees, in [ColourBand] order. */
    val CENTRES_DEGREES: DoubleArray = doubleArrayOf(25.0, 55.0, 105.0, 140.0, 195.0, 255.0, 295.0, 335.0)

    const val COUNT: Int = 8
    const val TWO_PI: Float = (2.0 * Math.PI).toFloat()

    /** Centres in radians (float, the shader's constants). */
    val CENTRES: FloatArray = FloatArray(COUNT) { Math.toRadians(CENTRES_DEGREES[it]).toFloat() }

    /** Distance from centre `k` to centre `k+1` (the last wraps to the first + 360°), radians. */
    val GAPS: DoubleArray = DoubleArray(COUNT) { k ->
        val next = if (k == COUNT - 1) CENTRES_DEGREES[0] + 360.0 else CENTRES_DEGREES[k + 1]
        Math.toRadians(next - CENTRES_DEGREES[k])
    }

    val GAPS_F: FloatArray = FloatArray(COUNT) { GAPS[it].toFloat() }
    val INV_GAPS: FloatArray = FloatArray(COUNT) { (1.0 / GAPS[it]).toFloat() }

    /** Segment of an OkLab hue [h] in radians (-π..π or 0..2π): `k` and the hue unwrapped to `c_0 .. c_0 + 2π`. */
    fun segment(h: Float): Int {
        val x = unwrap(h)
        var k = COUNT - 1
        for (j in 0 until COUNT - 1) {
            if (x < CENTRES[j + 1]) {
                k = j
                break
            }
        }
        return k
    }

    /** [h] moved into `c_0 .. c_0 + 2π`. */
    fun unwrap(h: Float): Float {
        var x = h
        if (x < CENTRES[0]) x += TWO_PI
        if (x >= CENTRES[0] + TWO_PI) x -= TWO_PI
        return x
    }

    /** `t` of [h] in its segment [k], 0..1. */
    fun position(h: Float, k: Int): Float = ((unwrap(h) - CENTRES[k]) * INV_GAPS[k]).coerceIn(0f, 1f)

    /** smoothstep's polynomial on 0..1. */
    fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    /** Hue-interpolated value of [values] (one per band) at hue [h]. */
    fun interpolate(values: FloatArray, h: Float): Float {
        val k = segment(h)
        val s = smooth(position(h, k))
        return values[k] + s * (values[(k + 1) % COUNT] - values[k])
    }

    /** The weight of every band at hue [h] (two neighbours share 1, the rest 0): the targeted adjustment's split. */
    fun weights(h: Float): FloatArray {
        val k = segment(h)
        val s = smooth(position(h, k))
        return FloatArray(COUNT).also {
            it[k] = 1f - s
            it[(k + 1) % COUNT] = s
        }
    }

    /** The band whose centre is nearest to [h]. */
    fun nearest(h: Float): ColourBand {
        val k = segment(h)
        return ColourBand.entries[if (position(h, k) < 0.5f) k else (k + 1) % COUNT]
    }

    /**
     * How strongly a colour of OkLab chroma [c] takes a hue-band luminance change: `q / (1 + q)`, `q = (c / C_HALF)²`.
     * 0 for neutrals (whose hue is noise), half at `C_HALF`, 0.86 at a typical saturated colour (0.15).
     */
    fun chromaWeight(c: Float): Float {
        val q = (c / C_HALF) * (c / C_HALF)
        return q / (1f + q)
    }

    const val C_HALF: Float = 0.06f
}

private const val PERCENT = 100.0
private const val MIN_CHROMA = 1e-7f

/**
 * The colour mixer's numbers: per band hue-map knots (`shift`, radians, and the map's tangent), saturation and
 * luminance (fractions -1..1). [hueActive] is false when no hue slider is moved (the map is skipped, so a pure
 * saturation/luminance edit does not round hues).
 */
class MixerParams(
    val shift: FloatArray,
    val tangent: FloatArray,
    val saturation: FloatArray,
    val luminance: FloatArray,
    val hueActive: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is MixerParams && shift.contentEquals(other.shift) &&
        tangent.contentEquals(other.tangent) && saturation.contentEquals(other.saturation) &&
        luminance.contentEquals(other.luminance) && hueActive == other.hueActive

    override fun hashCode(): Int = shift.contentHashCode() * 31 + saturation.contentHashCode() * 7 +
        luminance.contentHashCode()

    companion object {
        /** Fraction of the gap to the neighbouring centre a hue ±100 moves a band's centre. */
        const val HUE_AMOUNT: Double = 0.45

        /** [mixer] resolved; `null` when the mixer changes nothing. */
        fun from(mixer: ColourMixer): MixerParams? {
            if (mixer.isNeutral) return null
            val n = HueBands.COUNT
            val gaps = HueBands.GAPS
            val a = mixer.hue.toList().map { (it / PERCENT).coerceIn(-1.0, 1.0) }
            // Band centre k moves towards the next band (positive) or the previous one (negative) by a share of that
            // gap, so neighbours keep at least (1 - 2 HUE_AMOUNT) of their gap: the map stays strictly increasing.
            val shift = DoubleArray(n) { k -> a[k] * HUE_AMOUNT * if (a[k] > 0) gaps[k] else gaps[(k + n - 1) % n] }
            val secant = DoubleArray(n) { k -> (gaps[k] + shift[(k + 1) % n] - shift[k]) / gaps[k] }
            val tangent = DoubleArray(n) { k ->
                val prev = (k + n - 1) % n
                MonotoneSpline.butland(gaps[prev], gaps[k], secant[prev], secant[k])
            }
            return MixerParams(
                shift = FloatArray(n) { shift[it].toFloat() },
                tangent = FloatArray(n) { tangent[it].toFloat() },
                saturation = values(mixer.saturation),
                luminance = values(mixer.luminance),
                hueActive = !mixer.hue.isNeutral,
            )
        }

        /** Band values as fractions -1..1 in band order. */
        fun values(v: BandValues): FloatArray =
            FloatArray(HueBands.COUNT) { (v.toList()[it] / PERCENT.toFloat()).coerceIn(-1f, 1f) }
    }
}

/**
 * Colour mixer (HSL), slot 9 after vibrance/saturation; not applied in black & white. In OkLab, with the hue `h`,
 * chroma `C` and lightness `L` of the pixel and the band interpolation of [HueBands] (all evaluated at the input hue):
 *
 * ```
 * h' = Hue(h)                         monotone periodic Hermite through (c_k, c_k + shift_k), Fritsch-Butland tangents
 * C' = C (1 + S)                      S <= 0   (-100 = grey for that band)
 * C' = C (1 + S / (1 + (C/C_SOFT)²))  S > 0    (saturated colours grow less, as vibrance/saturation)
 * L' = L 2^(LUM_STOPS Lum chromaWeight(C))
 * ```
 *
 * The hue map is strictly increasing (its knots keep their order, [MixerParams.HUE_AMOUNT]), so a hue gradient never
 * folds or breaks; saturation keeps `C'` increasing in `C`; luminance is a gain on `L` that fades out for neutrals.
 */
object ColourMixerOp {
    /** OkLab-lightness exponent of luminance ±100 (`L` x 2^0.4: about ±1.2 stops of luminance). */
    const val LUM_STOPS: Float = 0.4f

    /** The mapped hue (radians) at position [t] of segment [k] (unwrapped, not reduced mod 2π). */
    fun mapHue(k: Int, t: Float, p: MixerParams): Float {
        val gap = HueBands.GAPS_F[k]
        val k1 = (k + 1) % HueBands.COUNT
        val t2 = t * t
        val t3 = t2 * t
        val y0 = p.shift[k]
        val y1 = gap + p.shift[k1]
        val rel = (2f * t3 - 3f * t2 + 1f) * y0 + (t3 - 2f * t2 + t) * (p.tangent[k] * gap) +
            (-2f * t3 + 3f * t2) * y1 + (t3 - t2) * (p.tangent[k1] * gap)
        return HueBands.CENTRES[k] + rel
    }

    /** Applies [p] to the OkLab pixel [lab] in place (neutral pixels are left exactly as they are). */
    fun applyLab(lab: FloatArray, p: MixerParams) {
        val a = lab[1]
        val b = lab[2]
        val c = sqrt(a * a + b * b)
        if (c <= MIN_CHROMA) return
        val h = StrictMath.atan2(b.toDouble(), a.toDouble()).toFloat()
        val k = HueBands.segment(h)
        val t = HueBands.position(h, k)
        val s = HueBands.smooth(t)
        val k1 = (k + 1) % HueBands.COUNT
        val sat = p.saturation[k] + s * (p.saturation[k1] - p.saturation[k])
        val lum = p.luminance[k] + s * (p.luminance[k1] - p.luminance[k])
        val q = c / VibranceSaturationOp.C_SOFT
        val fs = if (sat <= 0f) 1f + sat else 1f + sat / (1f + q * q)
        lab[0] = lab[0] * exp2(LUM_STOPS * lum * HueBands.chromaWeight(c))
        if (p.hueActive) {
            val h2 = mapHue(k, t, p).toDouble()
            val c2 = c * fs
            lab[1] = c2 * StrictMath.cos(h2).toFloat()
            lab[2] = c2 * StrictMath.sin(h2).toFloat()
        } else {
            lab[1] = a * fs
            lab[2] = b * fs
        }
    }

    /** Applies [p] to every pixel of [rgb] in place (its own OkLab round trip; the pipeline uses [ColourStageOp]). */
    fun apply(rgb: FloatArray, p: MixerParams) = roundTrip(rgb) { applyLab(it, p) }
}

/**
 * Black & white (the profile's [dev.hfps.reveal.pipeline.recipe.Treatment.BLACK_WHITE]), slot 9: the grey is the
 * pixel's OkLab lightness (perceptually even greys; a neutral pixel keeps its value exactly), brightened or darkened
 * per source colour by the grey mixer:
 *
 * ```
 * L' = L 2^(GREY_STOPS G(h) chromaWeight(C)),   rgb' = OkLab(L', 0, 0)
 * ```
 *
 * `G` is the band interpolation of the grey mixer (-1..1) at the pixel's hue; neutrals are untouched (no hue to read).
 */
object BlackWhiteOp {
    /** OkLab-lightness exponent of a grey mixer ±100 (`L` x 2^0.5: about ±1.5 stops of luminance). */
    const val GREY_STOPS: Float = 0.5f

    /** Applies the grey mixer [grey] (fractions in band order) to the OkLab pixel [lab] in place. */
    fun applyLab(lab: FloatArray, grey: FloatArray) {
        val a = lab[1]
        val b = lab[2]
        val c = sqrt(a * a + b * b)
        if (c > MIN_CHROMA) {
            val h = StrictMath.atan2(b.toDouble(), a.toDouble()).toFloat()
            lab[0] = lab[0] * exp2(GREY_STOPS * HueBands.interpolate(grey, h) * HueBands.chromaWeight(c))
        }
        lab[1] = 0f
        lab[2] = 0f
    }

    /** Applies the grey mixer to every pixel of [rgb] in place (own round trip; the pipeline uses [ColourStageOp]). */
    fun apply(rgb: FloatArray, grey: FloatArray) = roundTrip(rgb) { applyLab(it, grey) }
}

/**
 * Colour grading's numbers: the tint vectors (OkLab a, b per unit of lightness) of the shadows, midtones, highlights
 * and global wheels, their luminance fractions, and the tonal masks (split points `q1`, `q2` on OkLab `L`, half
 * widths of the tint and luminance transitions).
 */
class GradingParams(val tint: FloatArray, val luminance: FloatArray, val mask: FloatArray) {
    override fun equals(other: Any?): Boolean = other is GradingParams && tint.contentEquals(other.tint) &&
        luminance.contentEquals(other.luminance) && mask.contentEquals(other.mask)

    override fun hashCode(): Int = tint.contentHashCode() * 31 + luminance.contentHashCode() * 7 +
        mask.contentHashCode()

    companion object {
        /** Tint chroma per unit of OkLab L at saturation 100 (0.085 on mid grey). */
        const val TINT: Double = 0.15

        /** OkLab-lightness exponent of luminance ±100 (about ±0.6 stops). */
        const val LUM_STOPS: Float = 0.2f

        /** Pivot between the regions at balance 0 (OkLab L; mid grey is 0.565). */
        const val PIVOT: Double = 0.55

        /** How far balance ±100 moves the pivot (positive balance moves it down: more of the image is highlights). */
        const val BALANCE: Double = 0.2

        /** Distance of the shadow/midtone and midtone/highlight splits from the pivot. */
        const val SPLIT: Double = 0.25

        /** Half width of the tint transitions at blending 0 and 100. */
        const val BLEND_MIN: Double = 0.05
        const val BLEND_MAX: Double = 0.25

        /** [grading] resolved; `null` when no wheel changes anything. */
        fun from(grading: ColourGrading): GradingParams? {
            if (grading.isNeutral) return null
            val tint = FloatArray(8)
            val lum = FloatArray(4)
            GradeRegion.entries.forEachIndexed { r, region ->
                val w = grading[region]
                val s = (w.saturation / PERCENT).coerceIn(0.0, 1.0) * TINT
                val h = Math.toRadians(w.hue.toDouble())
                tint[2 * r] = (s * StrictMath.cos(h)).toFloat()
                tint[2 * r + 1] = (s * StrictMath.sin(h)).toFloat()
                lum[r] = (w.luminance / PERCENT).coerceIn(-1.0, 1.0).toFloat()
            }
            val balance = (grading.balance / PERCENT).coerceIn(-1.0, 1.0)
            val blend = (grading.blending / PERCENT).coerceIn(0.0, 1.0)
            val pivot = PIVOT - BALANCE * balance
            val mask = floatArrayOf(
                (pivot - SPLIT).toFloat(),
                (pivot + SPLIT).toFloat(),
                (BLEND_MIN + (BLEND_MAX - BLEND_MIN) * blend).toFloat(),
                BLEND_MAX.toFloat(),
            )
            return GradingParams(tint, lum, mask)
        }
    }
}

/**
 * Colour grading, slot 9 after the mixer / black & white (so a B&W picture can be toned). Tonal masks on the OkLab
 * lightness `x = clamp(L, 0, 1)`, a partition of unity:
 *
 * ```
 * shadows = 1 - smoothstep(q1 - w, q1 + w, x),  highlights = smoothstep(q2 - w, q2 + w, x),  midtones = the rest
 * ```
 *
 * with `w` from blending for the tints and fixed at [GradingParams.BLEND_MAX] for luminance (wide luminance masks keep
 * `L'` increasing in `L` for every slider combination: `|d mask/dx| <= 3`, so `1 - ln2 LUM_STOPS 6 > 0`). Then
 *
 * ```
 * L' = L 2^(LUM_STOPS (Σ mask_r lum_r + lum_global))
 * (a, b) += L' (Σ mask_r tint_r + tint_global)       tint_r = TINT sat_r (cos hue_r, sin hue_r)
 * ```
 *
 * The tint is added in proportion to the new lightness, so black stays black and a tint has the same strength
 * relative to the pixel's lightness everywhere.
 */
object ColourGradingOp {
    /** GLSL `smoothstep` written out (same on both sides). */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Applies [p] to the OkLab pixel [lab] in place. */
    fun applyLab(lab: FloatArray, p: GradingParams) {
        val m = p.mask
        val t = p.tint
        val lu = p.luminance
        val x = lab[0].coerceIn(0f, 1f)
        val sw = 1f - smoothstep(m[0] - m[2], m[0] + m[2], x)
        val hw = smoothstep(m[1] - m[2], m[1] + m[2], x)
        val mw = 1f - sw - hw
        val swl = 1f - smoothstep(m[0] - m[3], m[0] + m[3], x)
        val hwl = smoothstep(m[1] - m[3], m[1] + m[3], x)
        val mwl = 1f - swl - hwl
        val l = lab[0] * exp2(GradingParams.LUM_STOPS * (swl * lu[0] + mwl * lu[1] + hwl * lu[2] + lu[3]))
        val ta = sw * t[0] + mw * t[2] + hw * t[4] + t[6]
        val tb = sw * t[1] + mw * t[3] + hw * t[5] + t[7]
        lab[0] = l
        lab[1] = lab[1] + l * ta
        lab[2] = lab[2] + l * tb
    }

    /** Applies [p] to every pixel of [rgb] in place (own round trip; the pipeline uses [ColourStageOp]). */
    fun apply(rgb: FloatArray, p: GradingParams) = roundTrip(rgb) { applyLab(it, p) }
}

/**
 * Slot 9 with any colour tool on: vibrance/saturation, then the colour mixer or black & white, then grading, all on one
 * OkLab round trip per pixel (the conversions between them cancel; one round trip instead of up to three is what keeps
 * the develop pass inside the phone's frame budget). Without a colour tool the stage is [VibranceSaturationOp] alone,
 * exactly as before (ADR 0008).
 */
object ColourStageOp {
    /** Applies [colour] and [tools] (at least one tool on) to every pixel of [rgb] in place. */
    fun apply(rgb: FloatArray, colour: ColourParams, tools: ColourToolParams) {
        roundTrip(rgb) { lab ->
            if (colour.enabled) vibranceSaturationLab(lab, colour)
            tools.mixer?.let { ColourMixerOp.applyLab(lab, it) }
            tools.grey?.let { BlackWhiteOp.applyLab(lab, it.values) }
            tools.grading?.let { ColourGradingOp.applyLab(lab, it) }
        }
    }

    /** [VibranceSaturationOp] on an OkLab pixel. */
    private fun vibranceSaturationLab(lab: FloatArray, p: ColourParams) {
        val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        if (c <= MIN_CHROMA) return
        val k = VibranceSaturationOp.gain(c, lab[1] / c, lab[2] / c, p)
        lab[1] *= k
        lab[2] *= k
    }
}

/** Every pixel of [rgb] to OkLab, through [op], and back, in place. */
private inline fun roundTrip(rgb: FloatArray, op: (FloatArray) -> Unit) {
    val lab = FloatArray(3)
    var i = 0
    while (i < rgb.size) {
        OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], lab)
        op(lab)
        OkLab.toRgb(lab[0], lab[1], lab[2], lab)
        rgb[i] = lab[0]
        rgb[i + 1] = lab[1]
        rgb[i + 2] = lab[2]
        i += 3
    }
}

/**
 * Slot 9's colour tools for one recipe: the colour [mixer] (`null` = off, and always off in B&W), the B&W [grey]
 * mixer (`null` = colour treatment) and [grading] (`null` = off).
 */
data class ColourToolParams(
    val mixer: MixerParams? = null,
    val grey: GreyParams? = null,
    val grading: GradingParams? = null,
) {
    /** True when any of the three is on (slot 9 then runs [ColourStageOp]). */
    val active: Boolean get() = mixer != null || grey != null || grading != null

    companion object {
        val NONE: ColourToolParams = ColourToolParams()
    }
}

/** The grey mixer as fractions -1..1 in band order. */
class GreyParams(val values: FloatArray) {
    override fun equals(other: Any?): Boolean = other is GreyParams && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        fun from(values: BandValues): GreyParams = GreyParams(MixerParams.values(values))
    }
}

/** `2^x` through StrictMath (deterministic on every JVM). */
internal fun exp2(x: Float): Float = StrictMath.pow(2.0, x.toDouble()).toFloat()

/** The OkLab hue (radians) and chroma of a linear Rec.2020 colour, for the targeted adjustment and Auto mix. */
fun okLabHueChroma(r: Float, g: Float, b: Float): Pair<Float, Float> {
    val lab = FloatArray(3)
    OkLab.fromRgb(r, g, b, lab)
    val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    return StrictMath.atan2(lab[2].toDouble(), lab[1].toDouble()).toFloat() to c
}

/** Index of [band] in the operators' arrays. */
val ColourBand.index: Int get() = ordinal
