package dev.hfps.reveal.data.handoff

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import java.io.File
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

/**
 * ADR 0011 "Amendment W4: local copies": Hoard hands over `content://media/...` URIs (Images, and Downloads for the
 * formats Images refuses) with a read grant only. Pairing across collections, the kept originals, no persistable
 * grants, identity by fingerprint, the access check, the gallery offer and chunked batches.
 */
@RunWith(AndroidJUnit4::class)
class HandoffMediaTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var originals: LocalOriginals
    private val tree = FileUriTree()
    private var now = 10_000L
    private val taken = mutableListOf<Int>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val resolver by lazy {
        object : ContentResolver(context) {
            override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
                taken += modeFlags
            }
        }
    }

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        originals = LocalOriginals(temp.newFolder("handoff-originals"))
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = HandoffRepository(
        resolver,
        db,
        LibraryIndexer(db) {
            now
        },
        tree,
        originals = originals,
    ) { now }

    private fun library() = LibraryRepository(
        context.contentResolver,
        db,
        null,
        { tree },
        SidecarSync(db, { tree }, "Reveal test"),
        CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        originals = originals,
    )

    private fun unique(name: String, bytes: ByteArray) = bytes + name.toByteArray()

    /** A CR3-looking file (ISO BMFF `ftyp crx `), as Canon's container starts. */
    private fun cr3Bytes(name: String) = unique(
        name,
        byteArrayOf(0, 0, 0, 24) + "ftypcrx ".toByteArray() + ByteArray(16),
    )

    private fun media(collection: String, id: Int, name: String, bytes: ByteArray): Uri {
        val file = File(temp.newFolder(), name).apply { writeBytes(bytes) }
        return Uri.parse("content://media/external_primary/$collection/$id").also { tree.aliases[it.toString()] = file }
    }

    private fun image(id: Int, name: String) = media(
        "images/media",
        id,
        name,
        unique(name, SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif())),
    )

    private fun download(id: Int, name: String) = media("downloads", id, name, cr3Bytes(name))

    private suspend fun rows() = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS))

    // ------------------------------------------------------------------ offer on receipt

    @Test
    fun `the gallery offer on receipt is asked once and only below full access`() {
        val offer = GalleryAccess.Companion::shouldOfferForMedia
        assertThat(offer(true, GalleryAccessState.NONE, false)).isTrue()
        assertThat(offer(true, GalleryAccessState.PARTIAL, false)).isTrue()
        assertThat(offer(true, GalleryAccessState.FULL, false)).isFalse()
        // Answered once (allowed or declined): never again, in any state.
        GalleryAccessState.entries.forEach { assertThat(offer(true, it, true)).isFalse() }
        // No MediaStore photo in a hand-off: nothing to offer.
        assertThat(offer(false, GalleryAccessState.NONE, false)).isFalse()
    }

    @Test
    fun `a hand-off with MediaStore photos is what the offer looks for`() = runTest {
        assertThat(db.photoDao().observeMediaHandoffs().first()).isFalse()
        repo().receive(HandoffRequest(listOf(image(1, "IMG_0001.JPG"), image(2, "IMG_0002.JPG"))))
        assertThat(db.photoDao().observeMediaHandoffs().first()).isTrue()
    }

    // ------------------------------------------------------------------ grants and pairing

    @Test
    fun `a MediaStore hand-off never takes a persistable grant`() = runTest {
        repo().receive(HandoffRequest(listOf(image(1, "IMG_0001.JPG")), persistable = true))
        assertThat(taken).isEmpty()
    }

    @Test
    fun `a CR3 in Downloads and its JPEG in Images are one pair although their parents differ`() = runTest {
        val raw = download(7, "IMG_0001.CR3")
        val jpeg = image(8, "IMG_0001.JPG")
        repo().receive(HandoffRequest(listOf(raw, jpeg), title = "Trip", initial = raw))
        val photo = rows().single()
        assertThat(photo.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(db.photoDao().files(photo.id).map { it.role }).containsExactly(FileRole.RAW, FileRole.RENDERED)
    }

    // ------------------------------------------------------------------ kept originals

    @Test
    fun `a Downloads original is kept while the grant lives and opens after it ended and after a restart`() = runTest {
        val raw = download(7, "IMG_0002.CR3")
        val before = tree.aliases.getValue(raw.toString()).readBytes()
        repo().receive(HandoffRequest(listOf(raw)))
        val photo = rows().single()
        val file = db.photoDao().files(photo.id).single()
        // Filed under the local copy (a file: URI in Reveal's own storage), identity untouched.
        assertThat(file.documentId).startsWith(Uri.fromFile(originals.dir).toString())
        assertThat(file.fingerprint).isNotNull()
        assertThat(originals.isLocal(file.documentId)).isTrue()
        assertThat(File(Uri.parse(file.documentId).path!!).readBytes()).isEqualTo(before)

        // The grant ends: the media URI is refused from now on, and the process restarts (new objects, same files).
        tree.refused += raw.toString()
        val restarted = library()
        assertThat(restarted.checkHandoffs()).isEqualTo(0)
        assertThat(db.photoDao().files(photo.id).single().unreadableAt).isNull()
        assertThat(
            tree.openInput(file.documentId, file.name).use {
                it.descriptor.statSize
            },
        ).isEqualTo(before.size.toLong())
        assertThat(restarted.backgroundAccessCheck(again = true).unreadable).isEqualTo(0)

        // The same photo handed over again reuses the copy (same size) and stays one photo.
        tree.refused -= raw.toString()
        repo().receive(HandoffRequest(listOf(raw)))
        assertThat(rows()).hasSize(1)
        assertThat(originals.files()).hasSize(1)
    }

    @Test
    fun `an Images file is read through the permission, never copied`() = runTest {
        repo().receive(HandoffRequest(listOf(image(1, "IMG_0003.JPG"))))
        assertThat(originals.files()).isEmpty()
        val file = db.photoDao().handoffFiles().single()
        assertThat(file.documentId).startsWith("content://media/")
    }

    @Test
    fun `a file that is not an image is not kept`() = runTest {
        val text = media("downloads", 9, "notes.CR3", "not an image at all, only text".toByteArray())
        repo().receive(HandoffRequest(listOf(text)))
        assertThat(originals.files()).isEmpty()
    }

    @Test
    fun `forgetting the photo deletes its kept original, nothing else`() = runTest {
        val raw = download(7, "IMG_0004.CR3")
        repo().receive(HandoffRequest(listOf(raw)))
        val stray = File(originals.dir, "keep-me.txt").apply { writeText("x") }
        val photo = rows().single()
        assertThat(originals.files()).hasSize(2)
        library().forget(listOf(photo.id))
        assertThat(rows()).isEmpty()
        assertThat(originals.files().map { it.name }).containsExactly(stray.name)
    }

    // ------------------------------------------------------------------ access check on MediaStore URIs

    @Test
    fun `a MediaStore file is readable through the permission, refused only on a real refusal, never on busy`() =
        runTest {
            val ok = image(1, "A.JPG")
            val busy = image(2, "B.JPG")
            val refused = image(3, "C.JPG")
            repo().receive(HandoffRequest(listOf(ok, busy, refused)))
            tree.busy[busy.toString()] = Int.MAX_VALUE
            tree.refused += refused.toString()
            val pass = library().backgroundAccessCheck()
            assertThat(pass.busy).isEqualTo(1)
            assertThat(pass.unreadable).isEqualTo(1)
            val files = db.photoDao().handoffFiles().associateBy { it.documentId }
            assertThat(files.getValue(ok.toString()).unreadableAt).isNull()
            assertThat(files.getValue(busy.toString()).unreadableAt).isNull()
            assertThat(files.getValue(refused.toString()).unreadableAt).isNotNull()
            // Access comes back (the owner allowed all photos): the next explicit pass reads it again.
            tree.refused -= refused.toString()
            library().backgroundAccessCheck(again = true)
            assertThat(db.photoDao().handoffFiles().all { it.unreadableAt == null }).isTrue()
        }

    // ------------------------------------------------------------------ identity

    @Test
    fun `a Hoard copy, an earlier hand-off and a Server photo of the same content are one photo`() = runTest {
        val bytes =
            unique("IMG_0005.JPG", SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()))
        // An earlier hand-off through Hoard's SMB streaming link (legacy).
        val legacy = Uri.parse("content://dev.hfps.hoard.stream/h/1/IMG_0005.JPG")
        tree.aliases[legacy.toString()] = File(temp.newFolder(), "IMG_0005.JPG").apply { writeBytes(bytes) }
        repo().receive(HandoffRequest(listOf(legacy), title = "Old"))
        val first = rows().single()
        // The copy Hoard 2.2.0 made into MediaStore: same content, another URI.
        val copy = media("images/media", 5, "IMG_0005.JPG", bytes)
        repo().receive(HandoffRequest(listOf(copy), title = "New"))
        val second = rows().single()
        assertThat(second.id).isEqualTo(first.id)
        assertThat(db.photoDao().files(second.id).map { it.documentId }).containsExactly(copy.toString())

        // A Server photo of the same content (the NAS original): the hand-off opens that photo, no duplicate.
        val print = db.photoDao().files(second.id).single().fingerprint!!
        val serverId = db.sourceDao().insert(
            SourceEntity(0, "server:", "", "", null, null, SourceState.AVAILABLE, false, 0, kind = SourceKind.SERVER),
        )
        val serverPhoto = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = serverId,
                folder = "k",
                folderDocumentId = "",
                stemKey = "img_0005",
                name = "IMG_0005.JPG",
                format = PhotoFormat.JPEG,
                hasRaw = false,
                sortTime = 0,
                addedAt = 0,
            ),
        )
        db.photoDao().insertVersion(VersionEntity(photoId = serverPhoto, createdAt = 1))
        db.photoDao().insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = serverPhoto,
                    sourceId = serverId,
                    relativePath = "$print/IMG_0005.JPG",
                    documentId = "$print/IMG_0005.JPG",
                    name = "IMG_0005.JPG",
                    size = bytes.size.toLong(),
                    modified = 0,
                    role = FileRole.RENDERED,
                    fingerprint = print,
                    onServer = true,
                ),
            ),
        )
        db.photoDao().deletePhotos(listOf(second.id))
        repo().receive(HandoffRequest(listOf(copy), title = "Newest"))
        // The Server photo took the hand-off (its title); nothing was filed under the hand-off source.
        assertThat(db.photoDao().handoffFiles()).isEmpty()
        assertThat(db.photoDao().get(serverPhoto)!!.handoffTitle).isEqualTo("Newest")
    }

    // ------------------------------------------------------------------ chunks of one batch (URI items only)

    @Test
    fun `URI chunks of one batch share the stamp and title, each applies its own culling, the initial opens from 0`() =
        runTest {
            val chunks = (0..2).map { c -> (1..3).map { image(c * 10 + it, "IMG_%04d.JPG".format(c * 10 + it)) } }
            val batches = HandoffBatches(db) { now }
            val key = "dev.hfps.hoard\u0000Shoot"
            val outcomes = chunks.mapIndexed { index, uris ->
                val join = batches.join(key, expected = 3)
                val request = HandoffRequest(
                    uris = uris,
                    title = "Shoot",
                    initial = uris.first().takeIf { index == 0 },
                    batchIndex = index,
                    batchCount = 3,
                    culling = mapOf(uris.first() to HandoffCulling(rating = index + 1, flag = PickFlag.PICK)),
                    batchAt = join.at,
                )
                repo().receive(request).also { assertThat(request.silentMember).isEqualTo(index > 0) }
            }
            // Only index 0 opened a photo (its initial URI); the others are silent members of the library batch.
            assertThat(outcomes[0]).isInstanceOf(HandoffOutcome.OpenEditor::class.java)
            val all = rows()
            assertThat(all).hasSize(9)
            assertThat(all.map { it.handoffAt }.distinct()).containsExactly(10_000L)
            assertThat(all.map { it.handoffTitle }.distinct()).containsExactly("Shoot")
            // Culling per chunk: chunk c rated its first photo c + 1 stars.
            assertThat(all.filter { it.flag == PickFlag.PICK }.map { it.rating }.sorted()).containsExactly(1, 2, 3)
        }

    @Test
    fun `the newest batch is never cut by the retention, however big`() = runTest {
        val uris = (1..HandoffRepository.MAX_PHOTOS + 20).map { image(it, "IMG_%04d.JPG".format(it)) }
        repo().receive(HandoffRequest(uris, title = "Big"))
        assertThat(rows()).hasSize(HandoffRepository.MAX_PHOTOS + 20)
        // A later, small batch pushes the older, bigger one past the cap: the oldest of it go, never the newest.
        now += 1_000
        repo().receive(HandoffRequest(listOf(image(9_999, "NEW.JPG")), title = "Small"))
        assertThat(rows().count { it.handoffTitle == "Small" }).isEqualTo(1)
        assertThat(rows().size).isEqualTo(HandoffRepository.MAX_PHOTOS)
    }
}
