package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Recipe

/**
 * The numbers every operator needs for one (input, recipe) pair, computed once in double precision on the CPU and
 * rounded to float. The CPU reference and the GPU renderer both start from this object, so they cannot disagree on
 * a constant; they can only disagree on arithmetic, which is what the parity test measures.
 *
 * @property wbMultipliers raw white-balance multipliers (R, G, B), G = 1.
 * @property cameraToWorking row-major 3x3, balanced camera RGB to linear Rec.2020.
 * @property exposureGain linear gain `2^EV`.
 * @property toneKnee knee of the base tone curve.
 */
class DevelopParams(
    val wbMultipliers: FloatArray,
    val cameraToWorking: FloatArray,
    val exposureGain: Float,
    val toneKnee: Float,
) {
    override fun equals(other: Any?): Boolean = other is DevelopParams &&
        wbMultipliers.contentEquals(other.wbMultipliers) &&
        cameraToWorking.contentEquals(other.cameraToWorking) &&
        exposureGain == other.exposureGain &&
        toneKnee == other.toneKnee

    override fun hashCode(): Int {
        var h = wbMultipliers.contentHashCode()
        h = h * HASH + cameraToWorking.contentHashCode()
        h = h * HASH + exposureGain.hashCode()
        return h * HASH + toneKnee.hashCode()
    }

    companion object {
        private const val HASH = 31

        /** Resolves [recipe] against [input]. */
        fun from(input: PipelineInput, recipe: Recipe): DevelopParams {
            val wb = WhiteBalanceModel.solve(input, recipe.colour.whiteBalance)
            return DevelopParams(
                wbMultipliers = FloatArray(PipelineInput.CHANNELS) { wb.multipliers[it].toFloat() },
                cameraToWorking = wb.balancedToWorking.toFloatArray(),
                exposureGain = ExposureOp.gain(recipe.light.exposureEv),
                toneKnee = BaseToneOp.KNEE,
            )
        }
    }
}
