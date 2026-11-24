package dev.hfps.reveal.data.storage

import android.util.Log
import dev.hfps.image.ImageInput
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext

/**
 * The message prefix of a hand-off provider that is busy, not refusing (Hoard ADR 0010, Hoard 2.0.1): Hoard's
 * FileProvider limits how many of its hand-off files (often on SMB) are open at once and answers an open beyond that
 * with a `FileNotFoundException` whose message starts exactly with this (`busy: 8 hand-off reads in progress,
 * retry`). It means "try again shortly", never "unreadable": [isAccessError] is false for it, and [FileOpens.gated]
 * retries it with a bounded backoff. Any other `FileNotFoundException` keeps its meaning (gone, or refused).
 */
const val PROVIDER_BUSY_PREFIX = "busy:"

/** Whether [error] (or a cause) is a provider's "busy, retry" answer ([PROVIDER_BUSY_PREFIX]). */
@Suppress("ReturnCount")
fun isProviderBusy(error: Throwable): Boolean {
    var e: Throwable? = error
    var depth = 0
    while (e != null && depth++ < MAX_CAUSES) {
        if (e is ProviderBusyException) return true
        if (e is FileNotFoundException && e.message?.startsWith(PROVIDER_BUSY_PREFIX) == true) return true
        e = e.cause
    }
    return false
}

/** A provider stayed busy through every retry of [FileOpens.gated]: not an access error, try again later. */
class ProviderBusyException(cause: Throwable) : IOException("the provider stayed busy", cause)

private const val MAX_CAUSES = 8

/**
 * [runCatching] for reads through [FileOpens]: a failed read becomes a failure, but a cancelled wait for a slot
 * ([CancellationException], [InterruptedException]) is thrown on, so a cancelled batch stops instead of opening its
 * next document.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> readCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: InterruptedException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * [block] in [context] (`withContext`), returning what it opened. `withContext` throws when its coroutine was cancelled
 * while [block] ran, even after [block] returned: the input it opened would be dropped with its [FileOpens] slot, lost
 * until the process dies. This closes it instead. Use it whenever an input that holds a slot crosses a context switch.
 */
suspend fun <T : AutoCloseable?> openedIn(context: CoroutineContext, block: suspend CoroutineScope.() -> T): T {
    val opened = java.util.concurrent.atomic.AtomicReference<T>()
    try {
        return withContext(context) { block().also(opened::set) }
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        opened.get()?.close()
        throw e
    }
}

/**
 * Reveal's own limit on files opened through other apps' providers (ADR 0018 "W3/C", ADR 0011): at most
 * [BACKGROUND_SLOTS] open at once app-wide (hand-off documents read by the thumbnail loader, the viewer, the access
 * checks, the hand-off previewer, sync's uploads, the editor and the export), so a large library never bursts a
 * provider: each open of an SMB-backed hand-off costs the sender an `openProxyFileDescriptor` (appfuse, through vold),
 * and dozens per second stalled the storage service and restarted system_server (2026-11-15). A slot is held until the
 * input is closed; a caller waits for one, never goes ahead without it.
 *
 * The rules that keep the gate from locking up (ADR 0018 "W3/C", FIX 0.11.0):
 * - **Never wait for a slot while holding one.** A RAW + JPEG pair is read one file after the other, each closed
 *   before the next is opened (`FirstImageLoader.loadEach`, the thumbnail loader, the previewer): four callers each
 *   holding one slot and waiting for a second used to wait forever.
 * - **Hold a slot for the read only.** The input is closed as soon as it is decoded (`ProxyBuilder.decodeProxy`, an
 *   embedded preview), before any GPU work, mask or fill wait (`EditedThumbnails.decode` then `render`); a lock
 *   that serialises decodes is taken before the slot, never while holding one.
 *   The one long holder is sync's chunked upload of an original, which reads the file as it sends (one upload at a
 *   time, nothing inside it waits for another slot).
 * - **Never wait for a slot inside a database transaction**, nor for a lock or anything else while holding one.
 * - **Waits end with their caller.** A waiter keeps its place in the FIFO queue and leaves it when its thread is
 *   interrupted (`runInterruptible`) or, for coroutine callers that switch with [IO] / [io] (or add [waits]), within
 *   [POLL_MS] of its coroutine being cancelled ([CancellationException]); it never takes or frees a slot then.
 * - **An open input never crosses a context switch bare**: return it from [openedIn], not `withContext` (a cancelled
 *   switch back drops the result, slot included); wrap reads in [readCatching], not `runCatching`, so a cancelled wait
 *   ends the batch.
 * - A wait longer than [stuckMs] logs `REVEAL_IO` with every holder's tag, document and age (then again every
 *   [stuckMs]), so a pinned slot can be found; the waiter keeps waiting.
 *
 * Every open through it is counted ([opens]); [count] writes `REVEAL_IO` lines with the opens of the last second while
 * any happen (measurements: opens per second at start).
 */
