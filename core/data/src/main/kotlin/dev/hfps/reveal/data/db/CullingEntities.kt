package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One culling record of the paired hoard-server (Hoard ADR 0016, Reveal ADR 0027) as this device last pulled or pushed
 * it: per field (`rating`, `flag`, `label`) the server's value, revision and `modifiedAt`. A field never written on
 * the server has a null value and revision 0. Reveal keeps every record the culling feed sends, not only those of
 * photos it has (the mirror is what lets a photo added later find its shot's decisions without asking the server).
 * The record's member files are [CullingFileEntity] rows.
 */
@Entity(tableName = "culling_records")
data class CullingRecordEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    /** The record's newest revision in the feed. */
    val rev: Long = 0,
    /** The live item that took the shot over (a replaced item: no fields of its own any more). */
    @ColumnInfo(name = "replaced_by") val replacedBy: String? = null,
    /** 0..5, null = never written. */
    val rating: Int? = null,
    @ColumnInfo(name = "rating_rev") val ratingRev: Long = 0,
    @ColumnInfo(name = "rating_at") val ratingAt: Long = 0,
    /** A `PickFlag` name (`NONE`, `PICK`, `REJECT`), null = never written. */
    val flag: String? = null,
    @ColumnInfo(name = "flag_rev") val flagRev: Long = 0,
    @ColumnInfo(name = "flag_at") val flagAt: Long = 0,
    /** The colour label, `""` = no label, null = never written. */
    val label: String? = null,
    @ColumnInfo(name = "label_rev") val labelRev: Long = 0,
    @ColumnInfo(name = "label_at") val labelAt: Long = 0,
)

/**
 * A member file of a culling record's item (`fingerprints` of the feed), or the main file this device pushed a
 * change by (the push result names the item): a local photo whose files include [fingerprint] is that shot.
 */
@Entity(
    tableName = "culling_files",
    primaryKeys = ["fingerprint", "item_id"],
    indices = [Index(value = ["item_id"])],
)
data class CullingFileEntity(val fingerprint: String, @ColumnInfo(name = "item_id") val itemId: String)

/**
 * A local culling decision not pushed yet (Hoard ADR 0016 "Client rules" 2), one per photo and field: written by the
 * sync triggers whenever a photo's rating, flag or label changes outside a sync apply, so no write path (library,
 * sidecars, hand-offs) can forget it. The value is the photo's current one when the push is made (decisions on the
 * same field collapse to the last); [modifiedAt] is when it was made (the device's clock, or the sidecar's mtime for
 * an imported sidecar). [state] `invalid` = the server (or the client's own check) refused the value: it stays local
 * and is not pushed again until the server's field moves past [invalidRev]. [blind]: the change was not made looking
 * at the server's value (a sidecar or file imported, a hand-off's dated value, a value set before pairing): it is
 * pushed with `baseRev` 0, so the server decides by time alone, as for its own sidecar import (Hoard ADR 0016).
 */
@Entity(
    tableName = "culling_pending",
    primaryKeys = ["photo_id", "field"],
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["state", "modified_at"])],
)
data class CullingPendingEntity(
    @ColumnInfo(name = "photo_id") val photoId: Long,
    /** `rating`, `flag` or `label`. */
    val field: String,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val state: String = STATE_PENDING,
    @ColumnInfo(name = "invalid_rev", defaultValue = "0") val invalidRev: Long = 0,
    @ColumnInfo(defaultValue = "0") val blind: Boolean = false,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_INVALID = "invalid"
    }
}

/**
 * The culling values a photo's XMP sidecar held the last time Reveal read or wrote it (ADR 0027 "Sidecars"): null =
 * the property was absent. An import applies only the fields that differ from these (Hoard ADR 0016 "When a sidecar
 * is imported"), so a label changed in Lightroom does not re-assert a rating another device changed since.
 */
@Entity(
    tableName = "sidecar_culling",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SidecarCullingEntity(
    @PrimaryKey @ColumnInfo(name = "photo_id") val photoId: Long,
    /** Non-negative `xmp:Rating`; null when absent or a reject (`-1`). */
    val rating: Int? = null,
    /** A `PickFlag` name, null when the sidecar says nothing about the flag. */
    val flag: String? = null,
    /** `xmp:Label`, null when absent. */
    val label: String? = null,
)
