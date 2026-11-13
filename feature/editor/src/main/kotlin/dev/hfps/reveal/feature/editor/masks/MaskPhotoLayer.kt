@file:Suppress("TooManyFunctions", "ReturnCount", "MagicNumber", "LoopWithTooManyJumpStatements")

package dev.hfps.reveal.feature.editor.masks

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.canvas.HoverTooltipLayer
import dev.hfps.reveal.feature.editor.canvas.PhotoController
import dev.hfps.reveal.feature.editor.canvas.penHover
import dev.hfps.reveal.feature.editor.tools.PhotoLayer
import dev.hfps.reveal.feature.editor.tools.PhotoLayerHost
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the Masks panel does on the photo (docs/adr/0022 "M4/B (editor)"): the selected gradient's handles, the brush
 * (pressure, the pen's eraser end and its held button erase), the colour range's eyedropper, the subject's taps, the
 * brush cursor and the luminance map. Everything is in image coordinates through [MaskView], so it follows the crop,
 * rotation, flips, lens corrections and zoom. Once a pen has been seen, fingers only pan and zoom (docs/adr/0016).
 */
object MaskPhotoLayer {
    val layer: PhotoLayer = PhotoLayer(
        gestures = { host -> Modifier.maskGestures(host) },
        overlay = { host -> MaskOverlayLayer(host) },
    )
}

/** The view of the masks in the slot of [width] x [height] pixels; `null` before the photo is ready. */
internal fun maskView(state: EditorUiState, controller: PhotoController, width: Float, height: Float): MaskView? {
    val ready = state.phase as? EditorPhase.Ready ?: return null
    val view = controller.view ?: return null
    if (controller.cropView != null) return null
    val mapping = GeometryMapping.of(state.edit.recipe, state.edit.lens, ready.width, ready.height)
    return MaskView(mapping, view, width, height)
}

/** The selected mask's selected component, if any. */
private fun selectedComponent(state: EditorUiState): Pair<Int, MaskComponent>? {
    val mask = MaskEdits.find(state.edit.recipe, state.masks.selected) ?: return null
    val index = state.masks.component ?: return null
    return mask.components.getOrNull(index)?.let { index to it }
}

// ---------------------------------------------------------------------------------------------- gestures

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
@Composable
private fun Modifier.maskGestures(host: PhotoLayerHost): Modifier {
    val controller = host.photo
    val current by rememberUpdatedState(host)
    val slop = LocalViewConfiguration.current.touchSlop
    return this
        .penHover(controller) { at ->
            controller.penHover = at
            if (at != null) controller.penSeen = true
        }
        .pointerInput(controller, slop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                if (pen) controller.penSeen = true
                // With a pen in use, fingers fall through to the canvas gestures (pan, zoom).
                if (!pen && controller.penSeen) return@awaitEachGesture
                val h = current
                val state = h.state
                val actions = h.actions.masks
                val view = maskView(state, controller, size.width.toFloat(), size.height.toFloat())
                    ?: return@awaitEachGesture
                val selected = selectedComponent(state) ?: return@awaitEachGesture
                val (index, component) = selected
                val radius = (if (pen) PEN_HANDLE else HANDLE).toPx()
                val knob = MaskHandles.isoLength(KNOB.toPx(), view)
                val handle = hitHandle(component, view, down.position, radius, knob)
                if (handle != null) {
                    down.consume()
                    val from = view.slotToIso(down.position) ?: return@awaitEachGesture
                    actions.setInteracting(true)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.positionChanged()) {
                            change.consume()
                            view.slotToIso(change.position)?.let { to ->
                                actions.editComponent(index) { dragged(component, handle, from, to, view) }
                            }
                        }
                        if (!change.pressed) break
                    }
                    actions.commit()
                    actions.setInteracting(false)
                    return@awaitEachGesture
                }
                when (state.masks.tool) {
                    PhotoTool.BRUSH -> {
                        if (component !is MaskComponent.Brush) return@awaitEachGesture
                        val first = currentEvent
                        if (!pen && first.changes.count { it.pressed } > 1) return@awaitEachGesture
                        down.consume()
                        val erase = pen && (down.type == PointerType.Eraser || first.buttons.isPrimaryPressed)
                        val point = view.toImage(down.position) ?: return@awaitEachGesture
                        actions.strokeStart(stroke(point, down.pressure, pen), erase)
                        controller.brushCursor = down.position
                        controller.brushErasing = erase || state.masks.brush.erase
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!pen && event.changes.count { it.pressed } > 1) {
                                // A second finger: the user wants to zoom, not paint.
                                actions.strokeCancel()
                                cancelled = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.positionChanged()) {
                                change.consume()
                                val points = buildList {
                                    for (hc in change.historical) {
                                        view.toImage(hc.position)?.let { add(stroke(it, change.pressure, pen)) }
                                    }
                                    view.toImage(change.position)?.let { add(stroke(it, change.pressure, pen)) }
                                }
                                actions.strokeMove(points)
                                controller.brushCursor = change.position
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                        if (!cancelled) actions.strokeEnd()
                        controller.brushCursor = null
                        controller.brushErasing = false
                    }

                    PhotoTool.PICK_COLOUR, PhotoTool.SUBJECT_ADD, PhotoTool.SUBJECT_SUBTRACT -> {
                        // A tap (not a pan): the up is consumed so the canvas does not toggle the chrome.
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if ((change.position - down.position).getDistance() > slop) moved = true
                            if (!change.pressed) {
                                if (moved) return@awaitEachGesture
                                change.consume()
                                val at = view.toImage(change.position) ?: return@awaitEachGesture
                                if (at.x !in 0f..1f || at.y !in 0f..1f) return@awaitEachGesture
                                if (state.masks.tool == PhotoTool.PICK_COLOUR) {
                                    actions.pickColour(at.x, at.y)
                                } else {
                                    actions.subjectTap(at.x, at.y)
                                }
                                return@awaitEachGesture
                            }
                        }
                    }

                    PhotoTool.NONE -> Unit
                }
            }
        }
        .testTag(MaskTags.PHOTO_LAYER)
}

