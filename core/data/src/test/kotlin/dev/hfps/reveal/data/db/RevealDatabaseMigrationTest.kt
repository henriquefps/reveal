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
        helper.runMigrationsAndValidate(name, RevealDatabase.VERSION, true, *RevealDatabase.MIGRATIONS).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, RevealDatabase::class.java, name)
            .addMigrations(*RevealDatabase.MIGRATIONS).allowMainThreadQueries().build()
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
    fun `version 4 gets a distinct stable uuid on every photo and version, and new rows get one too`() {
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind) " +
                    "VALUES (1, 'content://t', 'r', 'Shoot', 'AVAILABLE', 1, 5, 'FOLDER')",
            )
            for (id in 1..3) {
                db.execSQL(
                    "INSERT INTO photos (id, source_id, folder, folder_document_id, stem_key, name, format, " +
                        "has_raw, sort_time, added_at, rating, flag, has_edit, metadata_state, orientation, hidden) " +
                        "VALUES ($id, 1, 'f', 'f', 'dsc$id', 'DSC$id.ARW', 'RAW', 1, 0, 0, 0, 'NONE', 0, 'PENDING', " +
                        "1, 0)",
                )
                db.execSQL("INSERT INTO versions (id, photo_id, position, created_at) VALUES ($id, $id, 0, 0)")
            }
        }
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val before = HashMap<String, List<String>>()
        helper.runMigrationsAndValidate(name, 5, true, RevealDatabase.MIGRATION_4_5).use { db ->
            for (table in listOf("photos", "versions")) {
                val ids = db.query("SELECT uuid FROM $table ORDER BY id").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                assertThat(ids).hasSize(3)
                assertThat(ids.toSet()).hasSize(3)
                ids.forEach { assertThat(it).matches(uuid.pattern) }
                before[table] = ids
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, RevealDatabase::class.java, name)
            .addMigrations(*RevealDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            runBlocking {
                val photos = database.photoDao()
                // Stable: reading again gives the same ids; a new row gets its own.
                assertThat(photos.getAll(listOf(1L, 2L, 3L)).sortedBy { it.id }.map { it.uuid })
                    .isEqualTo(before["photos"])
                val id = photos.insertPhoto(
                    PhotoEntity(
                        sourceId = 1, folder = "f", folderDocumentId = "f", stemKey = "dsc9", name = "DSC9.ARW",
                        format = dev.hfps.reveal.data.model.PhotoFormat.RAW, hasRaw = true, sortTime = 0, addedAt = 0,
                    ),
                )
                val fresh = checkNotNull(photos.get(id)).uuid
                assertThat(fresh).matches(uuid.pattern)
                assertThat(before.getValue("photos")).doesNotContain(fresh)
                val version = photos.insertVersion(VersionEntity(photoId = id, createdAt = 0))
                database.query("SELECT uuid FROM versions WHERE id = ?", arrayOf(version)).use { c ->
                    assertThat(c.moveToFirst()).isTrue()
                    assertThat(c.getString(0)).matches(uuid.pattern)
                }
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 5 gets the sync tables and columns, and its photos are tracked from then on`() {
        helper.createDatabase(name, 5).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind) " +
                    "VALUES (1, 'content://t', 'r', 'Shoot', 'AVAILABLE', 1, 5, 'FOLDER')",
            )
            db.execSQL(
                "INSERT INTO photos (id, uuid, source_id, folder, folder_document_id, stem_key, name, format, " +
                    "has_raw, sort_time, added_at, rating, flag, has_edit, metadata_state, orientation, hidden) " +
                    "VALUES (1, 'u1', 1, 'f', 'f', 'dsc1', 'DSC1.ARW', 'RAW', 1, 0, 0, 0, 'NONE', 0, 'PENDING', 1, 0)",
            )
            db.execSQL("INSERT INTO versions (id, uuid, photo_id, position, created_at) VALUES (1, 'v1', 1, 0, 0)")
        }
        helper.runMigrationsAndValidate(name, 6, true).use { db ->
            db.query("SELECT backup FROM sources").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            db.query("SELECT copy_of FROM versions").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
            db.query("SELECT COUNT(*) FROM sync_records").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = RevealDatabase.configure(Room.databaseBuilder(context, RevealDatabase::class.java, name))
            .allowMainThreadQueries().build()
        try {
            runBlocking {
                database.photoDao().setRating(listOf(1L), 3)
                val record = database.syncDao().record("photo", "u1")
                assertThat(record).isNotNull()
                assertThat(record!!.dirty).isTrue()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 6 gets the empty preset tables and keeps its library`() {
        helper.createDatabase(name, 6).use { db ->
            db.execSQL("INSERT INTO meta (`key`, value) VALUES ('seed', 'v6')")
        }
        helper.runMigrationsAndValidate(name, 7, true).use { db ->
            for (table in listOf("preset_packs", "preset_groups", "presets", "preset_marks")) {
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    assertThat(c.moveToFirst()).isTrue()
                    assertThat(c.getInt(0)).isEqualTo(0)
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = RevealDatabase.configure(Room.databaseBuilder(context, RevealDatabase::class.java, name))
            .allowMainThreadQueries().build()
        try {
            runBlocking {
                assertThat(database.metaDao().get("seed")).isEqualTo("v6")
                val group = database.presetDao().insertGroup(
                    PresetGroupEntity(name = "Mine", position = 0, createdAt = 1),
                )
                database.presetDao().insertPreset(
                    PresetEntity(
                        uuid = "0f8fad5b-d9cb-469f-a165-70867728950e", groupId = group, name = "P", author = "",
                        version = 1, createdAt = 1, updatedAt = 1, position = 0, patchJson = "{}", smartJson = null,
                        origin = "USER",
                    ),
                )
                val record = database.syncDao().record("preset", "0f8fad5b-d9cb-469f-a165-70867728950e")
                assertThat(record!!.dirty).isTrue()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 7 gets an empty unreadable mark on its files`() {
        helper.createDatabase(name, 7).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind, " +
                    "backup) VALUES (1, 'reveal-handoff:', '', 'h', 'AVAILABLE', 1, 0, 'HANDOFF', 1)",
            )
            db.execSQL(
                "INSERT INTO photos (id, uuid, source_id, folder, folder_document_id, stem_key, name, format, " +
                    "has_raw, sort_time, orientation, rating, flag, has_edit, metadata_state, added_at, hidden) " +
                    "VALUES " +
                    "(1, 'u1', 1, 'f', '', 'a', 'A.JPG', 'JPEG', 0, 0, 1, 3, 'PICK', 0, 'READ', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO photo_files (id, photo_id, source_id, relative_path, document_id, name, size, modified, " +
                    "role, fingerprint) VALUES (1, 1, 1, 'content://media/1', 'content://media/1', 'A.JPG', 9, 9, " +
                    "'RENDERED', 'v1:aa')",
            )
        }
        helper.runMigrationsAndValidate(name, 8, true).use { db ->
            db.query("SELECT fingerprint, unreadable_at FROM photo_files").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("v1:aa")
                assertThat(c.isNull(1)).isTrue()
            }
        }
    }

    @Test
    fun `version 8 keeps its previews with an unknown format (made again lazily)`() {
        helper.createDatabase(name, 8).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind, " +
                    "backup) VALUES (1, 'content://t', 'r', 'DCIM', 'AVAILABLE', 0, 0, 'FOLDER', 1)",
            )
            db.execSQL(
                "INSERT INTO photos (id, uuid, source_id, folder, folder_document_id, stem_key, name, format, " +
                    "has_raw, sort_time, orientation, rating, flag, has_edit, metadata_state, added_at, hidden) " +
                    "VALUES (1, 'u1', 1, 'f', '', 'a', 'A.ARW', 'RAW', 1, 0, 1, 0, 'NONE', 1, 'READ', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO photo_cloud (photo_id, original_on_server, preview_sha, preview_width, preview_height, " +
                    "preview_fingerprint, checked_at) VALUES (1, 0, 'abc', 2560, 1707, 'v1:aa', 5)",
            )
        }
        helper.runMigrationsAndValidate(name, 9, true).use { db ->
            db.query("SELECT preview_sha, preview_fingerprint, preview_format FROM photo_cloud").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(0)).isEqualTo("abc")
                assertThat(c.getString(1)).isEqualTo("v1:aa")
                assertThat(c.isNull(2)).isTrue()
            }
        }
    }

    @Test
    fun `9 to 10 marks no file as on the server and adds an empty delete queue`() {
        val name = "migration-9-10.db"
        helper.createDatabase(name, 9).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind, " +
                    "backup) VALUES (1, 'content://t', 'r', 'DCIM', 'AVAILABLE', 0, 0, 'FOLDER', 1)",
            )
            db.execSQL(
                "INSERT INTO photos (id, uuid, source_id, folder, folder_document_id, stem_key, name, format, " +
                    "has_raw, sort_time, orientation, rating, flag, has_edit, metadata_state, added_at, hidden) " +
                    "VALUES (1, 'u1', 1, 'f', '', 'a', 'A.ARW', 'RAW', 1, 0, 1, 0, 'NONE', 0, 'READ', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO photo_files (id, photo_id, source_id, relative_path, document_id, name, size, modified, " +
                    "role, fingerprint) VALUES (1, 1, 1, 'A.ARW', 'A.ARW', 'A.ARW', 9, 9, 'RAW', 'v1:aa')",
            )
        }
        helper.runMigrationsAndValidate(name, 10, true).use { db ->
            db.query("SELECT on_server FROM photo_files").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
            db.query("SELECT COUNT(*) FROM sync_deletes").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
            db.query("SELECT backup FROM sources").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1) // existing folders keep their setting
            }
        }
    }

    @Test
    fun `10 to 11 re-reads Server photos, backs up only edited hand-offs and adds unknown editors`() {
        val name = "migration-10-11.db"
        helper.createDatabase(name, 10).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind, " +
                    "backup) VALUES (1, 'content://t', 'r', 'DCIM', 'AVAILABLE', 0, 0, 'FOLDER', 1), " +
                    "(2, 'reveal-handoff:', '', 'apps', 'AVAILABLE', 1, 0, 'HANDOFF', 1), " +
                    "(3, 'reveal-sync:server', '', 'Server', 'AVAILABLE', 0, 0, 'SERVER', 1)",
            )
            for ((id, source) in listOf(1 to 1, 2 to 3)) {
                db.execSQL(
                    "INSERT INTO photos (id, uuid, source_id, folder, folder_document_id, stem_key, name, format, " +
                        "has_raw, sort_time, orientation, rating, flag, has_edit, metadata_state, added_at, hidden) " +
                        "VALUES ($id, 'u$id', $source, 'f', '', 'a$id', 'A.ARW', 'RAW', 1, 0, 1, 0, 'NONE', 0, " +
                        "'READ', 0, 0)",
                )
                db.execSQL(
                    "INSERT INTO versions (id, uuid, photo_id, position, created_at) VALUES ($id, 'v$id', $id, 0, 0)",
                )
            }
        }
        helper.runMigrationsAndValidate(name, 11, true, RevealDatabase.MIGRATION_10_11).use { db ->
            db.query("SELECT id, metadata_state FROM photos ORDER BY id").use { c ->
                val states = buildList { while (c.moveToNext()) add(c.getString(1)) }
                assertThat(states).containsExactly("READ", "PENDING").inOrder()
            }
            db.query("SELECT kind, backup FROM sources ORDER BY id").use { c ->
                val backup = buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
                assertThat(backup).containsExactly("FOLDER", 1, "HANDOFF", 0, "SERVER", 1)
            }
            db.query("SELECT edit_device, edited_at FROM versions").use { c ->
                while (c.moveToNext()) {
                    assertThat(c.isNull(0)).isTrue()
                    assertThat(c.isNull(1)).isTrue()
                }
            }
        }
    }

    @Test
    fun `11 to 12 adds the empty snapshot and export preset tables and keeps the versions`() {
        val name = "migration-11-12.db"
        helper.createDatabase(name, 11).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, tree_uri, root_document_id, display_name, state, writable, added_at, kind, " +
                    "backup) VALUES (1, 'content://t', 'r', 'DCIM', 'AVAILABLE', 0, 0, 'FOLDER', 1)",
            )
            db.execSQL(
                "INSERT INTO photos (id, uuid, source_id, folder, folder_document_id, stem_key, name, format, " +
                    "has_raw, sort_time, orientation, rating, flag, has_edit, metadata_state, added_at, hidden) " +
                    "VALUES (1, 'u1', 1, 'f', '', 'a', 'A.ARW', 'RAW', 1, 0, 1, 0, 'NONE', 0, 'READ', 0, 0)",
            )
            db.execSQL("INSERT INTO versions (id, uuid, photo_id, position, created_at) VALUES (1, 'v1', 1, 0, 0)")
        }
        helper.runMigrationsAndValidate(name, 12, true, *RevealDatabase.MIGRATIONS).use { db ->
            for (table in listOf("snapshots", "export_presets")) {
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    assertThat(c.moveToFirst()).isTrue()
                    assertThat(c.getInt(0)).isEqualTo(0)
                }
            }
            db.execSQL(
                "INSERT INTO snapshots (uuid, version_id, name, recipe_json, created_at) " +
                    "VALUES ('s1', 1, 'A', '{}', 0)",
            )
            db.query("SELECT COUNT(*) FROM versions").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
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
