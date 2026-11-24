package dev.hfps.reveal.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.handoff.FileUriTree
import dev.hfps.reveal.data.handoff.HandoffOutcome
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.thumb.EditedThumbnails
import dev.hfps.reveal.data.thumb.PhotoImages
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.mask.AiMaskSource
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The callers of [FileOpens] keep its rules (ADR 0018 "W3/C", FIX 0.11.0): a RAW + JPEG pair is read one file after
 * the other (four viewers reading pairs at once used to wait for each other forever), and a develop render holds no
 * slot (the file is closed once decoded).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileOpensCallersTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private val files = FileUriTree()
    private var now = 1_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Called once a document's slot is taken, before it is opened (a test's gate). */
    @Volatile
    private var onSlot: (String) -> Unit = {}

    /** The hand-off tree's contract: every open in a FileOpens slot, held until the input is closed. */
    private val tree = object : DocumentTree by files {
        override fun openInput(documentId: String, name: String): ImageInput = FileOpens.gated(name = name) { release ->
            onSlot(name)
            val input = files.openInput(documentId, name)
            ImageInput(input.name, input.descriptor, release)
        }
    }

    @Before
    fun setUp() {
        FileOpens.resetForTests()
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        // Robolectric's native graphics resolves its JNI classes on first use: on the test thread, not a pool's.
        val warm = jpeg(Color.GRAY, 8, 8, "warm-up")
        android.graphics.BitmapFactory.decodeByteArray(warm, 0, warm.size)
            .copyPixelsToBuffer(java.nio.ByteBuffer.allocate(8 * 8 * 4))
    }

    @After
    fun tearDown() {
        db.close()
        val leaked = FileOpens.open
        FileOpens.resetForTests()
        assertThat(leaked).isEqualTo(0)
    }

    private fun jpeg(color: Int, width: Int, height: Int, tag: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        return SyntheticShoot.jpeg(bytes, SyntheticExif()) + tag.toByteArray()
    }

    /** A hand-off of a RAW (with a small embedded preview) and its camera JPEG; returns the photo's id and version. */
    private suspend fun pair(stem: String): Pair<Long, Long> {
        val dir = temp.newFolder()
        val raw = File(dir, "$stem.ARW").apply {
            writeBytes(SyntheticShoot.raw(jpeg(Color.BLUE, 64, 48, stem), SyntheticExif()) + stem.toByteArray())
        }
        val jpg = File(dir, "$stem.JPG").apply { writeBytes(jpeg(Color.RED, 600, 400, stem)) }
        val handoffs = HandoffRepository(context.contentResolver, db, LibraryIndexer(db) { now }, tree) { now }
        val outcome = handoffs.receive(HandoffRequest(listOf(Uri.fromFile(raw), Uri.fromFile(jpg))))
        val version = (outcome as HandoffOutcome.OpenEditor).versionId
        val photo = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS)).single { it.versionId == version }
        return photo.id to version
    }

    @Test
    fun `four viewers reading RAW and JPEG pairs at once all finish`() = runBlocking<Unit> {
        val photos = List(FileOpens.BACKGROUND_SLOTS) { pair("DSC0000$it").first }
        val images = PhotoImages(db, { tree }, EditRepository(db) { now }, edited = null)
        // Every viewer holds its RAW's slot at the same moment: all four slots taken. The embedded preview is too small
        // for the screen, so each then needs its JPEG: holding the RAW meanwhile (the old code) waited forever.
        val barrier = CyclicBarrier(FileOpens.BACKGROUND_SLOTS)
        val jpegs = AtomicInteger()
        onSlot = { name ->
            if (name.endsWith(".ARW")) barrier.await(10, TimeUnit.SECONDS)
            if (name.endsWith(".JPG")) jpegs.incrementAndGet()
        }
        val pool = Executors.newFixedThreadPool(FileOpens.BACKGROUND_SLOTS) { r -> Thread(r).apply { isDaemon = true } }
        try {
            val shown = photos.map { id -> pool.submit<Boolean> { runBlocking { images.display(id, 2_000) != null } } }
            val results = shown.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results).containsExactly(true, true, true, true)
            // Each read its JPEG too (its size first, then the image): the pair path was taken.
            assertThat(jpegs.get()).isAtLeast(FileOpens.BACKGROUND_SLOTS * 2)
        } finally {
            onSlot = {}
            pool.shutdownNow()
        }
    }

    /** Decodes by opening and closing the file; the render reports the slots held and waits for the test. */
    private class SlowRenderer : EditedThumbnails {
        val rendering = CompletableDeferred<Int>()
        val finish = CompletableDeferred<Unit>()
        val decodes = AtomicInteger()

        /** Made on the test thread (Robolectric's native graphics aborts on a first use from a pool thread). */
        private val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        override suspend fun decode(
            open: () -> ImageInput,
            recipe: Recipe,
            longEdge: Int,
            alongside: (ImageInput) -> Unit,
        ): EditedThumbnails.Decoded = open().use { input ->
            alongside(input)
            decodes.incrementAndGet()
            EditedThumbnails.Decoded(PipelineInput.linearSrgb(1, 1, FloatArray(3)), 1, 1 to 1, null)
        }

        override suspend fun render(
            decoded: EditedThumbnails.Decoded,
            recipe: Recipe,
            longEdge: Int,
            masks: AiMaskSource,
            photoKey: String,
            waitForFills: Boolean,
        ): Bitmap {
            rendering.complete(FileOpens.open)
            finish.await()
            return bitmap
        }
    }

    private suspend fun edit(version: Long) {
        val edit = Recipe(light = Light(exposureEv = 1f))
        EditRepository(db) { now }.commit(version, edit, RecipeDiff.between(Recipe(), edit))
    }

    @Test
    fun `a viewer develop render holds no slot`() = runBlocking<Unit> {
        val (photo, version) = pair("DSC00100")
        edit(version)
        val renderer = SlowRenderer()
        val images = PhotoImages(db, { tree }, EditRepository(db) { now }, edited = renderer)
        val shown = async(Dispatchers.IO) { images.developed(photo, 1_000, version) }
        val heldWhileRendering = withTimeout(10_000) { renderer.rendering.await() }
        assertThat(renderer.decodes.get()).isEqualTo(1)
        assertThat(heldWhileRendering).isEqualTo(0)
        // The four slots are free for everyone else while the GPU works.
        val others = List(FileOpens.BACKGROUND_SLOTS) {
            var r: () -> Unit = {}
            FileOpens.gated { release -> r = release }
            r
        }
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS)
        others.forEach { it() }
        renderer.finish.complete(Unit)
        assertThat(withTimeout(10_000) { shown.await() }).isNotNull()
    }

    @Test
    fun `a grid edited thumbnail holds no slot while it renders`() = runBlocking<Unit> {
        val (photo, version) = pair("DSC00200")
        edit(version)
        val renderer = SlowRenderer()
        val thumbnails = ThumbnailRepository(
            db = db,
            trees = { tree },
            edits = EditRepository(db) { now },
            edited = renderer,
            dir = temp.newFolder("thumbs"),
            clock = { now },
        )
        val thumb = async(Dispatchers.IO) { thumbnails.thumbnail(photo, version, now) }
        assertThat(withTimeout(10_000) { renderer.rendering.await() }).isEqualTo(0)
        renderer.finish.complete(Unit)
        assertThat(withTimeout(10_000) { thumb.await() }).isNotNull()
    }
}
