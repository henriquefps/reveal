@file:Suppress("ReturnCount", "LoopWithTooManyJumpStatements")

package dev.hfps.reveal.sync.engine

import android.util.Log
import androidx.room.withTransaction
import dev.hfps.reveal.data.db.CullingFileEntity
import dev.hfps.reveal.data.db.CullingRecordEntity
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PendingCulling
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SyncTriggers
import dev.hfps.reveal.data.model.CullingField
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.sync.api.CullingChange
import dev.hfps.reveal.sync.api.CullingFieldState
import dev.hfps.reveal.sync.api.CullingRecord
import dev.hfps.reveal.sync.api.CullingResult
import dev.hfps.reveal.sync.api.ServerInfo
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What one culling sync did (logs, tests). */
data class CullingReport(
    /** Changes the server took (applied, unchanged, or a conflict either side won). */
    val pushed: Int = 0,
    /** Changes refused (by the server or the client's own check): kept local, never sent again as they are. */
    val refused: Int = 0,
    /** Culling records pulled. */
    val pulled: Int = 0,
    /** Photo fields set from the server's records. */
    val applied: Int = 0,
)

/**
 * Culling through hoard-server (Hoard ADR 0016 "Client rules", Reveal ADR 0027): rating, flag and label sync **per
 * field**, last writer wins on `modifiedAt` then device id, on the server's culling records instead of inside `photo`
 * records.
 *
 * - **Push**: the queue of local decisions (`culling_pending`, filled by the sync triggers) goes to `POST /v1/culling`,
 *   one change per photo and field, named by the photo's main file fingerprint, with the time the decision was made
 *   and the field's `baseRev` as this device last saw it (0 for a blind change: an imported sidecar, a dated hand-off,
 *   a value from before pairing). A refused change (`invalid`, or a label the client already knows the server refuses)
 *   stays local and is not retried; the rest of the queue goes on.
 * - **Pull**: the items feed with `include=culling` (its own cursor), every record mirrored (`culling_records`, its
 *   member files in `culling_files`), so a photo found later maps without asking the server.
 * - **Apply**: each local photo whose files include one of a record's member fingerprints takes the record's fields,
 *   except a field with a local change pending (it is pushed; the server decides; the next pull brings the winner).
 *   Applied with the sync triggers quiet: nothing is queued back, no sidecar is written.
 */
