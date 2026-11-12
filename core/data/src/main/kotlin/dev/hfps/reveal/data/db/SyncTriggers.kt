package dev.hfps.reveal.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQLite triggers that mark synced records dirty whenever the library changes them (ADR 0018 "Change tracking"), so no
 * write path (editor, library, XMP sidecars, hand-offs, indexing) can forget to: a photo's metadata or files change ->
 * its `photo` record; a version, its recipe or its history cursor change -> its `version` record; a preset, its
 * group (name, order) or its pack (kept after a trial) change -> its `preset` record, and a deleted preset becomes a
 * tombstone (docs/adr/0019 "Sync"). While the sync
 * engine applies pulled records it sets the `meta` key [APPLYING] in the same transaction, and the triggers stay quiet.
 *
 * Triggers are not part of Room's schema, so they are (re)created on every open ([callback]); the names carry a
 * version, and [create] drops the previous version's. SQLite 3.22 (API 29) has no UPSERT, hence a guarded `INSERT` +
 * `UPDATE`.
 */
object SyncTriggers {
    /** The `meta` key that silences the triggers while pulled records are applied. */
    const val APPLYING = "sync.applying"

    private const val VERSION = 2

    /** Unix milliseconds in SQL. */
    private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private const val QUIET = "NOT EXISTS (SELECT 1 FROM meta WHERE `key` = '$APPLYING')"

    /**
     * Inserts the record when missing, then marks it. No `OR IGNORE`: inside a trigger the outer statement's
     * conflict clause wins (Room writes `INSERT OR ABORT`), so the insert must not conflict at all. A NULL id (the
     * uuid lookup of a row being cascade-deleted) marks nothing.
     */
    private fun mark(kind: String, id: String): String =
        "INSERT INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT '$kind', $id, 0, 1, $NOW, 0 WHERE $id IS NOT NULL AND NOT EXISTS " +
            "(SELECT 1 FROM sync_records WHERE kind = '$kind' AND record_id = $id); " +
            "UPDATE sync_records SET dirty = 1, deleted = 0, modified_at = $NOW " +
            "WHERE kind = '$kind' AND record_id = $id;"

    /** Marks the record deleted (a tombstone to push). */
    private fun markDeleted(kind: String, id: String): String =
        "INSERT INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT '$kind', $id, 0, 1, $NOW, 1 WHERE $id IS NOT NULL AND NOT EXISTS " +
            "(SELECT 1 FROM sync_records WHERE kind = '$kind' AND record_id = $id); " +
            "UPDATE sync_records SET dirty = 1, deleted = 1, modified_at = $NOW " +
            "WHERE kind = '$kind' AND record_id = $id;"

    /** Marks every preset whose row matches [where] (a condition on `presets`). */
    private fun markPresets(where: String): String =
        "INSERT INTO sync_records (kind, record_id, rev, dirty, modified_at, deleted) " +
            "SELECT 'preset', uuid, 0, 1, $NOW, 0 FROM presets WHERE ($where) AND uuid NOT IN " +
            "(SELECT record_id FROM sync_records WHERE kind = 'preset'); " +
            "UPDATE sync_records SET dirty = 1, deleted = 0, modified_at = $NOW " +
            "WHERE kind = 'preset' AND record_id IN (SELECT uuid FROM presets WHERE $where);"

    private val photoOf = { photoId: String -> "(SELECT uuid FROM photos WHERE id = $photoId)" }
    private val versionOf = { versionId: String -> "(SELECT uuid FROM versions WHERE id = $versionId)" }

    /** name -> `AFTER ... ON ... ` clause and the record it marks. */
    private val TRIGGERS = listOf(
        Triple("photo_insert", "AFTER INSERT ON photos", mark("photo", "NEW.uuid")),
        Triple(
            "photo_update",
            "AFTER UPDATE OF rating, flag, label, hidden, capture_time, width, height, name, format ON photos",
            mark("photo", "NEW.uuid"),
        ),
        Triple("file_insert", "AFTER INSERT ON photo_files", mark("photo", photoOf("NEW.photo_id"))),
        Triple("file_print", "AFTER UPDATE OF fingerprint ON photo_files", mark("photo", photoOf("NEW.photo_id"))),
        Triple("file_delete", "AFTER DELETE ON photo_files", mark("photo", photoOf("OLD.photo_id"))),
        Triple("version_insert", "AFTER INSERT ON versions", mark("version", "NEW.uuid")),
        Triple("version_update", "AFTER UPDATE OF position, name ON versions", mark("version", "NEW.uuid")),
        Triple("recipe_insert", "AFTER INSERT ON recipes", mark("version", versionOf("NEW.version_id"))),
        Triple("recipe_update", "AFTER UPDATE ON recipes", mark("version", versionOf("NEW.version_id"))),
        Triple("cursor_insert", "AFTER INSERT ON history_cursor", mark("version", versionOf("NEW.version_id"))),
        Triple("cursor_update", "AFTER UPDATE ON history_cursor", mark("version", versionOf("NEW.version_id"))),
        Triple("preset_insert", "AFTER INSERT ON presets", mark("preset", "NEW.uuid")),
        Triple("preset_update", "AFTER UPDATE ON presets", mark("preset", "NEW.uuid")),
        Triple("preset_delete", "AFTER DELETE ON presets", markDeleted("preset", "OLD.uuid")),
        Triple(
            "preset_group_update",
            "AFTER UPDATE OF name, position ON preset_groups",
            markPresets("group_id = NEW.id"),
        ),
        Triple("preset_mark_insert", "AFTER INSERT ON preset_marks", markPresets("uuid = NEW.uuid")),
        Triple("preset_mark_update", "AFTER UPDATE OF favourite ON preset_marks", markPresets("uuid = NEW.uuid")),
        Triple(
            "preset_pack_update",
            "AFTER UPDATE OF trial ON preset_packs",
            markPresets("group_id IN (SELECT id FROM preset_groups WHERE pack_id = NEW.id)"),
        ),
    )

    /** Creates the triggers (idempotent) and drops those of older trigger versions. */
    fun create(db: SupportSQLiteDatabase) {
        for (old in 0 until VERSION) {
            for ((name, _, _) in TRIGGERS) db.execSQL("DROP TRIGGER IF EXISTS sync${old}_$name")
        }
        for ((name, event, body) in TRIGGERS) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync${VERSION}_$name $event FOR EACH ROW WHEN $QUIET " +
                    "BEGIN $body END",
            )
        }
    }

    /** Creates the triggers on every open of the database. */
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) = create(db)
    }
}
