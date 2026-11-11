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
)

/** What the incremental walk compares a listed file with. */
data class IndexedFile(
    val id: Long,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    val size: Long,
    val modified: Long,
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

    @Query("SELECT * FROM sources ORDER BY display_name COLLATE NOCASE")
    suspend fun all(): List<SourceEntity>

    @Query(
        "SELECT s.*, (SELECT COUNT(*) FROM photos p WHERE p.source_id = s.id AND p.hidden = 0) AS photo_count " +
            "FROM sources s ORDER BY s.display_name COLLATE NOCASE, s.id",
    )
    fun observeWithCounts(): Flow<List<SourceWithCount>>

    @Query("UPDATE sources SET state = :state, volume_name = COALESCE(:volumeName, volume_name) WHERE id = :id")
    suspend fun setState(id: Long, state: SourceState, volumeName: String?)

    @Query("UPDATE sources SET indexed_at = :time WHERE id = :id")
    suspend fun setIndexed(id: Long, time: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
@Suppress("TooManyFunctions", "LongParameterList")
interface PhotoDao {
    @RawQuery(observedEntities = [PhotoEntity::class, VersionEntity::class, RecipeEntity::class])
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

    @Query("SELECT id, photo_id, relative_path, size, modified FROM photo_files WHERE source_id = :sourceId")
    suspend fun indexedFiles(sourceId: Long): List<IndexedFile>

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

    @Query(
        "UPDATE photo_files SET size = :size, modified = :modified, document_id = :documentId, role = :role " +
            "WHERE id = :id",
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
            "orientation = :orientation, metadata_state = :state WHERE id = :id",
    )
    suspend fun setMetadata(
        id: Long,
        captureTime: Long?,
        offset: Int?,
        width: Int?,
        height: Int?,
        orientation: Int,
        state: MetadataState,
    )

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

    @Query("UPDATE photos SET xmp_document_id = :xmpDocumentId, xmp_modified = :xmpModified WHERE id = :id")
    suspend fun setSidecar(id: Long, xmpDocumentId: String?, xmpModified: Long?)

    // ---- user actions

    @Query("UPDATE photos SET rating = :rating WHERE id IN (:ids)")
    suspend fun setRating(ids: List<Long>, rating: Int)

    @Query("UPDATE photos SET flag = :flag WHERE id IN (:ids)")
    suspend fun setFlag(ids: List<Long>, flag: PickFlag)

    @Query("UPDATE photos SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<Long>, hidden: Boolean)

    @Query("UPDATE photos SET last_opened_at = :time WHERE id = :id")
    suspend fun setOpened(id: Long, time: Long)

    @Query("UPDATE photos SET has_edit = :hasEdit WHERE id = :id")
    suspend fun setHasEdit(id: Long, hasEdit: Boolean)
}

@Dao
interface EditDao {
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

    @Query("SELECT file_name FROM cache_entries")
    suspend fun fileNames(): List<String>

    @Query("DELETE FROM cache_entries WHERE `key` IN (:keys)")
    suspend fun delete(keys: List<String>)

    @Query("DELETE FROM cache_entries")
    suspend fun clear()
}
