package dev.hfps.reveal.handoff

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
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
import dev.hfps.image.ImageInput
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.handoff.HandoffTree
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.HandoffHostActivity
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.library.GalleryAccessTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hoard 2.2.0's hand-offs (ADR 0011 "Amendment W4"): `content://media/...` URIs with a read grant only: Images for
 * what MediaStore accepts, Downloads for what it refuses (CR3), no persistable grant, no sidecar items, chunks of 200.
 * Started from [HandoffHostActivity] like Hoard does. The test photos belong to the app (it reads them without a
 * permission), so what changes with `pm grant/revoke READ_MEDIA_IMAGES` (run the class once each way: revoking a
 * permission kills a running app, so the run script does it) is the gallery offer: FULL never asks, NONE and PARTIAL
 * ask once.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HandoffMediaTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val inserted = mutableListOf<Uri>()
    private val stem = "HM${System.nanoTime() % 1_000_000_000}"

    @Before
    fun setUp() = access.galleryAccess().resetAskedForTests()

    @After
    fun tearDown() = inserted.forEach(TestPhotos::delete)

    /** A JPEG in MediaStore.Images (`Pictures/RevealTest`), as Hoard's copies are. */
    private fun image(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RevealTest")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri.also(inserted::add)
    }

    private fun jpegBytes(seed: Int) =
        TestPhotos.uniqueContent(TestPhotos.syntheticJpeg(160, 120)) + "$seed".toByteArray()

    /** A CR3-looking file (ISO BMFF `ftyp crx `) in Downloads, `application/octet-stream`, real name kept. */
    private fun cr3(name: String): Uri {
        val bytes = byteArrayOf(0, 0, 0, 24) + "ftypcrx ".toByteArray() + ByteArray(32) +
            TestPhotos.uniqueContent(SyntheticShoot.markerOnlyJpeg(10, 10))
        return TestPhotos.insert(name, "application/octet-stream", bytes, unique = false).also(inserted::add)
    }

    private fun intent(uris: List<Uri>, index: Int? = null, count: Int? = null, initial: Uri? = null): Intent =
        Intent(HandoffIntents.ACTION_DEVELOP).setClass(context, MainActivity::class.java)
            .setType("image/*")
            .putExtra(HandoffIntents.EXTRA_TITLE, stem)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { intent ->
                intent.clipData = ClipData.newRawUri("photos", uris.first())
                    .also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                index?.let { intent.putExtra(HandoffIntents.EXTRA_BATCH_INDEX, it) }
                count?.let { intent.putExtra(HandoffIntents.EXTRA_BATCH_COUNT, it) }
                initial?.let { intent.putExtra(HandoffIntents.EXTRA_INITIAL_URI, it) }
            }

    /** Another app starts Reveal into its own task (no `NEW_TASK`), as Hoard does. */
    private fun fromAnotherApp(vararg intents: Intent, block: () -> Unit) {
        ActivityScenario.launch(HandoffHostActivity::class.java).use { host ->
            host.onActivity { activity ->
                if (intents.size == 1) activity.startActivity(intents[0]) else activity.startActivities(intents)
            }
            block()
        }
    }

    private fun photos() = runBlocking { access.library().photoList(PhotoQuery.FROM_APPS) }
        .filter { it.name.contains(stem) }

    @Test
    fun aMediaStoreHandOffOpensTheBatchAndTheOfferIsAskedOnlyBelowFullAccess() {
        val uris = (1..2).map { image("$stem-$it.jpg", jpegBytes(it)) }
        val state = access.galleryAccess().also { it.refresh() }.state.value
        fromAnotherApp(intent(uris)) {
            compose.waitUntil(TIMEOUT_MS) { photos().size == 2 }
            if (state == GalleryAccessState.FULL) {
                compose.waitForIdle()
                assertThat(compose.onAllNodes(hasTestTag(GalleryAccessTags.SHEET)).fetchSemanticsNodes()).isEmpty()
            } else {
                // NONE or PARTIAL: the sheet explains why Reveal wants every photo; declining is remembered.
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodes(hasTestTag(GalleryAccessTags.SHEET)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag(GalleryAccessTags.DECLINE).performClick()
                // Hoard's offer has its own answer: the older gallery-share offer is still unanswered.
                compose.waitUntil(TIMEOUT_MS) { access.galleryAccess().mediaAsked.value }
                assertThat(access.galleryAccess().asked.value).isFalse()
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodes(hasTestTag(GalleryAccessTags.SHEET)).fetchSemanticsNodes().isEmpty()
                }
            }
        }
        // Reopened later without the intent's grant (the hand-off is over): the files are still readable through the
        // app's own access, and nothing was marked unreadable.
        runBlocking { access.library().recheckAccess(force = true) }
        val mine = runBlocking { access.database().photoDao().handoffFiles() }.filter { f ->
            uris.any { it.toString() == f.documentId }
        }
        assertThat(mine).hasSize(uris.size)
        assertThat(mine.all { it.unreadableAt == null }).isTrue()
        uris.forEach {
            ImageInput.fromUri(context.contentResolver, it, "x.jpg").use { input ->
                assertThat(input.descriptor.statSize).isGreaterThan(0)
            }
        }
    }

    @Test
    fun aDownloadsOriginalIsKeptLocallyAndOpensAfterTheGrantAndTheFileAreGone() {
        val raw = cr3("$stem.CR3")
        val preview = image("$stem.JPG", jpegBytes(1))
        fromAnotherApp(intent(listOf(raw, preview), initial = raw)) {
            compose.waitUntil(TIMEOUT_MS) { photos().isNotEmpty() }
            compose.waitUntil(TIMEOUT_MS) {
                runBlocking { access.library().photoList(PhotoQuery.FROM_APPS) }.any { it.name == "$stem.CR3" }
            }
        }
        val photo = photos().single { it.name == "$stem.CR3" }
        val files = runBlocking { access.library().detail(photo.id)!!.files }
        // One pair although the RAW lives in Downloads and the JPEG in Images; the RAW is filed under the local copy.
        assertThat(files.map { it.name }).containsExactly("$stem.CR3", "$stem.JPG")
        val copy = runBlocking { access.database().photoDao().files(photo.id) }.map { it.documentId }
            .single { access.localOriginals().isLocal(it) }
        // The Downloads row and its grant are gone: the copy is all Reveal has, and it still opens.
        TestPhotos.delete(raw)
        runBlocking { access.library().recheckAccess(force = true) }
        HandoffTree(context.contentResolver).openInput(copy, "$stem.CR3").use {
            assertThat(it.descriptor.statSize).isGreaterThan(0)
        }
        assertThat(access.localOriginals().files()).isNotEmpty()
        // Forgetting the photo deletes the copy.
        runBlocking { access.library().forget(listOf(photo.id)) }
        assertThat(access.localOriginals().files().map { access.localOriginals().documentId(it) }).doesNotContain(copy)
    }

    @Test
    fun threeChunksOfMediaUrisAreOneBatch() {
        val bytes = SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(64, 48), SyntheticExif())
        val chunks = (0..2).map { c ->
            (0 until CHUNK).map { i ->
                val n = c * CHUNK + i
                image("$stem-%03d.jpg".format(n), bytes + "$stem$n".toByteArray())
            }
        }
        fromAnotherApp(
            *chunks.mapIndexed { index, uris ->
                intent(uris, index, count = chunks.size, initial = uris.first().takeIf { index == 0 })
            }.toTypedArray(),
        ) {
            compose.waitUntil(BIG_TIMEOUT_MS) { photos().size == chunks.sumOf { it.size } }
            val rows = photos()
            assertThat(rows.map { it.handoffAt }.distinct()).hasSize(1)
            assertThat(rows.map { it.handoffTitle }.distinct()).containsExactly(stem)
        }
    }

    @Test
    fun aSinglePhotoFromHoardOpensTheEditorWithTheOfferOverItOnce() {
        val uri = image("$stem-single.jpg", jpegBytes(7))
        val state = access.galleryAccess().also { it.refresh() }.state.value
        assumeTrue("the offer shows below full access only", state != GalleryAccessState.FULL)
        fromAnotherApp(intent(listOf(uri))) {
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(hasTestTag(GalleryAccessTags.SHEET)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(GalleryAccessTags.DECLINE).performClick()
            compose.waitUntil(TIMEOUT_MS) { access.galleryAccess().mediaAsked.value }
        }
        // Once: the next single photo from Hoard opens without it.
        val again = image("$stem-again.jpg", jpegBytes(8))
        fromAnotherApp(intent(listOf(again))) {
            compose.waitUntil(TIMEOUT_MS) { photos().any { it.name == "$stem-again.jpg" } }
            compose.waitForIdle()
            assertThat(compose.onAllNodes(hasTestTag(GalleryAccessTags.SHEET)).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun aBatchGoesOnWhenTheActivityIsRecreatedWhileItIsReceived() {
        val bytes = SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(64, 48), SyntheticExif())
        val uris = (0 until CHUNK).map { n -> image("$stem-r%03d.jpg".format(n), bytes + "$stem-r$n".toByteArray()) }
        ActivityScenario.launch<MainActivity>(intent(uris)).use { scenario ->
            // A rotation (or any configuration change) while the batch is still being filed.
            scenario.recreate()
            compose.waitUntil(BIG_TIMEOUT_MS) { photos().size == uris.size }
            assertThat(photos().map { it.handoffAt }.distinct()).hasSize(1)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val BIG_TIMEOUT_MS = 240_000L
        const val CHUNK = 200
    }
}
