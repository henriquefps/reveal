@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.Mat3
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * What a Remove method gets (docs/adr/0023, "Remove: the contract"): a **context crop** of the original photo around
 * the region, [width] x [height] pixels in display encoding ([rgb], 3 floats a pixel, sRGB-encoded as-shot colour,
 * exposure-normalised: see [RemoveColour]), and the region to fill, [hole] (1 = fill, 0 = keep; already dilated by
 * [RemoveSpec.DILATE] pixels). [seed] makes a randomised method deterministic.
 *
 * The crop is the same for the editor's preview, its zoom tiles and an export (read from the full-resolution original,
 * box-downscaled by an integer factor, [RemoveSpec]), so one fill serves all of them. [cancelled] turns true when no
 * one wants the fill any more (docs/adr/0023 "FIX/H"): a method polls [checkCancelled] in its loops.
 */
class RemoveInput(
    val width: Int,
    val height: Int,
    val rgb: FloatArray,
    val hole: FloatArray,
    val seed: Long,
    val cancelled: () -> Boolean = NEVER,
) {
    init {
        require(width > 0 && height > 0) { "empty crop" }
        require(rgb.size == width * height * 3 && hole.size == width * height) { "crop size mismatch" }
    }

    /** Throws [CancellationException] when the fill is no longer wanted. */
    fun checkCancelled() {
        if (cancelled()) throw CancellationException("remove fill cancelled")
    }

    /** The same crop with [cancelled] as its cancellation. */
    fun withCancel(cancelled: () -> Boolean): RemoveInput = RemoveInput(width, height, rgb, hole, seed, cancelled)

    private companion object {
        val NEVER: () -> Boolean = { false }
    }
}

/**
 * What a Remove method returns: the filled crop [rgb] (same size and encoding as the input; only the hole's pixels are
 * used), and optionally [offsets]: for each pixel of the hole, `(dx, dy)` in crop pixels to the pixel whose
 * neighbourhood fills it (a nearest-neighbour field). With offsets the engine copies the photo's own pixels at any
 * resolution (sharp at 100 % and in an export); without them it upsamples [rgb] (a model's output).
 */
class RemoveOutput(val rgb: FloatArray, val offsets: FloatArray? = null)

/**
 * A way to fill a region: pure and deterministic for a given input (it runs on a worker thread). A method may throw
 * [CancellationException] once [RemoveInput.cancelled] is true.
 */
fun interface RemoveMethod {
    fun fill(input: RemoveInput): RemoveOutput
}

/**
 * A read crop: the method's [input] and its display [colour] (to bring a filled pixel back into the photo). [spec] is
 * set when the crop was read on another grid than the request's (a thumbnail without the original: its own image's
 * crop, docs/adr/0023 "FIX/H"); the fill then carries it.
 */
class RemoveCrop(val input: RemoveInput, val colour: RemoveColour, val spec: RemoveSpec? = null)

/**
 * What a Remove spot asks for: [key] (identifies the input: the original photo, the crop, the shape and the method),
 * the [method] of the spot, the crop geometry [spec], and [build], which reads the crop (run on the source's worker
 * when [buildAnywhere], else on the caller's thread before the request is queued). [storeKey] (the photo's file
 * fingerprint instead of its decode, docs/adr/0023 "FIX/H") finds the fill in a [RemoveFillStore].
 */
class RemoveRequest(
    val key: String,
    val method: String,
    val spec: RemoveSpec,
    val buildAnywhere: Boolean,
    val storeKey: String? = null,
    val build: () -> RemoveCrop,
)

/**
 * A computed fill: the crop's [spec], its [input] encoding (for [RemoveColour.fromDisplay]) and the [output].
 * [fellBack] is set when the spot's method could not run (a model without enough memory, docs/adr/0024) and the patch
 * fill was used instead: the reason, for the editor to say so.
 */
class RemoveFill(
    val spec: RemoveSpec,
    val colour: RemoveColour,
    val output: RemoveOutput,
    val fellBack: String? = null,
)

/**
 * Thrown by a [RemoveMethod] that cannot run now (docs/adr/0024: an inpainting model without enough free memory, or
 * a model the device failed to load or run): [RemoveFills] then fills the spot with the patch method and marks the
 * fill [RemoveFill.fellBack], instead of failing it.
 */
class RemoveUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the renderers get Remove fills (docs/adr/0023): like the AI masks' source, it never blocks. [fill] returns the
 * fill when it has it, else null (the spot is skipped meanwhile) and starts computing it, calling [onReady] once (any
 * thread) when it is done; a failed fill is null for good (the spot renders nothing) and [failed] says so.
 *
 * Interest is counted per [onReady] (docs/adr/0023 "FIX/H"): [release] says a caller no longer waits for some keys
 * (the spot moved, was deleted, or the editor closed); a fill no one waits for any more is cancelled.
 */
interface RemoveSource {
    fun fill(request: RemoveRequest, onReady: () -> Unit): RemoveFill?

    /** True when [key]'s fill failed (the method threw). */
    fun failed(key: String): Boolean = false

    /** [onReady] no longer waits for [keys]: their fills are cancelled when no one else does. */
    fun release(onReady: () -> Unit, keys: Collection<String>) {}

    companion object {
        /** No source: Remove spots render nothing. */
        val NONE: RemoveSource = object : RemoveSource {
            override fun fill(request: RemoveRequest, onReady: () -> Unit): RemoveFill? = null
        }
    }
}

/**
 * The fills of one process (docs/adr/0023): the methods by name ([Heal.METHOD_PATCH] = [PatchFill]; the app
 * [register]s `model:<id>@<version>` at start, docs/adr/0024), a cache of the last [capacity] fills by key, one worker
 * ([executor], one thread). A method this app does not have falls back to [Heal.METHOD_PATCH] (the spot still renders;
 * the editor says which method ran); a method that throws [RemoveUnavailableException] is replaced by the patch fill
 * for that spot.
 *
 * Jobs (docs/adr/0023 "FIX/H"): one per key, with the callers waiting for it. The worker takes the **newest** job first
 * (the last edit is the one the user looks at); a job whose callers all [release]d it is dropped from the queue, or,
 * when it is running, cancelled ([RemoveInput.cancelled]: the method stops at its next check, nothing is cached, the
 * key is not marked failed).
 */
