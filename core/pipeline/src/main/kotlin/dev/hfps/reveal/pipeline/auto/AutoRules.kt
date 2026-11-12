@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.auto

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.GlobalToneOp
import dev.hfps.reveal.pipeline.cpu.LocalToneOp
import dev.hfps.reveal.pipeline.cpu.ToneParams
import dev.hfps.reveal.pipeline.cpu.WbAnalysis
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The adjustable constants of Auto (docs/adr/0010). M3's smart presets expose some of them ("target subject
 * brightness" = [targetGrey], "max shadow lift" = [maxShadows]). Display luminances are display-referred `Y`
 * (0..1, before the output encoding); lightness is `cbrt(Y)` (Light's global tone domain, ADR 0007).
 *
 * @property targetGrey display luminance the key lands on. The key is `m * median + (1 - m) * trimmed log-average`
 *   (`m` = [keyMedianWeight], quantiles 2-98 %) of the histogram mixed from the uniform and the subject-weighted
 *   one ([AutoAnalysis.subjectHistogram]: the saliency map, or the centre weight without one) by [keyCentreWeight].
 * @property faceTarget display luminance the faces' skin key should land on (the "target subject brightness" of a
 *   smart preset); with [faceTolerance] stops either side it is a band: a face already inside it is left alone,
 *   so darker and lighter skin keep their own brightness and only badly lit faces are corrected.
 * @property faceWeight how far (0..1) the exposure moves a face into the band, for faces covering at least
 *   [faceAreaFull] of the frame (smaller faces count less: `faceWeight * sqrt(area / faceAreaFull)`); the move is
 *   limited to [maxFaceShift] EV.
 * @property maxExposure largest |exposure| Auto sets, in EV.
 * @property protectHeadroom stops above [highlightCeiling] the brightest content may sit at after exposure; more
 *   lowers the exposure, by at most [maxProtect] EV (then shadows carry the rest).
 * @property highlightCeiling display luminance the [highlightQuantile] must not exceed (the profile's shoulder
 *   compresses above it); Highlights pulls it under.
 * @property shadowFloor display luminance the [shadowQuantile] should reach; Shadows lifts it, at most [maxShadows].
 * @property contrastTarget lightness spread (p75 - p25) Contrast moves towards, by [contrastStrength] of the way.
 * @property whitePoint lightness the [highlightQuantile] lands on after Whites.
 * @property blackPoint lightness the [blackQuantile] lands on after Blacks.
 * @property vibranceTarget median OkLab chroma Vibrance moves towards ([vibranceGain] slider units per unit of
 *   chroma), never negative, at most [maxVibrance].
 */
data class AutoParams(
    val targetGrey: Double = 0.17,
    val keyCentreWeight: Double = 1.0,
    val keyMedianWeight: Double = 1.0,
    val maxExposure: Double = 3.0,
    val protectHeadroom: Double = 1.5,
    val maxProtect: Double = 1.0,
    val highlightQuantile: Double = 0.995,
    val highlightCeiling: Double = 0.90,
    val maxHighlights: Int = 60,
    val shadowQuantile: Double = 0.05,
    val shadowFloor: Double = 0.012,
    val maxShadows: Int = 40,
    val contrastTarget: Double = 0.40,
    val contrastStrength: Double = 0.2,
    val minContrast: Int = -15,
    val maxContrast: Int = 20,
    val whitePoint: Double = 0.98,
    val minWhites: Int = -20,
    val maxWhites: Int = 30,
    val blackQuantile: Double = 0.005,
    val blackPoint: Double = 0.12,
    val minBlacks: Int = -20,
    val maxBlacks: Int = 10,
    val vibranceTarget: Double = 0.11,
    val vibranceGain: Double = 400.0,
    val maxVibrance: Int = 20,
    val faceTarget: Double = 0.12,
    val faceTolerance: Double = 0.8,
    val faceWeight: Double = 1.0,
    val faceAreaFull: Double = 0.001,
    val maxFaceShift: Double = 1.5,
)

