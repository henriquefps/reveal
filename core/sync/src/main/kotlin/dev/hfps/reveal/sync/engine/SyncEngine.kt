@file:Suppress("LoopWithTooManyJumpStatements")

package dev.hfps.reveal.sync.engine

import android.util.Log
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.ServerInfo
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What one metadata sync did (logs, tests and the measurement). */
data class SyncReport(
    val pushed: Int,
    val conflicts: Int,
    val pulled: Int,
    val copies: Int,
    val parked: Int,
    val nasFiles: Int,
    val lookedUp: Int,
    /** The one-off clean-up of 0.5.3 when this run did it, else null. */
    val cleanup: CleanupReport? = null,
)

/** The server behind the paired URL is another one (a reset, or the URL now points elsewhere). */
class ServerChangedException(val expected: String, val found: String) : IllegalStateException("server changed")

/**
 * One metadata sync with a server (ADR 0018 "Sync run"): push the dirty records (photos first, then versions,
 * settings), pull the change feed from the stored cursor, retry the inbox, read the NAS feed, and ask the server about
 * fingerprints it was not asked about yet. Idempotent and resumable: cursors are saved after every applied page, and a
 * record pushed twice (a lost response) is answered "applied" without a new revision.
 */
class SyncEngine(
    private val db: RevealDatabase,
    private val store: SyncStore,
    serverLibrary: ServerLibrary? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val library = serverLibrary ?: ServerLibrary(db, store, clock)
    private val meta = db.metaDao()

    /**
     * Runs a sync against [api]. [serverId] is the paired server's id (another one throws [ServerChangedException]).
     */
    suspend fun sync(api: SyncApi, serverId: String): SyncReport {
        val info = api.server()
        if (info.serverId != serverId) throw ServerChangedException(serverId, info.serverId)
        val devices = runCatching { api.devices().devices }.getOrDefault(emptyList())
        val names = devices.associate { it.id to it.name }
        val deviceName = { id: String -> names[id] }
        val first = meta.get(KEY_TRACKING) != serverId
        if (first) {
            // A first sync pulls before it pushes, so photos both devices already have adopt the server's ids.
            store.startTracking()
        }
        var pulled = 0
        var copies = 0
        var parked = 0
        // 0.5.3 (FIX/V), once: the feed again from the start, so records whose photo this device lost come back.
        val cleanup = meta.get(KEY_CLEANUP) != CLEANUP_VERSION
        if (cleanup && !first && meta.get(KEY_CLEANUP_PULLED) != CLEANUP_VERSION) {
            library.prepareCleanup()
            meta.delete(KEY_CURSOR)
        }
        if (first) {
            val stats = pull(api, info, deviceName)
            pulled += stats.applied
            copies += stats.copies
            meta.put(MetaEntity(KEY_TRACKING, serverId))
        }
        val (pushed, conflicts) = push(api, info)
        val stats = pull(api, info, deviceName)
        if (cleanup) meta.put(MetaEntity(KEY_CLEANUP_PULLED, CLEANUP_VERSION))
        pulled += stats.applied
        copies += stats.copies
        val retried = store.retryInbox(deviceName)
        pulled += retried.applied
        copies += retried.copies
        parked = db.syncDao().inbox().size
        val nas = pullNas(api, info)
        val lookups = lookup(api, info)
        var cleaned: CleanupReport? = null
        if (cleanup) {
            cleaned = library.cleanUp()
            // Its tombstones go up now, not with the next change.
            push(api, info)
            meta.put(MetaEntity(KEY_CLEANUP, CLEANUP_VERSION))
            meta.delete(KEY_CLEANUP_PULLED)
        }
        meta.put(MetaEntity(KEY_LAST_SYNC, clock().toString()))
        return SyncReport(pushed, conflicts, pulled, copies, parked, nas, lookups, cleaned)
    }

    // ------------------------------------------------------------------ push

    @Suppress("CyclomaticComplexMethod", "ThrowsCount")
    private suspend fun push(api: SyncApi, info: ServerInfo): Pair<Int, Int> {
        val batch = (info.intLimit("maxPushChanges") ?: DEFAULT_PUSH).coerceIn(1, DEFAULT_PUSH)
        val maxData =
            ((info.limit("maxRecordData") ?: DEFAULT_MAX_DATA.toLong()).toInt() * DATA_MARGIN_PERCENT) / PERCENT
        var pushed = 0
        var conflicts = 0
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val next = store.outgoing(batch, maxData)
            if (next.changes.isEmpty()) {
                if (next.read == 0) break else continue
            }
            val outgoing = fitBody(next.changes)
            val response = try {
                api.push(outgoing.map(Outgoing::change))
            } catch (e: SyncHttpException) {
                if (e.code != "invalid_change") throw e
                // One record the server refuses must not block the rest: drop it from this run and log it.
                val index = e.details?.get("index")?.jsonPrimitive?.intOrNull ?: throw e
                val bad = outgoing.getOrNull(index) ?: throw e
                Log.w(TAG, "The server refused a ${bad.change.kind} record: ${e.message}")
                refuse(bad.change, bad.stamp)
                continue
            }
            for ((index, result) in response.results.withIndex()) {
                val out = outgoing.getOrNull(index) ?: continue
                store.pushed(out, result)
                pushed++
                if (result.status != SyncStore.STATUS_APPLIED) conflicts++
            }
        }
        return pushed to conflicts
    }

    /** The longest prefix of [changes] whose JSON body stays under the server's 8 MiB (the rest goes next round). */
    private fun fitBody(changes: List<Outgoing>): List<Outgoing> {
        var total = 0L
        for ((index, out) in changes.withIndex()) {
            total += (out.change.data?.toString()?.length ?: 0) + CHANGE_OVERHEAD
            if (total > MAX_BODY && index > 0) return changes.subList(0, index)
        }
        return changes
    }

    private suspend fun refuse(change: PushChange, stamp: Long) {
        db.syncDao().clean(change.kind, change.id, stamp)
    }

    // ------------------------------------------------------------------ pull

    private suspend fun pull(api: SyncApi, info: ServerInfo, deviceName: (String) -> String?): ApplyStats {
        val limit = (info.intLimit("maxPullLimit") ?: DEFAULT_PULL).coerceIn(1, DEFAULT_PULL)
        val total = ApplyStats()
        var cursor = meta.get(KEY_CURSOR).orEmpty()
        var rounds = 0
        while (rounds++ < MAX_PAGES) {
            val page = try {
                api.pull(cursor, limit)
            } catch (e: SyncHttpException) {
                if (e.code != "invalid_cursor" || cursor.isEmpty()) throw e
                // A restored server or another one: from the start (applying a record twice is harmless).
                cursor = ""
                continue
            }
            val stats = store.apply(page.changes, deviceName)
            total.applied += stats.applied
            total.copies += stats.copies
            total.parked += stats.parked
            cursor = page.cursor
            meta.put(MetaEntity(KEY_CURSOR, cursor))
            if (!page.hasMore) break
        }
        return total
    }

    private suspend fun pullNas(api: SyncApi, info: ServerInfo): Int {
        val limit = (info.intLimit("maxPullLimit") ?: DEFAULT_PULL).coerceIn(1, DEFAULT_PULL)
        var cursor = meta.get(KEY_NAS_CURSOR).orEmpty()
        var count = 0
        var rounds = 0
        while (rounds++ < MAX_PAGES) {
            val page = try {
                api.nasFiles(cursor, limit)
            } catch (e: SyncHttpException) {
                if (e.code != "invalid_cursor" || cursor.isEmpty()) throw e
                cursor = ""
                continue
            }
            store.applyNas(page.files)
            count += page.files.size
            cursor = page.cursor
            meta.put(MetaEntity(KEY_NAS_CURSOR, cursor))
            if (!page.hasMore) break
        }
        return count
    }

    // ------------------------------------------------------------------ fingerprint lookups

    /**
     * "Do you have fingerprint X?" for photos not asked yet (or missing a day ago: another device may have uploaded).
     */
    private suspend fun lookup(api: SyncApi, info: ServerInfo): Int {
        val limit = (info.intLimit("maxLookup") ?: DEFAULT_LOOKUP).coerceIn(1, DEFAULT_LOOKUP)
        var count = 0
        var rounds = 0
        val before = clock() - RECHECK_MS
        while (rounds++ < MAX_PAGES) {
            val candidates = db.syncDao().unchecked(before, (limit / 2).coerceAtLeast(1))
            if (candidates.isEmpty()) break
            // A Server photo opens from any file of its shot (FIX/V): its other files are asked about too.
            val others = db.syncDao().serverFilesOf(candidates.map { it.photoId }.distinct())
            val asked = (candidates.map { it.fingerprint } + others.mapNotNull { it.fingerprint }).distinct()
            val found = HashMap<String, String>()
            for (part in asked.chunked(limit)) {
                for (hit in api.lookup(part).found) found[hit.fingerprint] = hit.source
            }
            store.filesLookedUp(asked, found.keys)
            val serverPhotos = others.groupBy { it.photoId }
            for (candidate in candidates) {
                val source = found[candidate.fingerprint]
                    ?: serverPhotos[candidate.photoId]?.firstNotNullOfOrNull { file -> file.fingerprint?.let(found::get) }
                store.lookedUp(candidate.photoId, source)
            }
            count += candidates.size
            if (candidates.size < (limit / 2).coerceAtLeast(1)) break
        }
        return count
    }

    /** Last successful sync (Unix ms), or null. */
    suspend fun lastSync(): Long? = meta.get(KEY_LAST_SYNC)?.toLongOrNull()

    /** Forgets the cursors (unpair, another server). */
    suspend fun resetCursors() {
        for (key in listOf(KEY_CURSOR, KEY_NAS_CURSOR, KEY_TRACKING, KEY_LAST_SYNC, KEY_CLEANUP, KEY_CLEANUP_PULLED)) {
            meta.delete(key)
        }
    }

    companion object {
        private const val TAG = "RevealSync"
        const val KEY_CURSOR = "sync.cursor"
        const val KEY_NAS_CURSOR = "sync.nasCursor"
        const val KEY_TRACKING = "sync.tracking"
        const val KEY_LAST_SYNC = "sync.lastSync"

        /** The one-off clean-up of 0.5.3 is done (its version), and its re-pull is done (a run cut short resumes). */
        const val KEY_CLEANUP = "sync.cleanup"
        const val KEY_CLEANUP_PULLED = "sync.cleanup.pulled"
        const val CLEANUP_VERSION = "1"
        private const val DEFAULT_PUSH = 500
        private const val DEFAULT_PULL = 500
        private const val DEFAULT_LOOKUP = 1000
        private const val DEFAULT_MAX_DATA = 2 * 1024 * 1024
        private const val DATA_MARGIN_PERCENT = 90
        private const val PERCENT = 100
        private const val MAX_ROUNDS = 1000
        private const val MAX_BODY = 7L * 1024 * 1024
        private const val CHANGE_OVERHEAD = 200
        private const val MAX_PAGES = 10_000
        private const val RECHECK_MS = 24L * 60 * 60 * 1000
    }
}
