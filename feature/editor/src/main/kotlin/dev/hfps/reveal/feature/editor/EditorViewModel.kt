package dev.hfps.reveal.feature.editor

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.image.proxy.ProxyStep
import dev.hfps.image.source.CancelToken
import dev.hfps.image.source.CancelledException
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.feature.editor.open.OpenEvent
import dev.hfps.reveal.feature.editor.open.OpenException
import dev.hfps.reveal.feature.editor.open.PhotoOpener
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the photo is in its life on screen. */
sealed interface EditorPhase {
    /** Decoding: [progress] 0..1 over the proxy steps. */
    data class Loading(val progress: Float) : EditorPhase

    /** The proxy is on the GPU; [renderer] draws it. [hundredPercentScale] = full-size pixels per proxy pixel. */
    data class Ready(
        val renderer: GpuDevelopRenderer,
        val width: Int,
        val height: Int,
        val hundredPercentScale: Float,
    ) : EditorPhase

    data class Failed(val error: OpenError) : EditorPhase
}

/** The M0 debug controls: exposure and white balance. `null` temperature/tint = as shot. */
data class DevelopControls(
    val exposureEv: Float = 0f,
    val temperatureK: Float? = null,
    val tint: Float? = null,
    val asShotTemperatureK: Float = DEFAULT_TEMPERATURE,
    val asShotTint: Float = 0f,
) {
    val recipe: Recipe
        get() = Recipe(
            light = Light(exposureEv = exposureEv),
            colour = Colour(WhiteBalance(temperatureK = temperatureK, tint = tint)),
        )

    companion object {
        const val DEFAULT_TEMPERATURE = 6500f
    }
}

data class EditorUiState(
    val fileName: String = "",
    /** The instant preview shown until the first developed frame, then dropped. */
    val preview: ImageBitmap? = null,
    val phase: EditorPhase = EditorPhase.Loading(0f),
    val controls: DevelopControls = DevelopControls(),
    /** True once a developed frame is on screen (the preview fades out). */
    val developed: Boolean = false,
)

