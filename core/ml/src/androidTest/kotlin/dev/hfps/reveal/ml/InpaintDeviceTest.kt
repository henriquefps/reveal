@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.inpaint.FillDiskCache
import dev.hfps.reveal.ml.inpaint.InpaintModels
import dev.hfps.reveal.ml.inpaint.InpaintService
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled inpainting model on the device (docs/adr/0024): a fill of a public-domain fixture, GPU vs CPU, the
 * cache, and the GPU program cache's effect on a cold load. Tag `REVEAL_ML` (`inpaint ...`). Emulators:
 * `-e backend cpu` (LiteRT's GPU accelerator does not run there; parity is then skipped).
 */
@RunWith(AndroidJUnit4::class)
class InpaintDeviceTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = InpaintModels.BIG_LAMA

    /** The fixture's centre crop ([w] x [h], nearest) and an ellipse hole over a third of it. */
    private fun crop(w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val photo = Fixtures.image("landscape")
        val rgb = FloatArray(w * h * 3)
        val hole = FloatArray(w * h)
        val s = minOf(photo.width / w.toFloat(), photo.height / h.toFloat())
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = ((photo.width - w * s) / 2 + x * s).toInt().coerceIn(0, photo.width - 1)
                val sy = ((photo.height - h * s) / 2 + y * s).toInt().coerceIn(0, photo.height - 1)
                for (c in 0..2) rgb[(y * w + x) * 3 + c] = photo.rgb[(sy * photo.width + sx) * 3 + c]
                val dx = (x - w / 2f) / (w * 0.18f)
                val dy = (y - h / 2f) / (h * 0.3f)
                hole[y * w + x] = if (dx * dx + dy * dy <= 1f) 1f else 0f
            }
        }
        return rgb to hole
    }

    private fun loader(backend: Backend, gpuCache: String? = null) =
        { spec: dev.hfps.reveal.ml.inpaint.InpaintModelSpec ->
            LiteRtEngine.load(
                LiteRtEngine.Source.Asset(target.assets, MaskModels.assetPath(spec)),
                listOf(backend),
                gpuCache = gpuCache?.let { LiteRtEngine.GpuCache(it, spec.key) },
            )
        }

    @Suppress("NestedBlockDepth") // per pixel and channel checks
    @Test
    fun fillsTheHoleAndKeepsEveryOtherPixel() {
        val (rgb, hole) = crop(360, 300)
        val dir = File(target.cacheDir, "inpaint-test").apply { deleteRecursively() }
        InpaintService(loader(Fixtures.preferred), FillDiskCache(dir)).use { service ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            val first = service.fill(model, 360, 300, rgb, hole)
            val cold = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            assertThat(first.fromCache).isFalse()
            var diffFromMean = 0.0
            val holeCount = hole.count { it > 0f }
            val mean = FloatArray(3)
            for (i in hole.indices) {
                if (hole[i] ==
                    0f
                ) {
                    for (c in 0..2) mean[c] += rgb[i * 3 + c] / (hole.size - holeCount)
                }
            }
            for (i in hole.indices) {
                for (c in 0..2) {
                    val v = first.rgb[i * 3 + c]
                    assertThat(v.isFinite()).isTrue()
                    if (hole[i] == 0f) {
                        assertThat(v).isEqualTo(rgb[i * 3 + c])
                    } else {
                        assertThat(v).isIn(com.google.common.collect.Range.closed(-0.01f, 1.01f))
                        diffFromMean += abs(v - mean[c])
                    }
                }
            }
            // a real fill has structure, not a flat average
            assertThat(diffFromMean / (holeCount * 3)).isGreaterThan(0.01)
            val again = service.fill(model, 360, 300, rgb, hole)
            assertThat(again.fromCache).isTrue()
            assertThat(again.rgb).isEqualTo(first.rgb)
            Log.i(
                TAG,
                "inpaint fill ${first.backend} cold=${f1(
                    cold,
                )} model=${f1(first.modelMs)} total=${f1(first.totalMs)} " +
                    "cached=${f1(again.totalMs)}",
            )
        }
        dir.deleteRecursively()
    }

    @Test
    fun gpuMatchesCpu() {
        if (Fixtures.preferred != Backend.GPU) return
        val (rgb, hole) = crop(512, 384)
        val gpu = InpaintService(loader(Backend.GPU)).use { it.fill(model, 512, 384, rgb, hole) }
        val cpu = InpaintService(loader(Backend.CPU)).use { it.fill(model, 512, 384, rgb, hole) }
        assertThat(gpu.backend).isEqualTo(Backend.GPU)
        var sum = 0.0
        var n = 0
        for (i in hole.indices) {
            if (hole[i] > 0f) {
                for (c in 0..2) sum += abs(gpu.rgb[i * 3 + c] - cpu.rgb[i * 3 + c])
                n += 3
            }
        }
        Log.i(
            TAG,
            "inpaint parity hole meanAbs=${"%.4f".format(
                Locale.ROOT,
                sum / n,
            )} gpu=${f1(gpu.modelMs)} cpu=${f1(cpu.modelMs)}",
        )
        assertThat(sum / n).isLessThan(0.03)
    }

    /** The process's PSS before a fill, with the model held, and after release (docs/adr/0024 "Memory"). */
    @Suppress("ExplicitGarbageCollectionCall") // the memory deltas need a settled heap
    @Test
    fun memoryIsReturnedAfterRelease() {
        val (rgb, hole) = crop(512, 384)
        fun pss(): Long {
            Runtime.getRuntime().gc()
            return android.os.Debug.getPss() / 1024
        }
        val before = pss()
        InpaintService(loader(Fixtures.preferred), idleMs = 600_000).use { service ->
            service.fill(model, 512, 384, rgb, hole)
            val held = pss()
            service.release()
            val after = pss()
            Log.i(TAG, "inpaint memory ${service.backend} beforeMiB=$before heldMiB=$held afterReleaseMiB=$after")
            assertThat(after - before).isLessThan((held - before) / 2 + 16)
        }
    }

    /** A cold load with an empty GPU program cache, then with the cache filled (the next editor session). */
    @Test
    fun gpuProgramCacheShortensTheColdLoad() {
        if (Fixtures.preferred != Backend.GPU) return
        val dir = File(target.codeCacheDir, "litert-gpu-test").apply { deleteRecursively() }
        val times = (0 until 2).map {
            val t0 = SystemClock.elapsedRealtimeNanos()
            LiteRtEngine.load(
                LiteRtEngine.Source.Asset(target.assets, MaskModels.assetPath(model)),
                listOf(Backend.GPU),
                gpuCache = LiteRtEngine.GpuCache(dir.path, model.key),
            ).close()
            (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        }
        Log.i(
            TAG,
            "inpaint load emptyCache=${f1(times[0])} filledCache=${f1(times[1])} cacheBytes=${dir.walk().sumOf {
                it.length()
            }}",
        )
        dir.deleteRecursively()
    }

    private fun f1(v: Double) = "%.1f".format(Locale.ROOT, v)

    private companion object {
        const val TAG = "REVEAL_ML"
    }
}
