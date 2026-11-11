package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges

/**
 * The numbers every operator needs for one (input, recipe) pair, computed once in double precision on the CPU and
 * rounded to float. The CPU reference and the GPU renderer both start from this object, so they cannot disagree on
 * a constant; they can only disagree on arithmetic, which is what the parity test measures.
 *
 * @property wbMultipliers raw white-balance multipliers (R, G, B), G = 1.
 * @property highlights clip levels for [HighlightReconstructionOp], or `null` when the input has no known saturation
 *   (standard images, synthetic): no reconstruction.
 * @property cameraToWorking row-major 3x3, balanced camera RGB to linear Rec.2020.
 * @property exposureGain linear gain `2^EV`.
 * @property profile the profile (base look, slot 6) resolved for this input; identity for display-referred input.
 * @property tone the Light group's tone sliders ([LocalToneOp], [GlobalToneOp]).
 * @property colour vibrance and saturation (slot 9).
 * @property detail noise reduction and sharpening (slot 11, [DetailOp]) at this render's pixel scale.
 */
class DevelopParams(
    val wbMultipliers: FloatArray,
    val cameraToWorking: FloatArray,
    val exposureGain: Float,
    val profile: ProfileParams,
    val highlights: HighlightParams? = null,
    val tone: ToneParams = ToneParams(),
    val colour: ColourParams = ColourParams.NONE,
    val detail: DetailParams = DetailParams.NONE,
) {
    override fun equals(other: Any?): Boolean = other is DevelopParams &&
        wbMultipliers.contentEquals(other.wbMultipliers) &&
        cameraToWorking.contentEquals(other.cameraToWorking) &&
        exposureGain == other.exposureGain &&
        profile == other.profile &&
        highlights == other.highlights &&
        tone == other.tone &&
        colour == other.colour &&
        detail == other.detail

    /** The same parameters without the Detail operator: what the stages before slot 11 depend on. */
    fun withoutDetail(): DevelopParams =
        DevelopParams(wbMultipliers, cameraToWorking, exposureGain, profile, highlights, tone, colour)

    override fun hashCode(): Int {
        var h = wbMultipliers.contentHashCode()
        h = h * HASH + cameraToWorking.contentHashCode()
        h = h * HASH + exposureGain.hashCode()
        h = h * HASH + profile.hashCode()
        h = h * HASH + highlights.hashCode()
        h = h * HASH + tone.hashCode()
        h = h * HASH + colour.hashCode()
        return h * HASH + detail.hashCode()
    }

    companion object {
        private const val HASH = 31
        private const val PERCENT = 100f

        /**
         * Resolves [recipe] against [input]. [pixelScale] is how many full-resolution pixels one pixel of [input] covers
         * (the proxy's downscale; 1 for full-resolution renders): only the Detail operator's lengths depend on it.
         */
        fun from(input: PipelineInput, recipe: Recipe, pixelScale: Float = 1f): DevelopParams {
            val wb = WhiteBalanceModel.solve(input, recipe.colour.whiteBalance)
            val multipliers = FloatArray(PipelineInput.CHANNELS) { wb.multipliers[it].toFloat() }
            val scene = input.kind == InputKind.SCENE_REFERRED
            return DevelopParams(
                wbMultipliers = multipliers,
                cameraToWorking = wb.balancedToWorking.toFloatArray(),
                exposureGain = ExposureOp.gain(recipe.light.exposureEv),
                profile = if (scene) ProfileLooks.resolve(recipe.profile) else ProfileParams.IDENTITY,
                highlights = input.saturation?.let { HighlightParams.from(multipliers, it) },
                tone = ToneParams.from(recipe.light),
                colour = ColourParams(
                    saturation = RecipeRanges.saturation.clamp(recipe.colour.saturation) / PERCENT,
                    vibrance = RecipeRanges.vibrance.clamp(recipe.colour.vibrance) / PERCENT,
                ),
                detail = DetailParams.from(recipe.detail, pixelScale),
            )
        }
    }
}
