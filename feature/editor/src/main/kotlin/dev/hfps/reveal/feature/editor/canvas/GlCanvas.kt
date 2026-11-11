package dev.hfps.reveal.feature.editor.canvas

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.hfps.reveal.designsystem.theme.EditorNeutrals
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Recipe

/**
 * The editor's photo canvas: a [SurfaceView] fed by the GPU develop pipeline, with fit-to-screen, pinch zoom up to
 * 400 %, double-tap fit <-> 100 %, one-finger pan when zoomed with fling, and hold = show the original (recipe
 * defaults). No editor chrome: the caller overlays whatever controls it wants.
 *
 * Zoom and pan are handled in the shader (the [ViewTransform] of [ZoomState]), so gestures never recompose and the
 * UI thread never waits for the GPU. [recipe] changes are pushed to the renderer straight away; the renderer
 * coalesces them to the latest.
 *
 * @param renderer a started renderer (see [GpuDevelopRenderer.create]); the caller owns and closes it.
 * @param input the linear proxy shown, uploaded when it changes.
 * @param recipe the edit to render.
 * @param zoom zoom/pan state; hoist it with [rememberZoomState] to drive zoom from outside (buttons, tests).
 */
@Composable
fun ZoomableGlCanvas(
    renderer: GpuDevelopRenderer,
    input: PipelineInput,
    recipe: Recipe,
    modifier: Modifier = Modifier,
    zoom: ZoomState = rememberZoomState(renderer),
) {
    val scope = rememberCoroutineScope()
    val currentRenderer = rememberUpdatedState(renderer)
    LaunchedEffect(renderer, input) {
        renderer.setInput(input)
        zoom.setImageSize(input.width, input.height)
    }
    LaunchedEffect(renderer) { renderer.setSurround(EditorNeutrals.Surround.toArgb() and RGB_MASK) }
    LaunchedEffect(renderer, recipe) { renderer.setRecipe(recipe) }
    DisposableEffect(renderer) { onDispose { renderer.setShowOriginal(false) } }
    Box(
        modifier = modifier
            .background(EditorNeutrals.Surround)
            .canvasGestures(zoom, scope) { renderer.setShowOriginal(it) },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                currentRenderer.value.attachSurface(holder.surface)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                zoom.setViewSize(width, height)
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
}

/** A [ZoomState] wired to [renderer]; [hundredPercentScale] is screen pixels per proxy pixel at 100 %. */
@Composable
fun rememberZoomState(renderer: GpuDevelopRenderer, hundredPercentScale: Float = 1f): ZoomState =
    remember(renderer, hundredPercentScale) {
        ZoomState(hundredPercentScale = hundredPercentScale, onChange = renderer::setView)
    }

private const val RGB_MASK = 0xFFFFFF