/** A stroke point: a pen's pressure (0..1), 1 for a finger or a mouse (docs/adr/0022 "Brush raster"). */
internal fun stroke(at: Offset, pressure: Float, pen: Boolean): StrokePoint =
    StrokePoint(at.x, at.y, if (pen) pressure.coerceIn(0f, 1f) else 1f)

private fun dragged(c: MaskComponent, handle: HandleId, from: Offset, to: Offset, view: MaskView): MaskComponent =
    when (c) {
        is MaskComponent.Linear -> MaskHandles.dragLinear(c, handle, from, to, view)
        is MaskComponent.Radial -> MaskHandles.dragRadial(c, handle, from, to, view)
        else -> c
    }

/**
 * The handle of [c] under slot point [p] within [radius] (pins first, then the nearest), `null` when none. A linear
 * gradient's lines are handles along their whole length; its centre line turns it.
 */
internal fun hitHandle(c: MaskComponent, view: MaskView, p: Offset, radius: Float, knob: Float): HandleId? {
    val slot = handlePositions(c, view, knob)
    val pin = slot[HandleId.CENTRE]?.firstOrNull()
    if (pin != null && (p - pin).getDistance() <= radius) return HandleId.CENTRE
    var best: HandleId? = null
    var bestDistance = radius
    if (c is MaskComponent.Linear) {
        for ((id, line) in linearLines(c, view)) {
            val d = MaskHandles.distanceToPolyline(p, line)
            if (d <= bestDistance) {
                best = id
                bestDistance = d
            }
        }
        return best
    }
    for ((id, points) in slot) {
        for (q in points) {
            val d = (p - q).getDistance()
            if (d <= bestDistance) {
                best = id
                bestDistance = d
            }
        }
    }
    return best
}

/** The handles' slot positions. */
private fun handlePositions(c: MaskComponent, view: MaskView, knob: Float): Map<HandleId, List<Offset>> = when (c) {
    is MaskComponent.Linear -> mapOfNotNull(HandleId.CENTRE to view.isoToSlot(MaskHandles.linearLines(c, view).centre))

    is MaskComponent.Radial ->
        MaskHandles.radialHandles(c, view, knob).mapValues { (_, points) -> points.mapNotNull(view::isoToSlot) }

    else -> emptyMap()
}

