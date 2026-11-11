package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import java.util.UUID

/** A new random (version 4) UUID, lower-case with dashes: the stable id of a synced row (ADR 0006 "Stable ids"). */
fun newUuid(): String = UUID.randomUUID().toString()

/**
 * An added folder: a Storage Access Framework tree the user granted (persisted). ADR 0006.
 * [volumeId] is the storage volume of an `ExternalStorageProvider` tree (`primary`, or an SD/USB uuid such as
 * `1234-ABCD`), null for other providers; [volumeName] its user-visible description when it was last mounted.
 */
@Entity(tableName = "sources", indices = [Index(value = ["tree_uri"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "root_document_id") val rootDocumentId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "volume_id") val volumeId: String?,
    @ColumnInfo(name = "volume_name") val volumeName: String?,
    val state: SourceState,
    /** The grant includes write and the folder accepts new documents: rating changes go to XMP sidecars. */
    val writable: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long? = null,
    /** [SourceKind.HANDOFF] is the single "Opened from other apps" source (ADR 0011); every other row is a folder. */
    @ColumnInfo(defaultValue = "FOLDER") val kind: SourceKind = SourceKind.FOLDER,
)

/**
 * One photo (a "shot"): a RAW, a camera rendering, or a RAW+JPEG pair collapsed into one (Hoard ADR 0006 pairing:
 * same folder, same case-insensitive stem). Its files are [PhotoFileEntity] rows; each file's identity is source +
 * relative path + size + mtime. [sortTime] = capture time (local camera clock, milliseconds incl. sub-seconds) or,
 * until the header is read or when it has none, the file's mtime.
 */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "folder", "stem_key"], unique = true),
        Index(value = ["source_id", "sort_time"]),
        Index(value = ["sort_time"]),
        Index(value = ["last_opened_at"]),
        Index(value = ["uuid"], unique = true),
    ],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id for sync (`docs/plan/sync.md`, ADR 0006 "Stable ids"): random, set on insert, never changes. */
    @ColumnInfo(defaultValue = "") val uuid: String = newUuid(),
    @ColumnInfo(name = "source_id") val sourceId: Long,
    /** Folder path relative to the source root, `/`-separated, "" for the root. */
    val folder: String,
    /** SAF document id of that folder (to create a sidecar next to the photo). */
    @ColumnInfo(name = "folder_document_id") val folderDocumentId: String,
    /** Lower-case stem: the pairing key inside [folder]. */
    @ColumnInfo(name = "stem_key") val stemKey: String,
    /** File name shown and searched: the RAW's name for a pair. */
    val name: String,
    val format: PhotoFormat,
    @ColumnInfo(name = "has_raw") val hasRaw: Boolean,
    @ColumnInfo(name = "sort_time") val sortTime: Long,
    /** Capture time from EXIF (camera clock as UTC millis, sub-seconds included), null until read or absent. */
    @ColumnInfo(name = "capture_time") val captureTime: Long? = null,
    /** EXIF OffsetTimeOriginal in minutes, when the camera wrote it. */
    @ColumnInfo(name = "capture_offset") val captureOffsetMinutes: Int? = null,
    /** Stored pixel size and EXIF orientation (1..8); the upright size swaps for 5..8. */
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 1,
    /** 0..5 stars. */
    val rating: Int = 0,
    /** `xmp:Label` (free text; Lightroom shows colour names as colours). */
    val label: String? = null,
    val flag: PickFlag = PickFlag.NONE,
    /** A non-default recipe exists for one of its versions (denormalised for the grid). */
    @ColumnInfo(name = "has_edit") val hasEdit: Boolean = false,
    @ColumnInfo(name = "metadata_state") val metadataState: MetadataState = MetadataState.PENDING,
    /** The XMP sidecar last applied: document id and its mtime (a newer mtime is read again). */
    @ColumnInfo(name = "xmp_document_id") val xmpDocumentId: String? = null,
    @ColumnInfo(name = "xmp_modified") val xmpModified: Long? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
    /** Hand-off source only (ADR 0011): when another app last sent this photo, and the sender's `EXTRA_TITLE`. */
    @ColumnInfo(name = "handoff_at") val handoffAt: Long? = null,
    @ColumnInfo(name = "handoff_title") val handoffTitle: String? = null,
    /**
     * "Remove from library": the photo stays indexed (so a rescan does not bring it back) but no grid shows it.
     * The files are never touched.
     */
    val hidden: Boolean = false,
)

/** One file on disk of a photo. Unique by (source, relative path); size + mtime detect changes. */
@Entity(
    tableName = "photo_files",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "relative_path"], unique = true),
        Index(value = ["photo_id"]),
        Index(value = ["fingerprint"]),
    ],
)
data class PhotoFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val role: FileRole,
    /**
     * Content fingerprint (`FileFingerprint`, `v1:` + SHA-256 of size, head and tail): the file's identity across
     * names, URIs and sources (ADR 0006 "Identity"). Null until computed (folders fill it in the background after a
     * walk) or when the file could not be read.
     */
    val fingerprint: String? = null,
)

/**
 * An independent edit of a photo. M1 creates exactly one per photo (with the photo); M5's virtual copies add more
 * rows ([position] orders them, [name] is null for the original version).
 */
@Entity(
    tableName = "versions",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["photo_id", "position"], unique = true), Index(value = ["uuid"], unique = true)],
)
data class VersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id for sync, as [PhotoEntity.uuid]. */
    @ColumnInfo(defaultValue = "") val uuid: String = newUuid(),
    @ColumnInfo(name = "photo_id") val photoId: Long,
    val position: Int = 0,
    val name: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** The committed recipe of a version (ADR 0003 JSON, `v` inside). Absent row = the default recipe. */
@Entity(
    tableName = "recipes",
    foreignKeys = [
        ForeignKey(
            entity = VersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["version_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecipeEntity(
    @PrimaryKey @ColumnInfo(name = "version_id") val versionId: Long,
    val json: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One committed step of a version's history (ADR 0003 "Committed steps", ADR 0006 "History"). Steps are ordered by
 * [position] (0, 1, 2, ...). [recipeJson] is the full recipe *after* the step ([encoding] = `snapshot`); [labelsJson]
 * is the step's `RecipeDiff` labels (`[{"field":"EXPOSURE","value":"+0.70"}]`). Position -1 never exists: the state
 * before the first step is the version's base (the default recipe).
 */
@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = VersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["version_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["version_id", "position"], unique = true)],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "version_id") val versionId: Long,
    val position: Int,
    val encoding: String = ENCODING_SNAPSHOT,
    @ColumnInfo(name = "recipe_json") val recipeJson: String,
    @ColumnInfo(name = "labels_json") val labelsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val ENCODING_SNAPSHOT = "snapshot"
    }
}

/** Where undo/redo stands in a version's history: the current step's position, -1 = before the first step. */
@Entity(
    tableName = "history_cursor",
    foreignKeys = [
        ForeignKey(
            entity = VersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["version_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HistoryCursorEntity(@PrimaryKey @ColumnInfo(name = "version_id") val versionId: Long, val position: Int)

/**
 * Index of the disk caches (thumbnails now; proxies and previews later): one row per cached file under the app
 * cache dir, so the LRU cap is enforced from the database instead of by listing the directory. [stamp] says what
 * the file was made from (file size + mtime, or recipe time + pipeline version), part of [key].
 */
@Entity(
    tableName = "cache_entries",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["photo_id"]), Index(value = ["last_access"])],
)
data class CacheEntryEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    val kind: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val bytes: Long,
    @ColumnInfo(name = "last_access") val lastAccess: Long,
)
