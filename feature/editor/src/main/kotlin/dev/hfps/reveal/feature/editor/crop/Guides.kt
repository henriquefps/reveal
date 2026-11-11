package dev.hfps.reveal.feature.editor.crop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.geometry.Perspective
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.recipe.Geometry
import kotlin.math.hypot

/**
 * Guide lines between the Crop mode's screen and the recipe (docs/adr/0015): a guide is stored in the
 * **lens-corrected** image (normalised 0..1), so it stays on the photo's content while the Transform changes. The
 * screen shows the editing plane: plane point -> crop plane ([GeometryMapping.editing]) -> corrected image
 * ([Perspective.toCorrected]).
 */
internal object Guides {
    /** The corrected-image point (0..1) under slot point [p], `null` behind the horizon. */
    @Suppress("LongParameterList")
    fun toGuide(g: Geometry, view: ViewTransform, p: Offset, slotW: Float, slotH: Float, w: Int, h: Int): Offset? {
        val (px, py) = CropView.toPlane(view, p, slotW, slotH)
        val image = GeometryMapping.editing(g, w, h).outputToImage(px, py)
        val half = hypot(w.toDouble(), h.toDouble()) / 2
        val q = Perspective.toCorrected(g.transform).map((image.x - w / 2.0) / half, (image.y - h / 2.0) / half)
            ?: return null
        return Offset(((q.x * half + w / 2.0) / w).toFloat(), ((q.y * half + h / 2.0) / h).toFloat())
    }

    /** The slot point of corrected-image point ([x], [y]) (0..1), `null` behind the horizon. */
    @Suppress("LongParameterList")
    fun toSlot(g: Geometry, view: ViewTransform, x: Float, y: Float, size: Size, w: Int, h: Int): Offset? {
        val half = hypot(w.toDouble(), h.toDouble()) / 2
        val p = Perspective.forward(g.transform).map((x * w - w / 2.0) / half, (y * h - h / 2.0) / half) ?: return null
        val plane = GeometryMapping.editing(g, w, h).imageToOutput(p.x * half + w / 2.0, p.y * half + h / 2.0)
        return CropView.toSlot(view, plane.x, plane.y, size.width, size.height)
    }
}
