package dev.hfps.reveal.data.handoff

import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RevealDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hand-offs that arrive as several intents with the same sender and `TITLE` (ADR 0011 "Batches over 500"): Hoard sends
 * a bigger batch as `startActivities(...)`, each intent marked with its `BATCH_INDEX`. Every intent opens its own
 * `MainActivity`; all of them are filed as **one** "Opened from other apps" batch (one `handoff_at`). The ones above
 * the first (`BATCH_INDEX` > 0, [HandoffRequest.silentMember]) ingest and finish silently, so the cascade unwinds to
 * the first, which shows the batch (the editor, or the library) with the notices of every member ([report],
 * [takeNotices]).
 *
 * The state is one record in `meta` (process death mid-cascade is fine): the batch's key, its stamp, when it was last
 * touched and the notices so far. A window of [WINDOW_MS] after the last touch keeps the batch open.
 */
class HandoffBatches(private val db: RevealDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    /** [at]: the `handoff_at` of every photo of the batch; [follower]: an earlier intent of the batch came first. */
    class Join(val at: Long, val follower: Boolean)

    @Serializable
    private data class State(
        val key: String,
        val at: Long,
        val touched: Long,
        /** What the members reported so far (summed), until the intent that shows the batch takes it. */
        val notFound: Int = 0,
        val notPaired: Int = 0,
        val otherServer: String? = null,
        val otherServerCount: Int = 0,
        val unreachable: Int = 0,
        /** The notices were handed to the UI: later members report on their own. */
        val taken: Boolean = false,
    ) {
        fun notice() = HandoffNotice(
            notFound,
            when {
                notPaired > 0 -> ServerIssue.NotPaired(notPaired)
                otherServer != null -> ServerIssue.OtherServer(otherServer, otherServerCount)
                unreachable > 0 -> ServerIssue.Unreachable(unreachable)
                else -> null
            },
        )

        fun plus(n: HandoffNotice): State {
            val issue = n.issue
            return copy(
                notFound = notFound + n.notFound,
                notPaired = notPaired + (issue as? ServerIssue.NotPaired)?.count.orZero(),
                otherServer = otherServer ?: (issue as? ServerIssue.OtherServer)?.serverName,
                otherServerCount = otherServerCount + (issue as? ServerIssue.OtherServer)?.count.orZero(),
                unreachable = unreachable + (issue as? ServerIssue.Unreachable)?.count.orZero(),
            )
        }
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val late = MutableSharedFlow<HandoffNotice>(extraBufferCapacity = 8)

    /** Notices of a member that finished after the batch's notice was shown (rare): shown on their own. */
    val lateNotices: SharedFlow<HandoffNotice> = late.asSharedFlow()

    private suspend fun load(): State? = db.metaDao().get(KEY)?.let {
        runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull()
    }

    private suspend fun save(state: State) =
        db.metaDao().put(MetaEntity(KEY, json.encodeToString(State.serializer(), state)))

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

    /**
     * A member reports its notices (not found, server issue) to the batch [key]; they are shown once, by
     * [takeNotices], with the ones of every other member. After that they go out on [lateNotices].
     */
    suspend fun report(key: String, notice: HandoffNotice) {
        if (notice.isEmpty) return
        val tooLate = mutex.withLock {
            val state = load()?.takeIf { it.key == key }
            when {
                state == null -> true

                state.taken -> true

                else -> {
                    save(state.plus(notice))
                    false
                }
            }
        }
        if (tooLate) late.emit(notice)
    }

    /** The notices of the whole batch [key] plus [own] (the showing intent's), once: the next call gets none. */
    suspend fun takeNotices(key: String?, own: HandoffNotice): HandoffNotice {
        if (key == null) return own
        return mutex.withLock {
            val state = load()?.takeIf { it.key == key } ?: return@withLock own
            val all = state.plus(own)
            save(
                all.copy(
                    taken = true,
                    notFound = 0,
                    notPaired = 0,
                    otherServer = null,
                    otherServerCount = 0,
                    unreachable = 0,
                ),
            )
            all.notice()
        }
    }

    companion object {
        private fun Int?.orZero() = this ?: 0

        private const val KEY = "handoff.batch"

        /** How long after its last intent a batch stays open for more. */
        const val WINDOW_MS = 10_000L

        /** The batch key of an intent: its sender and title; null when it has no title (no batching). */
        fun key(senderPackage: String?, title: String?, marked: Boolean = false): String? =
            (title?.takeIf { it.isNotBlank() } ?: "".takeIf { marked })?.let { "${senderPackage.orEmpty()}\u0000$it" }
    }
}

/** What a hand-off reports besides what it opened: [notFound] items and a [ServerIssue] (ADR 0011 "Amendment W3/H"). */
data class HandoffNotice(val notFound: Int = 0, val issue: ServerIssue? = null) {
    val isEmpty get() = notFound == 0 && issue == null
}
