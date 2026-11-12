package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.HistogramColors
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.canvas.HoverTooltipLayer
import dev.hfps.reveal.feature.editor.canvas.penHover
import dev.hfps.reveal.feature.editor.canvas.rememberPenHover
import dev.hfps.reveal.feature.editor.edit.CurveEditing
import dev.hfps.reveal.pipeline.cpu.ParametricCurveShape
import dev.hfps.reveal.pipeline.gl.Histogram
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the curve editor edits: the point curve of a channel, or the parametric curve. */
sealed interface CurveTarget {
    data class Points(val channel: CurveChannel) : CurveTarget

    data object Parametric : CurveTarget
}

/** The curve editor's callbacks: per-frame changes, the commit on release and the resets. */
class CurveCallbacks(
    /** Per frame: the edited point curve (not committed). */
    val onPoints: (List<CurvePoint>) -> Unit,
    /** Per frame in parametric mode: a region slider (index 0 shadows .. 3 highlights) moved to a value. */
    val onRegion: (Int, Float) -> Unit,
    /** Release, tap-to-add, accessibility actions: one committed step. */
    val onCommit: () -> Unit,
    /** Double-tap: the edited curve back to a straight line (one step). */
    val onReset: () -> Unit,
    /** The selected point moved (for the readout): index or null. */
    val onSelect: (Int?) -> Unit = {},
)

/**
 * The square curve editor (docs/plan/ux.md "Curves"): the histogram of the displayed photo behind the curve, quarter
 * grid, the identity diagonal. Point mode: tap to add a point on the curve, drag a point (or drag anywhere to add one
 * there and move it) with the finger's movement, drag it off the square to remove it, double-tap to reset the channel.
 * Parametric mode: drag up or down in a region to move that region's slider. Every point is also an accessibility
 * node (input/output values, nudge and delete actions).
 */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