object FileOpens {
    const val BACKGROUND_SLOTS = 4

    /**
     * Backoff of [gated] (Hoard ADR 0010's client rule): 250 ms doubling to 4 s, giving up after about 30 s in all
     * (then a transient failure, never "unreadable").
     */
    val BUSY_BACKOFF_MS = longArrayOf(250, 500, 1_000, 2_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000)

    /** The backoff [gated] uses by default (instrumented tests shorten it). */
    @Volatile
    var busyBackoffMs: LongArray = BUSY_BACKOFF_MS

    /** How often a waiter looks at its coroutine's cancellation (its place in the queue is kept meanwhile). */
    const val POLL_MS = 50L

    /** A slot wait longer than this logs the holders (and again every as long); tests shorten it. */
    @Volatile
    var stuckMs: Long = STUCK_MS

    /** Where a stuck wait is reported (`REVEAL_IO`; tests collect the lines). */
    @Volatile
    internal var report: (String) -> Unit = { line -> runCatching { Log.w(TAG, line) } }

    @Volatile
    private var gate = Gate(BACKGROUND_SLOTS)
    private val total = AtomicInteger()
    private val window = AtomicInteger()
    private val windowStart = AtomicLong(0)
    private val peak = AtomicInteger()
    private val peakInFlight = AtomicInteger()

    /** Opens counted since the process started (tests, measurements). */
    val opens: Int get() = total.get()

    /** The most opens counted in one second, and the most open at once (measurements). */
    val peakPerSecond: Int get() = peak.get()
    val peakConcurrent: Int get() = peakInFlight.get()

    /** Inputs held open now through [gated]. */
    val open: Int get() = gate.held

    /** Callers waiting for a slot now (tests). */
    val waiting: Int get() = gate.waiting

    /** Who holds the slots now: tag, document and age (tests, the stuck-wait log). */
    fun holders(): List<String> = gate.describeHolders()

    /**
     * Makes slot waits of the coroutine it is in (and its children) end when that coroutine is cancelled; [tag] names
     * the caller in the holders' list (`thumbnails`, `viewer`, `editor`, ...). Add it to the context that runs the
     * blocking open: `withContext(Dispatchers.IO + FileOpens.waits("viewer"))`, or use [io].
     */
    fun waits(tag: String): CoroutineContext.Element = WaitContext(tag)

    /** [Dispatchers.IO] with [waits] ([tag] names the caller). */
    fun io(tag: String): CoroutineContext = Dispatchers.IO + WaitContext(tag)

    /** [io] without a tag of its own (the thread's name is used). */
    val IO: CoroutineContext = Dispatchers.IO + WaitContext(null)

