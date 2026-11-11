package dev.hfps.reveal.data.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.format.ExifTime
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.data.model.PhotoFilter
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryIndexerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var root: File
    private var sourceId = 0L

    @Before
    fun setUp() = runTest {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        root = temp.newFolder("shoot")
        sourceId = db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://test/tree/shoot",
                rootDocumentId = "",
                displayName = "shoot",
                volumeId = null,
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = true,
                addedAt = 0,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun jpeg(name: String, time: String, sub: String = "000", dir: File = root) = File(
        dir,
        name,
    ).writeBytes(
        SyntheticShoot.jpeg(
            SyntheticShoot.markerOnlyJpeg(600, 400),
            SyntheticExif(dateTime = time, subSecond = sub),
        ),
    )

    private fun raw(name: String, time: String, sub: String = "000", dir: File = root) = File(dir, name).writeBytes(
        SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(1616, 1080), SyntheticExif(dateTime = time, subSecond = sub)),
    )

    private suspend fun index(tree: FileDocumentTree = FileDocumentTree(root)) =
        LibraryIndexer(db).index(sourceId, tree)

    private suspend fun rows(
        query: PhotoQuery = PhotoQuery(scope = LibraryScope.Source(sourceId), sort = PhotoSort.CAPTURE_OLDEST),
    ) = db.photoDao().rows(PhotoQuerySql.toQuery(query))

    @Test
    fun `pairs collapse, sidecars and EXIF are read, sub-seconds order a burst`() = runTest {
        raw("DSC00001.ARW", "2026:08:07 10:15:30", "500")
        jpeg("DSC00001.JPG", "2026:08:07 10:15:30", "500")
        raw("DSC00002.ARW", "2026:08:07 10:15:30", "100")
        jpeg("IMG_0003.jpg", "2026:08:07 09:00:00")
        File(root, "DSC00001.xmp").writeText(SyntheticShoot.sidecar(rating = 4))
        File(root, "notes.txt").writeText("ignored")
        File(root, ".hidden").mkdir()
        jpeg("x.jpg", "2026:01:01 00:00:00", dir = File(root, ".hidden"))
        val sub = File(root, "sub").apply { mkdir() }
        jpeg("DSC00009.JPG", "2026:08:09 08:00:00", dir = sub)
        File(sub, "dsc00009.jpg.xmp").writeText(SyntheticShoot.sidecar(rating = -1, creatorTool = "darktable"))

        val report = index()

        assertThat(report.files).isEqualTo(5)
        assertThat(report.photosAdded).isEqualTo(4)
        assertThat(report.sidecarsRead).isEqualTo(2)
        assertThat(report.headersRead).isEqualTo(4)
        val photos = rows()
        assertThat(
            photos.map {
                it.name
            },
        ).containsExactly("IMG_0003.jpg", "DSC00002.ARW", "DSC00001.ARW", "DSC00009.JPG").inOrder()
        val pair = photos.first { it.name == "DSC00001.ARW" }
        assertThat(pair.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(pair.rating).isEqualTo(4)
        assertThat(pair.flag).isEqualTo(PickFlag.PICK)
        assertThat(pair.captureTime).isEqualTo(ExifTime.localMillis("2026:08:07 10:15:30")!! + 500)
        assertThat(photos.first { it.name == "DSC00002.ARW" }.format).isEqualTo(PhotoFormat.RAW)
        assertThat(photos.first { it.name == "DSC00009.JPG" }.flag).isEqualTo(PickFlag.REJECT)
        val detail = db.photoDao().get(pair.id)!!
        assertThat(detail.metadataState).isEqualTo(MetadataState.READ)
        assertThat(detail.width).isEqualTo(6000)
        assertThat(db.photoDao().files(pair.id).map { it.relativePath }).containsExactly("DSC00001.ARW", "DSC00001.JPG")
        assertThat(db.photoDao().files(photos.last().id).single().relativePath).isEqualTo("sub/DSC00009.JPG")
    }

    @Test
    fun `filters and search`() = runTest {
        raw("DSC00001.ARW", "2026:08:07 10:00:00")
        jpeg("DSC00001.JPG", "2026:08:07 10:00:00")
        jpeg("IMG_100%.jpg", "2026:08:07 11:00:00")
        File(root, "IMG_100%.xmp").writeText(SyntheticShoot.sidecar(rating = 2, creatorTool = "Lightroom"))
        index()
        val scope = LibraryScope.Source(sourceId)
        assertThat(rows(PhotoQuery(scope, PhotoFilter.RAW)).map { it.name }).containsExactly("DSC00001.ARW")
        assertThat(rows(PhotoQuery(scope, PhotoFilter.JPEG)).map { it.name }).containsExactly("IMG_100%.jpg")
        assertThat(
            rows(PhotoQuery(scope, PhotoFilter.RATED, minRating = 2)).map {
                it.name
            },
        ).containsExactly("IMG_100%.jpg")
        assertThat(rows(PhotoQuery(scope, PhotoFilter.RATED, minRating = 3))).isEmpty()
        assertThat(rows(PhotoQuery(scope, PhotoFilter.PICKED))).isEmpty()
        assertThat(rows(PhotoQuery(scope, search = "100%")).map { it.name }).containsExactly("IMG_100%.jpg")
        assertThat(rows(PhotoQuery(scope, search = "1_0"))).isEmpty()
        assertThat(rows(PhotoQuery(scope, search = "dsc")).map { it.name }).containsExactly("DSC00001.ARW")
        assertThat(
            rows(PhotoQuery(scope, sort = PhotoSort.NAME)).map {
                it.name
            },
        ).containsExactly("DSC00001.ARW", "IMG_100%.jpg").inOrder()
        assertThat(rows(PhotoQuery(scope, sort = PhotoSort.CAPTURE_NEWEST)).first().name).isEqualTo("IMG_100%.jpg")
    }

    @Test
    fun `a second run is incremental`() = runTest {
        raw("DSC00001.ARW", "2026:08:07 10:00:00")
        jpeg("DSC00001.JPG", "2026:08:07 10:00:00")
        jpeg("DSC00002.JPG", "2026:08:07 10:00:01")
        jpeg("DSC00003.JPG", "2026:08:07 10:00:02")
        index()
        val firstIds = rows().associate { it.name to it.id }

        val unchanged = index()
        assertThat(unchanged.photosAdded).isEqualTo(0)
        assertThat(unchanged.photosChanged).isEqualTo(0)
        assertThat(unchanged.headersRead).isEqualTo(0)
        assertThat(unchanged.sidecarsRead).isEqualTo(0)

        // The JPEG of the pair goes away, DSC00002 changes, DSC00003 is deleted, DSC00004 arrives, a sidecar appears.
        File(root, "DSC00001.JPG").delete()
        jpeg("DSC00002.JPG", "2026:08:07 12:00:00")
        File(root, "DSC00002.JPG").setLastModified(1_000_000)
        File(root, "DSC00003.JPG").delete()
        jpeg("DSC00004.JPG", "2026:08:07 10:00:04")
        File(root, "DSC00001.xmp").writeText(SyntheticShoot.sidecar(rating = 5, creatorTool = "Lightroom"))
        val second = index()

        assertThat(second.photosAdded).isEqualTo(1)
        assertThat(second.photosChanged).isEqualTo(2)
        assertThat(second.photosRemoved).isEqualTo(1)
        assertThat(second.sidecarsRead).isEqualTo(1)
        assertThat(second.headersRead).isEqualTo(3)
        val after = rows().associateBy { it.name }
        assertThat(after.keys).containsExactly("DSC00001.ARW", "DSC00002.JPG", "DSC00004.JPG")
        assertThat(after.getValue("DSC00001.ARW").id).isEqualTo(firstIds["DSC00001.ARW"])
        assertThat(after.getValue("DSC00001.ARW").format).isEqualTo(PhotoFormat.RAW)
        assertThat(after.getValue("DSC00001.ARW").rating).isEqualTo(5)
        assertThat(after.getValue("DSC00002.JPG").id).isEqualTo(firstIds["DSC00002.JPG"])
        assertThat(after.getValue("DSC00002.JPG").captureTime).isEqualTo(ExifTime.localMillis("2026:08:07 12:00:00"))

        // The sidecar is removed: its ratings stay (the database is the working copy).
        File(root, "DSC00001.xmp").delete()
        index()
        assertThat(rows().first { it.name == "DSC00001.ARW" }.rating).isEqualTo(5)
        assertThat(db.photoDao().get(firstIds.getValue("DSC00001.ARW"))!!.xmpDocumentId).isNull()
    }

    @Test
    fun `fingerprints fill after a walk and are cleared and refilled when a file changes`() = runTest {
        jpeg("A.JPG", "2026:08:07 10:00:00")
        raw("DSC00001.ARW", "2026:08:07 10:00:01")
        val indexer = LibraryIndexer(db)
        val tree = FileDocumentTree(root)
        indexer.index(sourceId, tree)
        fun prints() = runBlocking {
            rows().flatMap { db.photoDao().files(it.id) }.associate { it.name to it.fingerprint }
        }
        assertThat(prints().values.toSet()).containsExactly(null)

        assertThat(indexer.fillFingerprints(sourceId, tree)).isEqualTo(2)
        val first = prints()
        assertThat(first.getValue("A.JPG")).isEqualTo(FileFingerprint.of(File(root, "A.JPG").readBytes()))
        assertThat(first.getValue("DSC00001.ARW")).startsWith("v1:")
        // Nothing left: a second fill reads nothing.
        assertThat(indexer.fillFingerprints(sourceId, tree)).isEqualTo(0)

        jpeg("A.JPG", "2026:08:07 11:00:00")
        File(root, "A.JPG").setLastModified(2_000_000)
        indexer.index(sourceId, tree)
        assertThat(prints()["A.JPG"]).isNull()
        assertThat(prints()["DSC00001.ARW"]).isEqualTo(first["DSC00001.ARW"])
        assertThat(indexer.fillFingerprints(sourceId, tree)).isEqualTo(1)
        assertThat(prints().getValue("A.JPG")).isEqualTo(FileFingerprint.of(File(root, "A.JPG").readBytes()))
        assertThat(prints().getValue("A.JPG")).isNotEqualTo(first["A.JPG"])
    }

    @Test
    fun `removed from library stays removed after a rescan, edits survive file changes`() = runTest {
        jpeg("A.JPG", "2026:08:07 10:00:00")
        jpeg("B.JPG", "2026:08:07 10:00:01")
        index()
        val a = rows().first { it.name == "A.JPG" }
        db.photoDao().setHidden(listOf(a.id), true)
        index()
        assertThat(rows().map { it.name }).containsExactly("B.JPG")
    }

    @Test
    fun `embedded ratings only fill unrated photos`() = runTest {
        raw("DSC00001.ARW", "2026:08:07 10:00:00")
        index()
        assertThat(rows().single().rating).isEqualTo(0)
    }

    @Test
    fun `an unreadable root fails without deleting anything`() = runTest {
        jpeg("A.JPG", "2026:08:07 10:00:00")
        index()
        val missing = FileDocumentTree(File(root, "missing"))
        val error = runCatching { LibraryIndexer(db).index(sourceId, missing) }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(rows()).hasSize(1)
    }

    @Test
    fun `indexing is cancellable between folders`() = runTest {
        repeat(30) { i ->
            File(root, "d$i").mkdir().also { jpeg("P$i.JPG", "2026:08:07 10:00:00", dir = File(root, "d$i")) }
        }
        val tree = FileDocumentTree(root)
        val job = async {
            LibraryIndexer(db).index(sourceId, tree) { p ->
                if (p.phase == IndexPhase.LISTING &&
                    p.done >= 3
                ) {
                    throw CancellationException("stop")
                }
            }
        }
        yield()
        assertThat(runCatching { job.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(tree.listings).isLessThan(31)
        assertThat(rows()).isEmpty()
    }
}
