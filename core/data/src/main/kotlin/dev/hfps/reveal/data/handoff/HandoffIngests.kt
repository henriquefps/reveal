package dev.hfps.reveal.data.handoff

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The hand-offs being received, in the app's scope rather than an activity's (ADR 0011 "Amendment W4"): the whole
 * receive (the batch join, [HandoffRepository.receiveStaged], the batch's notices) lives here and holds no activity,
 * so a recreated activity (rotation, a configuration change while a large batch copies) re-attaches to its request by
 * key instead of dropping it half-way.
 *
 * The intent's read grant is held by whoever holds it: the activity that received it and the foreground service it
 * hands the URIs to (`HandoffGrantService`, which holds its own grant until the ingest settles). Each holder is
 * counted ([Ingest.hold]); when the last one lets go, work that needs the grant (smart previews of grant-only
 * documents) stops relying on it.
 */
class HandoffIngests(
    private val scope: CoroutineScope,
    private val handoffs: HandoffRepository,
    private val batches: HandoffBatches,
) {
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
        private val holders: AtomicInteger,
        internal val result: Deferred<Result>,
    ) {
        /** The outcome was handed to the screen (a re-attached activity does not show it twice). */
        @Volatile
        var shown: Boolean = false

        /** Everything that needs the grant is done ([HandoffRepository.Received.settled]). */
        @Volatile
        var settled: Boolean = false
            internal set

        suspend fun await(): Result = result.await()

        /** What the request settled with (after [await]'s outcome, once the rest is done). */
        suspend fun awaitSettled(): HandoffRepository.Settled = await().received.settled.await()

        /**
         * One more holder of the intent's grant (the foreground service); the returned function lets go of it, once.
         */
        fun hold(): () -> Unit {
            holders.incrementAndGet()
            val released = AtomicBoolean(false)
            return { if (released.compareAndSet(false, true)) holders.decrementAndGet() }
        }

        private val handedOver = ConcurrentLinkedQueue<() -> Unit>()

        /**
         * A hold taken for the grant service before it is started (so the grant never looks gone between the activity
         * finishing and the service starting); the service takes it over with [takeHandOver]. The returned function
         * lets go of it when the start was refused.
         */
        fun handOver(): () -> Unit = hold().also { handedOver.add(it) }

        /** The hold [handOver] took for the service, or a new one. */
        fun takeHandOver(): () -> Unit = handedOver.poll() ?: hold()

        /** The activity that received the intent is gone (finishing): its grant goes with it. */
        fun holderGone() {
            if (activityReleased.compareAndSet(false, true)) holders.decrementAndGet()
        }

        private val activityReleased = AtomicBoolean(false)

        private val settledNotice = AtomicBoolean(false)

        /**
         * True once, for whoever reports what the request settled with (no space, copies refused): the activity when
         * it is still there, else the grant service.
         */
        fun claimSettledNotice(): Boolean = settledNotice.compareAndSet(false, true)

        /** Whether anyone still holds the intent's grant. */
        val holderAlive: Boolean get() = holders.get() > 0
    }

    private val running = ConcurrentHashMap<String, Ingest>()

    /**
     * Receives [request] under [key] (a new one per intent) in the app's scope: joins its batch ([batchKey]), receives
     * it ([HandoffRepository.receiveStaged]; a failure becomes a notice, never a crash) and, for a silent member,
     * reports its notices to the batch. The receiving activity is the first holder of the grant. Returns the existing
     * ingest when [key] is already running.
     */
    fun receive(key: String, request: HandoffRequest, batchKey: String?, hoardMedia: Boolean): Ingest =
        start(key, request.silentMember, batchKey, hoardMedia) { holderAlive ->
            val join = batchKey?.let { batches.join(it, request.batchCount) }
            val received = receiveSafely(handoffs, request.copy(batchAt = join?.at), holderAlive)
            if (batchKey != null) {
                batches.touch(batchKey)
                // A silent member's notices wait for the intent that shows the batch: one sheet for all (ADR 0011).
                if (request.silentMember) batches.report(batchKey, noticeOf(received.outcome))
            }
            Result(received, follower = join?.follower == true)
        }

    /**
     * Starts receiving under [key]: [receive] runs in the app's scope and gets whether the grant is still held.
     * Returns the existing ingest when [key] is already running.
     */
    fun start(
        key: String,
        silent: Boolean,
        batchKey: String?,
        hoardMedia: Boolean,
        receive: suspend (holderAlive: () -> Boolean) -> Result,
    ): Ingest = running.getOrPut(key) {
        val holders = AtomicInteger(1)
        val result = scope.async { receive { holders.get() > 0 } }
        Ingest(key, silent, batchKey, hoardMedia, holders, result).also { ingest ->
            // Kept until the screen is done with it, or long after it settled (an activity that never came back).
            scope.launch {
                runCatching { ingest.awaitSettled() }
                ingest.settled = true
                delay(FORGET_AFTER_MS)
                running.remove(key, ingest)
            }
        }
    }

    /** The ingest of [key] (a recreated activity re-attaching, the grant service), or null when unknown or over. */
    fun attach(key: String): Ingest? = running[key]

    /**
     * Runs [block] on the main thread once [ingest] settled, in the app's scope: it outlives the activity and the grant
     * service (a service stopped by its time limit still lets the notice of the copies it could not finish show).
     */
    fun whenSettled(ingest: Ingest, block: (HandoffRepository.Settled) -> Unit) {
        scope.launch {
            val settled = runCatching { ingest.awaitSettled() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { block(settled) }
        }
    }

    /** The screen is done with [key] (shown, and settled). */
    fun done(key: String) {
        running.remove(key)
    }

    companion object {
        private const val TAG = "RevealHandoff"
        private const val FORGET_AFTER_MS = 10 * 60_000L

        /** The notices a hand-off's outcome carries (server references not found, a server issue). */
        fun noticeOf(outcome: HandoffOutcome): HandoffNotice = when (outcome) {
            is HandoffOutcome.OpenEditor -> HandoffNotice(outcome.notFound, outcome.issue)
            is HandoffOutcome.OpenLibrary -> HandoffNotice(outcome.notFound, outcome.issue)
            is HandoffOutcome.ServerRefs -> HandoffNotice(outcome.notFound, outcome.issue)
            else -> HandoffNotice()
        }

        /** A hand-off that fails (a malformed server answer, a provider error) never crashes the app: a notice. */
        internal suspend fun receiveSafely(
            handoffs: HandoffRepository,
            request: HandoffRequest,
            holderAlive: () -> Boolean,
        ): HandoffRepository.Received = try {
            handoffs.receiveStaged(request, holderAlive)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "hand-off failed", e)
            val refs = request.server
            val outcome = if (refs != null) {
                HandoffOutcome.ServerRefs(ServerIssue.Unreachable(refs.items.size))
            } else {
                HandoffOutcome.Nothing
            }
            HandoffRepository.Received(outcome, CompletableDeferred(HandoffRepository.Settled()))
        }
    }
}