    /**
     * Opens through [open] in one of the app-wide slots; the slot is released by `release` (pass it to the input's
     * `onClose`), or at once when [open] throws. Never more than [BACKGROUND_SLOTS] at once (Hoard's own limit is 6,
     * contract 11). The wait is first come, first served and lasts as long as it takes: a caller must not hold another
     * slot meanwhile (see the class's rules). It ends with an [InterruptedException] when the thread is interrupted
     * and with a [CancellationException] when the coroutine of [waits] is cancelled; neither takes nor frees a slot.
     * [name] (the document) and [tag] (default: [waits]' tag, else the thread's name) describe the holder.
     *
     * A busy provider ([isProviderBusy], Hoard ADR 0010) is retried after each of [backoff]; the slot is **released
     * while waiting** (the pause is not an open), and after the last retry the failure is a [ProviderBusyException],
     * which is never an access error. Blocking: call it from an IO thread.
     */
    @Suppress("InstanceOfCheckForException")
    fun <T> gated(
        backoff: LongArray = busyBackoffMs,
        sleep: (Long) -> Unit = ::pause,
        name: String = "",
        tag: String? = null,
        open: (release: () -> Unit) -> T,
    ): T {
        var attempt = 0
        val who = tag ?: currentTag()
        while (true) {
            val slot = gate.acquire(who, name, currentJob.get())
            peakInFlight.accumulateAndGet(gate.held, ::maxOf)
            try {
                count()
                return open(slot::release)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                slot.release()
                if (e !is Exception || !isProviderBusy(e)) throw e
                if (attempt >= backoff.size) throw ProviderBusyException(e)
            }
            sleep(backoff[attempt++])
        }
    }

    /**
     * Opens a document of [uri] for the editor, the export or any other reader: another app's provider (Hoard,
     * [LocalCopies.isForeign]) goes through [gated] like the background work, so every open of a sender's files in
     * the app shares the one cap; anything else (Reveal's own providers, MediaStore, files) is opened as it is.
     * The returned input holds its slot until closed: close it once read, before waiting for anything else.
     */
    fun openDocument(
        uri: android.net.Uri,
        ownPackage: String,
        tag: String? = null,
        open: () -> ImageInput,
    ): ImageInput = if (!LocalCopies.isForeign(uri, ownPackage) || uri.authority == MEDIA_AUTHORITY) {
        open()
    } else {
        gated(name = uri.lastPathSegment.orEmpty().substringAfterLast('/'), tag = tag) { release ->
            val input = open()
            ImageInput(input.name, input.descriptor, release)
        }
    }

    /** Counts one open (also for opens that need no slot, such as a folder's thumbnail). */
    fun count() {
        total.incrementAndGet()
        val now = System.nanoTime() / NANOS_PER_MS
        val start = windowStart.get()
        if (now - start >= MILLIS && windowStart.compareAndSet(start, now)) {
            val last = window.getAndSet(0)
            if (last > 0) {
                peak.accumulateAndGet(last, ::maxOf)
                runCatching { Log.i(TAG, "opens/s=$last total=${total.get()} open=${gate.held}") }
            }
        }
        window.incrementAndGet()
    }

    /** A fresh gate with every slot free (tests: nothing a test leaves behind may block the next). */
    internal fun resetForTests() {
        gate = Gate(BACKGROUND_SLOTS)
        stuckMs = STUCK_MS
        report = { line -> runCatching { Log.w(TAG, line) } }
        resetCounters()
    }

    /** Forgets the counters (tests). */
    fun resetCounters() {
        total.set(0)
        window.set(0)
        peak.set(0)
        peakInFlight.set(gate.held)
    }

    /** The busy backoff's pause, ended early (as a [CancellationException]) when the caller is cancelled. */
    private fun pause(ms: Long) {
        val job = currentJob.get()
        val end = System.nanoTime() + ms * NANOS_PER_MS
        while (true) {
            if (job != null && !job.isActive) throw CancellationException("cancelled during a busy provider's backoff")
            val left = (end - System.nanoTime()) / NANOS_PER_MS
            if (left <= 0) return
            Thread.sleep(minOf(left, POLL_MS))
        }
    }

    private fun currentTag(): String = currentTagLocal.get() ?: Thread.currentThread().name

