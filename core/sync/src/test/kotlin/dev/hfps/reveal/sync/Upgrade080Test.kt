package dev.hfps.reveal.sync

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.sync.engine.SyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The W3/C build over a library of a 0.8.0 (database version 13) install that holds hand-offs: the migration to
 * version 14 and the adoption of the hand-offs the server holds, with counts before and after (printed as
 * `UPGRADE080`). The database is created from the exported version 13 schema and filled with the library shapes
 * seen in the field; no real user data.
 */
@RunWith(AndroidJUnit4::class)
class Upgrade080Test {
    private val name = "upgrade-080.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RevealDatabase::class.java)

    private fun SupportSQLiteDatabase.counts(): Map<String, Int> {
        fun count(sql: String) = query(sql).use {
            it.moveToFirst()
            it.getInt(0)
        }
        return linkedMapOf(
            "photos" to count("SELECT COUNT(*) FROM photos"),
            "photos in the hand-off source" to
                count("SELECT COUNT(*) FROM photos p JOIN sources s ON s.id = p.source_id WHERE s.kind = 'HANDOFF'"),
            "photos in the Server source" to
                count("SELECT COUNT(*) FROM photos p JOIN sources s ON s.id = p.source_id WHERE s.kind = 'SERVER'"),
            "photo files" to count("SELECT COUNT(*) FROM photo_files"),
            "files under the Server source" to
                count(
                    "SELECT COUNT(*) FROM photo_files f JOIN sources s ON s.id = f.source_id WHERE s.kind = 'SERVER'",
                ),
            "versions" to count("SELECT COUNT(*) FROM versions"),
            "edits (recipes not default)" to count("SELECT COUNT(*) FROM recipes WHERE is_default = 0"),
            "history steps" to count("SELECT COUNT(*) FROM history"),
            "photos with culling (rating/flag)" to
                count("SELECT COUNT(*) FROM photos WHERE rating > 0 OR flag != 'NONE'"),
            "photos still in 'Opened from other apps'" to
                count("SELECT COUNT(*) FROM photos WHERE handoff_at IS NOT NULL"),
        )
    }

