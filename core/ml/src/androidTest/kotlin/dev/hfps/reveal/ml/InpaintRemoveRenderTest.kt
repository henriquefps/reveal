@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.inpaint.InpaintModels
import dev.hfps.reveal.ml.inpaint.InpaintRemoveMethod
import dev.hfps.reveal.ml.inpaint.InpaintService
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.TileSink
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.heal.HealContext
import dev.hfps.reveal.pipeline.heal.HealOp
import dev.hfps.reveal.pipeline.heal.PixelReader
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.heal.RemoveSpec
import dev.hfps.reveal.pipeline.input.PipelineInputTileSource
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AI Remove through the heal engine (docs/adr/0023, 0024): the bundled model registered with `RemoveFills`, a large
 * spot on a noisy 1600 x 1200 image (the Remove crop is downscaled by 2, so the fill is upsampled with grain): the
 * object goes, and a tiled export equals one tile size and the preview (the same fill by key, the same grain by pixel).
 * Tag `REVEAL_ML` (`inpaint remove ...`). Emulators: `-e backend cpu` runs the model on the CPU (slow, ~20 s).
 */
@RunWith(AndroidJUnit4::class)
class InpaintRemoveRenderTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val w = 1600
    private val h = 1200

    private fun scene(): PipelineInput {
        val rnd = Random(11)
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = 0.12f + 0.3f * x / w + 0.15f * y / h
                for (c in 0..2) {
                    rgb[(y * w + x) * 3 + c] =
                        base * (0.9f + 0.1f * c) + 0.01f * rnd.nextGaussian().toFloat()
                }
            }
        }
        return HealScenes.withObject(PipelineInput.linearSrgb(w, h, rgb), 800, 600, 220)
    }

    @Test
    fun aiRemoveExportEqualsThePreview() = runBlocking {
        val image = scene()
        val service = InpaintService({ spec ->
            LiteRtEngine.load(
                LiteRtEngine.Source.Asset(target.assets, MaskModels.assetPath(spec)),
                listOf(Fixtures.preferred),
            )
        })
        val fills = RemoveFills(executor = Executors.newSingleThreadExecutor())
        InpaintRemoveMethod.registerAll(fills, service)
        val spot = HealScenes.spot("ai", Heal.KIND_REMOVE, w, h, 800f, 600f, 170f, feather = 20f)
            .copy(method = InpaintModels.BIG_LAMA.method)
        assertThat(checkNotNull(RemoveSpec.of(spot, w, h)).factor).isGreaterThan(1)
        val recipe = Recipe(heals = listOf(spot))
        // The CPU reference waits for the fill; the preview and the exports then share it by key.
        val t0 = System.nanoTime()
        val cpu = HealOp.apply(image, listOf(spot), HealContext(fills, PixelReader.of(image))).linearRgb
        val fillMs = (System.nanoTime() - t0) / 1e6
        val i = (600 * w + 800) * 3
        val redBefore = image.linearRgb[i] - image.linearRgb[i + 1]
        val redAfter = cpu[i] - cpu[i + 1]
        assertThat(redAfter).isLessThan(redBefore * 0.3f)
        val renderer = GpuDevelopRenderer.create()
        val small = TiledRenderer.create(256)
        val big = TiledRenderer.create(1024)
        try {
            renderer.upload(image)
            renderer.setHealSource(fills)
            renderer.setFullResolution(PipelineInputTileSource(image), 1f)
            val source = PipelineInputTileSource(image)
            val a = export(small, source, recipe, fills)
            val b = export(big, source, recipe, fills)
            val preview = renderer.renderOutput(recipe)
            val tiles = maxDiff8(a, b)
            val toPreview = maxDiff8(a, preview)
            Log.i(
                TAG,
                "inpaint remove fill=${"%.0f".format(fillMs)} ms (${service.backend ?: "released"}) red " +
                    "${"%.3f".format(
                        redBefore,
                    )} -> ${"%.3f".format(redAfter)}; tiles 256 vs 1024 max $tiles, vs preview $toPreview",
            )
            assertThat(tiles).isEqualTo(0)
            assertThat(toPreview).isAtMost(OUTPUT_MAX_LEVELS)
        } finally {
            renderer.close()
            small.close()
            big.close()
            service.close()
        }
    }

    private fun export(
        renderer: TiledRenderer,
        source: PipelineInputTileSource,
        recipe: Recipe,
        fills: RemoveFills,
    ): ByteArray {
        val plan = ExportPlan.of(
            w,
            h,
            recipe.geometry,
            ExportSize.Original,
            renderer.tileSize,
            DevelopParams.reach(recipe),
        )
        val out = ByteArray(w * h * 4)
        val sink = object : TileSink {
            override fun accept(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer) {
                for (row in 0 until h) {
                    rgba.position(row * w * 4)
                    rgba.get(out, ((y + row) * this@InpaintRemoveRenderTest.w + x) * 4, w * 4)
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
        const val TAG = "REVEAL_ML"

        /** As the heal engine's own preview-vs-export test (docs/adr/0023, `HealRenderTest`). */
        const val OUTPUT_MAX_LEVELS = 4
    }
}