fun CurveEditor(
    target: CurveTarget,
    curves: Curves,
    histogram: Histogram?,
    callbacks: CurveCallbacks,
    side: Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    readout: String? = null,
) {
    val colors = RevealTheme.colors
    val points = if (target is CurveTarget.Points) CurveEditing.shown(curves[target.channel]) else emptyList()
    val currentPoints by rememberUpdatedState(points)
    val currentCurves by rememberUpdatedState(curves)
    val currentCallbacks by rememberUpdatedState(callbacks)
    var active by remember { mutableStateOf<Int?>(null) }
    var removing by remember { mutableStateOf(false) }
    val hover = rememberPenHover()
    val viewConfiguration = LocalViewConfiguration.current
    val channelColour = when ((target as? CurveTarget.Points)?.channel) {
        CurveChannel.RED -> HistogramColors.Red
        CurveChannel.GREEN -> HistogramColors.Green
        CurveChannel.BLUE -> HistogramColors.Blue
        else -> colors.foreground
    }
    val description = stringResource(
        if (target is CurveTarget.Points) {
            R.string.editor_curve_points_description
        } else {
            R.string.editor_curve_parametric_description
        },
    )
    val addLabel = stringResource(R.string.editor_curve_add_point)
    val resetLabel = stringResource(R.string.editor_curve_reset)
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(target, side) {
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val offMargin = OFF_MARGIN.toPx() / w
            fun toCurve(p: Offset) = Offset(p.x / w, 1f - p.y / h)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                // The pen is precise: a smaller grab radius, so it can pick one of two close points.
                val grab = (if (down.type == PointerType.Stylus) PEN_GRAB else GRAB).toPx()
                hover.at = null
                val start = toCurve(down.position)
                val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (drag == null) {
                    // A tap: wait for a second one (double-tap resets), otherwise add a point where it landed.
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = false).also { it.consume() }
                    }
                    if (second != null) {
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        currentCallbacks.onReset()
                    } else if (target is CurveTarget.Points) {
                        CurveEditing.add(currentPoints, start.x)?.let { (next, index) ->
                            active = index
                            currentCallbacks.onSelect(index)
                            currentCallbacks.onPoints(next)
                            currentCallbacks.onCommit()
                        }
                    }
                    return@awaitEachGesture
                }
                if (target is CurveTarget.Parametric) {
                    val splits = ParametricCurveShape.splits(currentCurves.parametric)
                    val region = splits.count { start.x >= it }
                    val p = currentCurves.parametric
                    val from = listOf(p.shadows, p.darks, p.lights, p.highlights)[region]
                    var change: PointerInputChange = drag
                    while (change.pressed) {
                        val dy = (down.position.y - change.position.y) / h
                        currentCallbacks.onRegion(
                            region,
                            (from + dy * PARAMETRIC_PER_HEIGHT).coerceIn(-REGION_MAX, REGION_MAX),
                        )
                        change.consume()
                        change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    }
                    currentCallbacks.onCommit()
                    return@awaitEachGesture
                }
                var list = currentPoints
                val hit = CurveEditing.nearest(list, start.x, start.y, grab / w, grab / h)
                val index = hit ?: CurveEditing.add(list, start.x)?.let { (next, i) ->
                    list = next
                    i
                } ?: return@awaitEachGesture
                val origin = list[index]
                active = index
                currentCallbacks.onSelect(index)
                var change: PointerInputChange = drag
                var edited = list
                while (change.pressed) {
                    val dx = (change.position.x - down.position.x) / w
                    val dy = -(change.position.y - down.position.y) / h
                    val x = origin.x + dx
                    val y = origin.y + dy
                    removing = CurveEditing.removable(list, index) && CurveEditing.draggedOff(x, y, offMargin)
                    edited = if (removing) list else CurveEditing.move(list, index, x, y)
                    currentCallbacks.onPoints(edited)
                    change.consume()
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                }
                if (removing) {
                    currentCallbacks.onPoints(CurveEditing.remove(list, index))
                    active = null
                    currentCallbacks.onSelect(null)
                }
                removing = false
                currentCallbacks.onCommit()
            }
        }
    }
    Box(
        modifier
            .size(side)
            .border(BorderStroke(1.dp, colors.hairline), RevealTheme.shapes.control)
            .testTag(EditorTags.CURVE_EDITOR)
            .semantics {
                contentDescription = description
                customActions = buildList {
                    if (target is CurveTarget.Points && enabled) {
                        add(
                            CustomAccessibilityAction(addLabel) {
                                val x = CurveEditing.widestGapMiddle(currentPoints)
                                CurveEditing.add(currentPoints, x)?.let { (next, _) ->
                                    currentCallbacks.onPoints(next)
                                    currentCallbacks.onCommit()
                                    true
                                } ?: false
                            },
                        )
                    }
                    if (enabled) add(CustomAccessibilityAction(resetLabel) { currentCallbacks.onReset().let { true } })
                }
            },
    ) {
        val hoverAt = hover.at.takeIf { enabled }
        val hovered = hoverAt?.let { at ->
            with(LocalDensity.current) {
                val px = side.toPx()
                val r = PEN_GRAB.toPx() / px
                CurveEditing.nearest(points, at.x / px, 1f - at.y / px, r, r)
            }
        }
        Canvas(
            Modifier
                .size(side)
                .penHover(hover)
                .then(gestures),
        ) {
            drawGrid(colors.hairline)
            if (histogram != null) drawCurveHistogram(histogram, (target as? CurveTarget.Points)?.channel)
            drawLine(
                colors.mutedForeground.copy(alpha = DIAGONAL_ALPHA),
                Offset(0f, size.height),
                Offset(size.width, 0f),
            )
            when (target) {
                is CurveTarget.Parametric -> {
                    val splits = ParametricCurveShape.splits(curves.parametric)
                    splits.forEach {
                        val x = it.toFloat() * size.width
                        drawLine(
                            colors.mutedForeground,
                            Offset(x, 0f),
                            Offset(x, size.height),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, DASH)),
                        )
                    }
                    drawCurve(colors.foreground) { CurveEditing.parametricAt(curves.parametric, it) }
                }

                is CurveTarget.Points -> {
                    if (target.channel == CurveChannel.RGB) {
                        // The channel curves faintly behind the RGB curve, so their effect stays visible.
                        listOf(
                            CurveChannel.RED to HistogramColors.Red,
                            CurveChannel.GREEN to HistogramColors.Green,
                            CurveChannel.BLUE to HistogramColors.Blue,
                        ).forEach { (ch, c) ->
                            if (!Curves.isLinear(curves[ch])) {
                                drawCurve(c.copy(alpha = OTHER_ALPHA)) { CurveEditing.valueAt(curves[ch], it) }
                            }
                        }
                    }
                    drawCurve(channelColour) { CurveEditing.valueAt(points, it) }
                    // The pen hovering: a ring on the point it would take, or a ghost where a tap would add one.
                    if (hoverAt != null) {
                        val ring = hovered?.let { points[it] }
                        val gx = ring?.x ?: (hoverAt.x / size.width).coerceIn(0f, 1f)
                        val gy = ring?.y ?: CurveEditing.valueAt(points, gx).coerceIn(0f, 1f)
                        val c = Offset(gx * size.width, (1f - gy) * size.height)
                        drawCircle(colors.primary, HOVER_RADIUS.toPx(), c, style = Stroke(STROKE.toPx()))
                    }
                    points.forEachIndexed { i, p ->
                        val centre = Offset(p.x * size.width, (1f - p.y) * size.height)
                        val selected = i == active
                        val colour = if (selected && removing) colors.destructive else channelColour
                        drawCircle(colors.card, POINT_RADIUS.toPx(), centre)
                        if (selected) {
                            drawCircle(colour, POINT_RADIUS.toPx(), centre)
                        } else {
                            drawCircle(colour, POINT_RADIUS.toPx(), centre, style = Stroke(STROKE.toPx()))
                        }
                    }
                }
            }
        }
        if (target is CurveTarget.Points) {
            points.forEachIndexed { i, p -> PointNode(i, p, points, side, enabled, callbacks) }
        }
        if (hoverAt != null) {
            val tip = hoverReadout(target, curves, points, hovered, hoverAt, side)
            HoverTooltipLayer(tip, hoverAt, Modifier.matchParentSize())
        }
        if (readout != null) {
            Text(
                text = readout,
                style = RevealTheme.typography.value,
                color = colors.foreground,
                modifier = Modifier
                    .padding(8.dp)
                    .background(colors.card.copy(alpha = READOUT_ALPHA), RevealTheme.shapes.control)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

private const val READOUT_ALPHA = 0.85f

/** The hover tooltip: the hovered point's levels, else the curve's value under the pen. */
@Composable
private fun hoverReadout(
    target: CurveTarget,
    curves: Curves,
    points: List<CurvePoint>,
    hovered: Int?,
    at: Offset,
    side: Dp,
): String {
    val px = with(LocalDensity.current) { side.toPx() }
    val x = (at.x / px).coerceIn(0f, 1f)
    val (inX, outY) = when {
        hovered != null -> points[hovered].x to points[hovered].y
        target is CurveTarget.Parametric -> x to CurveEditing.parametricAt(curves.parametric, x)
        else -> x to CurveEditing.valueAt(points, x)
    }
    return stringResource(R.string.editor_hover_curve_point, CurveEditing.level(inX), CurveEditing.level(outY))
}

/** The accessibility node of point [index]: values, nudges, delete; it does not take touches. */
@Composable
private fun PointNode(
    index: Int,
    point: CurvePoint,
    points: List<CurvePoint>,
    side: Dp,
    enabled: Boolean,
    callbacks: CurveCallbacks,
) {
    val name = stringResource(R.string.editor_curve_point, index + 1)
    val state = stringResource(R.string.editor_curve_readout, CurveEditing.level(point.x), CurveEditing.level(point.y))
    val up = stringResource(R.string.editor_curve_point_up)
    val down = stringResource(R.string.editor_curve_point_down)
    val left = stringResource(R.string.editor_curve_point_left)
    val right = stringResource(R.string.editor_curve_point_right)
    val delete = stringResource(R.string.editor_curve_point_delete)
    val currentPoints by rememberUpdatedState(points)
    fun nudge(dx: Float, dy: Float): Boolean {
        val p = currentPoints[index]
        callbacks.onPoints(CurveEditing.move(currentPoints, index, p.x + dx, p.y + dy))
        callbacks.onCommit()
        return true
    }
    val node = NODE
    Box(
        Modifier
            .offset {
                val px = side.toPx()
                IntOffset(
                    (point.x * px - node.toPx() / 2).roundToInt(),
                    ((1f - point.y) * px - node.toPx() / 2).roundToInt(),
                )
            }
            .size(node)
            .testTag(EditorTags.curvePoint(index))
            .semantics {
                contentDescription = name
                stateDescription = state
                if (enabled) {
                    customActions = buildList {
                        add(CustomAccessibilityAction(up) { nudge(0f, CurveEditing.NUDGE) })
                        add(CustomAccessibilityAction(down) { nudge(0f, -CurveEditing.NUDGE) })
                        add(CustomAccessibilityAction(left) { nudge(-CurveEditing.NUDGE, 0f) })
                        add(CustomAccessibilityAction(right) { nudge(CurveEditing.NUDGE, 0f) })
                        if (CurveEditing.removable(points, index)) {
                            add(
                                CustomAccessibilityAction(delete) {
                                    callbacks.onPoints(CurveEditing.remove(currentPoints, index))
                                    callbacks.onCommit()
                                    true
                                },
                            )
                        }
                    }
                }
            },
    )
}

private fun DrawScope.drawGrid(colour: Color) {
    for (i in 1 until GRID) {
        val x = size.width * i / GRID
        val y = size.height * i / GRID
        drawLine(colour, Offset(x, 0f), Offset(x, size.height))
        drawLine(colour, Offset(0f, y), Offset(size.width, y))
    }
}

/** The displayed photo's histogram behind the curve: luminance for RGB and the parametric curve, else the channel. */
private fun DrawScope.drawCurveHistogram(h: Histogram, channel: CurveChannel?) {
    val (bins, colour) = when (channel) {
        CurveChannel.RED -> h.red to HistogramColors.Red
        CurveChannel.GREEN -> h.green to HistogramColors.Green
        CurveChannel.BLUE -> h.blue to HistogramColors.Blue
        else -> h.luma to HistogramColors.Luma
    }
    val peak = h.peak.toFloat()
    val path = Path()
    val dx = size.width / (Histogram.BINS - 1)
    path.moveTo(0f, size.height)
    for (i in 0 until Histogram.BINS) {
        path.lineTo(i * dx, size.height - size.height * (bins[i] / peak).coerceAtMost(1f))
    }
    path.lineTo(size.width, size.height)
    path.close()
    drawPath(path, colour.copy(alpha = HISTOGRAM_ALPHA), blendMode = BlendMode.SrcOver)
}

private inline fun DrawScope.drawCurve(colour: Color, crossinline f: (Float) -> Float) {
    val path = Path()
    val steps = CURVE_STEPS
    for (i in 0..steps) {
        val x = i / steps.toFloat()
        val y = f(x).coerceIn(0f, 1f)
        val p = Offset(x * size.width, (1f - y) * size.height)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, colour, style = Stroke(STROKE.toPx()))
}

/** Quarter grid. */
private const val GRID = 4

/** The region sliders' range. */
private const val REGION_MAX = 100f

/** Slider units per editor height of a vertical drag in parametric mode. */
private const val PARAMETRIC_PER_HEIGHT = 200f
private const val CURVE_STEPS = 128
private const val DIAGONAL_ALPHA = 0.35f
private const val OTHER_ALPHA = 0.55f
private const val HISTOGRAM_ALPHA = 0.3f
private const val DASH = 6f
private val POINT_RADIUS = 6.dp
private val STROKE = 2.dp
private val GRAB = 24.dp

/** The pen's grab radius (hover ring and drag): half the finger's. */
private val PEN_GRAB = 12.dp
private val HOVER_RADIUS = 10.dp
private val OFF_MARGIN = 32.dp
private val NODE = 24.dp

/** True when [a] and [b] are within half a level (the presets row's "selected"). */
internal fun samePoints(a: List<CurvePoint>, b: List<CurvePoint>): Boolean =
    a.size == b.size && a.zip(b).all { (p, q) -> abs(p.x - q.x) < HALF_LEVEL && abs(p.y - q.y) < HALF_LEVEL }

private const val HALF_LEVEL = 0.5f / 255f