/** Auto's result: plain slider values (exposure in EV snapped to 0.01, the others whole slider units). */
data class AutoResult(
    val exposureEv: Float,
    val contrast: Float,
    val highlights: Float,
    val shadows: Float,
    val whites: Float,
    val blacks: Float,
    val vibrance: Float,
) {
    /** The Light group as Auto sets it. */
    val light: Light get() = Light(exposureEv, contrast, highlights, shadows, whites, blacks)
}

/** What a tap on Auto sets: the strip's Auto ([ALL]) or the Light panel's ([LIGHT]). */
enum class AutoScope {
    /** Exposure, contrast, highlights, shadows, whites, blacks, vibrance, and the white balance in Auto WB. */
    ALL,

    /** The Light group only. */
    LIGHT,
}

/**
 * The rules (docs/adr/0010): pure, deterministic functions from [AutoAnalysis] to slider values. Each rule predicts
 * its effect with the operators' own transfer functions applied to a few percentiles (the local tone curve
 * [LocalToneOp.delta] on the scene stop, the profile's [AutoAnalysis.toneTable], the global tone curve
 * [GlobalToneOp.curve] on lightness), in pipeline order:
 *
 * 1. **Exposure** puts the key on [AutoParams.targetGrey], moves the faces' skin into its band around
 *    [AutoParams.faceTarget] when there are faces, and is lowered (by at most [AutoParams.maxProtect] EV) when that
 *    would push the highlight quantile more than [AutoParams.protectHeadroom] stops past the highlight ceiling, but
 *    never so far that the faces drop below their band.
 * 2. **Highlights**: the smallest pull that brings the highlight quantile under the ceiling (never below where the
 *    original had it for display-referred input: its clipped whites cannot be recovered).
 * 3. **Shadows**: the smallest lift that brings the shadow quantile up to the floor.
 * 4. **Contrast** moves the lightness spread part of the way to its target.
 * 5. **Whites** / 6. **Blacks** put the highlight / black quantiles on the white / black points.
 * 7. **Vibrance**: modest, towards a median chroma, never negative.
 */
object AutoRules {
    private const val PERCENT = 100.0
    private const val EV_STEP = 100.0
    private const val KEY_LO = 0.02
    private const val KEY_HI = 0.98
    private const val Q25 = 0.25
    private const val Q50 = 0.5
    private const val Q75 = 0.75

    /** Every slider value Auto sets for [a]. */
    fun compute(a: AutoAnalysis, p: AutoParams = AutoParams()): AutoResult {
        val ev = exposure(a, p)
        val highlights = highlights(a, p, ev)
        val shadows = shadows(a, p, ev, highlights)
        val local = localParams(highlights, shadows)
        fun lightness(q: Double): Double {
            val s = a.percentile(q) + ev
            val y = a.display(s + LocalToneOp.delta(s.toFloat(), local))
            return StrictMath.cbrt(max(y, 0.0))
        }
        val contrast = contrast(p, lightness(Q25), lightness(Q75))
        val whites = whites(p, lightness(p.highlightQuantile), contrast)
        val blacks = blacks(p, lightness(p.blackQuantile), contrast, whites)
        return AutoResult(
            exposureEv = ev.toFloat(),
            contrast = contrast.toFloat(),
            highlights = highlights.toFloat(),
            shadows = shadows.toFloat(),
            whites = whites.toFloat(),
            blacks = blacks.toFloat(),
            vibrance = vibrance(a, p).toFloat(),
        )
    }

