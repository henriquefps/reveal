@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements")

package dev.hfps.reveal.feature.editor.crop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.canvas.HoverTooltipLayer
import dev.hfps.reveal.feature.editor.canvas.penHover
import dev.hfps.reveal.feature.editor.canvas.rememberPenHover
import dev.hfps.reveal.feature.editor.tools.ModeHost
import dev.hfps.reveal.pipeline.geometry.Box
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.geometry.CropHandle
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GuideLine
import dev.hfps.reveal.pipeline.recipe.UprightMode
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The Crop mode's overlay over the photo (docs/plan/ux.md, product.md "Geometry"): the whole image straightened and
 * oriented (the renderer's editing plane) with the crop frame centred and as large as the slot allows, the outside
 * dimmed, handles on the corners and edges, a rule-of-thirds grid while dragging and a finer grid while
 * straightening.
 *
 * Gestures: a handle drags that edge/corner (the photo holds still, then the view eases to the new frame); one finger
 * elsewhere drags the photo under the frame; two fingers pinch to zoom inside the crop; with the horizon tool armed,
 * a drag draws a line that becomes level (or plumb); with Guided Upright armed, a drag draws a guide (with the loupe
 * magnifying under the finger) and the guides drawn so far show on the photo. Every frame of a gesture changes the
 * working geometry only; Done commits it.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun BoxScope.CropOverlay(host: ModeHost, modifier: Modifier = Modifier) {
    val ready = host.state.phase as? EditorPhase.Ready ?: return
    val session = host.crop
    val geometry = host.state.edit.recipe.geometry
    val width = ready.width
    val height = ready.height
    val density = LocalDensity.current
    val margin = with(density) { FRAME_MARGIN.toPx() }
    var slot by remember { mutableStateOf(IntSize.Zero) }
    val frame = CropEditing.frame(geometry, width, height)
    val (planeW, planeH) = CropEditing.planeSize(geometry, width, height)
    val maxScale = maxOf(
        ready.hundredPercentScale * MAX_ZOOM,
        minOf(slot.width.toFloat() / planeW, slot.height.toFloat() / planeH),
    )
    val target = CropView.fit(frame, slot.width.toFloat(), slot.height.toFloat(), margin, maxScale)
    val progress = remember { Animatable(1f) }
    val released = session.releasedView
    val shown = session.frozenView ?: released?.let { CropView.lerp(it, target, progress.value) } ?: target
    if (slot != IntSize.Zero) SideEffect { host.photo.showCropView(shown) }

    val scope = rememberCoroutineScope()
    val currentGeometry by rememberUpdatedState(geometry)
    val currentView by rememberUpdatedState(shown)
    val onGeometry by rememberUpdatedState(host.actions.onGeometry)
    val onGuide by rememberUpdatedState(host.actions.onGuide)
    val description = stringResource(R.string.editor_crop_frame)
    val colors = RevealTheme.colors
    val scrim = colors.background.copy(alpha = SCRIM_ALPHA)
    val line = colors.foreground
    val accent = colors.primary

    val hover = rememberPenHover()
    val penLoupe = Modifier.penHover(session) { at ->
        hover.at = at
        // Guided Upright with a pen: the loupe follows the hovering tip, so the guide starts exactly where it lands.
        if (session.guideTool) {
            val size = with(density) { LOUPE_SIZE.toPx() }
            val lift = with(density) { LOUPE_LIFT.toPx() }
            host.photo.showCropLoupe(
                at,
                at?.let { Offset(it.x, (it.y - lift).coerceAtLeast(size / 2f)) } ?: Offset.Zero,
                size,
                currentView,
            )
        }
    }

    val gestures = Modifier.pointerInput(width, height) {
        val minFrame = MIN_FRAME.toPx()
        val minLine = MIN_HORIZON.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            hover.at = null
            // The pen is precise: a smaller handle radius, so it can grab an edge next to a corner.
            val handleRadius = (if (down.type == PointerType.Stylus) PEN_HANDLE_TOUCH else HANDLE_TOUCH).toPx()
            val g0 = currentGeometry
            val view0 = currentView
            val frame0 = CropEditing.frame(g0, width, height)
            val slotW = size.width.toFloat()
            val slotH = size.height.toFloat()
            val handle = if (session.horizonTool || session.guideTool) {
                null
            } else {
                hitHandle(
                    frame0,
                    view0,
                    down.position,
                    slotW,
                    slotH,
                    handleRadius,
                )
            }
            when {
                session.guideTool -> {
                    session.grid = CropGrid.FINE
                    val loupeSize = LOUPE_SIZE.toPx()
                    val lift = LOUPE_LIFT.toPx()
                    fun loupe(p: Offset?) = host.photo.showCropLoupe(
                        p,
                        p?.let { Offset(it.x, (it.y - lift).coerceAtLeast(loupeSize / 2f)) } ?: Offset.Zero,
                        loupeSize,
                        view0,
                    )
                    loupe(down.position)
                    val end = trackFinger(down.id) {
                        session.guideLine = down.position to it
                        loupe(it)
                    }
                    loupe(null)
                    session.guideLine = null
                    session.grid = CropGrid.NONE
                    if (end != null && (end - down.position).getDistance() >= minLine) {
                        val a = Guides.toGuide(g0, view0, down.position, slotW, slotH, width, height)
                        val b = Guides.toGuide(g0, view0, end, slotW, slotH, width, height)
                        if (a != null && b != null) onGuide(GuideLine(a.x, a.y, b.x, b.y))
                    }
                }

                session.horizonTool -> {
                    session.grid = CropGrid.FINE
                    val end = trackFinger(down.id) { session.horizonLine = down.position to it }
                    session.horizonLine = null
                    session.grid = CropGrid.NONE
                    if (end != null && (end - down.position).getDistance() >= minLine) {
                        val (x0, y0) = CropView.toPlane(view0, down.position, slotW, slotH)
                        val (x1, y1) = CropView.toPlane(view0, end, slotW, slotH)
                        val angle = CropEditing.horizonAngle(g0, x0, y0, x1, y1)
                        onGeometry(CropEditing.straighten(g0, angle, width, height))
                        session.horizonTool = false
                    }
                }

                handle != null -> {
                    session.frozenView = view0
                    session.grid = CropGrid.THIRDS
                    val ratio = CropEditing.ratio(g0, width, height)
                    trackFinger(down.id) { p ->
                        val box = CropEditing.dragHandle(
                            g0,
                            frame0,
                            handle,
                            ((p.x - down.position.x) / view0.scale).toDouble(),
                            ((p.y - down.position.y) / view0.scale).toDouble(),
                            ratio,
                            width,
                            height,
                            (minFrame / view0.scale).toDouble(),
                        )
                        onGeometry(CropEditing.withFrame(g0, box, width, height))
                    }
                    session.grid = CropGrid.NONE
                    scope.launch {
                        progress.snapTo(0f)
                        session.releasedView = session.frozenView
                        session.frozenView = null
                        progress.animateTo(1f, tween(SETTLE_MS))
                        session.releasedView = null
                    }
                }

                else -> {
                    session.grid = CropGrid.THIRDS
                    val minSize = (minFrame / view0.scale).toDouble()
                    movePhoto(g0, frame0, width, height, minSize, slotW, slotH, margin) { onGeometry(it) }
                    session.grid = CropGrid.NONE
                }
            }
        }
    }
    val hoverAt = hover.at
    val penHandle = with(density) { PEN_HANDLE_TOUCH.toPx() }
    val hoveredHandle = hoverAt?.takeIf { !session.horizonTool && !session.guideTool && slot != IntSize.Zero }?.let {
        hitHandle(frame, shown, it, slot.width.toFloat(), slot.height.toFloat(), penHandle)
    }
    val hoveredGuide = hoverAt?.takeIf { session.guideTool && slot != IntSize.Zero }?.let { at ->
        geometry.transform.guides.firstNotNullOfOrNull { guide ->
            val size = Size(slot.width.toFloat(), slot.height.toFloat())
            val a = Guides.toSlot(geometry, shown, guide.x0, guide.y0, size, width, height)
            val b = Guides.toSlot(geometry, shown, guide.x1, guide.y1, size, width, height)
            if (a != null && b != null && distanceToSegment(at, a, b) <= penHandle) a to b else null
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { slot = it }
            .testTag(EditorTags.CROP_OVERLAY)
            .semantics { contentDescription = description }
            .then(penLoupe)
            .then(gestures),
    ) {
        if (slot == IntSize.Zero) return@Canvas
        val r = slotRect(frame, shown, size)
        drawScrim(r, scrim)
        drawGrid(r, session.grid, line.copy(alpha = GRID_ALPHA))
        drawRect(line, r.topLeft, r.size, style = Stroke(1.dp.toPx()))
        drawHandles(r, line, hoveredHandle, accent)
        hoveredGuide?.let { (a, b) -> drawLine(accent, a, b, 4.dp.toPx()) }
        session.horizonLine?.let { (a, b) -> drawLine(accent, a, b, 2.dp.toPx()) }
        if (session.guideTool || geometry.transform.upright == UprightMode.GUIDED) {
            for (guide in geometry.transform.guides) {
                val a = Guides.toSlot(geometry, shown, guide.x0, guide.y0, size, width, height) ?: continue
                val b = Guides.toSlot(geometry, shown, guide.x1, guide.y1, size, width, height) ?: continue
                drawLine(accent, a, b, 2.dp.toPx())
                drawCircle(accent, 4.dp.toPx(), a)
                drawCircle(accent, 4.dp.toPx(), b)
            }
        }
        session.guideLine?.let { (a, b) -> drawLine(accent, a, b, 2.dp.toPx()) }
    }
    val tooltip = when {
        hoveredHandle != null -> {
            val scale = ready.hundredPercentScale
            stringResource(
                R.string.editor_hover_crop_size,
                (frame.width * scale).roundToInt(),
                (frame.height * scale).roundToInt(),
            )
        }

        hoveredGuide != null -> {
            val (a, b) = hoveredGuide
            stringResource(R.string.editor_hover_guide, "%.1f".format(Locale.getDefault(), axisAngle(a, b)))
        }

        else -> null
    }
    HoverTooltipLayer(tooltip, hoverAt, Modifier.matchParentSize())
}

/** Distance from [p] to the segment [a]-[b]. */
internal fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

/** The tilt of the segment [a]-[b] from the nearest axis (horizontal or vertical), in degrees, signed. */
internal fun axisAngle(a: Offset, b: Offset): Double {
    val deg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
    // Fold to -45..45 around the nearest axis.
    var t = ((deg % 90.0) + 90.0) % 90.0
    if (t > 45.0) t -= 90.0
    return t
}

/** Follows pointer [id] until it lifts, calling [onMove] with each new position; returns the last position. */
private suspend fun AwaitPointerEventScope.trackFinger(id: PointerId, onMove: (Offset) -> Unit): Offset? {
    var last: Offset? = null
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return last
        if (change.positionChanged()) {
            change.consume()
            last = change.position
            onMove(change.position)
        }
        if (!change.pressed) return last
    }
}