class RemoveFills(
    methods: Map<String, RemoveMethod> = mapOf(Heal.METHOD_PATCH to PatchFill),
    private val capacity: Int = DEFAULT_CAPACITY,
    /** Whether fills computed here go into [store] (the editor's do; a thumbnail's, made without the original, not). */
    private val storeWrites: Boolean = true,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "reveal-remove").apply {
            isDaemon = true
        }
    },
) : RemoveSource {
    private val done = object : LinkedHashMap<String, RemoveFill>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoveFill>?): Boolean = size > capacity
    }
    private val failures = ConcurrentHashMap.newKeySet<String>()
    private val methods = ConcurrentHashMap(methods)

    /** The method each computed (or failed) key asked for: [register] drops what an unknown method left. */
    private val asked = ConcurrentHashMap<String, String>()

    /** One key's work: its request, a crop built by the caller (or null), who waits, and whether it was dropped. */
    private class Job(val request: RemoveRequest, val crop: RemoveCrop?) {
        val waiters = ArrayList<() -> Unit>()

        @Volatile
        var cancelled = false

        @Volatile
        var running = false
    }

    /** Jobs by key, queued or running; guarded by [lock], as [queue] (newest last). */
    private val jobs = HashMap<String, Job>()
    private val queue = ArrayDeque<Job>()
    private val lock = Any()

    /** Fills kept beyond the process (read before computing a request with a store key; docs/adr/0023 "FIX/H"). */
    @Volatile
    var store: RemoveFillStore? = null

    /** Fills computed, cancelled and failed since this instance was made (tests, the editor's measurements). */
    @Volatile
    var stats: Stats = Stats()
        private set

    /** What the worker has done: fills [computed], jobs [cancelled] (queued or running), fills [failed]. */
    data class Stats(
        val computed: Int = 0,
        val cancelled: Int = 0,
        val failed: Int = 0,
        /** The last fill's whole job, and its parts: reading the crop, the method. */
        val lastFillMs: Double = 0.0,
        val lastReadMs: Double = 0.0,
        val lastMethodMs: Double = 0.0,
    )

    /** Jobs queued or running (0: the worker is idle). */
    val activeJobs: Int get() = synchronized(lock) { jobs.size }

    /** The method that runs for [method] (the patch method when this app does not have it). */
    fun methodFor(method: String): String = if (methods.containsKey(method)) method else Heal.METHOD_PATCH

    /**
     * Adds (or replaces) the method [name] (`model:<id>@<version>`, docs/adr/0024). The app registers its models when
     * it starts, before anything renders; a fill computed before, under the patch method because [name] was unknown,
     * is dropped so the next request runs [name].
     */
    fun register(name: String, method: RemoveMethod) {
        methods[name] = method
        val stale = asked.filterValues { it == name }.keys
        synchronized(done) { stale.forEach(done::remove) }
        failures.removeAll(stale)
        stale.forEach(asked::remove)
    }

    /** The registered model methods (`model:<id>@<version>`), for the editor's AI / Patch choice. */
    fun models(): List<String> = methods.keys.filter { it.startsWith(Heal.METHOD_MODEL_PREFIX) }.sorted()

    override fun failed(key: String): Boolean = key in failures

    override fun fill(request: RemoveRequest, onReady: () -> Unit): RemoveFill? {
        synchronized(done) { done[request.key] }?.let { return it }
        if (request.key in failures) return null
        synchronized(lock) {
            jobs[request.key]?.takeIf { !it.cancelled }?.let { job ->
                if (job.waiters.none { it === onReady }) job.waiters += onReady
                return null
            }
        }
        val crop = if (request.buildAnywhere) null else request.build()
        synchronized(lock) {
            // Another caller may have queued it (or it finished) while the crop was read.
            synchronized(done) { done[request.key] }?.let { return it }
            // A job being cancelled (it may already have stopped) is replaced by a new one.
            val existing = jobs[request.key]?.takeIf { !it.cancelled }
            if (existing != null) {
                if (existing.waiters.none { it === onReady }) existing.waiters += onReady
                return null
            }
            val job = Job(request, crop)
            job.waiters += onReady
            jobs[request.key] = job
            queue.addLast(job)
        }
        executor.execute(::runNext)
        return null
    }

    override fun release(onReady: () -> Unit, keys: Collection<String>) {
        if (keys.isEmpty()) return
        var dropped = 0
        synchronized(lock) {
            for (key in keys) {
                val job = jobs[key] ?: continue
                job.waiters.removeAll { it === onReady }
                if (job.waiters.isNotEmpty()) continue
                job.cancelled = true
                if (!job.running) {
                    queue.remove(job)
                    jobs.remove(key)
                    dropped++
                }
            }
        }
        if (dropped > 0) count { it.copy(cancelled = it.cancelled + dropped) }
    }

    /** Runs the newest job still wanted (one per [fill] that queued one; a dropped job's turn finds nothing). */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun runNext() {
        val job = synchronized(lock) {
            queue.removeLastOrNull()?.also { it.running = true }
        } ?: return
        val key = job.request.key
        var fill: RemoveFill? = null
        var failed = false
        val start = System.nanoTime()
        var readMs = 0.0
        var methodMs = 0.0
        try {
            asked[key] = job.request.method
            val storeKey = job.request.storeKey
            val stored = storeKey?.let { k -> runCatching { store?.get(k) }.getOrNull() }
            if (stored != null) {
                fill = stored
            } else {
                val built = job.crop ?: job.request.build()
                val crop = RemoveCrop(built.input.withCancel { job.cancelled }, built.colour)
                val spec = built.spec ?: job.request.spec
                val method = checkNotNull(methods[methodFor(job.request.method)])
                val read = System.nanoTime()
                readMs = (read - start) / NANOS_PER_MS
                val made = try {
                    RemoveFill(spec, crop.colour, method.fill(crop.input))
                } catch (e: RemoveUnavailableException) {
                    val reason = e.message ?: "unavailable"
                    System.err.println("reveal-remove: ${job.request.method} unavailable ($reason): patch fill instead")
                    RemoveFill(spec, crop.colour, PatchFill.fill(crop.input), fellBack = reason)
                }
                fill = made
                methodMs = (System.nanoTime() - read) / NANOS_PER_MS
                // A fill made from the original, by the method asked for, outlives the process (thumbnails, viewer).
                if (storeWrites && storeKey != null && made.fellBack == null && built.spec == null) {
                    runCatching { store?.put(storeKey, made) }
                }
            }
            synchronized(done) { done[key] = fill }
        } catch (_: CancellationException) {
            // Nobody wants it any more: nothing cached, not a failure (a later request computes it again).
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (!job.cancelled) {
                failures += key
                failed = true
                System.err.println("reveal-remove: fill failed: $e")
            }
        } catch (e: OutOfMemoryError) {
            // A crop too big for the heap: the spot fails (renders nothing), the app lives on.
            failures += key
            failed = true
            System.err.println("reveal-remove: fill out of memory: $e")
        } finally {
            val ms = (System.nanoTime() - start) / NANOS_PER_MS
            count {
                when {
                    fill != null -> it.copy(
                        computed = it.computed + 1,
                        lastFillMs = ms,
                        lastReadMs = readMs,
                        lastMethodMs = methodMs,
                    )

                    failed -> it.copy(failed = it.failed + 1)

                    else -> it.copy(cancelled = it.cancelled + 1)
                }
            }
            val waiters = synchronized(lock) {
                if (jobs[key] === job) jobs.remove(key)
                job.running = false
                job.waiters.toList()
            }
            // A cancelled job has no waiters left; the others render the fill (or its failure) now.
            waiters.forEach { it() }
        }
    }

    private fun count(change: (Stats) -> Stats) = synchronized(this) { stats = change(stats) }

    /** Blocks until [request]'s fill is done (tests, exports); null when it failed or [timeoutMs] passed. */
    fun await(request: RemoveRequest, timeoutMs: Long = AWAIT_MS): RemoveFill? {
        val latch = CountDownLatch(1)
        val onReady: () -> Unit = { latch.countDown() }
        fill(request, onReady)?.let { return it }
        if (request.key in failures) return null
        val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!arrived) release(onReady, listOf(request.key))
        return synchronized(done) { done[request.key] }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64
        const val AWAIT_MS: Long = 120_000
        private const val NANOS_PER_MS = 1e6

        /** The fills shared by the editor, its zoom and the exports of this process. */
        val shared: RemoveFills by lazy { RemoveFills() }
    }
}

