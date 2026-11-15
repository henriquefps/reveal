package dev.hfps.reveal.ui.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.image.format.ExifTime
import dev.hfps.reveal.data.db.CameraCount
import dev.hfps.reveal.data.db.DateCounts
import dev.hfps.reveal.data.db.FilterCounts
import dev.hfps.reveal.data.db.LensCount
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.edit.VersionInfo
import dev.hfps.reveal.data.handoff.ServerIssue
import dev.hfps.reveal.data.index.IndexPhase
import dev.hfps.reveal.data.index.IndexProgress
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.library.XmpExportScope
import dev.hfps.reveal.data.model.CameraFilter
import dev.hfps.reveal.data.model.CullingFilter
import dev.hfps.reveal.data.model.DateFilter
import dev.hfps.reveal.data.model.LensFilter
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceFilter
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.data.model.TypeFilter
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.AddFolderBackupContent
import dev.hfps.reveal.feature.library.DateGrouping
import dev.hfps.reveal.feature.library.FilterSheetContent
import dev.hfps.reveal.feature.library.GalleryAccessContent
import dev.hfps.reveal.feature.library.GalleryOffer
import dev.hfps.reveal.feature.library.GoToDateContent
import dev.hfps.reveal.feature.library.HandoffServerContent
import dev.hfps.reveal.feature.library.LibraryActions
import dev.hfps.reveal.feature.library.LibraryContent
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.LibraryUiState
import dev.hfps.reveal.feature.library.ManageFoldersContent
import dev.hfps.reveal.feature.library.PasteEditsContent
import dev.hfps.reveal.feature.library.SyncBadge
import dev.hfps.reveal.feature.library.ThumbnailSource
import dev.hfps.reveal.feature.library.UnavailableContent
import dev.hfps.reveal.feature.library.UnavailableInfo
import dev.hfps.reveal.feature.library.UnavailableReason
import dev.hfps.reveal.feature.library.buildGrid
import dev.hfps.reveal.feature.library.buildGridEntries
import dev.hfps.reveal.feature.library.rememberDateLabels
import dev.hfps.reveal.feature.library.viewer.VersionsActions
import dev.hfps.reveal.feature.library.viewer.VersionsContent
import dev.hfps.reveal.feature.library.viewer.VersionsUi
import dev.hfps.reveal.feature.library.xmpexport.XmpExportContent
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic library content for the goldens: no photo, generated gradients as thumbnails. */
internal object LibrarySamples {
    val sources = listOf(
        SourceItem(1, "2026-08-07 Beach day", "SanDisk SD card", true, SourceState.AVAILABLE, true, 412, null),
        SourceItem(2, "Camera", "Internal storage", false, SourceState.AVAILABLE, true, 1284, null),
        SourceItem(3, "Trip to the coast", "USB drive", true, SourceState.UNPLUGGED, true, 96, null),
        SourceItem(
            4,
            "Downloads",
            "Internal storage",
            false,
            SourceState.AVAILABLE,
            false,
            0,
            IndexProgress(IndexPhase.LISTING, 820, 0),
        ),
    )

    private fun time(text: String) = checkNotNull(ExifTime.localMillis(text))

    val rows: List<PhotoRow> = (0 until 23).map { i ->
        val day = if (i < 9) "2026:08:09" else "2026:08:07"
        val format = when (i % 4) {
            0, 1 -> PhotoFormat.RAW_PAIR
            2 -> PhotoFormat.RAW
            else -> PhotoFormat.JPEG
        }
        PhotoRow(
            id = i + 1L,
            sourceId = 1,
            name = "DSC0%04d.%s".format(7700 - i, if (format == PhotoFormat.JPEG) "JPG" else "ARW"),
            format = format,
            sortTime = time("$day 18:%02d:00".format(59 - i)),
            captureTime = time("$day 18:%02d:00".format(59 - i)),
            rating = listOf(0, 3, 5, 0, 1, 0, 4, 0, 2)[i % 9],
            label = null,
            flag = when (i % 7) {
                1 -> PickFlag.PICK
                5 -> PickFlag.REJECT
                else -> PickFlag.NONE
            },
            hasEdit = i % 5 == 2,
            versionId = i + 100L,
            editTime = if (i % 5 == 2) 1L else null,
        )
    }

