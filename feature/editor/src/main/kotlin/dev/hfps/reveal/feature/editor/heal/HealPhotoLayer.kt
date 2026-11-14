@file:Suppress("ReturnCount", "MagicNumber", "LoopWithTooManyJumpStatements")

package dev.hfps.reveal.feature.editor.heal

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.canvas.HoverTooltipLayer
import dev.hfps.reveal.feature.editor.canvas.PhotoController
import dev.hfps.reveal.feature.editor.canvas.penHover
import dev.hfps.reveal.feature.editor.masks.MaskView
import dev.hfps.reveal.feature.editor.masks.maskView
import dev.hfps.reveal.feature.editor.tools.PhotoLayer
import dev.hfps.reveal.feature.editor.tools.PhotoLayerHost
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import kotlin.math.max

/**
 * What the Heal mode does on the photo (docs/adr/0023 "Editor"): a tap adds a spot of the brush size, a drag paints a
 * stroke spot (pen pressure scales the size), a drag of the selected spot's source moves where it copies from, a drag
 * of a spot moves it, a tap on a spot selects it. Every spot is drawn (target, and the selected spot's source with a
 * line between them), with the brush cursor under the finger or a hovering pen. Positions are image fractions through
 * [MaskView], so they follow the crop, rotation, flips, lens corrections and zoom; once a pen is seen, fingers only pan
 * and zoom (docs/adr/0016), and a second finger always cancels a stroke for a pinch.
 */
object HealPhotoLayer {
    val layer: PhotoLayer = PhotoLayer(
        gestures = { host -> Modifier.healGestures(host) },
        overlay = { host -> HealOverlay(host) },
    )
}

/** What a press on the photo grabbed. */
private sealed interface Grab {
    data class Source(val heal: Heal) : Grab

    data class Target(val heal: Heal) : Grab

    data object None : Grab
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun Modifier.healGestures(host: PhotoLayerHost): Modifier {
    val controller = host.photo
    val current by rememberUpdatedState(host)
    val slop = LocalViewConfiguration.current.touchSlop
    val description = stringResource(R.string.heal_photo_actions)
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
                if (!pen && controller.penSeen) return@awaitEachGesture
                val h = current
                val state = h.state
                if (!state.heal.open) return@awaitEachGesture
                val actions = h.actions.heal
                val view = healView(state, controller, size.width.toFloat(), size.height.toFloat())
                    ?: return@awaitEachGesture
                if (!pen && currentEvent.changes.count { it.pressed } > 1) return@awaitEachGesture
                val grabRadius = (if (pen) PEN_GRAB else GRAB).toPx()
                val grab = hit(state, view, down.position, grabRadius)
                down.consume()
                val start = view.toImage(down.position) ?: return@awaitEachGesture
                var moved = false
                var stroking = false
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (!pen && event.changes.count { it.pressed } > 1) {
                        // A second finger: the user wants to zoom, not paint.
                        if (stroking) actions.strokeCancel()
                        if (dragging) actions.commit()
                        controller.brushCursor = null
                        return@awaitEachGesture
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!moved && (change.position - down.position).getDistance() > slop) {
                        moved = true
                        when (grab) {
                            is Grab.Source, is Grab.Target -> {
                                dragging = true
                                if (grab is Grab.Target) actions.select(grab.heal.id)
                            }

                            Grab.None -> {
                                stroking = true
                                actions.strokeStart(point(start, down.pressure, pen))
                            }
                        }
                    }
                    if (change.positionChanged()) {
                        change.consume()
                        controller.brushCursor = change.position
                        val at = view.toImage(change.position)
                        if (at != null && dragging) {
                            val dx = at.x - start.x
                            val dy = at.y - start.y
                            when (grab) {
                                is Grab.Source -> actions.moveSource(grab.heal.id, grab.heal.dx + dx, grab.heal.dy + dy)

                                is Grab.Target -> {
                                    val (cx, cy) = HealEdits.centre(grab.heal)
                                    actions.moveTarget(grab.heal.id, cx + dx, cy + dy)
                                }

                                Grab.None -> Unit
                            }
                        }
                        if (stroking) {
                            val points = buildList {
                                for (hc in change.historical) {
                                    view.toImage(hc.position)?.let { add(point(it, change.pressure, pen)) }
                                }
                                at?.let { add(point(it, change.pressure, pen)) }
                            }
                            actions.strokeMove(points)
                        }
                    }
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                }
                controller.brushCursor = null
                when {
                    stroking -> actions.strokeEnd()

                    dragging -> actions.commit()

                    grab is Grab.Source -> actions.select(grab.heal.id)

                    grab is Grab.Target -> actions.select(
                        if (state.heal.selected ==
                            grab.heal.id
                        ) {
                            null
                        } else {
                            grab.heal.id
                        },
                    )

                    else -> actions.tap(start.x, start.y)
                }
            }
        }
        .semantics { contentDescription = description }
        .testTag(HealTags.PHOTO_LAYER)
}

