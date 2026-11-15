package dev.hfps.reveal.feature.library

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.model.DateFilter
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Formatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The grid's date labels in the device's language (W3/D): sentence case month titles ("Novembro de 2026"), short
 * months for the scroller's bubble, day sub-headers. Dates are the camera's wall clock stored as UTC millis, so every
 * format runs in UTC. Labels are cached per month (one formatter call per month, not per frame).
 */
@Stable
class DateLabels(
    private val locale: Locale,
    private val noDate: String,
    private val resources: android.content.res.Resources? = null,
) {
    private fun formatter(skeleton: String) =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private val monthTitle = formatter("MMMMyyyy")
    private val monthShort = formatter("MMMyyyy")
    private val monthOfYear = formatter("LLL")
    private val day = formatter("EEEEdMMMM")
    private val titles = HashMap<Int, String>()
    private val shorts = HashMap<Int, String>()
    private val days = HashMap<Long, String>()
    private val counts = HashMap<Int, String>()
    private val numbers = NumberFormat.getIntegerInstance(locale)

    private fun capitalised(text: String) = text.replaceFirstChar { it.titlecase(locale) }

    private fun start(month: Int): Date = Date(
        PhotoQuerySql.dayStart(LocalDate.of(DateIndex.year(month), DateIndex.monthOfYear(month), 1)),
    )

    /** "November 2026" / "Novembro de 2026"; "No date" for [DateIndex.NO_DATE]. */
    fun monthTitle(month: Int): String = if (month == DateIndex.NO_DATE) {
        noDate
    } else {
        titles.getOrPut(month) { capitalised(monthTitle.format(start(month))) }
    }

    /** "Nov 2026" / "Nov. de 2026" for the scroller's bubble. */
    fun monthShort(month: Int): String = if (month == DateIndex.NO_DATE) {
        noDate
    } else {
        shorts.getOrPut(month) { capitalised(monthShort.format(start(month))) }
    }

    /** The month's own short name ("Nov", "Nov."), for the month cells of "Go to date". */
    fun monthOfYear(month: Int): String = capitalised(monthOfYear.format(start(month)))

    /** A day sub-header: "Sunday, November 15" / "domingo, 15 de novembro". */
    fun day(time: Long): String = days.getOrPut(Math.floorDiv(time, DAY_MS)) { capitalised(day.format(Date(time))) }

    /** "1 photo" / "1,204 photos": one lookup per distinct count, never a plural + number format per header. */
    fun photoCount(count: Int): String = counts.getOrPut(count) {
        val formatted = numbers.format(count)
        resources?.getQuantityString(R.plurals.library_photo_count, count, formatted) ?: formatted
    }
}

private const val DAY_MS = 86_400_000L

private fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

@Composable
fun rememberDateLabels(): DateLabels {
    val locale = LocalConfiguration.current.locales[0]
    val noDate = stringResource(R.string.library_no_date)
    val resources = LocalResources.current
    return remember(locale, noDate) { DateLabels(locale, noDate, resources) }
}

/**
 * A month's header (W3/D): the month in sentence case and its photo count. The sticky copy over the grid draws the
 * same row, so the two line up exactly.
 */
@Composable
internal fun MonthHeader(title: String, countText: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RevealTheme.colors.card)
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            style = RevealTheme.typography.uiMedium,
            color = RevealTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            countText,
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A month header inside the grid: a heading for TalkBack, tagged by month for tests. */
@Composable
internal fun GridMonthHeader(entry: GridEntry.Month, labels: DateLabels) {
    MonthHeader(
        labels.monthTitle(entry.month),
        labels.photoCount(entry.count),
        Modifier
            .semantics { heading() }
            .testTag(LibraryTags.monthHeader(entry.month)),
    )
}

/**
 * The month of the grid's first visible row, pinned over the grid's top (W3/D); the next month's header pushes it up
 * as it arrives. The month changes only when the first visible item crosses a month (a derived state), and the push is
 * read in the draw phase: scrolling never recomposes it. A tap opens "Go to date".
 */
