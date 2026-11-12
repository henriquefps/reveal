package dev.hfps.reveal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The preset library's tables (docs/adr/0019 "Library"). */
@Dao
interface PresetDao {
    // ------------------------------------------------------------------ observe

    @Query("SELECT * FROM preset_groups ORDER BY position, id")
    fun observeGroups(): Flow<List<PresetGroupEntity>>

    @Query("SELECT * FROM presets ORDER BY group_id, position, id")
    fun observePresets(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM preset_packs ORDER BY imported_at")
    fun observePacks(): Flow<List<PresetPackEntity>>

    @Query("SELECT * FROM preset_marks")
    fun observeMarks(): Flow<List<PresetMarkEntity>>

    // ------------------------------------------------------------------ read

    @Query("SELECT * FROM preset_groups ORDER BY position, id")
    suspend fun groups(): List<PresetGroupEntity>

    @Query("SELECT * FROM preset_groups WHERE id = :id")
    suspend fun group(id: Long): PresetGroupEntity?

    @Query("SELECT * FROM preset_groups WHERE uuid = :uuid")
    suspend fun groupByUuid(uuid: String): PresetGroupEntity?

    @Query("SELECT * FROM preset_groups WHERE pack_id IS NULL AND name = :name ORDER BY position LIMIT 1")
    suspend fun userGroupNamed(name: String): PresetGroupEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM preset_groups")
    suspend fun nextGroupPosition(): Int

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun preset(id: Long): PresetEntity?

    @Query("SELECT * FROM presets WHERE uuid = :uuid")
    suspend fun presetByUuid(uuid: String): PresetEntity?

    @Query("SELECT * FROM presets WHERE group_id = :groupId ORDER BY position, id")
    suspend fun presetsIn(groupId: Long): List<PresetEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM presets WHERE group_id = :groupId")
    suspend fun nextPresetPosition(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM presets WHERE group_id = :groupId")
    suspend fun countIn(groupId: Long): Int

    @Query("SELECT * FROM preset_packs WHERE id = :id")
    suspend fun pack(id: Long): PresetPackEntity?

    @Query("SELECT * FROM preset_packs WHERE uuid = :uuid")
    suspend fun packByUuid(uuid: String): PresetPackEntity?

    @Query("SELECT * FROM preset_marks WHERE uuid = :uuid")
    suspend fun mark(uuid: String): PresetMarkEntity?

    // ------------------------------------------------------------------ write

    @Insert
    suspend fun insertGroup(group: PresetGroupEntity): Long

    @Update
    suspend fun updateGroup(group: PresetGroupEntity)

    @Query("DELETE FROM preset_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Insert
    suspend fun insertPreset(preset: PresetEntity): Long

    @Update
    suspend fun updatePreset(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deletePreset(id: Long)

    @Query("DELETE FROM presets WHERE uuid = :uuid")
    suspend fun deletePresetByUuid(uuid: String)

    @Insert
    suspend fun insertPack(pack: PresetPackEntity): Long

    @Update
    suspend fun updatePack(pack: PresetPackEntity)

    @Query("DELETE FROM preset_packs WHERE id = :id")
    suspend fun deletePack(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMark(mark: PresetMarkEntity)

    @Query("DELETE FROM preset_marks WHERE uuid = :uuid")
    suspend fun deleteMark(uuid: String)

    /** Deletes the user group [id] when no preset is left in it. */
    @Query(
        "DELETE FROM preset_groups WHERE id = :id AND pack_id IS NULL AND " +
            "NOT EXISTS (SELECT 1 FROM presets WHERE group_id = :id)",
    )
    suspend fun deleteGroupIfEmpty(id: Long)
}
