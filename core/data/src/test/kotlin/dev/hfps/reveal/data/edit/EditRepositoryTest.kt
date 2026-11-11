package dev.hfps.reveal.data.edit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditRepositoryTest {
    private lateinit var db: RevealDatabase
    private lateinit var edits: EditRepository
    private var photoId = 0L
    private var versionId = 0L

    @Before
    fun setUp() = runTest {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        edits = EditRepository(db) { 1000L }
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

    private fun exposure(ev: Float) = Recipe(light = Light(exposureEv = ev))

    private suspend fun commit(from: Recipe, to: Recipe) = edits.commit(versionId, to, RecipeDiff.between(from, to))

    @Test
    fun `commit, undo, redo and a new branch persist`() = runTest {
        assertThat(edits.recipe(versionId)).isEqualTo(Recipe())
        commit(Recipe(), exposure(0.7f))
        val two = commit(exposure(0.7f), exposure(1.2f))
        assertThat(two.steps.map { it.labels }).containsExactly(
            listOf(HistoryStep(HistoryField.EXPOSURE, "+0.70")),
            listOf(HistoryStep(HistoryField.EXPOSURE, "+1.20")),
        ).inOrder()
        assertThat(two.cursor).isEqualTo(1)
        assertThat(db.photoDao().get(photoId)!!.hasEdit).isTrue()

        assertThat(edits.undo(versionId)).isEqualTo(exposure(0.7f))
        assertThat(edits.undo(versionId)).isEqualTo(Recipe())
        assertThat(edits.undo(versionId)).isNull()
        assertThat(edits.recipe(versionId)).isEqualTo(Recipe())
        assertThat(db.photoDao().get(photoId)!!.hasEdit).isFalse()
        assertThat(edits.history(versionId).canRedo).isTrue()

        assertThat(edits.redo(versionId)).isEqualTo(exposure(0.7f))
        // A new commit after an undo drops the redo branch.
        val branch = commit(exposure(0.7f), exposure(-1f))
        assertThat(branch.steps).hasSize(2)
        assertThat(branch.steps.last().labels.single().value).isEqualTo("−1.00")
        assertThat(branch.canRedo).isFalse()
        assertThat(edits.recipe(versionId)).isEqualTo(exposure(-1f))

        // A fresh repository (an app restart) sees the same state.
        val reopened = EditRepository(db)
        assertThat(reopened.recipe(versionId)).isEqualTo(exposure(-1f))
        assertThat(reopened.history(versionId).cursor).isEqualTo(1)
        assertThat(reopened.goTo(versionId, -1)).isEqualTo(Recipe())
    }

    @Test
    fun `an empty diff is not a step`() = runTest {
        assertThat(commit(Recipe(), Recipe()).steps).isEmpty()
        assertThat(db.editDao().recipe(versionId)).isNull()
    }
}
