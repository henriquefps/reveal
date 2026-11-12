package dev.hfps.reveal.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.math.max

/** Loads grid thumbnails; the app uses the thumbnail cache, screenshots use generated images. */
interface ThumbnailSource {
    /** Already in memory: drawn in the same frame. */
    fun cached(row: PhotoRow): ImageBitmap?

    suspend fun load(row: PhotoRow): ImageBitmap?
}

/** Test tags of the library. */
object LibraryTags {
    const val GRID = "library-grid"
    const val SOURCES = "library-sources"
    const val ADD_FOLDER = "library-add-folder"
    const val EXPORT = "library-export"
    const val APPLY_PRESET = "library-apply-preset"
    const val PRESET_STRENGTH = "library-preset-strength"
    const val PRESET_APPLY_CONFIRM = "library-preset-apply"

    /** A preset row of the "Apply preset" chooser. */
    fun preset(uuid: String): String = "library-preset-$uuid"
    const val MENU = "library-menu"
    const val SELECTION_BAR = "library-selection"
    const val EMPTY = "library-empty"
    const val SEARCH_FIELD = "library-search"
    const val COPY_EDITS = "library-copy-edits"
    const val PASTE_EDITS = "library-paste-edits"
    const val PASTE_CHOOSER = "library-paste"
    const val RESET_EDITS = "library-reset-edits"

    const val DELETE_CONFLICTS = "library-delete-conflicts"

    fun cell(id: Long) = "library-cell-$id"

    /** A photo's later version (a sync conflict copy) in the grid. */
    fun copy(versionId: Long) = "library-copy-$versionId"

    fun source(id: Long) = "library-source-$id"
}

/** Grid columns: 3 on a portrait phone (whatever its width), adaptive with a 120 dp minimum cell above. */
internal fun gridColumns(widthDp: Dp, portraitPhone: Boolean): Int =
    if (portraitPhone) PHONE_COLUMNS else max(PHONE_COLUMNS, (widthDp / MIN_CELL).toInt())

private const val PHONE_COLUMNS = 3
private val MIN_CELL = 120.dp
private val GAP = 2.dp
private val PhoneMaxWidth = 600.dp

/**
 * The photo grid inside the card: square cells with 2 dp gaps, date headers in eyebrow style, badges. Tap opens
 * (or toggles while selecting), long-press starts a selection and dragging extends it across rows (with auto-scroll
 * near the edges).
 */
@Composable
fun PhotoGrid(
    entries: List<GridEntry>,
    selected: Set<Long>,
    thumbnails: ThumbnailSource,
    onOpen: (PhotoRow) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    onDraw: (Int) -> Unit = {},
) {
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(LocalDensity.current) { window.width.toDp() }
    val portraitPhone = windowWidth < PhoneMaxWidth && window.height > window.width
    val currentSelected by rememberUpdatedState(selected)
    val currentEntries by rememberUpdatedState(entries)
    val currentOnDraw by rememberUpdatedState(onDraw)
    val haptics = LocalHapticFeedback.current
    val autoScroll = rememberAutoScroll(state)
    BoxWithConstraints(modifier) {
        val columns = gridColumns(maxWidth, portraitPhone)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .testTag(LibraryTags.GRID)
                .photoDragSelect(
                    state = state,
                    selected = { currentSelected },
                    entries = { currentEntries },
                    onSelectionChange = { ids ->
                        val grew = ids.size > currentSelected.size
                        if (grew) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectionChange(ids)
                    },
                    onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onAutoScroll = autoScroll,
                ),
            horizontalArrangement = Arrangement.spacedBy(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            photoItems(
                entries = entries,
                selected = selected,
                thumbnails = thumbnails,
                onLongClick = { row -> onSelectionChange(currentSelected + row.id) },
            ) { row ->
                if (currentSelected.isEmpty()) onOpen(row) else onSelectionChange(currentSelected.toggle(row.id))
            }
        }
    }
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty()) {
            withFrameNanos { }
            currentOnDraw(entries.count { it is GridEntry.Photo })
        }
    }
}

private val AUTO_SCROLL_EDGE = 64.dp
private val AUTO_SCROLL_SPEED = 12.dp

