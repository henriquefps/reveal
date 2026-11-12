package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.hfps.reveal.data.model.PickFlag
import kotlinx.coroutines.flow.Flow

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

/** Counts for the Sync screen. */
data class SyncCounts(
    val pending: Int,
    val records: Int,
    @ColumnInfo(name = "uploads_pending") val uploadsPending: Int,
    @ColumnInfo(name = "upload_bytes_pending") val uploadBytesPending: Long,
    @ColumnInfo(name = "uploaded_bytes") val uploadedBytes: Long,
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

    @Query("SELECT COUNT(*) FROM sync_records WHERE dirty = 1")
    fun observePending(): Flow<Int>

    @Query(
        "SELECT (SELECT COUNT(*) FROM sync_records WHERE dirty = 1) AS pending, " +
            "(SELECT COUNT(*) FROM sync_records) AS records, " +
            "(SELECT COUNT(*) FROM sync_uploads WHERE state = 'pending') AS uploads_pending, " +
            "(SELECT COALESCE(SUM(size - `offset`), 0) FROM sync_uploads WHERE state = 'pending') " +
            "AS upload_bytes_pending, " +
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

    @Query(
        "SELECT COUNT(*) FROM photos WHERE hidden = 0 AND source_id IN (SELECT id FROM sources WHERE kind = 'SERVER')",
    )
    fun observeServerPhotoCount(): Flow<Int>

    @Query("DELETE FROM sources WHERE kind = 'SERVER'")
    suspend fun deleteServerSource()

    /**
     * NAS photos without files and without an edit (their files left the NAS): nothing left to show. A photo another
     * device described (it has a sync record) stays: its record decides.
     */
    @Query(
        "DELETE FROM photos WHERE source_id = :sourceId AND has_edit = 0 AND id NOT IN " +
            "(SELECT photo_id FROM photo_files) AND uuid NOT IN " +
            "(SELECT record_id FROM sync_records WHERE kind = 'photo')",
    )
    suspend fun deleteEmptyServerPhotos(sourceId: Long): Int

    /** Files a Server-source photo under its NAS folder (kept when another photo there has the same stem). */
    @Query("UPDATE OR IGNORE photos SET folder = :folder WHERE id = :id")
    suspend fun setServerFolder(id: Long, folder: String)

    @Query("SELECT * FROM photo_files WHERE source_id = :sourceId AND relative_path = :path")
    suspend fun serverFileByPath(sourceId: Long, path: String): PhotoFileEntity?

    /** Server-source photos still `PENDING` whose original the server serves: the next header reads, newest first. */
    @Query(
        "SELECT p.id FROM photos p JOIN photo_cloud c ON c.photo_id = p.id WHERE p.source_id = :sourceId AND " +
            "p.metadata_state = 'PENDING' AND c.original_on_server = 1 ORDER BY p.sort_time DESC LIMIT :limit",
    )
    suspend fun serverHeaderCandidates(sourceId: Long, limit: Int): List<Long>

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
     * "Smart previews"): edited, rated or picked ones, and every one whose original is not backed up ([noBackup], its
     * folder's backup is off, or Reveal cannot read it any more). Edited first, then newest. A photo's preview is made
     * again when its file changes.
     */
    @Query(
        "SELECT p.id AS photo_id, p.source_id, f.id AS file_id, f.document_id, f.name, f.size, f.fingerprint, " +
            "s.backup FROM photos p JOIN sources s ON s.id = p.source_id JOIN photo_files f ON f.photo_id = p.id " +
            "JOIN photo_cloud c ON c.photo_id = p.id WHERE s.kind != 'SERVER' AND s.state = 'AVAILABLE' " +
            "AND p.hidden = 0 AND c.original_on_server = 0 AND c.checked_at > 0 AND f.fingerprint IS NOT NULL " +
            "AND (f.role = 'RAW' OR NOT EXISTS (SELECT 1 FROM photo_files o WHERE o.photo_id = p.id AND " +
            "o.role = 'RAW')) AND (c.preview_fingerprint IS NULL OR c.preview_fingerprint != f.fingerprint) " +
            "AND (p.has_edit = 1 OR p.rating > 0 OR p.flag = 'PICK' OR s.backup = 0 OR :noBackup OR " +
            "f.unreadable_at IS NOT NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM sync_uploads u WHERE u.photo_id = p.id AND u.kind = 'preview' AND " +
            "u.state = 'pending') ORDER BY p.has_edit DESC, p.sort_time DESC LIMIT :limit",
    )
    suspend fun previewCandidates(noBackup: Boolean, limit: Int): List<CloudCandidate>

    /**
     * Device-only photos (looked up, missing on the server) of folders with the backup on, not queued yet. A file
     * Reveal cannot read (ADR 0011 "Unreadable hand-offs") waits until it can: its smart preview goes instead.
     */
    @Query(
        "SELECT p.id AS photo_id, p.source_id, f.id AS file_id, f.document_id, f.name, f.size, f.fingerprint, " +
            "s.backup FROM photos p JOIN sources s ON s.id = p.source_id JOIN photo_files f ON f.photo_id = p.id " +
            "JOIN photo_cloud c ON c.photo_id = p.id WHERE s.kind != 'SERVER' AND s.state = 'AVAILABLE' " +
            "AND s.backup = 1 AND p.hidden = 0 AND c.original_on_server = 0 AND c.checked_at > 0 " +
            "AND f.fingerprint IS NOT NULL AND f.unreadable_at IS NULL AND (f.role = 'RAW' OR NOT EXISTS " +
            "(SELECT 1 FROM photo_files o WHERE o.photo_id = p.id AND o.role = 'RAW')) AND NOT EXISTS " +
            "(SELECT 1 FROM sync_uploads u WHERE u.photo_id = p.id AND u.kind = 'original' AND u.state != 'done') " +
            "ORDER BY p.sort_time DESC LIMIT :limit",
    )
    suspend fun backupCandidates(limit: Int): List<CloudCandidate>

    // ---- uploads

    @Insert
    suspend fun insertUpload(upload: SyncUploadEntity): Long

    @Update
    suspend fun updateUpload(upload: SyncUploadEntity)

    @Query("SELECT * FROM sync_uploads WHERE state = 'pending' AND kind = :kind ORDER BY id LIMIT :limit")
    suspend fun pendingUploads(kind: String, limit: Int): List<SyncUploadEntity>

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
}
