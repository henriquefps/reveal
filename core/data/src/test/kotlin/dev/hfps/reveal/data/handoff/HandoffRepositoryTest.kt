package dev.hfps.reveal.data.handoff

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A [DocumentTree] whose document ids are `file:` URIs (the JVM has no content provider to hand-off from), plus
 * [aliases]: `content:` URIs standing for a file, as MediaStore's `.../images/media/<id>` (the same parent for every
 * photo, whatever its name).
 */
private class FileUriTree : DocumentTree {
    override val rootDocumentId = ""
    val aliases = HashMap<String, File>()

    private fun file(id: String) = aliases[id] ?: File(Uri.parse(id).path!!)

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
    private lateinit var edits: EditRepository
    private val tree = FileUriTree()
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java).allowMainThreadQueries().build()
        repo = HandoffRepository(context.contentResolver, db, LibraryIndexer(db), tree) { now }
        edits = EditRepository(db) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun file(dir: File, name: String, bytes: ByteArray = ByteArray(8)): Uri =
        Uri.fromFile(File(dir, name).apply { writeBytes(bytes) })

    /** Every synthetic photo is a different picture: its folder and name follow the image data (a unique tail). */
    private fun unique(dir: File, name: String, bytes: ByteArray) = bytes + "${dir.name}/$name".toByteArray()

    private fun raw(dir: File, name: String, time: String = "2026:08:07 10:15:30") = file(
        dir,
        name,
        unique(
            dir,
            name,
            SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(1616, 1080), SyntheticExif(dateTime = time)),
        ),
    )

    private fun jpeg(dir: File, name: String) = file(
        dir,
        name,
        unique(dir, name, SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif())),
    )

    /** A MediaStore-like URI (`content://media.test/images/media/<id>`) for [file]. */
    private fun media(id: Int, file: Uri): Uri =
        Uri.parse("content://media.test/images/media/$id").also { tree.aliases[it.toString()] = File(file.path!!) }

    private suspend fun versionOf(outcome: HandoffOutcome) = (outcome as HandoffOutcome.OpenEditor).versionId

    private suspend fun edit(versionId: Long, ev: Float) {
        val to = Recipe(light = Light(exposureEv = ev))
        edits.commit(versionId, to, RecipeDiff.between(edits.recipe(versionId), to))
    }

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

    @Test
    fun `a different file with the same name opens as a new photo with an empty history`() = runTest {
        // Two cards (or a counter reset): DSC00001.JPG twice, from one MediaStore collection.
        val first = media(1, jpeg(temp.newFolder("card1"), "DSC00001.JPG"))
        val second = media(2, jpeg(temp.newFolder("card2"), "DSC00001.JPG"))
        val v1 = versionOf(repo.receive(HandoffRequest(listOf(first))))
        edit(v1, 0.7f)
        now = 2_000
        val v2 = versionOf(repo.receive(HandoffRequest(listOf(second))))
        assertThat(v2).isNotEqualTo(v1)
        assertThat(edits.recipe(v2)).isEqualTo(Recipe())
        assertThat(edits.history(v2).steps).isEmpty()
        // The first one is untouched: both are in the library, the first still edited.
        assertThat(rows()).hasSize(2)
        assertThat(edits.history(v1).steps).hasSize(1)
        assertThat(edits.recipe(v1).light.exposureEv).isEqualTo(0.7f)
    }

    @Test
    fun `the same file opened again keeps its photo, edits and history`() = runTest {
        val photo = media(7, jpeg(temp.newFolder("gallery"), "reveal-crop.jpg"))
        val v1 = versionOf(repo.receive(HandoffRequest(listOf(photo))))
        edit(v1, 0.5f)
        edit(v1, 1.0f)
        edits.undo(v1)
        now = 5_000
        val v2 = versionOf(repo.receive(HandoffRequest(listOf(photo))))
        assertThat(v2).isEqualTo(v1)
        val history = edits.history(v2)
        assertThat(history.steps).hasSize(2)
        assertThat(history.cursor).isEqualTo(0)
        assertThat(edits.recipe(v2).light.exposureEv).isEqualTo(0.5f)
        assertThat(rows().single().handoffAt).isEqualTo(5_000)
    }

    @Test
    fun `the same content through another uri or app is the same photo with its history`() = runTest {
        val dir = temp.newFolder("twice")
        val file = jpeg(dir, "DSC00077.JPG")
        // Opened from the gallery (MediaStore), then from a file manager (another provider, another name even).
        val gallery = media(77, file)
        val v1 = versionOf(repo.receive(HandoffRequest(listOf(gallery))))
        edit(v1, 0.4f)
        val copy = File(temp.newFolder("manager"), "renamed.jpg").apply { writeBytes(File(file.path!!).readBytes()) }
        now = 2_000
        val v2 = versionOf(repo.receive(HandoffRequest(listOf(Uri.fromFile(copy)))))
        assertThat(v2).isEqualTo(v1)
        assertThat(edits.history(v2).steps).hasSize(1)
        assertThat(edits.recipe(v2).light.exposureEv).isEqualTo(0.4f)
        val photo = rows().single()
        assertThat(db.photoDao().files(photo.id).map { it.relativePath }).containsExactly(Uri.fromFile(copy).toString())
        // And back through the gallery again.
        now = 3_000
        assertThat(versionOf(repo.receive(HandoffRequest(listOf(gallery))))).isEqualTo(v1)
        assertThat(rows()).hasSize(1)
    }

    @Test
    fun `a photo of an added folder handed over by another app opens with its edits, its files untouched`() = runTest {
        val dir = temp.newFolder("folder")
        val file = File(dir, "DSC00090.JPG").apply {
            writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()))
        }
        val folderSource = db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://test/tree/folder",
                rootDocumentId = "",
                displayName = "folder",
                volumeId = null,
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = true,
                addedAt = 0,
            ),
        )
        val tree = FileDocumentTree(dir)
        val indexer = LibraryIndexer(db)
        indexer.index(folderSource, tree)
        indexer.fillFingerprints(folderSource, tree)
        val folderPhoto = db.photoDao().filesByFingerprint(listOf(FileFingerprint.of(file.readBytes()))).single()
        val folderVersion = db.photoDao().firstVersion(folderPhoto.file.photoId)!!
        edit(folderVersion, 0.9f)

        val outcome = repo.receive(HandoffRequest(listOf(media(90, Uri.fromFile(file)))))
        assertThat(versionOf(outcome)).isEqualTo(folderVersion)
        assertThat(rows()).isEmpty()
        assertThat(db.photoDao().files(folderPhoto.file.photoId).single().sourceId).isEqualTo(folderSource)
    }

    @Test
    fun `a file replaced under the same uri is a new photo and does not inherit the recipe`() = runTest {
        val dir = temp.newFolder("replaced")
        val photo = jpeg(dir, "IMG_0001.jpg")
        val v1 = versionOf(repo.receive(HandoffRequest(listOf(photo))))
        edit(v1, 0.7f)
        // Another picture written to the same path: other size and mtime.
        File(photo.path!!).apply {
            writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(800, 600), SyntheticExif()))
            setLastModified(lastModified() + 60_000)
        }
        now = 2_000
        val v2 = versionOf(repo.receive(HandoffRequest(listOf(photo))))
        assertThat(v2).isNotEqualTo(v1)
        assertThat(edits.recipe(v2)).isEqualTo(Recipe())
        assertThat(edits.history(v2).steps).isEmpty()
        // The old photo had only that file, whose content is gone: it leaves the library.
        assertThat(rows().map { it.versionId }).containsExactly(v2)
    }

    @Test
    fun `a pair completed later attaches only when the stored file's content matches`() = runTest {
        val dir = temp.newFolder("strict")
        val jpg = media(30, jpeg(dir, "DSC00060.JPG"))
        val v1 = versionOf(repo.receive(HandoffRequest(listOf(jpg))))
        edit(v1, 0.2f)
        // A RAW+JPEG pair of the same name whose JPEG is another picture: a new photo, the first keeps its edit.
        val other = temp.newFolder("other")
        val arw = media(31, raw(other, "DSC00060.ARW"))
        val otherJpg =
            media(
                32,
                file(
                    other,
                    "DSC00060.JPG",
                    SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(640, 480), SyntheticExif()),
                ),
            )
        now = 2_000
        val v2 = versionOf(repo.receive(HandoffRequest(listOf(arw, otherJpg))))
        assertThat(v2).isNotEqualTo(v1)
        assertThat(edits.history(v2).steps).isEmpty()
        assertThat(rows()).hasSize(2)
        assertThat(edits.recipe(v1).light.exposureEv).isEqualTo(0.2f)
    }

    @Test
    fun `a pair completed in a later hand-off joins the photo it started as`() = runTest {
        val dir = temp.newFolder("later")
        val jpg = media(10, jpeg(dir, "DSC00042.JPG"))
        val arw = media(11, raw(dir, "DSC00042.ARW"))
        val v1 = versionOf(repo.receive(HandoffRequest(listOf(jpg))))
        edit(v1, 0.3f)
        now = 2_000
        val v2 = versionOf(repo.receive(HandoffRequest(listOf(arw, jpg))))
        assertThat(v2).isEqualTo(v1)
        val photo = rows().single()
        assertThat(photo.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(db.photoDao().files(photo.id).map { it.name }).containsExactly("DSC00042.ARW", "DSC00042.JPG")
        assertThat(edits.recipe(v2).light.exposureEv).isEqualTo(0.3f)
    }

    @Test
    fun `a RAW and its JPEG from one media collection pair in one request but not across requests`() = runTest {
        val dir = temp.newFolder("collection")
        val arw = media(20, raw(dir, "DSC00050.ARW"))
        val jpg = media(21, jpeg(dir, "DSC00050.JPG"))
        repo.receive(HandoffRequest(listOf(arw, jpg)))
        assertThat(rows().single().format).isEqualTo(PhotoFormat.RAW_PAIR)
        // A lone JPEG of the same name from elsewhere later is not glued to that pair.
        val stranger = media(22, jpeg(temp.newFolder("elsewhere"), "DSC00050.JPG"))
        now = 2_000
        repo.receive(HandoffRequest(listOf(stranger)))
        val photos = rows()
        assertThat(photos).hasSize(2)
        assertThat(photos.first { it.format == PhotoFormat.RAW_PAIR }.let { db.photoDao().files(it.id) }.size)
            .isEqualTo(2)
    }

    // ---- culling values from the sender (ADR 0011 "Culling")

    private fun stamp(uri: Uri, millis: Long) = check(File(uri.path!!).setLastModified(millis))

    @Test
    fun `the sender's culling values rate a photo that has no sidecar, keyed by any file of the pair`() = runTest {
        val dir = temp.newFolder("cull")
        val arw = raw(dir, "DSC00021.ARW")
        val jpg = jpeg(dir, "DSC00021.JPG")
        val other = raw(dir, "DSC00022.ARW")
        repo.receive(
            HandoffRequest(
                listOf(arw, jpg, other),
                culling = mapOf(
                    jpg to HandoffCulling(rating = 4, flag = PickFlag.PICK, label = "Red"),
                    other to HandoffCulling(flag = PickFlag.REJECT),
                ),
            ),
        )
        val photos = rows().associateBy { it.name }
        with(photos.getValue("DSC00021.ARW")) {
            assertThat(rating).isEqualTo(4)
            assertThat(flag).isEqualTo(PickFlag.PICK)
            assertThat(label).isEqualTo("Red")
        }
        with(photos.getValue("DSC00022.ARW")) {
            assertThat(rating).isEqualTo(0)
            assertThat(flag).isEqualTo(PickFlag.REJECT)
        }
    }

    @Test
    fun `on a first hand-off the values win over the sidecar, which fills what they leave out`() = runTest {
        val dir = temp.newFolder("first")
        val sidecar = file(dir, "DSC00031.xmp", SyntheticShoot.sidecar(rating = 2, label = "Blue").toByteArray())
        val bytes = File(sidecar.path!!).readBytes()
        val arw = raw(dir, "DSC00031.ARW")
        now = 5_000_000_000_000L
        repo.receive(HandoffRequest(listOf(arw, sidecar), culling = mapOf(arw to HandoffCulling(rating = 5))))
        val photo = rows().single()
        assertThat(photo.rating).isEqualTo(5)
        assertThat(photo.label).isEqualTo("Blue")
        // Nothing is written back: the sender's sidecar is as it was.
        assertThat(File(sidecar.path!!).readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `a sidecar changed after the previous hand-off wins, an older one does not`() = runTest {
        val dir = temp.newFolder("again")
        val sidecar = file(dir, "DSC00041.xmp", SyntheticShoot.sidecar(rating = 1).toByteArray())
        val arw = raw(dir, "DSC00041.ARW")
        stamp(sidecar, 1_000_000_000L)
        now = 2_000_000_000L
        repo.receive(HandoffRequest(listOf(arw, sidecar), culling = mapOf(arw to HandoffCulling(rating = 3))))
        assertThat(rows().single().rating).isEqualTo(3)

        // Sent again, sidecar untouched since: the sender's new values win.
        now = 3_000_000_000L
        repo.receive(HandoffRequest(listOf(arw, sidecar), culling = mapOf(arw to HandoffCulling(rating = 4))))
        assertThat(rows().single().rating).isEqualTo(4)

        // Someone rated it in the sidecar after that hand-off: the sidecar wins over the values sent now.
        File(sidecar.path!!).writeText(SyntheticShoot.sidecar(rating = 2, label = "Green", creatorTool = "darktable"))
        stamp(sidecar, 3_500_000_000L)
        now = 4_000_000_000L
        repo.receive(
            HandoffRequest(
                listOf(arw, sidecar),
                culling = mapOf(arw to HandoffCulling(rating = 5, flag = PickFlag.PICK)),
            ),
        )
        with(rows().single()) {
            assertThat(rating).isEqualTo(2)
            assertThat(label).isEqualTo("Green")
            assertThat(flag).isEqualTo(PickFlag.NONE)
        }
    }

    @Test
    fun `without values a re-sent photo keeps the rating set in Reveal, with values the sender's win`() = runTest {
        val dir = temp.newFolder("keep")
        val arw = raw(dir, "DSC00051.ARW")
        repo.receive(HandoffRequest(listOf(arw)))
        val id = rows().single().id
        db.photoDao().setRating(listOf(id), 2)
        now = 2_000L
        repo.receive(HandoffRequest(listOf(arw)))
        assertThat(rows().single().rating).isEqualTo(2)
        now = 3_000L
        repo.receive(HandoffRequest(listOf(arw), culling = mapOf(arw to HandoffCulling(label = "Yellow"))))
        with(rows().single()) {
            assertThat(rating).isEqualTo(2)
            assertThat(label).isEqualTo("Yellow")
        }
        now = 4_000L
        repo.receive(HandoffRequest(listOf(arw), culling = mapOf(arw to HandoffCulling(rating = 0, label = ""))))
        with(rows().single()) {
            assertThat(rating).isEqualTo(0)
            assertThat(label).isNull()
        }
    }

    @Test
    fun `a hidden sidecar item is read, the normal name wins when both are sent`() = runTest {
        val dir = temp.newFolder("hidden")
        val hidden = file(dir, ".DSC00061.xmp", SyntheticShoot.sidecar(rating = 3).toByteArray())
        repo.receive(HandoffRequest(listOf(raw(dir, "DSC00061.ARW"), hidden)))
        assertThat(rows().single { it.name == "DSC00061.ARW" }.rating).isEqualTo(3)

        val dir2 = temp.newFolder("both")
        val hidden2 = file(dir2, ".DSC00062.ARW.xmp", SyntheticShoot.sidecar(rating = 1).toByteArray())
        val normal = file(dir2, "DSC00062.xmp", SyntheticShoot.sidecar(rating = 5).toByteArray())
        repo.receive(HandoffRequest(listOf(hidden2, raw(dir2, "DSC00062.ARW"), normal)))
        val photo = rows().single { it.name == "DSC00062.ARW" }
        assertThat(photo.rating).isEqualTo(5)
        assertThat(db.photoDao().get(photo.id)!!.xmpDocumentId).isEqualTo(normal.toString())
    }
}
