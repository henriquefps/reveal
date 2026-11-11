@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.auto

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.HueBands
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The colours the colour tools see (docs/adr/0013): the photo rendered on the CPU reference, on Auto's small analysis
 * sample ([AutoAnalyser.sample], about 512 px), with [recipe] up to and including vibrance/saturation and without the
 * colour mixer, black & white and grading. The targeted adjustment reads the colour under the finger from it (so a
 * band is chosen by the colour *before* the mixer moves it, and a B&W photo still knows its colours) and Auto mix
 * analyses it.
 */
class SourceColours(val width: Int, val height: Int, val rgb: FloatArray) {
    /** Mean linear Rec.2020 colour of the square of half-size [radius] around the sample pixel ([x], [y]). */
    fun mean(x: Int, y: Int, radius: Int): FloatArray {
        val out = FloatArray(3)
        var n = 0
        for (yy in max(0, y - radius)..min(height - 1, y + radius)) {
            for (xx in max(0, x - radius)..min(width - 1, x + radius)) {
                val i = (yy * width + xx) * 3
                out[0] += rgb[i]
                out[1] += rgb[i + 1]
                out[2] += rgb[i + 2]
                n++
            }
        }
        if (n > 0) for (c in 0 until 3) out[c] /= n
        return out
    }

    companion object {
        /** [recipe] as the colour tools' input: colour treatment, no mixer, no grading. */
        fun sourceRecipe(recipe: Recipe): Recipe = recipe.copy(
            profile = recipe.profile.copy(treatment = Treatment.COLOUR),
            colour = recipe.colour.copy(mixer = ColourMixer(), grading = ColourGrading()),
        )

        /** Renders [sample] (Auto's analysis sample) as [sourceRecipe] of [recipe] (geometry ignored). */
        fun render(sample: PipelineInput, recipe: Recipe): SourceColours = SourceColours(
            sample.width,
            sample.height,
            CpuDevelop.renderLinear(sample, sourceRecipe(recipe)),
        )
    }
}

/**
 * The targeted adjustment (docs/plan/product.md, Colour mixer): a vertical drag on the photo changes the bands under
 * the finger. The colour under the finger picks the bands with [HueBands.weights] (two neighbours sharing 1, as the
 * operator interpolates them), so dragging over an orange between red and yellow moves both in that proportion and
 * the result is exactly "more of what the operator does at this hue".
 */
object TargetedAdjust {
    /** Below this OkLab chroma the colour has no reliable hue: the drag does nothing (a grey has no band). */
    const val MIN_CHROMA: Float = 0.02f

    /** Slider units per dp of vertical drag (up = positive): 100 units over about 200 dp. */
    const val UNITS_PER_DP: Float = 0.5f

    /** The band weights of [rgb] (linear Rec.2020), or `null` when it is too neutral to have a band. */
    fun weights(rgb: FloatArray): FloatArray? {
        val lab = FloatArray(3)
        OkLab.fromRgb(rgb[0], rgb[1], rgb[2], lab)
        val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        if (c < MIN_CHROMA) return null
        return HueBands.weights(StrictMath.atan2(lab[2].toDouble(), lab[1].toDouble()).toFloat())
    }

    /** The band that leads for [weights] (the larger share), for the UI's "Adjusting: Red" hint. */
    fun lead(weights: FloatArray): ColourBand = ColourBand.entries[weights.indices.maxBy { weights[it] }]

    /** Per-band deltas for a drag of [dpUp] dp upwards (whole slider units, so the history shows clean values). */
    fun deltas(weights: FloatArray, dpUp: Float): FloatArray =
        FloatArray(weights.size) { (weights[it] * dpUp * UNITS_PER_DP).roundToInt().toFloat() }
}

