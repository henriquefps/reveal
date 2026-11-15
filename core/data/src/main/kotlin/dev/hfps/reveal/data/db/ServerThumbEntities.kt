package dev.hfps.reveal.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Where a photo's library thumbnail stands (ADR 0018 "W3/C"). */
enum class ServerThumbState {
    /** Stored on this device ([ServerThumbEntity.file]). */
    READY,

    /** The server is making it (asked again after a while). */
    PENDING,

    /** The server has no preview for it (`preview_unavailable`, or the item is gone): the grid reads the file. */
    UNAVAILABLE,
}

/**
 * The library thumbnail of one server file (ADR 0018 "W3/C"): hoard-server's grid thumbnail of the item that has
 * [fingerprint] as a member (Hoard ADR 0015), stored small on this device so the grid works offline. [v] is the
 * server's preview key (a new one = a new thumbnail); [file] the stored file's name in the library thumbnails folder.
 */
@Entity(tableName = "server_thumbs")
data class ServerThumbEntity(
    @PrimaryKey val fingerprint: String,
    @ColumnInfo(name = "item_id") val itemId: String? = null,
    val v: String? = null,
    val state: ServerThumbState,
    val file: String? = null,
    val bytes: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_used") val lastUsed: Long = 0,
)

/** A photo whose library thumbnail is missing, with the server file it is made from. */
data class ThumbCandidate(@ColumnInfo(name = "photo_id") val photoId: Long, val fingerprint: String)

/** The stored library thumbnails as the cache budget sees them. */
data class ServerThumbFile(
    val fingerprint: String,
    val file: String,
    val bytes: Long,
    @ColumnInfo(name = "last_used") val lastUsed: Long,
)

@Dao
interface ServerThumbDao {
    @Query("SELECT * FROM server_thumbs WHERE fingerprint = :fingerprint")
    suspend fun get(fingerprint: String): ServerThumbEntity?

    @Query("SELECT * FROM server_thumbs WHERE fingerprint IN (:fingerprints)")
    suspend fun getAll(fingerprints: List<String>): List<ServerThumbEntity>

    @Upsert
    suspend fun upsert(rows: List<ServerThumbEntity>)

    @Query("UPDATE server_thumbs SET last_used = :at WHERE fingerprint IN (:fingerprints)")
    suspend fun touch(fingerprints: List<String>, at: Long)

    @Query("SELECT fingerprint, file, bytes, last_used FROM server_thumbs WHERE state = 'READY' AND file IS NOT NULL")
    suspend fun stored(): List<ServerThumbFile>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM server_thumbs WHERE state = 'READY'")
    suspend fun storedBytes(): Long

    @Query("SELECT COUNT(*) FROM server_thumbs WHERE state = 'READY'")
    suspend fun storedCount(): Int

    @Query("DELETE FROM server_thumbs WHERE fingerprint IN (:fingerprints)")
    suspend fun delete(fingerprints: List<String>)

    @Query("DELETE FROM server_thumbs")
    suspend fun clear()

    /**
     * Photos of the Server and hand-off sources (the files this device does not hold in a folder) whose server file has
     * no library thumbnail yet, newest shot first: the prefetch's next batch (ADR 0018 "W3/C"). A pending one is asked
     * again once its row is older than [retryBefore]; an unavailable one after [unavailableBefore].
     */
    @Query(
        "SELECT q.photo_id, q.fingerprint FROM (SELECT p.id AS photo_id, COALESCE(p.capture_time, p.added_at) AS shot, " +
            "(SELECT f.fingerprint FROM photo_files f WHERE f.photo_id = p.id AND f.on_server = 1 AND " +
            "f.fingerprint IS NOT NULL ORDER BY CASE f.role WHEN 'RAW' THEN 0 ELSE 1 END, f.id LIMIT 1) AS fingerprint " +
            "FROM photos p JOIN sources s ON s.id = p.source_id WHERE p.hidden = 0 AND s.kind IN ('SERVER', 'HANDOFF')) q " +
            "WHERE q.fingerprint IS NOT NULL AND NOT EXISTS (SELECT 1 FROM server_thumbs t WHERE " +
            "t.fingerprint = q.fingerprint AND (t.state = 'READY' OR (t.state = 'PENDING' AND t.updated_at > :retryBefore) " +
            "OR (t.state = 'UNAVAILABLE' AND t.updated_at > :unavailableBefore))) ORDER BY q.shot DESC LIMIT :limit",
    )
    suspend fun missing(limit: Int, retryBefore: Long, unavailableBefore: Long): List<ThumbCandidate>

    /** How many photos the whole-library thumbnails cover, and how many of them are stored (Settings -> Storage). */
    @Query(
        "SELECT COUNT(DISTINCT f.fingerprint) FROM photos p JOIN sources s ON s.id = p.source_id JOIN photo_files f " +
            "ON f.photo_id = p.id WHERE p.hidden = 0 AND s.kind IN ('SERVER', 'HANDOFF') AND f.on_server = 1 AND " +
            "f.fingerprint IS NOT NULL AND f.role IN ('RAW', 'RENDERED')",
    )
    suspend fun wantedUpperBound(): Int
}