/** A stroke point: a pen's pressure (0..1), 1 for a finger or a mouse. */
private fun point(at: Offset, pressure: Float, pen: Boolean): StrokePoint =
    StrokePoint(at.x, at.y, if (pen) pressure.coerceIn(0f, 1f) else 1f)

/** The view of the spots in a slot of [width] x [height] pixels (the masks' view: image fractions to the slot). */
internal fun healView(state: EditorUiState, controller: PhotoController, width: Float, height: Float): MaskView? =
    if (state.phase is EditorPhase.Ready) maskView(state, controller, width, height) else null

/** A spot's radius on screen. */
private fun MaskView.radius(heal: Heal): Float = heal.radius * longEdge * pixelScale

/** The selected spot's source first, then the spots from the last one (drawn on top) down. */
private fun hit(state: EditorUiState, view: MaskView, p: Offset, slop: Float): Grab {
    val heals = state.edit.recipe.heals
    val selected = HealEdits.find(state.edit.recipe, state.heal.selected)
    if (selected != null && selected.hasSource) {
        val r = max(view.radius(selected), slop)
        if (distanceTo(selected, view, p, sourceOf = true) <= r) return Grab.Source(selected)
    }
    for (heal in heals.asReversed()) {
        val r = max(view.radius(heal), slop)
        if (distanceTo(heal, view, p, sourceOf = false) <= r) return Grab.Target(heal)
    }
    return Grab.None
}

/** The screen distance from [p] to the spot's centre (circle) or path (stroke); [sourceOf] measures its source. */
private fun distanceTo(heal: Heal, view: MaskView, p: Offset, sourceOf: Boolean): Float {
    val path = slotPath(heal, view, sourceOf)
    if (path.isEmpty()) return Float.MAX_VALUE
    if (path.size == 1) return (p - path[0]).getDistance()
    var best = Float.MAX_VALUE
    for (i in 1 until path.size) best = minOf(best, segmentDistance(p, path[i - 1], path[i]))
    return best
}

/** The spot's centre (circle) or points (stroke) in slot pixels, moved by the source offset when [sourceOf]. */
private fun slotPath(heal: Heal, view: MaskView, sourceOf: Boolean): List<Offset> {
    val dx = if (sourceOf) heal.dx else 0f
    val dy = if (sourceOf) heal.dy else 0f
    return if (heal.isStroke) {
        heal.strokePoints.mapNotNull { view.toSlot(it.x + dx, it.y + dy) }
    } else {
        listOfNotNull(view.toSlot(heal.x + dx, heal.y + dy))
    }
}

