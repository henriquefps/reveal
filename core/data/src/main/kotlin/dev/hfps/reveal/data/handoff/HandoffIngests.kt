package dev.hfps.reveal.data.handoff

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The hand-offs being received, in the app's scope rather than an activity's (ADR 0011 "Amendment W4"): a recreated
 * activity (rotation, a configuration change while a large batch copies) re-attaches to its request by key instead of
 * dropping it half-way. The activity that holds the intent's grant tells it when it is gone ([Ingest.holderGone]), so
 * work that needs the grant stops relying on it.
 */
class HandoffIngests(private val scope: CoroutineScope) {
    /** What one request's receive produced: the repository's [received] and whether the batch already had members. */
    data class Result(val received: HandoffRepository.Received, val follower: Boolean)

    /** One request in progress. [silent]: a batch member above the first (it never shows anything). */
    class Ingest internal constructor(
        val key: String,
        val silent: Boolean,
        /** The batch this request belongs to (notices are summed there), or null. */
        val batchKey: String?,
        /** Hoard handed over `MediaStore.Images` documents (the media offer, W4). */
        val hoardMedia: Boolean,
        private val alive: AtomicBoolean,
        internal val result: Deferred<Result>,
    ) {
        /** The outcome was handed to the screen (a re-attached activity does not show it twice). */
        @Volatile
        var shown: Boolean = false

        suspend fun await(): Result = result.await()

        /** The activity holding the grant finished: smart previews of grant-only documents are no longer made. */
        fun holderGone() = alive.set(false)

        internal val holderAlive: Boolean get() = alive.get()
    }

    private val running = ConcurrentHashMap<String, Ingest>()

    /**
     * Starts receiving under [key] (a new one per intent): [receive] runs in the app's scope and gets whether the
     * grant's holder still lives. Returns the existing ingest when [key] is already running.
     */
    fun start(
        key: String,
        silent: Boolean,
        batchKey: String?,
        hoardMedia: Boolean,
        receive: suspend (holderAlive: () -> Boolean) -> Result,
    ): Ingest = running.getOrPut(key) {
        val alive = AtomicBoolean(true)
        val result = scope.async { receive { alive.get() } }
        Ingest(key, silent, batchKey, hoardMedia, alive, result).also { ingest ->
            // Kept until the screen is done with it, or long after it settled (an activity that never came back).
            scope.launch {
                runCatching { ingest.await().received.settled.await() }
                delay(FORGET_AFTER_MS)
                running.remove(key, ingest)
            }
        }
    }

    /** The ingest of [key] (a recreated activity re-attaching), or null when it is unknown or over. */
    fun attach(key: String): Ingest? = running[key]

    /** The screen is done with [key] (shown, and settled for a silent member). */
    fun done(key: String) {
        running.remove(key)
    }

    private companion object {
        const val FORGET_AFTER_MS = 10 * 60_000L
    }
}
