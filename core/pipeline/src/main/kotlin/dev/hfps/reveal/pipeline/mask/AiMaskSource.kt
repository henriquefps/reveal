package dev.hfps.reveal.pipeline.mask

import dev.hfps.reveal.pipeline.recipe.MaskComponent

/**
 * Where the engine gets an AI mask (docs/adr/0022, "AI masks"): the engine never runs a model. `:core:ml` implements it
 * (M4/C: inference, its cache keyed as docs/adr/0021 says, edge refinement with [MaskComponent.Ai.refine]); the editor
 * wires it into the renderers (M4/B). Tests use a fake.
 *
 * Called on a renderer's GL thread: [mask] must not block on inference. It returns the mask when it has it, else null
 * (the component is then skipped: it contributes nothing) and starts computing it, then calls `onReady` (from any
 * thread) once; the renderer then renders again and asks again.
 */
fun interface AiMaskSource {
    /** The mask of [request], or null while it is not ready (then [onReady] is called once it is). */
    fun mask(request: AiMaskRequest, onReady: () -> Unit): AiMask?

    companion object {
        /** No source: every AI component is skipped. */
        val NONE: AiMaskSource = AiMaskSource { _, _ -> null }
    }
}

/**
 * What the renderer asks for: the AI [component] of the photo [photoKey] (the source's cache key, e.g. its content
 * fingerprint), for an upright image of [imageWidth] x [imageHeight] pixels (the source may answer at any resolution:
 * the engine resamples).
 */
data class AiMaskRequest(
    val photoKey: String,
    val component: MaskComponent.Ai,
    val imageWidth: Int,
    val imageHeight: Int,
)

/**
 * A single-channel mask over the whole upright image: [width] x [height] values in 0..1, row-major, top row first.
 * Sampled bilinearly with explicit weights at `(pixel + 0.5) * width / imageWidth - 0.5`, taps clamped.
 */
class AiMask(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0 && values.size == width * height) { "mask size mismatch" }
    }
}

/** The AI source and the photo it renders: what a render needs to resolve its AI components. */
data class MaskContext(val source: AiMaskSource = AiMaskSource.NONE, val photoKey: String = "") {
    /** The mask of [component] for an [imageWidth] x [imageHeight] image, or null (not ready, or no source). */
    fun ai(component: MaskComponent.Ai, imageWidth: Int, imageHeight: Int, onReady: () -> Unit = {}): AiMask? =
        if (source === AiMaskSource.NONE) {
            null
        } else {
            source.mask(AiMaskRequest(photoKey, component, imageWidth, imageHeight), onReady)
        }

    companion object {
        val NONE: MaskContext = MaskContext()
    }
}
