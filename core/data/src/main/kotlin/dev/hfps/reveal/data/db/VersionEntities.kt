package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A snapshot: a named, saved state of one version's recipe (docs/adr/0025 "Snapshots"). It belongs to its version (a
 * virtual copy has its own), travels with it through sync (`version.data.snapshots`) and goes when the version goes.
 * [recipeJson] is ADR 0003 JSON, decoded through `RecipeCodec` (its migrators) when applied.
 */
@Entity(
    tableName = "snapshots",
    foreignKeys = [
        ForeignKey(
            entity = VersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["version_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["version_id"]), Index(value = ["uuid"], unique = true)],
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id: the same snapshot on every device. */
    val uuid: String = newUuid(),
    @ColumnInfo(name = "version_id") val versionId: Long,
    val name: String,
    @ColumnInfo(name = "recipe_json") val recipeJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A user export preset (docs/adr/0025 "Export presets"): a named combination of the export sheet's choices.
 * [optionsJson] is `:feature:export`'s `ExportOptions` JSON (opaque here, decoded with defaults for unknown or missing
 * fields). The built-in presets are code, never rows. Synced as `setting` records `export-preset.<uuid>`.
 */
@Entity(tableName = "export_presets", indices = [Index(value = ["uuid"], unique = true)])
data class ExportPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val name: String,
    @ColumnInfo(name = "options_json") val optionsJson: String,
    /** Order in the sheet's preset row (ascending; new presets go last). */
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Virtual copies (versions), snapshots and export presets (docs/adr/0025). */
@Dao
@Suppress("TooManyFunctions")
interface VersionDao {
    @Query("SELECT * FROM versions WHERE photo_id = :photoId ORDER BY position")
    suspend fun versionsOf(photoId: Long): List<VersionEntity>

    @Query("SELECT * FROM versions WHERE photo_id = :photoId ORDER BY position")
    fun observeVersionsOf(photoId: Long): Flow<List<VersionEntity>>

    @Query("SELECT * FROM versions WHERE id = :id")
    suspend fun version(id: Long): VersionEntity?

    @Insert
    suspend fun insertVersion(version: VersionEntity): Long

    @Query("UPDATE versions SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    @Query("UPDATE versions SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("DELETE FROM versions WHERE id = :id")
    suspend fun deleteVersion(id: Long)

    // ---- snapshots

    @Query("SELECT * FROM snapshots WHERE version_id = :versionId ORDER BY created_at DESC, id DESC")
    suspend fun snapshots(versionId: Long): List<SnapshotEntity>

    @Query("SELECT * FROM snapshots WHERE version_id = :versionId ORDER BY created_at DESC, id DESC")
    fun observeSnapshots(versionId: Long): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots WHERE id = :id")
    suspend fun snapshot(id: Long): SnapshotEntity?

    @Query("SELECT * FROM snapshots WHERE uuid = :uuid")
    suspend fun snapshotByUuid(uuid: String): SnapshotEntity?

    /** Moves every snapshot of [from] to [into] (the conflict chooser keeps a copy's edit). */
    @Query("UPDATE snapshots SET version_id = :into WHERE version_id = :from")
    suspend fun moveSnapshots(from: Long, into: Long)

    @Insert
    suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Insert
    suspend fun insertSnapshots(snapshots: List<SnapshotEntity>)

    @Query("UPDATE snapshots SET name = :name WHERE id = :id")
    suspend fun renameSnapshot(id: Long, name: String)

    @Query("DELETE FROM snapshots WHERE id = :id")
    suspend fun deleteSnapshot(id: Long)

    @Query("DELETE FROM snapshots WHERE version_id = :versionId")
    suspend fun clearSnapshots(versionId: Long)

    // ---- export presets

    @Query("SELECT * FROM export_presets ORDER BY position, id")
    fun observeExportPresets(): Flow<List<ExportPresetEntity>>

    @Query("SELECT * FROM export_presets ORDER BY position, id")
    suspend fun exportPresets(): List<ExportPresetEntity>

    @Query("SELECT * FROM export_presets WHERE uuid = :uuid")
    suspend fun exportPresetByUuid(uuid: String): ExportPresetEntity?

    @Insert
    suspend fun insertExportPreset(preset: ExportPresetEntity): Long

    @androidx.room.Update
    suspend fun updateExportPreset(preset: ExportPresetEntity)

    @Query("DELETE FROM export_presets WHERE id = :id")
    suspend fun deleteExportPreset(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM export_presets")
    suspend fun nextExportPresetPosition(): Int
}