    /** A deterministic gradient per photo (hue from the id): stands in for the embedded previews. */
    val thumbnails = object : ThumbnailSource {
        private val cache = HashMap<Long, ImageBitmap>()

        override fun cached(row: PhotoRow): ImageBitmap = cache.getOrPut(row.id) { gradient(row.id) }

        override suspend fun load(row: PhotoRow): ImageBitmap = cached(row)

        private fun gradient(id: Long): ImageBitmap {
            val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
            val hue = (id * 47 % 360).toFloat()
            val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f))
            val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.6f, 0.35f))
            Canvas(bitmap).drawPaint(
                Paint().apply {
                    shader =
                        LinearGradient(0f, 0f, 96f, 64f, a, b, Shader.TileMode.CLAMP)
                },
            )
            return bitmap.asImageBitmap()
        }
    }

    /**
     * "Opened from other apps" (ADR 0011): two batches, the older gallery shares unreadable now ("preview only"), one
     * picked photo a folder holds too.
     */
    fun fromApps(): LibraryUiState {
        val handed = rows.take(7).mapIndexed { i, row ->
            row.copy(
                sourceId = if (i == 2) 1 else 9,
                handoffAt = if (i < 3) time("2026:11:12 09:30:00") else time("2026:11:10 18:05:00"),
                // Titles, not arrival times: a time header would render in the machine's time zone.
                handoffTitle = if (i < 3) "Hoard picks" else "Gallery",
                unreadable = i >= 3,
                hasEdit = i == 4,
                editTime = if (i == 4) 1L else null,
            )
        }
        val query = PhotoQuery.FROM_APPS
        return LibraryUiState(
            sources = sources,
            sourcesLoaded = true,
            counts = counts,
            query = query,
            entries = buildGridEntries(handed, query),
            photoCount = handed.size,
            photosLoaded = true,
        )
    }

    /**
     * The grid where four photos cannot be shown at all (FIX/U): the placeholder keeps their marks in place (no filter
     * chip since FIX/V). [selected] = some of them selected for "Try again" / "Remove".
     */
    fun unavailable(selected: Set<Long> = emptySet()): LibraryUiState {
        val gone = setOf(2L, 3L, 6L, 9L)
        val shown = rows.map { if (it.id in gone) it.copy(unreadable = true, unavailable = true) else it }
        val query = PhotoQuery()
        return LibraryUiState(
            sources = sources,
            sourcesLoaded = true,
            counts = counts,
            query = query,
            entries = buildGridEntries(shown, query),
            dateIndex = buildGrid(shown, query).dateIndex,
            photoCount = shown.size,
            photosLoaded = true,
            selected = versionsOf(selected),
        )
    }

    /** The selection holds version ids (docs/adr/0025); the samples name photos. */
    private fun versionsOf(photoIds: Set<Long>): Set<Long> =
        rows.filter { it.id in photoIds }.mapTo(HashSet()) { it.versionId }

    /**
     * Virtual copies (docs/adr/0025): photo 2 has an edited "Copy 1", photo 5 an unedited "Black and white", photo
     * 7 a sync conflict copy; [selected] cells by version id.
     */
    fun copies(selected: Set<Long> = emptySet()): LibraryUiState {
        val shown = rows.flatMap { row ->
            when (row.id) {
                2L -> listOf(row, row.copy(versionId = 900, versionPosition = 1, versionName = "Copy 1", editTime = 2))

                5L -> listOf(
                    row,
                    row.copy(versionId = 901, versionPosition = 1, versionName = "Black and white", editTime = null),
                )

                7L -> listOf(
                    row,
                    row.copy(versionId = 902, versionPosition = 1, conflict = true, copyDevice = "Tablet"),
                )

                else -> listOf(row)
            }
        }
        val query = PhotoQuery()
        return LibraryUiState(
            sources = sources,
            sourcesLoaded = true,
            counts = counts,
            query = query,
            entries = buildGridEntries(shown, query),
            dateIndex = buildGrid(shown, query).dateIndex,
            photoCount = shown.size,
            photosLoaded = true,
            selected = selected,
        )
    }

    /** The viewer's Versions sheet of photo 2 (docs/adr/0025): main version on screen, two copies, a conflict copy. */
    val versions = VersionsUi(
        photoName = "DSC07699.ARW",
        versions = listOf(
            VersionInfo(101, "a", 2, 0, null, conflict = false),
            VersionInfo(900, "b", 2, 1, "Copy 1", conflict = false),
            VersionInfo(903, "c", 2, 2, "Black and white, grainy", conflict = false),
            VersionInfo(904, "d", 2, 3, null, conflict = true),
        ),
        currentVersionId = 101,
    )

    val unavailableInfo = UnavailableInfo(
        photoId = 2,
        name = "DSC07699.ARW",
        hasEdit = true,
        reason = UnavailableReason.SENDER,
        appLabel = "Hoard",
        appLaunch = android.content.Intent(),
    )

    /** Per-choice counts of the samples' library (the filter sheet). */
    val counts = FilterCounts(
        fromApps = 7,
        serverOnly = 38,
        picked = 4,
        starred = 15,
        rejected = 3,
        edited = 5,
        notEdited = 1831,
        raw = 1402,
        jpeg = 434,
        synced = 610,
        total = 1836,
    )

    /** The home (FIX/L): every photo, newest first, with [query]'s filters; [paired] adds the Sync group. */
    fun grid(query: PhotoQuery = PhotoQuery(), selected: Set<Long> = emptySet(), paired: Boolean = false) =
        LibraryUiState(
            sources = sources,
            sourcesLoaded = true,
            counts = counts,
            paired = paired,
            syncBadge = if (paired) SyncBadge.Synced else SyncBadge.NotPaired,
            query = query,
            entries = buildGridEntries(rows, query),
            dateIndex = buildGrid(rows, query).dateIndex,
            photoCount = rows.size,
            photosLoaded = true,
            selected = versionsOf(selected),
        )

    private val datesMonths = listOf(
        "2026:11" to 3, "2026:10" to 2, "2026:09" to 4, "2026:07" to 1, "2026:06" to 2, "2026:03" to 3,
        "2026:01" to 1, "2025:12" to 4, "2025:08" to 2, "2025:05" to 1, "2025:02" to 2, "2024:11" to 1,
    )

    /**
     * A library spanning three years (W3/D): months of one to four shooting days with a few photos each, gaps (greyed
     * in "Go to date"), and two photos without a date at the end.
     */
    val datedRows: List<PhotoRow> = buildList {
        var id = 1000L
        for ((month, days) in datesMonths) {
            for (d in days downTo 1) {
                val photos = 2 + ((id + d) % 4).toInt()
                for (p in 0 until photos) {
                    val text = "$month:%02d %02d:%02d:00".format(d * 6, 18 - p, 30)
                    add(
                        rows[(id % rows.size).toInt()].copy(
                            id = id,
                            versionId = id + 10_000,
                            sortTime = time(text),
                            captureTime = time(text),
                            name = "DSC%05d.ARW".format(id),
                        ),
                    )
                    id++
                }
            }
        }
        add(rows[0].copy(id = id, versionId = id + 10_000, sortTime = 0, captureTime = null, name = "scan-01.jpg"))
        add(rows[1].copy(id = id + 1, versionId = id + 10_001, sortTime = 0, captureTime = null, name = "scan-02.jpg"))
    }

    /** The home over [datedRows] grouped by [grouping], with [query]'s filters. */
    fun dated(grouping: DateGrouping = DateGrouping.DAY, query: PhotoQuery = PhotoQuery()): LibraryUiState {
        val grid = buildGrid(datedRows, query, grouping)
        return LibraryUiState(
            sources = sources,
            sourcesLoaded = true,
            counts = counts,
            query = query,
            entries = grid.entries,
            dateIndex = grid.dateIndex,
            grouping = grouping,
            photoCount = datedRows.size,
            photosLoaded = true,
            cameras = listOf(
                CameraCount("SONY", "ZV-E10", 1203),
                CameraCount("NIKON CORPORATION", "NIKON D5600", 412),
                CameraCount("Apple", "iPhone 15 Pro", 221),
            ),
            lenses = listOf(
                LensCount("E PZ 16-50mm F3.5-5.6 OSS", 980),
                LensCount("FE 50mm F1.8", 223),
                LensCount("18.0-55.0 mm f/3.5-5.6", 412),
            ),
            dateCounts = DateCounts(thisMonth = 14, last30Days = 22, thisYear = 640, onThisDay = 9),
        )
    }

    /** Date, camera and lens filters on (W3/D): their chips above the grid and ticked in the sheet. */
    val datedQuery = PhotoQuery(
        filters = PhotoFilters(
            dates = setOf(DateFilter.ThisYear, DateFilter.Range(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31))),
            cameras = setOf(CameraFilter("SONY", "ZV-E10")),
            lenses = setOf(LensFilter("E PZ 16-50mm F3.5-5.6 OSS")),
        ),
    )

    /** Several active filters across groups: chips above the grid. */
    val combined = PhotoQuery(
        filters = PhotoFilters.folder(1).toggle(SourceFilter.FromApps).toggle(CullingFilter.PICKED)
            .toggle(CullingFilter.STARRED).toggle(TypeFilter.RAW).toggle(SyncFilter.NOT_SYNCED),
    )

    /** Paired and uploading: the bar's status, the pending badges and the server-only photos' cloud badges. */
    fun syncing(): LibraryUiState {
        val shown = rows.mapIndexed { i, row ->
            when (i % 6) {
                0 -> row.copy(uploading = true)
                1 -> row.copy(cloudOriginal = true)
                2 -> row.copy(remote = true, cloudOriginal = true)
                else -> row
            }
        }
        return grid(paired = true).copy(
            syncBadge = SyncBadge.Uploading(12),
            entries = buildGridEntries(shown, PhotoQuery()),
            dateIndex = buildGrid(shown, PhotoQuery()).dateIndex,
        )
    }
}

