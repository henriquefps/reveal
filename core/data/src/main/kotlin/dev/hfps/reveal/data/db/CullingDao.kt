package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.hfps.reveal.data.model.PickFlag
import kotlinx.coroutines.flow.Flow

/** A pending culling change ready to push: the photo's current value of [field] and its main file's fingerprint. */
data class PendingCulling(
    @ColumnInfo(name = "photo_id") val photoId: Long,
    val field: String,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val rating: Int,
    val flag: PickFlag,
    val label: String?,
    val fingerprint: String,
    val blind: Boolean,
)

/** A photo whose [field] differs from its shot's culling record, and the record's value ([value] as text). */
data class StaleCulling(@ColumnInfo(name = "photo_id") val photoId: Long, val value: String)

/**
 * The culling side of sync (ADR 0027) and of XMP sidecars: the mirror of the server's culling records
 * ([CullingRecordEntity], [CullingFileEntity]), the queue of local decisions ([CullingPendingEntity]) and what each
 * sidecar held when last read ([SidecarCullingEntity]).
 *
 * A photo maps to a record when **any** of its files has a fingerprint among the record's member files (Hoard ADR 0016
 * "Mapping a record"); replaced records never map (their shot's fields are on the live item's record).
 */
@Dao
@Suppress("TooManyFunctions")
interface CullingDao {
    // ---- records

    @Query("SELECT * FROM culling_records WHERE item_id = :itemId")
    suspend fun record(itemId: String): CullingRecordEntity?

    @Upsert
    suspend fun putRecord(record: CullingRecordEntity)

    @Query("SELECT item_id FROM culling_records WHERE replaced_by = :itemId")
    suspend fun replacedInto(itemId: String): List<String>