@Composable
internal fun StickyMonthHeader(
    state: LazyGridState,
    index: DateIndex,
    labels: DateLabels,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position by remember(index) { derivedStateOf { index.positionAt(state.firstVisibleItemIndex) } }
    val at = position
    if (at < 0) return
    var height by remember { mutableIntStateOf(0) }
    val month = index.months[at]
    val title = labels.monthTitle(month)
    val description = stringResource(R.string.library_go_to_date)
    val showing = stringResource(R.string.library_go_to_date_current, title)
    MonthHeader(
        title = title,
        countText = labels.photoCount(index.counts[at]),
        modifier = modifier
            .testTag(LibraryTags.STICKY_MONTH)
            .onSizeChanged { height = it.height }
            .graphicsLayer { translationY = pushUp(state, index, at, height) }
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = showing
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            },
    )
}

/** How far the next month's header pushes the sticky one up: 0 until it reaches the sticky header's bottom. */
@Suppress("ReturnCount")
private fun pushUp(state: LazyGridState, index: DateIndex, position: Int, height: Int): Float {
    val next = position + 1
    if (next >= index.size) return 0f
    val start = index.starts[next]
    val items = state.layoutInfo.visibleItemsInfo
    for (i in items.indices) {
        val item = items[i]
        if (item.index == start) {
            val y = item.offset.y
            return if (y < height) (y - height).toFloat() else 0f
        }
        if (item.index > start) break
    }
    return 0f
}

/**
 * What the fast scroller shares with the grid's container: a pen or a mouse hovering near the grid's end edge shows
 * it (stylus-friendly on the tablet). Filled by [Modifier.dateScrollerHover] on the container.
 */
@Stable
class DateScrollerState {
    internal var hovering by mutableStateOf(false)
}

/**
 * Watches stylus and mouse hover over the grid's container (the initial pass: nothing is consumed, the grid scrolls
 * and taps as before): within [edge] of the end edge the scroller shows.
 */
internal fun Modifier.dateScrollerHover(scroller: DateScrollerState, edgePx: Float): Modifier = pointerInput(edgePx) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            val hoverType = change.type == PointerType.Stylus || change.type == PointerType.Mouse
            scroller.hovering = when {
                !hoverType || event.type == PointerEventType.Exit -> false

                event.type == PointerEventType.Enter || event.type == PointerEventType.Move ->
                    !change.pressed && change.position.x >= size.width - edgePx

                else -> scroller.hovering
            }
        }
    }
}

private const val HIDE_DELAY_MS = 1_500L
private const val NANOS_PER_MICRO = 1_000L
private const val MIN_SCREENS = 3
private val THUMB_TOUCH_WIDTH = 48.dp
private val THUMB_TOUCH_HEIGHT = 56.dp
private val THUMB_WIDTH = 6.dp
private val THUMB_WIDTH_ACTIVE = 10.dp
private val THUMB_HEIGHT = 44.dp

