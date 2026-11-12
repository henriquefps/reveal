package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What this device knows about one synced record (ADR 0017 "Records", ADR 0018): the server revision of the state it
 * last pushed or applied ([rev], the next push's `baseRev`; 0 = never on the server) and whether it changed locally
 * since ([dirty], set by the triggers of `SyncTriggers` and by explicit deletes). [kind] is `photo`, `version`,
 * `preset` or `setting`; [recordId] the photo/version UUID or the setting key. A row is never deleted while sync is
 * paired: a tombstone keeps its row with [deleted] set.
 */
@Entity(tableName = "sync_records", primaryKeys = ["kind", "record_id"], indices = [Index(value = ["dirty"])])
data class SyncRecordEntity(
    val kind: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    val rev: Long = 0,
    val dirty: Boolean = true,
    /** When the device made the change (Unix ms, the device's clock): the push's `modifiedAt`. */
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val deleted: Boolean = false,
) {
    companion object {
        /** The record kinds the library's own code writes (the sync module's `Kinds`). */
        const val KIND_PHOTO = "photo"
        const val KIND_VERSION = "version"
    }
}

/**
 * The cloud state of a photo (ADR 0018 "Cloud states"): whether the server can serve its original (an indexed NAS file
 * or an uploaded backup, by fingerprint), its smart preview, and the work this device still owes it (preview and
 * original uploads). Absent row = nothing known yet (never synced).
 */
@Entity(
    tableName = "photo_cloud",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PhotoCloudEntity(
    @PrimaryKey @ColumnInfo(name = "photo_id") val photoId: Long,
    /** The server has the main file's fingerprint (NAS or backup): `/v1/originals/{fingerprint}` serves it. */
    @ColumnInfo(name = "original_on_server") val originalOnServer: Boolean = false,
    /** `nas` or `backup` (ADR 0017 lookup), null when not on the server. */
    @ColumnInfo(name = "original_source") val originalSource: String? = null,
    /** The smart preview blob (SHA-256 hex) the photo record names, null when it has none. */
    @ColumnInfo(name = "preview_sha") val previewSha: String? = null,
    @ColumnInfo(name = "preview_width") val previewWidth: Int? = null,
    @ColumnInfo(name = "preview_height") val previewHeight: Int? = null,
    /** This device made the preview for this version of the main file and uploaded it (or it already existed). */
    @ColumnInfo(name = "preview_fingerprint") val previewFingerprint: String? = null,
    /**
     * The [previewSha]'s format version (`SmartPreviewCodec.VERSION`) when this device uploaded it; null for one
     * another device made, or one 0.5.0-0.5.1 uploaded (format 1, made again lazily, ADR 0018 "Smart previews").
     */
    @ColumnInfo(name = "preview_format") val previewFormat: Int? = null,
    /** The full SHA-256 of the main file (the original's content address), computed before an upload. */
    @ColumnInfo(name = "original_sha") val originalSha: String? = null,
    /** When the server was last asked about the fingerprint (0 = never). */
    @ColumnInfo(name = "checked_at") val checkedAt: Long = 0,
)

/**
 * One blob upload this device owes the server (a smart preview or an original backup), resumable across process
 * deaths (ADR 0017 "Chunked, resumable uploads"): [uploadId] and [offset] are the server's, [file] the local file of a
 * preview (an original is read from its document).
 */
@Entity(
    tableName = "sync_uploads",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["photo_id"]), Index(value = ["state"])],
)
data class SyncUploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    /** `preview` or `original`. */
    val kind: String,
    val sha256: String,
    val size: Long,
    /** Originals: the file's content fingerprint (the server checks it). */
    val fingerprint: String? = null,
    /** Previews: the local file; originals: null (read from the photo's main document). */
    val file: String? = null,
    /** Previews: the pixel size the photo record names. */
    val width: Int? = null,
    val height: Int? = null,
    @ColumnInfo(name = "upload_id") val uploadId: String? = null,
    val offset: Long = 0,
    /** `pending`, `done` or `failed` (gave up: a checksum that never matches, a file that vanished). */
    val state: String = STATE_PENDING,
    val attempts: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
        const val KIND_PREVIEW = "preview"
        const val KIND_ORIGINAL = "original"

        /**
         * A smart preview this device uploaded and no longer needs ([sha256]): dropped from the server once the photo
         * record without it is pushed (`DELETE /v1/blobs/{sha256}`, ADR 0017 "Dropping a smart preview").
         */
        const val KIND_DROP = "drop_preview"
    }
}

/**
 * A server photo record that names a photo this device already holds under another UUID (two devices added the same
 * file before either synced, ADR 0018 "Identity"): versions that point at [remoteUuid] attach to [photoId].
 */
@Entity(
    tableName = "sync_aliases",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["photo_id"])],
)
data class SyncAliasEntity(
    @PrimaryKey @ColumnInfo(name = "remote_uuid") val remoteUuid: String,
    @ColumnInfo(name = "photo_id") val photoId: Long,
)

/**
 * A pulled record this device could not place yet (a version whose photo has not arrived or has no file here yet):
 * kept as the server sent it ([json], one feed record) and applied again after every pull.
 */
@Entity(tableName = "sync_inbox", primaryKeys = ["kind", "record_id"])
data class SyncInboxEntity(
    val kind: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    val rev: Long,
    val json: String,
)

/**
 * An uploaded original the user deleted from the server (removing a folder with "Also delete from the server", or
 * "Delete from server" in the Server scope, ADR 0018 "Deleting from the server"): `DELETE /v1/originals/{fingerprint}`
 * after the photo's tombstone was pushed. Not tied to a photo row: the photo is gone from this device already.
 */
@Entity(tableName = "sync_deletes")
data class SyncDeleteEntity(
    @PrimaryKey val fingerprint: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** `referenced` answers so far (a tombstone not pushed yet); given up after a few. */
    val attempts: Int = 0,
)
