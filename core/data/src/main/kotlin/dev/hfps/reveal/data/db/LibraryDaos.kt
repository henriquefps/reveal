package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import kotlinx.coroutines.flow.Flow

/** A source with its photo count, for the sources list. */
data class SourceWithCount(@Embedded val source: SourceEntity, @ColumnInfo(name = "photo_count") val photoCount: Int)

/** A grid cell: the photo, its first version and the time its recipe last changed (null = never edited). */
data class PhotoRow(
    val id: Long,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val name: String,
    val format: PhotoFormat,
    @ColumnInfo(name = "sort_time") val sortTime: Long,
    @ColumnInfo(name = "capture_time") val captureTime: Long?,
    val rating: Int,
    val label: String?,
    val flag: PickFlag,
    @ColumnInfo(name = "has_edit") val hasEdit: Boolean,
    @ColumnInfo(name = "version_id") val versionId: Long,
    @ColumnInfo(name = "edit_time") val editTime: Long?,
    /** Hand-off photos only: when another app sent it and the sender's title (ADR 0011). */
    @ColumnInfo(name = "handoff_at") val handoffAt: Long? = null,
    @ColumnInfo(name = "handoff_title") val handoffTitle: String? = null,
    /** 0 for a photo's first version; a later one is a virtual copy (a sync conflict copy when [conflict]). */
    @ColumnInfo(name = "version_position") val versionPosition: Int = 0,
    /** A virtual copy's name ("Copy 1", docs/adr/0025); null for the first version and unnamed versions. */
    @ColumnInfo(name = "version_name") val versionName: String? = null,
    val conflict: Boolean = false,
    /** The device whose losing change a conflict copy keeps (ADR 0017), null otherwise. */
    @ColumnInfo(name = "copy_device") val copyDevice: String? = null,
    /** The sync server can serve the original (ADR 0018 "Cloud states"). */
    @ColumnInfo(name = "cloud_original") val cloudOriginal: Boolean = false,
    /** The sync server holds a smart preview of it. */
    @ColumnInfo(name = "cloud_preview") val cloudPreview: Boolean = false,
    /** The photo lives only on the sync server (the Server source): no file on this device. */
    val remote: Boolean = false,
    /** This device still owes the server an upload of it (original or smart preview): the pending badge. */
    val uploading: Boolean = false,
    /**
     * Reveal can no longer read one of its files (a hand-off whose temporary grant ended, ADR 0011): it shows and edits
     * its local preview ("preview only").
     */
    val unreadable: Boolean = false,
    /**
     * [PhotoAvailability]: unreadable, and neither the server's original nor a server preview can stand in. A local
     * preview is not in the database: [LibraryRepository] clears this for photos that have one.
     */
    val unavailable: Boolean = false,
    /** The fingerprint of an unreadable file of the photo (to look for its local preview), null when readable. */
    @ColumnInfo(name = "unreadable_fingerprint") val unreadableFingerprint: String? = null,
    /**
     * A Server photo in Hoard's trash (ADR 0018 "R1"): listed only under that filter, shown as a placeholder, never
     * opened full size or edited.
     */
    @ColumnInfo(name = "in_trash") val inTrash: Boolean = false,
) {
    /** This cell's version has an edit (per version: a virtual copy is edited or not on its own, docs/adr/0025). */
    val edited: Boolean get() = editTime != null

    /** The grid's key: the photo for its first version, the version for a copy (two cells of one photo). */
    val gridKey: Any get() = if (versionPosition == 0) id else "v$versionId"
}

/** What the incremental walk compares a listed file with. */
data class IndexedFile(
    val id: Long,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    val size: Long,
    val modified: Long,
    /** A walk missed it and its photo waits for the server's word (ADR 0018 "R1"); null = listed last time. */
    @ColumnInfo(name = "vanished_at") val vanishedAt: Long? = null,
)

/** A file whose content fingerprint is still to be computed (filled in the background after a walk). */
data class UnprintedFile(
    val id: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    val name: String,
    val size: Long,
    val modified: Long,
)

/** A stored file found by its fingerprint, with what decides whether a hand-off may reuse its photo. */
data class FingerprintMatch(
    @Embedded val file: PhotoFileEntity,
    @ColumnInfo(name = "source_kind") val sourceKind: SourceKind,
    @ColumnInfo(name = "source_state") val sourceState: SourceState,
    @ColumnInfo(name = "photo_hidden") val photoHidden: Boolean,
    /** The photo was opened from another app (an adopted hand-off is one) or has an edit: the one to keep. */
    @ColumnInfo(name = "photo_kept") val photoKept: Boolean = false,
)