    @Test
    fun `a 0_8_0 library with hand-offs upgrades and adopts without losing a photo, edit or rating`() {
        helper.createDatabase(name, 13).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind) " +
                    "VALUES (1, 'reveal-handoff:', '', 'apps', 'AVAILABLE', 1, 0, 'HANDOFF')",
            )
            fun photo(id: Int, name: String, format: String, raw: Int, rating: Int, flag: String, edit: Int) =
                db.execSQL(
                    "INSERT INTO photos (id, uuid, source_id, folder, folder_document_id, stem_key, name, format, " +
                        "has_raw, sort_time, orientation, rating, flag, has_edit, metadata_state, added_at, hidden, " +
                        "handoff_at) VALUES ($id, 'p$id', 1, 'content://hoard/h/$id', '', '${name.lowercase()}', " +
                        "'$name', '$format', $raw, 1000, 1, $rating, '$flag', $edit, 'READ', 1, 0, 5000)",
                )

            fun file(id: Int, photo: Int, name: String, role: String, print: String?, onServer: Int, uri: String) =
                db.execSQL(
                    "INSERT INTO photo_files (id, photo_id, source_id, relative_path, document_id, name, size, " +
                        "modified, role, fingerprint, on_server) VALUES ($id, $photo, 1, '$uri', '$uri', '$name', " +
                        "100, 1, '$role', ${print?.let { "'$it'" }}, $onServer)",
                )

            fun version(photo: Int, edited: Boolean) {
                db.execSQL(
                    "INSERT INTO versions (id, uuid, photo_id, position, created_at) VALUES ($photo, 'v$photo', " +
                        "$photo, 0, 1)",
                )
                db.execSQL(
                    "INSERT INTO recipes (version_id, json, is_default, updated_at) VALUES ($photo, '{}', " +
                        "${if (edited) 0 else 1}, 1)",
                )
                if (edited) {
                    db.execSQL(
                        "INSERT INTO history (version_id, position, encoding, recipe_json, labels_json, created_at) " +
                            "VALUES ($photo, 0, 'json', '{}', '[]', 1)",
                    )
                }
            }
            // 1: a JPEG, edited and rated, the server holds it.
            photo(1, "IMG1.JPG", "JPEG", 0, 4, "PICK", 1)
            file(1, 1, "IMG1.JPG", "RENDERED", "v1:aa01", 1, "content://hoard/h/1/IMG1.JPG")
            version(1, true)
            // 2 and 3: one file and name handed over twice (a Hoard 1 URI and a Hoard 2 link), fingerprints backfilled.
            photo(2, "DUP.JPG", "JPEG", 0, 0, "NONE", 0)
            file(2, 2, "DUP.JPG", "RENDERED", "v1:aa02", 1, "content://hoard/h/2/DUP.JPG")
            version(2, false)
            photo(3, "DUP.JPG", "JPEG", 0, 0, "NONE", 0)
            file(3, 3, "DUP.JPG", "RENDERED", "v1:aa02", 1, "content://hoard2/link/3/DUP.JPG")
            version(3, false)
            // 4: RAW + JPEG, only the RAW is on the local pinned server; edited and picked.
            photo(4, "PAIR.ARW", "RAW_PAIR", 1, 3, "PICK", 1)
            file(4, 4, "PAIR.ARW", "RAW", "v1:aa04", 1, "content://hoard/h/4/PAIR.ARW")
            file(5, 4, "PAIR.JPG", "RENDERED", "v1:aa05", 0, "content://hoard/h/4/PAIR.JPG")
            version(4, true)
            // 5: rated but never edited, on the server (the culling must survive).
            photo(5, "RATED.JPG", "JPEG", 0, 5, "PICK", 0)
            file(6, 5, "RATED.JPG", "RENDERED", "v1:aa06", 1, "content://hoard/h/5/RATED.JPG")
            version(5, false)
            // 6: not on the server; 7: never read (no fingerprint), unavailable.
            photo(6, "LOCAL.JPG", "JPEG", 0, 2, "NONE", 1)
            file(7, 6, "LOCAL.JPG", "RENDERED", "v1:aa07", 0, "content://hoard/h/6/LOCAL.JPG")
            version(6, true)
            photo(7, "GONE.JPG", "JPEG", 0, 0, "NONE", 0)
            file(8, 7, "GONE.JPG", "RENDERED", null, 0, "content://hoard/h/7/GONE.JPG")
            version(7, false)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = helper.runMigrationsAndValidate(name, 13, false).use { it.counts() }
        // The W3/C build: migration 13 -> 14 (Room validates the schema), then the adoption.
        helper.closeWhenFinished(
            helper.runMigrationsAndValidate(name, RevealDatabase.VERSION, true, *RevealDatabase.MIGRATIONS),
        )
        val database = RevealDatabase.configure(Room.databaseBuilder(context, RevealDatabase::class.java, name))
            .allowMainThreadQueries().build()
        val store = SyncStore(database, emptyList()) { 10_000L }
        val first = runBlocking { store.adoptNext() }
        val second = runBlocking { store.adoptNext() }
        val after = database.openHelper.writableDatabase.counts()
        println("UPGRADE080 adoption: moved=${first.moved} failed=${first.failed}; second pass $second")
        println("UPGRADE080 counts before -> after")
        for ((k, v) in before) println("UPGRADE080   $k: $v -> ${after.getValue(k)}")
        database.close()

        assertThat(first.failed).isEqualTo(0)
        // 1, 2, 3, 4 and 5 move; 6 (not on the server) and 7 (never read) stay.
        assertThat(first.moved).isEqualTo(5)
        assertThat(second).isEqualTo(SyncStore.Adopted(0, 0))
        for (key in listOf("photos", "photo files", "versions", "edits (recipes not default)", "history steps")) {
            assertThat(after.getValue(key)).isEqualTo(before.getValue(key))
        }
        assertThat(after.getValue("photos with culling (rating/flag)"))
            .isEqualTo(before.getValue("photos with culling (rating/flag)"))
        assertThat(after.getValue("photos still in 'Opened from other apps'"))
            .isEqualTo(before.getValue("photos still in 'Opened from other apps'"))
        assertThat(after.getValue("photos in the Server source")).isEqualTo(5)
        assertThat(after.getValue("photos in the hand-off source")).isEqualTo(2)
        // The pair's JPEG (not on the server) is untouched: one file under the Server source per held file.
        assertThat(after.getValue("files under the Server source")).isEqualTo(5)
    }
}
