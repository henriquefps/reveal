package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.TileSink
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.heal.HealContext
import dev.hfps.reveal.pipeline.heal.PixelReader
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.heal.RemoveRequest
import dev.hfps.reveal.pipeline.heal.RemoveSource
import dev.hfps.reveal.pipeline.input.PipelineInputTileSource
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Healing on the GPU (docs/adr/0023): the preview equals the CPU reference within ADR 0002's tolerances for every kind,
 * partial updates (a new spot, a source drag) end where a fresh render does bit for bit, a Remove fill that arrives
 * later renders, Visualise spots shows the dust, and a tiled export equals one tile and the preview. Numbers under
 * `REVEAL_HEAL`.
 */
@RunWith(AndroidJUnit4::class)
class HealRenderTest {
    private lateinit var renderer: GpuDevelopRenderer
    private val w = 320
    private val h = 214
    private val clean = HealScenes.texturedGradient(w, h)
    private val image = HealScenes.withDust(HealScenes.withDust(clean, 100f, 80f, 7f), 220f, 140f, 6f)
    private val fills = RemoveFills(executor = Executors.newSingleThreadExecutor())

    @Before
    fun setUp(): Unit = runBlocking {
        renderer = GpuDevelopRenderer.create()
        renderer.upload(image)
        renderer.setHealSource(fills)
        // The Remove crops of the preview and of the CPU reference are read from the same original.
        renderer.setFullResolution(PipelineInputTileSource(image), 1f)
    }

    @After
    fun tearDown() {
        renderer.close()
    }

    private fun spots(): List<Heal> = listOf(
        HealScenes.spot("a", Heal.KIND_HEAL, w, h, 100f, 80f, 10f, ox = 40f, oy = 0f),
        HealScenes.spot("b", Heal.KIND_CLONE, w, h, 220f, 140f, 9f, ox = -30f, oy = -20f),
        HealScenes.stroke("c", Heal.KIND_HEAL, w, h, listOf(40f to 160f, 70f to 170f, 100f to 165f), 6f, oy = -40f),
        HealScenes.spot("d", Heal.KIND_REMOVE, w, h, 260f, 60f, 14f, feather = 30f),
    )

    private val recipes: List<Pair<String, Recipe>> = listOf(
        "spots" to Recipe(heals = spots()),
        "spots + light" to Recipe(
            light = Light(exposureEv = 0.4f, highlights = -30f, shadows = 40f, contrast = 15f),
            heals = spots(),
        ),
        "spots + detail" to Recipe(
            detail = Detail(sharpenAmount = 40f, nrLuminance = 20f),
            effects = Effects(clarity = 20f),
            heals = spots(),
        ),
    )

