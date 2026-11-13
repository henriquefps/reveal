@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.mask.MaskContext
import dev.hfps.reveal.pipeline.mask.MaskParams
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_BLACKS
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_CLARITY
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_CONTRAST
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_DEHAZE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_HIGHLIGHTS
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_NOISE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_SHADOWS
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_SHARPEN
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_TEXTURE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.G_WHITES
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.VECTOR
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_BLACKS
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_CLARITY
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_CONTRAST
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_DEHAZE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_EXPOSURE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_HIGHLIGHTS
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_HUE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_NOISE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_SATURATION
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_SHADOWS
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_SHARPNESS
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_TEMPERATURE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_TEXTURE
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_TINT
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_TINT_A
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_TINT_B
import dev.hfps.reveal.pipeline.mask.MaskParams.Companion.V_WHITES
import dev.hfps.reveal.pipeline.mask.MaskPlan
import dev.hfps.reveal.pipeline.mask.MaskRaster
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

// CPU reference of the local adjustments (docs/adr/0022, "Local adjustments"): each one is the global operator it
// mirrors with a per-pixel amount. Mirrored line for line by `gl/MaskShaders.kt` and the masked branches of
// `Shaders.DEVELOP_FRAG` and `DetailShaders`.

/**
 * The rendered masks' coverage over an image region and the per-pixel local sum `S = Σ_i coverage_i · adjust_i` (mask
 * order, component by component, as the shader's `localSum`).
 */
class LocalField(val params: MaskParams, val coverage: List<FloatArray>) {
    /** Writes the local sum at pixel [i] into [out] ([VECTOR] floats); false (and zeros) when no mask covers it. */
    fun sum(i: Int, out: FloatArray): Boolean {
        out.fill(0f)
        var any = false
        val adjust = params.adjust
        for (m in params.masks.indices) {
            val c = coverage[m][i]
            if (c == 0f) continue
            any = true
            val o = m * VECTOR
            for (k in 0 until VECTOR) out[k] += c * adjust[o + k]
        }
        return any
    }

    /** The global slider value at `G_` index [g]. */
    fun global(g: Int): Float = params.global[g]
}

/**
 * Local temperature and tint (docs/adr/0022): the display-referred relative white balance (`WhiteBalanceModel
 * .relativeCct`, a Bradford adaptation from the pretended light to D65) in linear Rec.2020, written
 * `c' = A^-1 diag(g(t, n)) A c` with `A` = Bradford x Rec.2020-to-XYZ. The cone gains `g` are tabulated over the two
 * sliders (21 x 21 nodes, step 10, computed in double) and interpolated bilinearly with explicit weights (GLSL
 * `localWhiteBalance` reads the same table as a texture).
 */
object LocalWhiteBalance {
    /** Table nodes per axis. */
    const val NODES: Int = 21

    /** Slider units between nodes. */
    const val STEP: Float = 10f

    private const val MIN = -100f

    /** `A` (row-major): Rec.2020 to Bradford cone space. */
    val toCone: FloatArray

    /** `A^-1` (row-major). */
    val fromCone: FloatArray

    /** The gains, `(t index * NODES + n index) * 3` (t = temperature, n = tint). */
    val table: FloatArray

    init {
        val a = ColorSpaces.bradford * ColorSpaces.REC2020.toXyz
        toCone = a.toFloatArray()
        fromCone = a.inverse().toFloatArray()
        val dst = ColorSpaces.bradford.times(
            WhiteBalanceModel.illuminantXyz(WhiteBalanceModel.D65_CCT.temperatureK, WhiteBalanceModel.D65_CCT.tint),
        )
        table = FloatArray(NODES * NODES * 3)
        for (ti in 0 until NODES) {
            for (ni in 0 until NODES) {
                val t = MIN + STEP * ti
                val n = MIN + STEP * ni
                val cct = WhiteBalanceModel.relativeCct(t.toDouble(), n.toDouble())
                val src = ColorSpaces.bradford.times(WhiteBalanceModel.illuminantXyz(cct.temperatureK, cct.tint))
                val o = (ti * NODES + ni) * 3
                for (c in 0 until 3) table[o + c] = (dst[c] / src[c]).toFloat()
            }
        }
    }