    @Query("DELETE FROM culling_files WHERE item_id = :itemId")
    suspend fun clearFiles(itemId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFiles(files: List<CullingFileEntity>)

    @Query("SELECT fingerprint FROM culling_files WHERE item_id = :itemId")
    suspend fun filesOf(itemId: String): List<String>

    /** The live record [photoId]'s shot maps to (the newest when its files are in several), or null. */
    @Query(
        "SELECT r.* FROM culling_records r WHERE r.replaced_by IS NULL AND r.item_id IN " +
            "(SELECT cf.item_id FROM culling_files cf JOIN photo_files f ON f.fingerprint = cf.fingerprint " +
            "WHERE f.photo_id = :photoId) ORDER BY r.rev DESC LIMIT 1",
    )
    suspend fun recordOf(photoId: Long): CullingRecordEntity?

    /** Live records no local file names yet: shots the server does not index (their id is derived, Hoard ADR 0014). */
    @Query(
        "SELECT item_id FROM culling_records r WHERE r.replaced_by IS NULL AND NOT EXISTS " +
            "(SELECT 1 FROM culling_files cf WHERE cf.item_id = r.item_id)",
    )
    suspend fun orphanItems(): List<String>

    /** The main files' fingerprints of every local photo (the derived-id candidates). */
    @Query(
        "SELECT DISTINCT fingerprint FROM photo_files WHERE fingerprint IS NOT NULL AND role IN ('RAW', 'RENDERED')",
    )
    suspend fun mainFingerprints(): List<String>

    @Query("SELECT COUNT(*) FROM culling_records")
    suspend fun recordCount(): Int

    @Query("DELETE FROM culling_records")
    suspend fun clearRecords()

    @Query("DELETE FROM culling_files")
    suspend fun clearAllFiles()

    // ---- the queue of local decisions

    /**
     * Up to [limit] pending changes, oldest first, of photos whose main file has a fingerprint (the shot is named by
     * it). The value is read from the photo now: decisions made since the change was queued collapse into it.
     */
    @Query(
        "SELECT * FROM (SELECT c.photo_id, c.field, c.modified_at, c.blind, p.rating, p.flag, p.label, " +
            "(SELECT f.fingerprint FROM photo_files f " +
            "WHERE f.photo_id = c.photo_id AND f.role IN ('RAW', 'RENDERED') " +
            "ORDER BY CASE f.role WHEN 'RAW' THEN 0 ELSE 1 END, f.id LIMIT 1) AS fingerprint " +
            "FROM culling_pending c JOIN photos p ON p.id = c.photo_id WHERE c.state = 'pending') " +
            "WHERE fingerprint IS NOT NULL ORDER BY modified_at, photo_id, field LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<PendingCulling>

    @Query("SELECT * FROM culling_pending WHERE photo_id = :photoId ORDER BY field")
    suspend fun pendingOf(photoId: Long): List<CullingPendingEntity>

    @Query("SELECT COUNT(*) FROM culling_pending WHERE state = 'pending'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM culling_pending WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>

    /** The change was pushed; removed unless the user changed the field again meanwhile (a newer [modifiedAt]). */
    @Query(
        "DELETE FROM culling_pending WHERE photo_id = :photoId AND field = :field AND state = 'pending' " +
            "AND modified_at = :modifiedAt",
    )
    suspend fun pushed(photoId: Long, field: String, modifiedAt: Long): Int

    /** The value can never apply as sent: kept local, not pushed again until the server's field moves past [rev]. */
    @Query(
        "UPDATE culling_pending SET state = 'invalid', invalid_rev = :rev WHERE photo_id = :photoId " +
            "AND field = :field AND state = 'pending' AND modified_at = :modifiedAt",
    )
    suspend fun refused(photoId: Long, field: String, modifiedAt: Long, rev: Long): Int

    /**
     * Dates queued [fields] of [photoId] at [modifiedAt] (an imported sidecar's mtime, a hand-off's time): a blind
     * change, decided against the server's value by time alone.
     */
    @Query(
        "UPDATE culling_pending SET modified_at = :modifiedAt, blind = 1 WHERE photo_id = :photoId " +
            "AND field IN (:fields) AND state = 'pending'",
    )
    suspend fun date(photoId: Long, fields: List<String>, modifiedAt: Long)

    @Query("DELETE FROM culling_pending WHERE photo_id = :photoId AND field = :field AND state = 'invalid'")
    suspend fun dropRefused(photoId: Long, field: String)

    @Query("DELETE FROM culling_pending WHERE photo_id IN (:photoIds) AND field = :field AND state = 'pending'")
    suspend fun drop(photoIds: List<Long>, field: String)

    @Query("UPDATE culling_pending SET state = 'pending', invalid_rev = 0 WHERE state = 'invalid'")
    suspend fun retryRefused()

    /**
     * Queues every local, non-default decision not queued yet (a first culling sync with a server): the bridge of
     * Hoard ADR 0016 ignores culling-aware devices, so values set before pairing (or before this version) go up
     * per field. Dated by the sidecar's mtime, else when the photo was added: older than any decision the server may
     * have, so a newer one there wins. Server photos (other devices' values) are not this device's decisions.
     */
    @Query(
        "INSERT INTO culling_pending (photo_id, field, modified_at, state, invalid_rev, blind) " +
            "SELECT p.id, 'rating', MAX(1, COALESCE(p.xmp_modified, p.added_at)), 'pending', 0, 1 FROM photos p " +
            "WHERE p.rating <> 0 AND p.source_id NOT IN (SELECT id FROM sources WHERE kind = 'SERVER') " +
            "AND NOT EXISTS (SELECT 1 FROM culling_pending c WHERE c.photo_id = p.id AND c.field = 'rating')",
    )
    suspend fun seedRatings()

    @Query(
        "INSERT INTO culling_pending (photo_id, field, modified_at, state, invalid_rev, blind) " +
            "SELECT p.id, 'flag', MAX(1, COALESCE(p.xmp_modified, p.added_at)), 'pending', 0, 1 FROM photos p " +
            "WHERE p.flag <> 'NONE' AND p.source_id NOT IN (SELECT id FROM sources WHERE kind = 'SERVER') " +
            "AND NOT EXISTS (SELECT 1 FROM culling_pending c WHERE c.photo_id = p.id AND c.field = 'flag')",
    )
    suspend fun seedFlags()

    @Query(
        "INSERT INTO culling_pending (photo_id, field, modified_at, state, invalid_rev, blind) " +
            "SELECT p.id, 'label', MAX(1, COALESCE(p.xmp_modified, p.added_at)), 'pending', 0, 1 FROM photos p " +
            "WHERE p.label IS NOT NULL AND TRIM(p.label) <> '' " +
            "AND p.source_id NOT IN (SELECT id FROM sources WHERE kind = 'SERVER') " +
            "AND NOT EXISTS (SELECT 1 FROM culling_pending c WHERE c.photo_id = p.id AND c.field = 'label')",
    )
    suspend fun seedLabels()

    // ---- reconciling photos with the records (Hoard ADR 0016 "Client rules" 3)

    /** Photos whose rating differs from their record's, with no local change of it pending (or refused at that rev). */
    @Query(
        "SELECT DISTINCT ph.id AS photo_id, CAST(r.rating AS TEXT) AS value FROM photos ph " +
            "JOIN photo_files f ON f.photo_id = ph.id JOIN culling_files cf ON cf.fingerprint = f.fingerprint " +
            "JOIN culling_records r ON r.item_id = cf.item_id " +
            "WHERE r.replaced_by IS NULL AND r.rating IS NOT NULL AND ph.rating <> r.rating AND NOT EXISTS " +
            "(SELECT 1 FROM culling_pending c WHERE c.photo_id = ph.id AND c.field = 'rating' " +
            "AND (c.state = 'pending' OR c.invalid_rev = r.rating_rev))",
    )
    suspend fun staleRatings(): List<StaleCulling>

    @Query(
        "SELECT DISTINCT ph.id AS photo_id, r.flag AS value FROM photos ph " +
            "JOIN photo_files f ON f.photo_id = ph.id JOIN culling_files cf ON cf.fingerprint = f.fingerprint " +
            "JOIN culling_records r ON r.item_id = cf.item_id " +
            "WHERE r.replaced_by IS NULL AND r.flag IS NOT NULL AND ph.flag <> r.flag AND NOT EXISTS " +
            "(SELECT 1 FROM culling_pending c WHERE c.photo_id = ph.id AND c.field = 'flag' " +
            "AND (c.state = 'pending' OR c.invalid_rev = r.flag_rev))",
    )
    suspend fun staleFlags(): List<StaleCulling>

    @Query(
        "SELECT DISTINCT ph.id AS photo_id, r.label AS value FROM photos ph " +
            "JOIN photo_files f ON f.photo_id = ph.id JOIN culling_files cf ON cf.fingerprint = f.fingerprint " +
            "JOIN culling_records r ON r.item_id = cf.item_id " +
            "WHERE r.replaced_by IS NULL AND r.label IS NOT NULL AND TRIM(COALESCE(ph.label, '')) <> r.label " +
            "AND NOT EXISTS (SELECT 1 FROM culling_pending c WHERE c.photo_id = ph.id AND c.field = 'label' " +
            "AND (c.state = 'pending' OR c.invalid_rev = r.label_rev))",
    )
    suspend fun staleLabels(): List<StaleCulling>

    @Query("UPDATE photos SET rating = :rating WHERE id = :photoId")
    suspend fun setRating(photoId: Long, rating: Int)

    @Query("UPDATE photos SET flag = :flag WHERE id = :photoId")
    suspend fun setFlag(photoId: Long, flag: PickFlag)

    @Query("UPDATE photos SET label = :label WHERE id = :photoId")
    suspend fun setLabel(photoId: Long, label: String?)

    // ---- hand-offs (Hoard ADR 0016 "Client rules" 6, Reveal ADR 0027)

    /** Drops queued rating changes of [photoIds] whose value is the server's already (a hand-off's cached value). */
    @Query(
        "DELETE FROM culling_pending WHERE field = 'rating' AND state = 'pending' AND photo_id IN (:photoIds) " +
            "AND EXISTS (SELECT 1 FROM photos ph JOIN photo_files f ON f.photo_id = ph.id " +
            "JOIN culling_files cf ON cf.fingerprint = f.fingerprint " +
            "JOIN culling_records r ON r.item_id = cf.item_id " +
            "WHERE ph.id = culling_pending.photo_id AND r.replaced_by IS NULL AND r.rating = ph.rating)",
    )
    suspend fun settleRatings(photoIds: List<Long>)

    @Query(
        "DELETE FROM culling_pending WHERE field = 'flag' AND state = 'pending' AND photo_id IN (:photoIds) " +
            "AND EXISTS (SELECT 1 FROM photos ph JOIN photo_files f ON f.photo_id = ph.id " +
            "JOIN culling_files cf ON cf.fingerprint = f.fingerprint " +
            "JOIN culling_records r ON r.item_id = cf.item_id " +
            "WHERE ph.id = culling_pending.photo_id AND r.replaced_by IS NULL AND r.flag = ph.flag)",
    )
    suspend fun settleFlags(photoIds: List<Long>)

    @Query(
        "DELETE FROM culling_pending WHERE field = 'label' AND state = 'pending' AND photo_id IN (:photoIds) " +
            "AND EXISTS (SELECT 1 FROM photos ph JOIN photo_files f ON f.photo_id = ph.id " +
            "JOIN culling_files cf ON cf.fingerprint = f.fingerprint " +
            "JOIN culling_records r ON r.item_id = cf.item_id " +
            "WHERE ph.id = culling_pending.photo_id AND r.replaced_by IS NULL " +
            "AND r.label = TRIM(COALESCE(ph.label, '')))",
    )
    suspend fun settleLabels(photoIds: List<Long>)

    // ---- sidecars

    @Query("SELECT * FROM sidecar_culling WHERE photo_id = :photoId")
    suspend fun sidecar(photoId: Long): SidecarCullingEntity?

    @Upsert
    suspend fun putSidecar(entity: SidecarCullingEntity)

    @Query("DELETE FROM sidecar_culling WHERE photo_id = :photoId")
    suspend fun forgetSidecar(photoId: Long)
}
