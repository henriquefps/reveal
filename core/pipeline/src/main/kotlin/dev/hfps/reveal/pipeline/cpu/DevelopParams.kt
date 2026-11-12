package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.optics.VignetteTable
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.Treatment
import kotlin.math.max

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
 * @property colour vibrance and saturation (slot 9); none in black & white.
 * @property curve the tone curve (slot 8, after contrast/whites/blacks), `null` when it is the identity.
 * @property tools the colour mixer, black & white and colour grading (slot 9, after vibrance/saturation).
 * @property detail noise reduction and sharpening (slot 11, [DetailOp]) at this render's pixel scale.
 * @property effects texture, clarity, dehaze (slot 12) and the post-crop vignette and grain ([EffectsParams]).
 * @property vignette the lens profile's vignetting correction (slot 3, [VignetteOp]), `null` when off.
 * @property defringe defringe (slot 10b, [DefringeOp]) at this render's pixel scale.
 */
class DevelopParams(
    val wbMultipliers: FloatArray,
    val cameraToWorking: FloatArray,
    val exposureGain: Float,
    val profile: ProfileParams,
    val highlights: HighlightParams? = null,
    val tone: ToneParams = ToneParams(),
    val colour: ColourParams = ColourParams.NONE,
    val curve: CurveLut? = null,
    val tools: ColourToolParams = ColourToolParams.NONE,
    val detail: DetailParams = DetailParams.NONE,
    val effects: EffectsParams = EffectsParams.NONE,
    val vignette: VignetteTable? = null,
    val defringe: DefringeParams = DefringeParams.NONE,
) {

    override fun equals(other: Any?): Boolean = other is DevelopParams &&
        wbMultipliers.contentEquals(other.wbMultipliers) &&
        cameraToWorking.contentEquals(other.cameraToWorking) &&
        exposureGain == other.exposureGain &&
        profile == other.profile &&
        highlights == other.highlights &&
        tone == other.tone &&
        colour == other.colour &&
        curve == other.curve &&
        tools == other.tools &&
        detail == other.detail &&
        effects == other.effects &&
        vignette == other.vignette &&
        defringe == other.defringe

    /**
     * The same parameters without Detail and Effects: what the stages before slot 11 (and the Effects grid, which is
     * built from the develop output) depend on.
     */
    fun withoutDetail(): DevelopParams = DevelopParams(
        wbMultipliers, cameraToWorking, exposureGain, profile, highlights, tone, colour, curve, tools,
        vignette = vignette, defringe = defringe,
    )

    /**
     * The same parameters without the post-crop vignette and grain: what the working texture depends on (they are
     * applied by the output pass, so dragging them never re-runs a stage).
     */
    fun withoutPost(): DevelopParams = if (!effects.post.active && effects.post == PostParams.NONE) {
        this
    } else {
        DevelopParams(
            wbMultipliers, cameraToWorking, exposureGain, profile, highlights, tone, colour, curve, tools, detail,
            effects.copy(post = PostParams.NONE), vignette, defringe,
        )
    }

    /**
     * These parameters with [recipe]'s Detail and Effects (slots 11 and 12) for [input] at [pixelScale]; every other
     * field is kept, so the result equals `from(input, recipe, pixelScale)` when the rest of [recipe] resolved to
     * these parameters (`from(input, recipe.withoutLocal(), pixelScale)`).
     */
    fun withLocal(input: PipelineInput, recipe: Recipe, pixelScale: Float): DevelopParams = DevelopParams(
        wbMultipliers, cameraToWorking, exposureGain, profile, highlights, tone, colour, curve, tools,
        detail = localDetail(input, recipe, pixelScale),
        effects = EffectsParams.from(recipe.effects, pixelScale),
        vignette = vignette,
        defringe = defringe,
    )

    /** True when slot 11 or 12 runs: the develop stage writes OkLab for them. */
    val localActive: Boolean get() = detail.active || effects.localActive

    /**
     * How far (rendered pixels) slots 11 and 12 read around a pixel: Detail's levels and the blur shared by sharpening
     * and texture. The apron an export or zoom tile needs (clarity and dehaze need none: they read the grid).
     */
    val reach: Int get() = defringe.reach + detail.reach(max(detail.sharpenReach, effects.textureReach))

    override fun hashCode(): Int {
        var h = wbMultipliers.contentHashCode()
        h = h * HASH + cameraToWorking.contentHashCode()
        h = h * HASH + exposureGain.hashCode()
        h = h * HASH + profile.hashCode()
        h = h * HASH + highlights.hashCode()
        h = h * HASH + tone.hashCode()
        h = h * HASH + colour.hashCode()
        h = h * HASH + curve.hashCode()
        h = h * HASH + tools.hashCode()
        h = h * HASH + effects.hashCode()
        h = h * HASH + vignette.hashCode()
        h = h * HASH + defringe.hashCode()
        return h * HASH + detail.hashCode()
    }

    companion object {
        private const val HASH = 31
        private const val PERCENT = 100f

        /**
         * Resolves [recipe] against [input]. [pixelScale] is how many full-resolution pixels one pixel of [input]
         * covers (the proxy's downscale; 1 for full-resolution renders): only the Detail operator's lengths depend on
         * it.
         */
        fun from(input: PipelineInput, recipe: Recipe, pixelScale: Float = 1f): DevelopParams {
            val wb = WhiteBalanceModel.solve(input, recipe.colour.whiteBalance)
            val multipliers = FloatArray(PipelineInput.CHANNELS) { wb.multipliers[it].toFloat() }
            val scene = input.kind == InputKind.SCENE_REFERRED
            val colour = recipe.colour
            val blackWhite = recipe.profile.treatment == Treatment.BLACK_WHITE
            return DevelopParams(
                wbMultipliers = multipliers,
                cameraToWorking = wb.balancedToWorking.toFloatArray(),
                exposureGain = ExposureOp.gain(recipe.light.exposureEv),
                profile = if (scene) ProfileLooks.resolve(recipe.profile) else ProfileParams.IDENTITY,
                highlights = input.saturation?.let { HighlightParams.from(multipliers, it) },
                tone = ToneParams.from(recipe.light),
                // Black & white replaces vibrance/saturation and the colour mixer with its grey mixer (ADR 0013).
                colour = if (blackWhite) {
                    ColourParams.NONE
                } else {
                    ColourParams(
                        saturation = RecipeRanges.saturation.clamp(colour.saturation) / PERCENT,
                        vibrance = RecipeRanges.vibrance.clamp(colour.vibrance) / PERCENT,
                    )
                },
                curve = CurveLut.of(recipe.light.curve),
                tools = ColourToolParams(
                    mixer = if (blackWhite) null else MixerParams.from(colour.mixer),
                    grey = if (blackWhite) GreyParams.from(colour.greyMixer) else null,
                    grading = GradingParams.from(colour.grading),
                ),
                detail = localDetail(input, recipe, pixelScale),
                effects = EffectsParams.from(recipe.effects, pixelScale),
                vignette = VignetteTable.of(input.lens, recipe.optics),
                defringe = DefringeParams.from(recipe.optics, pixelScale),
            )
        }

        private fun localDetail(input: PipelineInput, recipe: Recipe, pixelScale: Float): DetailParams =
            DetailParams.from(recipe.detail, pixelScale, NoiseModel.factor(input.kind, input.noise))

        /**
         * The apron of [recipe] at full resolution ([reach] does not depend on the input): for export plans and zoom
         * tiles (`ExportPlan.of(..., reach)`).
         */
        fun reach(recipe: Recipe, pixelScale: Float = 1f): Int {
            val detail = DetailParams.from(recipe.detail, pixelScale)
            val effects = EffectsParams.from(recipe.effects, pixelScale)
            // Defringe (slot 10b, docs/adr/0015) runs before Detail: its neighbourhood adds to the apron.
            return DefringeParams.from(recipe.optics, pixelScale).reach +
                detail.reach(max(detail.sharpenReach, effects.textureReach))
        }
    }
}

/** [this] without Detail and Effects (slots 11 and 12): the part of a recipe the stages before slot 11 resolve. */
fun Recipe.withoutLocal(): Recipe = copy(detail = Detail(), effects = Effects())
