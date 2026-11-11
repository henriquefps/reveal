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
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's persistence path: a [HistoryWriter] over a file database, then the process "dies" (database closed,
 * writer scope cancelled) and a fresh database + repository read the same history back (ADR 0006 "History").
 */
@RunWith(AndroidJUnit4::class)
class HistoryWriterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "history-writer-test.db"
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private fun open(): RevealDatabase = Room.databaseBuilder(context, RevealDatabase::class.java, name).build()

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdown()
        context.deleteDatabase(name)
    }

    private fun exposure(ev: Float) = Recipe(light = Light(exposureEv = ev))

    @Test
    fun `queued commits and moves survive a kill and restart`() = runBlocking {
        context.deleteDatabase(name)
        val db = open()
        val source = db.sourceDao().insert(SourceEntity(0, "t", "", "s", null, null, SourceState.AVAILABLE, true, 0))
        val photo = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "", folderDocumentId = "", stemKey = "a", name = "A.JPG",
                format = PhotoFormat.JPEG, hasRaw = false, sortTime = 0, addedAt = 0,
            ),
        )
        val version = db.photoDao().insertVersion(VersionEntity(photoId = photo, createdAt = 0))
        val writer = HistoryWriter(EditRepository(db), scope)

        // What the editor does: an in-memory history, each change queued at once (a burst, coalesced by the writer).
        var history = EditHistory()
        fun commit(recipe: Recipe, action: HistoryAction? = null) {
            history = checkNotNull(history.commit(recipe, action))
            writer.write(HistoryOp.Commit(version, history.current, history.entries[history.cursor].label))
        }
        fun move(next: EditHistory?) {
            history = checkNotNull(next)
            writer.write(HistoryOp.Move(version, history.cursor))
        }
        for (i in 1..20) commit(exposure(i / 10f))
        move(history.goTo(4))
        commit(Recipe(), HistoryAction.RESET_ALL)
        move(history.undo())
        writer.flush()

        // Kill: the process and its database go away without any shutdown hook.
        scope.cancel()
        db.close()

        val reopened = open()
        try {
            val loaded = EditRepository(reopened).load(version)
            assertThat(loaded).isEqualTo(history)
            assertThat(loaded.entries).hasSize(6)
            assertThat(loaded.cursor).isEqualTo(4)
            assertThat(loaded.canRedo).isTrue()
            assertThat(EditRepository(reopened).recipe(version)).isEqualTo(exposure(0.5f))
            assertThat(reopened.photoDao().get(photo)!!.hasEdit).isTrue()
        } finally {
            reopened.close()
        }
    }
}
