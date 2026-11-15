@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.engine

import androidx.room.withTransaction
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ImageKind
import dev.hfps.reveal.data.db.ADOPT_FAILED_PREFIX
import dev.hfps.reveal.data.db.EditedVersion
import dev.hfps.reveal.data.db.FingerprintMatch
import dev.hfps.reveal.data.db.HistoryCursorEntity
import dev.hfps.reveal.data.db.HistoryEntity
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RecipeEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SnapshotEntity
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncAliasEntity
import dev.hfps.reveal.data.db.SyncInboxEntity
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.db.SyncTriggers
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.db.newUuid
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.sync.RemoteEdits
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.sync.api.FeedRecord
import dev.hfps.reveal.sync.api.NasFile
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.PushResult
import dev.hfps.reveal.sync.api.SyncApi
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A local setting that follows the owner across devices (ADR 0018 "Settings"): one `setting` record. */
interface SyncedSetting {
    /** The record id, `[a-z0-9][a-z0-9._-]{0,63}`. */
    val key: String

    /** The current value as JSON. */
    fun read(): JsonElement

    /** Applies a value another device pushed. */
    fun write(value: JsonElement)
}

/** One change ready to push and the `modified_at` it was read at (to keep it dirty when it changed meanwhile). */
data class Outgoing(val change: PushChange, val stamp: Long)

/** A push batch and how many dirty records were read for it (some need nothing: 0 = nothing left). */
data class OutgoingBatch(val changes: List<Outgoing>, val read: Int)

/** What one pull applied, for logs and tests. */
data class ApplyStats(
    var applied: Int = 0,
    var parked: Int = 0,
    var copies: Int = 0,
    var skipped: Int = 0,
    /** Local ids of the versions whose edit changed. */
    val versions: MutableSet<Long> = LinkedHashSet(),
)

/**
 * The database side of sync (ADR 0018): reads the dirty records into pushable changes, applies pulled records, NAS
 * files and fingerprint lookups, and keeps the Server source. Everything a pull writes runs with the change-tracking
 * triggers quiet ([SyncTriggers.APPLYING]), so applying a record never pushes it back.
 */