private fun mapOfNotNull(pair: Pair<HandleId, Offset?>): Map<HandleId, List<Offset>> =
    pair.second?.let { mapOf(pair.first to listOf(it)) } ?: emptyMap()

/** A linear gradient's start, centre (the rotation handle) and end lines in slot pixels. */
internal fun linearLines(c: MaskComponent.Linear, view: MaskView): Map<HandleId, List<Offset>> {
    val lines = MaskHandles.linearLines(c, view)
    val n = lines.normal
    fun line(through: Offset): List<Offset> = List(LINE_SAMPLES + 1) { i ->
        val t = -LINE_REACH + 2 * LINE_REACH * i / LINE_SAMPLES
        through + n * t
    }.mapNotNull(view::isoToSlot)
    return mapOf(
        HandleId.START to line(lines.start),
        HandleId.ROTATE to line(lines.centre),
        HandleId.END to line(lines.end),
    )
}

// ---------------------------------------------------------------------------------------------- overlay

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun BoxScope.MaskOverlayLayer(host: PhotoLayerHost) {
    val state = host.state
    val controller = host.photo

    // Redraw when the view moves (zoom, pan, frame).
    @Suppress("UNUSED_VARIABLE")
    val tick = controller.viewTick
    val masks = state.masks
    val selected = selectedComponent(state)
    val component = selected?.second
    val colors = RevealTheme.colors
    val line = colors.foreground
    val shade = colors.background
    val accent = colors.primary
    val subtract = colors.destructive
    val hover = controller.penHover
    var slot by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val view = if (slot ==
        IntSize.Zero
    ) {
        null
    } else {
        maskView(state, controller, slot.width.toFloat(), slot.height.toFloat())
    }
    val knob = view?.let { MaskHandles.isoLength(with(density) { KNOB.toPx() }, it) } ?: 0f
    val hovered = if (view != null && hover != null && component != null) {
        hitHandle(component, view, hover, with(density) { PEN_HANDLE.toPx() }, knob)
    } else {
        null
    }

    val lumaMap = (component as? MaskComponent.LuminanceRange)?.takeIf { masks.luminanceMap }?.let { c ->
        masks.preLocal?.let { image -> rememberLuminanceMap(image, c.low, c.high, c.falloff, accent) }
    }
    Canvas(
        Modifier
            .matchParentSize()
            .onSizeChanged { slot = it }
            .testTag(MaskTags.HANDLES),
    ) {
        if (view == null) return@Canvas
        lumaMap?.let { drawLuminanceMap(it, view) }
        when (component) {
            is MaskComponent.Linear -> drawLinear(component, view, line, shade, accent, hovered)
            is MaskComponent.Radial -> drawRadial(component, view, knob, line, shade, accent, hovered)
            else -> Unit
        }
        // The subject's taps: added points and the objects taken away.
        val mask = MaskEdits.find(state.edit.recipe, masks.selected)
        mask?.components?.filterIsInstance<MaskComponent.Ai>()?.forEach { ai ->
            for (p in ai.prompts) {
                val at = view.toSlot(p.x, p.y) ?: continue
                drawCircle(shade, PROMPT.toPx() + 2.dp.toPx(), at)
                drawCircle(if (ai.op == MaskOp.SUBTRACT) subtract else accent, PROMPT.toPx(), at)
            }
        }
        // The brush cursor: the dab size and where the feather starts, under the finger or the hovering pen.
        val cursor = controller.brushCursor ?: hover?.takeIf { masks.tool == PhotoTool.BRUSH }
        if (component is MaskComponent.Brush && cursor != null) {
            val brush = masks.brush
            val r = brush.size / 2f * view.longEdge * view.pixelScale
            val inner = r * (1f - brush.feather / FULL).coerceIn(0f, 1f)
            val colour = if (controller.brushErasing || brush.erase) subtract else line
            drawCircle(shade, r, cursor, style = Stroke(3.dp.toPx()))
            drawCircle(colour, r, cursor, style = Stroke(1.5.dp.toPx()))
            if (inner > 1f) {
                drawCircle(
                    colour,
                    inner,
                    cursor,
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                )
            }
        }
    }
    when (masks.tool) {
        PhotoTool.PICK_COLOUR -> Hint(PhosphorRegular.Eyedropper, stringResource(R.string.masks_pick_hint))

        PhotoTool.SUBJECT_ADD, PhotoTool.SUBJECT_SUBTRACT ->
            Hint(PhosphorRegular.HandTap, stringResource(R.string.masks_subject_hint))

        PhotoTool.BRUSH -> if ((component as? MaskComponent.Brush)?.strokes?.isEmpty() == true) {
            Hint(PhosphorRegular.PaintBrush, stringResource(R.string.masks_brush_hint))
        }

        PhotoTool.NONE -> Unit
    }
    val tooltip = hovered?.let { handleName(it) }?.let { stringResource(it) }
    HoverTooltipLayer(tooltip, hover, Modifier.matchParentSize())
}

