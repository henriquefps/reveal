package dev.hfps.reveal.feature.editor.canvas

import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.viewinterop.AndroidView
import dev.hfps.reveal.designsystem.theme.EditorNeutrals
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.Loupe
import dev.hfps.reveal.pipeline.gl.ViewFrame
import dev.hfps.reveal.pipeline.gl.ViewTransform
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The photo of the editor: the renderer, its zoom/pan and the **frame**, the visible part of the full-screen
 * surface where the photo is fitted (the free space between the chrome). The layout reports the frame from the
 * "photo slot" it leaves empty ([onRootPlaced], [onSlotPlaced]); every change (a panel snap, a slider drag shrinking
 * the panel, immersive mode) moves the frame, the zoom re-fits and the renderer re-centres, so the photo follows the
 * chrome smoothly without resizing the surface.
 */
@Suppress("ReturnCount")
@Stable
class PhotoController {
    private var root: LayoutCoordinates? = null
    private var slot: LayoutCoordinates? = null

    /** The renderer of the ready photo, `null` before (and in screenshot tests). */
    var renderer: GpuDevelopRenderer? = null
        private set

    /** Zoom and pan of the ready photo, wired to [renderer]. */
    var zoom: ZoomState? = null
        private set

    /**
     * A pen has touched or hovered the photo in this editor: from then on the targeted adjustment answers the pen
     * and the finger pans and zooms (docs/adr/0016).
     */
    var penSeen: Boolean by mutableStateOf(false)

    /** Where a pen hovers over the photo (slot pixels) while a tool shows hover feedback, `null` otherwise. */
    var penHover: Offset? by mutableStateOf(null)

    /**
     * Counts view changes (zoom, pan, frame): overlays drawn in image coordinates (the Masks panel's handles) read it
     * to redraw when the view moves ([zoom] itself is plain maths, not snapshot state).
     */
    var viewTick: Int by mutableIntStateOf(0)
        private set

    /** The brush cursor's centre (slot pixels) while a finger or pen paints a mask, `null` otherwise. */
    var brushCursor: Offset? by mutableStateOf(null)

    /** The stroke in progress erases (the pen's eraser end, its button, or erase mode). */
    var brushErasing: Boolean by mutableStateOf(false)

    /** The eyedropper's finger and loupe position, in slot pixels. */
    val loupe: LoupeState = LoupeState()

    /** The frame in surface (root) pixels; `null` until the slot is placed. */
    var frame: ViewFrame? = null
        private set

    /**
     * The Crop mode's view of the editing plane (it follows the crop frame), `null` outside the mode; while set the
     * mode drives the renderer's view and the [zoom] stays where it was.
     */
    var cropView: ViewTransform? by mutableStateOf(null)

    /** The view shown now, in content (output or editing-plane) pixels: [cropView] or the [zoom]'s. */
    val view: ViewTransform?
        get() = cropView ?: zoom?.let { ViewTransform(it.scale, it.centerX, it.centerY) }

    private var contentWidth = 0
    private var contentHeight = 0

    /**
     * A zoomed view to show again once the photo, its content size and the frame are known (after a rotation or a
     * fold the activity is recreated; the ViewModel and renderer survive, the controller does not).
     */
    private var pending: Pending? = null

    private data class Pending(val contentWidth: Int, val contentHeight: Int, val memory: ZoomMemory)

    /** What [Saver] keeps: the content size and the zoomed view, `null` at fit or before the photo. */
    private fun saved(): FloatArray? {
        val m = zoom?.takeIf { cropView == null }?.memory() ?: return null
        return floatArrayOf(contentWidth.toFloat(), contentHeight.toFloat(), m.scale, m.centerX, m.centerY)
    }

    private fun applyPending() {
        val p = pending ?: return
        val z = zoom ?: return
        if (frame == null || p.contentWidth != contentWidth || p.contentHeight != contentHeight) return
        pending = null
        z.restore(p.memory)
    }

    /**
     * Binds the photo of [ready] (or none): a new zoom wired to its renderer, fitted to the frame already known, so
     * the renderer gets the frame even when the slot does not move when the photo arrives.
     */
    fun bind(ready: EditorPhase.Ready?) {
        val r = ready?.renderer
        renderer = r
        // A still photo (no renderer: screenshot tests) gets a zoom too, so overlays have a view to draw in.
        zoom = ready?.let {
            ZoomState(hundredPercentScale = it.hundredPercentScale, onChange = { v ->
                r?.setView(v)
                viewTick++
            }).also { z ->
                z.setImageSize(it.width, it.height)
            }
        }
        contentWidth = ready?.width ?: 0
        contentHeight = ready?.height ?: 0
        cropView = null
        frame = null
        slot?.let(::onSlotPlaced)
    }

    /**
     * The size of what the zoom frames: the geometry operator's output. A new size re-fits; the same size re-publishes
     * the zoom's view (the Crop mode moved the renderer's view while it was open).
     */
    fun setContentSize(width: Int, height: Int) {
        val z = zoom ?: return
        cropView = null
        if (width != contentWidth || height != contentHeight) {
            contentWidth = width
            contentHeight = height
            z.setImageSize(width, height)
        } else {
            renderer?.setView(ViewTransform(z.scale, z.centerX, z.centerY))
        }
        applyPending()
    }

    /** Shows [view] (editing-plane pixels) for the Crop mode. */
    fun showCropView(view: ViewTransform) {
        cropView = view
        renderer?.setView(view)
    }

    fun onRootPlaced(coordinates: LayoutCoordinates) {
        root = coordinates
    }

