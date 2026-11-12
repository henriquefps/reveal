package dev.hfps.reveal.data.handoff

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.model.CullingFilter
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.data.xmp.XmpWriter
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * One photo across sources (ADR 0011 "One photo across sources"): a hand-off photo and a folder photo of the same file
 * become the folder's photo, whichever came first, with the culling rule, both edits kept and a consistent sync id.
 */
@RunWith(AndroidJUnit4::class)
class HandoffMergeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var edits: EditRepository
    private lateinit var folderDir: File
    private lateinit var folderTree: FileDocumentTree
    private val handoffTree = FileUriTree()
    private var now = 1_000L
    private var folderId = 0L
    private var handoffsId = 0L

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        edits = EditRepository(db) { now }
        folderDir = temp.newFolder("card")
        folderTree = FileDocumentTree(folderDir)
    }

    @After
    fun tearDown() = db.close()

    private fun sidecars() = SidecarSync(
        db,
        { source -> if (source.kind == SourceKind.FOLDER) folderTree else handoffTree },
        "Reveal test",
        now = { OffsetDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneOffset.UTC) },
    )

    private fun handoffs() = HandoffRepository(
        context.contentResolver,
        db,
        LibraryIndexer(db) { now },
        handoffTree,
        sidecars = sidecars(),
    ) { now }

    private fun library(previews: LocalPreviews? = null) = LibraryRepository(
        context.contentResolver,
        db,
        null,
        { source -> if (source.kind == SourceKind.FOLDER) folderTree else handoffTree },
        sidecars(),
        CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        previews = previews,
    )

    /** A camera JPEG on the card, unique content. */
    private fun cardFile(name: String): File = File(folderDir, name).apply {
        writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()) + name.toByteArray())
    }

    /** The same file as the gallery hands it over: a MediaStore URI (no name in the URI). */
    private fun media(id: Int, file: File): Uri =
        Uri.parse("content://media/external/images/media/$id").also { handoffTree.aliases[it.toString()] = file }

    private suspend fun addFolder() {
        folderId = db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://test/tree/card",
                rootDocumentId = "",
                displayName = "card",
                volumeId = null,
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = true,
                addedAt = 0,
            ),
        )
        val indexer = LibraryIndexer(db) { now }
        indexer.index(folderId, folderTree)
        indexer.fillFingerprints(folderId, folderTree)
    }

    private suspend fun handOff(file: Uri, culling: HandoffCulling? = null): Long {
        val outcome = handoffs().receive(
            HandoffRequest(
                listOf(file),
                title = "Hoard picks",
                culling = culling?.let { mapOf(file to it) }.orEmpty(),
            ),
        )
        handoffsId = db.sourceDao().handoff()!!.id
        return (outcome as HandoffOutcome.OpenEditor).versionId
    }

    private suspend fun edit(versionId: Long, ev: Float) {
        val to = Recipe(light = Light(exposureEv = ev))
        edits.commit(versionId, to, RecipeDiff.between(edits.recipe(versionId), to))
    }

    private suspend fun allPhotos() = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.All)))

    private suspend fun fromApps() = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS))

    private fun sidecarOf(stem: String): XmpFields = XmpReader.read(File(folderDir, "$stem.xmp").readBytes())

    @Test
    fun `a hand-off then the folder, one photo, the folder's, with the picks, the edit and its sidecar`() = runTest {
        val file = File(temp.newFolder("staging"), "DSC00100.JPG")
        cardFile("DSC00100.JPG").copyTo(file)
        val version = handOff(media(100, file), HandoffCulling(rating = 3, flag = PickFlag.PICK))
        edit(version, 0.7f)
        now = 2_000L
        addFolder()
        assertThat(allPhotos()).hasSize(2) // not merged until the folder's fingerprints are compared

        val report = library().mergeHandoffs(folderId)

        assertThat(report.merged).isEqualTo(1)
        val photo = allPhotos().single()
        assertThat(photo.sourceId).isEqualTo(folderId)
        assertThat(photo.flag).isEqualTo(PickFlag.PICK)
        assertThat(photo.rating).isEqualTo(3)
        assertThat(edits.recipe(photo.versionId).light.exposureEv).isEqualTo(0.7f)
        assertThat(edits.history(photo.versionId).steps).hasSize(1)
        // The folder's "Picked" filter finds it, "Opened from other apps" still lists it with its batch.
        val picked = db.photoDao().rows(
            PhotoQuerySql.toQuery(PhotoQuery(filters = PhotoFilters.folder(folderId).toggle(CullingFilter.PICKED))),
        )
        assertThat(picked.map { it.id }).containsExactly(photo.id)
        assertThat(fromApps().single().id).isEqualTo(photo.id)
        assertThat(fromApps().single().handoffTitle).isEqualTo("Hoard picks")
        // Written to the folder's sidecar like any rating change.
        assertThat(sidecarOf("DSC00100")).isEqualTo(XmpFields(rating = 3, flag = PickFlag.PICK))
        assertThat(db.photoDao().get(photo.id)!!.xmpDocumentId).isEqualTo("DSC00100.xmp")
    }

    @Test
    fun `the folder then a hand-off with culling values, the folder photo takes them and its sidecar`() = runTest {
        val card = cardFile("DSC00200.JPG")
        addFolder()
        val folderPhoto = allPhotos().single()

        val version = handOff(media(200, card), HandoffCulling(rating = 4, flag = PickFlag.PICK, label = "Red"))

        assertThat(db.photoDao().photoIdOfVersion(version)).isEqualTo(folderPhoto.id)
        val photo = allPhotos().single()
        assertThat(photo.flag).isEqualTo(PickFlag.PICK)
        assertThat(photo.rating).isEqualTo(4)
        assertThat(photo.label).isEqualTo("Red")
        assertThat(sidecarOf("DSC00200")).isEqualTo(XmpFields(rating = 4, label = "Red", flag = PickFlag.PICK))
        assertThat(fromApps().map { it.id }).containsExactly(folderPhoto.id)
    }

    @Test
    fun `a sidecar written on the folder after the previous hand-off wins over values sent again`() = runTest {
        val card = cardFile("DSC00300.JPG")
        addFolder()
        handOff(media(300, card), HandoffCulling(rating = 4))
        // Someone rates it on the folder afterwards (Reveal or another app writing the sidecar).
        now = 5_000L
        File(folderDir, "DSC00300.xmp").apply {
            writeBytes(XmpWriter.update(readBytes(), XmpFields(rating = 1), "x", "d"))
            setLastModified(4_000L)
        }
        LibraryIndexer(db) { now }.index(folderId, folderTree)
        assertThat(allPhotos().single().rating).isEqualTo(1)

        handOff(media(300, card), HandoffCulling(rating = 5))

        assertThat(allPhotos().single().rating).isEqualTo(1)
    }

    @Test
    fun `conflicting culling, each field the folder lacks is filled, a newer hand-off wins, an older one not`() {
        val folder = XmpFields(rating = 2, label = null, flag = PickFlag.NONE)
        val handoff = XmpFields(rating = 5, label = "Red", flag = PickFlag.PICK)
        assertThat(HandoffMerge.resolve(folder, folderSidecarTime = 100, handoff = handoff, handoffTime = 50))
            .isEqualTo(XmpFields(rating = 2, label = "Red", flag = PickFlag.PICK))
        assertThat(HandoffMerge.resolve(folder, folderSidecarTime = 100, handoff = handoff, handoffTime = 150))
            .isEqualTo(handoff)
        assertThat(HandoffMerge.resolve(folder, folderSidecarTime = null, handoff = handoff, handoffTime = 150))
            .isEqualTo(handoff)
        assertThat(HandoffMerge.resolve(folder, folderSidecarTime = 100, handoff = XmpFields(), handoffTime = 150))
            .isEqualTo(folder)
    }

    @Test
    fun `an older hand-off does not overwrite a rating the folder's sidecar holds`() = runTest {
        val file = File(temp.newFolder("staging"), "DSC00400.JPG")
        cardFile("DSC00400.JPG").copyTo(file)
        handOff(media(400, file), HandoffCulling(rating = 5, flag = PickFlag.PICK))
        File(folderDir, "DSC00400.xmp").apply {
            writeBytes(XmpWriter.update(null, XmpFields(rating = 2), "x", "d"))
            setLastModified(9_000L)
        }
        now = 10_000L
        addFolder()

        library().mergeHandoffs(folderId)

        val photo = allPhotos().single()
        assertThat(photo.rating).isEqualTo(2) // the sidecar is newer than the hand-off
        assertThat(photo.flag).isEqualTo(PickFlag.PICK) // the folder had no flag
        assertThat(sidecarOf("DSC00400")).isEqualTo(XmpFields(rating = 2, flag = PickFlag.PICK))
    }

    @Test
    fun `both edited, the folder keeps its edit first and the hand-off's becomes a second version`() = runTest {
        val file = File(temp.newFolder("staging"), "DSC00500.JPG")
        cardFile("DSC00500.JPG").copyTo(file)
        val handoffVersion = handOff(media(500, file))
        edit(handoffVersion, 1.2f)
        edit(handoffVersion, 1.4f)
        addFolder()
        val folderVersion = allPhotos().single { it.sourceId == folderId }.versionId
        edit(folderVersion, 0.5f)

        library().mergeHandoffs(folderId)

        val rows = allPhotos()
        assertThat(rows.map { it.id }.distinct()).hasSize(1)
        assertThat(rows.map { it.versionId }).containsExactly(folderVersion, handoffVersion).inOrder()
        assertThat(edits.recipe(folderVersion).light.exposureEv).isEqualTo(0.5f)
        assertThat(edits.recipe(handoffVersion).light.exposureEv).isEqualTo(1.4f)
        assertThat(edits.history(handoffVersion).steps).hasSize(2)
    }

    @Test
    fun `the same file in two folders stays two photos, the hand-off joins one of them`() = runTest {
        val card = cardFile("DSC00600.JPG")
        val copyDir = temp.newFolder("backup")
        card.copyTo(File(copyDir, "DSC00600.JPG"))
        val file = File(temp.newFolder("staging"), "DSC00600.JPG")
        card.copyTo(file)
        handOff(media(600, file), HandoffCulling(flag = PickFlag.PICK))
        addFolder()
        val copyTree = FileDocumentTree(copyDir)
        val copyId = db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://test/tree/backup",
                rootDocumentId = "",
                displayName = "backup",
                volumeId = null,
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
            ),
        )
        LibraryIndexer(db) { now }.apply {
            index(copyId, copyTree)
            fillFingerprints(copyId, copyTree)
        }

        HandoffMerge(db) { now }.mergeInto(folderId)
        HandoffMerge(db) { now }.mergeInto(copyId)

        val rows = allPhotos()
        assertThat(rows.map { it.sourceId }).containsExactly(folderId, copyId)
        assertThat(rows.single { it.sourceId == folderId }.flag).isEqualTo(PickFlag.PICK)
        assertThat(rows.single { it.sourceId == copyId }.flag).isEqualTo(PickFlag.NONE)
    }

    @Test
    fun `merged hand-offs lose their local previews, a folder that is unplugged merges nothing`() = runTest {
        val file = File(temp.newFolder("staging"), "DSC00700.JPG")
        cardFile("DSC00700.JPG").copyTo(file)
        handOff(media(700, file))
        val print = FileFingerprint.of(file.readBytes())
        val previews = LocalPreviews(temp.newFolder("previews"))
        previews.displayFile(print).writeBytes(byteArrayOf(1))
        addFolder()
        db.sourceDao().setState(folderId, SourceState.UNPLUGGED, null)
        assertThat(library(previews).mergeHandoffs(folderId).merged).isEqualTo(0)
        assertThat(previews.display(print)).isNotNull()

        db.sourceDao().setState(folderId, SourceState.AVAILABLE, null)
        assertThat(library(previews).mergeHandoffs(folderId).merged).isEqualTo(1)
        assertThat(previews.display(print)).isNull()
    }

    @Test
    fun `sync identity, a hand-off the server knows gives its id to a folder photo the server does not know`() =
        runTest {
            val file = File(temp.newFolder("staging"), "DSC00800.JPG")
            cardFile("DSC00800.JPG").copyTo(file)
            val version = handOff(media(800, file))
            edit(version, 0.3f)
            val handoff = db.photoDao().get(db.photoDao().photoIdOfVersion(version)!!)!!
            pushed(SyncRecordEntity.KIND_PHOTO, handoff.uuid, rev = 7)
            val versionUuid = db.photoDao().versions(handoff.id).single().uuid
            pushed(SyncRecordEntity.KIND_VERSION, versionUuid, rev = 8)
            addFolder()

            library().mergeHandoffs(folderId)

            val photo = db.photoDao().get(allPhotos().single().id)!!
            assertThat(photo.uuid).isEqualTo(handoff.uuid)
            val record = db.syncDao().record(SyncRecordEntity.KIND_PHOTO, photo.uuid)!!
            assertThat(record.rev).isEqualTo(7)
            assertThat(record.dirty).isTrue()
            assertThat(record.deleted).isFalse()
            // The version moved with its id: pushed again so the server learns its new photo.
            val moved = db.syncDao().record(SyncRecordEntity.KIND_VERSION, versionUuid)!!
            assertThat(moved.dirty).isTrue()
            assertThat(db.photoDao().versions(photo.id).single().uuid).isEqualTo(versionUuid)
        }

    @Test
    fun `sync identity, when the server knows both, the hand-off's id becomes an alias and a tombstone`() = runTest {
        val file = File(temp.newFolder("staging"), "DSC00900.JPG")
        cardFile("DSC00900.JPG").copyTo(file)
        handOff(media(900, file))
        addFolder()
        val rows = allPhotos()
        val handoff = db.photoDao().get(rows.single { it.sourceId == handoffsId }.id)!!
        val folder = db.photoDao().get(rows.single { it.sourceId == folderId }.id)!!
        pushed(SyncRecordEntity.KIND_PHOTO, handoff.uuid, rev = 3)
        pushed(SyncRecordEntity.KIND_PHOTO, folder.uuid, rev = 4)

        library().mergeHandoffs(folderId)

        val tombstone = db.syncDao().record(SyncRecordEntity.KIND_PHOTO, handoff.uuid)!!
        assertThat(tombstone.deleted).isTrue()
        assertThat(tombstone.dirty).isTrue()
        assertThat(tombstone.rev).isEqualTo(3)
        assertThat(db.syncDao().alias(handoff.uuid)).isEqualTo(folder.id)
        assertThat(db.photoDao().get(folder.id)!!.uuid).isEqualTo(folder.uuid)
    }

    private suspend fun pushed(kind: String, id: String, rev: Long) {
        db.syncDao().putRecord(SyncRecordEntity(kind, id, rev, dirty = false, modifiedAt = now))
    }
}