/** The grid's cells: date headers span the row; [onClick] opens or toggles; long-press adds to the selection. */
private fun LazyGridScope.photoItems(
    entries: List<GridEntry>,
    selected: Set<Long>,
    thumbnails: ThumbnailSource,
    onLongClick: (PhotoRow) -> Unit,
    onClick: (PhotoRow) -> Unit,
) {
    items(
        items = entries,
        key = { it.key },
        span = { if (it is GridEntry.Photo) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
        contentType = { if (it is GridEntry.Photo) 1 else 0 },
    ) { entry ->
        when (entry) {
            is GridEntry.Header -> DateHeader(entry)

            is GridEntry.Group -> GroupHeader(entry)

            is GridEntry.Photo -> PhotoCell(
                row = entry.row,
                selected = entry.row.id in selected,
                selecting = selected.isNotEmpty(),
                thumbnails = thumbnails,
                onClick = { onClick(entry.row) },
                onLongClick = { onLongClick(entry.row) },
            )
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/**
 * Scrolls the grid every frame while a drag-select finger is near its top or bottom edge. Returns the callback the
 * drag reports the finger's y and the grid height to (NaN = the drag ended).
 */
@Composable
private fun rememberAutoScroll(state: LazyGridState): (Float, Int) -> Unit {
    var speed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(speed) {
        if (speed != 0f) {
            while (true) {
                state.scrollBy(speed)
                withFrameNanos { }
            }
        }
    }
    val density = LocalDensity.current
    val edge = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val step = with(density) { AUTO_SCROLL_SPEED.toPx() }
    return remember(edge, step) {
        { y, height ->
            speed = when {
                y < edge -> -step
                y > height - edge -> step
                else -> 0f
            }
        }
    }
}

/** Long-press selects the cell under the finger; dragging selects the range from it to the cell under the finger. */
private fun Modifier.photoDragSelect(
    state: LazyGridState,
    selected: () -> Set<Long>,
    entries: () -> List<GridEntry>,
    onSelectionChange: (Set<Long>) -> Unit,
    onStart: () -> Unit,
    onAutoScroll: (y: Float, height: Int) -> Unit,
): Modifier = pointerInput(Unit) {
    var anchor: Long? = null
    var current: Long? = null
    var before: Set<Long> = emptySet()
    fun keyAt(offset: Offset): Long? = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        val position = item.offset
        offset.x.toInt() in position.x until position.x + item.size.width &&
            offset.y.toInt() in position.y until position.y + item.size.height
    }?.key as? Long

    fun range(from: Long, to: Long): Set<Long> {
        val ids = entries().mapNotNull { (it as? GridEntry.Photo)?.row?.id }
        val a = ids.indexOf(from)
        val b = ids.indexOf(to)
        if (a < 0 || b < 0) return emptySet()
        return ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    }
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            val key = keyAt(offset) ?: return@detectDragGesturesAfterLongPress
            anchor = key
            current = key
            before = selected()
            onStart()
            onSelectionChange(before + key)
        },
        onDragEnd = {
            anchor = null
            onAutoScroll(Float.NaN, 0)
        },
        onDragCancel = {
            anchor = null
            onAutoScroll(Float.NaN, 0)
        },
        onDrag = { change, _ ->
            val start = anchor ?: return@detectDragGesturesAfterLongPress
            change.consume()
            onAutoScroll(change.position.y, size.height)
            val key = keyAt(change.position) ?: return@detectDragGesturesAfterLongPress
            if (key != current) {
                current = key
                onSelectionChange(before + range(start, key))
            }
        },
    )
}

@Composable
private fun DateHeader(header: GridEntry.Header, modifier: Modifier = Modifier) {
    val text = remember(header.day) {
        // Capture times are the camera's wall clock stored as UTC: format them in UTC to get the camera's date.
        DateFormat.getDateInstance(DateFormat.FULL).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(header.time))
    }
    Eyebrow(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** A hand-off batch: the sender's title, else when it arrived (local time). */
@Composable
private fun GroupHeader(group: GridEntry.Group, modifier: Modifier = Modifier) {
    val text = group.title ?: remember(group.at) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(group.at))
    }
    Eyebrow(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun PhotoCell(
    row: PhotoRow,
    selected: Boolean,
    selecting: Boolean,
    thumbnails: ThumbnailSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    var image by remember(row.versionId, row.editTime) { mutableStateOf(thumbnails.cached(row)) }
    LaunchedEffect(row.versionId, row.editTime) {
        if (image == null) image = thumbnails.load(row)
    }
    val description = cellDescription(row)
    val selectLabel = stringResource(R.string.library_cell_select)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(colors.muted)
            .testTag(if (row.versionPosition == 0) LibraryTags.cell(row.id) else LibraryTags.copy(row.versionId))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                onLongClick(label = selectLabel) {
                    onLongClick()
                    true
                }
            },
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (row.flag == PickFlag.REJECT) REJECT_ALPHA else 1f),
            )
        }
        // Badges are small labels on the photo; their text grows with the font scale only up to 130 % (TalkBack reads
        // the full description of the cell instead), so a 200 % scale does not cover the photo or clip them.
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_BADGE_SCALE)),
        ) {
            Badges(row, showEdited = !selecting, modifier = Modifier.fillMaxSize())
        }
        if (selecting) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(3.dp, colors.primary),
            )
        }
    }
}