/** What the incremental walk compares a listed shot with. */
data class IndexedPhoto(
    val id: Long,
    val folder: String,
    @ColumnInfo(name = "stem_key") val stemKey: String,
    @ColumnInfo(name = "xmp_document_id") val xmpDocumentId: String?,
    @ColumnInfo(name = "xmp_modified") val xmpModified: Long?,
)

@Dao
interface SourceDao {
    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE tree_uri = :treeUri")
    suspend fun byTree(treeUri: String): SourceEntity?

    /** The added folders (not the hand-off source). */
    @Query("SELECT * FROM sources WHERE kind = 'FOLDER' ORDER BY display_name COLLATE NOCASE")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE kind = 'HANDOFF' LIMIT 1")
    suspend fun handoff(): SourceEntity?

    @Query(
        "SELECT s.*, (SELECT COUNT(*) FROM photos p WHERE p.source_id = s.id AND p.hidden = 0 AND NOT " +
            PhotoQuerySql.VANISHED_SQL + ") AS photo_count " +
            "FROM sources s WHERE s.kind = 'FOLDER' ORDER BY s.display_name COLLATE NOCASE, s.id",
    )
    fun observeWithCounts(): Flow<List<SourceWithCount>>

    @Query("UPDATE sources SET state = :state, volume_name = COALESCE(:volumeName, volume_name) WHERE id = :id")
    suspend fun setState(id: Long, state: SourceState, volumeName: String?)

    @Query("UPDATE sources SET backup = :backup WHERE id = :id")
    suspend fun setBackup(id: Long, backup: Boolean)

