@file:Suppress("MagicNumber") // rgb channel loops (0..2)

package dev.hfps.reveal.ml.inpaint

import android.content.res.AssetManager
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.InferenceEngine
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A filled crop: [rgb] (the crop's size; the hole's pixels from the model, rounded as the cache stores them, every
 * other pixel the input's own), whether it came from the [fromCache], the [backend] the model ran on (null from the
 * cache) and the times ([modelMs] = the model alone, [totalMs] = with the layout and the cache).
 */
class InpaintResult(
    val rgb: FloatArray,
    val fromCache: Boolean,
    val backend: Backend?,
    val modelMs: Double,
    val totalMs: Double,
)

/**
 * Why a fill could not run (docs/adr/0024 "Memory"): too little free memory to load the model ([lowMemory]), or the
 * load or the run failed (an out-of-memory error, a driver refusing the model). Remove falls back to the patch fill
 * and says so.
 */
class InpaintUnavailableException(message: String, val lowMemory: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Remove's inpainting (docs/adr/0024): a crop and its hole in, the filled crop out ([InpaintLayout] fits it to the
 * model's square).
 *
 * - **Memory**: one model, loaded for a fill by [loader] (GPU, else CPU) and released right after it (big-LaMa holds
 *   ≈ 600 MB while loaded on the S24's GPU, ADR 0024; [idleMs] > 0 keeps it that long for the next fill). Before a
 *   load [hasMemoryFor] is asked (the app: free memory above the model's need); a refusal, an out-of-memory error or
 *   a failed load throws [InpaintUnavailableException] and frees what was loaded.
 * - **Blocking and serial**: [fill] runs on the caller's thread, one at a time (Remove's fills come from one worker,
 *   docs/adr/0023 `RemoveFills`).
 * - **Cache** ([cache], optional): keyed by the model `<id>@<version>` and the crop's exact content (size, every rgb
 *   and
 *   hole value hashed), so the same Remove input (docs/adr/0023 "the crop is defined on the original") hits after a
 *   restart. A fresh fill is rounded like a cached one: both paths give identical pixels.
 */
class InpaintService(
    private val loader: (InpaintModelSpec) -> InferenceEngine,
    private val cache: FillDiskCache? = null,
    private val idleMs: Long = 0,
    private val hasMemoryFor: (InpaintModelSpec) -> Boolean = { true },
) : AutoCloseable {
    private val lock = Any()
    private var loaded: Pair<InpaintModelSpec, InferenceEngine>? = null
    private var pendingRelease: ScheduledFuture<*>? = null
    private val releaser = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "reveal-inpaint-release").apply { isDaemon = true }
    }

    /** [model]'s fill of the [width] x [height] crop [rgb] (interleaved, display encoding) where [hole] > 0. */
    fun fill(model: InpaintModelSpec, width: Int, height: Int, rgb: FloatArray, hole: FloatArray): InpaintResult {
        val t0 = System.nanoTime()
        require(rgb.size == width * height * 3 && hole.size == width * height) { "crop size mismatch" }
        val holeIndex = hole.indices.filter { hole[it] > 0f }
        val key = key(model, width, height, rgb, hole)
        cache?.get(key, holeIndex.size)?.let { values ->
            return InpaintResult(merge(rgb, holeIndex, values), true, null, 0.0, ms(t0))
        }
        synchronized(lock) {
            pendingRelease?.cancel(false)
            val (layout, input) = InpaintLayout.prepare(width, height, rgb, hole, model.side)
            val t1 = System.nanoTime()
            val (engine, output) = run(model, input)
            val modelMs = ms(t1)
            val restored = layout.restore(output, rgb, hole)
            val values = FloatArray(holeIndex.size * 3)
            holeIndex.forEachIndexed { k, i -> for (c in 0..2) values[k * 3 + c] = restored[i * 3 + c] }
            val stored = FillDiskCache.quantised(values)
            cache?.put(key, stored)
            val backend = engine.backend
            if (idleMs <=
                0
            ) {
                release()
            } else {
                pendingRelease = releaser.schedule({ release() }, idleMs, TimeUnit.MILLISECONDS)
            }
            return InpaintResult(merge(rgb, holeIndex, stored), false, backend, modelMs, ms(t0))
        }
    }

    /** The backend of the loaded model (null when none is loaded). */
    val backend: Backend? get() = synchronized(lock) { loaded?.second?.backend }

    /** Frees the loaded model (the editor closing, `onTrimMemory`, or [idleMs] without a fill). */
    fun release() = synchronized(lock) {
        loaded?.second?.close()
        loaded = null
    }

    override fun close() {
        release()
        releaser.shutdownNow()
    }

    /** Loads [model] if needed and runs it; any failure frees the model and becomes [InpaintUnavailableException]. */
    // a driver or the runtime may fail in any way: Remove falls back to Patch; each failure is told apart
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun run(model: InpaintModelSpec, input: FloatArray): Pair<InferenceEngine, FloatArray> {
        val current = loaded?.takeIf { it.first == model }?.second
        val engine = current ?: run {
            release()
            if (!hasMemoryFor(model)) {
                throw InpaintUnavailableException("not enough free memory for ${model.key}", lowMemory = true)
            }
            try {
                loader(model).also { loaded = model to it }
            } catch (e: OutOfMemoryError) {
                throw InpaintUnavailableException("out of memory loading ${model.key}", lowMemory = true, cause = e)
            } catch (e: Exception) {
                throw InpaintUnavailableException("${model.key} could not be loaded", lowMemory = false, cause = e)
            }
        }
        return try {
            engine to engine.run(input)
        } catch (e: OutOfMemoryError) {
            release()
            throw InpaintUnavailableException("out of memory running ${model.key}", lowMemory = true, cause = e)
        } catch (e: Exception) {
            release()
            throw InpaintUnavailableException("${model.key} failed", lowMemory = false, cause = e)
        }
    }

    private fun merge(rgb: FloatArray, holeIndex: List<Int>, values: FloatArray): FloatArray {
        val out = rgb.copyOf()
        holeIndex.forEachIndexed { k, i -> for (c in 0..2) out[i * 3 + c] = values[k * 3 + c] }
        return out
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / NANOS_PER_MS

    companion object {
        /**
         * Free memory a model needs to load, as a multiple of its file size (big-LaMa: 53.6 MB file, +616 MB PSS on
         * the S24's GPU, +982 MB on the CPU; ADR 0024).
         */
        const val MEMORY_PER_FILE_BYTE: Long = 20
        private const val NANOS_PER_MS = 1e6

        /** The cache key: the model and the crop's exact content. */
        fun key(model: InpaintModelSpec, width: Int, height: Int, rgb: FloatArray, hole: FloatArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteBuffer.allocate((rgb.size + hole.size) * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            rgb.forEach { buf.putFloat(it) }
            hole.forEach { buf.putFloat(it) }
            digest.update(buf.array())
            val content = digest.digest().joinToString("") { "%02x".format(it) }
            return "inpaint/${model.key}/${width}x$height/$content"
        }

        /**
         * Loads a bundled model from the APK's assets with LiteRT (GPU, else CPU), keeping the GPU's compiled programs
         * in [gpuCacheDir] (null: no program cache) so later loads skip most of the compile.
         */
        fun assets(assets: AssetManager, gpuCacheDir: String?): (InpaintModelSpec) -> InferenceEngine = { model ->
            LiteRtEngine.load(
                LiteRtEngine.Source.Asset(assets, MaskModels.assetPath(model)),
                gpuCache = gpuCacheDir?.let { LiteRtEngine.GpuCache(it, model.key) },
            )
        }
    }
}
