package dev.hfps.reveal.pipeline.gl

import kotlin.math.min

/**
 * The part of the window surface where the photo is framed, in window pixels (origin top-left). The editor keeps its
 * `SurfaceView` the size of the whole canvas area and lays panels over it; the frame is the visible rectangle above
 * (portrait) or beside (landscape) the panel. Zoom and pan ([ViewTransform]) are expressed in frame terms: the
 * view centre is the frame centre, so the photo stays centred in the free space while the panel snaps.
 */
data class ViewFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    companion object {
        /** The whole window of [width] x [height] pixels. */
        fun whole(width: Int, height: Int): ViewFrame = ViewFrame(0f, 0f, width.toFloat(), height.toFloat())
    }
}

/** How the original (the default recipe) is compared with the edit on screen. */
sealed interface CompareMode {
    /** Only the edit (or the original while "hold to compare" is on). */
    data object Off : CompareMode

    /**
     * One image split by a divider at [position] (0..1 across the frame): the original before it, the edit after.
     *
     * @property leftRight true = a vertical divider, original left / edit right; false = a horizontal divider,
     *   original top / edit bottom.
     */
    data class Split(val leftRight: Boolean = true, val position: Float = 0.5f) : CompareMode

    /** Two copies of the photo next to each other (left/right in a wide frame, top/bottom in a tall one). */
    data object SideBySide : CompareMode
}

/**
 * A magnifier drawn by the output pass (the eyedropper's loupe): the image point ([imageX], [imageY]) shown at
 * [scale] screen pixels per image pixel in a square of [size] pixels centred on window pixel ([centerX], [centerY]).
 */
data class Loupe(
    val centerX: Float,
    val centerY: Float,
    val size: Float,
    val imageX: Float,
    val imageY: Float,
    val scale: Float,
)

/** One draw of the output pass: a window-space [view], an optional scissor [clip] and which texture it shows. */
data class ComparePane(val view: ViewTransform, val clip: ViewFrame?, val original: Boolean)

/**
 * Pure maths of the frame offset and the compare panes (JVM-tested). The output shader maps a window pixel `sp` to
 * the image with `ip = (sp - windowCentre) / scale + centre`; to show the image point `c` at a pane's centre `P`
 * instead of the window centre, the centre is shifted to `c - (P - windowCentre) / scale`.
 */
object CompareLayout {
    /**
     * The panes to draw, in order, for [mode] in [frame] of a [windowWidth] x [windowHeight] surface showing an image
     * of [imageWidth] x [imageHeight] pixels with the zoom [view] (frame terms).
     *
     * - [CompareMode.Off]: one unclipped pane with the edit.
     * - [CompareMode.Split]: the edit unclipped, then the original clipped to the "before" side of the divider.
     * - [CompareMode.SideBySide]: two clipped panes (original first); each keeps the zoom relative to fit.
     */
    @Suppress("LongParameterList")
    fun panes(
        mode: CompareMode,
        frame: ViewFrame,
        windowWidth: Int,
        windowHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        view: ViewTransform,
    ): List<ComparePane> {
        val windowCx = windowWidth / 2f
        val windowCy = windowHeight / 2f
        val framed = shifted(view, view.scale, frame, windowCx, windowCy)
        return when (mode) {
            CompareMode.Off -> listOf(ComparePane(framed, null, original = false))

            is CompareMode.Split -> {
                val p = mode.position.coerceIn(0f, 1f)
                val before = if (mode.leftRight) {
                    ViewFrame(frame.left, frame.top, frame.width * p, frame.height)
                } else {
                    ViewFrame(frame.left, frame.top, frame.width, frame.height * p)
                }
                listOf(ComparePane(framed, null, original = false), ComparePane(framed, before, original = true))
            }

            CompareMode.SideBySide -> {
                val (first, second) = sideBySide(frame)
                val frameFit = fit(frame, imageWidth, imageHeight)
                listOf(first to true, second to false).map { (pane, original) ->
                    val scale = view.scale * fit(pane, imageWidth, imageHeight) / frameFit
                    ComparePane(shifted(view, scale, pane, windowCx, windowCy), pane, original)
                }
            }
        }
    }

    /** The pane of [loupe] on a [windowWidth] x [windowHeight] surface: clipped to its square, never the original. */
    fun loupePane(loupe: Loupe, windowWidth: Int, windowHeight: Int): ComparePane {
        val half = loupe.size / 2f
        val clip = ViewFrame(loupe.centerX - half, loupe.centerY - half, loupe.size, loupe.size)
        val view = ViewTransform(
            scale = loupe.scale,
            centerX = loupe.imageX - (loupe.centerX - windowWidth / 2f) / loupe.scale,
            centerY = loupe.imageY - (loupe.centerY - windowHeight / 2f) / loupe.scale,
        )
        return ComparePane(view, clip, original = false)
    }

    /** The two halves of [frame]: left/right when it is at least as wide as tall, else top/bottom. */
    fun sideBySide(frame: ViewFrame): Pair<ViewFrame, ViewFrame> = if (frame.width >= frame.height) {
        val w = frame.width / 2f
        ViewFrame(frame.left, frame.top, w, frame.height) to ViewFrame(frame.left + w, frame.top, w, frame.height)
    } else {
        val h = frame.height / 2f
        ViewFrame(frame.left, frame.top, frame.width, h) to ViewFrame(frame.left, frame.top + h, frame.width, h)
    }

    /** Screen pixels per image pixel at which the whole image fits [frame]. */
    fun fit(frame: ViewFrame, imageWidth: Int, imageHeight: Int): Float =
        min(frame.width / imageWidth, frame.height / imageHeight)

    private fun shifted(view: ViewTransform, scale: Float, pane: ViewFrame, windowCx: Float, windowCy: Float) =
        ViewTransform(
            scale = scale,
            centerX = view.centerX - (pane.centerX - windowCx) / scale,
            centerY = view.centerY - (pane.centerY - windowCy) / scale,
        )
}