private const val REJECT_ALPHA = 0.4f
private const val MAX_BADGE_SCALE = 1.3f

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.background.copy(alpha = 0.6f))
            .border(1.5.dp, if (selected) colors.primary else colors.foreground.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                PhosphorRegular.Check,
                contentDescription = null,
                tint = colors.primaryForeground,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Corner badges: format and copy/conflict (top-left), cloud state and edited dot (top-right), stars (bottom-left),
 * pick/reject (bottom-right).
 */
@Suppress("LongMethod")
@Composable
private fun Badges(row: PhotoRow, showEdited: Boolean, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    Box(modifier.padding(4.dp)) {
        Column(Modifier.align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            formatBadge(row.format)?.let { label -> LabelBadge(stringResource(label)) }
            if (row.versionPosition > 0) {
                LabelBadge(
                    stringResource(if (row.conflict) R.string.library_badge_conflict else R.string.library_badge_copy),
                    highlight = row.conflict,
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            cloudIcon(cloudState(row))?.let { icon ->
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(colors.background.copy(alpha = BADGE_ALPHA)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(14.dp))
                }
            }
            if (row.hasEdit && showEdited) {
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.primary)
                        .border(1.dp, colors.background, CircleShape),
                )
            }
        }
        if (row.rating > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .clip(RevealTheme.shapes.control)
                    .background(colors.background.copy(alpha = BADGE_ALPHA))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    PhosphorFill.Star,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = row.rating.toString(),
                    style = RevealTheme.typography.eyebrow,
                    color = colors.foreground,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        when (row.flag) {
            PickFlag.PICK -> FlagIcon(PickFlag.PICK, Modifier.align(Alignment.BottomEnd))
            PickFlag.REJECT -> FlagIcon(PickFlag.REJECT, Modifier.align(Alignment.BottomEnd))
            PickFlag.NONE -> Unit
        }
    }
}

@Composable
private fun LabelBadge(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val colors = RevealTheme.colors
    Text(
        text = text,
        style = RevealTheme.typography.eyebrow,
        color = if (highlight) colors.background else colors.foreground,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier
            .clip(RevealTheme.shapes.control)
            .background(if (highlight) colors.warning else colors.background.copy(alpha = BADGE_ALPHA))
            .padding(horizontal = 4.dp),
    )
}

/** Where a photo's original is, for the cell badge (ADR 0018 "Cloud states"). */
internal enum class CloudState {
    /** On this device only (or sync is off): no badge. */
    LOCAL,

    /** On this device, and the server has the original too (NAS or backup). */
    BACKED_UP,

    /** Only the server has it; the original opens by range from there. */
    CLOUD,

    /** Only another device has the original; this one has its smart preview. */
    PREVIEW,
}

internal fun cloudState(row: PhotoRow): CloudState = when {
    row.remote && row.cloudOriginal -> CloudState.CLOUD
    row.remote -> CloudState.PREVIEW
    row.cloudOriginal -> CloudState.BACKED_UP
    else -> CloudState.LOCAL
}

private fun cloudIcon(state: CloudState) = when (state) {
    CloudState.LOCAL -> null
    CloudState.BACKED_UP -> PhosphorRegular.CloudCheck
    CloudState.CLOUD -> PhosphorRegular.Cloud
    CloudState.PREVIEW -> PhosphorRegular.Image
}

@Composable
private fun FlagIcon(flag: PickFlag, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(colors.background.copy(alpha = BADGE_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (flag == PickFlag.PICK) PhosphorFill.CheckCircle else PhosphorFill.XCircle,
            contentDescription = null,
            tint = if (flag == PickFlag.PICK) colors.primary else colors.destructive,
            modifier = Modifier.size(18.dp),
        )
    }
}

private const val BADGE_ALPHA = 0.72f

internal fun formatBadge(format: PhotoFormat): Int? = when (format) {
    PhotoFormat.RAW_PAIR -> R.string.library_badge_raw_jpg
    PhotoFormat.RAW -> R.string.library_badge_raw
    else -> null
}

/** What TalkBack reads for a cell: name, format, stars, flag, edited. */
@Composable
internal fun cellDescription(row: PhotoRow): String {
    val parts = mutableListOf(row.name, stringResource(formatName(row.format)))
    if (row.rating > 0) parts += pluralStringResource(R.plurals.library_stars, row.rating, row.rating)
    when (row.flag) {
        PickFlag.PICK -> parts += stringResource(R.string.library_flag_picked)
        PickFlag.REJECT -> parts += stringResource(R.string.library_flag_rejected)
        PickFlag.NONE -> Unit
    }
    if (row.hasEdit) parts += stringResource(R.string.library_edited)
    if (row.versionPosition > 0) {
        parts += if (row.conflict) {
            stringResource(R.string.library_conflict_copy_from, row.copyDevice.orEmpty())
        } else {
            stringResource(R.string.library_badge_copy)
        }
    }
    when (cloudState(row)) {
        CloudState.LOCAL -> Unit
        CloudState.BACKED_UP -> parts += stringResource(R.string.library_cloud_backed_up)
        CloudState.CLOUD -> parts += stringResource(R.string.library_cloud_only)
        CloudState.PREVIEW -> parts += stringResource(R.string.library_cloud_preview)
    }
    return parts.joinToString(", ")
}

internal fun formatName(format: PhotoFormat): Int = when (format) {
    PhotoFormat.RAW_PAIR -> R.string.library_format_raw_pair
    PhotoFormat.RAW -> R.string.library_format_raw
    PhotoFormat.JPEG -> R.string.library_format_jpeg
    PhotoFormat.HEIF -> R.string.library_format_heif
    PhotoFormat.PNG -> R.string.library_format_png
    PhotoFormat.WEBP -> R.string.library_format_webp
}