/**
 * One finger drags the photo under the frame; two fingers also pinch (zoom inside the crop). The view follows the
 * frame, so on screen the frame stays put and the photo moves.
 */
@Suppress("LongParameterList")
private suspend fun AwaitPointerEventScope.movePhoto(
    g: Geometry,
    start: Box,
    width: Int,
    height: Int,
    minSize: Double,
    slotW: Float,
    slotH: Float,
    margin: Float,
    onGeometry: (Geometry) -> Unit,
) {
    var box = start
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) return
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        if (zoom == 1f && pan == Offset.Zero) continue
        // The view of the current frame: the photo's scale on screen while the frame keeps its size.
        val view = CropView.fit(box, slotW, slotH, margin, Float.MAX_VALUE)
        if (zoom != 1f) {
            val (fx, fy) = CropView.toPlane(view, event.calculateCentroid(useCurrent = true), slotW, slotH)
            box = CropEditing.pinch(g, box, zoom.toDouble(), fx, fy, width, height, minSize)
        }
        if (pan != Offset.Zero) {
            box =
                CropEditing.pan(g, box, (pan.x / view.scale).toDouble(), (pan.y / view.scale).toDouble(), width, height)
        }
        event.changes.forEach { if (it.positionChanged()) it.consume() }
        onGeometry(CropEditing.withFrame(g, box, width, height))
    }
}

