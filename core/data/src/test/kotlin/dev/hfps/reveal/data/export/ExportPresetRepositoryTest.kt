package dev.hfps.reveal.data.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SyncTriggers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** User export presets (docs/adr/0025): order, rename, update, delete, and the sync records the triggers keep. */
@RunWith(AndroidJUnit4::class)
class ExportPresetRepositoryTest {
    private lateinit var db: RevealDatabase
    private lateinit var presets: ExportPresetRepository

    @Before
    fun setUp() {
        db = RevealDatabase.configure(
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            ),
        ).allowMainThreadQueries().build()
        presets = ExportPresetRepository(db) { 5L }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun record(uuid: String) = db.syncDao().record("setting", SyncTriggers.EXPORT_PRESET_PREFIX + uuid)

    @Test
    fun `presets keep their order, names and options`() = runTest {
        val a = presets.create("  Prints ", """{"quality":100}""")
        val b = presets.create("Phone", """{"quality":80}""")
        assertThat(presets.observe().first().map { it.name }).containsExactly("Prints", "Phone").inOrder()
        presets.update(a, name = "Large prints")
        presets.update(b, optionsJson = """{"quality":70}""")
        presets.update(b, name = "   ")
        val all = presets.all()
        assertThat(all.map { it.name }).containsExactly("Large prints", "Phone").inOrder()
        assertThat(all.last().optionsJson).isEqualTo("""{"quality":70}""")
        assertThat(all.map { it.position }).containsExactly(0, 1).inOrder()
        presets.delete(a)
        assertThat(presets.all().map { it.uuid }).containsExactly(b)
    }

    @Test
    fun `every change marks the preset's setting record, a delete makes a tombstone`() = runTest {
        val uuid = presets.create("Web", "{}")
        assertThat(record(uuid)!!.dirty).isTrue()
        db.syncDao().clearRecords()
        presets.update(uuid, name = "Web small")
        assertThat(record(uuid)!!.dirty).isTrue()
        presets.delete(uuid)
        assertThat(record(uuid)!!.deleted).isTrue()
    }
}
