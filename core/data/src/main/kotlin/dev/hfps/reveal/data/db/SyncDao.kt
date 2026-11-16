package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.hfps.reveal.data.model.PickFlag
import kotlinx.coroutines.flow.Flow

/** `meta`: the id of the server this device syncs with (set by the first sync, gone after unpairing). */
const val SYNC_TRACKING_KEY = "sync.tracking"

/** `meta` key prefix (+ photo id, value = time) of a Server photo the item lookup missed: asked again later. */
const val HEADER_MISS_PREFIX = "sync.headerMiss."

/** `meta` key prefix (+ photo id, value = time) of a hand-off whose adoption failed: skipped until it is retried. */
const val ADOPT_FAILED_PREFIX = "sync.adoptFailed."

/** A file's fingerprint and whether the server holds it (or its photo's original). */
data class PrintedFile(val fingerprint: String, @ColumnInfo(name = "on_server") val onServer: Boolean)

/** A photo with its main file, for the cloud work (lookups, previews, backups). */
data class CloudCandidate(
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    val name: String,
    val size: Long,
    val fingerprint: String,
    val backup: Boolean,
)

/** A smart preview this device made for a photo that no longer needs one. */
data class DroppablePreview(@ColumnInfo(name = "photo_id") val photoId: Long, val sha256: String)

/**
 * A version `v` a user edited: a recipe that is not the default, a name, a virtual copy, a conflict copy or snapshots
 * (the W5 repair, and what unpairing keeps of the Server source).
 */
internal const val EDITED_VERSION =
    "EXISTS (SELECT 1 FROM recipes r WHERE r.version_id = v.id AND r.is_default = 0) " +
        "OR v.name IS NOT NULL OR v.position > 0 OR v.copy_of IS NOT NULL " +
        "OR EXISTS (SELECT 1 FROM snapshots n WHERE n.version_id = v.id)"

/**
 * The photos [SyncDao.previewCandidates] and [SyncDao.outdatedPreviews] start from: device-only (looked up, missing on
 * the server), visible, in a reachable folder or hand-off, main file printed, nothing queued, and whose original cannot
 * go to the server: Reveal cannot read it, or it is edited while the originals backup is off (`:originalsOff`).
 */
private const val PREVIEW_PHOTOS =
    "SELECT p.id AS photo_id, p.source_id, f.id AS file_id, f.document_id, f.name, f.size, f.fingerprint, " +
        "s.backup FROM photos p JOIN sources s ON s.id = p.source_id JOIN photo_files f ON f.photo_id = p.id " +
        "JOIN photo_cloud c ON c.photo_id = p.id WHERE s.kind != 'SERVER' AND s.state = 'AVAILABLE' " +
        "AND p.hidden = 0 AND c.original_on_server = 0 AND c.checked_at > 0 AND f.fingerprint IS NOT NULL " +
        "AND (f.role = 'RAW' OR NOT EXISTS (SELECT 1 FROM photo_files o WHERE o.photo_id = p.id AND " +
        "o.role = 'RAW')) AND (f.unreadable_at IS NOT NULL OR (p.has_edit = 1 AND :originalsOff)) " +
        "AND NOT EXISTS (SELECT 1 FROM sync_uploads u WHERE u.photo_id = p.id AND u.kind = 'preview' AND " +
        "u.state = 'pending') "

/** Counts for the Sync screen. */
data class SyncCounts(
    val pending: Int,
    val records: Int,
    @ColumnInfo(name = "uploads_pending") val uploadsPending: Int,
    @ColumnInfo(name = "upload_bytes_pending") val uploadBytesPending: Long,
    @ColumnInfo(name = "uploaded_bytes") val uploadedBytes: Long,
)

/** One row of [SyncDao.editedVersions]. */
data class EditedVersion(
    @ColumnInfo(name = "version_uuid") val versionUuid: String,
    @ColumnInfo(name = "photo_uuid") val photoUuid: String,
    @ColumnInfo(name = "edit_device") val editDevice: String?,
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
    @ColumnInfo(name = "copy_of") val copyOf: String?,
    /** A Server photo made from another device's photo record (its uuid is the record's, its stem key too). */
    @ColumnInfo(name = "from_record") val fromRecord: Boolean,
)

/** A photo the W5 recovery asks the server about ([SyncDao.recoveryFingerprints]); null while it has no fingerprint. */
data class RecoveryKey(@ColumnInfo(name = "photo_id") val photoId: Long, val fingerprint: String?)

/** An edited Server photo's version an unpairing keeps ([SyncDao.unsyncedServerEdits]). */
data class KeptEdit(
    @ColumnInfo(name = "version_uuid") val versionUuid: String,
    @ColumnInfo(name = "photo_uuid") val photoUuid: String,
)

@Dao
@Suppress("TooManyFunctions")
interface SyncDao {
    // ---- records

