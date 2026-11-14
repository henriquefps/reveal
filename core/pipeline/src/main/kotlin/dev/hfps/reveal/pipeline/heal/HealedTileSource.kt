package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.TileSource
import dev.hfps.reveal.pipeline.recipe.Heal
import java.util.concurrent.TimeUnit

/**
 * A [TileSource] with healing applied (docs/adr/0023, "Everywhere the same"): the zoom tiles and the export read the
 * full-resolution [base] through it, so every tile gets the same healed pixels whatever its size and apron (the spots
 * are computed once, on the whole image, before the first tile). [base] stays owned by the caller.
 *
 * Remove fills come from [fills] with crops read from [base] itself (the original). With [wait] the constructor
 * blocks until every Remove fill is ready (an export), at most [WAIT_S] seconds; otherwise missing fills render nothing
 * and [onReady] is called when one arrives ([update] again then).
 */
class HealedTileSource(
    val base: TileSource,
    heals: List<Heal>,
    fills: RemoveSource,
    wait: Boolean = false,
    onReady: () -> Unit = {},
) : TileSource {
    private val reader = PixelReader.of(base)
    private val engine = HealEngine(
        base.width,
        base.height,
        reader,
        base.colour,
        RemoveContext(fills, reader, base.colour),
    )

    /** The spots this source renders. */
    var heals: List<Heal> = heals
        private set

    init {
        if (wait) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
            var latch = java.util.concurrent.CountDownLatch(1)
            engine.update(heals) { latch.countDown() }
            while (engine.pending.isNotEmpty() && System.nanoTime() < deadline) {
                latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                latch = java.util.concurrent.CountDownLatch(1)
                val next = latch
                engine.update(heals) { next.countDown() }
            }
        } else {
            engine.update(heals, onReady)
        }
    }

    /** Brings the spots to [heals] (or picks up fills that arrived); returns the rectangle that changed, or null. */
    fun update(heals: List<Heal>, onReady: () -> Unit = {}): IntArray? {
        this.heals = heals
        return engine.update(heals, onReady)
    }

    /** Ids of Remove spots still waiting for their fill. */
    val pending: Set<String> get() = engine.pending

    override val width: Int get() = base.width
    override val height: Int get() = base.height
    override val colour: PipelineInput get() = base.colour

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        synchronized(base) { base.read(x, y, w, h, out) }
        engine.overlay(x, y, w, h, out, RGBA)
    }

    private companion object {
        const val RGBA = 4
        const val WAIT_S = 120L
    }
}
