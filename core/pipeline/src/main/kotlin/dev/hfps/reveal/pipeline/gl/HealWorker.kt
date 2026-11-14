package dev.hfps.reveal.pipeline.gl

import android.util.Log
import dev.hfps.reveal.pipeline.heal.HealEngine
import dev.hfps.reveal.pipeline.heal.HealPatch
import dev.hfps.reveal.pipeline.heal.HealWarmUp
import dev.hfps.reveal.pipeline.recipe.Heal
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The preview's healing off the GL thread (docs/adr/0023 "FIX/H"): one thread owns the [HealEngine] and brings it to
 * the newest spots the renderer [submit]s (older submissions that were not started are skipped: a burst of edits
 * computes the last one), then hands the result to the GL thread through [deliver], which uploads the patches. The
 * GL thread never computes a spot, so a slow spot never stalls a frame; a Remove fill that arrives runs the last
 * spots again by itself.
 *
 * Results must be applied in order (each one's [Result.dirty] is relative to the one before); a new [Wanted.epoch]
 * (a new Remove source or original) starts a new engine, and the GL thread drops results of older epochs.
 */
internal class HealWorker(private val deliver: (Result) -> Unit) : AutoCloseable {
    /** What the engine became: its patches, where the healed image changed since the last result, and the status. */
    class Result(
        val epoch: Int,
        val seq: Long,
        val patches: List<HealPatch?>,
        val dirty: IntArray?,
        val status: HealStatus,
    )

    /** One submission: the spots of [epoch]'s engine, made by [factory] when the epoch is new. */
    private class Wanted(val epoch: Int, val heals: List<Heal>, val factory: () -> HealEngine, val seq: Long)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "reveal-heal-${COUNT.incrementAndGet()}").apply { isDaemon = true }
    }
    private val lock = Any()
    private var wanted: Wanted? = null
    private var last: Wanted? = null
    private var scheduled = false
    private var seq = 0L

    @Volatile
    private var closed = false

    // Worker thread only.
    private var engine: HealEngine? = null
    private var engineEpoch = Int.MIN_VALUE

    /** One callback for the engine's Remove fills (released by identity when a spot no longer needs its fill). */
    private val onFill: () -> Unit = ::rerun

    /** Milliseconds of the last engine update (the spots that changed), for measurements. */
    @Volatile
    var lastUpdateMs: Double = 0.0
        private set

    /** True when nothing is queued or computing: every result has been handed to [deliver]. */
    val idle: Boolean get() = synchronized(lock) { !scheduled } && tasks.get() == 0

    /** [onWorker] blocks queued or running. */
    private val tasks = AtomicInteger()

    /** Brings the engine to [heals]; returns the submission's number. */
    fun submit(epoch: Int, heals: List<Heal>, factory: () -> HealEngine): Long = synchronized(lock) {
        val next = Wanted(epoch, heals, factory, ++seq)
        wanted = next
        schedule()
        next.seq
    }

    /** Runs [block] on the worker with the engine (null before the first spot), after what is queued. */
    fun <T> onWorker(block: (HealEngine?) -> T, done: (kotlin.Result<T>) -> Unit) {
        tasks.incrementAndGet()
        try {
            executor.execute {
                try {
                    done(runCatching { block(engine) })
                } finally {
                    tasks.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            tasks.decrementAndGet()
            done(kotlin.Result.failure(e))
        }
    }

    /** Compiles the healing code ahead of the first spot ([HealWarmUp]), once, on the worker. */
    fun warmUp() {
        if (warmed) return
        warmed = true
        onWorker({ HealWarmUp.run() }) { r -> r.exceptionOrNull()?.let { Log.w(TAG, "heal warm-up failed", it) } }
    }

    @Volatile
    private var warmed = false

    /** A Remove fill arrived: compute the last spots again (unless newer ones are already queued). */
    private fun rerun() {
        synchronized(lock) {
            if (closed || wanted != null) return
            val previous = last ?: return
            wanted = Wanted(previous.epoch, previous.heals, previous.factory, ++seq)
            schedule()
        }
    }

    private fun schedule() {
        if (scheduled || closed) return
        scheduled = true
        try {
            executor.execute(::drain)
        } catch (_: RejectedExecutionException) {
            scheduled = false
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val w = wanted
                wanted = null
                if (w == null || closed) {
                    scheduled = false
                    return
                }
                w
            }
            compute(next)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun compute(w: Wanted) {
        try {
            if (w.epoch != engineEpoch) {
                engine?.close()
                engine = w.factory()
                engineEpoch = w.epoch
            }
            val e = checkNotNull(engine)
            val start = System.nanoTime()
            val dirty = e.update(w.heals, onFill)
            lastUpdateMs = (System.nanoTime() - start) / NANOS_PER_MS
            synchronized(lock) { last = w }
            deliver(Result(w.epoch, w.seq, e.current.toList(), dirty, HealStatus(e.pending, e.failed, e.fellBack)))
        } catch (_: CancellationException) {
            // The renderer closed while the engine read its pixels.
        } catch (e: Exception) {
            // A spot that cannot be computed must not take the editor down: it is logged and the preview keeps the
            // last healed image (docs/adr/0023 "FIX/H").
            Log.e(TAG, "healing failed", e)
            synchronized(lock) { last = w }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "healing out of memory", e)
            engine = null
            engineEpoch = Int.MIN_VALUE
        }
    }

    /** Stops the worker: queued work is dropped, the engine releases the Remove fills it waited for. */
    override fun close() {
        synchronized(lock) {
            closed = true
            wanted = null
            last = null
        }
        try {
            executor.execute {
                engine?.close()
                engine = null
            }
        } catch (_: RejectedExecutionException) {
            // Already shut down.
        }
        executor.shutdown()
    }

    private companion object {
        const val TAG = "RevealHeal"
        const val NANOS_PER_MS = 1e6
        val COUNT = AtomicInteger()
    }
}
