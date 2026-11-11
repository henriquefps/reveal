package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Recipe

/**
 * The CPU reference pipeline: the operators in their fixed order (docs/adr/0002, docs/adr/0007). Pure Kotlin over
 * float arrays, deterministic (no threads, no platform-dependent maths), the oracle for unit tests and GPU parity.
 */
object CpuDevelop {
    /**
     * Runs raw WB, highlight reconstruction, camera matrix, exposure, highlights/shadows, the profile (base look),
     * contrast/whites/blacks and vibrance/saturation, returning linear Rec.2020 RGB (`width*height*3`,
     * display-referred below 1.0) before the output transform. [input] is not modified.
     */
    fun renderLinear(input: PipelineInput, recipe: Recipe): FloatArray {
        val params = DevelopParams.from(input, recipe)
        val grid = LowResGrid(input.width, input.height)
        val rgb = input.linearRgb.copyOf()
        RawWhiteBalanceOp.apply(rgb, params.wbMultipliers)
        val hp = params.highlights
        // Skipped when nothing reaches the clip ramp: the operator is then the identity (the GPU skips it too).
        if (hp != null && HighlightReconstructionOp.needed(input.linearRgb, checkNotNull(input.saturation))) {
            HighlightReconstructionOp.apply(rgb, grid, hp, HighlightReconstructionOp.chroma(rgb, grid, hp))
        }
        CameraMatrixOp.apply(rgb, params.cameraToWorking)
        ExposureOp.apply(rgb, params.exposureGain)
        if (params.tone.localActive) {
            val moments = LocalToneOp.moments(rgb, grid)
            LocalToneOp.apply(rgb, grid, moments, LocalToneOp.guide(moments, grid), params.tone)
        }
        ProfileOp.apply(rgb, params.profile)
        if (params.tone.globalActive) GlobalToneOp.apply(rgb, params.tone)
        VibranceSaturationOp.apply(rgb, params.colour)
        return rgb
    }

    /** Full render to RGBA8 (top row first) in the requested output space. */
    fun render(input: PipelineInput, recipe: Recipe, output: OutputSettings = OutputSettings()): ByteArray =
        OutputTransformOp.apply(renderLinear(input, recipe), input.width, input.height, output)
}
