@file:Suppress("MagicNumber", "NestedBlockDepth")

package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * docs/adr/0023 "FIX/H": Remove fills are jobs that can be dropped. A job no one waits for is never run (queued) or
 * stops at the method's next check (running); nothing of it is cached or marked failed; the newest job runs first;
 * an engine releases the fills of spots that moved, and all of them when closed; the store serves and keeps fills.
 */
class RemoveJobsTest {
    private val w = 120
    private val h = 90
    private val image = HealScenes.withObject(HealScenes.stripes(w, h), 60, 45, 16)
    private val colour = RemoveColour(identity(), identity(), 1f)

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** A request whose crop is a flat grey [w] x [h] square (the method decides what happens). */
    private fun request(key: String, method: String = "test") = RemoveRequest(
        key,
        method,
        RemoveSpec(w, h, 1, 0, 0, 8, 8),
        buildAnywhere = true,
    ) { RemoveCrop(RemoveInput(8, 8, FloatArray(8 * 8 * 3) { 0.5f }, FloatArray(8 * 8), 1L), colour) }

    /** A method that blocks on [gate] (after saying it started) and records what ran. */
    private class Gated {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val method = RemoveMethod { input ->
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
            ran += "seed${input.seed}"
            RemoveOutput(input.rgb)
        }
    }

    @Test
    fun `a job released while queued never runs, and the newest queued job runs first`() {
        val gated = Gated()
        val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val tracking = RemoveMethod { input ->
            order += input.rgb[0].toString()
            RemoveOutput(input.rgb)
        }
        val fills = RemoveFills(
            mapOf(Heal.METHOD_PATCH to PatchFill, "gate" to gated.method, "track" to tracking),
            executor = Executors.newSingleThreadExecutor(),
        )
        val waiter = {}
        fills.fill(request("busy", "gate"), waiter)
        gated.started.await(5, TimeUnit.SECONDS)
        fun tracked(key: String, value: Float) = RemoveRequest(key, "track", RemoveSpec(w, h, 1, 0, 0, 8, 8), true) {
            RemoveCrop(RemoveInput(8, 8, FloatArray(8 * 8 * 3) { value }, FloatArray(8 * 8), 1L), colour)
        }
        fills.fill(tracked("old", 0.1f), waiter)
        fills.fill(tracked("dropped", 0.2f), waiter)
        fills.fill(tracked("new", 0.3f), waiter)
        fills.release(waiter, listOf("dropped"))
        gated.gate.countDown()
        awaitIdle(fills)
        assertThat(order).containsExactly("0.3", "0.1").inOrder()
        assertThat(fills.stats.cancelled).isEqualTo(1)
        assertThat(fills.failed("dropped")).isFalse()
    }

    @Test
    fun `a running job released stops at its next check, caches nothing and can be asked again`() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val looping = RemoveMethod { input ->
            started.countDown()
            try {
                while (true) {
                    input.checkCancelled()
                    Thread.sleep(1)
                }
                @Suppress("UNREACHABLE_CODE")
                RemoveOutput(input.rgb)
            } catch (e: CancellationException) {
                stopped.countDown()
                throw e
            }
        }
        val fills = RemoveFills(mapOf("loop" to looping), executor = Executors.newSingleThreadExecutor())
        val waiter = {}
        assertThat(fills.fill(request("k", "loop"), waiter)).isNull()
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(fills.activeJobs).isEqualTo(1)
        fills.release(waiter, listOf("k"))
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue()
        awaitIdle(fills)
        assertThat(fills.failed("k")).isFalse()
        assertThat(fills.stats.cancelled).isEqualTo(1)
        assertThat(fills.stats.computed).isEqualTo(0)
        // Asked again, it is computed again (here: cancelled again, then a real method under another key works).
        val again = CountDownLatch(1)
        fills.register("loop", RemoveMethod { RemoveOutput(it.rgb) })
        fills.fill(request("k", "loop")) { again.countDown() }
        assertThat(again.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(fills.fill(request("k", "loop")) {}).isNotNull()
    }