    /** The cone gains for temperature [t] and tint [n] (clamped to ±100) into [out]. */
    fun gains(t: Float, n: Float, out: FloatArray) {
        val u = (t.coerceIn(-100f, 100f) - MIN) / STEP
        val v = (n.coerceIn(-100f, 100f) - MIN) / STEP
        val t0 = min(floor(u).toInt(), NODES - 2)
        val n0 = min(floor(v).toInt(), NODES - 2)
        val fu = u - t0
        val fv = v - n0
        for (c in 0 until 3) {
            val g00 = table[(t0 * NODES + n0) * 3 + c]
            val g01 = table[(t0 * NODES + n0 + 1) * 3 + c]
            val g10 = table[((t0 + 1) * NODES + n0) * 3 + c]
            val g11 = table[((t0 + 1) * NODES + n0 + 1) * 3 + c]
            val a = g00 + (g01 - g00) * fv
            val b = g10 + (g11 - g10) * fv
            out[c] = a + (b - a) * fu
        }
    }

    /** Applies temperature [t] and tint [n] to the pixel at offset [i] of [rgb]; [tmp] is 3 floats of scratch. */
    fun apply(rgb: FloatArray, i: Int, t: Float, n: Float, tmp: FloatArray) {
        gains(t, n, tmp)
        val m = toCone
        val r = rgb[i]
        val g = rgb[i + 1]
        val b = rgb[i + 2]
        val l = (m[0] * r + m[1] * g + m[2] * b) * tmp[0]
        val s = (m[3] * r + m[4] * g + m[5] * b) * tmp[1]
        val q = (m[6] * r + m[7] * g + m[8] * b) * tmp[2]
        val k = fromCone
        rgb[i] = k[0] * l + k[1] * s + k[2] * q
        rgb[i + 1] = k[3] * l + k[4] * s + k[5] * q
        rgb[i + 2] = k[6] * l + k[7] * s + k[8] * q
    }
}

/**
 * Totals of a pixel: the global slider value plus the local sum, clamped to the slider's range, turned into the global
 * operator's constant with the same formula as its `from` (in float; GLSL `MaskShaders.TOTALS`). Callers use the
 * global constant itself where the local sum is 0, so unmasked pixels are bit-identical.
 */
object LocalTotals {
    private const val PERCENT = 100f

    fun unit(total: Float): Float = total.coerceIn(-PERCENT, PERCENT) / PERCENT

    fun highlightStops(total: Float): Float = unit(total) * LocalToneOp.HIGHLIGHT_STOPS.toFloat()

    fun shadowStops(total: Float): Float = unit(total) * LocalToneOp.SHADOW_STOPS.toFloat()

    fun contrastGamma(total: Float): Float = exp2(unit(total) * GlobalToneOp.CONTRAST_LOG2.toFloat())

    fun whitesShift(total: Float): Float = unit(total) * GlobalToneOp.WHITES_RANGE.toFloat()

    fun blacksShift(total: Float): Float = unit(total) * GlobalToneOp.BLACKS_RANGE.toFloat()

    /** Texture gain of a total (−100..100) at the render's blur attenuation [att]. */
    fun textureGain(total: Float, att: Float): Float {
        val t = unit(total)
        return t * (if (t > 0f) EffectsParams.TEXTURE_BOOST else EffectsParams.TEXTURE_SMOOTH) * att
    }

    fun clarityGain(total: Float): Float = unit(total) * EffectsParams.CLARITY_GAIN

    fun dehazeOmega(total: Float): Float = unit(total).let { if (it > 0f) it * EffectsParams.DEHAZE_OMEGA else 0f }