    @Test
    fun healsMatchCpuReference() = runBlocking {
        val failures = mutableListOf<String>()
        for ((label, recipe) in recipes) {
            val cpu = CpuDevelop.renderLinear(image, recipe, heals = HealContext(fills, PixelReader.of(image)))
            val gpu = renderer.renderLinear(recipe)
            val detail = recipe.detail != Detail() || recipe.effects != Effects()
            var worst = 0f
            var where = ""
            var sum = 0.0
            for (p in 0 until w * h) {
                val scale = maxOf(abs(cpu[p * 3]), abs(cpu[p * 3 + 1]), abs(cpu[p * 3 + 2]))
                for (c in 0 until 3) {
                    val error = abs(gpu[p * 3 + c] - cpu[p * 3 + c])
                    sum += error
                    val allowed = if (detail) DETAIL_ABS + DETAIL_REL * scale else LIGHT_ABS + LIGHT_REL * scale
                    if (error / allowed > worst) {
                        worst = error / allowed
                        where = "(${p % w}, ${p / w}) c$c cpu ${cpu[p * 3 + c]} gpu ${gpu[p * 3 + c]}"
                    }
                }
            }
            val mean = sum / (w * h * 3)
            val out = maxDiff8(
                CpuDevelop.render(image, recipe, heals = HealContext(fills, PixelReader.of(image))),
                renderer.renderOutput(recipe),
            )
            Log.i(TAG, "heals / $label: worst error / tolerance $worst at $where, mean $mean, 8-bit max diff $out")
            if (worst > 1f) failures += "$label ($worst)"
            if (out > OUTPUT_MAX_LEVELS) failures += "$label (8-bit $out)"
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun healingRemovesTheDustOnTheGpu() = runBlocking {
        val healed = renderer.renderLinear(Recipe(heals = spots()))
        val plain = renderer.renderLinear(Recipe())
        val reference = CpuDevelop.renderLinear(clean, Recipe())
        val before = HealScenes.meanError(plain, reference, w, 92, 72, 108, 88)
        val after = HealScenes.meanError(healed, reference, w, 92, 72, 108, 88)
        Log.i(TAG, "dust: mean error to the clean image $before -> $after")
        assertThat(after).isLessThan(before * 0.25f)
    }

    /**
     * docs/adr/0023 "Partial updates": a new spot and a source drag redraw the healed input's rectangle, the scene
     * there, the guide's rows and the develop output grown by the guide's reach; the result equals a fresh render.
     */
    @Test
    fun partialUpdatesEqualAFreshRender() = runBlocking {
        val base = Recipe(
            light = Light(highlights = -20f, shadows = 30f),
            detail = Detail(nrLuminance = 15f, sharpenAmount = 30f),
            effects = Effects(texture = 10f, clarity = 20f),
        )
        val all = spots().take(3)
        val before = renderer.partialUpdates
        renderer.renderLinear(base)
        for (n in 1..all.size) renderer.benchmark(listOf(base.copy(heals = all.take(n))), 110 to 74)
        for (k in 0..8) {
            val moved = all.mapIndexed { i, s -> if (i == 1) s.copy(dx = s.dx + k * 0.004f) else s }
            renderer.renderLinear(base.copy(heals = moved))
        }
        val last = all.mapIndexed { i, s -> if (i == 1) s.copy(dx = s.dx + 8 * 0.004f) else s }
        val partial = renderer.partialUpdates - before
        val incremental = renderer.renderLinear(base.copy(heals = last))
        val fresh = GpuDevelopRenderer.create()
        val reference = try {
            fresh.upload(image)
            fresh.setHealSource(fills)
            fresh.setFullResolution(PipelineInputTileSource(image), 1f)
            fresh.renderLinear(base.copy(heals = last))
        } finally {
            fresh.close()
        }
        var worst = 0f
        for (i in reference.indices) worst = maxOf(worst, abs(reference[i] - incremental[i]))
        Log.i(TAG, "partial updates: $partial, max |incremental - fresh| $worst")
        assertThat(partial).isAtLeast(8)
        assertThat(worst).isEqualTo(0f)
    }

    @Test
    fun aRemoveFillThatArrivesLaterRenders() = runBlocking {
        val later = AtomicReference<(() -> Unit)?>(null)
        val gate = AtomicReference(false)
        val slow = object : RemoveSource {
            override fun fill(request: RemoveRequest, onReady: () -> Unit) = if (gate.get()) {
                fills.await(request)
            } else {
                later.set(onReady)
                null
            }
        }
        renderer.setHealSource(slow)
        val remove = Recipe(heals = listOf(spots()[3]))
        val waiting = renderer.renderLinear(remove)
        val plain = renderer.renderLinear(Recipe())
        assertThat(waiting).isEqualTo(plain)
        gate.set(true)
        later.get()?.invoke()
        // The callback posts a re-render; ask again after it.
        var filled = renderer.renderLinear(remove)
        repeat(20) { if (filled.contentEquals(plain)) filled = renderer.renderLinear(remove) }
        assertThat(filled.contentEquals(plain)).isFalse()
    }

    @Test
    fun visualiseSpotsShowsTheDust() = runBlocking {
        renderer.setVisualiseSpots(70f)
        val view = ViewTransform(1f, w / 2f, h / 2f)
        val out = renderer.renderView(view, w, h, OutputSettings(dither = false))
        renderer.setVisualiseSpots(null)
        fun grey(x: Int, y: Int) = out[(y * w + x) * 4].toInt() and 0xff
        // The dust is darker than its neighbourhood: below mid-grey; the clean gradient sits near it.
        val dust = grey(100, 80)
        val around = grey(150, 40)
        Log.i(TAG, "visualise: dust $dust, clean $around")
        assertThat(dust).isLessThan(around - 40)
        assertThat(abs(around - 128)).isLessThan(40)
    }

    @Test
    fun tiledExportEqualsOneTileAndThePreview() = runBlocking {
        val recipe = recipes[1].second
        val source = PipelineInputTileSource(image)
        val small = TiledRenderer.create(256)
        val big = TiledRenderer.create(1024)
        try {
            val a = export(small, source, recipe)
            val b = export(big, source, recipe)
            var tiles = 0
            for (i in a.indices) tiles = maxOf(tiles, abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)))
            val preview = renderer.renderOutput(recipe)
            val toPreview = maxDiff8(a, preview)
            Log.i(TAG, "tiled export: 256 vs 1024 px tiles max diff $tiles, vs preview $toPreview")
            assertThat(tiles).isEqualTo(0)
            assertThat(toPreview).isAtMost(OUTPUT_MAX_LEVELS)
        } finally {
            small.close()
            big.close()
        }
    }

    private fun export(renderer: TiledRenderer, source: PipelineInputTileSource, recipe: Recipe): ByteArray {
        val plan = ExportPlan.of(
            w,
            h,
            recipe.geometry,
            dev.hfps.reveal.pipeline.export.ExportSize.Original,
            renderer.tileSize,
            dev.hfps.reveal.pipeline.cpu.DevelopParams.reach(recipe),
        )
        val out = ByteArray(w * h * 4)
        val sink = object : TileSink {
            override fun accept(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer) {
                for (row in 0 until h) {
                    rgba.position(row * w * 4)
                    rgba.get(out, ((y + row) * this@HealRenderTest.w + x) * 4, w * 4)
                }
            }
        }
        renderer.render(source, recipe, plan, OutputSettings(), sink, heals = fills)
        return out
    }

    private fun maxDiff8(a: ByteArray, b: ByteArray): Int {
        var worst = 0
        for (i in a.indices) {
            if (i % 4 == 3) continue
            worst = maxOf(worst, abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)))
        }
        return worst
    }

    private companion object {
        const val TAG = "REVEAL_HEAL"

        /** ADR 0002's tolerances for the Light and Detail recipes (GpuParityTest). */
        const val LIGHT_ABS = 2e-3f
        const val LIGHT_REL = 2e-2f
        const val DETAIL_ABS = 4e-3f
        const val DETAIL_REL = 3e-2f
        const val OUTPUT_MAX_LEVELS = 4
    }
}