/**
 * The grid's fast scroller (W3/D): a thumb on the end edge, shown while the grid scrolls (and while a pen hovers near
 * the edge), that jumps straight to any point when dragged, with a bubble naming the month (or [labelAt]'s label) under
 * the finger. The grid holds every row in memory, so a jump is one `scrollToItem`: no page in between is loaded.
 * TalkBack reads it as an adjustable control ("Scroll by date", the current month) with previous/next month actions.
 * Shown only when the grid is at least [MIN_SCREENS] screens long.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
@Composable
internal fun DateScroller(
    state: LazyGridState,
    index: DateIndex?,
    labelAt: (Int) -> String?,
    scroller: DateScrollerState,
    modifier: Modifier = Modifier,
) {
    val long by remember {
        derivedStateOf {
            val info = state.layoutInfo
            info.visibleItemsInfo.isNotEmpty() && info.totalItemsCount > info.visibleItemsInfo.size * MIN_SCREENS
        }
    }
    if (!long) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    // Shown when the first visible item changes (a scroll, a fling, a jump), while dragged or hovered; hidden a moment
    // after the last of them. Keyed on the item, not the pixel offset: no work per frame inside one row.
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.firstVisibleItemIndex, dragging, scroller.hovering) }.drop(1)
            .collectLatest { (_, held, hovered) ->
                visible = true
                if (!held && !hovered) {
                    delay(HIDE_DELAY_MS)
                    visible = false
                }
            }
    }
    // The fade is read in the draw phase: animating it must not recompose the scroller on every frame.
    val alpha = animateFloatAsState(if (visible) 1f else 0f, label = "scroller")
    val currentLabelAt by rememberUpdatedState(labelAt)

    fun maxFirst(): Int {
        val info = state.layoutInfo
        return (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
    }

    fun fraction(): Float = (state.firstVisibleItemIndex.toFloat() / maxFirst()).coerceIn(0f, 1f)

    fun jump(f: Float) {
        val to = (f.coerceIn(0f, 1f) * maxFirst()).roundToInt()
        if (to == target && dragging) return
        target = to
        val start = System.nanoTime()
        scope.launch {
            state.scrollToItem(to)
            withFrameNanos { }
            LibraryMetrics.jump((System.nanoTime() - start) / NANOS_PER_MICRO)
        }
    }

    // TalkBack: the month shown, its place among the months, previous/next month.
    val position by remember(index) {
        derivedStateOf { index?.positionAt(state.firstVisibleItemIndex) ?: -1 }
    }
    val shownLabel by remember { derivedStateOf { currentLabelAt(state.firstVisibleItemIndex) } }
    val bubble = if (dragging) labelAt(target) else shownLabel
    val description = stringResource(R.string.library_scroller)
    val previous = stringResource(R.string.library_scroller_previous)
    val next = stringResource(R.string.library_scroller_next)
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned {
            val bounds = it.boundsInWindow()
            LibraryMetrics.scrollerTrack(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt(),
            )
        },
    ) {
        val trackPx = with(density) { (maxHeight - THUMB_TOUCH_HEIGHT).toPx() }.coerceAtLeast(1f)
        val thumbY = { if (dragging) dragFraction * trackPx else fraction() * trackPx }
        if (dragging || scroller.hovering) {
            bubble?.let { label ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbY().roundToInt()) }
                        .padding(end = THUMB_TOUCH_WIDTH + 4.dp, top = 8.dp)
                        .clip(RevealTheme.shapes.control)
                        .background(RevealTheme.colors.foreground)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag(LibraryTags.SCROLLER_BUBBLE),
                ) {
                    Text(
                        label,
                        style = RevealTheme.typography.uiMedium,
                        color = RevealTheme.colors.background,
                        maxLines = 1,
                    )
                }
            }
        }
        val thumbWidth = if (dragging || scroller.hovering) THUMB_WIDTH_ACTIVE else THUMB_WIDTH
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY().roundToInt()) }
                .size(THUMB_TOUCH_WIDTH, THUMB_TOUCH_HEIGHT)
                .semantics {
                    contentDescription = description
                    bubble?.let { stateDescription = it }
                    val months = index?.size ?: 0
                    if (months > 1 && position >= 0) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            position.toFloat() / (months - 1),
                            0f..1f,
                            steps = (months - 2).coerceAtLeast(0),
                        )
                    }
                    setProgress { value ->
                        val i = index
                        if (i != null && i.size > 0) {
                            val at = (value * (i.size - 1)).roundToInt().coerceIn(0, i.size - 1)
                            scope.launch { state.scrollToItem(i.starts[at]) }
                        } else {
                            jump(value)
                        }
                        true
                    }
                    if (index != null) {
                        customActions = listOf(
                            CustomAccessibilityAction(previous) {
                                val at = position - 1
                                if (at >= 0) scope.launch { state.scrollToItem(index.starts[at]) }
                                at >= 0
                            },
                            CustomAccessibilityAction(next) {
                                val at = position + 1
                                if (at < index.size) scope.launch { state.scrollToItem(index.starts[at]) }
                                at < index.size
                            },
                        )
                    }
                }
                .then(
                    if (visible) {
                        Modifier.pointerInput(trackPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                val grab = down.position.y
                                val startY = fraction() * trackPx
                                dragFraction = startY / trackPx
                                target = state.firstVisibleItemIndex
                                dragging = true
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val y = startY + (change.position.y - grab)
                                        dragFraction = (y / trackPx).coerceIn(0f, 1f)
                                        jump(dragFraction)
                                    }
                                } finally {
                                    dragging = false
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .testTag(LibraryTags.SCROLLER),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .graphicsLayer { this.alpha = alpha.value }
                    .size(thumbWidth, THUMB_HEIGHT)
                    .clip(CircleShape)
                    .background(if (dragging) RevealTheme.colors.primary else RevealTheme.colors.mutedForeground),
            )
        }
    }
}

// ---------------------------------------------------------------------- go to date

/** "Go to date" (W3/D): a centred dialog on wide windows, a bottom sheet on phones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoToDateSheet(
    index: DateIndex,
    current: Int?,
    labels: DateLabels,
    onPick: (month: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    RevealBottomSheet(onDismissRequest = onDismiss) {
        GoToDateContent(index, current, labels, onPick)
    }
}

/**
 * The years of the grid, newest first, each with its twelve months and their counts (months without photos greyed,
 * not tappable) and "No date" when some photos have none. Opens at the year shown in the grid ([current]).
 */