    fun dehazeBeta(total: Float): Float = unit(total).let { if (it < 0f) -it * EffectsParams.DEHAZE_ADD else 0f }

    /** Sharpening gain of a total amount (global 0..150 plus local); 0 when the total is negative (softening). */
    fun sharpenGain(total: Float, att: Float): Float =
        if (total > 0f) min(total, SHARPEN_MAX) / PERCENT * DetailParams.SHARPEN_GAIN * att else 0f

    /** Softening (0..1) of a negative total sharpening amount. */
    fun soften(total: Float): Float = if (total < 0f) min(-total / PERCENT, 1f) else 0f

    /** Luminance noise reduction scale `(N / 100)^1.5` of a total amount (0..100). */
    fun noiseScale(total: Float): Float {
        val n = total.coerceIn(0f, PERCENT) / PERCENT
        return n * sqrt(n)
    }

    private const val SHARPEN_MAX = 150f
}

/**
 * Local saturation, hue and colour tint (slot 10, docs/adr/0022): one OkLab step after the colour stage. Saturation is
 * [VibranceSaturationOp.gain] with the local value (vibrance 0), hue a rotation of `(a, b)` by the local angle, the
 * tint colour grading's global wheel `(a, b) += L (tA, tB)`.
 */
object LocalColourOp {
    private const val MIN_CHROMA = 1e-7f
    private const val PERCENT = 100f

    /** True when the local sum [s] changes colour. */
    fun active(s: FloatArray): Boolean =
        s[V_SATURATION] != 0f || s[V_HUE] != 0f || s[V_TINT_A] != 0f || s[V_TINT_B] != 0f

    /** Applies the local sum [s] to the pixel at offset [i] of [rgb]; [lab] is 3 floats of scratch. */
    fun apply(rgb: FloatArray, i: Int, s: FloatArray, lab: FloatArray) {
        OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], lab)
        var a = lab[1]
        var b = lab[2]
        if (s[V_SATURATION] != 0f) {
            val c = sqrt(a * a + b * b)
            if (c > MIN_CHROMA) {
                val sat = s[V_SATURATION].coerceIn(-PERCENT, PERCENT) / PERCENT
                val k = VibranceSaturationOp.gain(c, a / c, b / c, ColourParams(sat, 0f))
                a *= k
                b *= k
            }
        }
        if (s[V_HUE] != 0f) {
            val theta = Math.toRadians(s[V_HUE].coerceIn(-180f, 180f).toDouble())
            val cs = StrictMath.cos(theta).toFloat()
            val sn = StrictMath.sin(theta).toFloat()
            val ra = a * cs - b * sn
            b = a * sn + b * cs
            a = ra
        }
        a += lab[0] * s[V_TINT_A]
        b += lab[0] * s[V_TINT_B]
        OkLab.toRgb(lab[0], a, b, lab)
        rgb[i] = lab[0]
        rgb[i + 1] = lab[1]
        rgb[i + 2] = lab[2]
    }
}

/**
 * Slots 5b-10 with masks (docs/adr/0022), the CPU reference of the masked `DEVELOP_FRAG`: per pixel the local white
 * balance and exposure gain, highlights/shadows with the shifted base, then the profile, contrast/whites/blacks with
 * per-pixel constants, the tone curve, the colour stage, and the local colour step.
 */