    /** Rule 1, snapped to the exposure slider's 0.01 EV. */
    fun exposure(a: AutoAnalysis, p: AutoParams): Double {
        val w = p.keyCentreWeight
        val c = DoubleArray(AutoAnalysis.BINS) { (1 - w) * a.histogram[it] + w * a.subjectHistogram[it] }
        val m = p.keyMedianWeight
        val key = m * a.percentile(Q50, c) + (1 - m) * a.trimmedMean(KEY_LO, KEY_HI, c)
        var ideal = a.sceneFor(p.targetGrey) - key
        var floor = Double.NEGATIVE_INFINITY
        val face = a.faceKey
        if (face != null) {
            val target = a.sceneFor(p.faceTarget)
            val low = target - p.faceTolerance
            val high = target + p.faceTolerance
            val s = face + ideal
            val shift = when {
                s < low -> low - s
                s > high -> high - s
                else -> 0.0
            }
            ideal += (faceWeight(a, p) * shift).coerceIn(-p.maxFaceShift, p.maxFaceShift)
            // Highlight protection may not darken the faces below their band (unless the exposure already does).
            floor = min(ideal, low - face)
        }
        val protect = a.sceneFor(p.highlightCeiling) + p.protectHeadroom - a.percentile(p.highlightQuantile)
        val ev = max(min(ideal, max(protect, ideal - p.maxProtect)), floor).coerceIn(-p.maxExposure, p.maxExposure)
        return (ev * EV_STEP).roundToInt() / EV_STEP
    }

    /** How strongly the faces steer the exposure: [AutoParams.faceWeight], less for small faces (0 without). */
    fun faceWeight(a: AutoAnalysis, p: AutoParams): Double {
        if (a.faceHistogram == null || p.faceAreaFull <= 0.0) return 0.0
        return p.faceWeight * min(1.0, sqrt(a.faceArea / p.faceAreaFull))
    }

    /** Rule 2: the highlights slider (0 or negative). */
    fun highlights(a: AutoAnalysis, p: AutoParams, ev: Double): Int {
        val original = a.percentile(p.highlightQuantile)
        val s = original + ev
        val ceiling = if (a.sceneReferred) {
            p.highlightCeiling
        } else {
            max(p.highlightCeiling, a.display(original))
        }
        if (a.display(s) <= ceiling) return 0
        val pull = (1..p.maxHighlights).firstOrNull { h ->
            a.display(s + LocalToneOp.delta(s.toFloat(), localParams(-h, 0))) <= ceiling
        }
        return -(pull ?: p.maxHighlights)
    }

    /** Rule 3: the shadows slider (0 or positive). */
    fun shadows(a: AutoAnalysis, p: AutoParams, ev: Double, highlights: Int): Int {
        val s = a.percentile(p.shadowQuantile) + ev
        fun reaches(v: Int) = a.display(s + LocalToneOp.delta(s.toFloat(), localParams(highlights, v))) >= p.shadowFloor
        return (0..p.maxShadows).firstOrNull(::reaches) ?: p.maxShadows
    }

    /** Rule 4: the contrast slider whose curve brings the spread [l25]..[l75] closest to the wanted spread. */
    fun contrast(p: AutoParams, l25: Double, l75: Double): Int {
        val spread = l75 - l25
        val wanted = spread + p.contrastStrength * (p.contrastTarget - spread)
        return best(p.minContrast..p.maxContrast) { c ->
            val t = globalParams(c, 0, 0)
            GlobalToneOp.curve(l75.toFloat(), t) - GlobalToneOp.curve(l25.toFloat(), t) - wanted.toFloat()
        }
    }

    /** Rule 5: the whites slider that puts lightness [lHigh] closest to the white point. */
    fun whites(p: AutoParams, lHigh: Double, contrast: Int): Int = best(p.minWhites..p.maxWhites) { w ->
        GlobalToneOp.curve(lHigh.toFloat(), globalParams(contrast, w, 0)) - p.whitePoint.toFloat()
    }

    /** Rule 6: the blacks slider that puts lightness [lBlack] closest to the black point. */
    fun blacks(p: AutoParams, lBlack: Double, contrast: Int, whites: Int): Int = best(p.minBlacks..p.maxBlacks) { b ->
        GlobalToneOp.curve(lBlack.toFloat(), globalParams(contrast, whites, b)) - p.blackPoint.toFloat()
    }

    /** Rule 7: vibrance. */
    fun vibrance(a: AutoAnalysis, p: AutoParams): Int =
        ((p.vibranceTarget - a.medianChroma) * p.vibranceGain).roundToInt().coerceIn(0, p.maxVibrance)

