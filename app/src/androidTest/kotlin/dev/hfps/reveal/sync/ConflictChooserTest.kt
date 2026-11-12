package dev.hfps.reveal.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.library.ConflictTags
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The conflict chooser (ADR 0018 amendment FIX/W) on a device, without a server: a folder photo gets a conflict copy
 * as a pull would file it; a tap on the copy's cell opens the chooser and "Keep this one" on the copy leaves one
 * version with the copy's edit; from the viewer, keeping the main version deletes the copy. Test tags only, so it
 * passes in any locale.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConflictChooserTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val run = System.nanoTime() % 1_000_000_000
    private val folder = SyntheticFolder("conflict-$run")

    @After
    fun tearDown() = folder.delete()

    private fun exposure(ev: Float) = Recipe(light = Light(exposureEv = ev))

    /** A photo whose main version has +0.5 EV and a copy from "Tablet" with -1 EV. */
    private fun photoWithConflict(): Triple<Long, Long, Long> = runBlocking {
        folder.jpeg("IMG_C$run.JPG", 7, base = folder.gradientJpeg(7) + "conflict-$run".toByteArray())
        val (sourceId, _) = folder.addAndIndex()
        val photo = access.library().photoList(PhotoQuery(scope = LibraryScope.Source(sourceId))).single()
        val db = access.database()
        val main = checkNotNull(db.photoDao().firstVersion(photo.id))
        val mainUuid = db.syncDao().versionsOf(photo.id).single().uuid
        access.edits().commit(main, exposure(0.5f), RecipeDiff.between(Recipe(), exposure(0.5f)))
        val copy = db.photoDao().insertVersion(
            VersionEntity(
                photoId = photo.id,
                position = 1,
                createdAt = 0,
                copyOf = mainUuid,
                copyDevice = "Tablet",
                editDevice = "Tablet",
                editedAt = System.currentTimeMillis() - 60_000,
            ),
        )
        access.edits().commit(copy, exposure(-1f), RecipeDiff.between(Recipe(), exposure(-1f)))
        Triple(sourceId, photo.id, copy)
    }

    private fun versions(photoId: Long) = runBlocking { access.database().syncDao().versionsOf(photoId) }

    private fun waitForVersions(photoId: Long, count: Int) {
        compose.waitUntil(TIMEOUT_MS) { versions(photoId).size == count }
    }

    @Test
    fun keepingTheCopyFromTheGridMakesItThePhotosOnlyVersion() {
        val (sourceId, photoId, copy) = photoWithConflict()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.source(sourceId)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.source(sourceId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.copy(copy)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.copy(copy)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ConflictTags.SHEET), TIMEOUT_MS)
            compose.onNodeWithTag(ConflictTags.KEEP_COPY).performClick()
            waitForVersions(photoId, 1)
        }
        val left = versions(photoId).single()
        assertThat(left.copyOf).isNull()
        assertThat(runBlocking { access.edits().recipe(left.id) }.light.exposureEv).isEqualTo(-1f)
    }

    @Test
    fun keepingTheMainVersionFromTheViewerDeletesTheCopy() {
        val (sourceId, photoId, _) = photoWithConflict()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.source(sourceId)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.source(sourceId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photoId)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.cell(photoId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ConflictTags.VIEWER_RESOLVE), TIMEOUT_MS)
            compose.onNodeWithTag(ConflictTags.VIEWER_RESOLVE).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ConflictTags.SHEET), TIMEOUT_MS)
            compose.onNodeWithTag(ConflictTags.KEEP_MAIN).performClick()
            waitForVersions(photoId, 1)
            compose.waitUntilDoesNotExist(hasTestTag(ConflictTags.VIEWER_RESOLVE), TIMEOUT_MS)
        }
        val left = versions(photoId).single()
        assertThat(runBlocking { access.edits().recipe(left.id) }.light.exposureEv).isEqualTo(0.5f)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
