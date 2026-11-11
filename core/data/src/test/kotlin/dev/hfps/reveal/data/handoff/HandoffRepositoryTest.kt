package dev.hfps.reveal.data.handoff

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** A [DocumentTree] whose document ids are `file:` URIs (the JVM has no content provider to hand-off from). */
private class FileUriTree : DocumentTree {
    override val rootDocumentId = ""

    private fun file(id: String) = File(Uri.parse(id).path!!)

    override fun children(documentId: String): List<DocumentEntry> = emptyList()

    override fun stat(documentId: String): DocumentEntry? = file(documentId).takeIf { it.isFile }?.let {
        DocumentEntry(documentId, it.name, "", it.length(), it.lastModified())
    }

    override fun openInput(documentId: String, name: String): ImageInput = ImageInput.fromFile(file(documentId))

    override fun readBytes(documentId: String, maxBytes: Int): ByteArray = file(documentId).readBytes()

    override fun writeBytes(documentId: String, bytes: ByteArray) = file(documentId).writeBytes(bytes)

    override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry =
        throw IOException("no folder")

    override fun uri(documentId: String): Uri = Uri.parse(documentId)
}

@RunWith(AndroidJUnit4::class)
class HandoffRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var repo: HandoffRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java).allowMainThreadQueries().build()
        repo = HandoffRepository(context.contentResolver, db, LibraryIndexer(db), FileUriTree()) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun file(dir: File, name: String, bytes: ByteArray = ByteArray(8)): Uri =
        Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })

    private fun raw(dir: File, name: String, time: String = "2026:08:07 10:15:30") = file(
        dir,
        name,
        SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(1616, 1080), SyntheticExif(dateTime = time)),
    )

    private fun jpeg(dir: File, name: String) = file(
        dir,
        name,
        SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()),
    )

    private suspend fun rows() = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.FromApps)))

    @Test
    fun `a RAW and its JPEG arrive as one pair, case-insensitively, and several shots open the library`() = runTest {
        val dir = temp.newFolder("pick")
        val outcome = repo.receive(
            HandoffRequest(
                uris = listOf(raw(dir, "DSC00001.ARW"), jpeg(dir, "dsc00001.jpg"), jpeg(dir, "IMG_5.JPG")),
                title = "Culling",
            ),
        )
        assertThat(outcome).isInstanceOf(HandoffOutcome.OpenLibrary::class.java)
        val photos = rows()
        assertThat(photos).hasSize(2)
        val pair = photos.first { it.name == "DSC00001.ARW" }
        assertThat(pair.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(pair.handoffTitle).isEqualTo("Culling")
        assertThat(db.photoDao().files(pair.id).map { it.name }).containsExactly("DSC00001.ARW", "dsc00001.jpg")
        assertThat(db.sourceDao().handoff()!!.kind).isEqualTo(SourceKind.HANDOFF)
        assertThat(db.sourceDao().all()).isEmpty()
        assertThat(db.photoDao().get(pair.id)!!.width).isEqualTo(6000)
    }

    @Test
    fun `one shot opens the editor and back closes the app`() = runTest {
        val dir = temp.newFolder("one")
        val outcome = repo.receive(HandoffRequest(listOf(raw(dir, "DSC00007.ARW"), jpeg(dir, "DSC00007.JPG"))))
        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.closeOnBack).isTrue()
        assertThat(outcome.libraryBehind).isFalse()
        assertThat(rows().single().versionId).isEqualTo(outcome.versionId)
    }

    @Test
    fun `the initial item opens the editor with the library behind it`() = runTest {
        val dir = temp.newFolder("many")
        val first = jpeg(dir, "a.jpg")
        val second = jpeg(dir, "b.jpg")
        val outcome = repo.receive(HandoffRequest(listOf(first, second), initial = second))
        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.libraryBehind).isTrue()
        assertThat(outcome.closeOnBack).isFalse()
        assertThat(db.photoDao().photoIdOfVersion(outcome.versionId)).isEqualTo(
            rows().first { it.name == "b.jpg" }.id,
        )
    }

    @Test
    fun `the same stem in different folders stays two photos`() = runTest {
        val one = temp.newFolder("one")
        val two = temp.newFolder("two")
        repo.receive(HandoffRequest(listOf(jpeg(one, "IMG_1.jpg"), jpeg(two, "IMG_1.jpg"))))
        assertThat(rows()).hasSize(2)
    }

    @Test
    fun `sending a photo again moves it first, keeps its edits and its id`() = runTest {
        val dir = temp.newFolder("again")
        val photo = jpeg(dir, "keep.jpg")
        val other = jpeg(dir, "other.jpg")
        repo.receive(HandoffRequest(listOf(photo)))
        val id = rows().single().id
        db.photoDao().setHasEdit(id, true)
        now = 2_000
        repo.receive(HandoffRequest(listOf(other), title = "second"))
        now = 3_000
        repo.receive(HandoffRequest(listOf(photo)))
        val photos = rows()
        assertThat(photos.map { it.name }).containsExactly("keep.jpg", "other.jpg").inOrder()
        assertThat(photos.first().id).isEqualTo(id)
        assertThat(photos.first().hasEdit).isTrue()
        assertThat(photos.first().handoffAt).isEqualTo(3_000)
    }

    @Test
    fun `a sidecar in the hand-off gives the rating and the pick flag`() = runTest {
        val dir = temp.newFolder("xmp")
        val sidecar = file(dir, "DSC00009.xmp", SyntheticShoot.sidecar(rating = 4, pickLabel = 3).toByteArray())
        repo.receive(HandoffRequest(listOf(raw(dir, "DSC00009.ARW"), sidecar)))
        val photo = rows().single()
        assertThat(photo.rating).isEqualTo(4)
        assertThat(photo.flag).isEqualTo(PickFlag.PICK)
        assertThat(db.photoDao().get(photo.id)!!.xmpDocumentId).isEqualTo(sidecar.toString())
    }

    @Test
    fun `a single unrecognised name is opened by uri, several unusable ones are nothing`() = runTest {
        val dir = temp.newFolder("odd")
        val odd = file(dir, "document.pdf")
        assertThat(repo.receive(HandoffRequest(listOf(odd)))).isEqualTo(HandoffOutcome.OpenUri(odd))
        assertThat(repo.receive(HandoffRequest(listOf(odd, file(dir, "b.txt"))))).isEqualTo(HandoffOutcome.Nothing)
        assertThat(rows()).isEmpty()
    }

    @Test
    fun `the oldest unedited hand-offs are forgotten beyond the cap`() = runTest {
        val dir = temp.newFolder("cap")
        for (i in 0 until HandoffRepository.MAX_PHOTOS + 3) {
            now = 10_000L + i
            repo.receive(HandoffRequest(listOf(jpeg(dir, "p%04d.jpg".format(i)))))
        }
        val names = rows().map { it.name }
        assertThat(names).hasSize(HandoffRepository.MAX_PHOTOS)
        assertThat(names).doesNotContain("p0000.jpg")
    }

    @Test
    fun `a document the provider cannot describe still gets a row from its name`() = runTest {
        val dir = temp.newFolder("gone")
        val missing = Uri.fromFile(File(dir, "missing.jpg"))
        val outcome = repo.receive(HandoffRequest(listOf(missing, jpeg(dir, "ok.jpg"))))
        // The missing file still gets a row from its name; the header read marks it failed.
        assertThat(outcome).isInstanceOf(HandoffOutcome.OpenLibrary::class.java)
        assertThat(rows().map { it.name }).contains("ok.jpg")
    }
}
