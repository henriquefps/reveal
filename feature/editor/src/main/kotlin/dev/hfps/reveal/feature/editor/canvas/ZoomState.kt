package dev.hfps.reveal.feature.editor.canvas

import dev.hfps.reveal.pipeline.gl.ViewTransform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom and pan of the photo canvas as plain maths (no Compose, JVM-tested). The view shows the image point
 * ([centerX], [centerY]) at the middle of the canvas with [scale] screen pixels per image pixel.
 *
 * - **Fit** is the smallest allowed scale: the whole image visible, centred.
 * - **100 %** is [hundredPercentScale] screen pixels per image pixel of the proxy (1.0 when the proxy is the full
 *   image; larger when the proxy is a downscaled copy, so 100 % always means one photo pixel per screen pixel).
 * - The maximum is [maxZoomFactor] times 100 % (400 % by default).
 * - Along an axis where the image is smaller than the view the image stays centred; otherwise the view cannot leave
 *   the image.
 *
 * Every change is reported through [onChange] as a [ViewTransform] for the renderer.
 */
class ZoomState(
    private val hundredPercentScale: Float = 1f,
    private val maxZoomFactor: Float = DEFAULT_MAX_ZOOM,
    private val onChange: (ViewTransform) -> Unit = {},
) {
    private var imageWidth = 1f
    private var imageHeight = 1f
    private var viewWidth = 1f
    private var viewHeight = 1f

    /** Current screen pixels per image pixel. */
    var scale: Float = 1f
        private set

    /** Image x at the view centre. */
    var centerX: Float = 0f
        private set

    /** Image y at the view centre. */
    var centerY: Float = 0f
        private set

    /** Scale at which the whole image is visible. */
    val fitScale: Float get() = min(viewWidth / imageWidth, viewHeight / imageHeight)

    /** Largest allowed scale. */
    val maxScale: Float get() = max(hundredPercentScale * maxZoomFactor, fitScale)

    /** Scale of one photo pixel per screen pixel, but never below fit. */
    val oneToOneScale: Float get() = hundredPercentScale.coerceIn(fitScale, maxScale)

    /** True when the view shows the whole image. */
    val isFit: Boolean get() = scale <= fitScale * FIT_TOLERANCE

    /** Sets the image size and resets to fit. */
    fun setImageSize(width: Int, height: Int) {
        imageWidth = width.toFloat()
        imageHeight = height.toFloat()
        centerX = imageWidth / 2f
        centerY = imageHeight / 2f
        scale = fitScale
        publish()
    }

    /** Sets the canvas size; keeps fit if the view was at fit, otherwise keeps the centre. */
    fun setViewSize(width: Int, height: Int) {
        val wasFit = isFit
        viewWidth = max(width.toFloat(), 1f)
        viewHeight = max(height.toFloat(), 1f)
        scale = if (wasFit) fitScale else scale.coerceIn(fitScale, maxScale)
        clampCenter()
        publish()
    }

    /** Moves the image by ([dx], [dy]) screen pixels (a finger drag). */
    fun panBy(dx: Float, dy: Float) {
        centerX -= dx / scale
        centerY -= dy / scale
        clampCenter()
        publish()
    }

    /** Multiplies the scale by [factor], keeping the image point under ([focusX], [focusY]) (view pixels) fixed. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        scaleTo(scale * factor, focusX, focusY)
    }

    /** Sets the scale (clamped), keeping the image point under the view pixel ([focusX], [focusY]) fixed. */
    fun scaleTo(newScale: Float, focusX: Float, focusY: Float) {
        val target = newScale.coerceIn(fitScale, maxScale)
        val imageX = centerX + (focusX - viewWidth / 2f) / scale
        val imageY = centerY + (focusY - viewHeight / 2f) / scale
        scale = target
        centerX = imageX - (focusX - viewWidth / 2f) / scale
        centerY = imageY - (focusY - viewHeight / 2f) / scale
        clampCenter()
        publish()
    }

    /** Combined pinch/drag step: zoom by [factor] around ([focusX], [focusY]) then move by ([dx], [dy]). */
    fun transform(factor: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {
        zoomBy(factor, focusX, focusY)
        panBy(dx, dy)
    }

    /** The scale a double tap goes to: 100 % from fit (or any scale below 100 %), fit otherwise. */
    fun doubleTapTarget(): Float = if (scale < oneToOneScale * ONE_TO_ONE_TOLERANCE) oneToOneScale else fitScale

    /** Focus point (view pixels) for zoom commands that have no finger: the middle of the canvas. */
    val viewCenterX: Float get() = viewWidth / 2f

    /** See [viewCenterX]. */
    val viewCenterY: Float get() = viewHeight / 2f

    /** Pan limits are reported so tests and fling code can check them. */
    fun isCentered(axisX: Boolean): Boolean =
        if (axisX) imageWidth * scale <= viewWidth else imageHeight * scale <= viewHeight

    private fun clampCenter() {
        centerX = clampAxis(centerX, imageWidth, viewWidth)
        centerY = clampAxis(centerY, imageHeight, viewHeight)
    }

    private fun clampAxis(center: Float, image: Float, view: Float): Float {
        val half = view / (2f * scale)
        return if (image * scale <= view) image / 2f else center.coerceIn(half, image - half)
    }

    private fun publish() = onChange(ViewTransform(scale, centerX, centerY))

    /** Returns true when [a] and [b] are the same scale for practical purposes. */
    fun sameScale(a: Float, b: Float): Boolean = abs(a - b) <= SAME_SCALE_EPSILON * max(a, b)

    companion object {
        /** Default maximum zoom, relative to 100 %. */
        const val DEFAULT_MAX_ZOOM: Float = 4f

        private const val FIT_TOLERANCE = 1.001f
        private const val ONE_TO_ONE_TOLERANCE = 0.999f
        private const val SAME_SCALE_EPSILON = 1e-3f
    }
}
