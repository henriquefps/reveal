package dev.hfps.reveal.data.model

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class PhotoListCodecTest {
    private val everything = PhotoFilters(
        sources = setOf(
            SourceFilter.Folder(42),
            SourceFilter.Folder(7),
            SourceFilter.FromApps,
            SourceFilter.ServerOnly,
        ),
        culling = CullingFilter.entries.toSet(),
        edits = EditFilter.entries.toSet(),
        types = TypeFilter.entries.toSet(),
        sync = SyncFilter.entries.toSet(),
        dates = setOf(
            DateFilter.ThisMonth,
            DateFilter.Last30Days,
            DateFilter.ThisYear,
            DateFilter.OnThisDay,
            DateFilter.Range(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 1)),
        ),
        cameras = setOf(CameraFilter("SONY", "ZV-E10"), CameraFilter("", "Pixel, 8 ~ Pro"), CameraFilter("Ü", "%")),
        lenses = setOf(LensFilter("E 18-135mm F3.5-5.6 OSS"), LensFilter("a,b~c\u0001d")),
    )

    @Test
    fun everyViewAndFilterRoundTrips() {
        val filters = listOf(PhotoFilters.NONE, PhotoFilters.folder(3), PhotoFilters.FROM_APPS, everything)
        for (scope in listOf(LibraryScope.All, LibraryScope.Recent)) {
            for (f in filters) {
                val query = PhotoQuery(scope, f, PhotoSort.NAME, "dsc 01")
                assertThat(PhotoListCodec.decode(PhotoListCodec.encode(query))).isEqualTo(query)
            }
        }
        assertThat(PhotoListCodec.decodeFilters(PhotoListCodec.encodeFilters(everything))).isEqualTo(everything)
    }

    @Test
    fun searchMayContainAnything() {
        val query = PhotoQuery(search = "a:b|c\nd,f:1")
        assertThat(PhotoListCodec.decode(PhotoListCodec.encode(query))).isEqualTo(query)
    }

    @Test
    fun garbageIsNull() {
        assertThat(PhotoListCodec.decode("")).isNull()
        assertThat(PhotoListCodec.decode("nope")).isNull()
        assertThat(PhotoListCodec.decode("x\u0001ALL\u00011\u0001NAME\u0001")).isNull()
        assertThat(PhotoListCodec.decode("2\u0001all\u0001f:x\u0001NAME\u0001")).isNull()
    }

    @Test
    fun aNewerBuildsChoiceIsSkipped() {
        assertThat(PhotoListCodec.decodeFilters("c:PICKED,c:SOMETHING_NEW,z:1"))
            .isEqualTo(PhotoFilters(culling = setOf(CullingFilter.PICKED)))
    }

    @Test
    fun theOldScopesBecomeFilters() {
        val sep = "\u0001"
        assertThat(PhotoListCodec.decode("apps${sep}ALL${sep}1${sep}CAPTURE_NEWEST$sep"))
            .isEqualTo(PhotoQuery.FROM_APPS)
        assertThat(PhotoListCodec.decode("server${sep}EDITED${sep}1${sep}NAME${sep}x"))
            .isEqualTo(
                PhotoQuery(
                    filters = PhotoFilters.SERVER_ONLY.toggle(EditFilter.EDITED),
                    sort = PhotoSort.NAME,
                    search = "x",
                ),
            )
        assertThat(PhotoListCodec.decode("src:9${sep}RATED${sep}3${sep}CAPTURE_OLDEST$sep"))
            .isEqualTo(
                PhotoQuery(
                    filters = PhotoFilters.folder(9).toggle(CullingFilter.STARRED),
                    sort = PhotoSort.CAPTURE_OLDEST,
                ),
            )
        assertThat(PhotoListCodec.decode("recent${sep}ALL${sep}1${sep}NAME$sep")?.scope).isEqualTo(LibraryScope.Recent)
        assertThat(PhotoListCodec.decode("all${sep}UNAVAILABLE${sep}1${sep}NAME$sep")?.filters)
            .isEqualTo(PhotoFilters.NONE)
    }

    @Test
    fun theOldLastSelectionBecomesFilters() {
        assertThat(PhotoListCodec.legacySelection("f:12")).isEqualTo(PhotoFilters.folder(12))
        assertThat(PhotoListCodec.legacySelection("s:FROM_APPS")).isEqualTo(PhotoFilters.FROM_APPS)
        assertThat(PhotoListCodec.legacySelection("s:SERVER")).isEqualTo(PhotoFilters.SERVER_ONLY)
        assertThat(PhotoListCodec.legacySelection("s:ALL")).isNull()
        assertThat(PhotoListCodec.legacySelection("s:RECENT")).isNull()
        assertThat(PhotoListCodec.legacySelection("")).isNull()
    }

    @Test
    fun togglingAndCounting() {
        val f = PhotoFilters.NONE.toggle(CullingFilter.PICKED).toggle(CullingFilter.REJECTED).toggle(TypeFilter.RAW)
        assertThat(f.count).isEqualTo(3)
        assertThat(f.toggle(CullingFilter.PICKED).culling).containsExactly(CullingFilter.REJECTED)
        assertThat(PhotoFilters.folder(1).singleFolder).isEqualTo(1L)
        assertThat(PhotoFilters.folder(1).toggle(SourceFilter.FromApps).singleFolder).isNull()
        assertThat(PhotoFilters.folder(1).toggle(SourceFilter.Folder(2)).withFolders(listOf(2L)))
            .isEqualTo(PhotoFilters.folder(2))
        assertThat(PhotoFilters.NONE.isEmpty).isTrue()
    }

    @Test
    fun w3dTokensAreStableAndUnknownOnesAreSkipped() {
        val filters = PhotoFilters(
            dates = setOf(DateFilter.Range(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31))),
            cameras = setOf(CameraFilter("NIKON CORPORATION", "NIKON D5600")),
            lenses = setOf(LensFilter("FE 50mm F1.8")),
        )
        assertThat(PhotoListCodec.encodeFilters(filters))
            .isEqualTo("d:2024-01-01_2024-03-31,cam:NIKON+CORPORATION~NIKON+D5600,lens:FE+50mm+F1.8")
        assertThat(PhotoListCodec.decodeFilters("d:month,d:nonsense,d:2024-03-01_2024-01-01,t:RAW"))
            .isEqualTo(PhotoFilters(dates = setOf(DateFilter.ThisMonth), types = setOf(TypeFilter.RAW)))
    }
}
