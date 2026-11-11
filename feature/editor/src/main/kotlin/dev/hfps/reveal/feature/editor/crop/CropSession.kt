@file:Suppress("MagicNumber")

package dev.hfps.reveal.feature.editor.crop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.hfps.reveal.pipeline.geometry.Box
import dev.hfps.reveal.pipeline.gl.ViewTransform
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** The guide drawn in the crop frame: none at rest, thirds while dragging, a finer grid while straightening. */
enum class CropGrid(val divisions: Int) {
    NONE(0),
    THIRDS(3),
    FINE(9),
}

/**
 * UI-only state of the Crop mode, shared by its overlay (frame, handles, gestures) and its controls (dial, chips,
 * buttons). The geometry itself lives in the recipe; this is only what the screen needs while the mode is open.
 */
@Stable
class CropSession {
    /** The grid inside the frame. */
    var grid: CropGrid by mutableStateOf(CropGrid.NONE)

    /** "Draw a line along the horizon" is armed: the next drag on the photo draws the line. */
    var horizonTool: Boolean by mutableStateOf(false)

    /** The line being drawn (slot pixels), `null` when none. */
    var horizonLine: Pair<Offset, Offset>? by mutableStateOf(null)

    /** The view held still while a handle is dragged (the frame moves, the photo does not). */
    var frozenView: ViewTransform? by mutableStateOf(null)

    /** The view at the end of a handle drag, from which the view animates to the refitted frame. */
    var releasedView: ViewTransform? by mutableStateOf(null)

    /** The custom aspect chooser is open. */
    var customAspect: Boolean by mutableStateOf(false)
}

/**
 * The Crop mode's view of the editing plane (pure maths, JVM-tested): the frame centred in the slot and as large as
 * the slot allows minus a [margin] for the handles, never above [maxScale].
 */
object CropView {
    /** The view that fits [frame] (plane pixels) into a slot of [slotWidth] x [slotHeight] pixels. */
    fun fit(frame: Box, slotWidth: Float, slotHeight: Float, margin: Float, maxScale: Float): ViewTransform {
        val w = (slotWidth - 2 * margin).coerceAtLeast(1f)
        val h = (slotHeight - 2 * margin).coerceAtLeast(1f)
        val scale = min(min(w / frame.width.toFloat(), h / frame.height.toFloat()), maxScale)
        return ViewTransform(scale, frame.centerX.toFloat(), frame.centerY.toFloat())
    }

    /** Slot pixel of plane point ([x], [y]) under [view]. */
    fun toSlot(view: ViewTransform, x: Double, y: Double, slotWidth: Float, slotHeight: Float): Offset = Offset(
        ((x - view.centerX) * view.scale + slotWidth / 2f).toFloat(),
        ((y - view.centerY) * view.scale + slotHeight / 2f).toFloat(),
    )

    /** Plane point of slot pixel [p] under [view]. */
    fun toPlane(view: ViewTransform, p: Offset, slotWidth: Float, slotHeight: Float): Pair<Double, Double> =
        (view.centerX + (p.x - slotWidth / 2f) / view.scale).toDouble() to
            (view.centerY + (p.y - slotHeight / 2f) / view.scale).toDouble()

    /** [from] to [to] at [t] in 0..1: centre linear, scale geometric (zoom feels even). */
    fun lerp(from: ViewTransform, to: ViewTransform, t: Float): ViewTransform = ViewTransform(
        scale = exp(ln(from.scale) + (ln(to.scale) - ln(from.scale)) * t),
        centerX = from.centerX + (to.centerX - from.centerX) * t,
        centerY = from.centerY + (to.centerY - from.centerY) * t,
    )
}
