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
import kotlinx.coroutines.async
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

    private fun media(collection: String, id: Int, name: String, bytes: ByteArray, path: String? = null): Uri {
        val file = File(temp.newFolder(), name).apply { writeBytes(bytes) }
        return Uri.parse("content://media/external_primary/$collection/$id").also {
            tree.aliases[it.toString()] = file
            tree.relativePaths[it.toString()] =
                path ?: if (collection == "downloads") HOARD_DOWNLOADS else HOARD_PICTURES
        }
    }

    private fun jpegBytes(name: String) =
        unique(name, SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()))

    private fun image(id: Int, name: String, path: String? = null) =
        media("images/media", id, name, jpegBytes(name), path)

    private fun download(id: Int, name: String, path: String? = null) =
        media("downloads", id, name, cr3Bytes(name), path)

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
        // No MediaStore photo from Hoard: nothing to offer.
        assertThat(offer(false, GalleryAccessState.NONE, false)).isFalse()
    }

    @Test
    fun `the media offer is Hoard's Images documents only, with its own answer`() {
        assertThat(HandoffApps.isHoard("dev.hfps.hoard")).isTrue()
        assertThat(HandoffApps.isHoard("dev.hfps.hoard.debug")).isTrue()
        assertThat(HandoffApps.isHoard("dev.hfps.hoardx")).isFalse()
        assertThat(HandoffApps.isHoard("com.sec.android.gallery3d")).isFalse()
        assertThat(HandoffApps.referrerPackage(Uri.parse("android-app://dev.hfps.hoard"))).isEqualTo("dev.hfps.hoard")
        assertThat(HandoffApps.referrerPackage(Uri.parse("https://example.com"))).isNull()
        assertThat(GalleryAccess.isMediaImages("content://media/external_primary/images/media/7")).isTrue()
        assertThat(GalleryAccess.isMediaImages("content://media/external_primary/downloads/7")).isFalse()
        assertThat(GalleryAccess.isMediaImages("content://dev.hfps.hoard.stream/h/1/A.JPG")).isFalse()

        val access = GalleryAccess(context).apply { resetAskedForTests() }
        access.noteHoardMedia()
        assertThat(access.hoardMedia.value).isTrue()
        // Answering the old offer (an unreadable gallery share) does not answer Hoard's, and the other way round.
        access.setAsked()
        assertThat(access.mediaAsked.value).isFalse()
        access.resetAskedForTests()
        access.setMediaAsked()
        assertThat(access.asked.value).isFalse()
        assertThat(GalleryAccess(context).mediaAsked.value).isTrue()
        access.resetAskedForTests()
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

    @Test
    fun `MediaStore pairing keys on the folder below the collection, so a camera's file never pairs with Hoard's`() =
        runTest {
            // A camera JPEG in DCIM and a Hoard CR3 of the same stem: two photos.
            val camera = image(1, "IMG_0001.JPG", path = "DCIM/Camera/")
            val hoard = download(2, "IMG_0001.CR3")
            repo().receive(HandoffRequest(listOf(camera, hoard), title = "Mixed"))
            assertThat(rows()).hasSize(2)

            fun key(path: String?) = HandoffRepository.parentKey(
                Uri.parse("content://media/external_primary/images/media/1"),
                path,
            )
            assertThat(key("Pictures/Hoard/Trip/")).isEqualTo(key("Download/Hoard/Trip/"))
            assertThat(key("Pictures/Hoard/Trip/")).isNotEqualTo(key("Pictures/Hoard/Other/"))
            assertThat(key("DCIM/Camera/")).isNotEqualTo(key("Pictures/Camera/Sub/"))
            // Files at a collection's root pair only within that root.
            assertThat(key("DCIM/")).isNotEqualTo(key("Pictures/"))
            // Without its path (not readable) a MediaStore URI pairs only within its collection.
            assertThat(
                HandoffRepository.parentKey(Uri.parse("content://media/external_primary/downloads/2"), null),
            ).isNotEqualTo(key(null))
        }

    @Test
    fun `a RAW in a later chunk joins its JPEG from an earlier chunk of the same batch`() = runTest {
        val jpeg = image(1, "IMG_0009.JPG")
        val raw = download(2, "IMG_0009.CR3")
        val other = image(3, "IMG_0010.JPG")
        repo().receive(HandoffRequest(listOf(jpeg, other), title = "Trip", batchAt = 5_000L, batchIndex = 0))
        repo().receive(HandoffRequest(listOf(raw), title = "Trip", batchAt = 5_000L, batchIndex = 1))
        val photos = rows().associateBy { it.name }
        assertThat(photos.keys).containsExactly("IMG_0009.CR3", "IMG_0010.JPG")
        val pair = photos.getValue("IMG_0009.CR3")
        assertThat(pair.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(db.photoDao().files(pair.id).map { it.role }).containsExactly(FileRole.RAW, FileRole.RENDERED)
        // Another batch (another stamp) with the same names is not joined.
        repo().receive(HandoffRequest(listOf(download(4, "IMG_0010.CR3")), title = "Trip", batchAt = 6_000L))
        assertThat(rows()).hasSize(3)
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

    @Test
    fun `a Downloads file of a folder's photo is not copied`() = runTest {
        val bytes = cr3Bytes("IMG_0011.CR3")
        val folder = temp.newFolder("card")
        File(folder, "IMG_0011.CR3").writeBytes(bytes)
        val sourceId = db.sourceDao().insert(
            SourceEntity(0, "content://test/tree/card", "", "card", null, null, SourceState.AVAILABLE, false, 0),
        )
        val folderTree = dev.hfps.reveal.data.FileDocumentTree(folder)
        LibraryIndexer(db).index(sourceId, folderTree)
        LibraryIndexer(db).fillFingerprints(sourceId, folderTree)

        repo().receive(HandoffRequest(listOf(media("downloads", 11, "IMG_0011.CR3", bytes))))

        assertThat(originals.files()).isEmpty()
        assertThat(db.photoDao().handoffFiles()).isEmpty()
    }

    @Test
    fun `a kept copy is filed again when the URI cannot be read, and forgetting deletes it whatever the row says`() =
        runTest {
            val raw = download(7, "IMG_0012.CR3")
            repo().receive(HandoffRequest(listOf(raw)))
            assertThat(originals.files()).hasSize(1)
            // Handed over again while the URI cannot be opened for a copy (the grant is gone by the time it is read):
            // its stat and fingerprint are known from before (the row keeps the URI), the kept copy is filed again.
            tree.refusedOpens += raw.toString()
            repo().receive(HandoffRequest(listOf(raw)))
            val file = db.photoDao().files(rows().single().id).single()
            assertThat(originals.isLocal(file.documentId)).isTrue()
            assertThat(originals.files()).hasSize(1)

            // A copy whose row was never repointed (a kill between the copy and the update) is still deleted.
            db.photoDao().repointFile(file.sourceId, file.documentId, raw.toString())
            library().forget(listOf(rows().single().id))
            assertThat(originals.files()).isEmpty()
        }

    @Test
    fun `a copy is refused below the free-space margin and reported, the photo keeps its URI`() = runTest {
        originals = LocalOriginals(temp.newFolder("full"), margin = 1_000, usableSpace = { 500 })
        val raw = download(7, "IMG_0013.CR3")
        val received = repo().receiveStaged(HandoffRequest(listOf(raw)))
        assertThat(received.settled.await().noSpace).isEqualTo(1)
        assertThat(originals.files()).isEmpty()
        assertThat(db.photoDao().handoffFiles().single().documentId).isEqualTo(raw.toString())
    }

    @Test
    fun `temporary files of an interrupted copy are swept, an extensionless Downloads item is kept by its bytes`() =
        runTest {
            val stale = File(originals.dir.apply { mkdirs() }, "1234.tmp").apply { writeText("half") }
            // Swept when the store is created (the app's start), never by a copy.
            originals = LocalOriginals(originals.dir)
            val unnamed = media("downloads", 14, "IMG_0014", cr3Bytes("IMG_0014"))
            tree.names[unnamed.toString()] = "IMG_0014"
            repo().receive(HandoffRequest(listOf(unnamed)))
            assertThat(stale.exists()).isFalse()
            val photo = rows().single()
            assertThat(photo.format).isEqualTo(PhotoFormat.RAW)
            assertThat(originals.files()).hasSize(1)
        }

    @Test
    fun `copies running together keep their temporary files, only a new store sweeps old ones`() = runTest {
        val dir = originals.dir.apply { mkdirs() }
        val sources = (1..2).map { i -> File(temp.newFolder(), "IMG_00$i.CR3").apply { writeBytes(cr3Bytes(name)) } }
        // Each copy waits until both have their temporary file: a sweep from a copy would delete the other's.
        val bothStarted = java.util.concurrent.CyclicBarrier(2)
        val store = LocalOriginals(dir, usableSpace = {
            bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Long.MAX_VALUE
        })
        val stale = File(dir, "old.tmp").apply { writeText("half") }
        val copies = sources.mapIndexed { i, file ->
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
                store.copyIn(dev.hfps.image.ImageInput.fromFile(file), "print$i", file.name)
            }
        }
        val kept = copies.map { it.await() }
        assertThat(kept.map { it?.name }).containsExactly("print0.cr3", "print1.cr3")
        assertThat(kept.all { it!!.length() == sources[0].length() || it.length() == sources[1].length() }).isTrue()
        // Created before the copies began, after this store's own sweep: only the next store removes it.
        assertThat(stale.exists()).isTrue()
        LocalOriginals(dir)
        assertThat(stale.exists()).isFalse()
        assertThat(store.files().map { it.name }).containsExactly("print0.cr3", "print1.cr3")
    }

    /**
     * A batch of Downloads originals received through [HandoffIngests]; the copies after the first wait until the
     * activity is gone ([HandoffIngests.Ingest.holderGone]); the sender's grant lasts while someone holds it.
     */
    private suspend fun receiveThenLeave(serviceHolds: Boolean): Pair<HandoffRepository.Settled, List<Uri>> {
        val scope = CoroutineScope(Dispatchers.IO)
        val staged =
            HandoffRepository(resolver, db, LibraryIndexer(db) { now }, tree, null, originals, background = scope) {
                now
            }
        val ingests = HandoffIngests(scope, staged, HandoffBatches(db) { now })
        val uris = (1..3).map { download(40 + it + if (serviceHolds) 10 else 0, "IMG_%04d.CR3".format(40 + it)) }
        val activityGone = java.util.concurrent.CountDownLatch(1)
        val opened = HashMap<String, Int>()
        lateinit var ingest: HandoffIngests.Ingest
        tree.onOpen = { id ->
            // The first open is the fingerprint; the copy of a photo after the first waits for the activity to go.
            val n = synchronized(opened) { opened.merge(id, 1, Int::plus)!! }
            if (n == 2 && id != uris.first().toString()) {
                activityGone.await(GATE_S, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        tree.granted = { id -> uris.none { it.toString() == id } || ingest.holderAlive }
        // The initial photo is the one copied before the outcome (the head); the others are copied after it.
        val request = HandoffRequest(uris, initial = uris.first())
        ingest = ingests.receive("k$serviceHolds", request, batchKey = null, hoardMedia = false)
        ingest.await()
        val service = if (serviceHolds) ingest.handOver() else null
        ingest.holderGone()
        activityGone.countDown()
        val settled = ingest.awaitSettled()
        service?.invoke()
        tree.onOpen = {}
        tree.granted = { true }
        return settled to uris
    }

    @Test
    fun `a request received again after the process died reuses its photos and copies`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO)

        // A new HandoffIngests per process: the redelivered start finds no ingest and receives its intent again.
        fun process() = HandoffIngests(
            scope,
            HandoffRepository(resolver, db, LibraryIndexer(db) { now }, tree, null, originals, background = scope) {
                now
            },
            HandoffBatches(db) { now },
        )
        val uris = (1..2).map { download(80 + it, "IMG_%04d.CR3".format(80 + it)) }
        val request = HandoffRequest(uris, title = "Trip")
        process().receive("k", request, batchKey = null, hoardMedia = false).awaitSettled()
        val before = db.photoDao().handoffFiles().sortedBy { it.relativePath }
        val copies = before.map { java.io.File(Uri.parse(it.documentId).path!!) }
        assertThat(copies.all { it.isFile }).isTrue()
        copies.forEach { it.setLastModified(1_000L) }

        val again = process().receive("k", request, batchKey = null, hoardMedia = false)
        assertThat(again.awaitSettled().notCopied).isEqualTo(0)
        val after = db.photoDao().handoffFiles().sortedBy { it.relativePath }
        assertThat(after.map { it.photoId to it.documentId }).isEqualTo(before.map { it.photoId to it.documentId })
        assertThat(rows()).hasSize(uris.size)
        // The copies were reused, not written again.
        assertThat(copies.map { it.lastModified() }).containsExactly(1_000L, 1_000L)
    }

    @Test
    fun `the grant service keeps the grant after the activity finishes, so every copy is made`() = runTest {
        val (settled, uris) = receiveThenLeave(serviceHolds = true)
        assertThat(settled.notCopied).isEqualTo(0)
        val files = db.photoDao().handoffFiles()
        assertThat(files).hasSize(uris.size)
        assertThat(files.all { originals.isLocal(it.documentId) }).isTrue()
    }

    @Test
    fun `without the service the copies the ended grant refused are counted for the notice`() = runTest {
        val (settled, uris) = receiveThenLeave(serviceHolds = false)
        // The first photo was copied before the outcome; the other two met the ended grant.
        assertThat(settled.notCopied).isEqualTo(2)
        val files = db.photoDao().handoffFiles()
        assertThat(files.count { originals.isLocal(it.documentId) }).isEqualTo(1)
        assertThat(files.filterNot { originals.isLocal(it.documentId) }.map { it.documentId })
            .containsExactlyElementsIn(uris.drop(1).map { it.toString() })
    }

    @Test
    fun `files at a collection's root pair only with the same root`() {
        val uri = Uri.parse("content://media/external_primary/images/media/1")
        val key = HandoffRepository.Companion::parentKey
        assertThat(key(uri, "Pictures/")).isEqualTo(key(uri, "Pictures"))
        assertThat(key(uri, "Pictures/")).isNotEqualTo(key(uri, "DCIM/"))
        assertThat(key(uri, "Download/")).isNotEqualTo(key(uri, "Pictures/"))
        // Not the key of a subfolder either, and Hoard's two collections still pair.
        assertThat(key(uri, "Pictures/")).isNotEqualTo(key(uri, "Pictures/Hoard/"))
        assertThat(key(uri, HOARD_PICTURES)).isEqualTo(key(uri, HOARD_DOWNLOADS))
    }

    @Test
    fun `a RAW that joins its JPEG from an earlier chunk drops the JPEG's own display preview`() = runTest {
        val making = object : PreviewMaking {
            override fun display(raw: dev.hfps.image.ImageInput?, rendered: dev.hfps.image.ImageInput?, out: File) {
                out.writeBytes(byteArrayOf(1))
            }

            override fun smart(main: dev.hfps.image.ImageInput, out: File) = out.writeBytes(byteArrayOf(2))
        }
        val previews = LocalPreviews(temp.newFolder("previews"))
        val scope = CoroutineScope(Dispatchers.IO)
        val previewer = HandoffPreviewer(tree, previews, scope, making)
        val staged = HandoffRepository(resolver, db, LibraryIndexer(db) { now }, tree, previewer, originals) { now }
        val jpeg = image(60, "IMG_0060.JPG")

        // One photo opens the editor: smart previews would wait for it; this test opens and closes none.
        fun noEditor() {
            previewer.editorOpened()
            previewer.editorClosed()
        }
        staged.receive(HandoffRequest(listOf(jpeg), title = "Trip", batchAt = 7_000L, batchIndex = 0))
        noEditor()
        previewer.idle()
        val jpegPrint = db.photoDao().handoffFiles().single().fingerprint!!
        assertThat(previews.display(jpegPrint)).isNotNull()

        val raw = download(61, "IMG_0060.CR3")
        staged.receive(HandoffRequest(listOf(raw), title = "Trip", batchAt = 7_000L, batchIndex = 1))
        noEditor()
        previewer.idle()
        val photo = rows().single()
        val rawPrint = db.photoDao().files(photo.id).single { it.role == FileRole.RAW }.fingerprint!!
        assertThat(previews.display(rawPrint)).isNotNull()
        assertThat(previews.display(jpegPrint)).isNull()
    }

    @Test
    fun `a joined JPEG keeps its display preview while the RAW's preview fails`() = runTest {
        val making = object : PreviewMaking {
            override fun display(raw: dev.hfps.image.ImageInput?, rendered: dev.hfps.image.ImageInput?, out: File) {
                check(raw == null) { "the RAW cannot be decoded" }
                out.writeBytes(byteArrayOf(1))
            }

            override fun smart(main: dev.hfps.image.ImageInput, out: File) = out.writeBytes(byteArrayOf(2))
        }
        val previews = LocalPreviews(temp.newFolder("previews"))
        val previewer = HandoffPreviewer(tree, previews, CoroutineScope(Dispatchers.IO), making)
        val staged = HandoffRepository(resolver, db, LibraryIndexer(db) { now }, tree, previewer, originals) { now }
        staged.receive(HandoffRequest(listOf(image(62, "IMG_0062.JPG")), title = "Trip", batchAt = 7_000L))
        previewer.editorOpened()
        previewer.editorClosed()
        previewer.idle()
        val jpegPrint = db.photoDao().handoffFiles().single().fingerprint!!
        assertThat(previews.display(jpegPrint)).isNotNull()

        staged.receive(
            HandoffRequest(listOf(download(63, "IMG_0062.CR3")), title = "Trip", batchAt = 7_000L, batchIndex = 1),
        )
        previewer.idle()
        val rawPrint = db.photoDao().files(rows().single().id).single { it.role == FileRole.RAW }.fingerprint!!
        assertThat(previews.display(rawPrint)).isNull()
        // Nothing replaced it: the photo still shows the JPEG's preview.
        assertThat(previews.display(jpegPrint)).isNotNull()
    }

    @Test
    fun `an earlier chunk's queued preview of a JPEG does nothing once a RAW joined it`() = runTest {
        val gate = java.util.concurrent.CountDownLatch(1)
        val made = java.util.Collections.synchronizedList(mutableListOf<String>())
        val making = object : PreviewMaking {
            override fun display(raw: dev.hfps.image.ImageInput?, rendered: dev.hfps.image.ImageInput?, out: File) {
                if (raw == null && rendered == null) gate.await() // The shot ahead of the queue.
                made += when {
                    raw != null -> "raw"
                    rendered != null -> "jpeg"
                    else -> "ahead"
                }
                out.writeBytes(byteArrayOf(1))
            }

            override fun smart(main: dev.hfps.image.ImageInput, out: File) = out.writeBytes(byteArrayOf(2))
        }
        val previews = LocalPreviews(temp.newFolder("previews"))
        val scope = CoroutineScope(Dispatchers.IO)
        val previewer = HandoffPreviewer(tree, previews, scope, making)
        val staged = HandoffRepository(
            resolver,
            db,
            LibraryIndexer(db) { now },
            tree,
            previewer,
            originals,
            background = scope,
        ) { now }
        // Another shot holds the queue, so the JPEG's display preview is still queued when the RAW joins it.
        val ahead = HandoffPreviewer.Document("content://none/ahead", "ahead.jpg")
        previewer.schedule(listOf(HandoffPreviewer.Shot("ahead", ahead, null, null, smart = false)))
        staged.receiveStaged(HandoffRequest(listOf(image(64, "IMG_0064.JPG")), title = "Trip", batchAt = 7_000L))
        val jpegPrint = db.photoDao().handoffFiles().single().fingerprint!!
        staged.receiveStaged(
            HandoffRequest(listOf(download(65, "IMG_0064.CR3")), title = "Trip", batchAt = 7_000L, batchIndex = 1),
        )
        gate.countDown()
        previewer.editorOpened()
        previewer.editorClosed()
        previewer.idle()
        val rawPrint = db.photoDao().files(rows().single().id).single { it.role == FileRole.RAW }.fingerprint!!
        assertThat(made).containsExactly("ahead", "raw").inOrder()
        assertThat(previews.display(rawPrint)).isNotNull()
        assertThat(previews.display(jpegPrint)).isNull()
        assertThat(previews.smart(jpegPrint)).isNull()
    }

    @Test
    fun `a silent chunk settles once its display previews are made, smart ones only while its grant lives`() = runTest {
        val displays = java.util.concurrent.atomic.AtomicInteger()
        val smarts = java.util.concurrent.atomic.AtomicInteger()
        val making = object : PreviewMaking {
            override fun display(raw: dev.hfps.image.ImageInput?, rendered: dev.hfps.image.ImageInput?, out: File) {
                displays.incrementAndGet()
                out.writeBytes(byteArrayOf(1))
            }

            override fun smart(main: dev.hfps.image.ImageInput, out: File) {
                smarts.incrementAndGet()
                out.writeBytes(byteArrayOf(2))
            }
        }
        val previews = LocalPreviews(temp.newFolder("previews"))
        val scope = CoroutineScope(Dispatchers.IO)
        val previewer = HandoffPreviewer(tree, previews, scope, making)
        val staged = HandoffRepository(
            resolver,
            db,
            LibraryIndexer(db) { now },
            tree,
            previewer,
            originals,
            background = scope,
        ) { now }
        val chunk0 = (1..3).map { image(it, "IMG_%04d.JPG".format(20 + it)) }
        val chunk1 = (4..6).map { image(it, "IMG_%04d.JPG".format(20 + it)) }
        // An editor is open (chunk 0's photo): smart previews wait, display images do not.
        previewer.editorOpened()
        staged.receiveStaged(HandoffRequest(chunk0, title = "Trip", batchAt = 7_000L, batchIndex = 0))
            .settled.await()

        // Chunk 1: its activity holds the grant until its display previews are made, then finishes.
        var alive = true
        val received = staged.receiveStaged(
            HandoffRequest(chunk1, title = "Trip", batchAt = 7_000L, batchIndex = 1),
        ) { alive }
        received.settled.await()
        val prints = chunk1.map { uri -> db.photoDao().handoffFiles().single { it.documentId == uri.toString() } }
            .map { it.fingerprint }
        assertThat(prints.all { previews.display(it) != null }).isTrue()
        // The activity finishes and the grant is revoked: no smart preview is attempted on those URIs any more;
        // chunk 0's holder (the visible activity) still lives, so its smart previews are made.
        alive = false
        tree.refused += chunk1.map { it.toString() }
        tree.opens.clear()
        previewer.editorClosed()
        previewer.idle()
        assertThat(prints.none { previews.smart(it) != null }).isTrue()
        assertThat(tree.opens.filter { id -> chunk1.any { it.toString() == id } }).isEmpty()
        assertThat(smarts.get()).isEqualTo(chunk0.size)
        assertThat(displays.get()).isEqualTo(6)
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

    private companion object {
        const val HOARD_PICTURES = "Pictures/Hoard/Trip/"
        const val GATE_S = 30L
        const val HOARD_DOWNLOADS = "Download/Hoard/Trip/"
    }
}
