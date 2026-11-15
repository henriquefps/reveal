package dev.hfps.reveal.data.storage

import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import dev.hfps.image.ImageInput
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The message prefix of a hand-off provider that is busy, not refusing (Hoard ADR 0010, Hoard 2.0.1): Hoard's
 * FileProvider limits how many of its hand-off files (often on SMB) are open at once and answers an open beyond that
 * with a `FileNotFoundException` whose message starts exactly with this (`busy: 8 hand-off reads in progress,
 * retry`). It means "try again shortly", never "unreadable": [isAccessError] is false for it, and [FileOpens.retryBusy]
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

/** A provider stayed busy through every retry of [FileOpens.retryBusy]: not an access error, try again later. */
class ProviderBusyException(cause: Throwable) : IOException("the provider stayed busy", cause)

private const val MAX_CAUSES = 8

/**
 * Reveal's own limit on files opened through other apps' providers for background work (ADR 0018 "W3/C", ADR 0011):
 * at most [BACKGROUND_SLOTS] open at once app-wide (hand-off documents held by the thumbnail loader, the access checks,
 * the hand-off previewer, sync's uploads), so a large library never bursts a provider: each open of an SMB-backed
 * hand-off costs the sender an `openProxyFileDescriptor` (appfuse, through vold), and dozens per second stalled the
 * storage service and restarted system_server (2026-11-15). A slot is held until the input is closed; a caller waits
 * for one (interruptibly), never goes ahead.
 *
 * Every open through it is counted ([opens]); [log] writes `REVEAL_IO` lines with the opens of the last second while
 * any happen (measurements: opens per second at start).
 */
object FileOpens {
    const val BACKGROUND_SLOTS = 4

    /**
     * Backoff of [retryBusy] (Hoard ADR 0010's client rule): 250 ms doubling to 4 s, giving up after about 30 s in all
     * (then a transient failure, never "unreadable").
     */
    val BUSY_BACKOFF_MS = longArrayOf(250, 500, 1_000, 2_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000)

    /** The backoff [retryBusy] uses by default (instrumented tests shorten it). */
    @Volatile
    var busyBackoffMs: LongArray = BUSY_BACKOFF_MS

    private val slots = Semaphore(BACKGROUND_SLOTS, true)
    private val total = AtomicInteger()
    private val window = AtomicInteger()
    private val windowStart = AtomicLong(0)
    private val peak = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val peakInFlight = AtomicInteger()

    /** Opens counted since the process started (tests, measurements). */
    val opens: Int get() = total.get()

    /** The most opens counted in one second, and the most open at once (measurements). */
    val peakPerSecond: Int get() = peak.get()
    val peakConcurrent: Int get() = peakInFlight.get()

    /** Inputs held open now through [gated]. */
    val open: Int get() = inFlight.get()

    /**
     * Opens through [open] in one of the app-wide slots; the slot is released by [release] (pass it to the input's
     * `onClose`), or at once when [open] throws. Never more than [BACKGROUND_SLOTS] at once (Hoard's own limit is 6,
     * contract 11): a caller waits for a slot as long as it takes, and the wait is interruptible (cancel the thread,
     * for example with `runInterruptible`), so a cancelled caller never pins one.
     *
     * A busy provider ([isProviderBusy], Hoard ADR 0010) is retried after each of [backoff]; the slot is **released
     * while waiting** (the pause is not an open), and after the last retry the failure is a [ProviderBusyException],
     * which is never an access error. Blocking: call it from an IO thread.
     */
    fun <T> gated(
        backoff: LongArray = busyBackoffMs,
        sleep: (Long) -> Unit = Thread::sleep,
        open: (release: () -> Unit) -> T,
    ): T {
        var attempt = 0
        while (true) {
            slots.acquire()
            val now = inFlight.incrementAndGet()
            peakInFlight.accumulateAndGet(now, ::maxOf)
            val released = java.util.concurrent.atomic.AtomicBoolean()
            val release = {
                if (released.compareAndSet(false, true)) {
                    inFlight.decrementAndGet()
                    slots.release()
                }
            }
            count()
            try {
                return open(release)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                release()
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
     * The returned input holds its slot until closed.
     */
    fun openDocument(uri: android.net.Uri, ownPackage: String, open: () -> ImageInput): ImageInput =
        if (!LocalCopies.isForeign(uri, ownPackage) || uri.authority == MEDIA_AUTHORITY) {
            open()
        } else {
            gated { release ->
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
                Log.i(TAG, "opens/s=$last total=${total.get()} open=${inFlight.get()}")
            }
        }
        window.incrementAndGet()
    }

    /** Forgets the counters (tests). */
    fun resetCounters() {
        total.set(0)
        window.set(0)
        peak.set(0)
        peakInFlight.set(inFlight.get())
    }

    private const val TAG = "REVEAL_IO"
    private const val MEDIA_AUTHORITY = "media"
    private const val MILLIS = 1_000L
    private const val NANOS_PER_MS = 1_000_000L
}
