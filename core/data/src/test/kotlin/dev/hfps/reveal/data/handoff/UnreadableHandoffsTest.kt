package dev.hfps.reveal.data.handoff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.OriginalPlace
import dev.hfps.reveal.data.library.OriginalReach
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Hand-offs Reveal can no longer read (ADR 0011 "Unreadable hand-offs"): a local preview made while the grant lasts,
 * the unreadable mark, and the library, viewer, editor and export falling back to the preview.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnreadableHandoffsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private val tree = FileUriTree()
    private lateinit var previews: LocalPreviews
    private val displays = AtomicInteger()
    private val smarts = AtomicInteger()
    private var now = 1_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Writes a real small JPEG as the display image and marker bytes as the smart preview. */
    private val making = object : PreviewMaking {
        override fun display(raw: ImageInput?, rendered: ImageInput?, out: File) {
            displays.incrementAndGet()
            out.writeBytes(jpeg(Color.RED))
        }

        override fun smart(main: ImageInput, out: File) {
            smarts.incrementAndGet()
            out.writeBytes(SMART)
        }
    }
    private lateinit var previewer: HandoffPreviewer

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        previews = LocalPreviews(temp.newFolder("previews"))
        previewer = HandoffPreviewer(tree, previews, CoroutineScope(Dispatchers.IO), making)
    }

    @After
    fun tearDown() = db.close()

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun handoffs() =
        HandoffRepository(context.contentResolver, db, LibraryIndexer(db) { now }, tree, previewer = previewer) { now }

    private fun library() = LibraryRepository(
        context.contentResolver,
        db,
        null,
        { tree },
        SidecarSync(db, { tree }, "Reveal test"),
        CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        serverAuthority = authority,
        previews = previews,
    )

    private val authority: String get() = ServerTree.authority(context.packageName)

    private fun galleryFile(name: String): Pair<Uri, File> {
        val file = File(temp.newFolder(), name).apply {
            writeBytes(
                SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()) + name.toByteArray(),
            )
        }
        val uri = Uri.parse("content://media/external/images/media/${name.hashCode() and 0xffff}")
        tree.aliases[uri.toString()] = file
        return uri to file
    }

    private suspend fun receive(uri: Uri): Long {
        val outcome = handoffs().receive(HandoffRequest(listOf(uri)))
        // The editor this opens comes and goes (the app's editor does the same through its view model).
        previewer.editorOpened()
        previewer.editorClosed()
        previewer.idle()
        return (outcome as HandoffOutcome.OpenEditor).versionId
    }

    private suspend fun rows() = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.FromApps)))

    @Test
    fun `a gallery hand-off gets its display image and smart preview, a re-share keeps them`() = runTest {
        val (uri, file) = galleryFile("20261112_101500.jpg")
        receive(uri)
        val print = FileFingerprint.of(file.readBytes())
        assertThat(previews.display(print)).isNotNull()
        assertThat(previews.smart(print)!!.readBytes()).isEqualTo(SMART)

        // The same photo shared again under another URI: the same photo, its previews are not made again.
        val again = Uri.parse("content://media/external/images/media/9999")
        tree.aliases[again.toString()] = file
        receive(again)
        assertThat(rows()).hasSize(1)
        assertThat(displays.get()).isEqualTo(1)
        assertThat(smarts.get()).isEqualTo(1)
        assertThat(previews.smart(print)).isNotNull()
    }

    @Test
    fun `while an editor is open only display images are made, smart previews follow when it closes`() = runTest {
        val (uri, file) = galleryFile("20261112_101550.jpg")
        val print = FileFingerprint.of(file.readBytes())
        // A single photo opens the editor: the hand-off expects it, then it opens.
        handoffs().receive(HandoffRequest(listOf(uri)))
        previewer.editorOpened()
        val deadline = System.currentTimeMillis() + 10_000
        while (previews.display(print) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(previews.display(print)).isNotNull()
        Thread.sleep(200)
        assertThat(previews.smart(print)).isNull()

        previewer.editorClosed()
        previewer.idle()
        assertThat(previews.smart(print)).isNotNull()
    }

    @Test
    fun `a grant that ended marks the photo preview only, a new read clears it`() = runTest {
        val (uri, _) = galleryFile("20261112_101600.jpg")
        receive(uri)
        assertThat(rows().single().unreadable).isFalse()

        tree.refused += uri.toString()
        assertThat(library().checkHandoffs()).isEqualTo(1)
        assertThat(rows().single().unreadable).isTrue()
        assertThat(db.photoDao().observeUnreadableHandoffs().first())
            .containsExactly(uri.toString())

        tree.refused -= uri.toString()
        assertThat(library().checkHandoffs()).isEqualTo(0)
        assertThat(rows().single().unreadable).isFalse()
    }

    @Test
    fun `the editor and the export use the local smart preview while the original cannot be read`() = runTest {
        val (uri, file) = galleryFile("20261112_101700.jpg")
        val version = receive(uri)
        val print = FileFingerprint.of(file.readBytes())
        assertThat(library().editTarget(version)!!.uri).isEqualTo(uri)
        assertThat(library().exportTarget(version)!!.reach).isEqualTo(OriginalReach.DEVICE)

        tree.refused += uri.toString()
        val edit = library().editTarget(version)!!
        assertThat(edit.uri).isEqualTo(Uri.fromFile(previews.smartFile(print)))
        val export = library().exportTarget(version)!!
        assertThat(export.reach).isEqualTo(OriginalReach.PREVIEW)
        assertThat(export.uri).isEqualTo(uri)
        assertThat(export.previewUri).isEqualTo(Uri.fromFile(previews.smartFile(print)))
        assertThat(library().unreadableCount(listOf(version))).isEqualTo(1)
        assertThat(rows().single().unreadable).isTrue()
    }

    @Test
    fun `the grid thumbnail falls back to the local display image`() = runTest {
        val (uri, _) = galleryFile("20261112_101800.jpg")
        val version = receive(uri)
        tree.refused += uri.toString()
        val thumbnails = ThumbnailRepository(
            db = db,
            trees = { tree },
            edits = EditRepository(db) { now },
            edited = null,
            dir = temp.newFolder("thumbs"),
            clock = { now },
            previews = previews,
        )
        val photo = rows().single()

        val thumbnail = thumbnails.thumbnail(photo.id, version, null)

        assertThat(thumbnail).isNotNull()
        assertThat(thumbnail!!.width).isEqualTo(64)
        assertThat(Color.red(thumbnail.getPixel(10, 10))).isGreaterThan(200)
        assertThat(rows().single().unreadable).isTrue()
    }

    @Test
    fun `forgotten hand-offs lose their previews`() = runTest {
        val (uri, file) = galleryFile("20261112_101900.jpg")
        receive(uri)
        val print = FileFingerprint.of(file.readBytes())
        assertThat(previews.display(print)).isNotNull()
        // 400 newer unedited hand-offs push it out of the retention window.
        val uris = (1..HandoffRepository.MAX_PHOTOS).map { galleryFile("IMG_$it.jpg").first }
        now = 2_000L
        handoffs().receive(HandoffRequest(uris))
        previewer.idle()
        assertThat(previews.display(print)).isNull()
        assertThat(previews.smart(print)).isNull()
    }

    @Test
    fun `local smart previews stay under their cap, the least recently used go first`() {
        val small = LocalPreviews(temp.newFolder("capped"), maxBytes = 25)
        small.dir.mkdirs()
        for ((i, print) in listOf("v1:aa", "v1:bb", "v1:cc").withIndex()) {
            small.smartFile(print).apply { writeBytes(ByteArray(10)) }.setLastModified(1_000L * (i + 1))
        }
        small.trim()
        assertThat(small.smartFile("v1:aa").exists()).isFalse()
        assertThat(small.smartFile("v1:bb").exists()).isTrue()
        assertThat(small.smartFile("v1:cc").exists()).isTrue()
        assertThat(LocalPreviews.key("v1:AB/../x")).isEqualTo("v1_ab____x")
    }

    // ------------------------------------------------------------------ fallbacks (ADR 0011 "Fallbacks")

    /** Marks [version]'s photo as known to the server: its original ([original]) and/or a smart preview. */
    private suspend fun onServer(version: Long, original: Boolean, preview: Boolean) {
        val photoId = db.photoDao().photoIdOfVersion(version)!!
        db.syncDao().putCloud(
            PhotoCloudEntity(
                photoId,
                originalOnServer = original,
                previewSha = if (preview) "ab".repeat(32) else null,
            ),
        )
    }

    private fun dropLocalPreviews(print: String) {
        previews.smartFile(print).delete()
        previews.displayFile(print).delete()
    }

    @Test
    fun `a backed-up hand-off opens and exports the server's original, its preview if the server is unreachable`() =
        runTest {
            val name = "20261112_102000.jpg"
            val (uri, file) = galleryFile(name)
            val version = receive(uri)
            val print = FileFingerprint.of(file.readBytes())
            onServer(version, original = true, preview = false)
            tree.refused += uri.toString()

            val server = ServerTree.uri(authority, ServerTree.ORIGINAL, ServerTree.documentId(print, name))
            val edit = library().editTarget(version)!!
            assertThat(edit.uri).isEqualTo(server)
            assertThat(edit.fallback).isEqualTo(Uri.fromFile(previews.smartFile(print)))
            assertThat(edit.unreadableHandoff).isTrue()
            assertThat(edit.unreadable).isNull()
            val export = library().exportTarget(version)!!
            assertThat(export.reach).isEqualTo(OriginalReach.SERVER)
            assertThat(export.uri).isEqualTo(server)
            assertThat(export.previewUri).isEqualTo(Uri.fromFile(previews.smartFile(print)))
            // Full size: the export sheet does not warn about it.
            assertThat(library().unreadableCount(listOf(version))).isEqualTo(0)
            val photoId = db.photoDao().photoIdOfVersion(version)!!
            assertThat(library().originalPlace(photoId)).isEqualTo(OriginalPlace.HANDOFF_SERVER)
        }

    @Test
    fun `without a local preview a hand-off opens the server's smart preview`() = runTest {
        val name = "20261112_102100.jpg"
        val (uri, file) = galleryFile(name)
        val version = receive(uri)
        val print = FileFingerprint.of(file.readBytes())
        dropLocalPreviews(print)
        onServer(version, original = false, preview = true)
        tree.refused += uri.toString()

        val smart = ServerTree.smartUri(authority, print, name)
        val edit = library().editTarget(version)!!
        assertThat(edit.uri).isEqualTo(smart)
        assertThat(edit.fallback).isNull()
        assertThat(edit.unreadableHandoff).isTrue()
        val export = library().exportTarget(version)!!
        assertThat(export.reach).isEqualTo(OriginalReach.PREVIEW)
        assertThat(export.previewUri).isEqualTo(smart)
        assertThat(library().unreadableCount(listOf(version))).isEqualTo(1)
        val photoId = db.photoDao().photoIdOfVersion(version)!!
        assertThat(library().originalPlace(photoId)).isEqualTo(OriginalPlace.HANDOFF_PREVIEW)
    }

    @Test
    fun `a hand-off with nothing in its place reports its document`() = runTest {
        val (uri, file) = galleryFile("20261112_102200.jpg")
        val version = receive(uri)
        val photoId = db.photoDao().photoIdOfVersion(version)!!
        assertThat(library().originalPlace(photoId)).isEqualTo(OriginalPlace.DEVICE)
        dropLocalPreviews(FileFingerprint.of(file.readBytes()))
        tree.refused += uri.toString()

        val edit = library().editTarget(version)!!
        assertThat(edit.uri).isEqualTo(uri)
        assertThat(edit.unreadable).isEqualTo(uri)
        assertThat(edit.unreadableHandoff).isTrue()
        assertThat(library().exportTarget(version)!!.reach).isEqualTo(OriginalReach.DEVICE)
        assertThat(library().originalPlace(photoId)).isEqualTo(OriginalPlace.HANDOFF_UNREADABLE)
    }

    @Test
    fun `the grid and the viewer read a backed-up hand-off from the server's display file`() = runTest {
        val name = "20261112_102300.jpg"
        val (uri, file) = galleryFile(name)
        val version = receive(uri)
        val print = FileFingerprint.of(file.readBytes())
        val main = db.photoDao().files(db.photoDao().photoIdOfVersion(version)!!).single()
        val fallbacks = HandoffFallbacks(db, previews, authority)
        assertThat(fallbacks.serverDisplay(main)).isNull()
        assertThat(fallbacks.localDisplayJpeg(main)).isNotNull()

        onServer(version, original = true, preview = false)
        assertThat(fallbacks.serverDisplay(main))
            .isEqualTo(ServerTree.uri(authority, ServerTree.DISPLAY, ServerTree.documentId(print, name)))
        // Without sync (no provider authority) there is no server fallback at all.
        assertThat(HandoffFallbacks(db, null, null).of(main).isEmpty).isTrue()
    }

    private companion object {
        val SMART = "RVSP-test".toByteArray()
    }
}
