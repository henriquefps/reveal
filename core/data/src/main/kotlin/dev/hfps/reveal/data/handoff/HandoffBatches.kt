package dev.hfps.reveal.data.handoff

import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RevealDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hand-offs that arrive as several intents with the same sender and `TITLE` (ADR 0011 "Batches over 500"): Hoard sends
 * a bigger batch as `startActivities(...)` (or a `startActivity` loop). Every intent opens its own `MainActivity`; all
 * of them are filed as **one** "Opened from other apps" batch (one `handoff_at`), and the ones that carry nothing to
 * open ([Join.follower] without an `INITIAL_*`) ingest and finish silently, so the cascade unwinds to the one that
 * opens the editor.
 *
 * The state is one record in `meta` (process death mid-cascade is fine): the batch's key, its stamp, when it was last
 * touched, and whether an intent that opens something claimed it. A window of [WINDOW_MS] after the last touch keeps
 * the batch open.
 */
class HandoffBatches(
    private val db: RevealDatabase,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [at]: the `handoff_at` every photo of the batch gets; [follower]: an earlier intent of this batch came before. */
    class Join(val at: Long, val follower: Boolean)

    @Serializable
    private data class State(
        val key: String,
        val at: Long,
        val touched: Long,
        val claimed: Boolean = false,
        val shown: Boolean = false,
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val library = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when a cascade ended with nothing to open in the editor: show "Opened from other apps". */
    val openLibrary: SharedFlow<Unit> = library.asSharedFlow()

    private suspend fun load(): State? = db.metaDao().get(KEY)?.let {
        runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull()
    }

    private suspend fun save(state: State) = db.metaDao().put(MetaEntity(KEY, json.encodeToString(State.serializer(), state)))

    /** Joins (or starts) the batch [key]: the same stamp for every intent inside the window. */
    suspend fun join(key: String): Join = mutex.withLock {
        val now = clock()
        val current = load()?.takeIf { it.key == key && now - it.touched <= WINDOW_MS }
        if (current == null) {
            save(State(key, at = now, touched = now))
            Join(now, follower = false)
        } else {
            save(current.copy(touched = now))
            Join(current.at, follower = true)
        }
    }

    /** The intent was ingested: the window restarts (a big batch takes a while to resolve). */
    suspend fun touch(key: String) = mutex.withLock {
        load()?.takeIf { it.key == key }?.let { save(it.copy(touched = clock())) }
    }

    /** An intent that opens something (an `INITIAL_*`) belongs to the batch: no library fallback is needed. */
    suspend fun claim(key: String) = mutex.withLock {
        load()?.takeIf { it.key == key }?.let { save(it.copy(claimed = true, touched = clock())) }
    }

    /**
     * A silent member finished. When nothing claims the batch for [SETTLE_MS] after the last touch (no intent carried
     * an `INITIAL_*`), the library opens instead, so a batch without an item to open is still shown.
     */
    fun silentFinished(key: String) {
        scope.launch {
            delay(SETTLE_MS)
            val show = mutex.withLock {
                val state = load()
                if (state != null && state.key == key && !state.claimed && !state.shown &&
                    clock() - state.touched >= SETTLE_MS - SLACK_MS
                ) {
                    save(state.copy(shown = true))
                    true
                } else {
                    false
                }
            }
            if (show) library.emit(Unit)
        }
    }

    companion object {
        private const val KEY = "handoff.batch"

        /** How long after its last intent a batch stays open for more. */
        const val WINDOW_MS = 10_000L
        const val SETTLE_MS = 5_000L
        private const val SLACK_MS = 100L

        /** The batch key of an intent: its sender and title; null when it has no title (no batching). */
        fun key(senderPackage: String?, title: String?): String? =
            title?.takeIf { it.isNotBlank() }?.let { "${senderPackage.orEmpty()}\u0000$it" }
    }
}