    @Test
    fun `a job another caller still waits for is not cancelled`() {
        val gated = Gated()
        val fills = RemoveFills(mapOf("gate" to gated.method), executor = Executors.newSingleThreadExecutor())
        val a = {}
        val bReady = CountDownLatch(1)
        val b = { bReady.countDown() }
        fills.fill(request("shared", "gate"), a)
        fills.fill(request("shared", "gate"), b)
        gated.started.await(5, TimeUnit.SECONDS)
        fills.release(a, listOf("shared"))
        gated.gate.countDown()
        assertThat(bReady.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(fills.fill(request("shared", "gate")) {}).isNotNull()
        assertThat(fills.stats.cancelled).isEqualTo(0)
    }

    @Test
    fun `the patch fill stops when cancelled`() {
        val spot = HealScenes.spot("r", Heal.KIND_REMOVE, w, h, 60f, 45f, 14f)
        val spec = checkNotNull(RemoveSpec.of(spot, w, h))
        val crop = spec.read(spot, PixelReader.of(image), image, 1L)
        val input = crop.input.withCancel { true }
        val thrown = runCatching { PatchFill.fill(input) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `an engine releases the fill of a spot that moved, and every fill when closed`() {
        val released = ConcurrentHashMap<String, Int>()
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val source = object : RemoveSource {
            override fun fill(request: RemoveRequest, onReady: () -> Unit): RemoveFill? {
                asked += request.key
                return null
            }

            override fun release(onReady: () -> Unit, keys: Collection<String>) {
                keys.forEach { released.merge(it, 1, Int::plus) }
            }
        }
        val reader = PixelReader.of(image)
        val engine = HealEngine(w, h, reader, image, RemoveContext(source, reader, image))
        val spot = HealScenes.spot("r", Heal.KIND_REMOVE, w, h, 60f, 45f, 14f)
        engine.update(listOf(spot))
        assertThat(engine.pending).containsExactly("r")
        val first = asked.last()
        engine.update(listOf(spot.copy(x = spot.x + 0.1f)))
        assertThat(released.keys).containsExactly(first)
        val second = asked.last()
        engine.close()
        assertThat(released.keys).containsExactly(first, second)
    }

    @Test
    fun `the store serves a fill without computing it, and keeps what the method made`() {
        val stored = HashMap<String, RemoveFill>()
        val store = object : RemoveFillStore {
            override fun get(key: String): RemoveFill? = stored[key]

            override fun put(key: String, fill: RemoveFill) {
                stored[key] = fill
            }
        }
        var calls = 0
        val counting = RemoveMethod {
            calls++
            RemoveOutput(it.rgb)
        }
        val fills = RemoveFills(mapOf("count" to counting), executor = Executors.newSingleThreadExecutor())
        fills.store = store
        val keyed = RemoveRequest("a", "count", RemoveSpec(w, h, 1, 0, 0, 8, 8), true, storeKey = "file:a") {
            RemoveCrop(RemoveInput(8, 8, FloatArray(8 * 8 * 3) { 0.5f }, FloatArray(8 * 8), 1L), colour)
        }
        assertThat(fills.await(keyed)).isNotNull()
        assertThat(calls).isEqualTo(1)
        assertThat(stored.keys).containsExactly("file:a")
        // Another process (a thumbnail's fills, read only): the stored fill, no computation.
        val reader =
            RemoveFills(mapOf("count" to counting), storeWrites = false, executor = Executors.newSingleThreadExecutor())
        reader.store = store
        assertThat(reader.await(keyed)).isNotNull()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `a fill survives the store's encoding exactly`() {
        val spot = HealScenes.spot("r", Heal.KIND_REMOVE, w, h, 60f, 45f, 14f)
        val fills = RemoveFills(executor = Executors.newSingleThreadExecutor())
        val reader = PixelReader.of(image)
        val spec = checkNotNull(RemoveSpec.of(spot, w, h))
        val key = RemoveSpec.key(RemoveSpec.identity(reader), spec, spot, spot.method)
        val fill = checkNotNull(
            fills.await(RemoveRequest(key, spot.method, spec, true) { spec.read(spot, reader, image, 3L) }),
        )
        val back = RemoveFillCodec.decode(RemoveFillCodec.encode(fill))
        assertThat(back.spec).isEqualTo(fill.spec)
        assertThat(back.output.rgb).isEqualTo(fill.output.rgb)
        assertThat(back.output.offsets).isEqualTo(fill.output.offsets)
        assertThat(back.colour.gain).isEqualTo(fill.colour.gain)
        assertThat(back.fellBack).isNull()
        val out = FloatArray(3)
        val disp = floatArrayOf(0.3f, 0.5f, 0.7f)
        val expected = FloatArray(3)
        back.colour.fromDisplay(disp, 0, out, 0)
        fill.colour.fromDisplay(disp, 0, expected, 0)
        assertThat(out).isEqualTo(expected)
    }

    @Test
    fun `big regions are searched on a coarser crop for the patch method only`() {
        val big = HealScenes.stroke(
            "s",
            Heal.KIND_REMOVE,
            6000,
            4000,
            listOf(2850f to 2000f, 3150f to 2000f),
            72f,
        )
        val patch = checkNotNull(RemoveSpec.of(big, 6000, 4000))
        // The region (300 px + the dabs) is at most PATCH_REGION pixels in the crop.
        val region = (300f + 2 * 72f) / patch.factor
        assertThat(region).isAtMost(RemoveSpec.PATCH_REGION)
        assertThat(maxOf(patch.width, patch.height)).isAtMost(RemoveSpec.MAX_SIDE)
        val model = checkNotNull(RemoveSpec.of(big.copy(method = "model:x@1"), 6000, 4000))
        assertThat(model.factor).isLessThan(patch.factor)
        // A small spot keeps the full resolution.
        val small = HealScenes.spot("d", Heal.KIND_REMOVE, 6000, 4000, 3000f, 2000f, 20f)
        assertThat(checkNotNull(RemoveSpec.of(small, 6000, 4000)).factor).isEqualTo(1)
    }

    @Test
    fun `a smaller image of the original fills its spot on its own grid, or takes the stored fill`() {
        // The original (here 240 x 180) and a half-size "thumbnail" of it.
        val original = HealScenes.withObject(HealScenes.stripes(240, 180), 120, 90, 32)
        val half = halfSize(original)
        val spot = HealScenes.spot("r", Heal.KIND_REMOVE, 240, 180, 120f, 90f, 28f, feather = 20f)
        val fills = RemoveFills(storeWrites = false, executor = Executors.newSingleThreadExecutor())
        val reader = PixelReader.of(half)
        val context = RemoveContext(fills, reader, half, photoKey = "fp", originalWidth = 240, originalHeight = 180)
        val out = applyWith(half, spot, context)
        val i = (45 * half.width + 60) * 3
        // The object (red) is gone at the thumbnail's size.
        assertThat(out[i] - out[i + 1]).isLessThan((half.linearRgb[i] - half.linearRgb[i + 1]) * 0.3f)
    }

    private fun applyWith(input: PipelineInput, spot: Heal, context: RemoveContext): FloatArray {
        val engine = HealEngine(input.width, input.height, PixelReader.of(input), input, context)
        val latch = CountDownLatch(1)
        engine.update(listOf(spot)) { latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        engine.update(listOf(spot))
        assertThat(engine.pending).isEmpty()
        val rgb = input.linearRgb.copyOf()
        engine.overlay(0, 0, input.width, input.height, rgb)
        return rgb
    }

    private fun halfSize(input: PipelineInput): PipelineInput {
        val hw = input.width / 2
        val hh = input.height / 2
        val rgb = FloatArray(hw * hh * 3)
        for (y in 0 until hh) {
            for (x in 0 until hw) {
                for (c in 0..2) {
                    var s = 0f
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            s +=
                                input.linearRgb[((2 * y + dy) * input.width + 2 * x + dx) * 3 + c]
                        }
                    }
                    rgb[(y * hw + x) * 3 + c] = s / 4f
                }
            }
        }
        return PipelineInput.linearSrgb(hw, hh, rgb)
    }

    private fun awaitIdle(fills: RemoveFills) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fills.activeJobs > 0 && System.nanoTime() < deadline) Thread.sleep(2)
        assertThat(fills.activeJobs).isEqualTo(0)
    }
}
