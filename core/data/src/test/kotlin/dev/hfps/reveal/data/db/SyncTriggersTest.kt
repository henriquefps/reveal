package dev.hfps.reveal.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Every library write path marks its sync record dirty, and nothing is marked while sync applies a pull. */
@RunWith(AndroidJUnit4::class)
class SyncTriggersTest {
    private lateinit var db: RevealDatabase
    private var photoId = 0L
    private var versionId = 0L
    private lateinit var photoUuid: String
    private lateinit var versionUuid: String

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        val source = db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://t",
                rootDocumentId = "r",
                displayName = "S",
                volumeId = null,
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
            ),
        )
        photoId = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "", folderDocumentId = "r", stemKey = "a", name = "A.ARW",
                format = PhotoFormat.RAW, hasRaw = true, sortTime = 0, addedAt = 0,
            ),
        )
        db.photoDao().insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = photoId,
                    sourceId = source,
                    relativePath = "A.ARW",
                    documentId = "a",
                    name = "A.ARW",
                    size = 1,
                    modified = 1,
                    role = FileRole.RAW,
                ),
            ),
        )
        versionId = db.photoDao().insertVersion(VersionEntity(photoId = photoId, createdAt = 0))
        photoUuid = db.photoDao().get(photoId)!!.uuid
        versionUuid = db.syncDao().versionsOf(photoId).single().uuid
    }

    @After
    fun tearDown() = db.close()

    private suspend fun cleanAll() = db.syncDao().clearRecords()

    private suspend fun dirty() = db.syncDao().dirty(100).map { it.kind to it.recordId }.toSet()

    @Test
    fun `new photos and versions, metadata, files and edits are marked`() = runBlocking {
        assertThat(dirty()).containsExactly("photo" to photoUuid, "version" to versionUuid)
        cleanAll()
        db.photoDao().setRating(listOf(photoId), 4)
        assertThat(dirty()).containsExactly("photo" to photoUuid)
        cleanAll()
        db.photoDao().setFlag(listOf(photoId), PickFlag.PICK)
        db.photoDao().setHidden(listOf(photoId), true)
        assertThat(dirty()).containsExactly("photo" to photoUuid)
        cleanAll()
        val file = db.photoDao().files(photoId).single()
        db.photoDao().setFingerprint(file.id, file.size, file.modified, "v1:" + "a".repeat(64))
        assertThat(dirty()).containsExactly("photo" to photoUuid)
        cleanAll()
        val edits = EditRepository(db)
        val edited = Recipe().let { it.copy(light = it.light.copy(exposureEv = 1f)) }
        edits.commit(versionId, edited, RecipeDiff.between(Recipe(), edited))
        assertThat(dirty()).containsExactly("version" to versionUuid)
        cleanAll()
        edits.undo(versionId)
        assertThat(dirty()).containsExactly("version" to versionUuid)
        Unit
    }

    @Test
    fun `a change while sync applies a pull is not marked, and a cascade delete marks nothing`() = runBlocking {
        cleanAll()
        db.metaDao().put(MetaEntity(SyncTriggers.APPLYING, "1"))
        db.photoDao().setRating(listOf(photoId), 2)
        db.metaDao().delete(SyncTriggers.APPLYING)
        assertThat(dirty()).isEmpty()
        db.photoDao().deletePhotos(listOf(photoId))
        assertThat(dirty()).isEmpty()
    }

    @Test
    fun `a marked record stays one row and remembers it changed`() = runBlocking {
        val before = db.syncDao().record("photo", photoUuid)!!
        db.syncDao().putRecord(before.copy(rev = 7, dirty = false))
        db.photoDao().setRating(listOf(photoId), 5)
        val after = db.syncDao().record("photo", photoUuid)!!
        assertThat(after.rev).isEqualTo(7)
        assertThat(after.dirty).isTrue()
    }

    @Test
    fun `a snapshot change marks its version, an export preset its setting record`() = runBlocking {
        cleanAll()
        val dao = db.versionDao()
        val id = dao.insertSnapshot(SnapshotEntity(versionId = versionId, name = "A", recipeJson = "{}", createdAt = 0))
        assertThat(dirty()).containsExactly("version" to versionUuid)
        cleanAll()
        dao.renameSnapshot(id, "B")
        assertThat(dirty()).containsExactly("version" to versionUuid)
        cleanAll()
        dao.deleteSnapshot(id)
        assertThat(dirty()).containsExactly("version" to versionUuid)
        cleanAll()
        val preset = ExportPresetEntity(name = "Web", optionsJson = "{}", position = 0, createdAt = 0, updatedAt = 0)
        dao.insertExportPreset(preset)
        assertThat(dirty()).containsExactly("setting" to SyncTriggers.EXPORT_PRESET_PREFIX + preset.uuid)
        cleanAll()
        dao.deleteExportPreset(dao.exportPresetByUuid(preset.uuid)!!.id)
        val tombstone = db.syncDao().record("setting", SyncTriggers.EXPORT_PRESET_PREFIX + preset.uuid)!!
        assertThat(tombstone.deleted).isTrue()
    }
}