    @Query(
        "SELECT * FROM sync_records WHERE dirty = 1 ORDER BY CASE kind WHEN 'photo' THEN 0 WHEN 'version' THEN 1 " +
            "ELSE 2 END, modified_at LIMIT :limit",
    )
    suspend fun dirty(limit: Int): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE kind = :kind AND record_id = :id")
    suspend fun record(kind: String, id: String): SyncRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecord(record: SyncRecordEntity)

    /**
     * After a push or an apply: the record is at [rev]; it stays dirty when it changed again since the push read it
     * ([modifiedAt] no longer matches).
     */
    @Query(
        "UPDATE sync_records SET rev = :rev, dirty = CASE WHEN modified_at = :modifiedAt THEN 0 ELSE dirty END " +
            "WHERE kind = :kind AND record_id = :id",
    )
    suspend fun pushed(kind: String, id: String, rev: Long, modifiedAt: Long)

    @Query("UPDATE sync_records SET dirty = 0 WHERE kind = :kind AND record_id = :id AND modified_at = :modifiedAt")
    suspend fun clean(kind: String, id: String, modifiedAt: Long)

    @Query("DELETE FROM sync_records WHERE kind = :kind AND record_id = :id")
    suspend fun deleteRecord(kind: String, id: String)

    /**
     * The versions of the photo [photoUuid] an earlier W5 build held back with [SyncRecordEntity.HELD_REV] (ADR 0018
     * "W5"): marked to push (rev 0, their edit time kept). Returns how many. Current holds keep their revision.
     */
    @Query(
        "UPDATE sync_records SET dirty = 1, rev = 0 WHERE kind = 'version' AND rev = -1 AND record_id IN " +
            "(SELECT v.uuid FROM versions v JOIN photos p ON p.id = v.photo_id WHERE p.uuid = :photoUuid)",
    )
    suspend fun releaseHeldVersions(photoUuid: String): Int

    /**
     * Versions a user edited (a recipe that is not the default, a name, a virtual copy, a conflict copy or snapshots)
     * and their photo, for the one-off repair of ADR 0018 "W5".
     */
    @Query(
        "SELECT v.uuid AS version_uuid, p.uuid AS photo_uuid, v.edit_device, v.edited_at, v.copy_of, " +
            "(s.kind = 'SERVER' AND p.stem_key = p.uuid) AS from_record FROM versions v " +
            "JOIN photos p ON p.id = v.photo_id JOIN sources s ON s.id = p.source_id " +
            "WHERE " + EDITED_VERSION + " ORDER BY v.id",
    )
    suspend fun editedVersions(): List<EditedVersion>

    /**
     * Unpairing (ADR 0018 "W5"): the Server source's photos without an edited version go; a Server photo a user edited
     * stays with its versions (the server may not hold its edit, and an old record naming a photo id without its
     * fingerprint could never find it again). Same "edited" as [editedVersions].
     */
    @Query(
        "DELETE FROM photos WHERE source_id IN (SELECT id FROM sources WHERE kind = 'SERVER') AND NOT EXISTS " +
            "(SELECT 1 FROM versions v WHERE v.photo_id = photos.id AND (" + EDITED_VERSION + "))",
    )
    suspend fun deleteUneditedServerPhotos()

    /** The Server source once none of its photos is left ([deleteUneditedServerPhotos]). */
    @Query(
        "DELETE FROM sources WHERE kind = 'SERVER' AND NOT EXISTS " +
            "(SELECT 1 FROM photos p WHERE p.source_id = sources.id)",
    )
    suspend fun deleteEmptyServerSource()

    /** Edited versions of Server photos whose current state the server is not known to hold (unpairing keeps them). */
    @Query(
        "SELECT v.uuid AS version_uuid, p.uuid AS photo_uuid FROM versions v JOIN photos p ON p.id = v.photo_id " +
            "JOIN sources s ON s.id = p.source_id " +
            "WHERE s.kind = 'SERVER' AND (" + EDITED_VERSION + ") AND NOT EXISTS (SELECT 1 FROM sync_records r " +
            "WHERE r.kind = 'version' AND r.record_id = v.uuid AND r.rev > 0 AND r.dirty = 0 AND r.deleted = 0)",
    )
    suspend fun unsyncedServerEdits(): List<KeptEdit>

    /** The Server source's photos (uuids): what an unpairing keeps of it belongs to a server (SyncStore.forget). */
    @Query("SELECT p.uuid FROM photos p JOIN sources s ON s.id = p.source_id WHERE s.kind = 'SERVER'")
    suspend fun serverPhotoUuids(): List<String>

    /** Marks [uuids] (versions) to push as new on this server, dated by their last edit. */
    @Query(
        "INSERT OR REPLACE INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT 'version', v.uuid, 0, 1, COALESCE(v.edited_at, :now), 0 FROM versions v WHERE v.uuid IN (:uuids)",
    )
    suspend fun markVersionsNew(uuids: List<String>, now: Long)

