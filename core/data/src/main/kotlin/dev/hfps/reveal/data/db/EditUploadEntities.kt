package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * What hoard-server holds of one version's edited preview (Hoard ADR 0020, Reveal ADR 0027 "Edited previews"), as this
 * device last uploaded or saw it: the item, the recipe digest and the metadata. Keyed by the version's sync uuid and
 * not tied to the row, so a version deleted here can still be deleted on the server. [refused] = `digest|editedAt`
 * the server refused for good (an older edit than another device's, an invalid image, too many edits): not tried
 * again until the edit changes.
 */
@Entity(tableName = "edit_uploads")
data class EditUploadEntity(
    @PrimaryKey @ColumnInfo(name = "version_uuid") val versionUuid: String,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "item_id") val itemId: String? = null,
    /** The digest the server holds for this version; null = nothing there. */
    val digest: String? = null,
    val label: String = "",
    val primary: Boolean = false,
    @ColumnInfo(name = "virtual_copy") val virtualCopy: Boolean = false,
    @ColumnInfo(name = "edited_at") val editedAt: Long = 0,
    val refused: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

/** A version as the edited-preview client sees it: its edit (recipe), name, place, and when it last changed. */
data class EditCandidate(
    @ColumnInfo(name = "version_id") val versionId: Long,
    val uuid: String,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    val position: Int,
    val name: String?,
    @ColumnInfo(name = "recipe_json") val recipeJson: String,
    @ColumnInfo(name = "recipe_at") val recipeAt: Long,
    /** The version record's `modifiedAt` (null: never tracked). */
    @ColumnInfo(name = "record_at") val recordAt: Long?,
    /** The version record still waits to be pushed. */
    @ColumnInfo(name = "record_dirty") val recordDirty: Boolean?,
    /** The photo's main file fingerprint (the item lookup's key). */
    val fingerprint: String?,
)

@Dao
interface EditUploadDao {
    /**
     * Every edited version that can have an edited preview: a non-default recipe, not a sync conflict copy (Hoard ADR
     * 0020 client rule 2).
     */
    @Query(
        "SELECT v.id AS version_id, v.uuid, v.photo_id, v.position, v.name, r.json AS recipe_json, " +
            "r.updated_at AS recipe_at, sr.modified_at AS record_at, sr.dirty AS record_dirty, " +
            "(SELECT f.fingerprint FROM photo_files f " +
            "WHERE f.photo_id = v.photo_id AND f.role IN ('RAW', 'RENDERED') " +
            "ORDER BY CASE f.role WHEN 'RAW' THEN 0 ELSE 1 END, f.id LIMIT 1) AS fingerprint " +
            "FROM versions v JOIN recipes r ON r.version_id = v.id " +
            "LEFT JOIN sync_records sr ON sr.kind = 'version' AND sr.record_id = v.uuid " +
            "WHERE v.copy_of IS NULL AND r.is_default = 0",
    )
    suspend fun candidates(): List<EditCandidate>

    @Query("SELECT * FROM edit_uploads")
    suspend fun all(): List<EditUploadEntity>

    @Upsert
    suspend fun put(entity: EditUploadEntity)

    @Query("DELETE FROM edit_uploads WHERE version_uuid = :uuid")
    suspend fun forget(uuid: String)

    @Query("DELETE FROM edit_uploads")
    suspend fun clear()

    /** The version still exists here (unedited or a conflict copy now): its server edit goes. */
    @Query("SELECT COUNT(*) FROM versions WHERE uuid = :uuid")
    suspend fun versionExists(uuid: String): Int

    /** The version was deleted on purpose (its record is a tombstone): its server edit goes. */
    @Query("SELECT COUNT(*) FROM sync_records WHERE kind = 'version' AND record_id = :uuid AND deleted = 1")
    suspend fun tombstone(uuid: String): Int
}
