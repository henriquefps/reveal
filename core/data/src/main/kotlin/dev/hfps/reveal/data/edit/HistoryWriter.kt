package dev.hfps.reveal.data.edit

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Writes the editor's committed history changes to Room in order, off the main thread and outside the screen's
 * lifetime (an app-wide [scope]: leaving the editor right after a commit still saves it). Changes that arrive while a
 * write is running are coalesced into the next transaction, so a burst of commits costs one write, and each
 * transaction is all-or-nothing: a crash or a kill never leaves a half-written step (ADR 0006 "History").
 */
class HistoryWriter(private val edits: EditRepository, scope: CoroutineScope) {
    private val queue = Channel<HistoryOp>(Channel.UNLIMITED)
    private val pendingCount = MutableStateFlow(0)

    init {
        scope.launch {
            for (first in queue) {
                val batch = mutableListOf(first)
                while (true) batch += queue.tryReceive().getOrNull() ?: break
                try {
                    edits.apply(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // The edit stays on screen; only its persistence failed (disk full, database closed).
                    Log.e(TAG, "Could not save ${batch.size} history changes", e)
                }
                pendingCount.update { it - batch.size }
            }
        }
    }

    /** Queues one change; returns at once. */
    fun write(op: HistoryOp) {
        pendingCount.update { it + 1 }
        queue.trySend(op)
    }

    /** Suspends until every queued change is written (tests, and before reading the database back). */
    suspend fun flush() {
        pendingCount.first { it == 0 }
    }

    private companion object {
        const val TAG = "RevealHistory"
    }
}
