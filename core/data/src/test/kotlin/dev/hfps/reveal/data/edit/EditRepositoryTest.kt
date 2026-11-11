package dev.hfps.reveal.data.edit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.HistoryCursorEntity
import dev.hfps.reveal.data.db.HistoryEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import dev.hfps.reveal.pipeline.recipe.StepLabel
import kotlinx.coroutines.flow.first
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

    @Test
    fun `load rebuilds the editor's history with snapshots, named steps and the cursor`() = runTest {
        val light = Recipe(light = Light(exposureEv = 0.7f, contrast = 12f))
        var history = EditHistory()
        val ops = mutableListOf<HistoryOp>()
        fun step(next: EditHistory?) {
            history = checkNotNull(next)
            ops += HistoryOp.Commit(versionId, history.current, history.entries[history.cursor].label)
        }
        step(history.commit(exposure(0.7f)))
        step(history.commit(light))
        step(history.commit(Recipe(), HistoryAction.RESET_GROUP, listOf(RecipeGroup.LIGHT)))
        history = checkNotNull(history.undo())
        ops += HistoryOp.Move(versionId, history.cursor)
        edits.apply(ops)

        val loaded = edits.load(versionId)
        assertThat(loaded).isEqualTo(history)
        assertThat(loaded.entries[2].label.action).isEqualTo(HistoryAction.RESET_GROUP)
        assertThat(loaded.entries[2].label.groups).containsExactly(RecipeGroup.LIGHT)
        assertThat(edits.recipe(versionId)).isEqualTo(light)
    }

    @Test
    fun `labels of a newer app are skipped, an undecodable step ends the history`() = runTest {
        db.editDao().insertHistory(
            HistoryEntity(
                versionId = versionId,
                position = 0,
                recipeJson = RecipeCodec.Default.encode(exposure(1f)),
                labelsJson = """[{"action":"FUTURE"},{"field":"EXPOSURE","value":"+1.00"},{"field":"NEW_KNOB"}]""",
                createdAt = 0,
            ),
        )
        db.editDao().insertHistory(
            HistoryEntity(versionId = versionId, position = 1, recipeJson = """{"v":999}""", labelsJson = "[]", createdAt = 0),
        )
        db.editDao().setCursor(HistoryCursorEntity(versionId, 1))
        val loaded = edits.load(versionId)
        assertThat(loaded.entries).hasSize(1)
        assertThat(loaded.cursor).isEqualTo(0)
        assertThat(loaded.entries[0].label).isEqualTo(StepLabel(listOf(HistoryStep(HistoryField.EXPOSURE, "+1.00"))))
    }

    @Test
    fun `batch paste adds one step per changed photo and keeps the targets' other groups`() = runTest {
        val second = newVersion("b")
        val third = newVersion("c")
        // The third photo already has the pasted light: no step for it.
        edits.commit(third, exposure(1f), RecipeDiff.between(Recipe(), exposure(1f)))
        val shadows = Recipe(colour = Colour(saturation = -30f))
        edits.commit(second, shadows, RecipeDiff.between(Recipe(), shadows))

        val clip = EditClipboard(Recipe(light = Light(exposureEv = 1f), colour = Colour(vibrance = 25f)))
        val changed = edits.paste(listOf(versionId, second, third), clip, setOf(RecipeGroup.LIGHT))
        assertThat(changed).isEqualTo(2)

        assertThat(edits.recipe(versionId)).isEqualTo(exposure(1f))
        assertThat(edits.recipe(second)).isEqualTo(shadows.copy(light = Light(exposureEv = 1f)))
        val secondHistory = edits.history(second)
        assertThat(secondHistory.steps).hasSize(2)
        assertThat(secondHistory.steps.last().label.action).isEqualTo(HistoryAction.PASTE)
        assertThat(secondHistory.steps.last().label.groups).containsExactly(RecipeGroup.LIGHT)
        assertThat(edits.history(third).steps).hasSize(1)
        assertThat(db.photoDao().get(photoId)!!.hasEdit).isTrue()
        // Undoing the paste on one photo leaves the others.
        assertThat(edits.undo(second)).isEqualTo(shadows)
        assertThat(edits.recipe(versionId)).isEqualTo(exposure(1f))
    }

    @Test
    fun `previous is the most recently edited other version`() = runTest {
        val second = newVersion("b")
        assertThat(edits.previous(versionId)).isNull()
        var now = 1000L
        val timed = EditRepository(db) { now }
        timed.commit(second, exposure(0.3f), RecipeDiff.between(Recipe(), exposure(0.3f)))
        now = 2000L
        timed.commit(versionId, exposure(2f), RecipeDiff.between(Recipe(), exposure(2f)))
        assertThat(timed.previous(versionId)).isEqualTo(exposure(0.3f))
        assertThat(timed.previous(second)).isEqualTo(exposure(2f))
        assertThat(timed.previous(-1)).isEqualTo(exposure(2f))
        // A version reset to the default is not "edited".
        now = 3000L
        timed.undo(versionId)
        assertThat(timed.previous(second)).isNull()
    }

    @Test
    fun `the clipboard and the paste choice survive a restart`() = runTest {
        assertThat(edits.currentClipboard()).isNull()
        assertThat(edits.pasteGroups()).isEqualTo(RecipePaste.DEFAULT_GROUPS)
        edits.copy(exposure(0.5f))
        edits.setPasteGroups(setOf(RecipeGroup.COLOUR, RecipeGroup.LIGHT))
        val reopened = EditRepository(db)
        assertThat(reopened.clipboard().first()).isEqualTo(EditClipboard(exposure(0.5f)))
        assertThat(reopened.pasteGroups()).containsExactly(RecipeGroup.LIGHT, RecipeGroup.COLOUR)
    }

    private suspend fun newVersion(stem: String): Long {
        val photo = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = db.photoDao().get(photoId)!!.sourceId, folder = "", folderDocumentId = "", stemKey = stem,
                name = "$stem.JPG", format = PhotoFormat.JPEG, hasRaw = false, sortTime = 0, addedAt = 0,
            ),
        )
        return db.photoDao().insertVersion(VersionEntity(photoId = photo, createdAt = 0))
    }
}