    /**
     * Main-file fingerprints of photos whose records the server may never have had (Server photos and hand-offs), for
     * finding the photo of a parked version through the server's edited previews. Paged by photo id ([after] = the
     * last page's last id): photos added during a pass come after it, so no page shifts and nothing is skipped.
     */
    @Query(
        "SELECT p.id AS photo_id, (SELECT f.fingerprint FROM photo_files f WHERE f.photo_id = p.id " +
            "AND f.fingerprint IS NOT NULL ORDER BY CASE f.role WHEN 'RAW' THEN 0 ELSE 1 END, f.id LIMIT 1) " +
            "AS fingerprint FROM photos p JOIN sources s ON s.id = p.source_id WHERE s.kind IN ('SERVER', 'HANDOFF') " +
            "AND p.id > :after ORDER BY p.id LIMIT :limit",
    )
    suspend fun recoveryFingerprints(limit: Int, after: Long): List<RecoveryKey>

    /** First sync with a server: every photo and version is to be pushed (rev 0 = created there). */
    @Query(
        "INSERT OR REPLACE INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT 'photo', uuid, 0, 1, :now, 0 FROM photos WHERE source_id NOT IN " +
            "(SELECT id FROM sources WHERE kind = 'SERVER')",
    )
    suspend fun markAllPhotos(now: Long)

    @Query(
        "INSERT OR REPLACE INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT 'version', v.uuid, 0, 1, :now, 0 FROM versions v JOIN photos p ON p.id = v.photo_id " +
            "WHERE p.source_id NOT IN (SELECT id FROM sources WHERE kind = 'SERVER')",
    )
    suspend fun markAllVersions(now: Long)

    /** Every preset of the library (packs on trial too: the push skips those until they are kept). */
    @Query(
        "INSERT OR REPLACE INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT 'preset', uuid, 0, 1, :now, 0 FROM presets",
    )
    suspend fun markAllPresets(now: Long)

    /** Every user export preset, as its `setting` record (docs/adr/0025 "Sync"). */
    @Query(
        "INSERT OR REPLACE INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT 'setting', '" + SyncTriggers.EXPORT_PRESET_PREFIX + "' || uuid, 0, 1, :now, 0 FROM export_presets",
    )
    suspend fun markAllExportPresets(now: Long)

    @Query("SELECT COUNT(*) FROM sync_records WHERE dirty = 1")
    fun observePending(): Flow<Int>

    @Query(
        "SELECT (SELECT COUNT(*) FROM sync_records WHERE dirty = 1) AS pending, " +
            "(SELECT COUNT(*) FROM sync_records) AS records, " +
            "(SELECT COUNT(*) FROM sync_uploads WHERE state = 'pending' AND kind != 'drop_preview') " +
            "AS uploads_pending, " +
            "(SELECT COALESCE(SUM(size - `offset`), 0) FROM sync_uploads WHERE state = 'pending' " +
            "AND kind != 'drop_preview') AS upload_bytes_pending, " +
            "(SELECT COALESCE(SUM(size), 0) FROM sync_uploads WHERE state = 'done') AS uploaded_bytes",
    )
    fun observeCounts(): Flow<SyncCounts>

    // ---- lookups by stable id

    @Query("SELECT * FROM photos WHERE uuid = :uuid")
    suspend fun photoByUuid(uuid: String): PhotoEntity?

    @Query("SELECT * FROM versions WHERE uuid = :uuid")
    suspend fun versionByUuid(uuid: String): VersionEntity?

    @Query("SELECT * FROM versions WHERE photo_id = :photoId ORDER BY position")
    suspend fun versionsOf(photoId: Long): List<VersionEntity>

