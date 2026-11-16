package dev.hfps.reveal.feature.library

import android.content.res.Resources
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.UnavailablePlaceholder
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/** Loads grid thumbnails; the app uses the thumbnail cache, screenshots use generated images. */
interface ThumbnailSource {
    /** Already in memory: drawn in the same frame. */
    fun cached(row: PhotoRow): ImageBitmap?

    suspend fun load(row: PhotoRow): ImageBitmap?

    /** [cached] for a cell [edge] px wide: a source may keep a smaller thumbnail for small cells (W2/P). */
    fun cached(row: PhotoRow, edge: Int): ImageBitmap? = cached(row)

    /** [load] for a cell [edge] px wide. */
    suspend fun load(row: PhotoRow, edge: Int): ImageBitmap? = load(row)

    /**
     * The edited thumbnail once its AI masks are ready (docs/adr/0022 "M4/F"), after [load] showed it without them;
     * suspends while they are made, null when there is nothing to add.
     */
    suspend fun upgrade(row: PhotoRow): ImageBitmap? = null
}

/** Test tags of the library. */
object LibraryTags {
    const val GRID = "library-grid"
    const val SOURCES = "library-sources"

    /** The home's top bar actions and filter bar (FIX/L). */
    const val RECENT = "library-recent"
    const val FOLDERS = "library-folders"
    const val SYNC_STATUS = "library-sync-status"
    const val SETTINGS = "library-settings"
    const val FILTERS = "library-filters"
    const val FILTERS_CLEAR = "library-filters-clear"
    const val FILTER_SHEET = "library-filter-sheet"
    const val FILTER_SHEET_DONE = "library-filter-sheet-done"
    const val FOLDERS_SHEET = "library-folders-sheet"

    /** A choice in the filter sheet, by its `PhotoListCodec` token (`c:PICKED`, `f:12`, `apps`, ...). */
    fun filterChoice(token: String) = "library-filter-$token"

    /** An active filter's chip above the grid (a tap removes it). */
    fun activeFilter(token: String) = "library-active-$token"

    /** A folder's menu in "Manage folders", and its items. */
    fun folderMenu(id: Long) = "library-folder-menu-$id"
    const val FOLDER_SHOW = "library-folder-show"
    const val FOLDER_REMOVE = "library-folder-remove"
    const val FOLDER_BACKUP_EVERYTHING = "library-folder-backup-everything"
    const val FOLDER_BACKUP_EDITED = "library-folder-backup-edited"
    const val FOLDER_BACKED_UP_BY_HOARD = "library-folder-backed-up-by-hoard"
    const val ADD_FOLDER = "library-add-folder"
    const val EXPORT = "library-export"
    const val APPLY_PRESET = "library-apply-preset"
    const val EXPORT_XMP = "library-export-xmp"
    const val MENU_EXPORT_XMP = "library-menu-export-xmp"
    const val FOLDER_EXPORT_XMP = "library-folder-export-xmp"
    const val XMP_SHEET = "library-xmp-sheet"
    const val XMP_NEXT = "library-xmp-next"
    const val XMP_NEXT_PICK = "library-xmp-next-pick"
    const val XMP_CHOOSE = "library-xmp-choose"
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

    /** The placeholder of a photo in Hoard's trash (ADR 0018 "R1"). */
    fun trashed(id: Long) = "library-trashed-$id"

    /** The cloud badge of cell [id], by `CloudState` name. */
    fun cloudBadge(id: Long, state: String) = "library-cloud-$id-$state"

    /** A photo's later version (a virtual copy or a sync conflict copy) in the grid. */
    fun copy(versionId: Long) = "library-copy-$versionId"

    /** The name badge of a later version's cell. */
    fun copyBadge(versionId: Long) = "library-copy-badge-$versionId"
    const val CREATE_COPY = "library-create-copy"
    const val SELECTION_MENU = "library-selection-menu"
    const val DELETE_COPIES = "library-delete-copies"

    fun source(id: Long) = "library-source-$id"

    /** W3/D: the sticky month, the fast scroller and its bubble, "Go to date", grouping, the custom range. */
    const val STICKY_MONTH = "library-sticky-month"
    const val SCROLLER = "library-scroller"
    const val SCROLLER_BUBBLE = "library-scroller-bubble"
    const val GO_TO_DATE = "library-go-to-date"
    const val GO_TO_DATE_SHEET = "library-go-to-date-sheet"
    const val GROUP_BY_MONTH = "library-group-month"
    const val GROUP_BY_DAY = "library-group-day"
    const val DATE_RANGE_SHEET = "library-date-range"
    const val DATE_RANGE_APPLY = "library-date-range-apply"