abstract class LibraryScreenshotBase(private val device: String) {
    @get:Rule
    val composeRule = createComposeRule()

    protected fun shoot(name: String, dark: Boolean, state: LibraryUiState, fontScale: Float = 1f) {
        composeRule.setContent {
            Scaled(fontScale) {
                RevealTheme(darkTheme = dark) { LibraryContent(state, LibraryActions(), LibrarySamples.thumbnails) }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/${device}_$name.png")
    }

    /**
     * A sheet's content as it shows: a bottom sheet on phones, a centred dialog ([wide], `RevealBottomSheet`) over the
     * home grid.
     */
    protected fun sheet(name: String, dark: Boolean, wide: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent {
            RevealTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    LibraryContent(LibrarySamples.grid(), LibraryActions(), LibrarySamples.thumbnails)
                    Box(
                        Modifier.fillMaxSize().background(RevealTheme.colors.background.copy(alpha = SCRIM)),
                        if (wide) Alignment.Center else Alignment.BottomCenter,
                    ) {
                        Surface(
                            color = RevealTheme.colors.card,
                            shape = RevealTheme.shapes.sheet,
                            modifier = if (wide) Modifier.widthIn(max = 560.dp).padding(24.dp) else Modifier,
                        ) {
                            Box(Modifier.padding(top = if (wide) 16.dp else 24.dp)) { content() }
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/${device}_$name.png")
    }

    /** The viewer's Versions sheet over the dark viewer: a bottom sheet on phones, a centred dialog when [wide]. */
    protected fun versionsSheet(name: String, wide: Boolean) {
        composeRule.setContent {
            EditorTheme {
                Box(
                    Modifier.fillMaxSize().background(RevealTheme.colors.background),
                    if (wide) Alignment.Center else Alignment.BottomCenter,
                ) {
                    Surface(
                        color = RevealTheme.colors.card,
                        shape = RevealTheme.shapes.sheet,
                        modifier = if (wide) Modifier.widthIn(max = 560.dp).padding(24.dp) else Modifier,
                    ) {
                        VersionsContent(
                            LibrarySamples.versions,
                            VersionsActions(),
                            Modifier.padding(top = if (wide) 16.dp else 24.dp),
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/${device}_$name.png")
    }

    /** The server-reference hand-off notices (docs/adr/0011 "Amendment W3/H"): every state, phone and tablet. */
    protected fun handoffServer(name: String, dark: Boolean, wide: Boolean, issue: ServerIssue?, notFound: Int = 0) =
        sheet(name, dark, wide) { HandoffServerContent(issue, notFound, onPair = {}, onDismiss = {}) }

    protected fun filterSheet(name: String, dark: Boolean, wide: Boolean, paired: Boolean = true) =
        sheet(name, dark, wide) {
            FilterSheetContent(LibrarySamples.grid(LibrarySamples.combined, paired = paired), LibraryActions())
        }

    /** "Go to date" over the dated home, opened at November 2026 (W3/D). */
    protected fun goToDate(name: String, dark: Boolean, wide: Boolean) = sheet(name, dark, wide) {
        val index = checkNotNull(LibrarySamples.dated().dateIndex)
        GoToDateContent(index, current = index.months[2], labels = rememberDateLabels(), onPick = {})
    }

    /** The filter sheet with the W3/D groups: Date presets and a custom range, cameras and lenses with counts. */
    protected fun datedFilters(name: String, dark: Boolean, wide: Boolean) = sheet(name, dark, wide) {
        FilterSheetContent(LibrarySamples.dated(query = LibrarySamples.datedQuery), LibraryActions())
    }

    /** The fast scroller while a finger drags it (W3/D): the thumb highlighted and the month bubble beside it. */
    protected fun scrollerDrag(name: String, dark: Boolean) {
        composeRule.setContent {
            RevealTheme(darkTheme = dark) {
                LibraryContent(LibrarySamples.dated(), LibraryActions(), LibrarySamples.thumbnails)
            }
        }
        // The clock is driven by hand: an idle wait would run through the scroller's hide delay.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(LibraryTags.GRID).performScrollToIndex(40)
        composeRule.mainClock.advanceTimeBy(FRAMES_MS)
        composeRule.onNodeWithTag(LibraryTags.SCROLLER).performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 3f))
        }
        composeRule.mainClock.advanceTimeBy(FRAMES_MS)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/${device}_$name.png")
        composeRule.onNodeWithTag(LibraryTags.SCROLLER).performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
    }

    protected fun manageFolders(name: String, dark: Boolean, wide: Boolean) = sheet(name, dark, wide) {
        ManageFoldersContent(LibrarySamples.sources, paired = true, actions = LibraryActions())
    }

    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }

    private companion object {
        const val SCRIM = 0.6f
        const val FRAMES_MS = 300L
    }
}

/**
 * Library goldens under `app/src/test/screenshots/library/` (phone 390x844, tablets): the home grid with pairs,
 * badges and date headers, active filter chips, the filter sheet, "Manage folders", selection, no match, the empty
 * library, Recent, 200 % font (FIX/L). Record with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class PhoneLibraryScreenshotTest : LibraryScreenshotBase("phone") {
    @Test fun homeLight() = shoot("home_light", dark = false, state = LibrarySamples.grid())

    @Test fun homeDark() = shoot("home_dark", dark = true, state = LibrarySamples.grid())

    @Test fun homeSelecting() =
        shoot("home_selecting_light", dark = false, state = LibrarySamples.grid(selected = setOf(2L, 3L, 4L, 7L)))

    @Test fun homeFontScale200() = shoot(
        "home_light_font200",
        dark = false,
        state = LibrarySamples.grid(),
        fontScale = 2f,
    )

    /** Filters across groups: removable chips and "Clear all" above the grid. */
    @Test fun homeFiltered() = shoot(
        "home_filtered_dark",
        dark = true,
        state = LibrarySamples.grid(LibrarySamples.combined, paired = true),
    )

    /** Paired, uploading: the bar's compact status and the pending badges. */
    @Test fun homeSyncing() = shoot("home_syncing_light", dark = false, state = LibrarySamples.syncing())

    @Test fun filterSheetLight() = filterSheet("filter_sheet_light", dark = false, wide = false)

    @Test fun filterSheetDark() = filterSheet("filter_sheet_dark", dark = true, wide = false, paired = false)

    @Test fun manageFolders() = manageFolders("manage_folders_light", dark = false, wide = false)

    @Test fun emptyLibraryLight() = shoot(
        "empty_light",
        dark = false,
        state = LibraryUiState(sourcesLoaded = true, photosLoaded = true, counts = FilterCounts()),
    )

    /** No folder, but photos other apps handed over: the home grid, not the empty state (ADR 0011). */
    @Test fun onlyHandOffs() = shoot(
        "only_handoffs_dark",
        dark = true,
        state = LibrarySamples.fromApps().copy(sources = emptyList(), query = PhotoQuery()),
    )

    @Test fun emptyLibraryFontScale200() = shoot(
        "empty_dark_font200",
        dark = true,
        state = LibraryUiState(sourcesLoaded = true, photosLoaded = true, counts = FilterCounts()),
        fontScale = 2f,
    )

    @Test fun noMatch() = shoot(
        "no_match_dark",
        dark = true,
        state = LibrarySamples.grid(
            query = PhotoQuery(filters = PhotoFilters(culling = setOf(CullingFilter.PICKED)), search = "zz"),
        ).copy(entries = emptyList(), photoCount = 0),
    )

    /** "Manage folders" → an unplugged folder: the home narrowed to it, with its banner. */
    @Test fun unplugged() = shoot(
        "unplugged_light",
        dark = false,
        state = LibrarySamples.grid(query = PhotoQuery(filters = PhotoFilters.folder(3))),
    )

    @Test fun recentEmpty() = shoot(
        "recent_empty_light",
        dark = false,
        state = LibrarySamples.grid().copy(recent = true, entries = emptyList(), photoCount = 0),
    )

    @Test fun pasteChooser() {
        composeRule.setContent {
            RevealTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(RevealTheme.colors.background), Alignment.BottomCenter) {
                    Surface(color = RevealTheme.colors.card, shape = RevealTheme.shapes.sheet) {
                        PasteEditsContent(
                            copied = Recipe(
                                light = Light(exposureEv = 0.7f, shadows = 35f),
                                colour = Colour(vibrance = 20f),
                                geometry = Geometry(angle = 1.5f),
                            ),
                            groups = RecipePaste.DEFAULT_GROUPS,
                            targets = 4,
                            actions = LibraryActions(),
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/phone_paste_chooser_light.png")
    }

    /** Hand-offs Reveal can no longer read carry the "preview only" badge (ADR 0011 "Unreadable hand-offs"). */
    @Test fun fromAppsPreviewOnly() = shoot(
        "from_apps_preview_only_light",
        dark = false,
        state = LibrarySamples.fromApps(),
    )

    /** Export XMP (ADR 0026 "Amendment W4"): next to the photos, a folder to pick once, a chosen folder only. */
    @Test fun exportXmp() = xmpSheet("phone_export_xmp_dark", XmpExportScope(24, true, emptyList()))

    @Test fun exportXmpPickFolder() =
        xmpSheet("phone_export_xmp_pick_light", XmpExportScope(8, false, listOf(3L to "Shoot 2026")), dark = false)

    @Test fun exportXmpChooseOnly() = xmpSheet(
        "phone_export_xmp_choose_only_dark",
        XmpExportScope(5, false, emptyList()),
    )

    private fun xmpSheet(file: String, scope: XmpExportScope, dark: Boolean = true) {
        composeRule.setContent {
            RevealTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(RevealTheme.colors.background), Alignment.BottomCenter) {
                    Surface(color = RevealTheme.colors.card, shape = RevealTheme.shapes.sheet) {
                        XmpExportContent(scope, {}, {}, {}, {}, Modifier.padding(top = 24.dp))
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/$file.png")
    }

    /** Hoard's offer with limited ("selected photos") access: what stays openable (ADR 0011 "Amendment W4"). */
    @Test fun galleryAccessPartial() = galleryOffer(
        "phone_gallery_access_partial_light",
        GalleryOffer.HOARD,
        true,
        false,
    )

    /** Hoard's offer on receipt of its MediaStore copies (ADR 0011 "Amendment W4"). */
    @Test fun galleryAccessHoard() = galleryOffer("phone_gallery_access_hoard_dark", GalleryOffer.HOARD, false, true)

    /** The once-only gallery access offer for a gallery share that can no longer be read (ADR 0011). */
    @Test fun galleryAccess() = galleryOffer("phone_gallery_access_dark", GalleryOffer.SHARED, false, true)

    private fun galleryOffer(file: String, offer: GalleryOffer, partial: Boolean, dark: Boolean) {
        composeRule.setContent {
            RevealTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(RevealTheme.colors.background), Alignment.BottomCenter) {
                    Surface(color = RevealTheme.colors.card, shape = RevealTheme.shapes.sheet) {
                        GalleryAccessContent(
                            onAllow = {},
                            onDecline = {},
                            modifier = Modifier.padding(top = 24.dp),
                            offer = offer,
                            partial = partial,
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/$file.png")
    }

    /** Adding a folder while paired: what it backs up (FIX/V). */
    @Test fun addFolderBackup() {
        composeRule.setContent {
            RevealTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(RevealTheme.colors.background), Alignment.BottomCenter) {
                    Surface(color = RevealTheme.colors.card, shape = RevealTheme.shapes.sheet) {
                        AddFolderBackupContent(onChoose = {}, onDismiss = {}, modifier = Modifier.padding(top = 24.dp))
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/phone_add_folder_backup_dark.png")
    }

    @Test fun unavailableGrid() = shoot("unavailable_light", dark = false, state = LibrarySamples.unavailable())

    @Test fun unavailableGridDark() = shoot("unavailable_dark", dark = true, state = LibrarySamples.unavailable())

    @Test fun unavailableFilteredSelecting() = shoot(
        "unavailable_selecting_light",
        dark = false,
        state = LibrarySamples.unavailable(selected = setOf(2L, 3L)),
    )

    /** What a tap on an unavailable photo opens: why, and what to do. */
    @Test fun unavailableSheet() {
        composeRule.setContent {
            RevealTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(RevealTheme.colors.background), Alignment.BottomCenter) {
                    Surface(color = RevealTheme.colors.card, shape = RevealTheme.shapes.sheet) {
                        UnavailableContent(
                            LibrarySamples.unavailableInfo,
                            onOpenApp = {},
                            onGallery = {},
                            onTryAgain = {},
                            onRemove = {},
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/phone_unavailable_sheet_dark.png")
    }

    /** Virtual copies as cells of their own, named; one copy selected (docs/adr/0025). */
    @Test fun copies() = shoot("home_copies_dark", dark = true, state = LibrarySamples.copies(selected = setOf(900L)))

    @Test fun handoffServerNotPaired() =
        handoffServer("handoff_server_not_paired_dark", true, false, ServerIssue.NotPaired(12))

    @Test fun handoffServerOther() = handoffServer(
        "handoff_server_other_light",
        false,
        false,
        ServerIssue.OtherServer("Home NAS", 3),
        notFound = 2,
    )

    @Test fun handoffServerUnreachable() =
        handoffServer("handoff_server_unreachable_dark", true, false, ServerIssue.Unreachable(4))

    @Test fun handoffServerMissing() = handoffServer("handoff_server_missing_light", false, false, null, notFound = 3)

    @Test fun versionsSheet() = versionsSheet("versions_sheet", wide = false)

    @Test fun datesDay() = shoot("library_dates_light", dark = false, state = LibrarySamples.dated())

    @Test fun datesMonth() = shoot(
        "library_dates_month_dark",
        dark = true,
        state = LibrarySamples.dated(DateGrouping.MONTH),
    )

    @Test fun datesFiltered() = shoot(
        "library_dates_filtered_dark",
        dark = true,
        state = LibrarySamples.dated(query = LibrarySamples.datedQuery),
    )

    @Test fun datesGoTo() = goToDate("library_dates_goto_light", dark = false, wide = false)

    @Test fun datesFilterSheet() = datedFilters("library_dates_filters_dark", dark = true, wide = false)

    @Test fun datesScroller() = scrollerDrag("library_dates_scroller_light", dark = false)

    @Test fun datesFontScale200() = shoot(
        "library_dates_light_font200",
        dark = false,
        state = LibrarySamples.dated(),
        fontScale = 2f,
    )

    @Test fun indexing() = shoot(
        "indexing_light",
        dark = false,
        state = LibrarySamples.grid(query = PhotoQuery(filters = PhotoFilters.folder(4)))
            .copy(entries = emptyList(), photoCount = 0),
    )
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-hdpi")
class TabletLibraryScreenshotTest : LibraryScreenshotBase("tablet") {
    @Test fun copies() = shoot("home_copies_light", dark = false, state = LibrarySamples.copies())

    @Test fun handoffServerNotPaired() =
        handoffServer("handoff_server_not_paired_dark", true, true, ServerIssue.NotPaired(12))

    @Test fun handoffServerOther() = handoffServer(
        "handoff_server_other_light",
        false,
        true,
        ServerIssue.OtherServer("Home NAS", 3),
        notFound = 2,
    )

    @Test fun versionsSheet() = versionsSheet("versions_sheet", wide = true)

    @Test fun homeLight() = shoot("home_light", dark = false, state = LibrarySamples.grid())

    @Test fun homeDark() = shoot("home_dark", dark = true, state = LibrarySamples.syncing())

    @Test fun homeFiltered() = shoot(
        "home_filtered_light",
        dark = false,
        state = LibrarySamples.grid(LibrarySamples.combined, paired = true),
    )

    @Test fun filterSheet() = filterSheet("filter_sheet_dark", dark = true, wide = true)

    @Test fun manageFolders() = manageFolders("manage_folders_light", dark = false, wide = true)

    @Test fun datesDay() = shoot("library_dates_light", dark = false, state = LibrarySamples.dated())

    @Test fun datesMonth() = shoot(
        "library_dates_month_dark",
        dark = true,
        state = LibrarySamples.dated(DateGrouping.MONTH),
    )

    @Test fun datesGoTo() = goToDate("library_dates_goto_dark", dark = true, wide = true)

    @Test fun datesFilterSheet() = datedFilters("library_dates_filters_light", dark = false, wide = true)

    @Test fun datesScroller() = scrollerDrag("library_dates_scroller_dark", dark = true)

    @Test fun unavailable() = shoot("unavailable_light", dark = false, state = LibrarySamples.unavailable())

    @Test fun unavailableSelecting() = shoot(
        "unavailable_selecting_dark",
        dark = true,
        state = LibrarySamples.unavailable(selected = setOf(2L, 3L)),
    )

    @Test fun recentEmpty() = shoot(
        "recent_empty_light",
        dark = false,
        state = LibrarySamples.grid().copy(recent = true, entries = emptyList(), photoCount = 0),
    )
}

/** The library on a Galaxy Tab S9 (M2/E): selection with its actions inline, 200 % font. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1495dp-h934dp-mdpi")
class TabletS9LibraryScreenshotTest : LibraryScreenshotBase("tablet_s9") {
    @Test fun homeDark() = shoot("home_dark", dark = true, state = LibrarySamples.grid(paired = true))

    @Test fun selecting() =
        shoot("selecting_light", dark = false, state = LibrarySamples.grid(selected = setOf(2L, 3L, 4L, 7L)))

    @Test fun fontScale200() = shoot(
        "home_light_font200",
        dark = false,
        state = LibrarySamples.grid(LibrarySamples.combined, paired = true),
        fontScale = 2f,
    )

    /** Export XMP's sheet is a centred dialog on a tablet (ADR 0026 "Amendment W4"). */
    @Test fun exportXmpSheet() {
        composeRule.setContent {
            RevealTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(RevealTheme.colors.background), Alignment.Center) {
                    Surface(
                        color = RevealTheme.colors.card,
                        shape = RevealTheme.shapes.sheet,
                        modifier = Modifier.widthIn(max = 560.dp),
                    ) {
                        XmpExportContent(
                            XmpExportScope(24, false, listOf(3L to "Shoot 2026")),
                            {},
                            {},
                            {},
                            {},
                            Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/library/tablet_s9_export_xmp_dark.png")
    }
}

/** Tablet portrait (934 dp wide). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w934dp-h1495dp-mdpi")
class TabletPortraitLibraryScreenshotTest : LibraryScreenshotBase("tablet_portrait") {
    @Test fun homeLight() = shoot("home_light", dark = false, state = LibrarySamples.grid())

    @Test fun homeFiltered() = shoot(
        "home_filtered_dark",
        dark = true,
        state = LibrarySamples.grid(LibrarySamples.combined, paired = true),
    )

    @Test fun filterSheet() = filterSheet("filter_sheet_light", dark = false, wide = true)

    @Test fun manageFolders() = manageFolders("manage_folders_dark", dark = true, wide = true)
}

/** A foldable's inner screen (852 dp). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w852dp-h883dp-mdpi")
class FoldableLibraryScreenshotTest : LibraryScreenshotBase("foldable") {
    @Test fun homeDark() = shoot("home_dark", dark = true, state = LibrarySamples.grid())

    @Test fun fontScale200() = shoot(
        "home_dark_font200",
        dark = true,
        state = LibrarySamples.grid(),
        fontScale = 2f,
    )
}

/** A landscape phone (docs/adr/0025): the grid with copies and the viewer's Versions sheet. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w844dp-h390dp-xhdpi")
class LandscapeLibraryScreenshotTest : LibraryScreenshotBase("landscape") {
    @Test fun copies() = shoot("home_copies_dark", dark = true, state = LibrarySamples.copies())

    @Test fun versionsSheet() = versionsSheet("versions_sheet", wide = false)
}