    fun onSlotPlaced(coordinates: LayoutCoordinates) {
        slot = coordinates
        val r = root ?: return
        if (!r.isAttached || !coordinates.isAttached) return
        val b = r.localBoundingBoxOf(coordinates, clipBounds = false)
        val next = ViewFrame(b.left, b.top, b.width, b.height)
        if (next == frame) return
        frame = next
        viewTick++
        renderer?.setFrame(next)
        zoom?.setViewSize(b.width.roundToInt(), b.height.roundToInt())
        applyPending()
    }

    /**
     * The proxy source pixel under slot point [p] through the geometry [mapping] (the zoom frames its output), or
     * `null` outside the picture.
     */
    fun imagePoint(p: Offset, mapping: GeometryMapping): Offset? {
        val z = zoom ?: return null
        val f = frame ?: return null
        val ox = z.centerX + (p.x - f.width / 2f) / z.scale
        val oy = z.centerY + (p.y - f.height / 2f) / z.scale
        if (ox !in 0f..mapping.outputWidth.toFloat() || oy !in 0f..mapping.outputHeight.toFloat()) return null
        val plane = mapping.outputToImage(ox.toDouble(), oy.toDouble())
        // Through the lens corrections and the Transform (docs/adr/0015) to the source pixel the eyedropper reads.
        val ip = mapping.warp?.let { it.source(plane.x, plane.y, 1) ?: return null } ?: plane
        val inside = ip.x in 0.0..mapping.imageWidth.toDouble() && ip.y in 0.0..mapping.imageHeight.toDouble()
        return if (inside) Offset(ip.x.toFloat(), ip.y.toFloat()) else null
    }

    /**
     * Shows the loupe centred on slot point [at] magnifying the image point under the slot point [finger]; `null`
     * hides it.
     */
    fun showLoupe(finger: Offset?, at: Offset, sizePx: Float) {
        val r = renderer ?: return
        val z = zoom ?: return
        val f = frame ?: return
        if (finger == null) {
            r.setLoupe(null)
            return
        }
        val ix = z.centerX + (finger.x - f.width / 2f) / z.scale
        val iy = z.centerY + (finger.y - f.height / 2f) / z.scale
        val scale = (z.scale * LOUPE_ZOOM).coerceAtMost(LOUPE_MAX_SCALE).coerceAtLeast(z.scale)
        r.setLoupe(Loupe(f.left + at.x, f.top + at.y, sizePx, ix, iy, scale))
    }

    /**
     * The Crop mode's magnifier while a guide is drawn (docs/adr/0015): the loupe at slot point [at] magnifies the
     * editing-plane point under [finger] in the crop [view]; `null` [finger] hides it.
     */
    fun showCropLoupe(finger: Offset?, at: Offset, sizePx: Float, view: ViewTransform) {
        val r = renderer ?: return
        val f = frame ?: return
        if (finger == null) {
            r.setLoupe(null)
            return
        }
        val px = view.centerX + (finger.x - f.width / 2f) / view.scale
        val py = view.centerY + (finger.y - f.height / 2f) / view.scale
        val scale = (view.scale * LOUPE_ZOOM).coerceAtMost(LOUPE_MAX_SCALE).coerceAtLeast(view.scale)
        r.setLoupe(Loupe(f.left + at.x, f.top + at.y, sizePx, px, py, scale))
    }

    companion object {
        private const val LOUPE_ZOOM = 4f
        private const val LOUPE_MAX_SCALE = 8f
        private const val SAVED_SIZE = 5

        /** Keeps the zoomed view across configuration changes. */
        val Saver: Saver<PhotoController, FloatArray> = Saver(
            save = { it.saved() },
            restore = { values ->
                PhotoController().apply {
                    if (values.size == SAVED_SIZE) {
                        pending = Pending(
                            values[0].toInt(),
                            values[1].toInt(),
                            ZoomMemory(values[2], values[3], values[4]),
                        )
                    }
                }
            },
        )
    }
}

/** Where the eyedropper's finger and loupe are, in slot pixels ([finger] `null` = no finger down). */
@Stable
class LoupeState {
    var finger: Offset? by mutableStateOf(null)
    var at: Offset by mutableStateOf(Offset.Zero)
}

/** One [PhotoController] for the screen, bound to the photo of [phase] whenever it changes. */
@Composable
fun rememberPhotoController(phase: EditorPhase): PhotoController {
    val controller = rememberSaveable(saver = PhotoController.Saver) { PhotoController() }
    val ready = phase as? EditorPhase.Ready
    remember(ready?.renderer, ready?.width, ready?.height) {
        controller.bind(ready)
        ready
    }
    return controller
}

/**
 * The full-screen GPU surface of [renderer] (the [PhotoController]'s), drawn behind the editor chrome. Reports frames
 * to [EditorMetrics] and the first presented frame through [onFirstFrame] (main thread).
 */
@Composable
fun PhotoSurface(renderer: GpuDevelopRenderer?, onFirstFrame: () -> Unit, modifier: Modifier = Modifier) {
    if (renderer == null) return
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val currentRenderer = rememberUpdatedState(renderer)
    LaunchedEffect(renderer) { renderer.setSurround(EditorNeutrals.Surround.toArgb() and RGB_MASK) }
    DisposableEffect(renderer) {
        val main = Handler(Looper.getMainLooper())
        val first = AtomicBoolean(true)
        renderer.onFrame = { stats ->
            EditorMetrics.frame(stats)
            if (first.getAndSet(false)) main.post { currentOnFirstFrame() }
        }
        onDispose {
            renderer.onFrame = null
            renderer.setShowOriginal(false)
            renderer.setLoupe(null)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            currentRenderer.value.attachSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            currentRenderer.value.invalidate()
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            currentRenderer.value.detachSurface()
                        }
                    },
                )
            }
        },
    )
}

private const val RGB_MASK = 0xFFFFFF