/**
 * The editor of one photo (M0 shape): shows the embedded preview immediately, decodes the screen proxy off the main
 * thread with progress, uploads it to the GPU renderer (dropping the CPU copy), and keeps the recipe of the debug
 * controls. Survives configuration changes; leaving the screen cancels the decode and closes the renderer.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
) : ViewModel() {
    private val route: EditorRoute = savedStateHandle.toRoute<EditorRoute>()
    private val cancel = CancelToken()
    private val opener = PhotoOpener(context.contentResolver)

    init {
        EditorMetrics.start()
    }

    /** The renderer (GL thread, context, shaders) starts while the file decodes. */
    private val gpu: Deferred<GpuDevelopRenderer> = viewModelScope.async(Dispatchers.Default) {
        GpuDevelopRenderer.create().also {
            it.measureGpuTime = EditorMetrics.measureGpuTime
            EditorMetrics.gpuReady()
        }
    }

    private val _state =
        MutableStateFlow(EditorUiState(fileName = route.uri?.let { opener.displayNameOrEmpty(it.toUri()) }.orEmpty()))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        open()
    }

    private fun open() {
        val metrics = context.resources.displayMetrics
        val screenLong = maxOf(metrics.widthPixels, metrics.heightPixels)
        val proxyLongEdge = screenLong.coerceIn(MIN_PROXY_EDGE, MAX_PROXY_EDGE)
        viewModelScope.launch {
            try {
                val uri = resolveUri()
                val photo = withContext(Dispatchers.IO) {
                    opener.open(uri, proxyLongEdge, screenLong, cancel, ::onEvent)
                }
                EditorMetrics.proxy()
                val asShot = WhiteBalanceModel.asShot(photo.input)
                val ready = gpu.await()
                ready.upload(photo.input)
                EditorMetrics.uploaded()
                EditorMetrics.renderer = ready
                val scale = maxOf(photo.fullWidth, photo.fullHeight).toFloat() /
                    maxOf(photo.input.width, photo.input.height)
                _state.update {
                    it.copy(
                        fileName = photo.name,
                        phase = EditorPhase.Ready(ready, photo.input.width, photo.input.height, scale),
                        controls = it.controls.copy(
                            asShotTemperatureK = asShot.temperatureK.roundToInt().toFloat(),
                            asShotTint = asShot.tint.roundToInt().toFloat(),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                cancel.cancel()
                throw e
            } catch (e: CancelledException) {
                // Leaving the screen: nothing to show.
                EditorMetrics.failed("cancelled: ${e.message}")
            } catch (e: OpenException) {
                fail(e.error, e.cause ?: e)
            } catch (e: OutOfMemoryError) {
                fail(OpenError.OUT_OF_MEMORY, e)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // GPU upload failures and anything unexpected: a friendly state, never a crash.
                fail(PhotoOpener.classify(e), e)
            }
        }
        viewModelScope.launch {
            // A GPU that cannot run the pipeline is an error even while the decode is still running.
            runCatching { gpu.await() }.exceptionOrNull()?.let { failure ->
                if (failure is CancellationException) return@launch
                cancel.cancel()
                _state.update { it.copy(phase = EditorPhase.Failed(PhotoOpener.classify(failure)), preview = null) }
            }
        }
    }

    /**
     * The document to open: the route's URI, or for a library version the RAW of its photo (a pair's JPEG is only the
     * first preview); a version that no longer exists is a lost file.
     */
    private suspend fun resolveUri(): Uri {
        route.uri?.let { return it.toUri() }
        val versionId = checkNotNull(route.versionId)
        val target = library.editTarget(versionId) ?: throw OpenException(OpenError.PERMISSION_LOST)
        _state.update { it.copy(fileName = target.name) }
        return target.uri
    }

    private fun fail(error: OpenError, cause: Throwable) {
        EditorMetrics.failed("$error: $cause")
        _state.update { it.copy(phase = EditorPhase.Failed(error), preview = null) }
    }

    private fun onEvent(event: OpenEvent) {
        when (event) {
            is OpenEvent.Identified -> _state.update { it.copy(fileName = event.name) }

            is OpenEvent.Preview -> {
                val bitmap = event.bitmap.asImageBitmap()
                _state.update { if (it.phase is EditorPhase.Loading) it.copy(preview = bitmap) else it }
                EditorMetrics.preview()
            }

            is OpenEvent.Step -> {
                val progress = (event.step.ordinal + 1f) / (ProxyStep.entries.size + 1f)
                _state.update {
                    if (it.phase is EditorPhase.Loading) it.copy(phase = EditorPhase.Loading(progress)) else it
                }
                EditorMetrics.sampleMemory()
            }
        }
    }

    /** Called (main thread) once the first developed frame is presented: the preview fades out and is released. */
    fun onDeveloped() {
        _state.update { it.copy(developed = true) }
    }

    /** Called when the preview has finished fading out. */
    fun onPreviewHidden() {
        _state.update { it.copy(preview = null) }
    }

    fun setExposure(ev: Float) = updateControls { it.copy(exposureEv = ev) }

    fun setTemperature(kelvin: Float) = updateControls { it.copy(temperatureK = kelvin) }

    fun setTint(tint: Float) = updateControls { it.copy(tint = tint) }

    fun resetControls() = updateControls {
        DevelopControls(asShotTemperatureK = it.asShotTemperatureK, asShotTint = it.asShotTint)
    }

    private fun updateControls(change: (DevelopControls) -> DevelopControls) {
        _state.update { it.copy(controls = change(it.controls)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCleared() {
        cancel.cancel()
        EditorMetrics.renderer = null
        // Closes the renderer once it exists (a creation cancelled half-way closes itself).
        gpu.invokeOnCompletion { failure -> if (failure == null) gpu.getCompleted().close() }
    }

    private fun PhotoOpener.displayNameOrEmpty(uri: Uri): String = runCatching { displayName(uri) }.getOrDefault("")

    private companion object {
        /** Screen proxy bounds (ADR 0002 "Proxies and tiles"): about the screen's long edge. */
        const val MIN_PROXY_EDGE = 2400
        const val MAX_PROXY_EDGE = 3000
    }
}
