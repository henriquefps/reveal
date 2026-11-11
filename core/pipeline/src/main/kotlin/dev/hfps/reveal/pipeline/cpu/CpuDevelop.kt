package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Recipe

/**
 * The CPU reference pipeline: the operators in their fixed order (docs/adr/0002). Pure Kotlin over float arrays,
 * deterministic (no threads, no platform-dependent maths), the oracle for unit tests and GPU parity.
 */
object CpuDevelop {
    /**
     * Runs raw WB, camera matrix, exposure and the base tone curve, returning linear Rec.2020 RGB
     * (`width*height*3`, display-referred below 1.0) before the output transform. [input] is not modified.
     */
    fun renderLinear(input: PipelineInput, recipe: Recipe): FloatArray {
        val params = DevelopParams.from(input, recipe)
        val rgb = input.linearRgb.copyOf()
        RawWhiteBalanceOp.apply(rgb, params.wbMultipliers, params.highlightClip)
        CameraMatrixOp.apply(rgb, params.cameraToWorking)
        ExposureOp.apply(rgb, params.exposureGain)
        BaseToneOp.apply(rgb, params.toneKnee)
        return rgb
    }

    /** Full render to RGBA8 (top row first) in the requested output space. */
    fun render(input: PipelineInput, recipe: Recipe, output: OutputSettings = OutputSettings()): ByteArray =
        OutputTransformOp.apply(renderLinear(input, recipe), input.width, input.height, output)
}
