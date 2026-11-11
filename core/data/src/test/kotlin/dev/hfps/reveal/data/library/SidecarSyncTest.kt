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
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import java.io.File
import java.time.OffsetDateTime
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
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
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

    @Test
    fun `a malformed existing sidecar is not overwritten`() = runTest {
        File(root, "A.JPG").writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(10, 10), SyntheticExif()))
        File(root, "A.xmp").writeText("<x:xmpmeta broken")
        val sourceId = source(writable = true)
        LibraryIndexer(db).index(sourceId, FileDocumentTree(root))
        val id = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
        db.photoDao().setRating(listOf(id), 5)
        assertThat(sync().write(listOf(id))).isEqualTo(SidecarOutcome(0, 1))
        assertThat(File(root, "A.xmp").readText()).isEqualTo("<x:xmpmeta broken")
    }
}