@Suppress("TooManyFunctions", "LargeClass")
class SyncStore(
    private val db: RevealDatabase,
    private val settings: List<SyncedSetting> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sync = db.syncDao()
    private val photos = db.photoDao()
    private val edits = db.editDao()
    private val meta = db.metaDao()
    private val json = SyncApi.json
    private val presetSync = PresetSync(db, clock)

    /**
     * Rating, flag and label of pulled `photo` records apply (an older server). False while the server has culling
     * records (ADR 0027): those three come from there, and a record's are ignored (they still go up in this device's
     * records, for devices on an older Reveal).
     */
    @Volatile
    var cullingInRecords: Boolean = true
    private val exportPresetSync = ExportPresetSync(db, clock)

    // ------------------------------------------------------------------ pairing

    /** A first sync with a server: every local photo and version is to be pushed, settings included. */
    suspend fun startTracking() = db.withTransaction {
        val now = clock()
        sync.markAllPhotos(now)
        sync.markAllVersions(now)
        sync.markAllPresets(now)
        sync.markAllExportPresets(now)
        for (setting in settings) meta.delete(settingKey(setting.key))
    }

    /** Unpairing (or a different server behind the same URL): forgets every sync row and the Server source. */
    suspend fun forget() = db.withTransaction {
        sync.clearRecords()
        sync.clearCloud()
        sync.clearUploads()
        sync.clearAliases()
        sync.clearInbox()
        sync.clearDeletes()
        sync.clearOnServer()
        sync.deleteServerSource()
        for (setting in settings) meta.delete(settingKey(setting.key))
        meta.delete(KEY_REPAIR_CHECKS)
        meta.delete(KEY_HELD_VERSIONS)
    }

    // ------------------------------------------------------------------ push

    /**
     * Up to [limit] pushable changes, photos first. Records that need nothing on the server (an unedited version that
     * was never pushed, a photo without a printed file yet) are marked clean on the way; a later change marks them
     * again.
     */
    suspend fun outgoing(limit: Int, maxData: Int): OutgoingBatch =
        // One transaction for the whole batch: thousands of small reads cost far less than one transaction each.
        db.withTransaction { readOutgoing(limit, maxData) }

    private suspend fun readOutgoing(limit: Int, maxData: Int): OutgoingBatch {
        // The server refuses a push of more than [limit] changes as a whole (hoard-server: 422 `invalid_change` without
        // an index, so nothing could ever go up again): settings, records and the photos added ahead of their versions
        // all count.
        val settingsOut = settingChanges()
        val room = limit - settingsOut.size
        if (room <= 0) return OutgoingBatch(settingsOut.take(limit), read = settingsOut.size)
        val out = ArrayList<Outgoing>()
        // Photos whose record is in this batch: a version's photo goes up before it (ADR 0018 "W5").
        val photosInBatch = HashSet<String>()
        val records = sync.dirty(room)
        for (record in records) {
            // The batch is full, or this version's photo would not fit with it: the version stays dirty and its photo
            // (marked dirty by [photoFirst]) leads the next round.
            if (out.size >= room || !offer(record, out, photosInBatch, room, maxData)) break
        }
        out += settingsOut
        return OutgoingBatch(out, read = records.size)
    }

    /** Adds [record]'s change to [out] (a version after its photo), holds or cleans it; false when there is no room. */
    private suspend fun offer(
        record: SyncRecordEntity,
        out: MutableList<Outgoing>,
        photosInBatch: MutableSet<String>,
        room: Int,
        maxData: Int,
    ): Boolean {
        var first: PhotoFirst? = null
        val change = when (record.kind) {
            Kinds.PHOTO -> photoChange(record)?.also { photosInBatch += it.id }

            Kinds.VERSION -> versionChange(record, maxData)?.let { version ->
                first = version.photo?.let { photoFirst(it, photosInBatch, out, room = out.size + 2 <= room) }
                version.change
            }

            Kinds.PRESET -> presetSync.change(record)

            Kinds.SETTING -> exportPresetSync.change(record)

            else -> null
        }
        when {
            first == PhotoFirst.NO_ROOM -> return false

            first == PhotoFirst.HELD -> hold(record)

            change == null -> {
                sync.clean(record.kind, record.recordId, record.modifiedAt)
                if (record.kind == Kinds.VERSION) unhold(record.recordId)
            }

            else -> out += Outgoing(change, record.modifiedAt)
        }
        return true
    }

    private enum class PhotoFirst { READY, HELD, NO_ROOM }

    /**
     * Makes sure the server knows [photo] before a version naming it goes up (ADR 0018 "W5"): a photo whose record the
     * server never accepted (a NAS photo made quietly from the feed, a hand-off, a photo whose record was never pushed)
     * joins this batch ahead of the version, so another device can map it by its fingerprint and the version applies
     * there instead of waiting in its inbox forever. [PhotoFirst.HELD] while the photo cannot be described yet (its
     * main file has no fingerprint): the version is held back until then ([hold]). [PhotoFirst.NO_ROOM] when the photo
     * would have to join but the batch has no [room] for it and the version: the photo is marked dirty, so it goes up
     * first next round.
     */
    private suspend fun photoFirst(
        photo: PhotoEntity,
        inBatch: MutableSet<String>,
        out: MutableList<Outgoing>,
        room: Boolean,
    ): PhotoFirst {
        // A photo record the server refused is not offered again in this process (the version goes up alone).
        if (photo.uuid in inBatch || photo.uuid in refusedPhotos) return PhotoFirst.READY
        val record = sync.record(Kinds.PHOTO, photo.uuid)
        // Accepted already, or deleted from the server on purpose (ServerLibrary): never brought back from here.
        if (record != null && (record.rev > 0 || record.deleted)) return PhotoFirst.READY
        val data = photoData(photo)
        val marked = when {
            record == null -> SyncRecordEntity(Kinds.PHOTO, photo.uuid, 0, dirty = true, modifiedAt = clock())
            !record.dirty -> record.copy(dirty = true)
            else -> record
        }
        if (marked != record) sync.putRecord(marked)
        // Without a fingerprint the record waits (and is pushed once the indexer fills it in: its trigger marks it).
        if (data == null) return PhotoFirst.HELD
        if (!room) return PhotoFirst.NO_ROOM
        out += Outgoing(
            PushChange(
                kind = Kinds.PHOTO,
                id = photo.uuid,
                baseRev = marked.rev,
                modifiedAt = marked.modifiedAt,
                data = json.encodeToJsonElement(PhotoData.serializer(), data),
            ),
            marked.modifiedAt,
        )
        inBatch += photo.uuid
        return PhotoFirst.READY
    }

    /** Photo records the server refused (`invalid_change`) since this process started. */
    private val refusedPhotos: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** The server refused this change (`invalid_change`): it is dropped from the run, and a photo is not re-offered. */
    suspend fun refused(change: PushChange, stamp: Long) {
        if (change.kind == Kinds.PHOTO) refusedPhotos += change.id
        sync.clean(change.kind, change.id, stamp)
        if (change.kind == Kinds.VERSION) unhold(change.id)
    }

    /**
     * A version waiting for its photo's record (ADR 0018 "W5"): not dirty, so it is not offered again, and listed in
     * `meta` [KEY_HELD_VERSIONS]; its [SyncRecordEntity.rev] stays the server's (a version the server has already, e.g.
     * one 0.8 pushed without its photo, goes up on top of its real revision once released: never as a create, which
     * the server would lose to its own state and keep as a conflict copy). Released by [releaseHeld].
     */
    private suspend fun hold(record: SyncRecordEntity) {
        sync.putRecord(record.copy(dirty = false))
        val held = heldVersions()
        if (record.recordId !in held) putHeld(held + record.recordId)
    }

    /** The versions [hold] keeps back (version uuids). */
    private suspend fun heldVersions(): Set<String> =
        meta.get(KEY_HELD_VERSIONS)?.split(',')?.filterTo(LinkedHashSet()) { it.isNotEmpty() }.orEmpty()

    private suspend fun putHeld(held: Set<String>) = when {
        held.isEmpty() -> meta.delete(KEY_HELD_VERSIONS)
        else -> meta.put(MetaEntity(KEY_HELD_VERSIONS, held.joinToString(",")))
    }

    /** [versionUuid] went up (or needs nothing on the server): no longer held. */
    private suspend fun unhold(versionUuid: String) {
        val held = heldVersions()
        if (versionUuid in held) putHeld(held - versionUuid)
    }

    /** True when [versionUuid] is held back by [hold]: local work the pull must not overwrite. */
    private suspend fun isHeld(versionUuid: String): Boolean = versionUuid in heldVersions()

    /**
     * The photo [photoUuid] (its id, or an alias of it) is known to the server now: its held versions are marked to go
     * up with the next push, at the revision they have (their edit time kept).
     */
    private suspend fun releaseHeld(photoUuid: String) {
        // Holds of an earlier W5 build (rev -1): pushed as creates, as that build did.
        sync.releaseHeldVersions(photoUuid)
        val held = heldVersions()
        if (held.isEmpty()) return
        val photoId = sync.photoByUuid(photoUuid)?.id ?: sync.alias(photoUuid) ?: return
        val left = LinkedHashSet<String>()
        for (uuid in held) {
            val version = sync.versionByUuid(uuid)
            val record = sync.record(Kinds.VERSION, uuid)
            when {
                // Gone (deleted, or its photo forgotten): nothing to hold any more.
                version == null || record == null -> Unit

                version.photoId == photoId -> sync.putRecord(record.copy(dirty = true))

                else -> left += uuid
            }
        }
        if (left.size != held.size) putHeld(left)
    }

    private suspend fun photoChange(record: SyncRecordEntity): PushChange? {
        if (record.deleted) {
            // A hand-off photo merged into a folder photo (ADR 0011 "One photo across sources"): the server's copy of
            // it goes; its versions now name the folder photo.
            return PushChange(Kinds.PHOTO, record.recordId, record.rev, record.modifiedAt, deleted = true)
        }
        val photo = sync.photoByUuid(record.recordId) ?: return null
        val data = photoData(photo) ?: return null
        return PushChange(
            kind = Kinds.PHOTO,
            id = photo.uuid,
            baseRev = record.rev,
            modifiedAt = record.modifiedAt,
            data = json.encodeToJsonElement(PhotoData.serializer(), data),
        )
    }

    /** `photo.data` of [photo], or null while its main file has no fingerprint. */
    suspend fun photoData(photo: PhotoEntity): PhotoData? {
        val files = photos.files(photo.id)
        val main = mainFile(files) ?: return null
        val fingerprint = main.fingerprint ?: return null
        val cloud = sync.cloud(photo.id)
        return PhotoData(
            fingerprint = fingerprint,
            files = files.mapNotNull { file ->
                file.fingerprint?.let { FileData(it, file.role.name, file.name, file.size) }
            },
            name = photo.name,
            format = photo.format.name,
            rating = photo.rating,
            flag = photo.flag.name,
            label = photo.label,
            captureTime = photo.captureTime,
            width = photo.width,
            height = photo.height,
            orientation = photo.orientation.takeIf { photo.metadataState == MetadataState.READ },
            hidden = photo.hidden,
            preview = cloud?.previewSha?.let { PreviewRef(it, cloud.previewWidth ?: 0, cloud.previewHeight ?: 0) },
        )
    }

    /** One version change to push and the photo it names. */
    private data class VersionChange(val change: PushChange, val photo: PhotoEntity?)

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private suspend fun versionChange(record: SyncRecordEntity, maxData: Int): VersionChange? {
        // A held version (rev -1) that changed again is still new to the server.
        val baseRev = record.rev.coerceAtLeast(0)
        if (record.deleted) {
            return VersionChange(
                PushChange(Kinds.VERSION, record.recordId, record.rev, record.modifiedAt, deleted = true),
                null,
            )
        }
        val version = sync.versionByUuid(record.recordId) ?: return null
        val photo = photos.get(version.photoId) ?: return null
        val recipe = edits.recipe(version.id)
        val steps = edits.history(version.id)
        val snapshots = db.versionDao().snapshots(version.id)
        // A version the user never edited needs nothing on the server: unedited, or only the lens profile default the
        // editor adds when it opens a new photo (ADR 0015 "New photos"). The other devices make their own; pushing it
        // made a second version or a conflict copy next to another device's real edit (FIX/W). A virtual copy goes
        // even unedited (docs/adr/0025): the user made it, the other devices show it.
        if (version.copyOf == null && version.position == 0 && snapshots.isEmpty() && isAutomatic(recipe, steps)) {
            return null
        }
        val cursor = edits.cursor(version.id) ?: (steps.size - 1)
        val photoFingerprint = mainFile(photos.files(photo.id))?.fingerprint
        var history = steps.map { StepData(parse(it.recipeJson), parse(it.labelsJson)) }
        var offset = 0
        var data: JsonElement
        while (true) {
            data = json.encodeToJsonElement(
                VersionData.serializer(),
                VersionData(
                    photoId = photo.uuid,
                    position = version.position,
                    name = version.name,
                    recipe = recipe?.let { parse(it.json) } ?: parse(RecipeCodec.Default.encode(DEFAULT_RECIPE)),
                    history = history,
                    cursor = (cursor - offset).coerceAtLeast(-1),
                    snapshots = snapshots.map { SnapshotData(it.uuid, it.name, parse(it.recipeJson), it.createdAt) },
                    photoFingerprint = photoFingerprint,
                ),
            )
            // A record is at most 2 MiB (ADR 0017): the oldest steps go first; undo then stops at the oldest kept one.
            if (history.isEmpty() || data.toString().length <= maxData) break
            val drop = (history.size / DROP_FRACTION).coerceAtLeast(1)
            history = history.drop(drop)
            offset += drop
        }
        return VersionChange(PushChange(Kinds.VERSION, version.uuid, baseRev, record.modifiedAt, data = data), photo)
    }

    private suspend fun settingChanges(): List<Outgoing> = settings.mapNotNull { setting ->
        val value = setting.read()
        val text = value.toString()
        val stored = meta.get(settingKey(setting.key))?.let {
            runCatching { json.decodeFromString(SettingState.serializer(), it) }.getOrNull()
        }
        if (stored != null && stored.value == text) return@mapNotNull null
        val record = sync.record(Kinds.SETTING, setting.key)
        Outgoing(PushChange(Kinds.SETTING, setting.key, record?.rev ?: 0, clock(), data = value), STAMP_SETTING)
    }

    /** Records the server's answer to one pushed change. */
    suspend fun pushed(outgoing: Outgoing, result: PushResult) = db.withTransaction {
        val change = outgoing.change
        if (change.kind == Kinds.SETTING && !ExportPresetSync.owns(change.id)) {
            sync.putRecord(
                SyncRecordEntity(Kinds.SETTING, change.id, result.rev, dirty = false, modifiedAt = change.modifiedAt),
            )
            if (result.status == STATUS_APPLIED || result.winner == WINNER_INCOMING) {
                meta.put(
                    MetaEntity(
                        settingKey(change.id),
                        json.encodeToString(SettingState.serializer(), SettingState(change.data.toString())),
                    ),
                )
            }
            return@withTransaction
        }
        if (change.kind == Kinds.VERSION) unhold(change.id)
        if (result.status == STATUS_APPLIED || result.winner == WINNER_INCOMING) {
            sync.pushed(change.kind, change.id, result.rev, outgoing.stamp)
            if (change.kind == Kinds.PHOTO && !change.deleted) releaseHeld(change.id)
        } else {
            // The server's state won: stay at our old base so the pull that follows (or the inbox, if it was pulled
            // already) applies it; for a version our losing state comes back as a copy.
            sync.pushed(change.kind, change.id, change.baseRev, outgoing.stamp)
        }
    }

    // ------------------------------------------------------------------ pull

    /**
     * Applies one page of the change feed, in order, in one transaction. [deviceName] names a conflict copy's device.
     */
    suspend fun apply(records: List<FeedRecord>, deviceName: (String) -> String?): ApplyStats {
        val stats = ApplyStats()
        // Small transactions with a yield between them: a 10,000-record first pull never holds the database for
        // long, so the library's queries (grid, counts, thumbnails) keep answering while it lands (ADR 0018).
        for (chunk in records.chunked(APPLY_CHUNK)) {
            quietly {
                for (record in chunk) applyOne(record, deviceName, stats)
            }
            yield()
        }
        announce(stats)
        return stats
    }

    /** Tells open editors about the versions this pull changed (after the transaction, so they read the new state). */
    private fun announce(stats: ApplyStats) {
        if (stats.versions.isNotEmpty()) RemoteEdits.announce(stats.versions)
    }

    /** Tries the parked records again (after their photo arrived). */
    suspend fun retryInbox(deviceName: (String) -> String?): ApplyStats {
        val stats = ApplyStats()
        val parked = sync.inbox()
        if (parked.isEmpty()) return stats
        quietly {
            for (entry in parked) {
                val record = runCatching { json.decodeFromString(FeedRecord.serializer(), entry.json) }.getOrNull()
                if (record == null) {
                    sync.unpark(entry.kind, entry.recordId, entry.rev)
                    continue
                }
                val before = stats.parked
                applyOne(record, deviceName, stats, fromInbox = true)
                if (stats.parked == before) sync.unpark(entry.kind, entry.recordId, entry.rev)
            }
        }
        announce(stats)
        return stats
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun applyOne(
        record: FeedRecord,
        deviceName: (String) -> String?,
        stats: ApplyStats,
        fromInbox: Boolean = false,
    ) {
        reassert = false
        val local = sync.record(record.kind, record.id)
        val placed = when {
            // This installation's own edit, from before an unpair (ADR 0018 "W5 (f)"): settled, nothing to apply.
            sameEdit(local, record) -> {
                stats.skipped++
                return
            }

            // Unpushed local work: the next push carries the old baseRev and the server decides (ADR 0017); the
            // server's state waits in the inbox and applies after that push if it is still the newest. A held version
            // ([hold]) is unpushed work too.
            local != null && (local.dirty || (record.kind == Kinds.VERSION && isHeld(record.id))) -> false

            // Already applied, or older than what the push left (our state won). A live record whose photo or version
            // is gone from this device (its folder was removed before 0.5.3, a forgotten hand-off) is placed again:
            // it may be openable from the server, or merge when its file shows up (FIX/V).
            local != null && applied(local, record, fromInbox) -> {
                stats.skipped++
                return
            }

            record.kind == Kinds.PHOTO -> applyPhoto(record)

            record.kind == Kinds.VERSION -> applyVersion(record, deviceName, stats)

            record.kind == Kinds.SETTING && ExportPresetSync.owns(record.id) -> exportPresetSync.apply(record)

            record.kind == Kinds.SETTING -> applySetting(record)

            record.kind == Kinds.PRESET -> presetSync.apply(record)

            else -> true
        }
        if (!placed) {
            sync.park(
                SyncInboxEntity(
                    record.kind,
                    record.id,
                    record.rev,
                    json.encodeToString(FeedRecord.serializer(), record),
                ),
            )
            stats.parked++
            return
        }
        sync.putRecord(
            SyncRecordEntity(
                record.kind,
                record.id,
                record.rev,
                dirty = reassert,
                modifiedAt = if (reassert) clock() else record.modifiedAt,
                deleted = record.deleted,
            ),
        )
        reassert = false
        stats.applied++
    }

    /**
     * A version this device never pushed to this server (rev 0, dirty: a first sync with a server it was paired with
     * before, ADR 0018 "W5 (f)") whose edit is the server's (recipe, name, position, snapshot ids): not a conflict. The
     * local record takes the server's revision and the record is not applied (this device's history and author stay;
     * the server's record was this installation's own, under its previous device). It is pushed again on top of that
     * revision unless the server's record also names the photo by its current id and main-file fingerprint: a record
     * of 0.8 or older, or one naming the id the photo had before it adopted another device's, would otherwise never be
     * repaired (the W5 repair skips first syncs). True when [record] is settled that way.
     */
    private suspend fun sameEdit(local: SyncRecordEntity?, record: FeedRecord): Boolean {
        if (local == null || record.kind != Kinds.VERSION || record.deleted) return false
        if (!local.dirty || local.rev != 0L || local.deleted) return false
        val data = decode(record.data, VersionData.serializer()) ?: return false
        val version = sync.versionByUuid(record.id) ?: return false
        if (!holdsEdit(version, data)) return false
        val photo = photos.get(version.photoId)
        val fingerprint = photo?.let { mainFile(photos.files(it.id))?.fingerprint }
        val named = photo != null && data.photoId == photo.uuid && data.photoFingerprint == fingerprint
        sync.putRecord(local.copy(rev = record.rev, dirty = !named || fingerprint == null))
        return true
    }

    /** True when [version]'s edit (recipe, name, position, snapshot ids) is the one [data] describes. */
    private suspend fun holdsEdit(version: VersionEntity, data: VersionData): Boolean {
        val recipe = edits.recipe(version.id)?.json?.let(::parse) ?: parse(RecipeCodec.Default.encode(DEFAULT_RECIPE))
        val snapshots = db.versionDao().snapshots(version.id).map { it.uuid }.sorted()
        return recipe == data.recipe && version.name == data.name && version.position == data.position &&
            snapshots == data.snapshots.orEmpty().map { it.id }.sorted()
    }

    /** [record] is already applied (or older than what our push left) and its photo or version is still here. */
    private suspend fun applied(local: SyncRecordEntity, record: FeedRecord, fromInbox: Boolean): Boolean =
        (local.rev > record.rev || (local.rev == record.rev && !fromInbox)) && (record.deleted || present(record))

    /** True when the photo or version of [record] is on this device (other kinds always are). */
    private suspend fun present(record: FeedRecord): Boolean = when (record.kind) {
        Kinds.PHOTO -> sync.photoByUuid(record.id) != null || sync.alias(record.id) != null
        Kinds.VERSION -> sync.versionByUuid(record.id) != null
        else -> true
    }

    /** Set by [applyPhoto] when this device's preview must be named again: the applied record is pushed again. */
    private var reassert = false

    private suspend fun applySetting(record: FeedRecord): Boolean {
        val setting = settings.firstOrNull { it.key == record.id } ?: return true
        val value = record.data ?: return true
        if (record.deleted || value is JsonNull) return true
        runCatching { setting.write(value) }
        meta.put(
            MetaEntity(
                settingKey(record.id),
                json.encodeToString(SettingState.serializer(), SettingState(value.toString())),
            ),
        )
        return true
    }

    /** The local photo of a server photo id: by uuid, by alias, else by its main file's fingerprint. */
    private suspend fun localPhoto(uuid: String, fingerprint: String?): PhotoEntity? {
        sync.photoByUuid(uuid)?.let { return it }
        sync.alias(uuid)?.let { id -> photos.get(id)?.let { return it } }
        if (fingerprint == null) return null
        return photoByFingerprint(uuid, fingerprint)
    }

    /**
     * The local photo whose main file is [fingerprint], for a server photo id [uuid] this device does not know (a
     * photo record, or a version naming a photo whose record has not arrived, ADR 0018 "W5"). Several photos with
     * that main file (the same file in two folders): the oldest one that is not hidden. A photo the server never had
     * under its own id (no record, or rev 0) takes [uuid], so every device names it the same; its old id stays an alias
     * (records that named it, e.g. a version pushed before the adoption, still find it). Else [uuid] becomes an alias.
     */
    private suspend fun photoByFingerprint(uuid: String, fingerprint: String): PhotoEntity? {
        val candidates = sync.photosWithMainFile(fingerprint)
        if (candidates.isEmpty()) return null
        val photo = if (candidates.size == 1) {
            candidates.single()
        } else {
            (candidates.firstOrNull { !it.hidden } ?: candidates.first()).also {
                runCatching {
                    android.util.Log.i(
                        TAG,
                        "photo ${uuid.take(DEVICE_SHORT)}: ${candidates.size} local photos share its main file, " +
                            "matched photo ${it.id}",
                    )
                }
            }
        }
        val ours = sync.record(Kinds.PHOTO, photo.uuid)
        return if (ours == null || (ours.rev == 0L && !ours.deleted)) {
            // Never on the server under our id: adopt the server's, so both devices name it the same.
            sync.deleteRecord(Kinds.PHOTO, photo.uuid)
            sync.setPhotoUuid(photo.id, uuid)
            sync.deleteAlias(uuid)
            sync.putAlias(SyncAliasEntity(photo.uuid, photo.id))
            photo.copy(uuid = uuid)
        } else {
            sync.putAlias(SyncAliasEntity(uuid, photo.id))
            photo
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun applyPhoto(record: FeedRecord): Boolean {
        if (record.deleted) {
            val photo = sync.photoByUuid(record.id) ?: return true
            // Another server id still names this photo (another device merged a hand-off into its folder photo, ADR
            // 0011 "One photo across sources"): the photo lives on under that id.
            // Only an id the server holds a live record of: an alias kept from an adoption never was one.
            val survivor = sync.aliasesOf(photo.id).firstOrNull { alias ->
                sync.record(Kinds.PHOTO, alias)?.let { it.rev > 0 && !it.deleted } == true
            }
            if (survivor != null) {
                sync.deleteAlias(survivor)
                sync.setPhotoUuid(photo.id, survivor)
                return true
            }
            // Only photos that live on the server alone go away; a file on this device stays in its folder. A hand-off
            // the server adopted (`handoff_at`) was never deletable by the server: it stays (with its edits and
            // culling), only the server's copies are forgotten (its files then read as unavailable, like a hand-off
            // whose grant ended, until the user forgets it).
            if (photo.sourceId == serverSource().id && photo.handoffAt == null) {
                photos.deletePhotos(listOf(photo.id))
            } else {
                forgetServerCopies(photo.id)
            }
            return true
        }
        val data = decode(record.data, PhotoData.serializer()) ?: return true
        val photo = localPhoto(record.id, data.fingerprint) ?: createServerPhoto(record.id, data)
        if (cullingInRecords) {
            val flag = PickFlag.entries.firstOrNull { it.name == data.flag } ?: PickFlag.NONE
            sync.applyPhotoMetadata(photo.id, data.rating.coerceIn(0, MAX_RATING), flag, data.label, data.hidden)
        } else if (photo.hidden != data.hidden) {
            sync.applyPhotoHidden(photo.id, data.hidden)
        }
        val cloud = sync.cloud(photo.id) ?: PhotoCloudEntity(photo.id)
        val preview = data.preview
        // The record says what the server holds: no preview there means none (a device dropped it, ADR 0018 "Smart
        // previews"), unless this device made the one it names here; then the record is pushed again with it (another
        // device wrote the photo without having seen it).
        val own = cloud.previewSha != null && (cloud.previewFormat != null || cloud.previewFingerprint != null)
        if (preview == null && own) {
            reassert = true
        } else {
            sync.putCloud(
                cloud.copy(
                    previewSha = preview?.sha256,
                    previewWidth = preview?.width,
                    previewHeight = preview?.height,
                    previewFormat = cloud.previewFormat.takeIf { preview?.sha256 == cloud.previewSha },
                ),
            )
        }
        if (photo.sourceId == serverSource().id) refreshServerPhoto(photo, data)
        // The photo is on the server under this id now (adopted, or our own record seen again): its held versions go.
        releaseHeld(record.id)
        return true
    }

    /**
     * A new state of a photo only the server describes: its files, and another lookup in this run (another device may
     * just have backed up its original; it pushes the record again then, FIX/V).
     */
    private suspend fun refreshServerPhoto(photo: PhotoEntity, data: PhotoData) {
        updateServerFiles(photo, data)
        val orientation = data.orientation?.coerceIn(1, MAX_ORIENTATION)
        val width = data.width
        val height = data.height
        if (orientation != null && width != null && height != null) {
            val size = Triple(width, height, orientation)
            if (size != Triple(photo.width, photo.height, photo.orientation)) {
                sync.setPhotoSize(photo.id, width, height, orientation)
            }
        }
        sync.cloud(photo.id)?.takeIf { !it.originalOnServer && it.checkedAt > 0 }?.let {
            sync.putCloud(it.copy(checkedAt = 0))
        }
    }

    /**
     * The photo was deleted from the server (ADR 0018 "Deleting from the server"): its originals and preview went with
     * the record, so this device stops naming them (a preview of its own would otherwise be named again) and asks
     * again whether the server has the original (the folder's backup rule may send it again).
     */
    private suspend fun forgetServerCopies(photoId: Long) {
        sync.cloud(photoId)?.let { cloud ->
            sync.putCloud(
                cloud.copy(
                    originalOnServer = false,
                    originalSource = null,
                    previewSha = null,
                    previewWidth = null,
                    previewHeight = null,
                    previewFingerprint = null,
                    previewFormat = null,
                    checkedAt = 0,
                ),
            )
        }
        for (file in photos.files(photoId)) file.fingerprint?.let { sync.setOnServer(it, false) }
    }

    /** A photo only the server has: filed under the Server source with its files and a first (unedited) version. */
    private suspend fun createServerPhoto(uuid: String, data: PhotoData): PhotoEntity {
        val source = serverSource()
        val now = clock()
        val format = PhotoFormat.entries.firstOrNull { it.name == data.format } ?: formatOf(data.files.map { it.name })
        val entity = PhotoEntity(
            uuid = uuid,
            sourceId = source.id,
            folder = "",
            folderDocumentId = "",
            stemKey = uuid,
            name = data.name.ifBlank { data.files.firstOrNull()?.name.orEmpty() },
            format = format,
            hasRaw = data.files.any { it.role == FileRole.RAW.name },
            // No capture time: 0 = the grid's "No date" group until the header is read (W3/D), never "now".
            sortTime = data.captureTime ?: 0,
            captureTime = data.captureTime,
            width = data.width,
            height = data.height,
            orientation = data.orientation?.coerceIn(1, MAX_ORIENTATION) ?: 1,
            // Without a capture time or an orientation from the other device (0.5.3 and older sent none), the header
            // is read by range like a NAS photo's: a portrait shot would otherwise show stretched (FIX/W).
            metadataState = if (data.captureTime == null || data.orientation == null) {
                MetadataState.PENDING
            } else {
                MetadataState.READ
            },
            addedAt = now,
        )
        val id = photos.insertPhoto(entity)
        photos.insertVersion(VersionEntity(photoId = id, createdAt = now))
        updateServerFiles(entity.copy(id = id), data)
        return entity.copy(id = id)
    }

    private suspend fun updateServerFiles(photo: PhotoEntity, data: PhotoData) {
        val existing = photos.files(photo.id).associateBy { it.fingerprint }
        val add = data.files.filter { it.fingerprint !in existing }.map { file ->
            PhotoFileEntity(
                photoId = photo.id,
                sourceId = photo.sourceId,
                relativePath = "photo/${photo.uuid}/${file.fingerprint.takeLast(FP_SUFFIX)}/${file.name}",
                documentId = ServerTree.documentId(file.fingerprint, file.name),
                name = file.name,
                size = file.size,
                modified = 0,
                role = FileRole.entries.firstOrNull { it.name == file.role } ?: FileRole.OTHER,
                fingerprint = file.fingerprint,
            )
        }
        if (add.isNotEmpty()) photos.insertFiles(add)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun applyVersion(record: FeedRecord, deviceName: (String) -> String?, stats: ApplyStats): Boolean {
        val existing = sync.versionByUuid(record.id)
        if (record.deleted) {
            if (existing != null) deleteVersionKeepingOne(existing)
            return true
        }
        val data = decode(record.data, VersionData.serializer()) ?: return true
        val photo = sync.photoByUuid(data.photoId) ?: sync.alias(data.photoId)?.let { photos.get(it) }
            ?: existing?.let { photos.get(it.photoId) }
            ?: data.photoFingerprint?.let { fingerprint ->
                // Found by its main file: the server knows the photo under [data.photoId] (it took that id, or the id
                // is an alias of it now), so this device's held versions of it go up without waiting for its record.
                photoByFingerprint(data.photoId, fingerprint)?.also { releaseHeld(data.photoId) }
            }
            ?: return false
        val copyDevice = record.copyOf?.let { deviceName(record.deviceId) ?: record.deviceId.take(DEVICE_SHORT) }
        val target = existing ?: placeVersion(photo.id, record.id, data, record.copyOf)
        val updated = target.copy(
            name = data.name,
            copyOf = record.copyOf ?: target.copyOf,
            copyDevice = copyDevice ?: target.copyDevice,
            // Who made this state, and when (the conflict chooser, FIX/W).
            editDevice = deviceName(record.deviceId) ?: record.deviceId.take(DEVICE_SHORT),
            editedAt = record.modifiedAt.takeIf { it > 0 },
        )
        if (updated != target) sync.updateVersion(updated)
        writeEdit(target.id, data)
        stats.versions += target.id
        if (record.copyOf != null && existing == null) stats.copies++
        return true
    }

    /** Where a version new to this device goes: its own position when free, else adopting an untouched local one. */
    private suspend fun placeVersion(photoId: Long, uuid: String, data: VersionData, copyOf: String?): VersionEntity {
        val versions = sync.versionsOf(photoId)
        val occupant = versions.firstOrNull { it.position == data.position }
        if (occupant != null && copyOf == null && isUntouched(occupant)) {
            sync.deleteRecord(Kinds.VERSION, occupant.uuid)
            sync.setVersionUuid(occupant.id, uuid)
            return occupant.copy(uuid = uuid)
        }
        val position = if (occupant == null) data.position else (versions.maxOf { it.position } + 1)
        val entity =
            VersionEntity(uuid = uuid, photoId = photoId, position = position, name = data.name, createdAt = clock())
        val id = photos.insertVersion(entity)
        return entity.copy(id = id)
    }

    /**
     * A local version nobody edited and the server never saw: safe to become the incoming one. The lens profile default
     * the editor adds on opening a photo is not an edit (FIX/W): such a version adopts another device's edit too.
     */
    private suspend fun isUntouched(version: VersionEntity): Boolean {
        // Only a photo's first version is made automatically; a virtual copy is the user's (docs/adr/0025).
        if (version.copyOf != null || version.position != 0) return false
        if (db.versionDao().snapshots(version.id).isNotEmpty()) return false
        val record = sync.record(Kinds.VERSION, version.uuid)
        if (record != null && record.rev > 0) return false
        return isAutomatic(edits.recipe(version.id), edits.history(version.id))
    }

    /**
     * True when neither the committed recipe nor any history step holds a user edit: no steps, or only the automatic
     * lens profile default ([dev.hfps.reveal.pipeline.recipe.Recipe.isUnedited], whose `is_default` the recipe row
     * stores). An undo past a real edit keeps that edit's step, so it never reads as automatic.
     */
    private fun isAutomatic(recipe: RecipeEntity?, steps: List<HistoryEntity>): Boolean =
        (recipe == null || recipe.isDefault) && steps.all { step ->
            runCatching { RecipeCodec.Default.decode(step.recipeJson).isUnedited() }.getOrDefault(false)
        }

    private suspend fun writeEdit(versionId: Long, data: VersionData) {
        val now = clock()
        sync.clearHistory(versionId)
        if (data.history.isNotEmpty()) {
            sync.insertHistory(
                data.history.mapIndexed { index, step ->
                    HistoryEntity(
                        versionId = versionId,
                        position = index,
                        recipeJson = step.recipe.toString(),
                        labelsJson = step.label.toString(),
                        createdAt = now,
                    )
                },
            )
        }
        edits.setCursor(HistoryCursorEntity(versionId, data.cursor.coerceIn(-1, data.history.lastIndex)))
        val recipeJson = data.recipe.toString()
        val isDefault = runCatching { RecipeCodec.Default.decode(recipeJson).isUnedited() }.getOrDefault(false)
        val current = edits.recipe(versionId)
        if (current == null || current.json != recipeJson) {
            edits.putRecipe(RecipeEntity(versionId, recipeJson, isDefault = isDefault, updatedAt = now))
        }
        data.snapshots?.let { writeSnapshots(versionId, it) }
        val photoId = photos.photoIdOfVersion(versionId) ?: return
        photos.setHasEdit(photoId, edits.photoHasEdit(photoId))
    }

    /**
     * The version's snapshots become [snapshots] (docs/adr/0025): the record carries them all. A snapshot id another
     * version of this device already holds (a conflict copy of the same version) gets a new local uuid.
     */
    private suspend fun writeSnapshots(versionId: Long, snapshots: List<SnapshotData>) {
        val dao = db.versionDao()
        val current = dao.snapshots(versionId)
        val wanted = snapshots.map { Triple(it.id, it.name, it.recipe.toString()) }
        if (current.map { Triple(it.uuid, it.name, it.recipeJson) }.sortedBy { it.first } ==
            wanted.sortedBy { it.first }
        ) {
            return
        }
        dao.clearSnapshots(versionId)
        val rows = snapshots.map { snapshot ->
            val taken = dao.snapshotByUuid(snapshot.id) != null
            SnapshotEntity(
                uuid = if (taken) newUuid() else snapshot.id,
                versionId = versionId,
                name = snapshot.name,
                recipeJson = snapshot.recipe.toString(),
                createdAt = snapshot.createdAt.takeIf { it > 0 } ?: clock(),
            )
        }
        if (rows.isNotEmpty()) dao.insertSnapshots(rows)
    }

    /** A version deleted on another device; the last version of a photo is reset instead (a photo needs one). */
    private suspend fun deleteVersionKeepingOne(version: VersionEntity) {
        val versions = sync.versionsOf(version.photoId)
        if (versions.size <= 1) {
            writeEdit(version.id, VersionData(photoId = "", recipe = parse(RecipeCodec.Default.encode(DEFAULT_RECIPE))))
            return
        }
        sync.deleteVersion(version.id)
        if (version.position == 0) {
            // The grid shows a photo by its version at position 0: the next one moves up.
            val next = versions.first { it.id != version.id }
            sync.updateVersion(next.copy(position = 0))
        }
        photos.setHasEdit(version.photoId, edits.photoHasEdit(version.photoId))
    }

    /** Deletes a local version (a conflict copy the user removed) and queues its tombstone. */
    suspend fun deleteVersion(versionId: Long) = db.withTransaction {
        val version = db.syncDao().versionsOf(photos.photoIdOfVersion(versionId) ?: return@withTransaction)
            .firstOrNull { it.id == versionId } ?: return@withTransaction
        val record = sync.record(Kinds.VERSION, version.uuid)
        quietlyInTransaction { deleteVersionKeepingOne(version) }
        if (record != null && record.rev > 0) {
            sync.putRecord(record.copy(dirty = true, deleted = true, modifiedAt = clock()))
        } else {
            sync.deleteRecord(Kinds.VERSION, version.uuid)
        }
    }

    // ------------------------------------------------------------------ W5 repair

    /**
     * The one-off repair of ADR 0018 "W5" (after a pull, so every local revision is the server's latest): before 0.9.0
     * an edited version could go up naming a photo the server never heard of (a NAS photo made quietly from the feed, a
     * hand-off), and other devices parked it forever. Two cases are marked to push:
     * - an edited version whose photo has no accepted record: the photo and the version (the push sends the photo
     *   first);
     * - an edited version of this device already on the server whose photo the server knows: pushed again, with its
     *   original time and the photo's main-file fingerprint, so it names the photo's current id (the photo may have
     *   adopted another device's id after the version went up). A Server photo made from another device's record and
     *   conflict copies are left alone: their ids never changed. Only a version this device is known to have edited
     *   ([authoredHere]) goes at once; one whose author is unknown (an edit older than 0.5.4 that may be another
     *   device's, pulled) is only listed, and [checkRepairs] pushes it on the next run if no pull changed it meanwhile.
     * Returns how many versions it marked.
     */
    suspend fun repairVersionPhotos(): Int = db.withTransaction {
        val now = clock()
        var marked = 0
        val checks = ArrayList<String>()
        for (row in sync.editedVersions()) {
            val photo = sync.record(Kinds.PHOTO, row.photoUuid)
            val version = sync.record(Kinds.VERSION, row.versionUuid)
            val repaired = when {
                photo?.deleted == true || version?.deleted == true -> false
                photo == null || photo.rev <= 0 -> markWithPhoto(row, photo, version, now)
                version == null || !isRepushed(row, version) -> false
                authoredHere(row, version) -> true.also { sync.putRecord(version.copy(dirty = true)) }
                else -> false.also { checks += "${row.versionUuid}$CHECK_REV${version.rev}" }
            }
            if (repaired) marked++
        }
        if (checks.isEmpty()) {
            meta.delete(
                KEY_REPAIR_CHECKS,
            )
        } else {
            meta.put(MetaEntity(KEY_REPAIR_CHECKS, checks.joinToString(",")))
        }
        marked
    }

    /**
     * The versions [repairVersionPhotos] left to check (author unknown), after this run's pull: each one still at the
     * revision it had then (no device changed it for a whole sync interval) and with nothing local to push is marked to
     * go up again; one another device changed meanwhile is left alone (its new record names the photo itself). Returns
     * how many it marked; the list is gone afterwards.
     */
    suspend fun checkRepairs(): Int = db.withTransaction {
        val list = meta.get(KEY_REPAIR_CHECKS) ?: return@withTransaction 0
        var marked = 0
        for (entry in list.split(',')) {
            val rev = entry.substringAfter(CHECK_REV, "").toLongOrNull()
            val record = sync.record(Kinds.VERSION, entry.substringBefore(CHECK_REV))
            if (record != null && record.rev == rev && record.isSettled) {
                sync.putRecord(record.copy(dirty = true))
                marked++
            }
        }
        meta.delete(KEY_REPAIR_CHECKS)
        marked
    }

    /** Nothing local to push and not a tombstone. */
    private val SyncRecordEntity.isSettled: Boolean
        get() = !dirty && !deleted

    /**
     * True when this device made [row]'s current edit: `edit_device` says so (0.5.4+), or it is unknown (an edit older
     * than 0.5.4) and the history holds steps committed here. A pulled record writes its whole history at once (every
     * step one time, [writeEdit]); steps made in the editor were committed one by one, at different times.
     */
    private suspend fun authoredHere(row: EditedVersion, record: SyncRecordEntity): Boolean {
        if (row.editDevice == EditRepository.THIS_DEVICE) return true
        if (row.editDevice != null) return false
        val version = sync.versionByUuid(record.recordId) ?: return false
        return edits.history(version.id).map { it.createdAt }.distinct().size > 1
    }

    /** Marks a version and its photo that the server never accepted ([repairVersionPhotos]). */
    private suspend fun markWithPhoto(
        row: EditedVersion,
        photo: SyncRecordEntity?,
        version: SyncRecordEntity?,
        now: Long,
    ): Boolean {
        when {
            photo == null -> sync.putRecord(SyncRecordEntity(Kinds.PHOTO, row.photoUuid, 0, modifiedAt = now))
            !photo.dirty -> sync.putRecord(photo.copy(dirty = true))
        }
        when {
            version == null ->
                sync.putRecord(SyncRecordEntity(Kinds.VERSION, row.versionUuid, 0, modifiedAt = row.editedAt ?: now))

            !version.dirty -> sync.putRecord(version.copy(dirty = true, rev = version.rev.coerceAtLeast(0)))
        }
        return true
    }

    /** This device's own clean edit on the server, of a photo whose id may have changed ([repairVersionPhotos]). */
    private fun isRepushed(row: EditedVersion, version: SyncRecordEntity): Boolean =
        // Its own edit or one of unknown author ([authoredHere] tells them apart); never another device's.
        version.rev > 0 && !version.dirty && row.copyOf == null && row.editDevice.isNullOrEmpty() && !row.fromRecord

    // ------------------------------------------------------------------ conflict chooser

    /**
     * The two sides of a conflict (ADR 0018 amendment FIX/W): [versionId] is a conflict copy or the version it copies
     * (then its first copy). The main side is the version the copy names (`copy_of`), else the photo's first one.
     */
    suspend fun conflict(versionId: Long): ConflictPair? = db.withTransaction {
        val photoId = photos.photoIdOfVersion(versionId) ?: return@withTransaction null
        val versions = sync.versionsOf(photoId)
        val picked = versions.firstOrNull { it.id == versionId } ?: return@withTransaction null
        val copy = if (picked.copyOf != null) picked else versions.firstOrNull { it.copyOf == picked.uuid }
        copy ?: return@withTransaction null
        val main = versions.firstOrNull { it.uuid == copy.copyOf && it.copyOf == null }
            ?: versions.firstOrNull { it.copyOf == null } ?: return@withTransaction null
        ConflictPair(photoId, side(main, copy = false), side(copy, copy = true))
    }

    /** The conflict of [photoId]'s first conflict copy, or null when it has none. */
    suspend fun conflictOfPhoto(photoId: Long): ConflictPair? {
        val copy = sync.versionsOf(photoId).firstOrNull { it.copyOf != null } ?: return null
        return conflict(copy.id)
    }

    private suspend fun side(version: VersionEntity, copy: Boolean): ConflictSide {
        val recipe = edits.recipe(version.id)
        return ConflictSide(
            versionId = version.id,
            device = version.editDevice ?: version.copyDevice.takeIf { copy },
            editedAt = version.editedAt ?: recipe?.updatedAt,
            editTime = recipe?.takeIf { !it.isDefault }?.updatedAt,
            copy = copy,
        )
    }

    /**
     * "Keep this one" in the conflict chooser: keeping the main version deletes the copy; keeping the copy makes its
     * edit (recipe, history, cursor) the main version's, as a change of this device, then deletes the copy. Either way
     * the copy's tombstone and the main version's new state go to the other devices with the next sync.
     */
    suspend fun keep(pair: ConflictPair, keepCopy: Boolean) {
        db.withTransaction {
            if (keepCopy) {
                val from = pair.copy.versionId
                val into = pair.main.versionId
                val steps = edits.history(from)
                sync.clearHistory(into)
                if (steps.isNotEmpty()) {
                    sync.insertHistory(steps.map { it.copy(id = 0, versionId = into, createdAt = clock()) })
                }
                val cursor = edits.cursor(from) ?: (steps.size - 1)
                edits.setCursor(HistoryCursorEntity(into, cursor.coerceIn(-1, steps.lastIndex)))
                val recipe = edits.recipe(from)
                val now = clock()
                if (recipe != null) {
                    edits.putRecipe(recipe.copy(versionId = into, updatedAt = now))
                } else {
                    edits.putRecipe(
                        RecipeEntity(
                            into,
                            RecipeCodec.Default.encode(DEFAULT_RECIPE),
                            isDefault = true,
                            updatedAt = now,
                        ),
                    )
                }
                // The kept side's snapshots come along (docs/adr/0025).
                db.versionDao().clearSnapshots(into)
                db.versionDao().moveSnapshots(from, into)
                edits.markEdited(into, EditRepository.THIS_DEVICE, now)
                photos.setHasEdit(pair.photoId, edits.photoHasEdit(pair.photoId))
            }
            deleteVersion(pair.copy.versionId)
        }
        RemoteEdits.announce(setOf(pair.main.versionId))
    }

    // ------------------------------------------------------------------ NAS files and lookups

    /** Files the server indexes on its NAS (ADR 0017 "NAS folders"): the Server source's photos. */
    suspend fun applyNas(files: List<NasFile>) {
        val source = quietly { serverSource() }
        for (chunk in files.chunked(APPLY_CHUNK)) {
            quietly { for (file in chunk) applyNasFile(source, file) }
            yield()
        }
        quietly { sync.deleteEmptyServerPhotos(source.id) }
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private suspend fun applyNasFile(source: SourceEntity, file: NasFile) {
        val path = "nas/${file.folderId}/${file.path}"
        val known = sync.serverFileByPath(source.id, path)
        if (file.removed || file.fingerprint == null) {
            if (known != null) photos.deleteFiles(listOf(known.id))
            return
        }
        val fingerprint = file.fingerprint
        if (known != null && known.fingerprint == fingerprint) return
        if (known != null) photos.deleteFiles(listOf(known.id))
        val name = file.path.substringAfterLast('/')
        val type = ImageFormats.classify(name)
        if (!type.isDisplayable) return
        // The same file on this device (a folder or a hand-off): it is "in the cloud", nothing to add.
        val local = photos.filesByFingerprint(listOf(fingerprint))
        if (keepAdopted(source, fingerprint, local)) return
        val elsewhere = local.firstOrNull { it.file.sourceId != source.id }
        if (elsewhere != null) {
            sync.setOnServer(fingerprint, true)
            if (isMainFile(elsewhere.file)) markOriginal(elsewhere.file.photoId, SOURCE_NAS)
            return
        }
        val dir = file.path.substringBeforeLast('/', "")
        val folder = listOf(file.folderName, dir).filter { it.isNotEmpty() }.joinToString("/")
        val stem = ImageFormats.stem(name).lowercase()
        val role = if (type.isRaw) FileRole.RAW else FileRole.RENDERED
        val sameFile = local.firstOrNull()?.file
        val photoId = when {
            // A photo record arrived first (another device's photo): it is this NAS file.
            sameFile != null -> sameFile.photoId.also { photos.deleteFiles(listOf(sameFile.id)) }

            else -> photos.byStem(source.id, folder, stem)?.id
                ?: newNasPhoto(source, folder, stem, name, type.isRaw, file)
        }
        photos.insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = photoId, sourceId = source.id, relativePath = path,
                    documentId = ServerTree.documentId(fingerprint, name), name = name,
                    size = file.size, modified = file.modifiedAt, role = role, fingerprint = fingerprint,
                    onServer = true,
                ),
            ),
        )
        val shot = photos.files(photoId)
        val raw = shot.firstOrNull { it.role == FileRole.RAW }
        val photo = photos.get(photoId) ?: return
        val format = formatOf(shot.map { it.name })
        if (photo.format != format || photo.hasRaw != (raw != null) || (raw != null && photo.name != raw.name) ||
            photo.folder != folder
        ) {
            photos.updateShot(photoId, raw?.name ?: photo.name, format, raw != null, "")
            sync.setServerFolder(photoId, folder)
        }
        markOriginal(photoId, SOURCE_NAS)
    }

    /**
     * A hand-off the server adopted keeps its own file row (and its photo): the feed never replaces it, so a later
     * rename or removal on the NAS cannot empty the photo (adoption must not make a hand-off deletable).
     */
    private suspend fun keepAdopted(source: SourceEntity, fingerprint: String, local: List<FingerprintMatch>): Boolean {
        val adopted = local.firstOrNull {
            it.file.sourceId == source.id && it.file.relativePath.startsWith(ADOPTED_PREFIX)
        } ?: return false
        sync.setOnServer(fingerprint, true)
        if (isMainFile(adopted.file)) markOriginal(adopted.file.photoId, SOURCE_NAS)
        return true
    }

    private suspend fun newNasPhoto(
        source: SourceEntity,
        folder: String,
        stem: String,
        name: String,
        raw: Boolean,
        file: NasFile,
    ): Long {
        val now = clock()
        val id = photos.insertPhoto(
            PhotoEntity(
                sourceId = source.id, folder = folder, folderDocumentId = "", stemKey = stem, name = name,
                format = formatOf(
                    listOf(name),
                ),
                // The capture time comes from the header, read by range after the feed (`readServerHeaders`).
                hasRaw = raw, sortTime = file.modifiedAt, metadataState = MetadataState.PENDING,
                addedAt = now,
            ),
        )
        photos.insertVersion(VersionEntity(photoId = id, createdAt = now))
        return id
    }

    private suspend fun markOriginal(photoId: Long, source: String) {
        val cloud = sync.cloud(photoId) ?: PhotoCloudEntity(photoId)
        if (!cloud.originalOnServer || cloud.originalSource != source) {
            sync.putCloud(cloud.copy(originalOnServer = true, originalSource = source, checkedAt = clock()))
        }
    }

    /** True when [file] is its photo's main file (the RAW of a pair, else the rendering). */
    private suspend fun isMainFile(file: PhotoFileEntity): Boolean = mainFile(photos.files(file.photoId))?.id == file.id

    /** The per-file answer of a fingerprint lookup (FIX/V): which of [asked] the server can serve. */
    suspend fun filesLookedUp(asked: Collection<String>, found: Set<String>) = db.withTransaction {
        for (fingerprint in asked) sync.setOnServer(fingerprint, fingerprint in found)
    }

    /**
     * An original this device uploaded is on the server now: the lookups say so, and the photo record is pushed again
     * so other devices ask about it at once (their Server photo becomes openable) instead of a day later.
     */
    suspend fun originalLanded(photoId: Long, fingerprint: String) = db.withTransaction {
        lookedUp(photoId, SOURCE_BACKUP)
        sync.setOnServer(fingerprint, true)
        val uuid = photos.get(photoId)?.uuid ?: return@withTransaction
        val record = sync.record(Kinds.PHOTO, uuid) ?: return@withTransaction
        // A later modifiedAt than the pushed one, so the server takes it as a new state (an identical repeat is not).
        val at = maxOf(clock(), record.modifiedAt + 1)
        if (!record.dirty && !record.deleted) sync.putRecord(record.copy(dirty = true, modifiedAt = at))
    }

    /** The answer of a fingerprint lookup for [photoId]: found (`nas`/`backup`) or missing. */
    suspend fun lookedUp(photoId: Long, source: String?) {
        val cloud = sync.cloud(photoId) ?: PhotoCloudEntity(photoId)
        sync.putCloud(cloud.copy(originalOnServer = source != null, originalSource = source, checkedAt = clock()))
    }

    // ------------------------------------------------------------------ hand-offs the server holds

    /** What one adoption batch did: photos moved, and photos that failed (marked, skipped until [ADOPT_RETRY_MS]). */
    data class Adopted(val moved: Int, val failed: Int)

    /**
     * Makes [photoIds] (hand-off photos whose main file the server holds) Server photos, in place (ADR 0018 "W3/C"):
     * the same row keeps its uuid, versions, edits, history, culling and `handoff_at` (it stays in "Opened from other
     * apps"); the files **the server holds** (`on_server`, with a fingerprint) take the server's document ids, so the
     * grid, the viewer, the editor and the export read the server's copy and never open the sending app's URI again;
     * every other file stays as it is (a JPEG of a pair the server does not hold is still read from the sender). The
     * sidecar link goes (culling rides the server, docs/adr/0011 "Amendment W3/H"). Quiet: nothing changes for other
     * devices.
     *
     * One photo per transaction: a photo that fails (a unique path clash is made unique first; anything else is
     * logged) is marked ([ADOPT_FAILED_PREFIX]) and skipped until [ADOPT_RETRY_MS] have passed, so it never blocks
     * the others, the batch or the sync. Reusable for photos handed over by server reference.
     */
    suspend fun adoptBatch(photoIds: List<Long>): Adopted {
        var moved = 0
        var failed = 0
        for (photoId in photoIds) {
            try {
                if (quietly { adoptOne(photoId) }) moved++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                failed++
                android.util.Log.w("RevealSync", "adopting hand-off photo $photoId failed: ${e.javaClass.simpleName}")
                runCatching { db.metaDao().put(MetaEntity(ADOPT_FAILED_PREFIX + photoId, clock().toString())) }
            }
        }
        return Adopted(moved, failed)
    }

    suspend fun adoptHandoffs(photoIds: List<Long>): Int = adoptBatch(photoIds).moved

    private suspend fun adoptOne(photoId: Long): Boolean {
        val source = serverSource()
        val keep = photos.files(photoId).filter {
            it.onServer && it.fingerprint != null && it.role != FileRole.OTHER && it.sourceId != source.id
        }
        if (keep.isEmpty()) return false
        for (file in keep) {
            val print = checkNotNull(file.fingerprint)
            var path = "$ADOPTED_PREFIX$print/${file.name}"
            // The same file under the same name in two hand-offs (backfilled fingerprints): the unique path.
            if (sync.serverFileByPath(source.id, path) != null) path = "$ADOPTED_PREFIX$print/${file.id}/${file.name}"
            sync.adoptFile(file.id, source.id, path, ServerTree.documentId(print, file.name))
        }
        sync.adoptPhoto(photoId, source.id)
        markOriginal(photoId, sync.cloud(photoId)?.originalSource ?: SOURCE_NAS)
        return true
    }

    /** One batch of [adoptBatch] over what is adoptable now (`moved + failed` = 0: none left to try). */
    suspend fun adoptNext(limit: Int = ADOPT_BATCH): Adopted =
        adoptBatch(sync.adoptableHandoffs(limit, clock() - ADOPT_RETRY_MS))

    /** One batch of [adoptHandoffs] over what is adoptable now; returns how many moved (0 = none left). */
    suspend fun adoptHandoffs(limit: Int = ADOPT_BATCH): Int = adoptNext(limit).moved

    // ------------------------------------------------------------------ the Server source

    /** The single Server source (created on first use). */
    suspend fun serverSource(): SourceEntity = sync.serverSource() ?: run {
        val entity = SourceEntity(
            treeUri = SERVER_TREE,
            rootDocumentId = "",
            displayName = "",
            volumeId = null,
            volumeName = null,
            state = SourceState.AVAILABLE,
            writable = false,
            addedAt = clock(),
            kind = SourceKind.SERVER,
            backup = false,
        )
        entity.copy(id = db.sourceDao().insert(entity))
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun <T> quietly(block: suspend () -> T): T = db.withTransaction { quietlyInTransaction(block) }

    private suspend fun <T> quietlyInTransaction(block: suspend () -> T): T {
        val already = meta.get(SyncTriggers.APPLYING) != null
        if (!already) meta.put(MetaEntity(SyncTriggers.APPLYING, "1"))
        return try {
            block()
        } finally {
            if (!already) meta.delete(SyncTriggers.APPLYING)
        }
    }

    private fun parse(text: String): JsonElement = runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonNull)

    private fun <T> decode(element: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        element?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    @kotlinx.serialization.Serializable
    private data class SettingState(val value: String)

    companion object {
        private const val TAG = "RevealSync"
        const val STATUS_APPLIED = "applied"
        const val WINNER_INCOMING = "incoming"
        const val SOURCE_NAS = "nas"
        const val SOURCE_BACKUP = "backup"

        /** Records applied per transaction (a few milliseconds each on a phone). */
        const val APPLY_CHUNK = 50

        /** Hand-off photos looked at per batch ([adoptBatch], one transaction each). */
        const val ADOPT_BATCH = 100

        /** A hand-off whose adoption failed is tried again after this long. */
        const val ADOPT_RETRY_MS = 6 * 60 * 60 * 1000L

        /** Relative path prefix of adopted hand-off files (unique next to the NAS feed's `nas/<folder>/<path>`). */
        const val ADOPTED_PREFIX = "handoff/"

        /** `sources.tree_uri` of the Server source (unique, never a real URI). */
        const val SERVER_TREE = dev.hfps.reveal.data.sync.ServerTree.SOURCE_TREE
        private const val MAX_RATING = 5
        private const val MAX_ORIENTATION = 8
        private const val FP_SUFFIX = 12
        private const val DEVICE_SHORT = 8
        private const val DROP_FRACTION = 4
        private const val STAMP_SETTING = -1L
        private val DEFAULT_RECIPE = dev.hfps.reveal.pipeline.recipe.Recipe()

        private fun settingKey(key: String) = "sync.setting.$key"

        /** `meta`: versions held back until their photo is on the server ([hold]): version uuids, `,` joined. */
        const val KEY_HELD_VERSIONS = "sync.heldVersions"

        /** `meta`: versions the W5 repair left to check on the next run ([checkRepairs]): `uuid@rev`, `,` joined. */
        const val KEY_REPAIR_CHECKS = "sync.versionPhotos.checks"
        private const val CHECK_REV = "@"

        fun mainFile(files: List<PhotoFileEntity>): PhotoFileEntity? =
            files.firstOrNull { it.role == FileRole.RAW } ?: files.firstOrNull { it.role == FileRole.RENDERED }

        /** The grid's format badge of a shot's file names. */
        fun formatOf(names: List<String>): PhotoFormat {
            val types = names.map { ImageFormats.classify(it) }
            val raw = types.any { it.isRaw }
            val rendered = types.firstOrNull { !it.isRaw && it.isDisplayable }
            return when {
                raw && rendered != null -> PhotoFormat.RAW_PAIR
                raw -> PhotoFormat.RAW
                rendered?.kind == ImageKind.HEIF -> PhotoFormat.HEIF
                rendered?.kind == ImageKind.PNG -> PhotoFormat.PNG
                rendered?.kind == ImageKind.WEBP -> PhotoFormat.WEBP
                else -> PhotoFormat.JPEG
            }
        }
    }
}