    @Query("SELECT photo_id FROM sync_aliases WHERE remote_uuid = :uuid")
    suspend fun alias(uuid: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlias(alias: SyncAliasEntity)

    /** The server ids that name [photoId] besides its own uuid. */
    @Query("SELECT remote_uuid FROM sync_aliases WHERE photo_id = :photoId ORDER BY remote_uuid")
    suspend fun aliasesOf(photoId: Long): List<String>

    @Query("DELETE FROM sync_aliases WHERE remote_uuid = :uuid")
    suspend fun deleteAlias(uuid: String)

    /** A merge (ADR 0011 "One photo across sources"): the aliases of [from] name [to]. */
    @Query("UPDATE sync_aliases SET photo_id = :to WHERE photo_id = :from")
    suspend fun moveAliases(from: Long, to: Long)

    @Query("UPDATE photos SET uuid = :uuid WHERE id = :id")
    suspend fun setPhotoUuid(id: Long, uuid: String)

    @Query("UPDATE versions SET uuid = :uuid WHERE id = :id")
    suspend fun setVersionUuid(id: Long, uuid: String)

    @Update
    suspend fun updateVersion(version: VersionEntity)

    @Query("DELETE FROM versions WHERE id = :id")
    suspend fun deleteVersion(id: Long)

    @Query("UPDATE photos SET rating = :rating, flag = :flag, label = :label, hidden = :hidden WHERE id = :id")
    suspend fun applyPhotoMetadata(id: Long, rating: Int, flag: PickFlag, label: String?, hidden: Boolean)

    /** A pulled photo record while culling comes from the server's culling records (ADR 0027): only `hidden`. */
    @Query("UPDATE photos SET hidden = :hidden WHERE id = :id")
    suspend fun applyPhotoHidden(id: Long, hidden: Boolean)

    /**
     * Every printed file and whether the sync server holds a copy Reveal can read instead (the file itself, or the
     * photo's main file): Settings -> Storage keeps a hand-off's local preview unless this is true (FIX/W).
     */
    @Query(
        "SELECT f.fingerprint AS fingerprint, " +
            "(f.on_server = 1 OR COALESCE(c.original_on_server, 0) = 1) AS on_server FROM photo_files f " +
            "LEFT JOIN photo_cloud c ON c.photo_id = f.photo_id WHERE f.fingerprint IS NOT NULL",
    )
    suspend fun printedFiles(): List<PrintedFile>

    /** A Server photo's stored size and EXIF orientation from its record (FIX/W). */
    @Query("UPDATE photos SET width = :width, height = :height, orientation = :orientation WHERE id = :id")
    suspend fun setPhotoSize(id: Long, width: Int, height: Int, orientation: Int)

    /**
     * This device's photos whose orientation is not 1 and whose record the server already holds (rev > 0, not dirty):
     * 0.5.3 and older pushed them without an orientation, so they are pushed again once (FIX/W).
     */
    @Query(
        "UPDATE sync_records SET dirty = 1, modified_at = :now WHERE kind = 'photo' AND dirty = 0 AND deleted = 0 " +
            "AND rev > 0 AND record_id IN (SELECT p.uuid FROM photos p JOIN sources s ON s.id = p.source_id " +
            "WHERE s.kind != 'SERVER' AND p.orientation != 1 AND p.metadata_state = 'READ')",
    )
    suspend fun markOrientedPhotosDirty(now: Long): Int

    @Query("DELETE FROM history WHERE version_id = :versionId")
    suspend fun clearHistory(versionId: Long)

    @Insert
    suspend fun insertHistory(steps: List<HistoryEntity>)

    /** The photos of [fingerprints]' main files (a RAW, or the rendering of a photo without one). */
    @Query(
        "SELECT f.* FROM photo_files f WHERE f.fingerprint IN (:fingerprints) AND (f.role = 'RAW' OR NOT EXISTS " +
            "(SELECT 1 FROM photo_files o WHERE o.photo_id = f.photo_id AND o.role = 'RAW')) ORDER BY f.id",
    )
    suspend fun mainFilesByFingerprint(fingerprints: List<String>): List<PhotoFileEntity>

    // ---- the Server source

    @Query("SELECT * FROM sources WHERE kind = 'SERVER' LIMIT 1")
    suspend fun serverSource(): SourceEntity?

    /**
     * The photos the "Server" entry lists: those that open (`PhotoQuerySql.SERVER_OPENABLE_SQL`) or lost their files
     * (shown unavailable), not those in Hoard's trash (ADR 0018 "R1").
     */
    @Query(
        "SELECT COUNT(*) FROM photos p LEFT JOIN photo_cloud c ON c.photo_id = p.id WHERE p.hidden = 0 AND " +
            "p.source_id IN (SELECT id FROM sources WHERE kind = 'SERVER') AND " + PhotoQuerySql.SERVER_LISTED_SQL +
            " AND NOT " + PhotoQuerySql.IN_TRASH_SQL,
    )
    fun observeServerPhotoCount(): Flow<Int>

    @Query("DELETE FROM sources WHERE kind = 'SERVER'")
    suspend fun deleteServerSource()

    /**
     * NAS photos without files and without an edit (their files left the NAS): nothing left to show. A photo another
     * device described (it has a sync record) stays: its record decides.
     */
    @Query(
        "DELETE FROM photos WHERE source_id = :sourceId AND has_edit = 0 AND handoff_at IS NULL AND id NOT IN " +
            "(SELECT photo_id FROM photo_files) AND uuid NOT IN " +
            "(SELECT record_id FROM sync_records WHERE kind = 'photo')",
    )
    suspend fun deleteEmptyServerPhotos(sourceId: Long): Int

    /** Files a Server-source photo under its NAS folder (kept when another photo there has the same stem). */
    @Query("UPDATE OR IGNORE photos SET folder = :folder WHERE id = :id")
    suspend fun setServerFolder(id: Long, folder: String)

    @Query("SELECT * FROM photo_files WHERE source_id = :sourceId AND relative_path = :path")
    suspend fun serverFileByPath(sourceId: Long, path: String): PhotoFileEntity?

    /**
     * A NAS file of [fingerprint] was purged (ADR 0018 "R1"): this device's own files of it (folders, hand-offs) no
     * longer count as on the server, and their photos are looked up again by the next sync.
     */
    @Query(
        "UPDATE photo_files SET on_server = 0 WHERE fingerprint = :fingerprint AND source_id NOT IN " +
            "(SELECT id FROM sources WHERE kind = 'SERVER')",
    )
    suspend fun localNotOnServer(fingerprint: String)

    @Query(
        "UPDATE photo_cloud SET original_on_server = 0, original_source = NULL, checked_at = 0 WHERE photo_id IN " +
            "(SELECT f.photo_id FROM photo_files f JOIN sources s ON s.id = f.source_id WHERE s.kind != 'SERVER' " +
            "AND f.fingerprint = :fingerprint)",
    )
    suspend fun lookUpAgain(fingerprint: String)

    @androidx.room.Transaction
    suspend fun askAgain(fingerprint: String) {
        localNotOnServer(fingerprint)
        lookUpAgain(fingerprint)
    }

    /** A NAS file moved into Hoard's trash ([at]) or out of it (null), ADR 0018 "R1". */
    @Query("UPDATE photo_files SET trashed_at = :at WHERE id = :fileId")
    suspend fun setTrashed(fileId: Long, at: Long?)

    /** A photo "Remove from Reveal" hid is shown again: its file is back on the server (ADR 0018 "R1"). */
    @Query("UPDATE photos SET hidden = 0, forgotten_at = NULL WHERE id = :photoId")
    suspend fun unforget(photoId: Long)

    /**
     * Server-source photos still `PENDING` whose original the server serves: the next header reads, newest first.
     * A photo the server's lookup missed ([HEADER_MISS_PREFIX] in `meta`, stamped with the time) waits until that stamp
     * is not after [missedSince] (a miss is "not yet", never a failed header: a photo with a wrong orientation and no
     * capture time would sort and stretch wrongly for good); [Long.MAX_VALUE] ignores the stamps.
     */
    @Query(
        "SELECT p.id FROM photos p JOIN photo_cloud c ON c.photo_id = p.id WHERE p.source_id = :sourceId AND " +
            "p.metadata_state = 'PENDING' AND c.original_on_server = 1 AND NOT EXISTS (SELECT 1 FROM meta m WHERE " +
            "m.`key` = '$HEADER_MISS_PREFIX' || p.id AND CAST(m.value AS INTEGER) > :missedSince) " +
            "ORDER BY p.sort_time DESC LIMIT :limit",
    )
    suspend fun serverHeaderCandidates(sourceId: Long, limit: Int, missedSince: Long): List<Long>

    /**
     * Hand-off photos whose main file (the RAW of a pair, else the rendering) the server holds (ADR 0018 "W3/C"): they
     * become Server photos ([adoptFile], [adoptPhoto]) so nothing opens the sender's URI to show or edit them. A file
     * that is a readable local MediaStore copy (`content://media/...`, W4) stays where it is: adoption exists to stop
     * reading over the sender's proxy, and a local copy is faster and works offline; an unreadable one is adopted. A
     * Downloads original Reveal keeps itself (`file:` under `handoff-originals`) is never adopted: adoption would drop
     * the row and leave the copy behind.
     */
    @Query(
        "SELECT p.id FROM photos p JOIN sources s ON s.id = p.source_id WHERE s.kind = 'HANDOFF' AND EXISTS " +
            "(SELECT 1 FROM photo_files f WHERE f.photo_id = p.id AND f.on_server = 1 AND f.fingerprint IS NOT NULL " +
            "AND (f.document_id NOT LIKE 'content://media/%' OR f.unreadable_at IS NOT NULL) " +
            "AND f.document_id NOT LIKE 'file:%/handoff-originals/%' " +
            "AND f.id = (SELECT m.id FROM photo_files m WHERE m.photo_id = p.id AND m.role IN ('RAW', 'RENDERED') " +
            "ORDER BY CASE m.role WHEN 'RAW' THEN 0 ELSE 1 END, m.id LIMIT 1)) AND NOT EXISTS (SELECT 1 FROM meta x " +
            "WHERE x.`key` = '$ADOPT_FAILED_PREFIX' || p.id AND CAST(x.value AS INTEGER) > :failedSince) " +
            "ORDER BY p.id LIMIT :limit",
    )
    suspend fun adoptableHandoffs(limit: Int, failedSince: Long): List<Long>

    /**
     * Moves a photo to [sourceId] (the Server source); its sidecar link goes: a Server photo's culling rides the server
     * (docs/adr/0011 "Amendment W3/H": no sidecar is written for server items, the sender's URI is never opened again).
     */
    @Query("UPDATE photos SET source_id = :sourceId, xmp_document_id = NULL, xmp_modified = NULL WHERE id = :photoId")
    suspend fun adoptPhoto(photoId: Long, sourceId: Long)

    /** Moves a file to the Server source under the server's document id (ADR 0018 "Server photos"). */
    @Query(
        "UPDATE photo_files SET source_id = :sourceId, relative_path = :path, document_id = :documentId, " +
            "unreadable_at = NULL, checked_at = NULL WHERE id = :fileId",
    )
    suspend fun adoptFile(fileId: Long, sourceId: Long, path: String, documentId: String)

    // ---- folder photos Hoard may have freed (ADR 0018 "R1")

    /** Files of folder photos a walk no longer lists, waiting for the server's word (`photo_files.vanished_at`). */
    @Query(
        "SELECT f.* FROM photo_files f JOIN sources s ON s.id = f.source_id WHERE s.kind = 'FOLDER' AND " +
            "f.vanished_at IS NOT NULL ORDER BY f.photo_id, f.id",
    )
    suspend fun vanishedFiles(): List<PhotoFileEntity>

    /** A freed file becomes the server's: its fingerprint (the item's), document id and path in the Server source. */
    @Query(
        "UPDATE photo_files SET source_id = :sourceId, relative_path = :path, document_id = :documentId, " +
            "fingerprint = :fingerprint, on_server = 1, vanished_at = NULL, unreadable_at = NULL, checked_at = NULL " +
            "WHERE id = :fileId",
    )
    suspend fun adoptFreedFile(fileId: Long, sourceId: Long, path: String, documentId: String, fingerprint: String)

    /** A freed folder photo moves to the Server source under [folder] (its sidecar link goes, as for hand-offs). */
    @Query(
        "UPDATE photos SET source_id = :sourceId, folder = :folder, folder_document_id = '', xmp_document_id = NULL, " +
            "xmp_modified = NULL WHERE id = :photoId",
    )
    suspend fun adoptFreedPhoto(photoId: Long, sourceId: Long, folder: String)

    // ---- cloud state

    @Query("SELECT * FROM photo_cloud WHERE photo_id = :photoId")
    suspend fun cloud(photoId: Long): PhotoCloudEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCloud(cloud: PhotoCloudEntity)

    /**
     * Photos with a printed main file that the server was not asked about since [before] (or never): the next
     * fingerprint lookup. Server-source photos are included: another device's backup can make a preview-only photo's
     * original reachable (a waiting export then goes on).
     */
    @Query(
        "SELECT p.id AS photo_id, p.source_id, f.id AS file_id, f.document_id, f.name, f.size, f.fingerprint, " +
            "s.backup FROM photos p JOIN sources s ON s.id = p.source_id JOIN photo_files f ON f.photo_id = p.id " +
            "LEFT JOIN photo_cloud c ON c.photo_id = p.id WHERE f.fingerprint IS NOT NULL " +
            "AND (f.role = 'RAW' OR NOT EXISTS (SELECT 1 FROM photo_files o WHERE o.photo_id = p.id AND " +
            "o.role = 'RAW')) AND COALESCE(c.original_on_server, 0) = 0 AND COALESCE(c.checked_at, 0) < :before " +
            "ORDER BY p.sort_time DESC LIMIT :limit",
    )
    suspend fun unchecked(before: Long, limit: Int): List<CloudCandidate>

    /**
     * Device-only photos (looked up, missing on the server) that need a smart preview from this device (ADR 0018
     * "Smart previews", FIX/P): an edited photo's original is backed up instead ([backupCandidates]), so a preview is
     * only for an original that cannot go up: one Reveal cannot read any more (the preview is then the only copy), or
     * an edited one while the originals backup is off ([originalsOff]). Ratings, flags and labels travel without one.
     * Edited first, then newest. A photo's preview is made again when its file changes.
     */
    @Query(
        PREVIEW_PHOTOS + "AND (c.preview_fingerprint IS NULL OR c.preview_fingerprint != f.fingerprint) " +
            "ORDER BY p.has_edit DESC, p.sort_time DESC LIMIT :limit",
    )
    suspend fun previewCandidates(originalsOff: Boolean, limit: Int): List<CloudCandidate>

    /**
     * Photos that still qualify for a preview and whose preview this device uploaded in a format older than [format]
     * (0.5.0-0.5.1 wrote format 1, about 9 MB): made again lazily, newest first. Not for unreadable originals (their
     * preview is the hand-off's own, it cannot be made again).
     */
    @Query(
        PREVIEW_PHOTOS + "AND c.preview_fingerprint = f.fingerprint AND c.preview_sha IS NOT NULL " +
            "AND f.unreadable_at IS NULL AND COALESCE(c.preview_format, 1) < :format " +
            "ORDER BY p.sort_time DESC LIMIT :limit",
    )
    suspend fun outdatedPreviews(originalsOff: Boolean, format: Int, limit: Int): List<CloudCandidate>

    /**
     * Previews this device made (it uploaded them, or 0.5.x did: a preview fingerprint) for photos that no longer
     * qualify: not edited and readable, or their original reached the server (ADR 0018 "Smart previews").
     */
    @Query(
        "SELECT c.photo_id AS photo_id, c.preview_sha AS sha256 FROM photo_cloud c " +
            "JOIN photos p ON p.id = c.photo_id JOIN sources s ON s.id = p.source_id " +
            "WHERE c.preview_sha IS NOT NULL AND s.kind != 'SERVER' " +
            "AND p.hidden = 0 AND (c.preview_format IS NOT NULL OR c.preview_fingerprint IS NOT NULL) " +
            "AND (c.original_on_server = 1 OR (p.has_edit = 0 AND NOT EXISTS (SELECT 1 FROM photo_files f " +
            "WHERE f.photo_id = p.id AND f.unreadable_at IS NOT NULL))) " +
            "AND NOT EXISTS (SELECT 1 FROM sync_uploads u WHERE u.photo_id = p.id AND u.kind = 'preview' AND " +
            "u.state = 'pending') LIMIT :limit",
    )
    suspend fun droppablePreviews(limit: Int): List<DroppablePreview>

    /**
     * Device-only photos (looked up, missing on the server) of folders with the backup on, and edited photos of any
     * folder or hand-off (an edit is worth its original, like Lightroom; ADR 0018 "Smart previews"), not queued yet.
     * Removing the edit later never deletes an uploaded original. A file Reveal cannot read (ADR 0011 "Unreadable
     * hand-offs") waits until it can: its smart preview goes instead.
     */
    @Query(
        "SELECT p.id AS photo_id, p.source_id, f.id AS file_id, f.document_id, f.name, f.size, f.fingerprint, " +
            "s.backup FROM photos p JOIN sources s ON s.id = p.source_id JOIN photo_files f ON f.photo_id = p.id " +
            "JOIN photo_cloud c ON c.photo_id = p.id WHERE s.kind != 'SERVER' AND s.state = 'AVAILABLE' " +
            "AND (s.backup = 1 OR p.has_edit = 1) AND p.hidden = 0 AND c.original_on_server = 0 AND c.checked_at > 0 " +
            "AND f.fingerprint IS NOT NULL AND f.unreadable_at IS NULL AND (f.role = 'RAW' OR NOT EXISTS " +
            "(SELECT 1 FROM photo_files o WHERE o.photo_id = p.id AND o.role = 'RAW')) AND NOT EXISTS " +
            "(SELECT 1 FROM sync_uploads u WHERE u.photo_id = p.id AND u.kind = 'original' AND u.state != 'done') " +
            "AND (c.candidate_at IS NULL OR c.candidate_at <= :waitedSince) " +
            "ORDER BY p.sort_time DESC LIMIT :limit",
    )
    suspend fun backupCandidates(limit: Int, waitedSince: Long = Long.MAX_VALUE): List<CloudCandidate>

    /**
     * A candidate Hoard's phone backup covers starts its grace now (ADR 0018 "R1"): [backupCandidates] leaves it out
     * until [at] is before its `waitedSince`. Only the first time counts.
     */
    @Query("UPDATE photo_cloud SET candidate_at = :at WHERE photo_id = :photoId AND candidate_at IS NULL")
    suspend fun startGrace(photoId: Long, at: Long)

    /** Pending original uploads that are due (not waiting for another device's upload of the same bytes). */
    @Query(
        "SELECT * FROM sync_uploads WHERE state = 'pending' AND kind = 'original' AND " +
            "(retry_at IS NULL OR retry_at <= :now) ORDER BY id LIMIT :limit",
    )
    suspend fun dueOriginals(now: Long, limit: Int): List<SyncUploadEntity>

    /**
     * The other files of the Server-source photos among [photoIds] (their main file is in the lookup already): a shot
     * opens from any file the server holds (FIX/V).
     */
    @Query(
        "SELECT f.* FROM photo_files f JOIN sources s ON s.id = f.source_id WHERE s.kind = 'SERVER' AND " +
            "f.photo_id IN (:photoIds) AND f.fingerprint IS NOT NULL",
    )
    suspend fun serverFilesOf(photoIds: List<Long>): List<PhotoFileEntity>

    /** What a lookup or the NAS feed said about one file (per file: a pair may have only its RAW on the server). */
    @Query("UPDATE photo_files SET on_server = :onServer WHERE fingerprint = :fingerprint")
    suspend fun setOnServer(fingerprint: String, onServer: Boolean)

    /** True when the server can serve [fingerprint] as far as this device knows (a lookup or the NAS feed said so). */
    @Query("SELECT COUNT(*) > 0 FROM photo_files WHERE fingerprint = :fingerprint AND on_server = 1")
    suspend fun knownOnServer(fingerprint: String): Boolean

    // ---- deleting from the server (FIX/V)

    @Query("SELECT * FROM photos WHERE source_id = :sourceId")
    suspend fun photosOfSource(sourceId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun photosById(ids: List<Long>): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE source_id = :sourceId AND has_edit = 1")
    suspend fun editedInSource(sourceId: Long): Int

    @Update
    suspend fun updatePhotoRow(photo: PhotoEntity)

    @Update
    suspend fun updateFileRows(files: List<PhotoFileEntity>)

    @Query("DELETE FROM photo_files WHERE id IN (:ids)")
    suspend fun deleteFileRows(ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun queueDeletes(deletes: List<SyncDeleteEntity>)

    @Query("SELECT * FROM sync_deletes ORDER BY created_at, fingerprint LIMIT :limit")
    suspend fun pendingDeletes(limit: Int): List<SyncDeleteEntity>

    @Update
    suspend fun updateDelete(delete: SyncDeleteEntity)

    @Query("DELETE FROM sync_deletes WHERE fingerprint = :fingerprint")
    suspend fun deleteQueued(fingerprint: String)

    @Query("DELETE FROM sync_deletes")
    suspend fun clearDeletes()

    /** Unpairing: nothing is known to be on a server any more. */
    @Query("UPDATE photo_files SET on_server = 0 WHERE on_server = 1")
    suspend fun clearOnServer()

    /** Photos (any source) whose main file is [fingerprint]: duplicates when more than one, ordered oldest first. */
    @Query(
        "SELECT p.* FROM photos p JOIN photo_files f ON f.photo_id = p.id WHERE f.fingerprint = :fingerprint AND " +
            "(f.role = 'RAW' OR NOT EXISTS (SELECT 1 FROM photo_files o WHERE o.photo_id = p.id AND " +
            "o.role = 'RAW')) ORDER BY p.id",
    )
    suspend fun photosWithMainFile(fingerprint: String): List<PhotoEntity>

    /** Main-file fingerprints that more than one Server-source photo has (the one-off clean-up, FIX/V). */
    @Query(
        "SELECT f.fingerprint FROM photo_files f JOIN photos p ON p.id = f.photo_id JOIN sources s ON " +
            "s.id = p.source_id WHERE s.kind = 'SERVER' AND f.fingerprint IS NOT NULL AND (f.role = 'RAW' OR NOT " +
            "EXISTS (SELECT 1 FROM photo_files o WHERE o.photo_id = p.id AND o.role = 'RAW')) " +
            "GROUP BY f.fingerprint HAVING COUNT(DISTINCT p.id) > 1",
    )
    suspend fun duplicateServerFingerprints(): List<String>

    /** Every photo record id this device holds a row for (live ones), for the one-off clean-up (FIX/V). */
    @Query("SELECT record_id FROM sync_records WHERE kind = 'photo' AND deleted = 0 AND rev > 0")
    suspend fun livePhotoRecordIds(): List<String>

    // ---- uploads

    @Insert
    suspend fun insertUpload(upload: SyncUploadEntity): Long

    @Update
    suspend fun updateUpload(upload: SyncUploadEntity)

    @Query("SELECT * FROM sync_uploads WHERE state = 'pending' AND kind = :kind ORDER BY id LIMIT :limit")
    suspend fun pendingUploads(kind: String, limit: Int): List<SyncUploadEntity>

    /** True when a photo record on this device names [sha256] as its smart preview (a drop of it must wait). */
    @Query("SELECT COUNT(*) > 0 FROM photo_cloud WHERE preview_sha = :sha256")
    suspend fun previewNamed(sha256: String): Boolean

    /** Cancels queued drops of [sha256] (the same preview is named again). */
    @Query("DELETE FROM sync_uploads WHERE kind = 'drop_preview' AND sha256 = :sha256 AND state = 'pending'")
    suspend fun cancelDrops(sha256: String)

    @Query("SELECT * FROM sync_uploads WHERE id = :id")
    suspend fun upload(id: Long): SyncUploadEntity?

    @Query("DELETE FROM sync_uploads WHERE id = :id")
    suspend fun deleteUpload(id: Long)

    // ---- inbox

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun park(entry: SyncInboxEntity)

    @Query("SELECT * FROM sync_inbox ORDER BY rev")
    suspend fun inbox(): List<SyncInboxEntity>

    @Query("DELETE FROM sync_inbox WHERE kind = :kind AND record_id = :id AND rev <= :rev")
    suspend fun unpark(kind: String, id: String, rev: Long)

    // ---- forgetting a server

    @Query("DELETE FROM sync_records")
    suspend fun clearRecords()

    @Query("DELETE FROM photo_cloud")
    suspend fun clearCloud()

    @Query("DELETE FROM sync_uploads")
    suspend fun clearUploads()

    @Query("DELETE FROM sync_aliases")
    suspend fun clearAliases()

    @Query("DELETE FROM sync_inbox")
    suspend fun clearInbox()

    /** The pending uploads of [photoId] (a photo moved to the Server source cannot send them any more). */
    @Query("DELETE FROM sync_uploads WHERE photo_id = :photoId AND state = 'pending' AND kind != 'drop_preview'")
    suspend fun cancelUploads(photoId: Long)
}
