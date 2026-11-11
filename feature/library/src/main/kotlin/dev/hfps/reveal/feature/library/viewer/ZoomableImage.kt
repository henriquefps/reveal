package dev.hfps.reveal.feature.library.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A decoded region of the full-resolution image, in its pixel coordinates. */
class DetailTile(val bitmap: ImageBitmap, val rect: Rect)

/**
 * Pan/zoom of one photo. [scale] is relative to "fit" (1 = the whole photo on screen); [offset] pans the scaled
 * image from the centre, in screen px. 100 % is the scale at which one full-resolution pixel is one screen pixel.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(Size.Zero)
    var fullSize by mutableStateOf(IntSize.Zero)

    val zoomed: Boolean get() = scale > 1.01f

    /** Screen px per full-resolution px at fit. */
    val fitScale: Float
        get() = if (fullSize.width <= 0 || viewport.width <= 0f) {
            1f
        } else {
            min(viewport.width / fullSize.width, viewport.height / fullSize.height)
        }

    /** The scale (relative to fit) that shows the photo at 100 %, at least 2x fit. */
    val hundredPercent: Float get() = max(1f / fitScale, MIN_DOUBLE_TAP)

    fun zoomBy(factor: Float, centroid: Offset) {
        val next = (scale * factor).coerceIn(1f, max(hundredPercent * OVERZOOM, MIN_DOUBLE_TAP))
        val applied = next / scale
        // Keep the point under the centroid fixed.
        val center = Offset(viewport.width / 2, viewport.height / 2)
        offset = (offset - (centroid - center)) * applied + (centroid - center)
        scale = next
        clamp()
    }

    fun panBy(delta: Offset) {
        offset += delta
        clamp()
    }

    fun toggle(at: Offset) {
        if (zoomed) {
            scale = 1f
            offset = Offset.Zero
        } else {
            zoomBy(hundredPercent, at)
        }
    }

    fun clamp() {
        val size = imageSize()
        val maxX = max(0f, (size.width - viewport.width) / 2)
        val maxY = max(0f, (size.height - viewport.height) / 2)
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /** The displayed size of the whole image in screen px. */
    fun imageSize(): Size = Size(fullSize.width * fitScale * scale, fullSize.height * fitScale * scale)

    /** Where the whole image is drawn on screen. */
    fun imageRect(): Rect {
        val size = imageSize()
        val left = (viewport.width - size.width) / 2 + offset.x
        val top = (viewport.height - size.height) / 2 + offset.y
        return Rect(left, top, left + size.width, top + size.height)
    }

    /** The visible part of the image in full-resolution pixels. */
    fun visibleImageRect(): Rect {
        val image = imageRect()
        val k = fullSize.width / image.width
        return Rect(
            ((0f - image.left) * k).coerceAtLeast(0f),
            ((0f - image.top) * k).coerceAtLeast(0f),
            ((viewport.width - image.left) * k).coerceAtMost(fullSize.width.toFloat()),
            ((viewport.height - image.top) * k).coerceAtMost(fullSize.height.toFloat()),
        )
    }

    /** Screen px per full-resolution px now. */
    val screenScale: Float get() = fitScale * scale

    private companion object {
        const val MIN_DOUBLE_TAP = 2f
        const val OVERZOOM = 2f
    }
}

/**
 * Draws [base] (the screen-size image) and, when zoomed in past its resolution, [detail] (full-resolution pixels of
 * the visible part) on top. Pinch zooms around the fingers, one finger pans only while zoomed (so the pager swipes
 * at fit), double-tap toggles fit and 100 %, tap calls [onTap].
 */
@Composable
fun ZoomableImage(
    base: ImageBitmap,
    fullSize: IntSize,
    state: ZoomState,
    detail: DetailTile?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        LaunchedEffect(viewport, fullSize) {
            state.viewport = viewport
            state.fullSize = if (fullSize.width > 0) fullSize else IntSize(base.width, base.height)
            state.clamp()
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { state.toggle(it) })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2 || state.zoomed) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f) state.zoomBy(zoom, event.calculateCentroid())
                                if (pan != Offset.Zero) state.panBy(pan)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            if (state.fullSize.width <= 0) return@Canvas
            val rect = state.imageRect()
            drawImage(
                image = base,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            if (detail != null && state.zoomed) {
                val k = rect.width / state.fullSize.width
                drawImage(
                    image = detail.bitmap,
                    dstOffset = IntOffset(
                        (rect.left + detail.rect.left * k).roundToInt(),
                        (
                            rect.top +
                                detail.rect.top * k
                            ).roundToInt(),
                    ),
                    dstSize = IntSize((detail.rect.width * k).roundToInt(), (detail.rect.height * k).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}
