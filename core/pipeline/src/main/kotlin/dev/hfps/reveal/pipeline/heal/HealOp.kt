package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.colourOnly
import dev.hfps.reveal.pipeline.recipe.Heal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a render needs to resolve its Remove spots (docs/adr/0023): the fills' [source] and the [original] the crops
 * are read from (null: the image being rendered). [NONE]: Remove spots render nothing.
 */
data class HealContext(val source: RemoveSource = RemoveSource.NONE, val original: PixelReader? = null) {
    companion object {
        val NONE: HealContext = HealContext()
    }
}

/** The healing stage of the CPU reference (docs/adr/0023): slot 0, on the input before raw white balance. */
object HealOp {
    private const val WAIT_S = 120L

    /** True when some of [heals] changes pixels. */
    fun active(heals: List<Heal>): Boolean = heals.any { it.renders }

    /**
     * [input] with [heals] applied (a new input sharing nothing with it), or [input] itself when no spot renders.
     * Waits for Remove fills (at most two minutes).
     */
    fun apply(input: PipelineInput, heals: List<Heal>, context: HealContext = HealContext.NONE): PipelineInput {
        if (!active(heals)) return input
        val reader = PixelReader.of(input)
        val colour = input.colourOnly()
        val engine = HealEngine(
            input.width,
            input.height,
            reader,
            colour,
            RemoveContext(context.source, context.original ?: reader, colour),
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        var latch = CountDownLatch(1)
        engine.update(heals) { latch.countDown() }
        while (engine.pending.isNotEmpty() && System.nanoTime() < deadline) {
            latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            latch = CountDownLatch(1)
            val next = latch
            engine.update(heals) { next.countDown() }
        }
        val rgb = input.linearRgb.copyOf()
        engine.overlay(0, 0, input.width, input.height, rgb)
        return PipelineInput(
            input.kind,
            input.width,
            input.height,
            rgb,
            input.cameraToXyz,
            input.asShotMultipliers,
            input.saturation,
            input.noise,
            input.lens,
        )
    }
}