    /** A month by its `DateIndex` key: `2026-11`, or `nodate`. */
    fun monthKey(month: Int): String = if (month == DateIndex.NO_DATE) {
        "nodate"
    } else {
        "%04d-%02d".format(java.util.Locale.ROOT, DateIndex.year(month), DateIndex.monthOfYear(month))
    }

    /** A month's cell in "Go to date". */
    fun month(month: Int) = "library-month-${monthKey(month)}"

    /** A month's header in the grid. */
    fun monthHeader(month: Int) = "library-month-header-${monthKey(month)}"
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
@Suppress("LongMethod")
@Composable
fun PhotoGrid(
    entries: List<GridEntry>,
    /** Selected cells by version id. */
    selected: Set<Long>,
    thumbnails: ThumbnailSource,
    onOpen: (PhotoRow) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    onDraw: (Int) -> Unit = {},
    /** Where each month starts (W3/D): month headers, the sticky month and the scroller's dates; null = no dates. */
    dateIndex: DateIndex? = null,
    /** A tap on the sticky month: "Go to date". */
    onMonthClick: () -> Unit = {},
) {
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(LocalDensity.current) { window.width.toDp() }
    val portraitPhone = windowWidth < PhoneMaxWidth && window.height > window.width
    val currentSelected by rememberUpdatedState(selected)
    val currentEntries by rememberUpdatedState(entries)
    val currentOnDraw by rememberUpdatedState(onDraw)
    val haptics = LocalHapticFeedback.current
    val autoScroll = rememberAutoScroll(state)
    val labels = rememberDateLabels()
    val scroller = remember { DateScrollerState() }
    val hoverEdge = with(LocalDensity.current) { SCROLLER_HOVER_EDGE.toPx() }
    BoxWithConstraints(modifier.dateScrollerHover(scroller, hoverEdge)) {
        val columns = gridColumns(maxWidth, portraitPhone)
        // The cells' width in pixels: the thumbnails are decoded no larger than they are drawn.
        val cellPx = with(LocalDensity.current) { ((maxWidth - GAP * (columns - 1)) / columns).roundToPx() }
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
                labels = labels,
                cellPx = cellPx,
                onLongClick = { row -> onSelectionChange(currentSelected + row.versionId) },
            ) { row ->
                if (currentSelected.isEmpty()) onOpen(row) else onSelectionChange(currentSelected.toggle(row.versionId))
            }
        }
        if (dateIndex != null) StickyMonthHeader(state, dateIndex, labels, onMonthClick)
        DateScroller(
            state = state,
            index = dateIndex,
            labelAt = { at -> scrollerLabel(currentEntries, dateIndex, labels, at) },
            scroller = scroller,
        )
    }
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty()) {
            withFrameNanos { }
            currentOnDraw(entries.count { it is GridEntry.Photo })
        }
    }
}

/** The scroller's bubble at entry [at]: its month in a date order, else the photo name's first letter. */
@Suppress("ReturnCount")
private fun scrollerLabel(entries: List<GridEntry>, index: DateIndex?, labels: DateLabels, at: Int): String? {
    if (index != null) return index.monthAt(at)?.let(labels::monthShort)
    val row = (entries.getOrNull(at) as? GridEntry.Photo)?.row ?: return null
    return row.name.firstOrNull()?.uppercaseChar()?.toString()
}

private val SCROLLER_HOVER_EDGE = 48.dp
private val AUTO_SCROLL_EDGE = 64.dp
private val AUTO_SCROLL_SPEED = 12.dp