@Composable
fun GoToDateContent(
    index: DateIndex,
    current: Int?,
    labels: DateLabels,
    onPick: (month: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val years = remember(index) {
        index.months.filter { it != DateIndex.NO_DATE }.map { DateIndex.year(it) }.distinct().sortedDescending()
    }
    val totals = remember(index) {
        val out = HashMap<Int, Int>()
        for (i in 0 until index.size) {
            val m = index.months[i]
            if (m != DateIndex.NO_DATE) out[DateIndex.year(m)] = (out[DateIndex.year(m)] ?: 0) + index.counts[i]
        }
        out
    }
    val noDate = index.countOf(DateIndex.NO_DATE)
    val startYear = current?.takeIf { it != DateIndex.NO_DATE }?.let { years.indexOf(DateIndex.year(it)) } ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startYear.coerceAtLeast(0))
    Column(modifier.fillMaxWidth().testTag(LibraryTags.GO_TO_DATE_SHEET)) {
        Text(
            stringResource(R.string.library_go_to_date),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(years, key = { it }) { year ->
                YearMonths(year, totals[year] ?: 0, index, current, labels, onPick)
            }
            if (noDate > 0) {
                item(key = "nodate") {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        RevealChip(
                            label = stringResource(
                                R.string.library_filter_with_count,
                                labels.monthTitle(DateIndex.NO_DATE),
                                formatCount(noDate),
                            ),
                            selected = current == DateIndex.NO_DATE,
                            onClick = { onPick(DateIndex.NO_DATE) },
                            modifier = Modifier.testTag(LibraryTags.month(DateIndex.NO_DATE)),
                        )
                    }
                }
            }
        }
    }
}

private const val MONTHS_PER_ROW = 4
private const val MONTHS_IN_YEAR = 12
private const val EMPTY_ALPHA = 0.38f

@Composable
private fun YearMonths(
    year: Int,
    total: Int,
    index: DateIndex,
    current: Int?,
    labels: DateLabels,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Eyebrow(year.toString(), Modifier.weight(1f).semantics { heading() })
            Text(
                pluralStringResource(R.plurals.library_photo_count, total, formatCount(total)),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.mutedForeground,
            )
        }
        for (row in 0 until MONTHS_IN_YEAR / MONTHS_PER_ROW) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until MONTHS_PER_ROW) {
                    val month = DateIndex.of(year, row * MONTHS_PER_ROW + col + 1)
                    MonthCell(
                        month = month,
                        count = index.countOf(month),
                        current = month == current,
                        labels = labels,
                        onPick = onPick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(Modifier.size(6.dp))
        }
    }
}