private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 <= 0f) return (p - a).getDistance()
    val t = (((p - a).x * ab.x + (p - a).y * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

// ---------------------------------------------------------------------------------------------- overlay

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun BoxScope.HealOverlay(host: PhotoLayerHost) {
    val state = host.state
    val controller = host.photo

    // Redraw when the view moves (zoom, pan, frame).
    @Suppress("UNUSED_VARIABLE")
    val tick = controller.viewTick
    val heal = state.heal
    if (!heal.open) return
    val colors = RevealTheme.colors
    val line = colors.foreground
    val shade = colors.background
    val accent = colors.primary
    val failed = colors.destructive
    var slot by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val view = if (slot ==
        IntSize.Zero
    ) {
        null
    } else {
        healView(state, controller, slot.width.toFloat(), slot.height.toFloat())
    }
    val spin by rememberInfiniteTransition(label = "heal").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "healSpin",
    )
    val hover = controller.penHover
    val hoveredSource = if (view != null && hover != null) {
        (hit(state, view, hover, with(density) { PEN_GRAB.toPx() }) as? Grab.Source) != null
    } else {
        false
    }
    Canvas(
        Modifier
            .matchParentSize()
            .onSizeChanged { slot = it }
            .testTag(HealTags.OVERLAY),
    ) {
        if (view == null) return@Canvas
        val thin = 1.5.dp.toPx()
        val under = 3.dp.toPx()
        for (spot in state.edit.recipe.heals) {
            val selected = spot.id == heal.selected
            val colour = when {
                spot.id in heal.failed -> failed
                selected -> accent
                else -> line
            }
            val r = view.radius(spot)
            val target = slotPath(spot, view, sourceOf = false)
            drawShape(target, r, shade, under, null)
            drawShape(target, r, colour, if (selected) thin * 1.5f else thin, null)
            if (spot.id in heal.pending) {
                target.firstOrNull()?.let { c ->
                    drawArc(
                        accent,
                        startAngle = spin,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = c - Offset(r + 4.dp.toPx(), r + 4.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(2 * (r + 4.dp.toPx()), 2 * (r + 4.dp.toPx())),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            if (selected && spot.hasSource) {
                val source = slotPath(spot, view, sourceOf = true)
                val dash = PathEffect.dashPathEffect(floatArrayOf(DASH, DASH))
                drawShape(source, r, shade, under, null)
                drawShape(source, r, if (hoveredSource) accent else line, thin, dash)
                val a = target.firstOrNull()
                val b = source.firstOrNull()
                if (a != null && b != null) {
                    drawLine(shade, a, b, under)
                    drawLine(line, a, b, thin, pathEffect = dash)
                    drawCircle(shade, 5.dp.toPx(), b)
                    drawCircle(if (hoveredSource) accent else line, 3.5.dp.toPx(), b)
                }
            }
        }
        // The stroke being painted.
        heal.painting?.let { points ->
            val slotPoints = points.mapNotNull { view.toSlot(it.x, it.y) }
            val width = 2f * heal.settings.size * view.longEdge * view.pixelScale
            drawPolylineWide(slotPoints, accent.copy(alpha = PAINT_ALPHA), width)
        }
        // The brush cursor under the finger or the hovering pen.
        val cursor = controller.brushCursor ?: hover
        if (cursor != null) {
            val r = heal.settings.size * view.longEdge * view.pixelScale
            drawCircle(shade, r, cursor, style = Stroke(under))
            drawCircle(line, r, cursor, style = Stroke(thin))
            val inner = r * (1f - heal.settings.feather / 100f).coerceIn(0f, 1f)
            if (inner > 1f) {
                drawCircle(
                    line,
                    inner,
                    cursor,
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                )
            }
        }
    }
    val tooltip = if (hoveredSource) stringResource(R.string.heal_source) else null
    HoverTooltipLayer(tooltip, hover, Modifier.matchParentSize())
}

/** A circle (one point) or a stroke's outline (its path at the dab width). */
private fun DrawScope.drawShape(points: List<Offset>, r: Float, colour: Color, width: Float, effect: PathEffect?) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(colour, r, points[0], style = Stroke(width, pathEffect = effect))
        return
    }
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    // A stroke: its area as a light wash at the dab diameter, and its path.
    drawPath(
        path,
        colour.copy(alpha = STROKE_WASH),
        style = Stroke(2f * r, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.drawPolylineWide(points: List<Offset>, colour: Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(colour, width / 2f, points[0])
        return
    }
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private val GRAB = 28.dp
private val PEN_GRAB = 16.dp
private const val DASH = 10f
private const val SPIN_MS = 1200
private const val PAINT_ALPHA = 0.35f
private const val STROKE_WASH = 0.18f
