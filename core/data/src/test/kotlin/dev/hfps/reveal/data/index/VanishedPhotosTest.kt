package dev.hfps.reveal.data.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SYNC_TRACKING_KEY
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A folder photo whose files vanished (ADR 0018 "R1", freed-adopt): Hoard may have freed it after backing it up, so
 * the walk does not delete it while the server can still say so. Paired, every such photo waits (unlisted); unpaired,
 * only one with an edit does; a week later today's rule applies; a photo listed again is back as it was.
 */
@RunWith(AndroidJUnit4::class)
class VanishedPhotosTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var root: File
    private var sourceId = 0L
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() = runTest {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            )
                .allowMainThreadQueries().build()
        root = temp.newFolder("Camera")
        sourceId = db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
                rootDocumentId = "primary:DCIM/Camera",
                displayName = "Camera",
                volumeId = "primary",
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun jpeg(name: String) = File(root, name).writeBytes(
        SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif(dateTime = "2026:08:07 10:00:00")),
    )

    private suspend fun index() = LibraryIndexer(db) { now }.index(sourceId, FileDocumentTree(root))

    private suspend fun listed() =
        db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.ofSource(sourceId))).map { it.name }

    private suspend fun id(name: String) = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.ofSource(sourceId)))
        .first { it.name == name }.id

    private suspend fun edit(photoId: Long) {
        val edits = EditRepository(db) { now }
        val version = checkNotNull(db.photoDao().firstVersion(photoId))
        val before = edits.recipe(version)
        val after = before.copy(light = before.light.copy(exposureEv = 0.5f))
        edits.commit(version, after, RecipeDiff.between(before, after))
    }

    private suspend fun pair() = db.metaDao().put(MetaEntity(SYNC_TRACKING_KEY, "server"))

    @Test
    fun `unpaired, an unedited photo goes at once and an edited one waits a week, unlisted`() = runTest {
        jpeg("PXL_1.jpg")
        jpeg("PXL_2.jpg")
        index()
        val edited = id("PXL_2.jpg")
        edit(edited)

        File(root, "PXL_1.jpg").delete()
        File(root, "PXL_2.jpg").delete()
        val report = index()

        assertThat(report.photosRemoved).isEqualTo(1)
        assertThat(listed()).isEmpty()
        // The edited photo is kept, its file marked as vanished, out of every list and count.
        assertThat(db.photoDao().get(edited)).isNotNull()
        assertThat(db.photoDao().files(edited).single().vanishedAt).isEqualTo(now)
        assertThat(db.sourceDao().observeWithCounts().first().single().photoCount).isEqualTo(0)
        assertThat(db.photoDao().observeFilterCounts().first().total).isEqualTo(0)

        // A walk six days later keeps it (the first time counts), one after the week applies today's rule.
        now += 6 * DAY
        index()
        assertThat(db.photoDao().get(edited)).isNotNull()
        assertThat(db.photoDao().files(edited).single().vanishedAt).isEqualTo(now - 6 * DAY)
        now += 2 * DAY
        assertThat(index().photosRemoved).isEqualTo(1)
        assertThat(db.photoDao().get(edited)).isNull()
    }

    @Test
    fun `paired, every vanished photo waits for the server and comes back when listed again`() = runTest {
        pair()
        jpeg("PXL_3.jpg")
        jpeg("PXL_4.jpg")
        index()
        val plain = id("PXL_3.jpg")
        val file = File(root, "PXL_3.jpg")
        val saved = file.readBytes()
        val modified = file.lastModified()
        file.delete()
        assertThat(index().photosRemoved).isEqualTo(0)
        assertThat(listed()).containsExactly("PXL_4.jpg")
        assertThat(db.photoDao().files(plain).single().vanishedAt).isNotNull()

        // A read failed while it was gone (the backup, a thumbnail): the mark goes when the file is back.
        db.photoDao().setUnreadable(db.photoDao().files(plain).single().documentId, now)
        // The file is back (a walk that had missed it, a restore): same photo, listed again.
        file.writeBytes(saved)
        file.setLastModified(modified)
        index()
        assertThat(listed()).containsExactly("PXL_3.jpg", "PXL_4.jpg")
        assertThat(id("PXL_3.jpg")).isEqualTo(plain)
        assertThat(db.photoDao().files(plain).single().vanishedAt).isNull()
        assertThat(db.photoDao().files(plain).single().unreadableAt).isNull()
    }

    @Test
    fun `a pair that loses only its JPEG keeps the RAW as before, nothing waits`() = runTest {
        pair()
        File(root, "DSC1.ARW").writeBytes(
            SyntheticShoot.raw(
                SyntheticShoot.markerOnlyJpeg(1616, 1080),
                SyntheticExif(dateTime = "2026:08:07 10:00:00"),
            ),
        )
        jpeg("DSC1.JPG")
        index()
        val photo = id("DSC1.ARW")
        File(root, "DSC1.JPG").delete()
        index()
        assertThat(db.photoDao().files(photo).map { it.name }).containsExactly("DSC1.ARW")
        assertThat(db.photoDao().files(photo).single().vanishedAt).isNull()
        assertThat(listed()).containsExactly("DSC1.ARW")
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