@Composable
private fun MonthCell(
    month: Int,
    count: Int,
    current: Boolean,
    labels: DateLabels,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    val enabled = count > 0
    val title = labels.monthTitle(month)
    val description = if (enabled) {
        stringResource(
            R.string.library_month_cell,
            title,
            pluralStringResource(R.plurals.library_photo_count, count, formatCount(count)),
        )
    } else {
        stringResource(R.string.library_month_empty, title)
    }
    Column(
        modifier = modifier
            .testTag(LibraryTags.month(month))
            .heightIn(min = 52.dp)
            .clip(RevealTheme.shapes.control)
            .background(if (current) colors.accent else colors.card)
            .border(1.dp, if (current) colors.primary else colors.border, RevealTheme.shapes.control)
            .clickable(enabled = enabled) { onPick(month) }
            .alpha(if (enabled) 1f else EMPTY_ALPHA)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
                if (enabled) {
                    onClick {
                        onPick(month)
                        true
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            labels.monthOfYear(month),
            style = RevealTheme.typography.uiMedium,
            color = if (current) colors.accentForeground else colors.foreground,
            maxLines = 1,
        )
        Text(
            if (enabled) formatCount(count) else "–",
            style = RevealTheme.typography.ui,
            color = colors.mutedForeground,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------- custom date range

/** A custom range's label in the device's language: "Jan 1 – Mar 31, 2024" / "1 de jan. – 31 de mar. de 2024". */
@Composable
internal fun rangeLabel(range: DateFilter.Range): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(range, locale) {
        DateUtils.formatDateRange(
            context,
            Formatter(StringBuilder(), locale),
            PhotoQuerySql.dayStart(range.from),
            PhotoQuerySql.dayStart(range.to.plusDays(1)),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
            "UTC",
        ).toString()
    }
}

/** The Date filter's custom range (W3/D): a date range picker over the filter sheet; Apply adds the range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeSheet(onApply: (DateFilter.Range) -> Unit, onDismiss: () -> Unit) {
    RevealBottomSheet(onDismissRequest = onDismiss) {
        val picker = rememberDateRangePickerState()
        val colors = RevealTheme.colors
        Column(Modifier.fillMaxWidth().testTag(LibraryTags.DATE_RANGE_SHEET)) {
            DateRangePicker(
                state = picker,
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 520.dp),
                title = {
                    Text(
                        stringResource(R.string.library_date_range_title),
                        style = RevealTheme.typography.title,
                        color = colors.foreground,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp),
                    )
                },
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = colors.card,
                    titleContentColor = colors.foreground,
                    headlineContentColor = colors.foreground,
                    weekdayContentColor = colors.mutedForeground,
                    dayContentColor = colors.foreground,
                    selectedDayContainerColor = colors.primary,
                    selectedDayContentColor = colors.primaryForeground,
                    todayContentColor = colors.primary,
                    todayDateBorderColor = colors.primary,
                    dayInSelectionRangeContainerColor = colors.accent,
                    dayInSelectionRangeContentColor = colors.accentForeground,
                    dividerColor = colors.hairline,
                ),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                GhostButton(stringResource(R.string.library_cancel), onDismiss)
                val start = picker.selectedStartDateMillis
                PrimaryButton(
                    text = stringResource(R.string.library_date_range_apply),
                    enabled = start != null,
                    onClick = {
                        val from = utcDay(start ?: return@PrimaryButton)
                        val to = picker.selectedEndDateMillis?.let(::utcDay) ?: from
                        onApply(DateFilter.Range(minOf(from, to), maxOf(from, to)))
                    },
                    modifier = Modifier.testTag(LibraryTags.DATE_RANGE_APPLY),
                )
            }
        }
    }
}

/** The picker's dates are UTC midnights: the same wall-clock day as the grid's dates. */
private fun utcDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
