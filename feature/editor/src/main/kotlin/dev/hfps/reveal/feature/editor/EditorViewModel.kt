package dev.hfps.reveal.feature.editor

import android.content.Context
import android.net.Uri
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
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.edit.RecipeControls
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.feature.editor.open.OpenEvent
import dev.hfps.reveal.feature.editor.open.OpenException
import dev.hfps.reveal.feature.editor.open.PhotoOpener
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.cpu.WhiteBalancePicker
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.WbMode
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The editor of one photo: shows the embedded preview immediately, decodes the screen proxy off the main thread with
 * progress, uploads it to the GPU renderer (dropping the CPU copy), and owns the edit (working and committed recipe)
 * and the view options, pushing both to the renderer. Survives configuration changes; leaving the screen cancels the
 * decode and closes the renderer.
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
                val input = photo.input
                val asShot = WhiteBalanceModel.asShot(input).let {
                    AsShot(it.temperatureK.roundToInt().toFloat(), it.tint.roundToInt().toFloat())
                }
                // Grey-world "Auto" white balance, measured while the proxy is still on the heap.
                val autoWb = withContext(Dispatchers.Default) { WhiteBalancePicker.grayWorld(input) }?.let {
                    AsShot(RecipeRanges.temperature.snap(it.temperatureK.toFloat()), it.tint.roundToInt().toFloat())
                }
                val ready = gpu.await()
                ready.upload(input)
                EditorMetrics.uploaded()
                EditorMetrics.renderer = ready
                photoInput = colourOnly(input)
                val scale = maxOf(photo.fullWidth, photo.fullHeight).toFloat() / maxOf(input.width, input.height)
                _state.update {
                    it.copy(
                        fileName = photo.name,
                        phase = EditorPhase.Ready(ready, input.width, input.height, scale),
                        edit = it.edit.copy(isRaw = input.isRaw, asShot = asShot, autoWb = autoWb),
                    )
                }
                attach(ready)
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

    // ------------------------------------------------------------------ edits

    /** Per drag frame: the working recipe changes and goes to the renderer (coalesced there); nothing is stored. */
    fun setControl(id: ControlId, value: Float) = edit { RecipeControls.set(it, id, value) }

    /**
     * Commits the working recipe (slider release, numeric input, chips, resets).
     * TODO(history worker): compute RecipeDiff.between(committed, working), append the step and store the recipe.
     */
    fun commit() {
        _state.update { it.copy(edit = it.edit.copy(committed = it.edit.recipe)) }
    }

    fun resetControl(id: ControlId) = editAndCommit { RecipeControls.reset(it, id) }

    fun resetGroup(group: RecipeGroup) = editAndCommit { group.reset(it) }

    fun setLook(look: BaseLook) = editAndCommit { RecipeControls.withLook(it, look) }

    fun setWbMode(mode: WbMode) = editAndCommit { RecipeControls.withWbMode(it, mode, _state.value.edit.autoWb) }

    // ------------------------------------------------------------------ view options

    fun setHistogram(on: Boolean) {
        updateView { it.copy(histogram = on) }
        renderer()?.histogramEnabled = on
    }

    fun setClipping(warnings: ClippingWarnings) {
        updateView { it.copy(clipping = warnings) }
        renderer()?.setClippingWarnings(warnings)
    }

    fun setCompare(mode: CompareMode) {
        updateView { it.copy(compare = mode, lastCompare = if (mode == CompareMode.Off) it.lastCompare else mode) }
        renderer()?.setCompare(mode)
    }

    fun setShowOriginal(show: Boolean) {
        if (_state.value.view.showOriginal == show) return
        updateView { it.copy(showOriginal = show) }
        renderer()?.setShowOriginal(show)
    }

    fun setEyedropper(on: Boolean) = updateView { it.copy(eyedropper = on) }

    /**
     * Eyedropper: reads the mean raw colour around the proxy pixel ([imageX], [imageY]) from the renderer's source
     * and sets the white balance that makes it neutral (Custom). Leaves the mode either way.
     */
    fun pickNeutral(imageX: Float, imageY: Float) {
        val renderer = renderer()
        val input = photoInput
        updateView { it.copy(eyedropper = false) }
        if (renderer == null || input == null) return
        viewModelScope.launch {
            val mean = renderer.sampleSource(imageX.roundToInt(), imageY.roundToInt(), EYEDROPPER_RADIUS)
            val cct = WhiteBalancePicker.fromNeutral(input, mean) ?: return@launch
            editAndCommit {
                val wb = it.colour.whiteBalance.copy(
                    temperatureK = RecipeRanges.temperature.snap(
                        cct.temperatureK.toFloat(),
                        RecipeRanges.temperature.fineStep,
                    ),
                    tint = cct.tint.roundToInt().toFloat(),
                    mode = WbMode.CUSTOM,
                )
                it.copy(colour = it.colour.copy(whiteBalance = wb))
            }
        }
    }

    private fun edit(change: (Recipe) -> Recipe) {
        val next = _state.updateAndGet { it.copy(edit = it.edit.copy(recipe = change(it.edit.recipe))) }
        renderer()?.setRecipe(next.edit.recipe)
    }

    private fun editAndCommit(change: (Recipe) -> Recipe) {
        edit(change)
        commit()
    }

    private fun updateView(change: (ViewOptions) -> ViewOptions) {
        _state.update { it.copy(view = change(it.view)) }
    }

    private fun renderer(): GpuDevelopRenderer? = (_state.value.phase as? EditorPhase.Ready)?.renderer

    /** Pushes the current edit and view options to a renderer that just became ready, and listens to histograms. */
    private fun attach(renderer: GpuDevelopRenderer) {
        val current = _state.value
        renderer.setRecipe(current.edit.recipe)
        renderer.setClippingWarnings(current.view.clipping)
        renderer.setCompare(current.view.compare)
        renderer.onHistogram = { histogram -> _state.update { it.copy(histogram = histogram) } }
        renderer.histogramEnabled = current.view.histogram
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCleared() {
        cancel.cancel()
        renderer()?.onHistogram = null
        EditorMetrics.renderer = null
        // Closes the renderer once it exists (a creation cancelled half-way closes itself).
        gpu.invokeOnCompletion { failure -> if (failure == null) gpu.getCompleted().close() }
    }

    private fun PhotoOpener.displayNameOrEmpty(uri: Uri): String = runCatching { displayName(uri) }.getOrDefault("")

    /** The proxy's colour metadata without its pixels (for the eyedropper maths); set once uploaded. */
    @Volatile
    private var photoInput: PipelineInput? = null

    private fun colourOnly(input: PipelineInput) = PipelineInput(
        input.kind,
        1,
        1,
        FloatArray(PipelineInput.CHANNELS),
        input.cameraToXyz,
        input.asShotMultipliers?.copyOf(),
        input.saturation,
    )

    private companion object {
        /** Screen proxy bounds (ADR 0002 "Proxies and tiles"): about the screen's long edge. */
        const val MIN_PROXY_EDGE = 2400
        const val MAX_PROXY_EDGE = 3000

        /** Half-size of the eyedropper's sampled square, in proxy pixels (a 9x9 average). */
        const val EYEDROPPER_RADIUS = 4
    }
}
