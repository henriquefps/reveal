package dev.hfps.reveal.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the M0 schema (version 1) with data, migrates it to the current version and checks it with Room. */
@RunWith(AndroidJUnit4::class)
class RevealDatabaseMigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RevealDatabase::class.java)

    @Test
    fun `version 1 migrates to the current schema and keeps its rows`() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO meta (`key`, value) VALUES ('seed', 'm0')")
        }
        helper.runMigrationsAndValidate(name, RevealDatabase.VERSION, true).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, RevealDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertThat(database.metaDao().get("seed")).isEqualTo("m0")
                assertThat(database.sourceDao().all()).isEmpty()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 2 gets the hand-off columns and its folders stay folders`() {
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                "INSERT INTO sources (tree_uri, root_document_id, display_name, state, writable, added_at) " +
                    "VALUES ('content://t', 'r', 'Shoot', 'AVAILABLE', 1, 5)",
            )
        }
        helper.runMigrationsAndValidate(name, 3, true).use { db ->
            db.query("SELECT kind FROM sources").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("FOLDER")
            }
        }
    }

    @Test
    fun `version 3 gets an empty fingerprint column on its files`() {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind) " +
                    "VALUES (1, 'content://t', 'r', 'Shoot', 'AVAILABLE', 1, 5, 'FOLDER')",
            )
            db.execSQL(
                "INSERT INTO photos (id, source_id, folder, folder_document_id, stem_key, name, format, has_raw, " +
                    "sort_time, added_at, rating, flag, has_edit, metadata_state, orientation, hidden) " +
                    "VALUES (1, 1, 'f', 'f', 'dsc1', 'DSC1.ARW', 'RAW', 1, 0, 0, 0, 'NONE', 0, 'PENDING', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO photo_files (photo_id, source_id, relative_path, document_id, name, size, modified, " +
                    "role) VALUES (1, 1, 'f/DSC1.ARW', 'd', 'DSC1.ARW', 10, 20, 'RAW')",
            )
        }
        helper.runMigrationsAndValidate(name, 4, true).use { db ->
            db.query("SELECT name, fingerprint FROM photo_files").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("DSC1.ARW")
                assertThat(c.isNull(1)).isTrue()
            }
        }
    }

    @Test
    fun `every exported schema up to the current version exists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = "dev.hfps.reveal.data.db.RevealDatabase"
        val files = context.assets.list(folder).orEmpty().toSet()
        for (version in 1..RevealDatabase.VERSION) assertThat(files).contains("$version.json")
    }
}
