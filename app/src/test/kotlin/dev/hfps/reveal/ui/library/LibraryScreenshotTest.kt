package dev.hfps.reveal.ui.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.image.format.ExifTime
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.index.IndexPhase
import dev.hfps.reveal.data.index.IndexProgress
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilter
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.LibraryActions
import dev.hfps.reveal.feature.library.LibraryContent
import dev.hfps.reveal.feature.library.LibrarySelection
import dev.hfps.reveal.feature.library.LibraryUiState
import dev.hfps.reveal.feature.library.SpecialSource
import dev.hfps.reveal.feature.library.ThumbnailSource
import dev.hfps.reveal.feature.library.buildGridEntries
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

    fun grid(
        selection: LibrarySelection = LibrarySelection.Folder(1),
        query: PhotoQuery = PhotoQuery(),
        selected: Set<Long> = emptySet(),
    ) = LibraryUiState(
        sources = sources,
        sourcesLoaded = true,
        selection = selection,
        query = query,
        entries = buildGridEntries(rows, query.copy(scope = LibraryScope.Source(1))),
        photoCount = rows.size,
        photosLoaded = true,
        selected = selected,
    )
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

    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }
}

/**
 * Library goldens under `app/src/test/screenshots/library/` (phone 390x844, tablet 1280x800): sources, grid with
 * pairs/badges/date headers, selection, filters with no match, empty library, 200 % font. Record with
 * `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class PhoneLibraryScreenshotTest : LibraryScreenshotBase("phone") {
    private val sourcesOnly = LibrarySamples.grid().copy(selection = null)

    @Test fun sourcesLight() = shoot("sources_light", dark = false, state = sourcesOnly)

    @Test fun sourcesDark() = shoot("sources_dark", dark = true, state = sourcesOnly)

    @Test fun gridLight() = shoot("grid_light", dark = false, state = LibrarySamples.grid())

    @Test fun gridDark() = shoot("grid_dark", dark = true, state = LibrarySamples.grid())

    @Test fun gridSelecting() =
        shoot("grid_selecting_light", dark = false, state = LibrarySamples.grid(selected = setOf(2L, 3L, 4L, 7L)))

    @Test fun gridFontScale200() = shoot(
        "grid_light_font200",
        dark = false,
        state = LibrarySamples.grid(),
        fontScale = 2f,
    )

    @Test fun sourcesFontScale200() = shoot("sources_dark_font200", dark = true, state = sourcesOnly, fontScale = 2f)

    @Test fun emptyLibraryLight() = shoot(
        "empty_light",
        dark = false,
        state = LibraryUiState(sourcesLoaded = true, photosLoaded = true),
    )

    @Test fun emptyLibraryFontScale200() = shoot(
        "empty_dark_font200",
        dark = true,
        state = LibraryUiState(sourcesLoaded = true, photosLoaded = true),
        fontScale = 2f,
    )

    @Test fun noMatch() = shoot(
        "no_match_dark",
        dark = true,
        state = LibrarySamples.grid(
            query = PhotoQuery(filter = PhotoFilter.PICKED, search = "zz"),
        ).copy(entries = emptyList(), photoCount = 0),
    )

    @Test fun unplugged() = shoot(
        "unplugged_light",
        dark = false,
        state = LibrarySamples.grid(selection = LibrarySelection.Folder(3)),
    )

    @Test fun indexing() = shoot(
        "indexing_light",
        dark = false,
        state = LibrarySamples.grid(selection = LibrarySelection.Folder(4)).copy(entries = emptyList(), photoCount = 0),
    )
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-hdpi")
class TabletLibraryScreenshotTest : LibraryScreenshotBase("tablet") {
    @Test fun twoPaneLight() = shoot("two_pane_light", dark = false, state = LibrarySamples.grid())

    @Test fun twoPaneDark() = shoot("two_pane_dark", dark = true, state = LibrarySamples.grid())

    @Test fun twoPaneRecentEmpty() = shoot(
        "two_pane_recent_empty_light",
        dark = false,
        state = LibrarySamples.grid(
            selection = LibrarySelection.Special(SpecialSource.RECENT),
        ).copy(entries = emptyList(), photoCount = 0),
    )
}
