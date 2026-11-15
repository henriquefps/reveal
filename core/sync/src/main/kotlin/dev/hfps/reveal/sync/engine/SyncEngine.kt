@file:Suppress("LoopWithTooManyJumpStatements")

package dev.hfps.reveal.sync.engine

import android.util.Log
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.api.DeviceReport
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
    /** Culling through the server's culling records (ADR 0027), null when the server does not list `culling`. */
    val culling: CullingReport? = null,
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
    /** What this installation reports to a server with `device-identity` (null: nothing, as before 0.8). */
    private val device: ClientDevice? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val library = serverLibrary ?: ServerLibrary(db, store, clock)
    private val meta = db.metaDao()
    val culling = CullingSync(db, clock)

    /**
     * Runs a sync against [api]. [serverId] is the paired server's id (another one throws [ServerChangedException]).
     */
    suspend fun sync(api: SyncApi, serverId: String): SyncReport {
        val info = api.server()
        if (info.serverId != serverId) throw ServerChangedException(serverId, info.serverId)
        // Culling per field through the server's culling records (ADR 0027), when it has them; otherwise inside the
        // photo records, as before. The capability is declared before any photo record goes up under this version,
        // so the server stops bridging this device's records (Hoard ADR 0016 "Client rules" 5).
        val serverCulling = info.has(FEATURE_CULLING)
        store.cullingInRecords = !serverCulling
        if (info.has(FEATURE_DEVICE_IDENTITY)) report(api, serverId, serverCulling)
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
        val (pushedFirst, conflicts) = push(api, info)
        var pushed = pushedFirst
        val stats = pull(api, info, deviceName)
        if (cleanup) meta.put(MetaEntity(KEY_CLEANUP_PULLED, CLEANUP_VERSION))
        pulled += stats.applied
        copies += stats.copies
        val retried = store.retryInbox(deviceName)
        pulled += retried.applied
        copies += retried.copies
        parked = db.syncDao().inbox().size
        pushed += upgrades(api, info, first)
        val cullingReport = if (serverCulling) culling.run(api, info) else null
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
        return SyncReport(pushed, conflicts, pulled, copies, parked, nas, lookups, cleaned, cullingReport)
    }

    /** The one-off steps of updates that run after a pull ([sync]); returns how many changes they pushed. */
    private suspend fun upgrades(api: SyncApi, info: ServerInfo, first: Boolean): Int {
        var count = 0
        // 0.5.4 (FIX/W), once and after a pull (so nothing newer is overwritten): this device's rotated photos go up
        // again with their orientation, which older versions never sent (other devices showed them stretched).
        if (meta.get(KEY_ORIENTATION) != ORIENTATION_VERSION) {
            val marked = db.syncDao().markOrientedPhotosDirty(clock())
            if (marked > 0) count += push(api, info).first
            meta.put(MetaEntity(KEY_ORIENTATION, ORIENTATION_VERSION))
        }
        // 0.9.0 (W5), once and after a pull (every local revision is the server's latest, so a version pushed again
        // never turns a newer edit of another device into a conflict copy): edited versions whose photo the server
        // never heard of go up with their photo, and this device's edited versions go up again naming their photo's
        // current id and main-file fingerprint. A first sync with a server needs none: everything just went up.
        if (meta.get(KEY_VERSION_PHOTOS) != VERSION_PHOTOS_VERSION) {
            val marked = if (first) 0 else store.repairVersionPhotos()
            if (marked > 0) count += push(api, info).first
            meta.put(MetaEntity(KEY_VERSION_PHOTOS, VERSION_PHOTOS_VERSION))
        }
        return count
    }

    /**
     * `PATCH /v1/device` with the app id, model and (with [culling]) `features: ["culling"]` (Hoard ADR 0017 "Client
     * calls" 2), once per server and whenever what is reported changes (an update, a renamed build).
     */
    private suspend fun report(api: SyncApi, serverId: String, culling: Boolean) {
        val device = device ?: return
        val report = DeviceReport(
            app = device.app,
            manufacturer = device.manufacturer,
            model = device.model,
            features = if (culling) listOf(FEATURE_CULLING) else null,
        )
        val signature = serverId + " " + SyncApi.json.encodeToString(DeviceReport.serializer(), report)
        if (meta.get(KEY_DEVICE_REPORTED) == signature) return
        try {
            val view = api.reportDevice(report)
            meta.put(MetaEntity(KEY_DEVICE_REPORTED, signature))
            view.physicalDevice?.id?.let { meta.put(MetaEntity(KEY_PHYSICAL_DEVICE, it)) }
        } catch (e: SyncHttpException) {
            // An answer worth a retry stops the run; anything else (a field the server refuses) is tried next run.
            if (e.transient || e.unauthorized) throw e
            Log.w(TAG, "The server refused the device report: ${e.code}")
        }
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
        store.refused(change, stamp)
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
                    ?: serverPhotos[candidate.photoId]?.firstNotNullOfOrNull { file ->
                        file.fingerprint?.let(found::get)
                    }
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
        val keys = listOf(
            KEY_CURSOR,
            KEY_NAS_CURSOR,
            KEY_TRACKING,
            KEY_LAST_SYNC,
            KEY_CLEANUP,
            KEY_CLEANUP_PULLED,
            KEY_ORIENTATION,
            KEY_VERSION_PHOTOS,
            KEY_DEVICE_REPORTED,
            KEY_PHYSICAL_DEVICE,
        )
        for (key in keys) {
            meta.delete(key)
        }
        culling.forget()
    }

    companion object {
        private const val TAG = "RevealSync"
        const val KEY_CURSOR = "sync.cursor"
        const val KEY_NAS_CURSOR = "sync.nasCursor"
        const val KEY_TRACKING = "sync.tracking"
        const val KEY_LAST_SYNC = "sync.lastSync"

        /** What was last reported with `PATCH /v1/device` (server id + body), and the physical device id read. */
        const val KEY_DEVICE_REPORTED = "sync.device.reported"
        const val KEY_PHYSICAL_DEVICE = "sync.device.physical"
        const val FEATURE_CULLING = "culling"
        const val FEATURE_DEVICE_IDENTITY = "device-identity"
        const val FEATURE_ITEMS = "items"

        /** The one-off clean-up of 0.5.3 is done (its version), and its re-pull is done (a run cut short resumes). */
        const val KEY_CLEANUP = "sync.cleanup"
        const val KEY_CLEANUP_PULLED = "sync.cleanup.pulled"
        const val CLEANUP_VERSION = "1"

        /** This device's rotated photos were pushed again with their orientation (FIX/W). */
        const val KEY_ORIENTATION = "sync.orientation"
        const val ORIENTATION_VERSION = "1"

        /** Edited versions were pushed again with their photo (ADR 0018 "W5"). */
        const val KEY_VERSION_PHOTOS = "sync.versionPhotos"
        const val VERSION_PHOTOS_VERSION = "1"
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
