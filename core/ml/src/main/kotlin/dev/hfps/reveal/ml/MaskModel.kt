package dev.hfps.reveal.ml

import dev.hfps.reveal.ml.runtime.InferenceEngine

/** A point the user tapped, in normalised image coordinates (0..1), for interactive models. */
data class MaskPrompt(val x: Float, val y: Float)

/** The masks of one run at the model's output resolution, and what the run cost. */
class MaskResult(val masks: Map<MaskKind, Mask>, val inferenceMs: Double, val totalMs: Double)

/**
 * A model that produces masks (docs/adr/0021). [segment] runs it on a display image of the photo and returns every
 * kind it knows, at its own resolution: the caller resizes and refines ([dev.hfps.reveal.ml.refine.GuidedRefiner]).
 * Not thread-safe: one run at a time per instance; [close] releases the runtime.
 */
interface MaskModel : AutoCloseable {
    val spec: ModelSpec
    val kinds: Set<MaskKind> get() = spec.kinds.keys

    fun segment(image: MlImage, prompt: MaskPrompt? = null): MaskResult
}

/** A [MaskModel] over any [InferenceEngine]: encode, run, decode, map classes to kinds. */
class SegmentationModel(override val spec: ModelSpec, private val engine: InferenceEngine) : MaskModel {
    override fun segment(image: MlImage, prompt: MaskPrompt?): MaskResult {
        require(prompt == null || spec.interactive) { "${spec.id} takes no prompt" }
        require(prompt != null || !spec.interactive) { "${spec.id} needs a prompt" }
        val start = System.nanoTime()
        val input = spec.input.encode(image, prompt?.let { promptChannel(it) })
        val t0 = System.nanoTime()
        val raw = engine.run(input)
        val t1 = System.nanoTime()
        val planes = spec.output.decode(raw, spec.kinds.values.flatMap { it.channels }.toSet())
        val masks = spec.kinds.mapValues { (_, rule) -> Mask(spec.output.width, spec.output.height, rule.of(planes)) }
        val end = System.nanoTime()
        return MaskResult(masks, (t1 - t0) / NANOS_PER_MS, (end - start) / NANOS_PER_MS)
    }

    /** The prompt channel: a small disc of ones around the tapped point (MediaPipe's interactive segmenter input). */
    internal fun promptChannel(p: MaskPrompt): FloatArray {
        val w = spec.input.width
        val h = spec.input.height
        val cx = p.x.coerceIn(0f, 1f) * (w - 1)
        val cy = p.y.coerceIn(0f, 1f) * (h - 1)
        val r2 = PROMPT_RADIUS * PROMPT_RADIUS
        return FloatArray(w * h) { i ->
            val dx = i % w - cx
            val dy = i / w - cy
            if (dx * dx + dy * dy <= r2) 1f else 0f
        }
    }

    override fun close() = engine.close()

    private companion object {
        const val NANOS_PER_MS = 1e6
        const val PROMPT_RADIUS = 3f
    }
}
