package dev.hfps.reveal.data.handoff

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.thumb.ServerThumb
import dev.hfps.reveal.data.thumb.ServerThumbs
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The grid of photos the server holds (ADR 0018 "W3/C"): a hand-off whose file the server holds shows the server's
 * thumbnail and the sender's URI is never opened for it; "later" shows nothing (no open either); only a file the
 * server does not serve thumbnails for is read.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerThumbnailsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private val tree = FileUriTree()
    private val now = 1_000L
    private var answer: ServerThumb = ServerThumb.Later
    private val asked = ArrayList<String>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val server = object : ServerThumbs {
        override suspend fun thumbnail(fingerprint: String): ServerThumb {
            asked += fingerprint
            return answer
        }

        override suspend fun editedThumbnail(fingerprint: String, versionUuid: String): Bitmap? = null
    }

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun thumbnails() = ThumbnailRepository(
        db = db,
        trees = { tree },
        edits = EditRepository(db) { now },
        edited = null,
        dir = temp.newFolder(),
        clock = { now },
        serverThumbs = { server },
    )

    private suspend fun handOver(name: String, onServer: Boolean): Pair<Long, Long> {
        val file = File(temp.newFolder(), name).apply {
            val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
            }
            val jpeg = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            writeBytes(jpeg.toByteArray() + name.toByteArray())
        }
        val uri = Uri.parse("content://dev.hfps.hoard.stream/h/1/$name")
        tree.aliases[uri.toString()] = file
        val outcome = HandoffRepository(context.contentResolver, db, LibraryIndexer(db) { now }, tree) { now }
            .receive(HandoffRequest(listOf(uri)))
        val version = (outcome as HandoffOutcome.OpenEditor).versionId
        val row = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS)).single { it.name == name }
        if (onServer) db.syncDao().setOnServer(db.photoDao().files(row.id).single().fingerprint!!, true)
        tree.opens.clear()
        return row.id to version
    }

    @Test
    fun `a hand-off the server holds shows the server's thumbnail without opening the sender's URI`() = runTest {
        val (photo, version) = handOver("A.jpg", onServer = true)
        answer = ServerThumb.Image(Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888))
        assertThat(thumbnails().thumbnail(photo, version, null)).isNotNull()
        assertThat(asked).hasSize(1)
        assertThat(tree.opens).isEmpty()
    }

    @Test
    fun `later shows nothing and opens nothing, not served reads the file`() = runTest {
        val (photo, version) = handOver("B.jpg", onServer = true)
        answer = ServerThumb.Later
        assertThat(thumbnails().thumbnail(photo, version, null)).isNull()
        assertThat(tree.opens).isEmpty()
        answer = ServerThumb.NotServed
        assertThat(thumbnails().thumbnail(photo, version, null)).isNotNull()
        assertThat(tree.opens).hasSize(1)
    }

    @Test
    fun `a hand-off the server lacks is read as before and the server is not asked`() = runTest {
        val (photo, version) = handOver("C.jpg", onServer = false)
        assertThat(thumbnails().thumbnail(photo, version, null)).isNotNull()
        assertThat(asked).isEmpty()
        assertThat(tree.opens).hasSize(1)
    }
}
