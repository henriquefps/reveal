package dev.hfps.reveal.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
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

    @Test
    fun `rating, flagging and editing write no sidecar on their own`() = runTest {
        raw("DSC00001")
        val ids = indexed()
        db.photoDao().setRating(ids, 4)
        db.photoDao().setFlag(ids, PickFlag.PICK)
        val v = db.photoDao().firstVersion(ids.single())!!
        val edit = Recipe(light = Light(exposureEv = 1f))
        EditRepository(db) { 1_778_000_000_000L }.commit(v, edit, RecipeDiff.between(Recipe(), edit))
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
        // The same names again: nothing is overwritten (the provider de-duplicates; the file tree refuses, so counted).
        val again = export().run(ids, XmpDestination.Folder(out.path))
        assertThat(again.written + again.databaseOnly).isEqualTo(2)
        assertThat(File(out, "DSC00001.xmp").length()).isGreaterThan(0)
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