/**
 * The display encoding of a Remove crop (docs/adr/0023, "Remove: the contract"): camera RGB → linear sRGB with the
 * photo's as-shot white balance ([toLinear], a 3 x 3), times an exposure [gain] that brings the crop's median
 * luminance to 0.18, then the sRGB transfer curve extended to any value (odd: `sign(v) f(|v|)`, the curve continued
 * above 1). Invertible, so a model's output goes back into the photo exactly where it is unchanged.
 */
class RemoveColour(internal val toLinear: FloatArray, internal val fromLinear: FloatArray, val gain: Float) {
    /** Camera RGB ([cam], 3 floats at [i]) to display, into [out] at [o]. */
    fun toDisplay(cam: FloatArray, i: Int, out: FloatArray, o: Int) {
        val r = cam[i]
        val g = cam[i + 1]
        val b = cam[i + 2]
        for (c in 0..2) {
            val v = (toLinear[c * 3] * r + toLinear[c * 3 + 1] * g + toLinear[c * 3 + 2] * b) * gain
            out[o + c] = encode(v)
        }
    }

    /** Display ([disp], 3 floats at [i]) back to camera RGB, into [out] at [o]. */
    fun fromDisplay(disp: FloatArray, i: Int, out: FloatArray, o: Int) {
        val r = decode(disp[i]) / gain
        val g = decode(disp[i + 1]) / gain
        val b = decode(disp[i + 2]) / gain
        for (c in 0..2) out[o + c] = fromLinear[c * 3] * r + fromLinear[c * 3 + 1] * g + fromLinear[c * 3 + 2] * b
    }

