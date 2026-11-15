package dev.hfps.reveal.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.handoff.FileUriTree
import dev.hfps.reveal.data.handoff.HandoffCulling
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.xmp.XmpDevelop
import dev.hfps.reveal.data.xmp.XmpDom
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpPreferences
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** ADR 0026 "Export XMP": no automatic writes any more; the explicit export is the only writer. */
@RunWith(AndroidJUnit4::class)
class XmpExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var shoot: File
    private lateinit var out: File
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        shoot = temp.newFolder("shoot")
        out = temp.newFolder("out")
    }

    @After
    fun tearDown() = db.close()

    private fun sync() = SidecarSync(db, { FileDocumentTree(shoot) }, "Reveal 0.9.0", writeEdits = { true }) {
        OffsetDateTime.parse("2026-11-15T10:00:00Z")
    }

    private fun export() = XmpExport(db, sync()) { FileDocumentTree(File(it)) }

    private suspend fun source(writable: Boolean, kind: SourceKind = SourceKind.FOLDER) = db.sourceDao().insert(
        SourceEntity(
            0, "content://t/${System.nanoTime()}", "", "shoot", null, null, SourceState.AVAILABLE, writable, 0,
            kind = kind,
        ),
    )

    private suspend fun raw(stem: String) {
        File(shoot, "$stem.ARW").writeBytes(SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
    }

    private suspend fun indexed(writable: Boolean = true): List<Long> {
        val id = source(writable)
        LibraryIndexer(db).index(id, FileDocumentTree(shoot))
        return db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).map { it.id }.distinct()
    }

    private fun sidecars(dir: File) = dir.list().orEmpty().filter { it.endsWith(".xmp") }.sorted()

    /** Every create and write that reaches a tree. */
    private class RecordingTree(private val inner: DocumentTree) : DocumentTree by inner {
        val writes = mutableListOf<String>()

        override fun writeBytes(documentId: String, bytes: ByteArray) {
            writes += "write $documentId"
            inner.writeBytes(documentId, bytes)
        }

        override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry {
            writes += "create $name"
            return inner.create(parentDocumentId, name, mimeType)
        }
    }

    /** A provider's de-duplication: a taken name gets ` (1)`, ` (2)`, ... as SAF providers do. */
    private class DedupingTree(private val root: File) : DocumentTree by FileDocumentTree(root) {
        override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry {
            val dir = if (parentDocumentId.isEmpty()) root else File(root, parentDocumentId)
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.')
            val free = generateSequence(0) { it + 1 }
                .map { n -> if (n == 0) name else "$stem ($n).$ext" }
                .first { !File(dir, it).exists() }
            return FileDocumentTree(root).create(parentDocumentId, free, mimeType)
        }
    }

    @Test
    fun `rating, flagging, labels, edits, hand-offs and merges write no sidecar on their own`() = runTest {
        raw("DSC00001")
        val card = File(shoot, "DSC00002.JPG").apply {
            writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(60, 40), SyntheticExif()) + "2".toByteArray())
        }
        val folderTree = RecordingTree(FileDocumentTree(shoot))
        val handoffFiles = FileUriTree()
        val handoffTree = RecordingTree(handoffFiles)
        val trees = DocumentTreeFactory { source -> if (source.kind == SourceKind.FOLDER) folderTree else handoffTree }
        val library = LibraryRepository(
            context.contentResolver,
            db,
            null,
            trees,
            SidecarSync(db, trees, "Reveal 0.9.0", writeEdits = { true }),
            CoroutineScope(Dispatchers.Unconfined),
        )
        val sourceId = source(writable = true)
        LibraryIndexer(db).index(sourceId, folderTree)
        LibraryIndexer(db).fillFingerprints(sourceId, folderTree)
        val ids = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).map { it.id }.distinct()

        // The library's actions.
        library.setRating(ids, 4)
        library.setFlag(ids, PickFlag.PICK)
        val v = db.photoDao().firstVersion(ids.first())!!
        val edit = Recipe(light = Light(exposureEv = 1f))
        EditRepository(db) { 1_778_000_000_000L }.commit(v, edit, RecipeDiff.between(Recipe(), edit))

        // A hand-off of a folder file with a label (folder first: the folder's photo takes the values).
        val handoffs = HandoffRepository(context.contentResolver, db, LibraryIndexer(db), handoffTree) { 2_000L }
        val sameAsFolder = Uri.parse("content://media/external/images/media/2")
        handoffFiles.aliases[sameAsFolder.toString()] = card
        handoffs.receive(
            HandoffRequest(
                listOf(sameAsFolder),
                culling = mapOf(sameAsFolder to HandoffCulling(rating = 2, label = "Red")),
            ),
        )
        // A hand-off first, then the same file appears in the folder: merged.
        val staged = File(temp.newFolder("staging"), "DSC00003.JPG").apply {
            writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(60, 40), SyntheticExif()) + "3".toByteArray())
        }
        val first = Uri.parse("content://media/external/images/media/3")
        handoffFiles.aliases[first.toString()] = staged
        handoffs.receive(
            HandoffRequest(listOf(first), culling = mapOf(first to HandoffCulling(rating = 5, label = "Blue"))),
        )
        staged.copyTo(File(shoot, "DSC00003.JPG"))
        LibraryIndexer(db).index(sourceId, folderTree)
        LibraryIndexer(db).fillFingerprints(sourceId, folderTree)
        assertThat(library.mergeHandoffs(sourceId).merged).isEqualTo(1)

        val photos = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).associateBy { it.name }
        assertThat(photos.getValue("DSC00002.JPG").label).isEqualTo("Red")
        assertThat(photos.getValue("DSC00003.JPG").label).isEqualTo("Blue")
        assertThat(folderTree.writes).isEmpty()
        assertThat(handoffTree.writes).isEmpty()
        assertThat(sidecars(shoot)).isEmpty()
    }

    @Test
    fun `next to the photos writes culling and the edit, and keeps what the sidecar already has`() = runTest {
        raw("DSC00001")
        raw("DSC00002")
        File(shoot, "DSC00002.xmp").writeText(
            SyntheticShoot.sidecar(rating = 1, label = "Blue", creatorTool = "darktable"),
        )
        val ids = indexed()
        db.photoDao().setRating(ids, 3)
        val edit = Recipe(light = Light(exposureEv = 0.5f))
        val first = ids.first { db.photoDao().get(it)!!.name.startsWith("DSC00001") }
        EditRepository(db) { 1_778_000_000_000L }
            .commit(db.photoDao().firstVersion(first)!!, edit, RecipeDiff.between(Recipe(), edit))
        val scope = export().analyse(ids)
        assertThat(scope.nextToPhotos).isTrue()
        val outcome = export().run(ids, XmpDestination.NextToPhotos())
        assertThat(outcome.written).isEqualTo(2)
        assertThat(sidecars(shoot)).containsExactly("DSC00001.xmp", "DSC00002.xmp")
        assertThat(XmpFields(rating = 3)).isEqualTo(
            dev.hfps.reveal.data.xmp.XmpReader.read(File(shoot, "DSC00001.xmp").readBytes()),
        )
        // The other app's tool name stays: merged, never replaced.
        assertThat(File(shoot, "DSC00002.xmp").readText()).contains("darktable")
        assertThat(XmpDevelop.read(XmpDom.parse(File(shoot, "DSC00001.xmp").readBytes())).reveal?.recipe)
            .isEqualTo(edit)
    }

    @Test
    fun `a malformed sidecar is kept and counted`() = runTest {
        raw("A")
        val broken = "<x:xmpmeta broken"
        File(shoot, "A.xmp").writeText(broken)
        val ids = indexed()
        db.photoDao().setRating(ids, 5)
        val outcome = export().run(ids, XmpDestination.NextToPhotos())
        assertThat(outcome).isEqualTo(SidecarOutcome(0, 0, 1))
        assertThat(File(shoot, "A.xmp").readText()).isEqualTo(broken)
    }

    @Test
    fun `a chosen folder gets flat sidecars, the photo's own sidecar is the base and stays`() = runTest {
        raw("DSC00001")
        raw("DSC00002")
        val own = File(shoot, "DSC00002.xmp")
        own.writeText(SyntheticShoot.sidecar(rating = 2, label = "Red", creatorTool = "darktable"))
        val before = own.readText()
        val ids = indexed(writable = false)
        val outcome = export().run(ids, XmpDestination.Folder(out.path))
        assertThat(outcome.written).isEqualTo(2)
        assertThat(sidecars(out)).containsExactly("DSC00001.xmp", "DSC00002.xmp")
        assertThat(File(out, "DSC00002.xmp").readText()).contains("darktable")
        assertThat(ratingOf(File(out, "DSC00002.xmp"))).isEqualTo(2)
        assertThat(own.readText()).isEqualTo(before)
    }

    @Test
    fun `a name taken in the chosen folder gets the provider's copy name, the file already there stays intact`() =
        runTest {
            raw("DSC00001")
            val ids = indexed(writable = false)
            db.photoDao().setRating(ids, 4)
            val theirs = File(out, "DSC00001.xmp").apply {
                writeText(SyntheticShoot.sidecar(rating = 1, label = "Purple", creatorTool = "darktable"))
            }
            val before = theirs.readText()
            val deduping = XmpExport(db, sync()) { DedupingTree(File(it)) }

            val outcome = deduping.run(ids, XmpDestination.Folder(out.path))

            assertThat(outcome.written).isEqualTo(1)
            assertThat(sidecars(out)).containsExactly("DSC00001 (1).xmp", "DSC00001.xmp")
            val ours = File(out, "DSC00001 (1).xmp").readText()
            assertThat(ours).isNotEqualTo(before)
            assertThat(theirs.readText()).isEqualTo(before)
            assertThat(ratingOf(File(out, "DSC00001 (1).xmp"))).isEqualTo(4)
            assertThat(ratingOf(theirs)).isEqualTo(1)
        }

    @Test
    fun `a photo's malformed sidecar is left alone and the chosen folder gets a fresh one`() = runTest {
        raw("A")
        val broken = "<x:xmpmeta broken"
        File(shoot, "A.xmp").writeText(broken)
        val ids = indexed(writable = false)
        db.photoDao().setRating(ids, 5)
        val outcome = export().run(ids, XmpDestination.Folder(out.path))
        assertThat(outcome).isEqualTo(SidecarOutcome(1, 0))
        assertThat(File(shoot, "A.xmp").readText()).isEqualTo(broken)
        assertThat(ratingOf(File(out, "A.xmp"))).isEqualTo(5)
    }

    @Test
    fun `a job run again overwrites the documents it wrote, no copies`() = runTest {
        raw("DSC00001")
        raw("DSC00002")
        val ids = indexed(writable = true)
        db.photoDao().setRating(ids, 3)
        val deduping = XmpExport(db, sync()) { DedupingTree(File(it)) }
        // Into a chosen folder: the first run recorded what it created (as the job's log does).
        val folder = deduping.open(XmpDestination.Folder(out.path))
        val created = HashMap<Long, String>()
        for (id in ids) deduping.exportOne(folder, id) { created[id] = it }
        db.photoDao().setRating(ids, 5)
        for (id in ids) deduping.exportOne(folder, id, ownDocument = created[id])
        assertThat(sidecars(out)).containsExactly("DSC00001.xmp", "DSC00002.xmp")
        assertThat(ratingOf(File(out, "DSC00001.xmp"))).isEqualTo(5)

        // Next to the photos: the photo records the sidecar it created before writing it, so a rerun merges into it.
        val next = deduping.open(XmpDestination.NextToPhotos())
        for (id in ids) deduping.exportOne(next, id)
        for (id in ids) deduping.exportOne(next, id)
        assertThat(sidecars(shoot)).containsExactly("DSC00001.xmp", "DSC00002.xmp")
        // Even when the first write was interrupted between creating the document and filling it.
        val interrupted = ids.first()
        File(shoot, db.photoDao().get(interrupted)!!.xmpDocumentId!!).writeBytes(ByteArray(0))
        assertThat(deduping.exportOne(next, interrupted).outcome.written).isEqualTo(1)
        assertThat(sidecars(shoot)).containsExactly("DSC00001.xmp", "DSC00002.xmp")
        assertThat(ratingOf(File(shoot, "DSC00001.xmp"))).isEqualTo(5)
    }

    private fun ratingOf(file: File) = dev.hfps.reveal.data.xmp.XmpReader.read(file.readBytes()).rating

    @Test
    fun `next to the photos is refused for photos the server or another app holds, a folder still works`() = runTest {
        raw("DSC00001")
        val ids = indexed()
        val serverId = source(writable = false, kind = SourceKind.SERVER)
        val handoffId = source(writable = true, kind = SourceKind.HANDOFF)
        listOf(serverId, handoffId).forEach { sourceId ->
            LibraryIndexer(db).index(sourceId, FileDocumentTree(shoot))
        }
        val all = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).map { it.id }.distinct()
        assertThat(all.size).isGreaterThan(ids.size)
        val scope = export().analyse(all)
        assertThat(scope.nextToPhotos).isFalse()
        assertThat(scope.canOfferNext).isFalse()
        val foreign = all - ids.toSet()
        val next = export().run(foreign, XmpDestination.NextToPhotos())
        assertThat(next.written).isEqualTo(0)
        assertThat(next.databaseOnly).isEqualTo(foreign.size)
        assertThat(sidecars(shoot)).isEmpty()
        val chosen = export().run(foreign, XmpDestination.Folder(out.path))
        assertThat(chosen.written + chosen.databaseOnly).isEqualTo(foreign.size)
        assertThat(chosen.written).isGreaterThan(0)
    }

    @Test
    fun `a folder without a write grant offers the one-off pick, and writes through it`() = runTest {
        raw("DSC00001")
        val ids = indexed(writable = false)
        val scope = export().analyse(ids)
        assertThat(scope.nextToPhotos).isFalse()
        assertThat(scope.needsGrant).hasSize(1)
        assertThat(scope.canOfferNext).isTrue()
        val none = export().run(ids, XmpDestination.NextToPhotos())
        assertThat(none.written).isEqualTo(0)
        assertThat(sidecars(shoot)).isEmpty()
        val sourceId = scope.needsGrant.single().first
        val one = export().run(ids, XmpDestination.NextToPhotos(mapOf(sourceId to shoot.path)))
        assertThat(one.written).isEqualTo(1)
        assertThat(sidecars(shoot)).containsExactly("DSC00001.xmp")
    }

    @Test
    fun `the write-edits switch of earlier versions is removed on upgrade`() {
        val prefs = context.getSharedPreferences(XmpPreferences.FILE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(XmpPreferences.KEY_WRITE_REMOVED, true).putBoolean(XmpPreferences.KEY_READ, false)
            .commit()
        val xmp = XmpPreferences(context)
        assertThat(prefs.contains(XmpPreferences.KEY_WRITE_REMOVED)).isFalse()
        // Reading is untouched.
        assertThat(xmp.readEdits.value).isFalse()
    }
}
