@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.raw.RawDecoder
import dev.hfps.reveal.ml.inpaint.InpaintModels
import dev.hfps.reveal.ml.inpaint.InpaintRemoveMethod
import dev.hfps.reveal.ml.inpaint.InpaintService
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.HealStatus
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.input.NativeRawTileSource
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.mask.StrokeCodec
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AI Remove on the owner's camera size (docs/adr/0023 "FIX/H", docs/adr/0024): the CC0 24 MP `sony-zv-e10.arw` in
 * this test app's `files/samples`, the editor's setup (2400 px proxy, the full decode as the original), big-LaMa
 * registered with a fresh `RemoveFills`. Logs `REVEAL_ML heal 24 MP AI ...`: a new 300 px stroke (the model loaded
 * and released, as the app does), its drag-release with only the release sent (the editor since FIX/H) and with every
 * drag frame sent (before FIX/H). Skipped without the sample; emulators: `-e backend cpu`.
 */
@RunWith(AndroidJUnit4::class)
class AiRemove24MpBenchmark {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aiRemoveOn24Mp(): Unit = runBlocking {
        val sample = File(target.filesDir, "samples/sony-zv-e10.arw")
        assumeTrue("no files/samples/sony-zv-e10.arw", sample.isFile)
        val proxy = ImageInput.fromFile(sample).use { ProxyBuilder.decodeProxy(it, 2400) }
        val input = PipelineInputMapper.map(proxy.image, proxy.info)
        val decoded = ImageInput.fromFile(sample).use { RawDecoder.decodeNative(it.descriptor) }
        val full = NativeRawTileSource(decoded.image, decoded.info)
        val k = maxOf(full.width, full.height).toFloat() / maxOf(input.width, input.height)
        val service = InpaintService({ spec ->
            LiteRtEngine.load(
                LiteRtEngine.Source.Asset(target.assets, MaskModels.assetPath(spec)),
                listOf(Fixtures.preferred),
            )
        })
        val fills = RemoveFills(executor = Executors.newSingleThreadExecutor())
        InpaintRemoveMethod.registerAll(fills, service)
        val renderer = GpuDevelopRenderer.create()
        val status = AtomicReference(HealStatus())
        val frame = 1080 to 720
        suspend fun untilShown(recipe: Recipe): Double {
            val start = System.nanoTime()
            renderer.setRecipe(recipe)
            while (true) {
                renderer.benchmark(listOf(recipe), frame)
                val pending = status.get().pending.any { id -> recipe.heals.any { it.id == id } }
                val ms = (System.nanoTime() - start) / 1e6
                if (!pending || ms > 180_000) return ms
            }
        }
        fun stroke(cx: Float): Heal {
            val y = 0.45f * full.height
            val x = cx * full.width
            val points = (0..30).map { i -> x - 150f + 300f * i / 30 to y }
            return HealScenes.stroke("ai", Heal.KIND_REMOVE, full.width, full.height, points, 0.012f * full.width)
                .copy(method = InpaintModels.BIG_LAMA.method)
        }
        fun shifted(h: Heal, px: Float) = h.copy(
            stroke = StrokeCodec.encode(h.strokePoints.map { it.copy(x = it.x + px / full.width) }),
        )
        try {
            renderer.upload(input, k)
            renderer.setFullResolution(full, k)
            renderer.setHealSource(fills)
            renderer.onHeals = { status.set(it) }
            untilShown(Recipe())
            val first = stroke(0.35f)
            val placed = untilShown(Recipe(heals = listOf(first)))
            Log.i(TAG, "heal 24 MP AI: new Remove stroke (300 px): ${"%.0f".format(placed)} ms")
            // Release only (the editor since FIX/H): +400 px; then every frame sent (before FIX/H): +400 px more.
            var at = first
            for (perFrame in listOf(false, true)) {
                var worst = 0.0
                for (i in 1..30) {
                    val t = System.nanoTime()
                    val r = Recipe(heals = listOf(if (perFrame) shifted(at, 400f * i / 30) else at))
                    renderer.setRecipe(r)
                    renderer.benchmark(listOf(r), frame)
                    worst = maxOf(worst, (System.nanoTime() - t) / 1e6)
                }
                at = shifted(at, 400f)
                val release = untilShown(Recipe(heals = listOf(at)))
                Log.i(
                    TAG,
                    "heal 24 MP AI: drag-release, ${if (perFrame) "every frame sent" else "release only"}: " +
                        "${"%.0f".format(release)} ms after the release (drag frames worst ${"%.0f".format(worst)} ms)",
                )
            }
        } finally {
            renderer.close()
            service.close()
        }
    }

    private companion object {
        const val TAG = "REVEAL_ML"
    }
}
