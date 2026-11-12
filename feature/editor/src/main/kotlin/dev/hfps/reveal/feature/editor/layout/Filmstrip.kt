package dev.hfps.reveal.feature.editor.layout

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R

/** The filmstrip's thumbnails: the library's thumbnail cache (edited thumbs included), or fakes in tests. */
interface FilmstripThumbs {
    /** The thumbnail already in memory, or `null`. */
    fun cached(row: PhotoRow): ImageBitmap?

    /** Loads (or renders) the thumbnail; `null` when it cannot be read. */
    suspend fun load(row: PhotoRow): ImageBitmap?
}

/**
 * The photos the filmstrip shows (the grid's folder or library scope the editor was opened from, live, so ratings and
 * edited thumbnails follow) and the one being edited.
 */
@Immutable
class FilmstripUi(val photos: List<PhotoRow>, val currentVersionId: Long?, val thumbs: FilmstripThumbs) {
    /** Position of the photo being edited, -1 when it is not in the list. */
    val index: Int = photos.indexOfFirst { it.versionId == currentVersionId }

    /** The photo [step] places after (negative = before) the current one, `null` at the ends. */
    fun neighbour(step: Int): PhotoRow? {
        if (index < 0) return null
        return photos.getOrNull(index + step)
    }
}

/**
 * The tablet editor's filmstrip (docs/plan/ux.md "Landscape and tablets"): one lazy row of square thumbnails with the
 * edited thumb, stars and pick/reject, the current photo outlined; a tap moves the editor to that photo. Lazy and
 * keyed by photo, so a folder of thousands of photos composes only the visible cells.
 */
@Composable
fun Filmstrip(ui: FilmstripUi, onOpen: (PhotoRow) -> Unit, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (ui.index - LEAD_CELLS).coerceAtLeast(0))
    KeepCurrentVisible(state, ui.index)
    val description = pluralStringResource(R.plurals.editor_filmstrip, ui.photos.size, ui.photos.size)
    LazyRow(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .background(colors.background)
            .testTag(EditorTags.FILMSTRIP)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(ui.photos, key = { it.gridKey }, contentType = { CELL }) { row ->
            FilmstripCell(row, row.versionId == ui.currentVersionId, ui.thumbs) { onOpen(row) }
        }
    }
}

/** Scrolls so the current photo is visible when it changes (keyboard and swipe moves). */
@Composable
private fun KeepCurrentVisible(state: LazyListState, index: Int) {
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val shown = visible.any {
            it.index == index && it.offset >= 0 &&
                it.offset + it.size <= state.layoutInfo.viewportEndOffset
        }
        if (!shown) state.scrollToItem((index - LEAD_CELLS).coerceAtLeast(0))
    }
}

@Composable
private fun FilmstripCell(row: PhotoRow, current: Boolean, thumbs: FilmstripThumbs, onClick: () -> Unit) {
    val colors = RevealTheme.colors
    var image by remember(row.id, row.editTime) { mutableStateOf(thumbs.cached(row)) }
    LaunchedEffect(row.id, row.editTime) {
        if (image == null) image = thumbs.load(row)
    }
    val description = cellDescription(row)
    Box(
        modifier = Modifier
            .size(CELL_SIZE)
            .clip(RevealTheme.shapes.control)
            .background(colors.muted)
            .clickable(onClick = onClick)
            .testTag(EditorTags.filmstripCell(row.versionId))
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = current
            },
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (row.flag == PickFlag.REJECT) REJECT_ALPHA else 1f),
            )
        }
        // Badge text stays small at large font scales (the description carries the values for TalkBack).
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_BADGE_SCALE)),
        ) {
            CellBadges(row)
        }
        if (current) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(CURRENT_BORDER, colors.primary, RevealTheme.shapes.control),
            )
        }
    }
}

/** Edited dot (top-right), stars (bottom-left), pick/reject (bottom-right), as in the library grid. */
@Composable
private fun CellBadges(row: PhotoRow) {
    val colors = RevealTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .padding(3.dp),
    ) {
        if (row.hasEdit) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .border(1.dp, colors.background, CircleShape),
            )
        }
        if (row.rating > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .clip(RevealTheme.shapes.control)
                    .background(colors.background.copy(alpha = BADGE_ALPHA))
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    PhosphorFill.Star,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    text = row.rating.toString(),
                    style = RevealTheme.typography.eyebrow,
                    color = colors.foreground,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        if (row.flag != PickFlag.NONE) {
            Icon(
                imageVector = if (row.flag == PickFlag.PICK) PhosphorFill.CheckCircle else PhosphorFill.XCircle,
                contentDescription = null,
                tint = if (row.flag == PickFlag.PICK) colors.primary else colors.destructive,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colors.background.copy(alpha = BADGE_ALPHA)),
            )
        }
    }
}

@Composable
private fun cellDescription(row: PhotoRow): String {
    val parts = buildList {
        add(row.name)
        if (row.rating > 0) add(pluralStringResource(R.plurals.editor_filmstrip_stars, row.rating, row.rating))
        when (row.flag) {
            PickFlag.PICK -> add(stringResource(R.string.editor_filmstrip_picked))
            PickFlag.REJECT -> add(stringResource(R.string.editor_filmstrip_rejected))
            PickFlag.NONE -> Unit
        }
        if (row.hasEdit) add(stringResource(R.string.editor_filmstrip_edited))
    }
    return parts.joinToString(", ")
}

/** Height of the filmstrip row. */
val FilmstripHeight = 76.dp

private val STRIP_HEIGHT = FilmstripHeight
private val CELL_SIZE = 64.dp
private val CURRENT_BORDER = 2.dp
private const val LEAD_CELLS = 3
private const val CELL = 0
private const val REJECT_ALPHA = 0.4f
private const val BADGE_ALPHA = 0.72f
private const val MAX_BADGE_SCALE = 1.3f
