package dev.hfps.reveal.data.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's start, for work that must not touch files before the first frame (ADR 0018 "W3/C"): the activity calls
 * [firstFrame] once its first frame is drawn; bulk passes (indexing, access checks, fingerprints, sync, the library
 * thumbnail prefetch) [awaitSettled] first. A process without an activity (a WorkManager job, a provider) settles
 * after [NO_FRAME_MS].
 */
object AppStart {
    private val frame = CompletableDeferred<Unit>()

    /** The settle time after the first frame (the grid's first screen of thumbnails loads first). */
    @Volatile
    var settleMs: Long = SETTLE_MS

    /** Whether the first frame was drawn. */
    val framed: Boolean get() = frame.isCompleted

    @Volatile
    private var settledAt = 0L

    fun firstFrame() {
        if (frame.complete(Unit)) settledAt = now() + settleMs
    }

    /** Suspends until the first frame (or [NO_FRAME_MS] without one) plus [settleMs]; at once afterwards. */
    suspend fun awaitSettled() {
        if (!frame.isCompleted) {
            withTimeoutOrNull(NO_FRAME_MS) { frame.await() }
            if (!frame.isCompleted) {
                firstFrame()
                return
            }
        }
        val wait = settledAt - now()
        if (wait > 0) delay(wait)
    }

    private fun now() = System.nanoTime() / NANOS_PER_MS

    const val SETTLE_MS = 5_000L
    const val NO_FRAME_MS = 15_000L
    private const val NANOS_PER_MS = 1_000_000L
}
