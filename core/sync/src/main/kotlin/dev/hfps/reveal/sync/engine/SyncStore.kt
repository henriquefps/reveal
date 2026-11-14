@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.engine

import androidx.room.withTransaction
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ImageKind
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
        val out = ArrayList<Outgoing>()
        val records = sync.dirty((limit - settings.size).coerceAtLeast(1))
        for (record in records) {
            val change = when (record.kind) {
                Kinds.PHOTO -> photoChange(record)
                Kinds.VERSION -> versionChange(record, maxData)
                Kinds.PRESET -> presetSync.change(record)
                Kinds.SETTING -> exportPresetSync.change(record)
                else -> null
            }
            if (change == null) {
                sync.clean(record.kind, record.recordId, record.modifiedAt)
            } else {
                out += Outgoing(change, record.modifiedAt)
            }
        }
        out += settingChanges()
        return OutgoingBatch(out, read = records.size)
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

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private suspend fun versionChange(record: SyncRecordEntity, maxData: Int): PushChange? {
        if (record.deleted) {
            return PushChange(Kinds.VERSION, record.recordId, record.rev, record.modifiedAt, deleted = true)
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
                ),
            )
            // A record is at most 2 MiB (ADR 0017): the oldest steps go first; undo then stops at the oldest kept one.
            if (history.isEmpty() || data.toString().length <= maxData) break
            val drop = (history.size / DROP_FRACTION).coerceAtLeast(1)
            history = history.drop(drop)
            offset += drop
        }
        return PushChange(Kinds.VERSION, version.uuid, record.rev, record.modifiedAt, data = data)
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
        if (result.status == STATUS_APPLIED || result.winner == WINNER_INCOMING) {
            sync.pushed(change.kind, change.id, result.rev, outgoing.stamp)
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

    private suspend fun applyOne(
        record: FeedRecord,
        deviceName: (String) -> String?,
        stats: ApplyStats,
        fromInbox: Boolean = false,
    ) {
        reassert = false
        val local = sync.record(record.kind, record.id)
        val placed = when {
            // Unpushed local work: the next push carries the old baseRev and the server decides (ADR 0017); the
            // server's state waits in the inbox and applies after that push if it is still the newest.
            local != null && local.dirty -> false

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
        val file = sync.mainFilesByFingerprint(listOf(fingerprint)).firstOrNull() ?: return null
        val photo = photos.get(file.photoId) ?: return null
        val ours = sync.record(Kinds.PHOTO, photo.uuid)
        return if (ours == null || ours.rev == 0L) {
            // Never on the server under our id: adopt the server's, so both devices name it the same.
            sync.deleteRecord(Kinds.PHOTO, photo.uuid)
            sync.setPhotoUuid(photo.id, uuid)
            photo.copy(uuid = uuid)
        } else {
            sync.putAlias(SyncAliasEntity(uuid, photo.id))
            photo
        }
    }

    private suspend fun applyPhoto(record: FeedRecord): Boolean {
        if (record.deleted) {
            val photo = sync.photoByUuid(record.id) ?: return true
            // Another server id still names this photo (another device merged a hand-off into its folder photo, ADR
            // 0011 "One photo across sources"): the photo lives on under that id.
            val survivor = sync.aliasesOf(photo.id).firstOrNull()
            if (survivor != null) {
                sync.deleteAlias(survivor)
                sync.setPhotoUuid(photo.id, survivor)
                return true
            }
            // Only photos that live on the server alone go away; a file on this device stays in its folder.
            if (photo.sourceId == serverSource().id) {
                photos.deletePhotos(listOf(photo.id))
            } else {
                forgetServerCopies(photo.id)
            }
            return true
        }
        val data = decode(record.data, PhotoData.serializer()) ?: return true
        val photo = localPhoto(record.id, data.fingerprint) ?: createServerPhoto(record.id, data)
        val flag = PickFlag.entries.firstOrNull { it.name == data.flag } ?: PickFlag.NONE
        sync.applyPhotoMetadata(photo.id, data.rating.coerceIn(0, MAX_RATING), flag, data.label, data.hidden)
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
            sortTime = data.captureTime ?: now,
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
        val photo = sync.photoByUuid(data.photoId) ?: sync.alias(data.photoId)?.let { photos.get(it) } ?: return false
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
        const val STATUS_APPLIED = "applied"
        const val WINNER_INCOMING = "incoming"
        const val SOURCE_NAS = "nas"
        const val SOURCE_BACKUP = "backup"

        /** Records applied per transaction (a few milliseconds each on a phone). */
        const val APPLY_CHUNK = 50

        /** `sources.tree_uri` of the Server source (unique, never a real URI). */
        const val SERVER_TREE = "reveal-sync:server"
        private const val MAX_RATING = 5
        private const val MAX_ORIENTATION = 8
        private const val FP_SUFFIX = 12
        private const val DEVICE_SHORT = 8
        private const val DROP_FRACTION = 4
        private const val STAMP_SETTING = -1L
        private val DEFAULT_RECIPE = dev.hfps.reveal.pipeline.recipe.Recipe()

        private fun settingKey(key: String) = "sync.setting.$key"

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