private fun handleName(id: HandleId): Int = when (id) {
    HandleId.CENTRE -> R.string.masks_handle_move
    HandleId.START -> R.string.masks_handle_start
    HandleId.END -> R.string.masks_handle_end
    HandleId.ROTATE -> R.string.masks_handle_rotate
    HandleId.RADIUS_X, HandleId.RADIUS_Y -> R.string.masks_handle_size
    HandleId.FEATHER -> R.string.masks_feather
}

@Composable
private fun BoxScope.Hint(icon: ImageVector, text: String) {
    val colors = RevealTheme.colors
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(8.dp),
        shape = RevealTheme.shapes.chip,
        color = colors.card,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Text(
                text = text,
                style = RevealTheme.typography.ui,
                color = colors.foreground,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .widthIn(max = 260.dp),
            )
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawLinear(
    c: MaskComponent.Linear,
    view: MaskView,
    line: Color,
    shade: Color,
    accent: Color,
    hovered: HandleId?,
) {
    for ((id, points) in linearLines(c, view)) {
        val colour = if (id == hovered) accent else line
        val dashed = id != HandleId.ROTATE
        drawPolyline(points, shade, 3.dp.toPx(), null)
        drawPolyline(
            points,
            colour,
            1.5.dp.toPx(),
            if (dashed) PathEffect.dashPathEffect(floatArrayOf(DASH, DASH)) else null,
        )
    }
    view.isoToSlot(MaskHandles.linearLines(c, view).centre)?.let {
        pin(
            it,
            shade,
            if (hovered ==
                HandleId.CENTRE
            ) {
                accent
            } else {
                line
            },
            accent,
        )
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawRadial(
    c: MaskComponent.Radial,
    view: MaskView,
    knob: Float,
    line: Color,
    shade: Color,
    accent: Color,
    hovered: HandleId?,
) {
    val frame = MaskHandles.radialFrame(c, view)
    val outline = MaskHandles.radialOutline(frame).mapNotNull(view::isoToSlot)
    drawPolyline(outline, shade, 3.dp.toPx(), null)
    drawPolyline(outline, line, 1.5.dp.toPx(), null)
    val inner = (1f - c.feather / FULL).coerceIn(0f, 1f)
    if (inner > 0f && inner < 1f) {
        val feather = MaskHandles.radialOutline(frame, inner).mapNotNull(view::isoToSlot)
        drawPolyline(
            feather,
            if (hovered ==
                HandleId.FEATHER
            ) {
                accent
            } else {
                line
            },
            1.dp.toPx(),
            PathEffect.dashPathEffect(floatArrayOf(DASH, DASH)),
        )
    }
    val handles = MaskHandles.radialHandles(c, view, knob)
    val top = view.isoToSlot(frame.toIso(0f, -frame.ry))
    val knobAt = handles[HandleId.ROTATE]?.firstOrNull()?.let(view::isoToSlot)
    if (top != null && knobAt != null) drawLine(line, top, knobAt, 1.dp.toPx())
    for ((id, points) in handles) {
        for (p in points.mapNotNull(view::isoToSlot)) {
            val colour = if (id == hovered) accent else line
            if (id == HandleId.CENTRE) pin(p, shade, colour, accent) else dot(p, shade, colour)
        }
    }
}

private fun DrawScope.drawPolyline(points: List<Offset>, colour: Color, width: Float, effect: PathEffect?) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, colour, style = Stroke(width, pathEffect = effect))
}

private fun DrawScope.pin(at: Offset, shade: Color, ring: Color, fill: Color) {
    drawCircle(shade, PIN.toPx() + 2.dp.toPx(), at)
    drawCircle(ring, PIN.toPx(), at)
    drawCircle(fill, PIN.toPx() - 3.dp.toPx(), at)
}

private fun DrawScope.dot(at: Offset, shade: Color, colour: Color) {
    drawCircle(shade, DOT.toPx() + 2.dp.toPx(), at)
    drawCircle(colour, DOT.toPx(), at)
}

// ---------------------------------------------------------------------------------------------- luminance map

/** The pre-local lightness in grey, the luminance range's band tinted with [accent] (docs/plan/ux.md "Masks"). */
@Composable
private fun rememberLuminanceMap(image: PreLocalImage, low: Float, high: Float, falloff: Float, accent: Color): Bitmap =
    remember(image, low, high, falloff, accent) {
        LuminanceMap.render(image, low, high, falloff, accent.toArgb())
    }

/** The luminance map drawn through the geometry (crop, turns, flips; the small lens warp is left out). */
private fun DrawScope.drawLuminanceMap(bitmap: Bitmap, view: MaskView) {
    val m = view.mapping.toOutput
    val matrix = Matrix().apply {
        setScale(view.imageWidth.toFloat() / bitmap.width, view.imageHeight.toFloat() / bitmap.height)
        postConcat(
            Matrix().apply {
                setValues(
                    floatArrayOf(
                        m.a.toFloat(), m.b.toFloat(), m.tx.toFloat(),
                        m.c.toFloat(), m.d.toFloat(), m.ty.toFloat(),
                        0f, 0f, 1f,
                    ),
                )
            },
        )
        postTranslate(-view.view.centerX, -view.view.centerY)
        postScale(view.view.scale, view.view.scale)
        postTranslate(size.width / 2f, size.height / 2f)
    }
    drawContext.canvas.nativeCanvas.withSave {
        val left = (0f - view.view.centerX) * view.view.scale + size.width / 2f
        val top = (0f - view.view.centerY) * view.view.scale + size.height / 2f
        clipRect(
            left,
            top,
            left + view.mapping.outputWidth * view.view.scale,
            top + view.mapping.outputHeight * view.view.scale,
        )
        drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

/** The luminance map's pixels (pure, JVM-tested through [coverage]). */
internal object LuminanceMap {
    /** The luminance range's coverage of lightness [l] (0..1), as the engine computes it (docs/adr/0022). */
    fun coverage(l: Float, low: Float, high: Float, falloff: Float): Float {
        val x = l.coerceIn(0f, 1f)
        val lo = low / FULL
        val hi = high / FULL
        val f = falloff / FULL
        return sstep(lo - f, lo, x) * (1f - sstep(hi, hi + f, x))
    }

    private fun sstep(e0: Float, e1: Float, x: Float): Float {
        if (e0 >= e1) return if (x < e1) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun render(image: PreLocalImage, low: Float, high: Float, falloff: Float, accent: Int): Bitmap {
        val n = image.width * image.height
        val ar = (accent shr RED) and BYTE
        val ag = (accent shr GREEN) and BYTE
        val ab = accent and BYTE
        val px = IntArray(n) { i ->
            val l = image.lightness(i).coerceIn(0f, 1f)
            val grey = (l * BYTE).roundToInt()
            val k = coverage(l, low, high, falloff) * TINT
            val r = (grey + (ar - grey) * k).roundToInt()
            val g = (grey + (ag - grey) * k).roundToInt()
            val b = (grey + (ab - grey) * k).roundToInt()
            (BYTE shl ALPHA) or (r shl RED) or (g shl GREEN) or b
        }
        return Bitmap.createBitmap(px, max(1, image.width), max(1, image.height), Bitmap.Config.ARGB_8888)
    }

    private const val BYTE = 0xff
    private const val ALPHA = 24
    private const val RED = 16
    private const val GREEN = 8
    private const val TINT = 0.6f
}

private const val FULL = 100f
private const val DASH = 10f
private const val LINE_SAMPLES = 48
private const val LINE_REACH = 2f
private val HANDLE = 28.dp
private val PEN_HANDLE = 16.dp
private val KNOB = 32.dp
private val PIN = 9.dp
private val DOT = 6.dp
private val PROMPT = 5.dp