    /** Camera RGB ([cam] at [i]) to linear sRGB (as shot, without the gain), into [out] at [o]. */
    fun linear(cam: FloatArray, i: Int, out: FloatArray, o: Int) {
        val r = cam[i]
        val g = cam[i + 1]
        val b = cam[i + 2]
        for (c in 0..2) out[o + c] = toLinear[c * 3] * r + toLinear[c * 3 + 1] * g + toLinear[c * 3 + 2] * b
    }

    /** Linear sRGB ([lin] at [i], without the gain) back to camera RGB, into [out] at [o]. */
    fun camera(lin: FloatArray, i: Int, out: FloatArray, o: Int) {
        val r = lin[i]
        val g = lin[i + 1]
        val b = lin[i + 2]
        for (c in 0..2) out[o + c] = fromLinear[c * 3] * r + fromLinear[c * 3 + 1] * g + fromLinear[c * 3 + 2] * b
    }

    /** Linear sRGB luminance of camera pixel [i] of [cam] before the gain. */
    fun luminance(cam: FloatArray, i: Int): Float {
        val r = cam[i]
        val g = cam[i + 1]
        val b = cam[i + 2]
        var y = 0f
        for (c in 0..2) {
            y += LUMA[c] * (toLinear[c * 3] * r + toLinear[c * 3 + 1] * g + toLinear[c * 3 + 2] * b)
        }
        return y
    }

    /** The same encoding with [gain]. */
    fun withGain(gain: Float): RemoveColour = RemoveColour(toLinear, fromLinear, gain)

    companion object {
        private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        private const val MID_GREY = 0.18f
        private const val MIN_GAIN = 1f / 64f
        private const val MAX_GAIN = 64f

        /** Camera → linear sRGB under the as-shot white balance of [colour] (its matrices only). */
        fun of(colour: PipelineInput): RemoveColour {
            val wb = WhiteBalanceModel.solve(colour, WhiteBalance(), ColorSpaces.SRGB)
            val m = wb.balancedToWorking * Mat3.diagonal(wb.multipliers[0], wb.multipliers[1], wb.multipliers[2])
            return RemoveColour(m.toFloatArray(), m.inverse().toFloatArray(), 1f)
        }

        /** The gain that brings the median luminance of [values] to 0.18 (clamped to 1/64..64). */
        fun gainFor(values: FloatArray): Float {
            if (values.isEmpty()) return 1f
            val sorted = values.copyOf().also { it.sort() }
            val median = sorted[sorted.size / 2]
            return if (median <= 0f) 1f else (MID_GREY / median).coerceIn(MIN_GAIN, MAX_GAIN)
        }

        /**
         * The sRGB transfer curve, odd and continued above 1. Up to [TABLE_MAX] it is read from a table in the square
         * root of the value ([TABLE_SIZE] steps, linear between them; `sqrt` is exact, so every device gets the same
         * value), above it computed. Within 2e-5 of the curve (Remove's crops encode about a million values).
         */
        fun encode(v: Float): Float {
            val a = abs(v)
            val e = if (a < TABLE_MAX) {
                val t = kotlin.math.sqrt(a / TABLE_MAX) * TABLE_SIZE
                val i = t.toInt().coerceAtMost(TABLE_SIZE - 1)
                val f = t - i
                TABLE[i] + f * (TABLE[i + 1] - TABLE[i])
            } else {
                exact(a)
            }
            return if (v < 0f) -e else e
        }

        /** The sRGB transfer curve of a value >= 0, computed. */
        fun exact(a: Float): Float = if (a <= LINEAR_TOE) {
            a * TOE_SLOPE
        } else {
            1.055f * StrictMath.pow(a.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        }

        private const val LINEAR_TOE = 0.0031308f
        private const val TOE_SLOPE = 12.92f
        private const val TABLE_MAX = 16f
        private const val TABLE_SIZE = 8192
        private val TABLE = FloatArray(TABLE_SIZE + 1) {
            val s = it.toFloat() / TABLE_SIZE
            exact(s * s * TABLE_MAX)
        }

        /** Inverse of [encode]. */
        fun decode(e: Float): Float {
            val a = abs(e)
            val v = if (a <= 0.04045f) a / 12.92f else StrictMath.pow(((a + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            return if (e < 0f) -v else v
        }
    }
}
