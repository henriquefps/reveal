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
    fun `every exported schema up to the current version exists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = "dev.hfps.reveal.data.db.RevealDatabase"
        val files = context.assets.list(folder).orEmpty().toSet()
        for (version in 1..RevealDatabase.VERSION) assertThat(files).contains("$version.json")
    }
}
