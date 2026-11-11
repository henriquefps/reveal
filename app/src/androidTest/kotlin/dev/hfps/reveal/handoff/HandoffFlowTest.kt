package dev.hfps.reveal.handoff

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.library.LibraryTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hand-off contract (ADR 0011) on a device: every intent shape another app can send, with real content URIs
 * (MediaStore Downloads, so they go through a content provider), RAW+JPEG pairs grouped, the library's "Opened from
 * other apps" source, single opens that return to the caller, and sidecar write-back. Texts never matter: nodes are
 * found by test tag, so it passes in any locale. The test runs in the app's uid, so it cannot prove cross-app grants;
 * those are the contract's `ClipData` + grant flags, exercised by the Hoard side.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HandoffFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val inserted = mutableListOf<Uri>()

    /** A stem no other run used, so MediaStore never renames a file and rows never collide with older runs. */
    private val stem = "HO${System.nanoTime() % 1_000_000_000}"

    @After
    fun tearDown() = inserted.forEach(TestPhotos::delete)

    private fun jpeg(name: String): Uri =
        TestPhotos.insert(name, "image/jpeg", TestPhotos.syntheticJpeg(480, 320)).also(inserted::add)

    private fun raw(name: String): Uri {
        val bytes = SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(1616, 1080), SyntheticExif())
        return TestPhotos.insert(name, "image/x-sony-arw", bytes).also(inserted::add)
    }

    private fun clip(vararg uris: Uri): ClipData {
        val clip = ClipData.newRawUri("photos", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }

    private fun launch(intent: Intent): ActivityScenario<MainActivity> = ActivityScenario.launch(
        intent.setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun photos() = runBlocking {
        access.library().photoList(PhotoQuery(scope = LibraryScope.FromApps)).filter { it.name.startsWith(stem) }
    }

    private fun editorShown() = hasTestTag(EditorTags.DEVELOPED) or hasTestTag(EditorTags.ERROR) or
        hasTestTag(EditorTags.PREVIEW) or hasTestTag(EditorTags.LOADING)

    private fun waitForActivityToFinish(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(TIMEOUT_MS) { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun theContractActionIsResolvedToReveal() {
        val pm = context.packageManager
        val intents = listOf(
            Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*"),
            Intent(HandoffIntents.ACTION_DEVELOP).setType("image/x-sony-arw"),
            Intent(HandoffIntents.ACTION_DEVELOP),
            Intent(Intent.ACTION_EDIT).setDataAndType(Uri.parse("content://x/a.arw"), "image/x-adobe-dng"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/a.jpg"), "image/jpeg"),
            Intent(Intent.ACTION_SEND).setType("image/jpeg"),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*"),
        )
        for (intent in intents) {
            val names = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
            assertThat(names).contains(context.packageName)
        }
    }

    @Test
    fun developWithClipDataGroupsThePairAndOpensTheLibrary() {
        val pair = listOf(raw("$stem.ARW"), jpeg("${stem.lowercase()}.jpg"))
        val lone = jpeg("${stem}_lone.JPG")
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*")
            .putExtra(HandoffIntents.EXTRA_TITLE, "Hoard test")
        intent.clipData = clip(pair[0], pair[1], lone)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        launch(intent).use {
            compose.waitUntil(TIMEOUT_MS) { photos().size == 2 }
            val rows = photos()
            val paired = rows.single { it.name == "$stem.ARW" }
            assertThat(paired.format).isEqualTo(PhotoFormat.RAW_PAIR)
            assertThat(paired.handoffTitle).isEqualTo("Hoard test")
            assertThat(rows.single { it.name != paired.name }.format).isEqualTo(PhotoFormat.JPEG)
            val files = runBlocking { access.library().detail(paired.id)!!.files }
            assertThat(files.map { it.name }).containsExactly("$stem.ARW", "${stem.lowercase()}.jpg")
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(paired.id)), TIMEOUT_MS)
            shot("handoff-library")
        }
    }

    /** Synthetic content only: device screenshots for the PR (`files/pr-shots`, pulled by hand). */
    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SHOT_SETTLE_MS)
        val dir = java.io.File(context.filesDir, "pr-shots").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(dir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun sendMultipleOpensTheLibraryWithEveryPhoto() {
        val uris = arrayListOf(jpeg("${stem}_a.jpg"), jpeg("${stem}_b.jpg"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        launch(intent).use {
            compose.waitUntil(TIMEOUT_MS) { photos().size == 2 }
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photos().first().id)), TIMEOUT_MS)
        }
    }

    @Test
    fun singleViewEditAndSendOpenTheEditorAndBackReturnsToTheCaller() {
        val shapes = listOf<(Uri) -> Intent>(
            { Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg") },
            { Intent(Intent.ACTION_EDIT).setDataAndType(it, "image/jpeg") },
            { Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, it) },
            { Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*").apply { clipData = clip(it) } },
        )
        shapes.forEachIndexed { index, shape ->
            val uri = jpeg("${stem}_s$index.jpg")
            val scenario = launch(shape(uri))
            compose.waitUntilAtLeastOneExists(editorShown(), TIMEOUT_MS)
            runCatching { Espresso.pressBack() }
            waitForActivityToFinish(scenario)
            scenario.close()
        }
        assertThat(photos()).hasSize(shapes.size)
    }

    @Test
    fun developWithAnInitialItemOpensTheEditorAndBackShowsTheBatch() {
        val first = jpeg("${stem}_1.jpg")
        val second = jpeg("${stem}_2.jpg")
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*")
            .putExtra(HandoffIntents.EXTRA_INITIAL_URI, second)
        intent.clipData = clip(first, second)
        launch(intent).use {
            compose.waitUntilAtLeastOneExists(editorShown(), TIMEOUT_MS)
            Espresso.pressBack()
            val rows = photos()
            assertThat(rows).hasSize(2)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(rows.first().id)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.cell(rows.first().id)).assertExists()
        }
    }

    @Test
    fun aRatingGoesBackToTheSidecarTheSenderHandedOver() {
        val raw = raw("$stem.ARW")
        val sidecar = TestPhotos.insert(
            "$stem.xmp",
            "application/octet-stream",
            SyntheticShoot.sidecar(rating = 2).toByteArray(),
        )
            .also(inserted::add)
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*")
        intent.clipData = clip(raw, sidecar)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        launch(intent).use {
            compose.waitUntil(TIMEOUT_MS) { photos().isNotEmpty() }
            val photo = photos().single()
            assertThat(photo.rating).isEqualTo(2)
            val outcome = runBlocking { access.library().setRating(listOf(photo.id), 5) }
            assertThat(outcome.written).isEqualTo(1)
            val text = context.contentResolver.openInputStream(sidecar)!!.use { it.readBytes() }
            assertThat(XmpReader.read(text).rating).isEqualTo(5)
            // A Hoard sidecar means "picked"; the write keeps it, now as an explicit digiKam pick label.
            assertThat(XmpReader.read(text).flag).isEqualTo(PickFlag.PICK)
        }
    }

    /** The culling extra as Hoard writes it (ADR 0011 "Culling"): literal keys, so the wire format is tested. */
    private fun culling(vararg items: Pair<Uri, Bundle>) = Bundle().apply {
        items.forEach { (uri, values) -> putBundle(uri.toString(), values) }
    }

    @Test
    fun cullingValuesFromTheSenderRateTheBatchAndAreNotWrittenBack() {
        val pair = listOf(raw("${stem}_c.ARW"), jpeg("${stem}_c.JPG"))
        val rejected = jpeg("${stem}_r.jpg")
        val sidecarBytes = SyntheticShoot.sidecar(rating = 1, label = "Blue", creatorTool = "darktable").toByteArray()
        val sidecar = TestPhotos.insert("${stem}_c.xmp", "application/octet-stream", sidecarBytes).also(inserted::add)
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*")
            .putExtra("dev.hfps.reveal.extra.CULLING_VERSION", 1)
            .putExtra(
                "dev.hfps.reveal.extra.CULLING",
                culling(
                    pair[1] to Bundle().apply {
                        putInt("rating", 4)
                        putString("flag", "pick")
                    },
                    rejected to Bundle().apply {
                        putString("flag", "reject")
                        putString("label", "Red")
                    },
                ),
            )
        intent.clipData = clip(pair[0], pair[1], rejected, sidecar)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        launch(intent).use {
            compose.waitUntil(TIMEOUT_MS) { photos().size == 2 }
            val rows = photos().associateBy { it.name }
            with(rows.getValue("${stem}_c.ARW")) {
                assertThat(rating).isEqualTo(4)
                assertThat(flag).isEqualTo(PickFlag.PICK)
                // Not sent: the sidecar's label fills it.
                assertThat(label).isEqualTo("Blue")
            }
            with(rows.getValue("${stem}_r.jpg")) {
                assertThat(flag).isEqualTo(PickFlag.REJECT)
                assertThat(label).isEqualTo("Red")
            }
            val now = context.contentResolver.openInputStream(sidecar)!!.use { it.readBytes() }
            assertThat(now).isEqualTo(sidecarBytes)
        }
    }

    @Test
    fun aRatingWithoutASidecarStaysInTheDatabase() {
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*")
        intent.clipData = clip(jpeg("${stem}_db.jpg"))
        launch(intent).use {
            compose.waitUntil(TIMEOUT_MS) { photos().isNotEmpty() }
            val photo = photos().single()
            val outcome = runBlocking { access.library().setRating(listOf(photo.id), 3) }
            assertThat(outcome.databaseOnly).isEqualTo(1)
            assertThat(photos().single().rating).isEqualTo(3)
        }
    }

    @Test
    fun anIntentWithNothingReadableDoesNotCrash() {
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*")
        intent.clipData = clip(Uri.parse("content://${context.packageName}.nothing/none.txt"))
        launch(intent).use {
            compose.waitForIdle()
            assertThat(photos()).isEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val SHOT_SETTLE_MS = 1_500L
    }
}
