package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.recipe.Recipe

/**
 * The CPU reference pipeline: the operators in their fixed order (docs/adr/0002, docs/adr/0007; geometry is
 * slot 13). Pure Kotlin over
 * float arrays, deterministic (no threads, no platform-dependent maths), the oracle for unit tests and GPU parity.
 */
object CpuDevelop {
    /**
     * Runs raw WB, highlight reconstruction, camera matrix, vignetting correction (docs/adr/0015), exposure,
     * highlights/shadows, the profile (base look), contrast/whites/blacks, the tone curve, vibrance/saturation, the
     * colour mixer or black & white, colour grading (docs/adr/0013), Detail (noise reduction, sharpening) and the local
     * Effects (texture, clarity, dehaze; docs/adr/0014), returning linear Rec.2020 RGB (`width*height*3`,
     * display-referred below 1.0) before the output transform. Defringe (docs/adr/0015) runs right before Detail.
     * [input] is not modified. [pixelScale]: full-resolution pixels per pixel of [input] (a proxy's downscale; the
     * Detail operator's scale).
     */
    fun renderLinear(input: PipelineInput, recipe: Recipe, pixelScale: Float = 1f): FloatArray {
        val params = DevelopParams.from(input, recipe, pixelScale)
        val grid = LowResGrid(input.width, input.height)
        val rgb = input.linearRgb.copyOf()
        RawWhiteBalanceOp.apply(rgb, params.wbMultipliers)
        val hp = params.highlights
        // Skipped when nothing reaches the clip ramp: the operator is then the identity (the GPU skips it too).
        if (hp != null && HighlightReconstructionOp.needed(input.linearRgb, checkNotNull(input.saturation))) {
            HighlightReconstructionOp.apply(rgb, grid, hp, HighlightReconstructionOp.chroma(rgb, grid, hp))
        }
        CameraMatrixOp.apply(rgb, params.cameraToWorking)
        params.vignette?.let { VignetteOp.apply(rgb, input.width, input.height, it) }
        ExposureOp.apply(rgb, params.exposureGain)
        if (params.tone.localActive) {
            val moments = LocalToneOp.moments(rgb, grid)
            LocalToneOp.apply(rgb, grid, moments, LocalToneOp.guide(moments, grid), params.tone)
        }
        ProfileOp.apply(rgb, params.profile)
        if (params.tone.globalActive) GlobalToneOp.apply(rgb, params.tone)
        params.curve?.let { CurveOp.apply(rgb, it) }
        if (params.tools.active) {
            ColourStageOp.apply(rgb, params.colour, params.tools)
        } else {
            VibranceSaturationOp.apply(rgb, params.colour)
        }
        DefringeOp.apply(rgb, input.width, input.height, params.defringe)
        DetailOp.apply(rgb, input.width, input.height, params.detail, params.effects, grid)
        return rgb
    }

    /**
     * Slots 1-13: [renderLinear], the geometry operator (crop, straighten, quarter turns, flips; [GeometryOp]), then
     * the post-crop vignette and grain ([PostCropOp]). The result is [mapping]'s output size
     * (`outputWidth*outputHeight*3`).
     */
    fun renderGeometry(
        input: PipelineInput,
        recipe: Recipe,
        mapping: GeometryMapping = mapping(input, recipe),
        pixelScale: Float = 1f,
    ): FloatArray {
        val linear = renderLinear(input, recipe, pixelScale)
        val out = if (mapping.isIdentity) linear else GeometryOp.apply(linear, mapping)
        PostCropOp.apply(out, mapping.outputWidth, mapping.outputHeight, PostParams.from(recipe.effects))
        return out
    }

    /** The geometry mapping of [recipe] on [input]: its output size is the size of [render]'s result. */
    fun mapping(input: PipelineInput, recipe: Recipe): GeometryMapping =
        GeometryMapping.of(recipe, input.lens, input.width, input.height)

    /**
     * Full render to RGBA8 (top row first) in the requested output space, [mapping]`(input, recipe)`'s output size
     * (the input's size when the recipe has no geometry).
     */
    fun render(
        input: PipelineInput,
        recipe: Recipe,
        output: OutputSettings = OutputSettings(),
        pixelScale: Float = 1f,
    ): ByteArray {
        val mapping = mapping(input, recipe)
        return OutputTransformOp.apply(
            renderGeometry(input, recipe, mapping, pixelScale),
            mapping.outputWidth,
            mapping.outputHeight,
            output,
        )
    }
}
