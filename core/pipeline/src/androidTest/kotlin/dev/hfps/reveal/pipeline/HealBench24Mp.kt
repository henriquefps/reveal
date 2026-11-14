package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.raw.RawDecoder
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.HealStatus
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.heal.RemoveSource
import dev.hfps.reveal.pipeline.input.NativeRawTileSource
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * docs/adr/0023 "FIX/H": healing on the owner's camera size, the CC0 24 MP `sony-zv-e10.arw` in this test app's
 * `files/samples` (`scripts/fetch-samples.sh`; skipped without it). The editor's setup: the proxy (2400 px) on the
 * renderer, the full-resolution decode as the original Remove's crops are read from, a fresh [RemoveFills] (cold).
 * Timed from the edit to the frame that shows it: a new Remove stroke (300 px long, 0.012 of the long edge, the
 * editor's default size), the same stroke dragged (30 frames then the release; with [perFrame] every frame's position
 * is sent, as the editor did before FIX/H, otherwise only the release, as it does now), a new heal spot and a heal
 * spot's drag-release. Offscreen frames at [frame] drive the renderer (as the editor's frames do).
 *
 * Written against the renderer's public API of 0.7.0 as well, so the same file measures the code before FIX/H.
 */
class HealBench24Mp(private val tag: String, private val frame: Pair<Int, Int>?) {
    private val sample = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "samples/sony-zv-e10.arw",
    )

    /** One renderer with the proxy and the original; [fills] serve its Remove spots. */
    class Session(val renderer: GpuDevelopRenderer, val width: Int, val height: Int) {
        val status = AtomicReference(HealStatus())
    }

    /** Runs [block] on the 24 MP sample with [fills]; false (logged) when the sample is missing. */
    suspend fun withSample(fills: RemoveSource, block: suspend (Session) -> Unit): Boolean {
        if (!sample.isFile) {
            Log.i(tag, "heal 24 MP: no files/samples/sony-zv-e10.arw, skipped")
            return false
        }
        val proxy = ImageInput.fromFile(sample).use { ProxyBuilder.decodeProxy(it, PROXY_EDGE) }
        val input = PipelineInputMapper.map(proxy.image, proxy.info)
        val decoded = ImageInput.fromFile(sample).use { RawDecoder.decodeNative(it.descriptor) }
        val full = NativeRawTileSource(decoded.image, decoded.info)
        val k = maxOf(full.width, full.height).toFloat() / maxOf(input.width, input.height)
        val renderer = GpuDevelopRenderer.create()
        try {
            renderer.upload(input, k)
            renderer.setFullResolution(full, k)
            renderer.setHealSource(fills)
            // The editor's warm-up (docs/adr/0023 "FIX/H") runs while the photo opens: let it finish, as it would.
            val warm = System.nanoTime()
            while (!renderer.healIdle) kotlinx.coroutines.delay(5)
            Log.i(tag, "heal 24 MP: warm-up ${"%.0f".format((System.nanoTime() - warm) / NANOS_PER_MS)} ms")
            val session = Session(renderer, full.width, full.height)
            renderer.onHeals = { session.status.set(it) }
            Log.i(tag, "heal 24 MP: original ${full.width}x${full.height}, proxy ${input.width}x${input.height}")
            val t = System.nanoTime()
            dev.hfps.reveal.pipeline.heal.RemoveSpec.identity(dev.hfps.reveal.pipeline.heal.PixelReader.of(full))
            Log.i(
                tag,
                "heal 24 MP: identity of the original ${"%.0f".format((System.nanoTime() - t) / NANOS_PER_MS)} ms",
            )
            block(session)
        } finally {
            renderer.close()
        }
        return true
    }

    /** A Remove stroke of [length] original pixels, horizontal, centred at ([cx], [cy]) (fractions). */
    fun removeStroke(s: Session, id: String, cx: Float, cy: Float, length: Float = STROKE_PX, method: String): Heal {
        val x = cx * s.width
        val y = cy * s.height
        val points = (0..STROKE_POINTS).map { i -> x - length / 2 + length * i / STROKE_POINTS to y }
        return HealScenes.stroke(id, Heal.KIND_REMOVE, s.width, s.height, points, RADIUS * s.width)
            .copy(method = method)
    }

    /** A heal spot of the default size at ([cx], [cy]) copying from 3 radii to the right. */
    fun healSpot(s: Session, id: String, cx: Float, cy: Float): Heal {
        val r = RADIUS * s.width
        return HealScenes.spot(id, Heal.KIND_HEAL, s.width, s.height, cx * s.width, cy * s.height, r, ox = 3 * r)
    }

    /**
     * Milliseconds from sending [recipe] to the first frame where no Remove spot is pending (frames rendered as the
     * editor would, one after another), at most [limitMs].
     */
    suspend fun untilShown(s: Session, recipe: Recipe, limitMs: Long = LIMIT_MS): Double {
        val start = System.nanoTime()
        s.renderer.setRecipe(recipe)
        var frames = 0
        while (true) {
            s.renderer.benchmark(listOf(recipe), frame)
            frames++
            val pending = s.status.get().pending.any { id -> recipe.heals.any { it.id == id } }
            val ms = (System.nanoTime() - start) / NANOS_PER_MS
            if (!pending || ms > limitMs) return ms
        }
    }

    /**
     * A drag of [heal] by [dx] original pixels over [frames] frames, then the release: per frame the recipe with the
     * spot moved (when [perFrame]) or nothing new (the overlay only), then the time from the release to the frame that
     * shows the final spot. Returns (release to shown, worst frame during the drag) in ms.
     */
    suspend fun dragRelease(
        s: Session,
        base: Recipe,
        heal: Heal,
        dx: Float,
        frames: Int,
        perFrame: Boolean,
    ): Pair<Double, Double> {
        var worst = 0.0
        for (i in 1..frames) {
            val t = System.nanoTime()
            val moved = moved(heal, dx * i / frames / s.width)
            val recipe = if (perFrame) {
                base.copy(
                    heals = base.heals.map {
                        if (it.id ==
                            heal.id
                        ) {
                            moved
                        } else {
                            it
                        }
                    },
                )
            } else {
                base
            }
            s.renderer.setRecipe(recipe)
            s.renderer.benchmark(listOf(recipe), frame)
            worst = maxOf(worst, (System.nanoTime() - t) / NANOS_PER_MS)
        }
        val final = base.copy(heals = base.heals.map { if (it.id == heal.id) moved(heal, dx / s.width) else it })
        return untilShown(s, final) to worst
    }

    fun moved(heal: Heal, fx: Float): Heal = if (heal.isStroke) {
        heal.copy(
            stroke = dev.hfps.reveal.pipeline.mask.StrokeCodec.encode(
                heal.strokePoints.map { it.copy(x = it.x + fx) },
            ),
        )
    } else {
        heal.copy(x = heal.x + fx)
    }

    companion object {
        const val PROXY_EDGE = 2400
        const val STROKE_PX = 300f
        const val STROKE_POINTS = 30

        /** The editor's default spot size (`HealSettings.DEFAULT_SIZE`), a fraction of the long edge. */
        const val RADIUS = 0.012f
        const val LIMIT_MS = 120_000L
        private const val NANOS_PER_MS = 1e6

        fun fills(): RemoveFills = RemoveFills(executor = java.util.concurrent.Executors.newSingleThreadExecutor())
    }
}