    /** The coroutine (and tag) of [waits], set on the thread while that coroutine runs on it. */
    private val currentJob = ThreadLocal<Job?>()
    private val currentTagLocal = ThreadLocal<String?>()

    private class WaitContext(val tag: String?) :
        AbstractCoroutineContextElement(Key),
        ThreadContextElement<Pair<Job?, String?>> {
        companion object Key : CoroutineContext.Key<WaitContext>

        override fun updateThreadContext(context: CoroutineContext): Pair<Job?, String?> {
            val old = currentJob.get() to currentTagLocal.get()
            currentJob.set(context[Job])
            currentTagLocal.set(tag)
            return old
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Pair<Job?, String?>) {
            currentJob.set(oldState.first)
            currentTagLocal.set(oldState.second)
        }
    }

    /** One slot taken from a [Gate]; [release] is idempotent. */
    private class Slot(private val gate: Gate, val id: Long) {
        private val released = AtomicBoolean()

        fun release() {
            if (released.compareAndSet(false, true)) gate.release(id)
        }
    }

    private class Holder(val tag: String, val name: String, val since: Long)

    /**
     * [BACKGROUND_SLOTS] slots handed out first come, first served: a waiter joins the tail of [queue] and takes a slot
     * only from its head, so polling for cancellation never costs it its place.
     */
    private class Gate(slots: Int) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val queue = ArrayDeque<Any>()
        private val holders = LinkedHashMap<Long, Holder>()
        private var free = slots
        private var next = 0L

        val held: Int get() = lock.withLock { holders.size }
        val waiting: Int get() = lock.withLock { queue.size }

        fun describeHolders(): List<String> = lock.withLock { describe(System.nanoTime()) }

        @Suppress("NestedBlockDepth")
        fun acquire(tag: String, name: String, job: Job?): Slot {
            val started = System.nanoTime()
            var reportAt = started + stuckMs * NANOS_PER_MS
            var stuck = false
            lock.lockInterruptibly()
            try {
                val me = Any()
                queue.addLast(me)
                try {
                    while (queue.first() !== me || free == 0) {
                        if (job != null && !job.isActive) {
                            throw CancellationException("cancelled while waiting for a file slot")
                        }
                        changed.await(POLL_MS, TimeUnit.MILLISECONDS)
                        val now = System.nanoTime()
                        if (now >= reportAt) {
                            stuck = true
                            reportAt = now + stuckMs * NANOS_PER_MS
                            val waited = (now - started) / NANOS_PER_MS / MILLIS
                            val line = "slot wait of $tag ($name) for $waited s, ${queue.size} waiting; " +
                                "holders: ${describe(now).joinToString()}"
                            runCatching { report(line) }
                        }
                    }
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    // Interrupted or cancelled: out of the queue, no slot taken; the next waiter may be the head now.
                    queue.remove(me)
                    changed.signalAll()
                    throw e
                }
                queue.removeFirst()
                free--
                val id = next++
                holders[id] = Holder(tag, name, System.nanoTime())
                if (free > 0 && queue.isNotEmpty()) changed.signalAll()
                val slot = Slot(this, id)
                // The slot is the caller's from here: a report that throws must not lose it.
                if (stuck) runCatching { report("slot wait of $tag ($name) ended after ${elapsedMs(started)} ms") }
                return slot
            } finally {
                lock.unlock()
            }
        }

        fun release(id: Long) = lock.withLock {
            if (holders.remove(id) != null) {
                free++
                changed.signalAll()
            }
        }

        private fun elapsedMs(started: Long) = (System.nanoTime() - started) / NANOS_PER_MS

        private fun describe(now: Long): List<String> = holders.values.map {
            "${it.tag} (${it.name}, ${(now - it.since) / NANOS_PER_MS / MILLIS} s)"
        }
    }

    private const val TAG = "REVEAL_IO"
    private const val MEDIA_AUTHORITY = "media"
    private const val MILLIS = 1_000L
    private const val NANOS_PER_MS = 1_000_000L
    private const val STUCK_MS = 120_000L
}