/**
 * Auto mix for black & white (docs/adr/0013, "Auto mix"): grey mixer values computed from the photo's colours,
 * deterministic and plain slider values. The idea: a straight lightness conversion merges colours of similar
 * brightness (a red flower on green leaves), so Auto spreads the greys of the photo's main colours apart, plus a mild
 * warm-up/cool-down (the classic yellow-orange filter: warm colours a little lighter, blues a little darker, which also
 * separates warm/cool pairs of equal lightness).
 *
 * 1. Per band `k`, from [SourceColours]: share `s_k = Σ w_k(h) g(C) / N` (pixels weighted by band membership and
 *    [HueBands.chromaWeight]), mean grey in stops `l_k = 3 log2 L` and mean chroma weight `ḡ_k`.
 * 2. Bias `β_k = BIAS cos(c_k - WARM_HUE)` stops (warm up, cool down) and biased greys `b_k = l_k + β_k`.
 * 3. Main colours: bands with `s_k >= MIN_SHARE`; their weighted mean `b̄` and offsets `d_k = b_k - b̄`.
 * 4. Stretch: if the main colours' spread `σ = sqrt(Σ s d² / Σ s)` is under [TARGET_SPREAD] stops, they move apart
 *    by `κ = min(TARGET_SPREAD / max(σ, 0.05) - 1, MAX_STRETCH)`: target `T_k = b̄ + (1 + κ) d_k`. Because the bias
 *    is in `d`, two colours of the same lightness separate too (the warm one lighter).
 * 5. Value `v_k = 100 (T_k - l_k) / (GREY_STOPS_Y ḡ_k)` (stops of luminance to grey-mixer units; bands that are not
 *    main colours use the bias alone with `ḡ = 1`), rounded, limited to ±[MAX_VALUE].
 */
object AutoBlackWhite {
    const val MIN_SHARE: Float = 0.02f
    const val TARGET_SPREAD: Float = 0.6f
    const val MAX_STRETCH: Float = 3f
    const val BIAS: Float = 0.25f
    const val WARM_HUE_DEGREES: Double = 60.0
    const val MAX_VALUE: Float = 60f

    /** Luminance stops of a grey mixer ±100 at full chroma weight: `3 * BlackWhiteOp.GREY_STOPS`. */
    private const val GREY_STOPS_Y = 1.5f
    private const val MIN_L = 1e-4f

    /** The Auto mix of [colours]. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun mix(colours: SourceColours): BandValues {
        val n = HueBands.COUNT
        val share = DoubleArray(n)
        val grey = DoubleArray(n)
        val weight = DoubleArray(n)
        val lab = FloatArray(3)
        val px = colours.width * colours.height
        for (i in 0 until px) {
            val o = i * 3
            OkLab.fromRgb(colours.rgb[o], colours.rgb[o + 1], colours.rgb[o + 2], lab)
            val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            if (c < TargetedAdjust.MIN_CHROMA) continue
            val g = HueBands.chromaWeight(c)
            val w = HueBands.weights(StrictMath.atan2(lab[2].toDouble(), lab[1].toDouble()).toFloat())
            val stops = 3.0 * log2(max(lab[0], MIN_L).toDouble())
            for (k in 0 until n) {
                if (w[k] == 0f) continue
                val m = w[k].toDouble() * g
                share[k] += m
                grey[k] += m * stops
                weight[k] += w[k].toDouble() * g * g
            }
        }
        val main = (0 until n).filter { share[it] / px >= MIN_SHARE }
        val bias = DoubleArray(n) { BIAS * cos(Math.toRadians(HueBands.CENTRES_DEGREES[it] - WARM_HUE_DEGREES)) }
        val l = DoubleArray(n) { if (share[it] > 0) grey[it] / share[it] else 0.0 }
        val b = DoubleArray(n) { l[it] + bias[it] }
        val gBar = DoubleArray(n) { if (share[it] > 0) weight[it] / share[it] else 1.0 }
        val total = main.sumOf { share[it] }
        val mean = if (total > 0) main.sumOf { share[it] * b[it] } / total else 0.0
        val spread = if (total > 0) sqrt(main.sumOf { share[it] * (b[it] - mean) * (b[it] - mean) } / total) else 0.0
        val stretch = if (main.size < 2) {
            0.0
        } else {
            (TARGET_SPREAD / max(spread, 0.05) - 1.0).coerceIn(0.0, MAX_STRETCH.toDouble())
        }
        val values = (0 until n).map { k ->
            val isMain = k in main
            val stops = if (isMain) mean + (1 + stretch) * (b[k] - mean) - l[k] else bias[k]
            val g = if (isMain) max(gBar[k], 0.1) else 1.0
            val v = (100.0 * stops / (GREY_STOPS_Y * g)).coerceIn(-MAX_VALUE.toDouble(), MAX_VALUE.toDouble())
            v.roundToInt().toFloat().let { if (abs(it) == 0f) 0f else it }
        }
        return BandValues.of(values)
    }

    /** Renders [sample] as [recipe]'s colours ([SourceColours]) and returns its Auto mix. */
    fun run(sample: PipelineInput, recipe: Recipe): BandValues = mix(SourceColours.render(sample, recipe))

    private fun log2(x: Double): Double = StrictMath.log(x) / StrictMath.log(2.0)
}