/** The handle within [radius] of [p], the nearest one; `null` when none. */
@Suppress("LongParameterList")
private fun hitHandle(
    frame: Box,
    view: ViewTransform,
    p: Offset,
    slotW: Float,
    slotH: Float,
    radius: Float,
): CropHandle? {
    val tl = CropView.toSlot(view, frame.left, frame.top, slotW, slotH)
    val br = CropView.toSlot(view, frame.right, frame.bottom, slotW, slotH)
    return CropHandle.entries
        .map { h ->
            val x = when (h.hx) {
                -1 -> tl.x
                1 -> br.x
                else -> (tl.x + br.x) / 2f
            }
            val y = when (h.hy) {
                -1 -> tl.y
                1 -> br.y
                else -> (tl.y + br.y) / 2f
            }
            h to hypot(p.x - x, p.y - y)
        }
        .filter { it.second <= radius }
        .minByOrNull { it.second }
        ?.first
}

private fun slotRect(frame: Box, view: ViewTransform, size: Size): Rect {
    val tl = CropView.toSlot(view, frame.left, frame.top, size.width, size.height)
    val br = CropView.toSlot(view, frame.right, frame.bottom, size.width, size.height)
    return Rect(tl, br)
}

private fun DrawScope.drawScrim(r: Rect, colour: Color) {
    drawRect(colour, Offset.Zero, Size(size.width, r.top.coerceAtLeast(0f)))
    drawRect(colour, Offset(0f, r.bottom), Size(size.width, (size.height - r.bottom).coerceAtLeast(0f)))
    drawRect(colour, Offset(0f, r.top), Size(r.left.coerceAtLeast(0f), r.height))
    drawRect(colour, Offset(r.right, r.top), Size((size.width - r.right).coerceAtLeast(0f), r.height))
}

