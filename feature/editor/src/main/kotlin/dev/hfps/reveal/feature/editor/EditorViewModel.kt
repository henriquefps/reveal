package dev.hfps.reveal.feature.editor

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
import dev.hfps.reveal.data.edit.EditClipboard
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.edit.HistoryOp
import dev.hfps.reveal.data.edit.HistoryWriter
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.edit.RecipeControls
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.feature.editor.open.OpenEvent
import dev.hfps.reveal.feature.editor.open.OpenException
import dev.hfps.reveal.feature.editor.open.PhotoOpener
import dev.hfps.reveal.feature.export.ui.ExportRequest
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.WbAnalysis
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.cpu.WhiteBalancePicker
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    private val edits: EditRepository,
    private val writer: HistoryWriter,
) : ViewModel() {
    private val route: EditorRoute = savedStateHandle.toRoute<EditorRoute>()

    /** The library version being edited; null for a photo opened from another app (history in memory only). */
    private val versionId: Long? = route.versionId
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

    /** The version's persisted history (after any write still queued from a previous visit). */
    private val storedHistory: Deferred<EditHistory> = viewModelScope.async {
        versionId?.let {
            writer.flush()
            edits.load(it)
        } ?: EditHistory()
    }

    /** The edit currently on clipboard (copied here or in the library). */
    @Volatile
    private var clipboard: EditClipboard? = null

    init {
        open()
        viewModelScope.launch {
            val history = storedHistory.await()
            val recipe = history.current
            _state.update { it.copy(edit = it.edit.copy(recipe = recipe, history = history)) }
            renderer()?.setRecipe(recipe)
        }
        viewModelScope.launch {
            edits.clipboard().collect { clip ->
                clipboard = clip
                _state.update { it.copy(edit = it.edit.copy(clipboard = clip?.recipe)) }
            }
        }
        viewModelScope.launch {
            val previous = edits.previous(versionId ?: NO_VERSION) != null
            _state.update { it.copy(edit = it.edit.copy(hasPrevious = previous)) }
        }
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
                val autoWb = prepareAuto(input)
                val ready = gpu.await()
                // The renderer starts on the stored recipe (attach() pushes it).
                storedHistory.await()
                val scale = maxOf(photo.fullWidth, photo.fullHeight).toFloat() / maxOf(input.width, input.height)
                ready.upload(input, scale)
                EditorMetrics.uploaded()
                EditorMetrics.renderer = ready
                EditorMetrics.setControl = { id, value ->
                    setControl(id, value)
                    commit()
                }
                EditorMetrics.recipes = { _state.value.edit.let { it.recipe to it.committed } }
                photoInput = colourOnly(input)
                _state.update {
                    it.copy(
                        fileName = photo.name,
                        phase = EditorPhase.Ready(ready, input.width, input.height, scale),
                        edit = it.edit.copy(isRaw = input.isRaw, asShot = asShot, autoWb = autoWb, autoReady = true),
                    )
                }
                attach(ready)
                if (scale > 1f) loadFullResolution(uri, ready, scale)
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
     * Decodes the photo's full resolution in the background once the proxy is on screen and hands it to the renderer,
     * whose zoom then shows developed full-resolution tiles above 50 % (docs/adr/0012). Best effort: a failure (memory,
     * an unreadable region) leaves the proxy-only zoom of M1.
     */
    private fun loadFullResolution(uri: Uri, renderer: GpuDevelopRenderer, scale: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            val source = try {
                opener.openFullResolution(uri, cancel)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Includes OutOfMemoryError: the editor keeps working on the proxy.
                android.util.Log.w("RevealEditor", "full resolution unavailable: $e")
                return@launch
            }
            if (!renderer.setFullResolution(source, scale)) {
                source.close()
                return@launch
            }
            EditorMetrics.fullResolution(SystemClock.elapsedRealtime() - start, source.width, source.height)
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
     * Commits the working recipe (slider release, numeric input, chips, resets): one history step labelled with its
     * `RecipeDiff` (docs/adr/0003), saved to Room through the [HistoryWriter]. Nothing user-visible changed = no
     * step, and the working recipe snaps back to the committed one.
     */
    fun commit() = commitWith(null)

    fun resetControl(id: ControlId) = editAndCommit { RecipeControls.reset(it, id) }

    fun resetGroup(group: RecipeGroup) = editAndCommit(HistoryAction.RESET_GROUP, listOf(group)) { group.reset(it) }

    /** Every group back to "no change" (one step). */
    fun resetAll() = editAndCommit(HistoryAction.RESET_ALL) { Recipe.DEFAULT }

    /** Back to the recipe the photo opens with: the default recipe plus the user's default settings (one step). */
    fun resetAsOpened() = editAndCommit(HistoryAction.AS_OPENED) { asOpened() }

    // ------------------------------------------------------------------ history

    fun undo() = move { it.undo() }

    fun redo() = move { it.redo() }

    /** The history list: jumps to the step at [position] (-1 = the original); later steps stay for redo. */
    fun goTo(position: Int) = move { it.goTo(position) }

    private fun move(change: (EditHistory) -> EditHistory?) {
        val history = change(_state.value.edit.history) ?: return
        show(history.current, history)
        versionId?.let { writer.write(HistoryOp.Move(it, history.cursor)) }
    }

    // ------------------------------------------------------------------ copy / paste

    /** Copies the whole edit (the paste chooser picks the groups). */
    fun copy() {
        val recipe = _state.value.edit.committed
        viewModelScope.launch { edits.copy(recipe) }
    }

    /** Opens the paste chooser on the last choice of groups. */
    fun openPaste() {
        if (clipboard == null) return
        viewModelScope.launch {
            val groups = edits.pasteGroups()
            _state.update { it.copy(edit = it.edit.copy(pasteChooser = groups)) }
        }
    }

    fun setPasteGroups(groups: Set<RecipeGroup>) {
        _state.update { s -> s.copy(edit = s.edit.copy(pasteChooser = groups.takeIf { s.edit.pasteChooser != null })) }
    }

    fun dismissPaste() = _state.update { it.copy(edit = it.edit.copy(pasteChooser = null)) }

    /** Pastes the chosen groups of the clipboard onto this photo: one step. */
    fun confirmPaste() {
        val groups = _state.value.edit.pasteChooser ?: return
        val clip = clipboard
        dismissPaste()
        if (clip == null || groups.isEmpty()) return
        viewModelScope.launch { edits.setPasteGroups(groups) }
        editAndCommit(HistoryAction.PASTE, RecipeGroup.entries.filter { it in groups }) { clip.pasteOnto(it, groups) }
    }

    /** "Previous": the last edited photo's recipe (everything but geometry) applied as one step. */
    fun previous() {
        viewModelScope.launch {
            val previous = edits.previous(versionId ?: NO_VERSION) ?: return@launch
            val groups = RecipePaste.DEFAULT_GROUPS
            editAndCommit(HistoryAction.PREVIOUS, RecipeGroup.entries.filter { it in groups }) {
                RecipePaste.paste(previous, it, groups)
            }
        }
    }

    /**
     * The recipe a photo opens with ("as opened from camera"): the default recipe. TODO(settings worker): apply the
     * user's default settings (default profile, open with Auto) here once Settings stores them.
     */
    private fun asOpened(): Recipe = Recipe.DEFAULT

    fun setLook(look: BaseLook) = editAndCommit { RecipeControls.withLook(it, look) }

    /**
     * One-tap Auto (docs/adr/0010): analyses the photo as the current profile and white balance render it (off the
     * main thread, on the CPU reference, so the result is the same on every device), then sets the sliders of
     * [scope] as one history step. Idempotent: the analysis ignores the values Auto sets. Taps while a run is in
     * flight are ignored.
     */
    fun auto(scope: AutoScope) {
        val inputs = autoInputs ?: return
        if (autoJob?.isActive == true) return
        val base = _state.value.edit.recipe
        autoJob = viewModelScope.launch {
            val next = withContext(Dispatchers.Default) {
                val start = SystemClock.elapsedRealtime()
                AutoRules.run(inputs.sample, base, scope, inputs.wb).also {
                    EditorMetrics.auto(scope.name, SystemClock.elapsedRealtime() - start)
                }
            }
            val groups = if (scope ==
                AutoScope.ALL
            ) {
                listOf(RecipeGroup.LIGHT, RecipeGroup.COLOUR)
            } else {
                listOf(RecipeGroup.LIGHT)
            }
            editAndCommit(HistoryAction.AUTO, groups) { current ->
                val lit = current.copy(light = next.light)
                if (scope == AutoScope.LIGHT) {
                    lit
                } else {
                    lit.copy(
                        colour = lit.colour.copy(
                            vibrance = next.colour.vibrance,
                            whiteBalance = next.colour.whiteBalance,
                        ),
                    )
                }
            }
        }
    }

    fun setWbMode(mode: WbMode) = editAndCommit { RecipeControls.withWbMode(it, mode, _state.value.edit.autoWb) }

    /** What the export sheet exports: this photo with the recipe on screen; null until the photo is open. */
    fun exportRequest(): ExportRequest? {
        val current = _state.value
        if (!current.ready) return null
        return ExportRequest.Current(route.versionId, route.uri, current.fileName, current.edit.recipe)
    }

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

    /** The Detail panel's masking overlay: the photo shows the sharpening mask while Masking is dragged. */
    fun setDetailMask(show: Boolean) {
        renderer()?.setDetailMask(show)
    }

    // ------------------------------------------------------------------ crop mode

    /** Crop mode gesture frame: the working geometry changes; nothing is committed until Done. */
    fun setGeometry(geometry: Geometry) = edit { it.copy(geometry = geometry) }

    /** The Crop mode opens or closes: the renderer shows the editing plane while it is open. */
    fun setCropMode(open: Boolean) {
        if (_state.value.view.cropping == open) return
        updateView { it.copy(cropping = open) }
        renderer()?.setGeometryEditing(open)
    }

    /** Done: everything changed in the mode becomes one committed step (one history entry). */
    fun cropDone() {
        commit()
        setCropMode(false)
    }

    /** Cancel / back: the geometry returns to the committed one (the mode never commits until Done). */
    fun cropCancel() {
        edit { it.copy(geometry = _state.value.edit.committed.geometry) }
        setCropMode(false)
    }

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

    private fun editAndCommit(
        action: HistoryAction? = null,
        groups: List<RecipeGroup> = emptyList(),
        change: (Recipe) -> Recipe,
    ) {
        edit(change)
        commitWith(action, groups)
    }

    private fun commitWith(action: HistoryAction?, groups: List<RecipeGroup> = emptyList()) {
        val edit = _state.value.edit
        val history = edit.history.commit(edit.recipe, action, groups)
        if (history == null) {
            if (edit.recipe != edit.committed) show(edit.committed, edit.history)
            return
        }
        _state.update { it.copy(edit = it.edit.copy(history = history)) }
        versionId?.let { writer.write(HistoryOp.Commit(it, history.current, history.entries[history.cursor].label)) }
    }

    /** Shows [recipe] as both the working and the committed recipe of [history]. */
    private fun show(recipe: Recipe, history: EditHistory) {
        _state.update { it.copy(edit = it.edit.copy(recipe = recipe, history = history)) }
        renderer()?.setRecipe(recipe)
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
        renderer.setGeometryEditing(current.view.cropping)
        renderer.onHistogram = { histogram -> _state.update { it.copy(histogram = histogram) } }
        renderer.histogramEnabled = current.view.histogram
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCleared() {
        cancel.cancel()
        renderer()?.onHistogram = null
        EditorMetrics.renderer = null
        EditorMetrics.setControl = null
        EditorMetrics.recipes = null
        // Closes the renderer once it exists (a creation cancelled half-way closes itself).
        gpu.invokeOnCompletion { failure -> if (failure == null) gpu.getCompleted().close() }
    }

    private fun PhotoOpener.displayNameOrEmpty(uri: Uri): String = runCatching { displayName(uri) }.getOrDefault("")

    /**
     * Auto's inputs (ADR 0008/0010), taken while the proxy is still on the heap: the Auto WB analysis and a small
     * point-sampled copy for the tone analysis (about 2 MB; the proxy itself is dropped after the upload). Returns the
     * Auto white balance for the chip, `null` when it cannot be estimated.
     */
    private suspend fun prepareAuto(input: PipelineInput): WhiteBalance? {
        val (wbAnalysis, sample) = withContext(Dispatchers.Default) {
            runCatching { AutoWhiteBalance.analyse(input) }.getOrNull() to AutoAnalyser.sample(input)
        }
        autoInputs = AutoInputs(sample, wbAnalysis)
        return wbAnalysis?.let { runCatching { AutoRules.whiteBalance(it) }.getOrNull() }
    }

    /** What Auto needs once the proxy is gone: the analysis sample and the Auto WB analysis (null if it failed). */
    private class AutoInputs(val sample: PipelineInput, val wb: WbAnalysis?)

    @Volatile
    private var autoInputs: AutoInputs? = null

    private var autoJob: Job? = null

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

        /** "No version" for queries that exclude the current one. */
        const val NO_VERSION = -1L
    }
}