class CullingSync(private val db: RevealDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.cullingDao()
    private val meta = db.metaDao()

    /** One culling sync with [api] (the server lists `culling`). */
    suspend fun run(api: SyncApi, info: ServerInfo): CullingReport {
        seed(info.serverId)
        var pulled = 0
        // First time with this server: learn its records first, so the queue goes up with the right base revisions.
        if (meta.get(KEY_CURSOR) == null) pulled += pull(api, info)
        val (pushed, refused) = push(api, info)
        pulled += pull(api, info)
        resolveDerived()
        val applied = apply()
        return CullingReport(pushed, refused, pulled, applied)
    }

    /**
     * Once per server: every local non-default value is queued (blind, dated as old as it can be), since the server's
     * bridge from `photo` records stops once this device declared `culling` (Hoard ADR 0016 finding A).
     */
    private suspend fun seed(serverId: String) {
        if (meta.get(KEY_SEEDED) == serverId) return
        db.withTransaction {
            dao.seedRatings()
            dao.seedFlags()
            dao.seedLabels()
            meta.put(MetaEntity(KEY_SEEDED, serverId))
        }
    }

    // ------------------------------------------------------------------ push

    @Suppress("CyclomaticComplexMethod")
    private suspend fun push(api: SyncApi, info: ServerInfo): Pair<Int, Int> {
        val batch = (info.intLimit("maxCullingChanges") ?: MAX_CHANGES).coerceIn(1, MAX_CHANGES)
        var pushed = 0
        var refused = 0
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val pending = dao.pending(batch)
            if (pending.isEmpty()) break
            val before = pushed + refused
            val outgoing = ArrayList<Pair<PendingCulling, CullingChange>>()
            db.withTransaction {
                val foreign = keptForeignPhotoIds()
                for (change in pending) {
                    val value = if (change.photoId in foreign) null else wireValue(change)
                    if (value == null) {
                        // Also a photo kept from another server (SyncStore.keptPhotos): it stays on this device.
                        // A label the server would refuse (too long, control characters): it stays on this device.
                        dao.refused(
                            change.photoId,
                            change.field,
                            change.modifiedAt,
                            fieldRev(change.photoId, change.field),
                        )
                        refused++
                    } else {
                        val base = if (change.blind) 0 else fieldRev(change.photoId, change.field)
                        outgoing += change to CullingChange(
                            fingerprint = change.fingerprint,
                            field = change.field,
                            value = value,
                            baseRev = base,
                            modifiedAt = change.modifiedAt.coerceAtLeast(1),
                        )
                    }
                }
            }
            if (outgoing.isEmpty()) continue
            val response = api.pushCulling(outgoing.map { it.second })
            db.withTransaction {
                for ((index, sent) in outgoing.withIndex()) {
                    val result = response.results.firstOrNull { it.index == index } ?: response.results.getOrNull(index)
                    if (result == null) continue
                    if (pushed(sent.first, sent.second, result)) pushed++ else refused++
                }
            }
            // A round that settled nothing (no answer for any change) would read the same batch again.
            if (pushed + refused == before) break
        }
        return pushed to refused
    }

    /** Local ids of the photos an unpairing kept from another server ([SyncStore.keptPhotos]). */
    private suspend fun keptForeignPhotoIds(): Set<Long> {
        val uuids = db.metaDao().get(SyncStore.KEY_KEPT_PHOTOS)?.split(',')?.map { it.substringBefore('@') }
            ?.filter { it.isNotEmpty() }.orEmpty()
        return uuids.mapNotNullTo(HashSet()) { db.syncDao().photoByUuid(it)?.id }
    }

    /** Records the server's answer to one change; false when it was refused. */
    private suspend fun pushed(local: PendingCulling, change: CullingChange, result: CullingResult): Boolean {
        if (result.status == STATUS_INVALID) {
            Log.w(TAG, "The server refused a ${change.field} change: ${result.code}")
            dao.refused(local.photoId, local.field, local.modifiedAt, fieldRev(local.photoId, local.field))
            return false
        }
        val itemId = result.itemId
        val rev = result.rev
        if (itemId != null) {
            // The shot this photo is: its main file maps it from now on, even before the record comes in a pull.
            dao.addFiles(listOf(CullingFileEntity(local.fingerprint, itemId)))
            val record = dao.record(itemId) ?: CullingRecordEntity(itemId)
            val ours = result.status != STATUS_CONFLICT || result.winner == WINNER_INCOMING
            val at = if (result.clipped) minOf(change.modifiedAt, clock()) else change.modifiedAt
            val updated = if (ours && rev != null) withField(record, change.field, change.value, rev, at) else record
            dao.putRecord(updated.copy(rev = maxOf(updated.rev, rev ?: 0)))
        }
        dao.pushed(local.photoId, local.field, local.modifiedAt)
        return true
    }

    /** The field's revision on the record [photoId] maps to (0 = never seen). */
    private suspend fun fieldRev(photoId: Long, field: String): Long {
        val record = dao.recordOf(photoId) ?: return 0
        return when (CullingField.of(field)) {
            CullingField.RATING -> record.ratingRev
            CullingField.FLAG -> record.flagRev
            CullingField.LABEL -> record.labelRev
            null -> 0
        }
    }

    // ------------------------------------------------------------------ pull

    private suspend fun pull(api: SyncApi, info: ServerInfo): Int {
        val limit = (info.intLimit("maxPullLimit") ?: MAX_PULL).coerceIn(1, MAX_PULL)
        var cursor = meta.get(KEY_CURSOR).orEmpty()
        var count = 0
        var rounds = 0
        while (rounds++ < MAX_PAGES) {
            val page = try {
                api.cullingChanges(cursor, limit)
            } catch (e: SyncHttpException) {
                if (e.code != "invalid_cursor" || cursor.isEmpty()) throw e
                // A restored server or another one: from the start (storing a record twice is harmless).
                cursor = ""
                continue
            }
            db.withTransaction {
                for (record in page.culling) store(record)
                meta.put(MetaEntity(KEY_CURSOR, page.cursor))
            }
            count += page.culling.size
            cursor = page.cursor
            if (!page.hasMore) break
        }
        return count
    }

    /** Mirrors one pulled record; a field this device pushed at a newer revision keeps that state. */
    private suspend fun store(pulled: CullingRecord) {
        val existing = dao.record(pulled.itemId) ?: CullingRecordEntity(pulled.itemId)
        var entity = existing.copy(rev = maxOf(existing.rev, pulled.rev), replacedBy = pulled.replacedBy)
        pulled.rating?.let { entity = withState(entity, CullingField.RATING.key, it) }
        pulled.flag?.let { entity = withState(entity, CullingField.FLAG.key, it) }
        pulled.label?.let { entity = withState(entity, CullingField.LABEL.key, it) }
        dao.putRecord(entity)
        when {
            // A replaced item's shot is the live item's now (its fields were carried over there).
            pulled.replacedBy != null -> dao.clearFiles(pulled.itemId)

            pulled.fingerprints.isNotEmpty() -> {
                dao.clearFiles(pulled.itemId)
                dao.addFiles(pulled.fingerprints.distinct().map { CullingFileEntity(it, pulled.itemId) })
            }

            // `[]` and live: a shot the server does not index; the files this device pushed it by stay.
            else -> Unit
        }
    }

    private fun withState(record: CullingRecordEntity, field: String, state: CullingFieldState): CullingRecordEntity {
        val current = when (CullingField.of(field)) {
            CullingField.RATING -> record.ratingRev
            CullingField.FLAG -> record.flagRev
            CullingField.LABEL -> record.labelRev
            null -> return record
        }
        if (state.rev < current) return record
        return withField(record, field, state.value, state.rev, state.modifiedAt)
    }

    /**
     * Records with no member file this device knows and no `replacedBy`: shots the server does not index, whose id is
     * derived from their main file's fingerprint (Hoard ADR 0014 "Item id"). A local main file that derives one of
     * these ids is that shot (Hoard ADR 0016 "Mapping a record" 3).
     */
    private suspend fun resolveDerived() {
        val orphans = dao.orphanItems().toHashSet()
        if (orphans.isEmpty()) return
        val found = dao.mainFingerprints().mapNotNull { fingerprint ->
            derivedItemId(fingerprint).takeIf { it in orphans }?.let { CullingFileEntity(fingerprint, it) }
        }
        if (found.isNotEmpty()) dao.addFiles(found)
    }

    // ------------------------------------------------------------------ apply

    /**
     * Sets each photo's fields to its shot's record (Hoard ADR 0016 "Client rules" 3), where no local change of that
     * field is pending, quietly: no queued change, no dirty `photo` record, no sidecar write.
     */
    suspend fun apply(): Int = db.withTransaction {
        val quiet = meta.get(SyncTriggers.APPLYING) == null
        if (quiet) meta.put(MetaEntity(SyncTriggers.APPLYING, "1"))
        var count = 0
        try {
            for (stale in dao.staleRatings()) {
                val rating = stale.value.toIntOrNull()?.coerceIn(0, MAX_RATING) ?: continue
                dao.setRating(stale.photoId, rating)
                dao.dropRefused(stale.photoId, CullingField.RATING.key)
                count++
            }
            for (stale in dao.staleFlags()) {
                val flag = PickFlag.entries.firstOrNull { it.name == stale.value } ?: continue
                dao.setFlag(stale.photoId, flag)
                dao.dropRefused(stale.photoId, CullingField.FLAG.key)
                count++
            }
            for (stale in dao.staleLabels()) {
                dao.setLabel(stale.photoId, stale.value.takeIf { it.isNotEmpty() })
                dao.dropRefused(stale.photoId, CullingField.LABEL.key)
                count++
            }
        } finally {
            if (quiet) meta.delete(SyncTriggers.APPLYING)
        }
        count
    }

    /** Forgets the server's records and cursor (unpair, another server); local decisions stay queued. */
    suspend fun forget() = db.withTransaction {
        dao.clearRecords()
        dao.clearAllFiles()
        dao.retryRefused()
        for (key in KEYS) meta.delete(key)
    }

    companion object {
        private const val TAG = "RevealSync"
        const val KEY_CURSOR = "sync.culling.cursor"
        const val KEY_SEEDED = "sync.culling.seeded"
        val KEYS = listOf(KEY_CURSOR, KEY_SEEDED)

        /** `POST /v1/culling` takes at most 500 changes. */
        private const val MAX_CHANGES = 500
        private const val MAX_PULL = 500
        private const val MAX_ROUNDS = 1000
        private const val MAX_PAGES = 10_000
        private const val MAX_RATING = 5
        const val STATUS_INVALID = "invalid"
        const val STATUS_CONFLICT = "conflict"
        const val WINNER_INCOMING = "incoming"

        /** The longest label the server takes, in characters (Hoard ADR 0016). */
        const val MAX_LABEL = 64
        private const val CONTROL_MAX = 0x20
        private const val DELETE = 0x7f

        /** The wire value of a pending change, or null when the server would refuse it (a label: never sent). */
        fun wireValue(change: PendingCulling): JsonElement? = when (CullingField.of(change.field)) {
            CullingField.RATING -> JsonPrimitive(change.rating.coerceIn(0, MAX_RATING))
            CullingField.FLAG -> JsonPrimitive(change.flag.name.lowercase())
            CullingField.LABEL -> validLabel(change.label)?.let(::JsonPrimitive)
            null -> null
        }

        /**
         * [label] as the server stores it (trimmed, `""` = none), or null when it would refuse it: over 64 characters
         * or with a control character (Hoard ADR 0016 "The culling record"). Lightroom allows free text there.
         */
        fun validLabel(label: String?): String? {
            val trimmed = label.orEmpty().trim()
            if (trimmed.any { it.code < CONTROL_MAX || it.code == DELETE }) return null
            if (trimmed.codePointCount(0, trimmed.length) > MAX_LABEL) return null
            return trimmed
        }

        /**
         * The id hoard-server gives the item whose main file is [fingerprint] (Hoard ADR 0014 "Item id"): a version 8
         * UUID from SHA-256 of `"hoard-item\u0000" + fingerprint`.
         */
        fun derivedItemId(fingerprint: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest("hoard-item\u0000$fingerprint".toByteArray())
            val b = hash.copyOf(UUID_BYTES)
            b[VERSION_BYTE] = ((b[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_8).toByte()
            b[VARIANT_BYTE] = ((b[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
            val hex = b.joinToString("") { "%02x".format(it.toInt() and BYTE) }
            var at = 0
            return UUID_GROUPS.joinToString("-") { length -> hex.substring(at, at + length).also { at += length } }
        }

        private const val UUID_BYTES = 16
        private val UUID_GROUPS = listOf(8, 4, 4, 4, 12)
        private const val VERSION_BYTE = 6
        private const val VARIANT_BYTE = 8
        private const val LOW_NIBBLE = 0x0f
        private const val VERSION_8 = 0x80
        private const val VARIANT_MASK = 0x3f
        private const val VARIANT_RFC = 0x80
        private const val BYTE = 0xff

        /** [record] with [field] set to the wire [value] at [rev] and [at]; an unreadable value leaves it as it was. */
        internal fun withField(
            record: CullingRecordEntity,
            field: String,
            value: JsonElement,
            rev: Long,
            at: Long,
        ): CullingRecordEntity {
            val primitive = runCatching { value.jsonPrimitive }.getOrNull() ?: return record
            return when (CullingField.of(field)) {
                CullingField.RATING -> primitive.intOrNull?.let {
                    record.copy(rating = it.coerceIn(0, MAX_RATING), ratingRev = rev, ratingAt = at)
                } ?: record

                CullingField.FLAG -> PickFlag.entries.firstOrNull {
                    it.name.equals(primitive.contentOrNull, ignoreCase = true)
                }?.let { record.copy(flag = it.name, flagRev = rev, flagAt = at) } ?: record

                CullingField.LABEL -> primitive.contentOrNull?.let {
                    record.copy(label = it.trim(), labelRev = rev, labelAt = at)
                } ?: record

                null -> record
            }
        }
    }
}
