package dev.hfps.reveal.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.data.model.CameraFilter
import dev.hfps.reveal.data.model.DateFilter
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.LensFilter
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.model.TypeFilter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The W3/D filters (ADR 0006 "W3/D"): Date presets relative to today and custom ranges on the grid's date, Camera
 * (make + model) and Lens, OR within each group and AND across, their counts and lists, and indexed query plans.
 */
@RunWith(AndroidJUnit4::class)
class DateCameraFiltersTest {
    private lateinit var db: RevealDatabase
    private var folderId = 0L
    private val today = LocalDate.of(2026, 11, 15)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val sony = CameraFilter("SONY", "ZV-E10")
    private val nikon = CameraFilter("NIKON CORPORATION", "NIKON D5600")

    @Before
    fun setUp() = runTest {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        folderId = db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://test/tree/f", rootDocumentId = "", displayName = "f", volumeId = null,
                volumeName = null, state = SourceState.AVAILABLE, writable = true, addedAt = 0,
                kind = SourceKind.FOLDER,
            ),
        )
        photo("TODAY.ARW", at(2026, 11, 15, 9), sony, "E PZ 16-50mm F3.5-5.6 OSS", raw = true)
        photo("MONTH.JPG", at(2026, 11, 1, 0), sony, "E PZ 16-50mm F3.5-5.6 OSS")
        photo("OCT.JPG", at(2026, 10, 20, 23), nikon, "18.0-55.0 mm f/3.5-5.6")
        photo("SEPT.JPG", at(2026, 9, 1, 12), nikon, "")
        photo("LASTYEAR.ARW", at(2025, 11, 15, 18), sony, "FE 50mm F1.8", raw = true)
        photo("OLD.JPG", at(2019, 11, 15, 7), null, null)
        photo("JAN.JPG", at(2026, 1, 1, 0), sony, "FE 50mm F1.8")
        photo("NODATE.JPG", 0, null, null)
    }

    @After
    fun tearDown() = db.close()

    private fun at(y: Int, m: Int, d: Int, h: Int) =
        LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private suspend fun photo(name: String, time: Long, camera: CameraFilter?, lens: String?, raw: Boolean = false) {
        val dao = db.photoDao()
        val id = dao.insertPhoto(
            PhotoEntity(
                sourceId = folderId, folder = "", folderDocumentId = "", stemKey = name.lowercase(), name = name,
                format = if (raw) PhotoFormat.RAW else PhotoFormat.JPEG, hasRaw = raw, sortTime = time,
                captureTime = time.takeIf { it > 0 }, addedAt = 0, metadataState = MetadataState.READ,
                cameraMake = camera?.make, cameraModel = camera?.model, lens = lens,
                metadataVersion = CURRENT_METADATA_VERSION,
            ),
        )
        dao.insertVersion(VersionEntity(photoId = id, createdAt = 0))
        dao.insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = id,
                    sourceId = folderId,
                    relativePath = name,
                    documentId = name,
                    name = name,
                    size = 1,
                    modified = 0,
                    role = if (raw) FileRole.RAW else FileRole.RENDERED,
                ),
            ),
        )
    }

    private suspend fun names(filters: PhotoFilters): List<String> {
        val (sql, args) = PhotoQuerySql.build(PhotoQuery(filters = filters), today)
        return db.photoDao().rows(androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())).map { it.name }
    }

    private fun dates(vararg filters: DateFilter) = PhotoFilters(dates = filters.toSet())

    @Test
    fun `the date presets are relative to today on the camera's clock`() = runTest {
        assertThat(names(dates(DateFilter.ThisMonth))).containsExactly("TODAY.ARW", "MONTH.JPG").inOrder()
        assertThat(names(dates(DateFilter.Last30Days))).containsExactly("TODAY.ARW", "MONTH.JPG", "OCT.JPG")
            .inOrder()
        assertThat(names(dates(DateFilter.ThisYear)))
            .containsExactly("TODAY.ARW", "MONTH.JPG", "OCT.JPG", "SEPT.JPG", "JAN.JPG").inOrder()
        // Earlier years only, never today itself; a photo without a date never matches.
        assertThat(names(dates(DateFilter.OnThisDay))).containsExactly("LASTYEAR.ARW", "OLD.JPG").inOrder()
    }

    @Test
    fun `a custom range includes both days and choices of the group are ORed`() = runTest {
        val range = DateFilter.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 20))
        assertThat(names(dates(range))).containsExactly("OCT.JPG", "SEPT.JPG").inOrder()
        val oneDay = DateFilter.Range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1))
        assertThat(names(dates(oneDay))).containsExactly("JAN.JPG")
        assertThat(names(dates(oneDay, DateFilter.ThisMonth))).containsExactly("TODAY.ARW", "MONTH.JPG", "JAN.JPG")
            .inOrder()
    }

    @Test
    fun `camera and lens filter by the header's values and AND with the other groups`() = runTest {
        assertThat(names(PhotoFilters(cameras = setOf(nikon)))).containsExactly("OCT.JPG", "SEPT.JPG").inOrder()
        assertThat(names(PhotoFilters(cameras = setOf(nikon, sony)))).hasSize(6)
        assertThat(names(PhotoFilters(lenses = setOf(LensFilter("FE 50mm F1.8")))))
            .containsExactly("JAN.JPG", "LASTYEAR.ARW").inOrder()
        val sonyRawThisYear = PhotoFilters(
            cameras = setOf(sony),
            types = setOf(TypeFilter.RAW),
            dates = setOf(DateFilter.ThisYear),
        )
        assertThat(names(sonyRawThisYear)).containsExactly("TODAY.ARW")
        // A make or model that looks like SQL is only ever a bound value.
        assertThat(names(PhotoFilters(cameras = setOf(CameraFilter("x' OR 1=1 --", "y"))))).isEmpty()
    }

    @Test
    fun `the camera and lens lists count the listed photos and skip unknown values`() = runTest {
        val cameras = db.photoDao().observeCameras().first()
        assertThat(cameras.map { CameraFilter(it.make.orEmpty(), it.model) to it.count })
            .containsExactly(sony to 4, nikon to 2).inOrder()
        val lenses = db.photoDao().observeLenses().first()
        assertThat(lenses.map { it.lens to it.count }).containsExactly(
            "E PZ 16-50mm F3.5-5.6 OSS" to 2,
            "FE 50mm F1.8" to 2,
            "18.0-55.0 mm f/3.5-5.6" to 1,
        ).inOrder()
    }

    @Test
    fun `the date preset counts match the filters`() = runTest {
        val counts = db.photoDao().observeDateCounts(PhotoQuerySql.dateCounts(today)).first()
        assertThat(counts).isEqualTo(DateCounts(thisMonth = 2, last30Days = 3, thisYear = 5, onThisDay = 2))
    }

    @Test
    fun `camera labels name the maker once`() {
        assertThat(sony.label).isEqualTo("SONY ZV-E10")
        assertThat(nikon.label).isEqualTo("NIKON D5600")
        assertThat(CameraFilter("Canon", "Canon EOS R5").label).isEqualTo("Canon EOS R5")
        assertThat(CameraFilter("OLYMPUS IMAGING CORP.", "E-M1").label).isEqualTo("OLYMPUS E-M1")
        assertThat(CameraFilter("", "Pixel 8").label).isEqualTo("Pixel 8")
    }

    /** EXPLAIN QUERY PLAN: ranges use the `sort_time` index, cameras and lenses their own; nothing scans unindexed. */
    @Test
    fun `the new filters use their indexes`() {
        fun plan(filters: PhotoFilters): List<String> {
            val (sql, args) = PhotoQuerySql.build(PhotoQuery(filters = filters), today)
            val out = ArrayList<String>()
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args.toTypedArray()).use { c ->
                while (c.moveToNext()) out += c.getString(c.columnCount - 1).trim()
            }
            return out
        }
        val cases = mapOf(
            dates(DateFilter.ThisMonth) to "index_photos_sort_time",
            dates(DateFilter.Range(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))) to "index_photos_sort_time",
            PhotoFilters(cameras = setOf(sony)) to "index_photos_camera_make_camera_model",
            PhotoFilters(cameras = setOf(sony, nikon)) to "index_photos_camera_make_camera_model",
            PhotoFilters(lenses = setOf(LensFilter("FE 50mm F1.8"))) to "index_photos_lens",
        )
        for ((filters, index) in cases) {
            val lines = plan(filters)
            assertWithMessage(lines.joinToString("\n")).that(lines.any { it.startsWith("SEARCH p ") && index in it })
                .isTrue()
            for (line in lines.filter { it.startsWith("SCAN ") }) {
                assertWithMessage(line).that(line == "SCAN sources" || " USING " in line).isTrue()
            }
        }
        // "On this day" has no index to use: it walks the photos in `sort_time` order (measured at 25,000 rows).
        val onThisDay = plan(dates(DateFilter.OnThisDay))
        for (line in onThisDay.filter { it.startsWith("SCAN ") }) {
            assertWithMessage(line).that(line == "SCAN sources" || " USING " in line).isTrue()
        }
    }
}