    /**
     * [recipe] with Auto applied for [scope]: the Light sliders (and, for [AutoScope.ALL], vibrance, and the white
     * balance [autoWb] when the recipe's mode is Auto, with the face rules for [faces]). Everything else is kept.
     */
    fun applyTo(
        recipe: Recipe,
        result: AutoResult,
        scope: AutoScope,
        autoWb: WbAnalysis? = null,
        faces: List<Face> = emptyList(),
    ): Recipe {
        val lit = recipe.copy(light = result.light)
        if (scope == AutoScope.LIGHT) return lit
        val balanced = withAutoWb(lit, autoWb, faces)
        return balanced.copy(colour = balanced.colour.copy(vibrance = result.vibrance))
    }

    /** [recipe] with the Auto white balance of [autoWb] when its mode is Auto (otherwise unchanged). */
    fun withAutoWb(recipe: Recipe, autoWb: WbAnalysis?, faces: List<Face> = emptyList()): Recipe {
        val wb = recipe.colour.whiteBalance
        if (wb.mode != WbMode.AUTO || autoWb == null) return recipe
        return recipe.copy(colour = recipe.colour.copy(whiteBalance = whiteBalance(autoWb, faces)))
    }

    /**
     * The Auto white balance ([AutoWhiteBalance], ADR 0008, with the face rules for [faces]) as plain slider values:
     * kelvin snapped to the temperature slider's fine step, tint and the relative pair to whole units. The Auto WB
     * chip and the Auto tool both use this, so they always agree.
     */
    fun whiteBalance(autoWb: WbAnalysis, faces: List<Face> = emptyList()): WhiteBalance {
        val wb = AutoWhiteBalance.whiteBalance(autoWb, faces)
        return wb.copy(
            temperatureK = wb.temperatureK?.let {
                RecipeRanges.temperature.snap(it, RecipeRanges.temperature.fineStep)
            },
            tint = wb.tint?.let { it.roundToInt().toFloat() },
            relTemperature = wb.relTemperature.roundToInt().toFloat(),
            relTint = wb.relTint.roundToInt().toFloat(),
        )
    }

    /**
     * One tap: the white balance first (Auto WB mode only), then the analysis of [sample] as that recipe renders it,
     * then the rules. Deterministic, and idempotent (the analysis ignores the values Auto sets).
     */
    fun run(
        sample: PipelineInput,
        recipe: Recipe,
        scope: AutoScope,
        autoWb: WbAnalysis? = null,
        params: AutoParams = AutoParams(),
    ): Recipe = run(PhotoFeatures(sample, autoWb), recipe, scope, params)

    /** [run] with the photo's [features] (faces and the subject map steer the exposure, faces the white balance). */
    fun run(features: PhotoFeatures, recipe: Recipe, scope: AutoScope, params: AutoParams = AutoParams()): Recipe {
        val balanced = if (scope == AutoScope.ALL) withAutoWb(recipe, features.wb, features.faces) else recipe
        val analysis = AutoAnalyser.analyse(features.sample, balanced, features.faces, features.saliency)
        return applyTo(balanced, compute(analysis, params), scope, features.wb, features.faces)
    }

    /** The integer in [range] with the smallest |[error]|; ties go to the value nearest 0 (the gentler edit). */
    private fun best(range: IntRange, error: (Int) -> Float): Int {
        var bestValue = 0
        var bestError = Float.MAX_VALUE
        for (v in range.sortedBy { abs(it) }) {
            val e = abs(error(v))
            if (e < bestError) {
                bestError = e
                bestValue = v
            }
        }
        return bestValue
    }

    private fun localParams(highlights: Int, shadows: Int) = ToneParams(
        highlightStops = (highlights / PERCENT * LocalToneOp.HIGHLIGHT_STOPS).toFloat(),
        shadowStops = (shadows / PERCENT * LocalToneOp.SHADOW_STOPS).toFloat(),
    )

    private fun globalParams(contrast: Int, whites: Int, blacks: Int) =
        ToneParams.from(Light(contrast = contrast.toFloat(), whites = whites.toFloat(), blacks = blacks.toFloat()))
}