    @Query("UPDATE sources SET indexed_at = :time WHERE id = :id")
    suspend fun setIndexed(id: Long, time: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Per-choice photo counts for the filter sheet (`PhotoDao.observeFilterCounts`). */
data class FilterCounts(
    @ColumnInfo(name = "from_apps") val fromApps: Int = 0,
    @ColumnInfo(name = "server_only") val serverOnly: Int = 0,
    val picked: Int = 0,
    val starred: Int = 0,
    val rejected: Int = 0,
    val edited: Int = 0,
    @ColumnInfo(name = "not_edited") val notEdited: Int = 0,
    val raw: Int = 0,
    val jpeg: Int = 0,
    val synced: Int = 0,
    /** Every photo of the home (unfiltered). */
    val total: Int = 0,
    /** Photos in Hoard's trash (not part of [total]: hidden unless their filter is on, ADR 0018 "R1"). */
    @ColumnInfo(name = "in_trash") val inTrash: Int = 0,
) {
    val notSynced: Int get() = total - synced
}

/** A camera of the library (W3/D): make and model as the header wrote them (trimmed), and how many cells. */
data class CameraCount(val make: String?, val model: String, val count: Int)

/** A lens of the library and how many cells (W3/D). */
data class LensCount(val lens: String, val count: Int)

/** How many cells each date preset of the Date filter holds (W3/D), relative to the day it was asked. */
data class DateCounts(
    @ColumnInfo(name = "this_month") val thisMonth: Int = 0,
    @ColumnInfo(name = "last_30_days") val last30Days: Int = 0,
    @ColumnInfo(name = "this_year") val thisYear: Int = 0,
    @ColumnInfo(name = "on_this_day") val onThisDay: Int = 0,
)

@Dao
@Suppress("TooManyFunctions", "LongParameterList")
interface PhotoDao {
    /** How many photos "Opened from other apps" lists (ADR 0011): the library starts there when it has no folder. */
    @Query("SELECT COUNT(*) FROM photos WHERE handoff_at IS NOT NULL AND hidden = 0")
    fun observeFromAppsCount(): Flow<Int>

    /**
     * How many grid cells of the library home each filter choice would show on its own (the filter sheet's counts; the
     * folders' come with the sources). Not faceted: counted over every cell, whatever the other filters. A cell is a
     * version: a virtual copy counts once more (docs/adr/0025).
     */
    @Query(
        "SELECT COALESCE(SUM(p.handoff_at IS NOT NULL), 0) " +
            "AS from_apps, COALESCE(SUM(" + PhotoQuerySql.SERVER_SOURCE_SQL + "), 0) AS server_only, " +
            "COALESCE(SUM(p.flag = 'PICK'), 0) AS picked, COALESCE(SUM(p.rating >= 1), 0) AS starred, " +
            "COALESCE(SUM(p.flag = 'REJECT'), 0) AS rejected, " +
            "COALESCE(SUM(" + PhotoQuerySql.EDITED_SQL + "), 0) AS edited, " +
            "COALESCE(SUM(NOT " + PhotoQuerySql.EDITED_SQL + "), 0) AS not_edited, " +
            "COALESCE(SUM(p.has_raw = 1), 0) AS raw, " +
            "COALESCE(SUM(p.has_raw = 0), 0) AS jpeg, COALESCE(SUM(" + PhotoQuerySql.SYNCED_SQL + "), 0) AS synced, " +
            "COUNT(*) AS total, (SELECT COUNT(*) " + PhotoQuerySql.CELLS_FROM + " WHERE p.hidden = 0 AND " +
            PhotoQuerySql.IN_TRASH_SQL + ") AS in_trash " + PhotoQuerySql.CELLS_FROM + " " +
            "WHERE " + PhotoQuerySql.LISTED_SQL,
    )
    fun observeFilterCounts(): Flow<FilterCounts>

    @RawQuery(
        observedEntities = [
            PhotoEntity::class, VersionEntity::class, RecipeEntity::class, PhotoCloudEntity::class,
            SourceEntity::class,
        ],
    )
    fun observeRows(query: SupportSQLiteQuery): Flow<List<PhotoRow>>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<PhotoRow>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun get(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :id")
    fun observe(id: Long): Flow<PhotoEntity?>

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<PhotoEntity>

    @Query("SELECT * FROM photo_files WHERE photo_id = :photoId ORDER BY role, name")
    suspend fun files(photoId: Long): List<PhotoFileEntity>

    @Query("SELECT photo_id FROM versions WHERE id = :versionId")
    suspend fun photoIdOfVersion(versionId: Long): Long?

    @Query("SELECT id FROM versions WHERE photo_id = :photoId ORDER BY position LIMIT 1")
    suspend fun firstVersion(photoId: Long): Long?

    // ---- indexing

    @Query(
        "SELECT id, photo_id, relative_path, size, modified, vanished_at FROM photo_files WHERE source_id = :sourceId",
    )
    suspend fun indexedFiles(sourceId: Long): List<IndexedFile>

    /**
     * A walk no longer lists these files and their photo kept none (ADR 0018 "R1", freed-adopt): the photo waits,
     * unlisted, for the server's word. The first time is kept (the 7-day bound counts from it).
     */
    @Query("UPDATE photo_files SET vanished_at = :at WHERE id IN (:ids) AND vanished_at IS NULL")
    suspend fun markVanished(ids: List<Long>, at: Long)

    /** A walk listed these files again: their photo is back (a read that failed while it was gone no longer counts). */
    @Query("UPDATE photo_files SET vanished_at = NULL, unreadable_at = NULL WHERE id IN (:ids)")
    suspend fun clearVanished(ids: List<Long>)

    /** Files of [sourceId] that vanished at or before [before]: the bound of the wait is over, today's rule applies. */
    @Query(
        "SELECT id FROM photo_files WHERE source_id = :sourceId AND vanished_at IS NOT NULL AND vanished_at <= :before",
    )
    suspend fun expiredVanished(sourceId: Long, before: Long): List<Long>

    /**
     * Rewrites nothing, but tells the grid's observers that these photos changed (a change to their files alone, which
     * the grid's query reads but does not observe).
     */
    @Query("UPDATE photos SET added_at = added_at WHERE id IN (:ids)")
    suspend fun touchPhotos(ids: List<Long>)

    /** The photo has a version a user edited (an edit, a name, a virtual or conflict copy, snapshots). */
    @Query("SELECT EXISTS (SELECT 1 FROM versions v WHERE v.photo_id = :photoId AND (" + EDITED_VERSION + "))")
    suspend fun isEdited(photoId: Long): Boolean

    @Query("SELECT id, folder, stem_key, xmp_document_id, xmp_modified FROM photos WHERE source_id = :sourceId")
    suspend fun indexedPhotos(sourceId: Long): List<IndexedPhoto>

    @Insert
    suspend fun insertPhoto(photo: PhotoEntity): Long

    @Insert
    suspend fun insertFiles(files: List<PhotoFileEntity>)

    @Insert
    suspend fun insertVersion(version: VersionEntity): Long

    @Query("DELETE FROM photo_files WHERE id IN (:ids)")
    suspend fun deleteFiles(ids: List<Long>)

    /** A changed file (size or mtime): its fingerprint is cleared, to be computed again. */
    @Query(
        "UPDATE photo_files SET size = :size, modified = :modified, document_id = :documentId, role = :role, " +
            "fingerprint = NULL WHERE id = :id",
    )
    suspend fun updateFile(id: Long, size: Long, modified: Long, documentId: String, role: String)

    @Query(
        "UPDATE photos SET name = :name, format = :format, has_raw = :hasRaw, " +
            "folder_document_id = :folderDocumentId, metadata_state = 'PENDING' WHERE id = :id",
    )
    suspend fun updateShot(id: Long, name: String, format: PhotoFormat, hasRaw: Boolean, folderDocumentId: String)

    @Query("DELETE FROM photos WHERE source_id = :sourceId AND id NOT IN (SELECT photo_id FROM photo_files)")
    suspend fun deleteEmptyPhotos(sourceId: Long): Int

    @Query(
        "SELECT f.* FROM photo_files f JOIN photos p ON p.id = f.photo_id WHERE p.source_id = :sourceId " +
            "AND p.metadata_state = 'PENDING' ORDER BY p.sort_time DESC",
    )
    suspend fun pendingMetadataFiles(sourceId: Long): List<PhotoFileEntity>

    @Query(
        "UPDATE photos SET capture_time = :captureTime, capture_offset = :offset, " +
            "sort_time = COALESCE(:captureTime, sort_time), width = :width, height = :height, " +
            "orientation = :orientation, metadata_state = :state, camera_make = :make, camera_model = :model, " +
            "lens = :lens, metadata_version = :version WHERE id = :id",
    )
    suspend fun setMetadata(
        id: Long,
        captureTime: Long?,
        offset: Int?,
        width: Int?,
        height: Int?,
        orientation: Int,
        state: MetadataState,
        make: String? = null,
        model: String? = null,
        lens: String? = null,
        version: Int = CURRENT_METADATA_VERSION,
    )

    /**
     * The camera fields of a photo read before W3/D (`LibraryIndexer.backfillCameras`): only columns no sync trigger
     * watches, so the backfill marks no record.
     */
    @Query(
        "UPDATE photos SET camera_make = :make, camera_model = :model, lens = :lens, metadata_version = :version " +
            "WHERE id = :id",
    )
    suspend fun setCamera(id: Long, make: String?, model: String?, lens: String?, version: Int)

    /** Files of photos of [sourceId] whose header was read before the camera fields existed, newest first. */
    @Query(
        "SELECT f.* FROM photo_files f JOIN photos p ON p.id = f.photo_id WHERE p.id IN (SELECT id FROM photos " +
            "WHERE source_id = :sourceId AND metadata_state = 'READ' AND metadata_version < :version " +
            "ORDER BY sort_time DESC LIMIT :limit)",
    )
    suspend fun cameraBackfillFiles(sourceId: Long, version: Int, limit: Int): List<PhotoFileEntity>

    /** [cameraBackfillFiles] of the Server source: only photos whose original the server serves (read by range). */
    @Query(
        "SELECT f.* FROM photo_files f JOIN photos p ON p.id = f.photo_id WHERE p.id IN (SELECT p2.id FROM photos " +
            "p2 JOIN photo_cloud c ON c.photo_id = p2.id WHERE p2.source_id = :sourceId AND p2.metadata_state = " +
            "'READ' AND p2.metadata_version < :version AND c.original_on_server = 1 ORDER BY p2.sort_time DESC " +
            "LIMIT :limit)",
    )
    suspend fun serverCameraBackfillFiles(sourceId: Long, version: Int, limit: Int): List<PhotoFileEntity>

    /** The cameras of the library (make + model) with their cell counts, most photos first (the Camera filter). */
    @Query(
        "SELECT p.camera_make AS make, p.camera_model AS model, COUNT(*) AS count " + PhotoQuerySql.CELLS_FROM +
            " WHERE " + PhotoQuerySql.LISTED_SQL + " AND p.camera_model IS NOT NULL AND p.camera_model != '' " +
            "GROUP BY p.camera_make, p.camera_model ORDER BY count DESC, model",
    )
    fun observeCameras(): Flow<List<CameraCount>>

    /** The lenses of the library with their cell counts, most photos first (the Lens filter). */
    @Query(
        "SELECT p.lens AS lens, COUNT(*) AS count " + PhotoQuerySql.CELLS_FROM + " WHERE " +
            PhotoQuerySql.LISTED_SQL + " AND p.lens IS NOT NULL AND p.lens != '' GROUP BY p.lens " +
            "ORDER BY count DESC, lens",
    )
    fun observeLenses(): Flow<List<LensCount>>

    /** [PhotoQuerySql.dateCounts]: how many cells each date preset holds. */
    @RawQuery(observedEntities = [PhotoEntity::class, VersionEntity::class, PhotoCloudEntity::class])
    fun observeDateCounts(query: SupportSQLiteQuery): Flow<DateCounts>

    @Query(
        "UPDATE photos SET rating = :rating, label = :label, flag = :flag, xmp_document_id = :xmpDocumentId, " +
            "xmp_modified = :xmpModified WHERE id = :id",
    )
    suspend fun applyXmp(
        id: Long,
        rating: Int,
        label: String?,
        flag: PickFlag,
        xmpDocumentId: String?,
        xmpModified: Long?,
    )

    /**
     * A sidecar import (ADR 0027): only the culling fields the file changed (`set*`), and the sidecar's state. The
     * sync triggers queue each field that really changes.
     */
    @Suppress("LongParameterList")
    @Query(
        "UPDATE photos SET rating = CASE WHEN :setRating THEN :rating ELSE rating END, " +
            "flag = CASE WHEN :setFlag THEN :flag ELSE flag END, " +
            "label = CASE WHEN :setLabel THEN :label ELSE label END, " +
            "xmp_document_id = :xmpDocumentId, xmp_modified = :xmpModified WHERE id = :id",
    )
    suspend fun applySidecar(
        id: Long,
        setRating: Boolean,
        rating: Int,
        setFlag: Boolean,
        flag: PickFlag,
        setLabel: Boolean,
        label: String?,
        xmpDocumentId: String?,
        xmpModified: Long?,
    )

    @Query("UPDATE photos SET xmp_document_id = :xmpDocumentId, xmp_modified = :xmpModified WHERE id = :id")
    suspend fun setSidecar(id: Long, xmpDocumentId: String?, xmpModified: Long?)

    // ---- user actions

    @Query("UPDATE photos SET rating = :rating WHERE id IN (:ids)")
    suspend fun setRating(ids: List<Long>, rating: Int)

    @Query("UPDATE photos SET flag = :flag WHERE id IN (:ids)")
    suspend fun setFlag(ids: List<Long>, flag: PickFlag)

    @Query("UPDATE photos SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<Long>, hidden: Boolean)

    /** "Remove from Reveal" of photos it keeps: hidden, and marked as forgotten (ADR 0018 "R1"). */
    @Query("UPDATE photos SET hidden = 1, forgotten_at = :at WHERE id IN (:ids)")
    suspend fun forgetHidden(ids: List<Long>, at: Long)

    // ---- hand-offs (ADR 0011)

    @Query("SELECT * FROM photos WHERE source_id = :sourceId AND folder = :folder AND stem_key = :stemKey")
    suspend fun byStem(sourceId: Long, folder: String, stemKey: String): PhotoEntity?

    @Query("SELECT * FROM photo_files WHERE source_id = :sourceId AND relative_path IN (:paths)")
    suspend fun filesByPath(sourceId: Long, paths: List<String>): List<PhotoFileEntity>

    @Query("DELETE FROM photo_files WHERE photo_id = :photoId")
    suspend fun deleteFilesOf(photoId: Long)

    // ---- content fingerprints (ADR 0006 "Identity")

    @Query(
        "SELECT f.*, s.kind AS source_kind, s.state AS source_state, p.hidden AS photo_hidden, " +
            "(p.handoff_at IS NOT NULL OR p.has_edit = 1) AS photo_kept FROM photo_files f " +
            "JOIN photos p ON p.id = f.photo_id JOIN sources s ON s.id = f.source_id " +
            "WHERE f.fingerprint IN (:fingerprints) ORDER BY f.id",
    )
    suspend fun filesByFingerprint(fingerprints: List<String>): List<FingerprintMatch>

    @Query(
        "SELECT id, document_id, name, size, modified FROM photo_files " +
            "WHERE source_id = :sourceId AND fingerprint IS NULL",
    )
    suspend fun unprintedFiles(sourceId: Long): List<UnprintedFile>

    /** Stores a fingerprint unless the file changed (or was printed) since it was read. */
    @Query(
        "UPDATE photo_files SET fingerprint = :fingerprint WHERE id = :id AND size = :size AND modified = :modified " +
            "AND fingerprint IS NULL",
    )
    suspend fun setFingerprint(id: Long, size: Long, modified: Long, fingerprint: String)

    @Query(
        "UPDATE photos SET name = :name, format = :format, has_raw = :hasRaw, folder_document_id = :folder, " +
            "handoff_at = :at, handoff_title = :title, hidden = 0, metadata_state = 'PENDING', " +
            "xmp_document_id = :xmpDocumentId, xmp_modified = :xmpModified WHERE id = :id",
    )
    suspend fun updateHandoff(
        id: Long,
        name: String,
        format: PhotoFormat,
        hasRaw: Boolean,
        folder: String,
        at: Long,
        title: String?,
        xmpDocumentId: String?,
        xmpModified: Long?,
    )

    /** Hand-off photos beyond the newest [keep] that have no edit, oldest first: candidates to forget. */
    @Query(
        "SELECT p.id FROM photos p WHERE p.source_id = :sourceId AND p.has_edit = 0 " +
            "ORDER BY p.handoff_at DESC, p.id DESC LIMIT -1 OFFSET :keep",
    )
    suspend fun staleHandoffs(sourceId: Long, keep: Int): List<Long>

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deletePhotos(ids: List<Long>)

    @Query("SELECT * FROM photo_files WHERE photo_id IN (:photoIds)")
    suspend fun filesOf(photoIds: List<Long>): List<PhotoFileEntity>

    @Query("UPDATE photos SET last_opened_at = :time WHERE id = :id")
    suspend fun setOpened(id: Long, time: Long)

    @Query("UPDATE photos SET has_edit = :hasEdit WHERE id = :id")
    suspend fun setHasEdit(id: Long, hasEdit: Boolean)

    // ---- unreadable documents (ADR 0011 "Unreadable hand-offs")

    /** Marks the files of [documentId] that are not marked yet: access was refused or the document is gone. */
    @Query(
        "UPDATE photo_files SET unreadable_at = :time WHERE document_id = :documentId AND unreadable_at IS NULL",
    )
    suspend fun setUnreadable(documentId: String, time: Long): Int

    /**
     * The file fingerprints of the [limit] photos opened most recently since [since] (ADR 0018 "W3/C"): their server
     * originals stay on this device (pinned) while the cache budget allows.
     */
    @Query(
        "SELECT f.fingerprint FROM photo_files f WHERE f.fingerprint IS NOT NULL AND f.photo_id IN " +
            "(SELECT id FROM photos WHERE last_opened_at > :since ORDER BY last_opened_at DESC LIMIT :limit)",
    )
    suspend fun recentlyOpenedFingerprints(since: Long, limit: Int): List<String>

    /** When the access check last looked at [documentId] (ADR 0018 "W3/C"). */
    @Query("UPDATE photo_files SET checked_at = :time WHERE document_id = :documentId")
    suspend fun setChecked(documentId: String, time: Long): Int

    /** Clears the mark of [documentId]: it was read again. */
    @Query("UPDATE photo_files SET unreadable_at = NULL WHERE document_id = :documentId AND unreadable_at IS NOT NULL")
    suspend fun setReadable(documentId: String): Int

    /**
     * How many of [versionIds] are versions of a photo with an unreadable file whose original the sync server does not
     * have (those export from the server, ADR 0011 "Fallbacks"): they export at preview size.
     */
    @Query(
        "SELECT COUNT(*) FROM versions v WHERE v.id IN (:versionIds) AND EXISTS (SELECT 1 FROM photo_files f " +
            "WHERE f.photo_id = v.photo_id AND f.unreadable_at IS NOT NULL) AND NOT EXISTS (SELECT 1 FROM " +
            "photo_cloud c WHERE c.photo_id = v.photo_id AND c.original_on_server = 1)",
    )
    suspend fun unreadableVersions(versionIds: List<Long>): Int

    /** Every file marked unreadable, whatever its source (the "Unavailable" re-check). */
    @Query("SELECT * FROM photo_files WHERE unreadable_at IS NOT NULL")
    suspend fun unreadableFiles(): List<PhotoFileEntity>

    /** [PhotoQuerySql.UNAVAILABLE_SQL] for one photo (the viewer); a local preview is checked by the repository. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM photos p LEFT JOIN photo_cloud c ON c.photo_id = p.id WHERE p.id = :id AND (" +
            PhotoQuerySql.UNAVAILABLE_SQL + "))",
    )
    suspend fun unavailableCandidate(id: Long): Boolean

    /** [PhotoQuerySql.IN_TRASH_SQL] for one photo (the viewer). */
    @Query("SELECT EXISTS(SELECT 1 FROM photos p WHERE p.id = :id AND " + PhotoQuerySql.IN_TRASH_SQL + ")")
    suspend fun inTrash(id: Long): Boolean

    /** How many of [ids] have an edit (the "Remove from Reveal" confirmation says they go too). */
    @Query("SELECT COUNT(*) FROM photos WHERE id IN (:ids) AND has_edit = 1")
    suspend fun editedCount(ids: List<Long>): Int

    /** Every file of the hand-off source (the access check). */
    @Query("SELECT f.* FROM photo_files f JOIN sources s ON s.id = f.source_id WHERE s.kind = 'HANDOFF'")
    suspend fun handoffFiles(): List<PhotoFileEntity>

    /** Unreadable hand-off documents (the gallery access offer looks for MediaStore ones). */
    @Query(
        "SELECT f.document_id FROM photo_files f JOIN sources s ON s.id = f.source_id JOIN photos p ON " +
            "p.id = f.photo_id WHERE s.kind = 'HANDOFF' AND f.unreadable_at IS NOT NULL AND p.hidden = 0",
    )
    fun observeUnreadableHandoffs(): Flow<List<String>>

    /**
     * The hand-off photo an earlier chunk of the same batch filed for a shot (batch stamp [at], parent key [folder],
     * stem [stem]): a later chunk's member joins it (ADR 0011 "Amendment W4").
     */
    @Query(
        "SELECT * FROM photos WHERE source_id = :sourceId AND handoff_at = :at AND folder_document_id = :folder " +
            "AND stem_key = :stem ORDER BY id LIMIT 1",
    )
    suspend fun batchShot(sourceId: Long, at: Long, folder: String, stem: String): PhotoEntity?

    /** Of [fingerprints], those a file row still files under a kept local copy (`handoff-originals`, W4). */
    @Query(
        "SELECT DISTINCT fingerprint FROM photo_files WHERE fingerprint IN (:fingerprints) " +
            "AND document_id LIKE 'file:%/handoff-originals/%'",
    )
    suspend fun localCopyPrints(fingerprints: List<String>): List<String>

    /**
     * Points a file row at another document (a hand-off's local copy of its original, W4). Its `relative_path` keeps
     * the sender's URI, so the same URI handed over again finds the row (and its fingerprint) without reading it.
     */
    @Query("UPDATE photo_files SET document_id = :to WHERE document_id = :from AND source_id = :sourceId")
    suspend fun repointFile(sourceId: Long, from: String, to: String)

    /** How many photos the newest hand-off batch holds (it is never pruned, however big). */
    @Query(
        "SELECT COUNT(*) FROM photos WHERE handoff_at IS NOT NULL AND " +
            "handoff_at = (SELECT MAX(handoff_at) FROM photos WHERE handoff_at IS NOT NULL)",
    )
    suspend fun newestBatchSize(): Int

    // ---- one photo across sources (ADR 0011 "One photo across sources")

    /**
     * Pairs of a hand-off file and a file of the available folder [folderSourceId] with the same fingerprint, whose
     * photo was not removed from the library: the hand-off photos that are a folder photo too.
     */
    suspend fun handoffTwins(folderSourceId: Long): List<HandoffTwin> = twins(folderSourceId, "HANDOFF")

    /**
     * Pairs of a Server-source file (ADR 0018 "Server photos") and a file of the available folder [folderSourceId]
     * with the same fingerprint: a folder added again (or added on this device) holds a photo the server already
     * describes, which becomes the folder's photo instead of a duplicate (ADR 0018 "Deleting from the server").
     */
    suspend fun serverTwins(folderSourceId: Long): List<HandoffTwin> = twins(folderSourceId, "SERVER")

    @Query(
        "SELECT h.photo_id AS handoff_photo_id, f.photo_id AS folder_photo_id, h.role AS handoff_role, " +
            "f.role AS folder_role FROM photo_files f JOIN photo_files h ON h.fingerprint = f.fingerprint " +
            "JOIN sources fs ON fs.id = f.source_id JOIN sources hs ON hs.id = h.source_id " +
            "JOIN photos fp ON fp.id = f.photo_id WHERE f.source_id = :folderSourceId AND fs.kind = 'FOLDER' " +
            "AND fs.state = 'AVAILABLE' AND hs.kind = :otherKind AND fp.hidden = 0 AND f.fingerprint IS NOT NULL " +
            "ORDER BY h.photo_id, f.id",
    )
    suspend fun twins(folderSourceId: Long, otherKind: String): List<HandoffTwin>

    @Query(
        "UPDATE photos SET handoff_at = :at, handoff_title = :title WHERE id = :id AND " +
            "(handoff_at IS NULL OR handoff_at < :at)",
    )
    suspend fun setHandoffAt(id: Long, at: Long, title: String?)

    /**
     * Folder and Server photos (adopted hand-offs, ADR 0018 "W3/C") beyond the newest [keep] of "Opened from other
     * apps" leave it (the photos stay).
     */
    @Query(
        "UPDATE photos SET handoff_at = NULL, handoff_title = NULL WHERE id IN (SELECT p.id FROM photos p WHERE " +
            "p.handoff_at IS NOT NULL ORDER BY p.handoff_at DESC, p.id DESC LIMIT -1 OFFSET :keep) AND " +
            "source_id IN (SELECT id FROM sources WHERE kind IN ('FOLDER', 'SERVER'))",
    )
    suspend fun clearStaleFolderHandoffs(keep: Int)

    @Query("SELECT * FROM versions WHERE photo_id = :photoId ORDER BY position")
    suspend fun versions(photoId: Long): List<VersionEntity>

    /** Moves a version to another photo (a merge; the version keeps its id, uuid, recipe and history). */
    @Query("UPDATE versions SET photo_id = :photoId, position = :position WHERE id = :versionId")
    suspend fun moveVersion(versionId: Long, photoId: Long, position: Int)

    @Query("DELETE FROM versions WHERE id = :versionId")
    suspend fun deleteVersion(versionId: Long)

    @Query("UPDATE photos SET rating = :rating, label = :label, flag = :flag WHERE id = :id")
    suspend fun setCulling(id: Long, rating: Int, label: String?, flag: PickFlag)
}

/** A hand-off file and a folder file with the same content ([PhotoDao.handoffTwins]). */
data class HandoffTwin(
    @ColumnInfo(name = "handoff_photo_id") val handoffPhotoId: Long,
    @ColumnInfo(name = "folder_photo_id") val folderPhotoId: Long,
    @ColumnInfo(name = "handoff_role") val handoffRole: dev.hfps.reveal.data.model.FileRole,
    @ColumnInfo(name = "folder_role") val folderRole: dev.hfps.reveal.data.model.FileRole,
)

/** A master version's recipe change ([EditDao.changedMasters]). */
data class ChangedRecipe(val photoId: Long, val updatedAt: Long)

@Dao
interface EditDao {
    /** Who changed the version's edit last and when (ADR 0018 FIX/W); no sync trigger watches these columns. */
    @Query("UPDATE versions SET edit_device = :device, edited_at = :at WHERE id = :versionId")
    suspend fun markEdited(versionId: Long, device: String?, at: Long?)

    @Query("SELECT * FROM recipes WHERE version_id = :versionId")
    suspend fun recipe(versionId: Long): RecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecipe(recipe: RecipeEntity)

    @Query(
        "SELECT COUNT(*) > 0 FROM recipes r JOIN versions v ON v.id = r.version_id " +
            "WHERE v.photo_id = :photoId AND r.is_default = 0",
    )
    suspend fun photoHasEdit(photoId: Long): Boolean

    @Query("SELECT * FROM history WHERE version_id = :versionId ORDER BY position")
    suspend fun history(versionId: Long): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE version_id = :versionId AND position = :position")
    suspend fun historyStep(versionId: Long, position: Int): HistoryEntity?

    @Insert
    suspend fun insertHistory(step: HistoryEntity)

    @Query("DELETE FROM history WHERE version_id = :versionId AND position > :position")
    suspend fun truncateHistoryAfter(versionId: Long, position: Int)

    @Query("SELECT position FROM history_cursor WHERE version_id = :versionId")
    suspend fun cursor(versionId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCursor(cursor: HistoryCursorEntity)

    /** The newest `recipes.updated_at`: emits on every recipe change (ADR 0026, the sidecar writer's trigger). */
    @Query("SELECT MAX(updated_at) FROM recipes")
    fun observeLastChange(): Flow<Long?>

    /** Master versions (position 0) whose recipe changed after [after] (Unix ms), oldest change first. */
    @Query(
        "SELECT v.photo_id AS photoId, r.updated_at AS updatedAt FROM recipes r " +
            "JOIN versions v ON v.id = r.version_id " +
            "WHERE v.position = 0 AND r.updated_at > :after ORDER BY r.updated_at",
    )
    suspend fun changedMasters(after: Long): List<ChangedRecipe>

    /** The most recently changed edited recipe of any version but [excludeVersionId] ("Previous"). */
    @Query(
        "SELECT * FROM recipes WHERE version_id != :excludeVersionId AND is_default = 0 " +
            "ORDER BY updated_at DESC LIMIT 1",
    )
    suspend fun lastEdited(excludeVersionId: Long): RecipeEntity?
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entries WHERE `key` = :key")
    suspend fun get(key: String): CacheEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntryEntity)

    @Query("UPDATE cache_entries SET last_access = :time WHERE `key` IN (:keys)")
    suspend fun touch(keys: List<String>, time: Long)

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM cache_entries")
    suspend fun totalBytes(): Long

    @Query("SELECT * FROM cache_entries ORDER BY last_access LIMIT :limit")
    suspend fun oldest(limit: Int): List<CacheEntryEntity>

    @Query("SELECT * FROM cache_entries")
    suspend fun all(): List<CacheEntryEntity>

    @Query("SELECT * FROM cache_entries WHERE `key` IN (:keys)")
    suspend fun entries(keys: List<String>): List<CacheEntryEntity>

    @Query("SELECT file_name FROM cache_entries")
    suspend fun fileNames(): List<String>

    @Query("DELETE FROM cache_entries WHERE `key` IN (:keys)")
    suspend fun delete(keys: List<String>)

    @Query("DELETE FROM cache_entries")
    suspend fun clear()
}