object LocalDevelop {
    /**
     * The local field of [params]' masks over [input] whose scene-linear pixels are [scene] (slots 1-4 done): the
     * pre-local image when a range mask needs it (the develop stages without masks, in OkLab float16), then the
     * coverage. [moments] / [coef] are the local-tone guide of [scene] (null when it is not built).
     */
    @Suppress("LongParameterList")
    fun field(
        input: PipelineInput,
        params: DevelopParams,
        scene: FloatArray,
        grid: LowResGrid,
        moments: FloatArray?,
        coef: FloatArray?,
        context: MaskContext,
    ): LocalField {
        val w = input.width
        val h = input.height
        val plan = MaskPlan.of(params.masks.masks, w, h)
        val pre = if (plan.needsPre) {
            val copy = scene.copyOf()
            CpuDevelop.developGlobal(copy, params, grid, moments, coef)
            DetailOp.toLab(copy, w * h)
        } else {
            null
        }
        val ai = plan.ai.map { context.ai(it, w, h) }
        val coverage = MaskRaster.coverage(plan, MaskRaster.Region(0, 0, w, h), pre, ai)
        return LocalField(params.masks, coverage)
    }

    /** Slots 5b-10 on scene-linear [rgb] in place, with the masks of [field]. */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition")
    fun develop(
        rgb: FloatArray,
        width: Int,
        params: DevelopParams,
        grid: LowResGrid,
        moments: FloatArray?,
        coef: FloatArray?,
        field: LocalField,
    ) {
        val n = rgb.size / CHANNELS
        val s = FloatArray(VECTOR)
        val tmp = FloatArray(3)
        val ab = FloatArray(2)
        val t = params.tone
        // 1. Local white balance and exposure, then highlights/shadows on the shifted base (one pass, as the shader).
        for (p in 0 until n) {
            val i = p * CHANNELS
            val masked = field.sum(p, s)
            val l0 = if (moments != null) LocalToneOp.logLuminance(rgb[i], rgb[i + 1], rgb[i + 2]) else 0f
            var shift = 0f
            if (masked && (s[V_TEMPERATURE] != 0f || s[V_TINT] != 0f || s[V_EXPOSURE] != 0f)) {
                if (s[V_TEMPERATURE] != 0f || s[V_TINT] != 0f) {
                    LocalWhiteBalance.apply(rgb, i, s[V_TEMPERATURE], s[V_TINT], tmp)
                }
                if (s[V_EXPOSURE] != 0f) {
                    val g = exp2(s[V_EXPOSURE].coerceIn(-EV_MAX, EV_MAX))
                    rgb[i] *= g
                    rgb[i + 1] *= g
                    rgb[i + 2] *= g
                }
                if (moments != null) shift = LocalToneOp.logLuminance(rgb[i], rgb[i + 1], rgb[i + 2]) - l0
            }
            val toneLocal = masked && (s[V_HIGHLIGHTS] != 0f || s[V_SHADOWS] != 0f)
            if (t.localActive || toneLocal) {
                val tp = if (toneLocal) {
                    t.copy(
                        highlightStops = if (s[V_HIGHLIGHTS] != 0f) {
                            LocalTotals.highlightStops(field.global(G_HIGHLIGHTS) + s[V_HIGHLIGHTS])
                        } else {
                            t.highlightStops
                        },
                        shadowStops = if (s[V_SHADOWS] != 0f) {
                            LocalTotals.shadowStops(field.global(G_SHADOWS) + s[V_SHADOWS])
                        } else {
                            t.shadowStops
                        },
                    )
                } else {
                    t
                }
                LocalToneOp.applyShifted(
                    rgb, i, grid, checkNotNull(moments), checkNotNull(coef), p % width, p / width, l0, shift, tp, ab,
                )
            }
        }
        // 2. The profile (no local form).
        ProfileOp.apply(rgb, params.profile)
        // 3. Contrast, whites, blacks with per-pixel constants.
        for (p in 0 until n) {
            val masked = field.sum(p, s)
            val toneGlobal = masked && (s[V_CONTRAST] != 0f || s[V_WHITES] != 0f || s[V_BLACKS] != 0f)
            if (!t.globalActive && !toneGlobal) continue
            val tp = if (toneGlobal) {
                t.copy(
                    contrastGamma = if (s[V_CONTRAST] != 0f) {
                        LocalTotals.contrastGamma(field.global(G_CONTRAST) + s[V_CONTRAST])
                    } else {
                        t.contrastGamma
                    },
                    whitesShift = if (s[V_WHITES] != 0f) {
                        LocalTotals.whitesShift(field.global(G_WHITES) + s[V_WHITES])
                    } else {
                        t.whitesShift
                    },
                    blacksShift = if (s[V_BLACKS] != 0f) {
                        LocalTotals.blacksShift(field.global(G_BLACKS) + s[V_BLACKS])
                    } else {
                        t.blacksShift
                    },
                )
            } else {
                t
            }
            GlobalToneOp.pixel(rgb, p * CHANNELS, tp)
        }
        // 4. The tone curve and the colour stage (no local form), as the unmasked pipeline.
        params.curve?.let { CurveOp.apply(rgb, it) }
        if (params.tools.active) {
            ColourStageOp.apply(rgb, params.colour, params.tools)
        } else {
            VibranceSaturationOp.apply(rgb, params.colour)
        }
        // 5. Slot 10: local saturation, hue and colour tint.
        if (params.masks.needs(MaskParams.NEED_COLOUR)) {
            for (p in 0 until n) {
                if (field.sum(p, s) && LocalColourOp.active(s)) LocalColourOp.apply(rgb, p * CHANNELS, s, tmp)
            }
        }
    }

