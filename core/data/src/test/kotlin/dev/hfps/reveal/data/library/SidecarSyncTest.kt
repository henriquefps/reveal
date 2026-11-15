package dev.hfps.reveal.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SidecarCullingEntity
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncTriggers
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.CullingField
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.xmp.SidecarCulling
import dev.hfps.reveal.data.xmp.XmpDevelop
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidecarSyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        db = RevealDatabase.configure(
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            ),
        ).allowMainThreadQueries().build()
        root = temp.newFolder("shoot")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun source(writable: Boolean, state: SourceState = SourceState.AVAILABLE) = db.sourceDao().insert(
        SourceEntity(0, "content://t/${System.nanoTime()}", "", "shoot", null, null, state, writable, 0),
    )

    private fun sync() = SidecarSync(db, {
        FileDocumentTree(root)
    }, "Reveal 0.2.0") { OffsetDateTime.parse("2026-11-11T10:00:00Z") }

    @Test
    fun `ratings go to a new sidecar named after the RAW, then merge into it`() = runTest {
        File(
            root,
            "DSC00001.ARW",
        ).writeBytes(SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        File(
            root,
            "DSC00001.JPG",
        ).writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        val sourceId = source(writable = true)
        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        val id = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id

        db.photoDao().setRating(listOf(id), 3)
        assertThat(sync().write(listOf(id))).isEqualTo(SidecarOutcome(1, 0))
        val sidecar = File(root, "DSC00001.xmp")
        assertThat(XmpReader.read(sidecar.readBytes())).isEqualTo(XmpFields(rating = 3))
        assertThat(sidecar.readText()).contains("xmp:CreatorTool=\"Reveal 0.2.0\"")

        db.photoDao().setFlag(listOf(id), PickFlag.PICK)
        sync().write(listOf(id))
        assertThat(XmpReader.read(sidecar.readBytes())).isEqualTo(XmpFields(rating = 3, flag = PickFlag.PICK))
        assertThat(root.list()!!.filter { it.endsWith(".xmp") }).containsExactly("DSC00001.xmp")

        // Our own write is not read back as a change on the next walk.
        val again = LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        assertThat(again.sidecarsRead).isEqualTo(0)
    }

    @Test
    fun `a hidden sidecar is merged into a new normal one, which wins from then on`() = runTest {
        File(
            root,
            "DSC00002.ARW",
        ).writeBytes(SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        val hidden = File(root, ".DSC00002.xmp")
        hidden.writeText(SyntheticShoot.sidecar(rating = 2, label = "Red", creatorTool = "darktable"))
        val before = hidden.readText()
        val sourceId = source(writable = true)
        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        val id = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(2)

        db.photoDao().setRating(listOf(id), 4)
        assertThat(sync().write(listOf(id))).isEqualTo(SidecarOutcome(1, 0))
        val normal = File(root, "DSC00002.xmp")
        assertThat(XmpReader.read(normal.readBytes())).isEqualTo(XmpFields(rating = 4, label = "Red"))
        assertThat(hidden.readText()).isEqualTo(before)
        assertThat(db.photoDao().get(id)!!.xmpDocumentId).isEqualTo("DSC00002.xmp")

        val again = LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        assertThat(again.sidecarsRead).isEqualTo(0)
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(4)
    }

    @Test
    fun `read-only or unplugged sources keep the change in the database`() = runTest {
        File(root, "A.JPG").writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        val readOnly = source(writable = false)
        LibraryIndexer(db).index(readOnly, FileDocumentTree(root))
        val id = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
        assertThat(sync().write(listOf(id))).isEqualTo(SidecarOutcome(0, 1))
        assertThat(File(root, "A.xmp").exists()).isFalse()
    }

    /** A provider whose writes fail after the document was created ([failure] thrown from `writeBytes`). */
    private class FailingWrites(root: File, private val failure: () -> Throwable) :
        DocumentTree by FileDocumentTree(root) {
        override fun writeBytes(documentId: String, bytes: ByteArray) = throw failure()
    }

    @Test
    fun `a write cancelled or refused after the create leaves no empty sidecar and the record as it was`() = runTest {
        File(root, "A.JPG").writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        val sourceId = source(writable = true)
        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        val id = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
        db.photoDao().setRating(listOf(id), 4)

        val refused = SidecarSync(db, { FailingWrites(root) { java.io.IOException("refused") } }, "Reveal 0.9.0")
        assertThat(refused.write(listOf(id))).isEqualTo(SidecarOutcome(0, 1))
        assertThat(File(root, "A.xmp").exists()).isFalse()
        assertThat(db.photoDao().get(id)!!.xmpDocumentId).isNull()

        val cancelled = SidecarSync(db, { FailingWrites(root) { CancellationException("cancel") } }, "Reveal 0.9.0")
        val thrown = runCatching { cancelled.write(listOf(id)) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(File(root, "A.xmp").exists()).isFalse()
        assertThat(db.photoDao().get(id)!!.xmpDocumentId).isNull()

        // Into a chosen folder: the document created for the photo is deleted too.
        val out = temp.newFolder("out")
        val write = sync().writeOneInto(id, FailingWrites(out) { java.io.IOException("full") }, "")
        assertThat(write.outcome).isEqualTo(SidecarOutcome(0, 1))
        assertThat(out.list().orEmpty().toList()).isEmpty()

        // And the next write works as if nothing happened.
        assertThat(sync().write(listOf(id))).isEqualTo(SidecarOutcome(1, 0))
        assertThat(XmpReader.read(File(root, "A.xmp").readBytes())).isEqualTo(XmpFields(rating = 4))
    }

    @Test
    fun `a malformed existing sidecar is not overwritten`() = runTest {
        File(root, "A.JPG").writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        File(root, "A.xmp").writeText("<x:xmpmeta broken")
        val sourceId = source(writable = true)
        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        val id = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
        db.photoDao().setRating(listOf(id), 5)
        assertThat(sync().write(listOf(id))).isEqualTo(SidecarOutcome(0, 0, 1))
        assertThat(File(root, "A.xmp").readText()).isEqualTo("<x:xmpmeta broken")
    }

    @Test
    fun `an original-file export gets the version's edit merged into the photo's sidecar, which stays as it was`() =
        runTest {
            File(root, "B.ARW").writeBytes(SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
            val own = File(root, "B.xmp")
            own.writeText(SyntheticShoot.sidecar(rating = 3, label = "Red", creatorTool = "darktable"))
            val before = own.readText()
            val sourceId = source(writable = true)
            LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
            val id = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
            val versionId = checkNotNull(db.photoDao().firstVersion(id))
            val edit = Recipe(light = Light(exposureEv = 0.5f))
            val bytes = checkNotNull(sync().exportSidecar(versionId, edit))
            assertThat(XmpReader.read(bytes)).isEqualTo(XmpFields(rating = 3, label = "Red"))
            val develop = XmpDevelop.read(bytes)
            assertThat(develop.reveal?.recipe).isEqualTo(edit)
            assertThat(develop.crs).containsKey("Exposure2012")
            // darktable's tool name stays: the export merges, never replaces.
            assertThat(String(bytes)).contains("darktable")
            assertThat(own.readText()).isEqualTo(before)
            // A malformed sidecar is not merged: the export gets a fresh one.
            own.writeText("<x:xmpmeta broken")
            assertThat(
                XmpDevelop.read(checkNotNull(sync().exportSidecar(versionId, edit))).reveal?.recipe,
            ).isEqualTo(edit)
        }

    /** A value pulled from the sync server: set with the triggers quiet, as the sync engine does. */
    private suspend fun pulled(block: suspend () -> Unit) {
        db.metaDao().put(MetaEntity(SyncTriggers.APPLYING, "1"))
        block()
        db.metaDao().delete(SyncTriggers.APPLYING)
    }

    private suspend fun indexedRaw(stem: String, sidecar: String?): Pair<Long, Long> {
        File(root, "$stem.ARW").writeBytes(SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        sidecar?.let { File(root, "$stem.xmp").writeText(it) }
        File(root, "$stem.xmp").takeIf { it.exists() }?.setLastModified(1_700_000_000_000L)
        val sourceId = source(writable = true)
        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        return sourceId to db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
    }

    @Test
    fun `only the culling field the user changed is written, and an edit leaves them all alone`() = runTest {
        val (_, id) = indexedRaw(
            "DSC00003",
            SyntheticShoot.sidecar(rating = 2, label = "Red", creatorTool = "darktable"),
        )
        // The label came back from the server as cleared (another app): not the user's decision on this device.
        pulled { db.cullingDao().setLabel(id, null) }

        db.photoDao().setRating(listOf(id), 4)
        sync().write(listOf(id), fields = setOf(CullingField.RATING))
        val sidecar = File(root, "DSC00003.xmp")
        assertThat(
            XmpReader.readCulling(sidecar.readBytes()),
        ).isEqualTo(SidecarCulling(rating = 4, PickFlag.NONE, "Red"))

        // An edit-only write (ADR 0026) with a pulled rating of 5 in the database: the file keeps the user's 4.
        pulled { db.cullingDao().setRating(id, 5) }
        val edits = SidecarSync(db, { FileDocumentTree(root) }, "Reveal 0.8.0", writeEdits = { true }) {
            OffsetDateTime.parse("2026-11-14T10:00:00Z")
        }
        assertThat(edits.write(listOf(id), editsOnly = true).written).isEqualTo(1)
        assertThat(
            XmpReader.readCulling(sidecar.readBytes()),
        ).isEqualTo(SidecarCulling(rating = 4, PickFlag.NONE, "Red"))
        // What the file holds is remembered for the next import.
        assertThat(db.cullingDao().sidecar(id)).isEqualTo(SidecarCullingEntity(id, 4, "NONE", "Red"))
    }

    @Test
    fun `an edit-only write creates a sidecar without culling properties`() = runTest {
        val (_, id) = indexedRaw("DSC00004", sidecar = null)
        pulled { db.cullingDao().setRating(id, 3) }
        val edits = SidecarSync(db, { FileDocumentTree(root) }, "Reveal 0.8.0", writeEdits = { true }) {
            OffsetDateTime.parse("2026-11-14T10:00:00Z")
        }
        val versionId = db.photoDao().firstVersion(id)!!
        val recipe = dev.hfps.reveal.pipeline.recipe.Recipe().let { it.copy(light = it.light.copy(exposureEv = 1f)) }
        dev.hfps.reveal.data.edit.EditRepository(db).commit(
            versionId,
            recipe,
            dev.hfps.reveal.pipeline.recipe.RecipeDiff.between(dev.hfps.reveal.pipeline.recipe.Recipe(), recipe),
        )
        assertThat(edits.write(listOf(id), editsOnly = true).written).isEqualTo(1)
        val text = File(root, "DSC00004.xmp").readText()
        assertThat(text).doesNotContain("xmp:Rating")
        assertThat(text).doesNotContain("PickLabel")
        assertThat(XmpReader.readCulling(text.toByteArray()).isEmpty).isTrue()
    }

    @Test
    fun `a changed sidecar imports only the fields that changed in it, dated by its mtime`() = runTest {
        val (sourceId, id) =
            indexedRaw("DSC00005", SyntheticShoot.sidecar(rating = 2, label = "Red", creatorTool = "darktable"))
        db.openHelper.writableDatabase.execSQL("DELETE FROM culling_pending")
        // Another device rated it 5 since (pulled); Lightroom then changes only the label in the file.
        pulled { db.cullingDao().setRating(id, 5) }
        val sidecar = File(root, "DSC00005.xmp")
        sidecar.writeText(SyntheticShoot.sidecar(rating = 2, label = "Green", creatorTool = "darktable"))
        sidecar.setLastModified(1_700_000_100_000L)

        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))

        with(db.photoDao().get(id)!!) {
            assertThat(rating).isEqualTo(5)
            assertThat(label).isEqualTo("Green")
        }
        val queued = db.cullingDao().pendingOf(id).single()
        assertThat(queued.field).isEqualTo("label")
        assertThat(queued.modifiedAt).isEqualTo(sidecar.lastModified())
        assertThat(queued.blind).isTrue()

        // Lightroom clears the label (removes xmp:Label): it is cleared here too.
        sidecar.writeText(SyntheticShoot.sidecar(rating = 2, creatorTool = "darktable"))
        sidecar.setLastModified(1_700_000_200_000L)
        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        assertThat(db.photoDao().get(id)!!.label).isNull()
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(5)
    }

    @Test
    fun `a reject in a sidecar keeps the photo's stars`() = runTest {
        val (sourceId, id) = indexedRaw("DSC00006", SyntheticShoot.sidecar(rating = 4, creatorTool = "Lightroom"))
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(4)
        val sidecar = File(root, "DSC00006.xmp")
        sidecar.writeText(SyntheticShoot.sidecar(rating = -1, creatorTool = "Lightroom"))
        sidecar.setLastModified(1_700_000_100_000L)

        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))

        with(db.photoDao().get(id)!!) {
            assertThat(flag).isEqualTo(PickFlag.REJECT)
            assertThat(rating).isEqualTo(4)
        }
    }
}
