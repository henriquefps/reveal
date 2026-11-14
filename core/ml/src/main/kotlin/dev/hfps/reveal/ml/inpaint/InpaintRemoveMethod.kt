package dev.hfps.reveal.ml.inpaint

import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.heal.RemoveInput
import dev.hfps.reveal.pipeline.heal.RemoveMethod
import dev.hfps.reveal.pipeline.heal.RemoveOutput
import dev.hfps.reveal.pipeline.heal.RemoveUnavailableException

/**
 * Remove's `model:<id>@<version>` method (docs/adr/0023 contract, docs/adr/0024): the crop through [service]. The
 * output has no offsets: the heal engine upsamples the fill and adds grain where the render is finer than the crop. A
 * fill the model cannot make (too little memory, a failed load or run) becomes [RemoveUnavailableException]:
 * [RemoveFills] fills that spot with the patch method and marks it ([LOW_MEMORY] or [FAILED]).
 */
class InpaintRemoveMethod(private val service: InpaintService, private val model: InpaintModelSpec) : RemoveMethod {
    override fun fill(input: RemoveInput): RemoveOutput = try {
        RemoveOutput(service.fill(model, input.width, input.height, input.rgb, input.hole).rgb)
    } catch (e: InpaintUnavailableException) {
        throw RemoveUnavailableException(if (e.lowMemory) LOW_MEMORY else FAILED, e)
    }

    companion object {
        /** [RemoveUnavailableException]'s message when there was not enough free memory. */
        const val LOW_MEMORY: String = "low-memory"

        /** ... when the model failed to load or run. */
        const val FAILED: String = "model-failed"

        /** Registers every bundled model with [fills] (the app, when it starts: before anything renders). */
        fun registerAll(fills: RemoveFills, service: InpaintService) {
            for (model in InpaintModels.bundled) fills.register(model.method, InpaintRemoveMethod(service, model))
        }
    }
}