private fun DrawScope.drawGrid(r: Rect, grid: CropGrid, colour: Color) {
    val n = grid.divisions
    if (n == 0) return
    val stroke = 1.dp.toPx()
    for (i in 1 until n) {
        val x = r.left + r.width * i / n
        val y = r.top + r.height * i / n
        drawLine(colour, Offset(x, r.top), Offset(x, r.bottom), stroke)
        drawLine(colour, Offset(r.left, y), Offset(r.right, y), stroke)
    }
}

/** L-shaped corner handles and short bars at the middle of each edge; the [hovered] one in [accent]. */
@Suppress("CyclomaticComplexMethod")
private fun DrawScope.drawHandles(r: Rect, base: Color, hovered: CropHandle?, accent: Color) {
    val t = HANDLE_THICKNESS.toPx()
    val len = HANDLE_LENGTH.toPx().coerceAtMost(minOf(r.width, r.height) / 3f)
    for (h in CropHandle.entries) {
        val colour = if (h == hovered) accent else base
        val x = when (h.hx) {
            -1 -> r.left
            1 -> r.right
            else -> r.center.x
        }
        val y = when (h.hy) {
            -1 -> r.top
            1 -> r.bottom
            else -> r.center.y
        }
        if (h.corner) {
            // Outside the frame line so the corner reads as a bracket around the crop.
            val ox = if (h.hx < 0) x - t else x
            val oy = if (h.hy < 0) y - t else y
            val horizontalX = if (h.hx < 0) ox else x + t - len
            drawRect(colour, Offset(horizontalX, oy), Size(len, t))
            val verticalY = if (h.hy < 0) oy else y + t - len
            drawRect(colour, Offset(ox, verticalY), Size(t, len))
        } else if (h.hx != 0) {
            drawRect(colour, Offset(if (h.hx < 0) x - t else x, y - len / 2f), Size(t, len))
        } else {
            drawRect(colour, Offset(x - len / 2f, if (h.hy < 0) y - t else y), Size(len, t))
        }
    }
}

private val FRAME_MARGIN = 28.dp
private val LOUPE_SIZE = 112.dp
private val LOUPE_LIFT = 96.dp
private val HANDLE_TOUCH = 32.dp

/** The pen's handle radius (and the hover's): precise enough to tell an edge from the corner beside it. */
private val PEN_HANDLE_TOUCH = 20.dp
private val HANDLE_THICKNESS = 3.dp
private val HANDLE_LENGTH = 20.dp
private val MIN_FRAME = 48.dp
private val MIN_HORIZON = 24.dp
private const val MAX_ZOOM = 4f
private const val SCRIM_ALPHA = 0.72f
private const val GRID_ALPHA = 0.55f
private const val SETTLE_MS = 220
