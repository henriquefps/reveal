package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An imported preset pack (docs/adr/0019 "Library"): its metadata, licence and cover (a file under
 * `files/preset-covers`, named by the pack's uuid). [trial] is true while the user tries it before keeping it: its
 * presets show in the panel but do not sync. Its groups cascade from it.
 */
@Entity(tableName = "preset_packs", indices = [Index(value = ["uuid"], unique = true)])
data class PresetPackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val author: String,
    val version: Int,
    @ColumnInfo(name = "licence_name") val licenceName: String,
    @ColumnInfo(name = "licence_text") val licenceText: String,
    val link: String?,
    @ColumnInfo(name = "cover_file") val coverFile: String?,
    val trial: Boolean,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
)

/**
 * A group of the preset library: the user's own (no pack) or one of a pack's groups ([packId]). [position] orders the
 * groups in the panel; [uuid] is the group's identity in files and sync.
 */
@Entity(
    tableName = "preset_groups",
    foreignKeys = [
        ForeignKey(
            entity = PresetPackEntity::class,
            parentColumns = ["id"],
            childColumns = ["pack_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["pack_id"])],
)
data class PresetGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val name: String,
    val position: Int,
    @ColumnInfo(name = "pack_id") val packId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One preset (docs/adr/0019): its metadata, the patch (the nested partial recipe of the file format) and the optional
 * smart part, both as JSON. [uuid] is the preset's id (files, sync, favourites). [origin]: `USER` (created here),
 * `IMPORTED` (a `.revealpreset`), `PACK` (from a pack, read-only but deletable), `SYNC` (another device's).
 */
@Entity(
    tableName = "presets",
    foreignKeys = [
        ForeignKey(
            entity = PresetGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["group_id", "position"])],
)
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "group_id") val groupId: Long,
    val name: String,
    val author: String,
    val version: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val position: Int,
    @ColumnInfo(name = "patch_json") val patchJson: String,
    @ColumnInfo(name = "smart_json") val smartJson: String?,
    val origin: String,
)

/**
 * Per-preset marks, keyed by the preset's uuid so built-in presets (not rows of [PresetEntity]) have them too:
 * favourite and when it was last applied (the "Recent" row).
 */
@Entity(tableName = "preset_marks", indices = [Index(value = ["last_used_at"])])
data class PresetMarkEntity(
    @PrimaryKey val uuid: String,
    val favourite: Boolean = false,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
)