/** The grid's cells: date headers span the row; [onClick] opens or toggles; long-press adds to the selection. */
private fun LazyGridScope.photoItems(
    entries: List<GridEntry>,
    selected: Set<Long>,
    thumbnails: ThumbnailSource,
    labels: DateLabels,
    cellPx: Int,
    onLongClick: (PhotoRow) -> Unit,
    onClick: (PhotoRow) -> Unit,
) {
    items(
        items = entries,
        key = { it.key },
        span = { if (it is GridEntry.Photo) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
        contentType = {
            when (it) {
                is GridEntry.Photo -> 1
                is GridEntry.Month -> 2
                else -> 0
            }
        },
    ) { entry ->
        when (entry) {
            is GridEntry.Month -> GridMonthHeader(entry, labels)

            is GridEntry.Header -> DateHeader(entry, labels)

            is GridEntry.Group -> GroupHeader(entry)

            is GridEntry.Photo -> PhotoCell(
                row = entry.row,
                selected = entry.row.versionId in selected,
                selecting = selected.isNotEmpty(),
                thumbnails = thumbnails,
                cellPx = cellPx,
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

    // Selection keys are version ids (docs/adr/0025): a virtual copy's cell is selected on its own.
    fun keyAt(offset: Offset): Long? {
        val key = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val position = item.offset
            offset.x.toInt() in position.x until position.x + item.size.width &&
                offset.y.toInt() in position.y until position.y + item.size.height
        }?.key ?: return null
        return entries().firstNotNullOfOrNull { entry ->
            (entry as? GridEntry.Photo)?.row?.takeIf { it.gridKey == key }
        }
            ?.versionId
    }

    fun range(from: Long, to: Long): Set<Long> {
        val ids = entries().mapNotNull { (it as? GridEntry.Photo)?.row?.versionId }
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

/** A day sub-header under its month (W3/D): weekday, day and month, in the camera's date. */
@Composable
private fun DateHeader(header: GridEntry.Header, labels: DateLabels, modifier: Modifier = Modifier) {
    val text = remember(header.day, labels) { labels.day(header.time) }
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
    cellPx: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    var image by remember(row.versionId, row.editTime, cellPx) { mutableStateOf(thumbnails.cached(row, cellPx)) }
    val placeholder = row.inTrash || row.unavailable
    LaunchedEffect(row.versionId, row.editTime, placeholder, cellPx) {
        if (image == null && !placeholder) image = thumbnails.load(row, cellPx)
        if (image != null && row.editTime != null) thumbnails.upgrade(row)?.let { image = it }
    }
    // The description is built only when the semantics are read (TalkBack, tests), not for every cell composed.
    val resources = LocalResources.current
    val selectLabel = stringResource(R.string.library_cell_select)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(colors.muted)
            .testTag(if (row.versionPosition == 0) LibraryTags.cell(row.id) else LibraryTags.copy(row.versionId))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = cellDescription(resources, row)
                this.selected = selected
                onLongClick(label = selectLabel) {
                    onLongClick()
                    true
                }
            },
    ) {
        val bitmap = image
        if (placeholder) {
            CellPlaceholder(row)
        } else if (bitmap != null) {
            // Drawn by the cell itself, not an Image node: a tablet row composes and measures 13 cells at once while
            // the grid flings (W2/P).
            Box(Modifier.fillMaxSize().cropImage(bitmap, if (row.flag == PickFlag.REJECT) REJECT_ALPHA else 1f))
        }
        if (hasBadges(row, showEdited = !selecting)) {
            // Badges are small labels on the photo; their text grows with the font scale only up to 130 % (TalkBack
            // reads the full description of the cell instead), so a 200 % scale does not cover the photo or clip them.
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_BADGE_SCALE)),
            ) {
                Badges(row, showEdited = !selecting, modifier = Modifier.fillMaxSize())
            }
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

/** [bitmap] scaled to cover the node, centred and clipped (what `Image(contentScale = Crop)` draws). */
private fun Modifier.cropImage(bitmap: ImageBitmap, alpha: Float): Modifier = drawWithCache {
    val scale = max(size.width / bitmap.width, size.height / bitmap.height)
    val width = (bitmap.width * scale).roundToInt()
    val height = (bitmap.height * scale).roundToInt()
    val offset = IntOffset(((size.width - width) / 2f).roundToInt(), ((size.height - height) / 2f).roundToInt())
    val dstSize = IntSize(width, height)
    onDrawBehind { clipRect { drawImage(bitmap, dstOffset = offset, dstSize = dstSize, alpha = alpha) } }
}

/**
 * A cell nothing can show: a photo in Hoard's trash (R1: it opens to a note, never to the editor), or an unavailable
 * one (FIX/U: never an empty square, the cell says so).
 */
@Composable
private fun CellPlaceholder(row: PhotoRow) {
    if (row.inTrash) {
        UnavailablePlaceholder(
            name = row.name,
            label = stringResource(R.string.library_in_trash_label),
            modifier = Modifier.testTag(LibraryTags.trashed(row.id)),
        )
    } else {
        UnavailablePlaceholder(
            name = row.name,
            label = stringResource(R.string.library_unavailable_label),
            modifier = Modifier.testTag(UnavailableTags.cell(row.id)),
        )
    }
}

/** Whether [Badges] would draw anything for [row] (most cells have none: they skip the badge layer entirely). */
internal fun hasBadges(row: PhotoRow, showEdited: Boolean): Boolean =
    formatBadge(row.format) != null || row.versionPosition > 0 || cloudState(row) != CloudState.LOCAL ||
        (row.edited && showEdited) || row.rating > 0 || row.flag != PickFlag.NONE

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
                    when {
                        row.conflict -> stringResource(R.string.library_badge_conflict)

                        // A virtual copy shows its name ("Copy 1", or what the user called it, docs/adr/0025).
                        else -> row.versionName ?: stringResource(R.string.library_badge_copy)
                    },
                    highlight = row.conflict,
                    modifier = Modifier.testTag(LibraryTags.copyBadge(row.versionId)),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val cloud = cloudState(row)
            cloudIcon(cloud)?.let { icon ->
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.background.copy(alpha = BADGE_ALPHA))
                        .testTag(LibraryTags.cloudBadge(row.id, cloud.name)),
                    contentAlignment = Alignment.Center,
                ) {
                    // The badge says what it means (TalkBack reads it as part of the cell, ADR 0011 "Fallbacks").
                    Icon(
                        icon,
                        contentDescription = cloudDescription(cloud)?.let { stringResource(it) },
                        tint = colors.foreground,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (row.edited && showEdited) {
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

    /** On this device, and its original (or smart preview) waits to go up: the pending badge (FIX/L). */
    UPLOADING,

    /** Only the server has it; the original opens by range from there. */
    CLOUD,

    /** Only another device has the original; this one has its smart preview. */
    PREVIEW,

    /**
     * Only another device has the original and the server has no smart preview of it (only edited photos get one,
     * ADR 0018 "Smart previews"): its ratings and labels are here, its pixels are not.
     */
    ELSEWHERE,

    /**
     * Another app handed it over and Reveal can no longer read it (its grant ended, ADR 0011): it shows and edits its
     * local preview.
     */
    LOCAL_PREVIEW,
}

internal fun cloudState(row: PhotoRow): CloudState = when {
    // Nothing shows it: the placeholder says so, a cloud badge would only repeat it badly.
    row.unavailable || row.inTrash -> CloudState.LOCAL

    // A hand-off Reveal can no longer read whose original the server has opens from there (ADR 0011 "Fallbacks").
    row.unreadable && row.cloudOriginal -> CloudState.CLOUD

    row.unreadable -> CloudState.LOCAL_PREVIEW

    row.remote && row.cloudOriginal -> CloudState.CLOUD

    row.remote && row.cloudPreview -> CloudState.PREVIEW

    row.remote -> CloudState.ELSEWHERE

    row.cloudOriginal -> CloudState.BACKED_UP

    row.uploading -> CloudState.UPLOADING

    else -> CloudState.LOCAL
}

private fun cloudIcon(state: CloudState) = when (state) {
    CloudState.LOCAL -> null
    CloudState.BACKED_UP -> PhosphorRegular.CloudCheck
    CloudState.UPLOADING -> PhosphorRegular.CloudArrowUp
    CloudState.CLOUD -> PhosphorRegular.Cloud
    CloudState.PREVIEW, CloudState.LOCAL_PREVIEW -> PhosphorRegular.Image
    CloudState.ELSEWHERE -> PhosphorRegular.DeviceMobile
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
internal fun cellDescription(resources: Resources, row: PhotoRow): String {
    val parts = mutableListOf(row.name, resources.getString(formatName(row.format)))
    if (row.unavailable) parts += resources.getString(R.string.library_unavailable_cell)
    if (row.inTrash) parts += resources.getString(R.string.library_in_trash_cell)
    if (row.rating > 0) parts += resources.getQuantityString(R.plurals.library_stars, row.rating, row.rating)
    when (row.flag) {
        PickFlag.PICK -> parts += resources.getString(R.string.library_flag_picked)
        PickFlag.REJECT -> parts += resources.getString(R.string.library_flag_rejected)
        PickFlag.NONE -> Unit
    }
    if (row.edited) parts += resources.getString(R.string.library_edited)
    if (row.versionPosition > 0) {
        parts += if (row.conflict) {
            resources.getString(R.string.library_conflict_copy_from, row.copyDevice.orEmpty())
        } else {
            row.versionName ?: resources.getString(R.string.library_badge_copy)
        }
    }
    // The cloud state is read from the badge's own description (merged into the cell).
    return parts.joinToString(", ")
}

/** What the grid's cloud badge means, read by TalkBack (null without a badge). */
internal fun cloudDescription(state: CloudState): Int? = when (state) {
    CloudState.LOCAL -> null
    CloudState.BACKED_UP -> R.string.library_cloud_backed_up
    CloudState.UPLOADING -> R.string.library_cloud_uploading
    CloudState.CLOUD -> R.string.library_cloud_only
    CloudState.PREVIEW -> R.string.library_cloud_preview
    CloudState.ELSEWHERE -> R.string.library_cloud_elsewhere
    CloudState.LOCAL_PREVIEW -> R.string.library_local_preview
}

internal fun formatName(format: PhotoFormat): Int = when (format) {
    PhotoFormat.RAW_PAIR -> R.string.library_format_raw_pair
    PhotoFormat.RAW -> R.string.library_format_raw
    PhotoFormat.JPEG -> R.string.library_format_jpeg
    PhotoFormat.HEIF -> R.string.library_format_heif
    PhotoFormat.PNG -> R.string.library_format_png
    PhotoFormat.WEBP -> R.string.library_format_webp
}
