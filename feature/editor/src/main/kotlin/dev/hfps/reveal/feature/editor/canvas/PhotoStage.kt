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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.viewinterop.AndroidView
import dev.hfps.reveal.designsystem.theme.EditorNeutrals
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.Loupe
import dev.hfps.reveal.pipeline.gl.ViewFrame
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
class PhotoController(val renderer: GpuDevelopRenderer?, val zoom: ZoomState?) {
    private var root: LayoutCoordinates? = null

    /** The eyedropper's finger and loupe position, in slot pixels. */
    val loupe: LoupeState = LoupeState()

    /** The frame in surface (root) pixels; `null` until the slot is placed. */
    var frame: ViewFrame? by mutableStateOf(null)
        private set

    fun onRootPlaced(coordinates: LayoutCoordinates) {
        root = coordinates
    }

    fun onSlotPlaced(slot: LayoutCoordinates) {
        val r = root ?: return
        if (!r.isAttached || !slot.isAttached) return
        val b = r.localBoundingBoxOf(slot, clipBounds = false)
        val next = ViewFrame(b.left, b.top, b.width, b.height)
        if (next == frame) return
        frame = next
        renderer?.setFrame(next)
        zoom?.setViewSize(b.width.roundToInt(), b.height.roundToInt())
    }

    /** The proxy image pixel under slot point [p], or `null` outside the image. */
    fun imagePoint(p: Offset, imageWidth: Int, imageHeight: Int): Offset? {
        val z = zoom ?: return null
        val f = frame ?: return null
        val x = z.centerX + (p.x - f.width / 2f) / z.scale
        val y = z.centerY + (p.y - f.height / 2f) / z.scale
        return if (x in 0f..imageWidth.toFloat() && y in 0f..imageHeight.toFloat()) Offset(x, y) else null
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

    private companion object {
        const val LOUPE_ZOOM = 4f
        const val LOUPE_MAX_SCALE = 8f
    }
}

/** Where the eyedropper's finger and loupe are, in slot pixels ([finger] `null` = no finger down). */
@Stable
class LoupeState {
    var finger: Offset? by mutableStateOf(null)
    var at: Offset by mutableStateOf(Offset.Zero)
}

/** A [PhotoController] for [phase]: a zoom wired to the renderer once the photo is ready. */
@Composable
fun rememberPhotoController(phase: EditorPhase): PhotoController {
    val ready = phase as? EditorPhase.Ready
    val renderer = ready?.renderer
    return remember(renderer, ready?.width, ready?.height) {
        val zoom = renderer?.let { r ->
            ZoomState(hundredPercentScale = ready.hundredPercentScale, onChange = r::setView)
        }
        zoom?.setImageSize(ready.width, ready.height)
        PhotoController(renderer, zoom)
    }
}

/**
 * The full-screen GPU surface of the [controller]'s renderer, drawn behind the editor chrome. Reports frames to
 * [EditorMetrics] and the first presented frame through [onFirstFrame] (main thread).
 */
@Composable
fun PhotoSurface(controller: PhotoController, onFirstFrame: () -> Unit, modifier: Modifier = Modifier) {
    val renderer = controller.renderer ?: return
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