    /** Detail and Effects totals of one pixel, written by [detail]. */
    class DetailTotals {
        var masked: Boolean = false
        var sharpenGain: Float = 0f
        var soften: Float = 0f
        var textureGain: Float = 0f
        var clarityGain: Float = 0f
        var dehazeOmega: Float = 0f
        var dehazeBeta: Float = 0f

        /** Total luminance NR scale `(N/100)^1.5`, or NaN where the pixel takes the global thresholds. */
        var noiseScale: Float = Float.NaN

        /** True when the pixel's total luminance NR is 0 (it keeps its input lightness exactly). */
        var noNoise: Boolean = false
    }

    /** The Detail and Effects constants of pixel [i] into [out] (the global ones where the local sum is 0). */
    fun detail(field: LocalField, i: Int, d: DetailParams, e: EffectsParams, s: FloatArray, out: DetailTotals) {
        val masked = field.sum(i, s)
        out.masked = masked
        out.sharpenGain = d.sharpenGain
        out.soften = 0f
        out.textureGain = e.textureGain
        out.clarityGain = e.clarityGain
        out.dehazeOmega = e.dehazeOmega
        out.dehazeBeta = e.dehazeBeta
        out.noiseScale = Float.NaN
        out.noNoise = field.global(G_NOISE) == 0f
        if (!masked) return
        if (s[V_SHARPNESS] != 0f) {
            val total = field.global(G_SHARPEN) + s[V_SHARPNESS]
            out.sharpenGain = LocalTotals.sharpenGain(total, d.sharpenAtt)
            out.soften = LocalTotals.soften(total)
        }
        if (s[V_TEXTURE] !=
            0f
        ) {
            out.textureGain = LocalTotals.textureGain(field.global(G_TEXTURE) + s[V_TEXTURE], e.textureAtt)
        }
        if (s[V_CLARITY] != 0f) out.clarityGain = LocalTotals.clarityGain(field.global(G_CLARITY) + s[V_CLARITY])
        if (s[V_DEHAZE] != 0f) {
            val total = field.global(G_DEHAZE) + s[V_DEHAZE]
            out.dehazeOmega = LocalTotals.dehazeOmega(total)
            out.dehazeBeta = LocalTotals.dehazeBeta(total)
        }
        if (s[V_NOISE] != 0f) {
            val total = (field.global(G_NOISE) + s[V_NOISE]).coerceIn(0f, PERCENT)
            out.noiseScale = LocalTotals.noiseScale(total)
            out.noNoise = total == 0f
        }
    }

    private const val CHANNELS = 3
    private const val EV_MAX = 4f
    private const val PERCENT = 100f
}
