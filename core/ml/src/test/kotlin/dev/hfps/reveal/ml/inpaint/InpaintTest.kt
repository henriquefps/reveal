package dev.hfps.reveal.ml.inpaint

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.InferenceEngine
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import org.junit.Test

/** The crop layout, the fill cache and the service around a fake model (docs/adr/0024). */
class InpaintTest {
    private fun crop(
        w: Int,
        h: Int,
        hole: (Int, Int) -> Boolean,
        value: (Int, Int, Int) -> Float = { x, y, c ->
            (x * 7 + y * 3 + c * 11) % 50 / 50f
        },
    ): Pair<FloatArray, FloatArray> {
        val rgb = FloatArray(w * h * 3)
        val mask = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0..2) rgb[(y * w + x) * 3 + c] = value(x, y, c)
                mask[y * w + x] = if (hole(x, y)) 1f else 0f
            }
        }
        return rgb to mask
    }

    @Test
    fun `mirror reflects without repeating the edge`() {
        assertThat((0 until 9).map { InpaintLayout.mirror(it, 4) }).containsExactly(0, 1, 2, 3, 2, 1, 0, 1, 2).inOrder()
        assertThat(InpaintLayout.mirror(5, 1)).isEqualTo(0)
    }

    @Test
    fun `a wide crop fills the width and mirrors below, hole included`() {
        val (rgb, hole) = crop(200, 100, { x, y -> x in 90..110 && y in 80..99 })
        val (layout, input) = InpaintLayout.prepare(200, 100, rgb, hole, 64)
        assertThat(layout.contentWidth).isEqualTo(64)
        assertThat(layout.contentHeight).isEqualTo(32)
        // the row below the content mirrors the last content row: same hole flag, same values
        for (x in 0 until 64) {
            for (k in 0..3) assertThat(input[(32 * 64 + x) * 4 + k]).isEqualTo(input[(30 * 64 + x) * 4 + k])
        }
        // hole pixels are zeroed and flagged; known pixels keep their value
        val holeCount = (0 until 64 * 64).count { input[it * 4 + 3] == 1f }
        assertThat(holeCount).isGreaterThan(0)
        for (i in 0 until 64 * 64) {
            if (input[i * 4 + 3] == 1f) for (c in 0..2) assertThat(input[i * 4 + c]).isEqualTo(0f)
        }
    }

    @Test
    fun `the hole only grows through the resampling`() {
        val (rgb, hole) = crop(300, 300, { x, y -> x == 150 && y == 150 })
        val (layout, input) = InpaintLayout.prepare(300, 300, rgb, hole, 64)
        val output = FloatArray(64 * 64 * 3) { 0.5f }
        val out = layout.restore(output, rgb, hole)
        assertThat((0 until 64 * 64).count { input[it * 4 + 3] == 1f }).isAtLeast(1)
        for (c in 0..2) assertThat(out[(150 * 300 + 150) * 3 + c]).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `restore keeps every known pixel exactly and brings highlights back`() {
        val (rgb, hole) = crop(120, 90, { x, y ->
            x in 40..60 && y in 30..50
        }) { x, y, c -> 0.2f + x * 0.02f + c * 0.1f }
        val (layout, input) = InpaintLayout.prepare(120, 90, rgb, hole, 64)
        val max = rgb.filterIndexed { i, _ -> hole[i / 3] == 0f }.max()
        assertThat(layout.highlight).isWithin(1e-6f).of(max)
        assertThat(input.filterIndexed { i, _ -> i % 4 != 3 }.max()).isAtMost(1f)
        val output = FloatArray(64 * 64 * 3) { 0.25f }
        val out = layout.restore(output, rgb, hole)
        for (i in 0 until 120 * 90) {
            for (c in 0..2) {
                if (hole[i] == 0f) {
                    assertThat(out[i * 3 + c]).isEqualTo(rgb[i * 3 + c])
                } else {
                    assertThat(out[i * 3 + c]).isWithin(1e-5f).of(0.25f * max)
                }
            }
        }
    }

    @Test
    fun `the fill cache round-trips rounded values and evicts the oldest`() {
        val dir = Files.createTempDirectory("fills").toFile()
        var now = 1_000L
        val cache = FillDiskCache(dir, clock = { now })
        val values = FillDiskCache.quantised(FloatArray(300) { it * 0.0137f - 1f })
        cache.put("a", values)
        assertThat(cache.get("a", 100)).isEqualTo(values)
        assertThat(cache.get("a", 99)).isNull()
        assertThat(cache.get("b", 100)).isNull()
        now = 2_000L
        cache.put("b", values)
        cache.trim(cache.entries().first().bytes)
        assertThat(cache.get("a", 100)).isNull()
        assertThat(cache.get("b", 100)).isEqualTo(values)
        assertThat(dir.listFiles()!!.map(File::getName)).doesNotContain("a")
        dir.deleteRecursively()
    }

    private class FakeEngine(private val side: Int) : InferenceEngine {
        var runs = 0
        var closed = false
        override val backend: Backend = Backend.CPU

        // paints every pixel the hole's flag marks with the mean of the known pixels: a deterministic "model"
        override fun runAll(input: FloatArray): List<FloatArray> {
            runs++
            val n = side * side
            val mean = FloatArray(3)
            var known = 0
            for (i in 0 until n) {
                if (input[i * 4 + 3] == 0f) {
                    known++
                    for (c in 0..2) mean[c] += input[i * 4 + c]
                }
            }
            return listOf(FloatArray(n * 3) { mean[it % 3] / known })
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the service fills the hole, caches it identically and frees the model after each fill`() {
        val model = InpaintModels.BIG_LAMA.copy(side = 32)
        val engines = mutableListOf<FakeEngine>()
        val dir = Files.createTempDirectory("fills").toFile()
        val service = InpaintService({ FakeEngine(it.side).also(engines::add) }, FillDiskCache(dir))
        val (rgb, hole) = crop(80, 60, { x, y -> abs(x - 40) < 8 && abs(y - 30) < 8 })
        val first = service.fill(model, 80, 60, rgb, hole)
        assertThat(first.fromCache).isFalse()
        assertThat(first.backend).isEqualTo(Backend.CPU)
        assertThat(engines.single().runs).isEqualTo(1)
        assertThat(engines.single().closed).isTrue()
        assertThat(service.backend).isNull()
        for (i in 0 until 80 * 60) {
            if (hole[i] == 0f) for (c in 0..2) assertThat(first.rgb[i * 3 + c]).isEqualTo(rgb[i * 3 + c])
        }
        val again = service.fill(model, 80, 60, rgb, hole)
        assertThat(again.fromCache).isTrue()
        assertThat(again.rgb).isEqualTo(first.rgb)
        assertThat(engines).hasSize(1)
        // another crop loads the model again
        val (rgb2, _) = crop(80, 60, { _, _ -> false }) { x, _, c -> x * 0.01f + c * 0.2f }
        service.fill(model, 80, 60, rgb2, hole)
        assertThat(engines).hasSize(2)
        assertThat(engines.all { it.closed }).isTrue()
        service.close()
        dir.deleteRecursively()
    }

    @Test
    fun `an idle delay keeps the model for the next fill`() {
        val model = InpaintModels.BIG_LAMA.copy(side = 32)
        val engines = mutableListOf<FakeEngine>()
        InpaintService({ FakeEngine(it.side).also(engines::add) }, idleMs = 60_000).use { service ->
            val (rgb, hole) = crop(40, 40, { x, _ -> x < 10 })
            service.fill(model, 40, 40, rgb, hole)
            service.fill(model, 40, 40, rgb.map { it * 0.5f }.toFloatArray(), hole)
            assertThat(engines.single().runs).isEqualTo(2)
            assertThat(service.backend).isEqualTo(Backend.CPU)
            service.release()
            assertThat(engines.single().closed).isTrue()
        }
    }

    @Test
    fun `too little memory or a failing model makes the fill unavailable`() {
        val model = InpaintModels.BIG_LAMA.copy(side = 32)
        val (rgb, hole) = crop(40, 40, { x, _ -> x < 10 })
        var loads = 0
        InpaintService({
            loads++
            FakeEngine(it.side)
        }, hasMemoryFor = { false }).use { service ->
            val e = runCatching { service.fill(model, 40, 40, rgb, hole) }.exceptionOrNull()
            assertThat(e).isInstanceOf(InpaintUnavailableException::class.java)
            assertThat((e as InpaintUnavailableException).lowMemory).isTrue()
            assertThat(loads).isEqualTo(0)
        }
        InpaintService({ throw OutOfMemoryError("no room") }).use { service ->
            val e = runCatching {
                service.fill(model, 40, 40, rgb, hole)
            }.exceptionOrNull() as InpaintUnavailableException
            assertThat(e.lowMemory).isTrue()
        }
        val failing = object : InferenceEngine {
            var closed = false
            override val backend = Backend.GPU
            override fun runAll(input: FloatArray): List<FloatArray> = error("driver lost")
            override fun close() {
                closed = true
            }
        }
        InpaintService({ failing }).use { service ->
            val e = runCatching {
                service.fill(model, 40, 40, rgb, hole)
            }.exceptionOrNull() as InpaintUnavailableException
            assertThat(e.lowMemory).isFalse()
            assertThat(failing.closed).isTrue()
        }
    }

    @Test
    fun `the key changes with the content and the model`() {
        val (rgb, hole) = crop(10, 10, { x, _ -> x < 3 })
        val k = InpaintService.key(InpaintModels.BIG_LAMA, 10, 10, rgb, hole)
        val rgb2 = rgb.copyOf().also { it[5] += 1e-6f }
        assertThat(InpaintService.key(InpaintModels.BIG_LAMA, 10, 10, rgb2, hole)).isNotEqualTo(k)
        assertThat(InpaintService.key(InpaintModels.BIG_LAMA.copy(version = 2), 10, 10, rgb, hole)).isNotEqualTo(k)
        assertThat(InpaintModels.of("model:big-lama@1")).isEqualTo(InpaintModels.BIG_LAMA)
        assertThat(InpaintModels.of("patch")).isNull()
    }
}
