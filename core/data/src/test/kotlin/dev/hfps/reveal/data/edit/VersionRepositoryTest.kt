package dev.hfps.reveal.data.edit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.EditFilter
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Virtual copies and snapshots (docs/adr/0025): making, naming, deleting, the grid's cells and sync records. */
@RunWith(AndroidJUnit4::class)
class VersionRepositoryTest {
    private lateinit var db: RevealDatabase
    private lateinit var edits: EditRepository
    private lateinit var versions: VersionRepository
    private var photoId = 0L
    private var versionId = 0L
    private var now = 1000L

    @Before
    fun setUp() = runTest {
        db = RevealDatabase.configure(
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            ),
        ).allowMainThreadQueries().build()
        edits = EditRepository(db) { now }
        versions = VersionRepository(db, edits) { now }
        val source = db.sourceDao().insert(SourceEntity(0, "t", "", "s", null, null, SourceState.AVAILABLE, true, 0))
        photoId = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "", folderDocumentId = "", stemKey = "a", name = "A.JPG",
                format = PhotoFormat.JPEG, hasRaw = false, sortTime = 0, addedAt = 0,
            ),
        )
        versionId = db.photoDao().insertVersion(VersionEntity(photoId = photoId, createdAt = 0))
    }

    @After
    fun tearDown() = db.close()

    private val names: (Int) -> String = { "Copy $it" }
    private val edited = Recipe(light = Light(exposureEv = 0.7f), colour = Colour(saturation = 20f))

    private suspend fun edit(id: Long, recipe: Recipe) =
        edits.commit(id, recipe, RecipeDiff.between(edits.recipe(id), recipe))

    private suspend fun rows() = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery()))

    @Test
    fun `a copy of the current edit starts with one virtual copy step and its own history`() = runTest {
        edit(versionId, edited)
        val copy = checkNotNull(versions.createCopy(versionId, CopySource.CURRENT, names))
        assertThat(edits.recipe(copy)).isEqualTo(edited)
        val history = edits.load(copy)
        assertThat(history.entries).hasSize(1)
        assertThat(history.entries.single().label.action).isEqualTo(HistoryAction.VIRTUAL_COPY)
        assertThat(history.entries.single().label.groups).containsExactly(RecipeGroup.LIGHT, RecipeGroup.COLOUR)
        // Undo of the copy reaches the original; the source is untouched.
        edits.undo(copy)
        assertThat(edits.recipe(copy)).isEqualTo(Recipe())
        assertThat(edits.recipe(versionId)).isEqualTo(edited)
        assertThat(versions.version(copy)!!.isCopy).isTrue()
    }

    @Test
    fun `a copy of the original has no edit, and copies are numbered by the first free name`() = runTest {
        edit(versionId, edited)
        val one = checkNotNull(versions.createCopy(versionId, CopySource.ORIGINAL, names))
        assertThat(edits.recipe(one)).isEqualTo(Recipe())
        assertThat(edits.load(one).entries).isEmpty()
        val two = checkNotNull(versions.createCopy(one, CopySource.CURRENT, names))
        assertThat(versions.versions(photoId).map { it.name }).containsExactly(null, "Copy 1", "Copy 2").inOrder()
        assertThat(versions.versions(photoId).map { it.position }).containsExactly(0, 1, 2).inOrder()
        // A deleted copy's number is not reused while a later one exists; a rename frees a name.
        assertThat(versions.delete(one)).isTrue()
        versions.rename(two, "  Black and white  ")
        val three = checkNotNull(versions.createCopy(versionId, CopySource.CURRENT, names))
        assertThat(versions.version(three)!!.name).isEqualTo("Copy 2")
        assertThat(versions.version(two)!!.name).isEqualTo("Black and white")
        // A blank rename keeps the name.
        versions.rename(two, "  ")
        assertThat(versions.version(two)!!.name).isEqualTo("Black and white")
    }

    @Test
    fun `copies are cells of their own, edited or not on their own`() = runTest {
        edit(versionId, edited)
        val copy = checkNotNull(versions.createCopy(versionId, CopySource.ORIGINAL, names))
        val cells = rows()
        assertThat(cells.map { it.versionId }).containsExactly(versionId, copy).inOrder()
        assertThat(cells.map { it.versionName }).containsExactly(null, "Copy 1").inOrder()
        assertThat(cells.map { it.edited }).containsExactly(true, false).inOrder()
        assertThat(cells.map { it.gridKey }).containsNoDuplicates()
        val editedOnly = db.photoDao().rows(
            PhotoQuerySql.toQuery(PhotoQuery(filters = PhotoFilters(edits = setOf(EditFilter.EDITED)))),
        )
        assertThat(editedOnly.map { it.versionId }).containsExactly(versionId)
        val counts = db.photoDao().observeFilterCounts().first()
        assertThat(counts.total).isEqualTo(2)
        assertThat(counts.edited).isEqualTo(1)
        assertThat(counts.notEdited).isEqualTo(1)
    }

    @Test
    fun `the last version is never deleted, the first one's place goes to the next`() = runTest {
        assertThat(versions.delete(versionId)).isFalse()
        edit(versionId, edited)
        val copy = checkNotNull(versions.createCopy(versionId, CopySource.ORIGINAL, names))
        assertThat(versions.delete(versionId)).isTrue()
        assertThat(versions.versions(photoId).single().id).isEqualTo(copy)
        assertThat(versions.version(copy)!!.position).isEqualTo(0)
        assertThat(db.photoDao().get(photoId)!!.hasEdit).isFalse()
        assertThat(versions.delete(copy)).isFalse()
    }

    @Test
    fun `a deleted copy the server knows becomes a tombstone, one it never saw is forgotten`() = runTest {
        val known = checkNotNull(versions.createCopy(versionId, CopySource.ORIGINAL, names))
        val local = checkNotNull(versions.createCopy(versionId, CopySource.ORIGINAL, names))
        val knownUuid = versions.version(known)!!.uuid
        val localUuid = versions.version(local)!!.uuid
        db.syncDao().putRecord(SyncRecordEntity("version", knownUuid, rev = 7, dirty = false, modifiedAt = 1))
        versions.delete(known)
        versions.delete(local)
        val tombstone = db.syncDao().record("version", knownUuid)!!
        assertThat(tombstone.deleted).isTrue()
        assertThat(tombstone.dirty).isTrue()
        assertThat(tombstone.rev).isEqualTo(7)
        assertThat(db.syncDao().record("version", localUuid)).isNull()
    }

    @Test
    fun `snapshots are saved, renamed, applied as one named step and deleted`() = runTest {
        edit(versionId, edited)
        val id = versions.createSnapshot(versionId, "  Before the crop ", edited)
        now = 2000L
        val blank = versions.createSnapshot(versionId, "   ", Recipe(), fallbackName = "Snapshot 2")
        assertThat(versions.snapshots(versionId).map { it.name }).containsExactly("Snapshot 2", "Before the crop")
            .inOrder()
        versions.renameSnapshot(id, "Colour")
        assertThat(versions.observeSnapshots(versionId).first().last().name).isEqualTo("Colour")
        // Applying the original-state snapshot is one SNAPSHOT step named after it; undo returns to the edit.
        assertThat(versions.applySnapshot(blank)).isTrue()
        assertThat(edits.recipe(versionId)).isEqualTo(Recipe())
        val step = edits.load(versionId).let { it.entries[it.cursor].label }
        assertThat(step.action).isEqualTo(HistoryAction.SNAPSHOT)
        assertThat(step.name).isEqualTo("Snapshot 2")
        edits.undo(versionId)
        assertThat(edits.recipe(versionId)).isEqualTo(edited)
        // The edit already is the snapshot: no step.
        assertThat(versions.applySnapshot(id)).isFalse()
        versions.deleteSnapshot(id)
        assertThat(versions.snapshots(versionId).map { it.id }).containsExactly(blank)
        // A copy has its own snapshots; they go with the version.
        val copy = checkNotNull(versions.createCopy(versionId, CopySource.CURRENT, names))
        assertThat(versions.snapshots(copy)).isEmpty()
        versions.createSnapshot(copy, "Copy's", edited)
        versions.delete(copy)
        assertThat(db.versionDao().snapshots(copy)).isEmpty()
    }

    @Test
    fun `a batch makes one copy per selected version`() = runTest {
        val other = db.photoDao().insertVersion(VersionEntity(photoId = photoId, position = 1, createdAt = 0))
        val made = versions.createCopies(listOf(versionId, other), CopySource.CURRENT, names)
        assertThat(made).hasSize(2)
        assertThat(versions.versions(photoId).map { it.position }).containsExactly(0, 1, 2, 3).inOrder()
    }
}
