package dev.hfps.reveal.feature.editor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.image.proxy.ProxySource
import dev.hfps.image.proxy.ProxyStep
import dev.hfps.image.source.CancelToken
import dev.hfps.image.source.CancelledException
import dev.hfps.reveal.data.edit.EditClipboard
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.edit.HistoryOp
import dev.hfps.reveal.data.edit.HistoryWriter
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.data.handoff.HandoffApps
import dev.hfps.reveal.data.handoff.HandoffPreviewer
import dev.hfps.reveal.data.handoff.SenderApp
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.preset.PresetEvent
import dev.hfps.reveal.data.preset.PresetFilesIo
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.sync.RemoteEdits
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.edit.OpticsAnalysis
import dev.hfps.reveal.feature.editor.edit.RecipeControls
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.feature.editor.open.OpenEvent
import dev.hfps.reveal.feature.editor.open.OpenException
import dev.hfps.reveal.feature.editor.open.OpenedPhoto
import dev.hfps.reveal.feature.editor.open.PhotoOpener
import dev.hfps.reveal.feature.editor.presets.CreatePresetDraft
import dev.hfps.reveal.feature.editor.presets.PresetSession
import dev.hfps.reveal.feature.editor.presets.PresetSteps
import dev.hfps.reveal.feature.editor.presets.PresetThumbnails
import dev.hfps.reveal.feature.editor.presets.PreviewInput
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.feature.export.ui.ExportRequest
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoBlackWhite
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.auto.SourceColours
import dev.hfps.reveal.pipeline.auto.TargetedAdjust
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.WbAnalysis
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.cpu.WhiteBalancePicker
import dev.hfps.reveal.pipeline.geometry.ImageLines
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.input.colourOnly
import dev.hfps.reveal.pipeline.preset.ApplyContext
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.preset.SmartCorrection
import dev.hfps.reveal.pipeline.preset.SmartCorrector
import dev.hfps.reveal.pipeline.preset.SmartRules
import dev.hfps.reveal.pipeline.preset.smartRules
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ColourToolControls
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GuideLine
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.UprightMode
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
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
@Suppress("LargeClass")
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val edits: EditRepository,
    private val writer: HistoryWriter,
    private val presets: PresetRepository,
    private val smartCorrection: SmartCorrection,
    private val handoffPreviews: HandoffPreviewer,
) : ViewModel() {
    private val route: EditorRoute = savedStateHandle.toRoute<EditorRoute>()

    /** The tool the editor opens with: the route's (kept along the filmstrip), else Light. */
    val initialTool: ToolId = route.tool?.let { name -> ToolId.entries.firstOrNull { it.name == name } } ?: ToolId.LIGHT

    /** The route of another photo of the same filmstrip, opening on [tool]. */
    fun routeFor(versionId: Long, tool: ToolId): EditorRoute =
        EditorRoute(versionId = versionId, strip = route.strip, tool = tool.name)

    /** The library version being edited; null for a photo opened from another app (history in memory only). */
    private val versionId: Long? = route.versionId
    private val cancel = CancelToken()
    private val opener = PhotoOpener(context.contentResolver)

    init {
        handoffPreviews.editorOpened()
    }

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
        versionId?.let { id ->
            viewModelScope.launch { RemoteEdits.versions.filter { it == id }.collect { reloadRemote(id) } }
        }
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
            presets.library().collect { library ->
                _state.update { it.copy(presets = it.presets.copy(library = library)) }
            }
        }
        viewModelScope.launch {
            val previous = edits.previous(versionId ?: NO_VERSION) != null
            _state.update { it.copy(edit = it.edit.copy(hasPrevious = previous)) }
        }
    }

    @Suppress("LongMethod")
    private fun open() {
        val metrics = context.resources.displayMetrics
        val screenLong = maxOf(metrics.widthPixels, metrics.heightPixels)
        val proxyLongEdge = screenLong.coerceIn(MIN_PROXY_EDGE, MAX_PROXY_EDGE)
        viewModelScope.launch {
            var opening: Opening? = null
            try {
                opening = resolveTarget()
                val (photo, uri) = withContext(Dispatchers.IO) { openWithFallback(opening, proxyLongEdge, screenLong) }
                EditorMetrics.proxy()
                val previewOnly = previewOnly(photo, opening)
                val input = photo.input
                val asShot = WhiteBalanceModel.asShot(input).let {
                    AsShot(it.temperatureK.roundToInt().toFloat(), it.tint.roundToInt().toFloat())
                }
                val autoWb = prepareAuto(input)
                thumbnails = withContext(Dispatchers.Default) {
                    val small = PreviewInput.of(input)
                    val full = maxOf(photo.fullWidth, photo.fullHeight).toFloat() / maxOf(small.width, small.height)
                    PresetThumbnails(small, full.coerceAtLeast(1f))
                }
                prepareOptics(input)
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
                EditorMetrics.history = { _state.value.edit.history }
                photoInput = colourOnly(input)
                _state.update {
                    it.copy(
                        fileName = photo.name,
                        previewOnly = previewOnly,
                        phase = EditorPhase.Ready(ready, input.width, input.height, scale),
                        edit = it.edit.copy(
                            isRaw = input.isRaw,
                            asShot = asShot,
                            autoWb = autoWb,
                            autoReady = true,
                            lens = input.lens,
                        ),
                    )
                }
                attach(ready)
                applyLensDefault(input)
                val full = photo.fullWidth to photo.fullHeight
                // After the full resolution, so the two never need the heap at once.
                if (scale > 1f) {
                    loadFullResolution(uri, ready, scale) { keepSmartPreview(input, full) }
                } else {
                    keepSmartPreview(input, full)
                }
            } catch (e: CancellationException) {
                cancel.cancel()
                throw e
            } catch (e: CancelledException) {
                // Leaving the screen: nothing to show.
                EditorMetrics.failed("cancelled: ${e.message}")
            } catch (e: OpenException) {
                fail(e.error, e.cause ?: e, opening?.unreadable?.takeIf { e.error == OpenError.PERMISSION_LOST })
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
    private fun loadFullResolution(uri: Uri, renderer: GpuDevelopRenderer, scale: Float, then: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                fullResolution(uri, renderer, scale)
            } finally {
                then()
            }
        }
    }

    /**
     * A hand-off's smart preview from the input just opened (ADR 0011 "Unreadable hand-offs"), so it can still be
     * edited once the sender's grant ends, without decoding the original a second time.
     */
    private fun keepSmartPreview(input: PipelineInput, full: Pair<Int, Int>) {
        val version = versionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                library.keepSmartPreview(version) { jpeg, out ->
                    SmartPreviewCodec.encode(input, jpeg, full.first, full.second, out)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("RevealEditor", "no smart preview kept: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun fullResolution(uri: Uri, renderer: GpuDevelopRenderer, scale: Float) {
        val start = SystemClock.elapsedRealtime()
        val source = try {
            opener.openFullResolution(uri, cancel, _state.value.edit.lens)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Includes OutOfMemoryError: the editor keeps working on the proxy.
            android.util.Log.w("RevealEditor", "full resolution unavailable: $e")
            return
        }
        if (!renderer.setFullResolution(source, scale)) {
            source.close()
            return
        }
        EditorMetrics.fullResolution(SystemClock.elapsedRealtime() - start, source.width, source.height)
    }

    /**
     * What to open: [uri], else [fallback] when [uri] fails (a hand-off's server original while the server cannot be
     * reached: its smart preview); [handoff] when the photo is a hand-off Reveal can no longer read; [unreadable] the
     * hand-off document when there is nothing in its place (ADR 0011 "Fallbacks").
     */
    private class Opening(val uri: Uri, val fallback: Uri?, val handoff: Boolean, val unreadable: Uri?)

    /**
     * The document to open: the route's URI, or for a library version the RAW of its photo (a pair's JPEG is only the
     * first preview); a version that no longer exists is a lost file.
     */
    private suspend fun resolveTarget(): Opening {
        route.uri?.let { return Opening(it.toUri(), null, false, null) }
        val versionId = checkNotNull(route.versionId)
        val target = library.editTarget(versionId) ?: throw OpenException(OpenError.PERMISSION_LOST)
        _state.update { it.copy(fileName = target.name) }
        return Opening(target.uri, target.fallback, target.unreadableHandoff, target.unreadable)
    }

    /**
     * Opens [opening]'s document, or its fallback when that fails (a hand-off's server original the server cannot serve
     * now: its smart preview, ADR 0011 "Fallbacks"). Returns the photo and the URI it came from.
     */
    private fun openWithFallback(opening: Opening, proxyLongEdge: Int, screenLong: Int): Pair<OpenedPhoto, Uri> = try {
        opener.open(opening.uri, proxyLongEdge, screenLong, cancel, ::onEvent) to opening.uri
    } catch (e: CancelledException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        val fallback = opening.fallback ?: throw e
        android.util.Log.i("RevealEditor", "original unavailable, opening the preview: $e")
        opener.open(fallback, proxyLongEdge, screenLong, cancel, ::onEvent) to fallback
    }

    /** Whether the photo opened from a smart preview, and why (the editor's note). */
    private fun previewOnly(photo: OpenedPhoto, opening: Opening): PreviewOnly? = when {
        photo.stats.source != ProxySource.SMART_PREVIEW -> null
        opening.handoff -> PreviewOnly.HANDOFF
        else -> PreviewOnly.ELSEWHERE
    }

    private fun fail(error: OpenError, cause: Throwable, unreadable: Uri? = null) {
        EditorMetrics.failed("$error: $cause")
        val handoff = unreadable?.let(::unreadableHandoff)
        _state.update { it.copy(phase = EditorPhase.Failed(error, handoff), preview = null) }
    }

    /** What the error of an unreadable hand-off offers: the sending app, gallery access for a MediaStore URI. */
    private fun unreadableHandoff(uri: Uri): UnreadableHandoff {
        sender = HandoffApps.sender(context.packageManager, uri, context.packageName)
        val gallery = GalleryAccess.isMediaStore(uri.toString()) &&
            GalleryAccess.stateOf(Build.VERSION.SDK_INT) {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            } != GalleryAccessState.FULL
        return UnreadableHandoff(app = sender?.label, gallery = gallery)
    }

    /** The app that shared the photo shown in the error, if known. */
    private var sender: SenderApp? = null

    /** Opens the app that shared an unreadable hand-off (the error's action), so the owner can share it again. */
    fun openSender() {
        val app = sender ?: return
        runCatching { context.startActivity(Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** After gallery access was granted: opens the photo again (its MediaStore URI may be readable now). */
    fun retryOpen() {
        if (_state.value.phase !is EditorPhase.Failed) return
        _state.update { it.copy(phase = EditorPhase.Loading(0f)) }
        open()
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
            val features = photoFeatures(inputs)
            val next = withContext(Dispatchers.Default) {
                val start = SystemClock.elapsedRealtime()
                AutoRules.run(features, base, scope).also {
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

    // ------------------------------------------------------------------ colour tools (docs/adr/0013)

    /** Per drag frame: several controls at once (a grading wheel's hue and saturation); not committed. */
    fun setControls(values: Map<ControlId, Float>) =
        edit { values.entries.fold(it) { r, (id, v) -> RecipeControls.set(r, id, v) } }

    /** Per drag frame: one point curve's points; not committed (the editor commits on release). */
    fun setCurve(channel: CurveChannel, points: List<CurvePoint>) =
        edit { RecipeControls.withCurve(it, channel, points) }

    /** A point-curve preset on the RGB curve, one step. */
    fun applyCurvePreset(points: List<CurvePoint>) =
        editAndCommit(HistoryAction.CURVE_PRESET, listOf(RecipeGroup.LIGHT)) {
            RecipeControls.withCurve(it, CurveChannel.RGB, points)
        }

    /** One committed step from [change] (a sub-panel's reset). */
    fun editAndCommitStep(change: (Recipe) -> Recipe) = editAndCommit(change = change)

    fun setTreatment(treatment: Treatment) = editAndCommit { RecipeControls.withTreatment(it, treatment) }

    /** Black & white Auto mix: the grey mixer from the photo's colours as the recipe shows them, one step. */
    fun autoMix() {
        if (autoMixJob?.isActive == true) return
        autoMixJob = viewModelScope.launch {
            val colours = sourceColours() ?: return@launch
            val mix = withContext(Dispatchers.Default) { AutoBlackWhite.mix(colours) }
            editAndCommit(HistoryAction.AUTO_MIX, listOf(RecipeGroup.COLOUR)) {
                it.copy(colour = it.colour.copy(greyMixer = mix))
            }
        }
    }

    /** Turns the targeted adjustment on for [target] (off for `null`); prepares the colours it reads. */
    fun setTargeted(target: TargetedTarget?) {
        updateView { it.copy(targeted = target, eyedropper = if (target != null) false else it.eyedropper) }
        if (target == null) targetedHover(null)
        if (target != null) viewModelScope.launch { sourceColours() }
    }

    /**
     * A targeted drag starts at the proxy image point ([imageX], [imageY]): the colour there (before the mixer, so
     * a hue already moved still picks its own band) chooses the bands and their shares ([TargetedAdjust]).
     */
    fun targetedStart(imageX: Float, imageY: Float) {
        val target = _state.value.view.targeted ?: return
        val ready = _state.value.phase as? EditorPhase.Ready ?: return
        val drag = TargetedDrag(_state.value.edit.recipe, target)
        targeted = drag
        viewModelScope.launch {
            val colours = sourceColours()
            val sx = (imageX * colours?.width.orZero() / ready.width).toInt()
            val sy = (imageY * colours?.height.orZero() / ready.height).toInt()
            val weights = colours?.let { TargetedAdjust.weights(it.mean(sx, sy, TARGETED_RADIUS)) }
            drag.weights = weights ?: FloatArray(ColourBand.entries.size)
            _state.update { it.copy(edit = it.edit.copy(targetedBand = weights?.let(TargetedAdjust::lead))) }
            applyTargeted(drag)
            if (drag.ended) finishTargeted(drag)
        }
    }

    /** The targeted drag moved [dpUp] dp up from its start (negative = down). */
    fun targetedDrag(dpUp: Float) {
        val drag = targeted ?: return
        drag.dpUp = dpUp
        applyTargeted(drag)
    }

    /** The targeted drag ended: one step (nothing when the colour had no band). */
    fun targetedEnd() {
        val drag = targeted ?: return
        drag.ended = true
        if (drag.weights != null) finishTargeted(drag)
    }

    /**
     * A pen hovers the photo while the targeted adjustment is on: the band under it ([EditState.hoverBand], for the
     * tooltip), read like a drag's start; `null` [point] (the pen left) clears it.
     */
    fun targetedHover(point: Offset?) {
        hoverJob?.cancel()
        val ready = _state.value.phase as? EditorPhase.Ready
        if (point == null || ready == null || _state.value.view.targeted == null) {
            if (_state.value.edit.hoverBand != null) _state.update { it.copy(edit = it.edit.copy(hoverBand = null)) }
            return
        }
        hoverJob = viewModelScope.launch {
            val colours = sourceColours() ?: return@launch
            val sx = (point.x * colours.width / ready.width).toInt()
            val sy = (point.y * colours.height / ready.height).toInt()
            val band = TargetedAdjust.weights(colours.mean(sx, sy, TARGETED_RADIUS))?.let(TargetedAdjust::lead)
            _state.update { it.copy(edit = it.edit.copy(hoverBand = band)) }
        }
    }

    private var hoverJob: Job? = null

    private fun applyTargeted(drag: TargetedDrag) {
        val weights = drag.weights ?: return
        if (targeted !== drag) return
        val deltas = TargetedAdjust.deltas(weights, drag.dpUp)
        val property = drag.target.property
        edit {
            if (property == null) {
                ColourToolControls.nudgeGrey(drag.start, deltas)
            } else {
                ColourToolControls.nudgeMixer(drag.start, property, deltas)
            }
        }
    }

    private fun finishTargeted(drag: TargetedDrag) {
        if (targeted !== drag) return
        targeted = null
        _state.update { it.copy(edit = it.edit.copy(targetedBand = null)) }
        commit()
    }

    /**
     * The colours the colour tools see ([SourceColours]) for the current recipe, rendered on the CPU from Auto's
     * analysis sample and cached until something before the colour tools changes; `null` before the photo is open.
     */
    @Suppress("ReturnCount")
    private suspend fun sourceColours(): SourceColours? {
        val sample = autoInputs?.sample ?: return null
        val key = SourceColours.sourceRecipe(_state.value.edit.recipe).copy(geometry = Geometry())
        sourceCache?.let { (k, v) -> if (k == key) return v }
        val colours = withContext(Dispatchers.Default) { SourceColours.render(sample, key) }
        sourceCache = key to colours
        return colours
    }

    private fun Int?.orZero(): Int = this ?: 0

    /** One targeted drag: the recipe it started from, the bands' shares (null until the colour is read). */
    private class TargetedDrag(val start: Recipe, val target: TargetedTarget) {
        var weights: FloatArray? = null
        var dpUp: Float = 0f
        var ended: Boolean = false
    }

    private var targeted: TargetedDrag? = null
    private var sourceCache: Pair<Recipe, SourceColours>? = null
    private var autoMixJob: Job? = null

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

    fun setEyedropper(on: Boolean) = updateView { it.copy(eyedropper = on, targeted = if (on) null else it.targeted) }

    /** The Detail panel's masking overlay: the photo shows the sharpening mask while Masking is dragged. */
    fun setDetailMask(show: Boolean) {
        renderer()?.setDetailMask(show)
    }

    // ------------------------------------------------------------------ optics and transform (docs/adr/0015)

    /**
     * A new photo (no stored history) with a lens profile starts with its corrections on, as one undoable step
     * (`HistoryAction.LENS_PROFILE`); photos edited before keep their recipe (docs/adr/0015, "Defaults").
     */
    private fun applyLensDefault(input: PipelineInput) {
        val lens = input.lens ?: return
        val edit = _state.value.edit
        val optics = Optics.forNewPhoto(hasProfile = true, profileHasCa = lens.hasChromatic, isRaw = input.isRaw)
        if (edit.history.entries.isEmpty() && optics != edit.recipe.optics) {
            // Only onto a still empty stored history: another device's edit synced meanwhile wins (FIX/W).
            edit { it.copy(optics = optics) }
            commitWith(HistoryAction.LENS_PROFILE, onlyIfEmpty = true)
        }
    }

    /** Lines and (without profile CA data) the CA estimate, off the main thread while the proxy is on the heap. */
    private fun prepareOptics(input: PipelineInput) {
        val estimateCa = input.lens?.hasChromatic != true
        opticsAnalysis = viewModelScope.async(Dispatchers.Default) {
            runCatching { OpticsAnalysis.of(input, estimateCa) }.getOrNull()
        }
    }

    @Volatile
    private var opticsAnalysis: Deferred<OpticsAnalysis?>? = null

    /**
     * "Remove chromatic aberration" on or off, one step: the profile's CA data when it has some, else the automatic
     * estimate (written into the recipe so every render, export included, uses the same numbers).
     */
    fun setRemoveCa(on: Boolean) {
        viewModelScope.launch {
            val lens = _state.value.edit.lens
            val estimate = if (on && lens?.hasChromatic != true) opticsAnalysis?.await()?.ca else null
            editAndCommit {
                val optics = it.optics
                it.copy(
                    optics = optics.copy(
                        removeCa = on,
                        caRed = estimate?.first ?: 0f,
                        caBlue = estimate?.second ?: 0f,
                    ),
                )
            }
        }
    }

    /** Upright [mode] in the Crop mode's Transform tab: the transform sliders change (committed on Done). */
    fun upright(mode: UprightMode) {
        viewModelScope.launch {
            val analysis = opticsAnalysis?.await() ?: return@launch
            val recipe = _state.value.edit.recipe
            val g = withContext(Dispatchers.Default) {
                analysis.upright(mode, recipe.geometry, _state.value.edit.lens, recipe.optics)
            }
            if (g == null) {
                _state.update { it.copy(edit = it.edit.copy(uprightFailed = mode)) }
                return@launch
            }
            _state.update { it.copy(edit = it.edit.copy(uprightFailed = null)) }
            setGeometry(g)
        }
    }

    /** Auto-straighten (the button next to the angle dial): the straighten angle levels the dominant lines. */
    fun autoStraighten() {
        viewModelScope.launch {
            val analysis = opticsAnalysis?.await() ?: return@launch
            val recipe = _state.value.edit.recipe
            val g = withContext(Dispatchers.Default) {
                analysis.straighten(recipe.geometry, _state.value.edit.lens, recipe.optics)
            } ?: return@launch
            setGeometry(g)
        }
    }

    /** Guided Upright: a guide was drawn; from two guides on, the transform follows them. */
    fun addGuide(line: GuideLine) {
        val recipe = _state.value.edit.recipe
        val t = recipe.geometry.transform
        val guides = OpticsAnalysis.withGuide(t, line)
        val withGuides = recipe.geometry.copy(transform = t.copy(guides = guides, upright = UprightMode.GUIDED))
        setGeometry(withGuides)
        if (guides.size >= 2) upright(UprightMode.GUIDED)
    }

    /** Removes every guide (the transform stays as it is). */
    fun clearGuides() {
        val g = _state.value.edit.recipe.geometry
        setGeometry(g.copy(transform = g.transform.copy(guides = emptyList())))
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

    private fun commitWith(
        action: HistoryAction?,
        groups: List<RecipeGroup> = emptyList(),
        onlyIfEmpty: Boolean = false,
    ) {
        val edit = _state.value.edit
        val history = edit.history.commit(edit.recipe, action, groups)
        if (history == null) {
            if (edit.recipe != edit.committed) show(edit.committed, edit.history)
            return
        }
        _state.update { it.copy(edit = it.edit.copy(history = history)) }
        versionId?.let {
            writer.write(HistoryOp.Commit(it, history.current, history.entries[history.cursor].label, onlyIfEmpty))
        }
    }

    /**
     * Another device changed this version and sync applied it (ADR 0018 "Live updates"): show its history, unless a
     * slider is held (that change commits on top, and the server settles the conflict).
     */
    private suspend fun reloadRemote(versionId: Long) {
        writer.flush()
        val stored = edits.load(versionId)
        val edit = _state.value.edit
        if (edit.recipe != edit.committed || stored == edit.history) return
        show(stored.current, stored)
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
        handoffPreviews.editorClosed()
        cancel.cancel()
        renderer()?.onHistogram = null
        EditorMetrics.renderer = null
        EditorMetrics.setControl = null
        EditorMetrics.recipes = null
        EditorMetrics.history = null
        thumbnails?.close()
        thumbnails = null
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
        // Faces and the subject map (docs/adr/0010) take a few hundred ms: found in the background, so the open is
        // never slower; Auto waits for them, and the Auto WB chip value is refined with the faces once they are in.
        val features = viewModelScope.async(Dispatchers.Default) {
            val start = SystemClock.elapsedRealtime()
            runCatching { PhotoFeatures.of(input, sample = sample, wb = wbAnalysis) }.getOrNull()?.let {
                PhotoFeatures(sample, wbAnalysis, it.faces, it.saliency, lens = input.lens)
            }.also { EditorMetrics.features(it?.faces?.size ?: -1, SystemClock.elapsedRealtime() - start) }
        }
        autoInputs = AutoInputs(sample, wbAnalysis, features)
        viewModelScope.launch {
            val f = features.await() ?: return@launch
            if (f.faces.isEmpty() || wbAnalysis == null) return@launch
            val wb = withContext(Dispatchers.Default) {
                runCatching { AutoRules.whiteBalance(wbAnalysis, f.faces) }.getOrNull()
            } ?: return@launch
            _state.update { it.copy(edit = it.edit.copy(autoWb = wb)) }
        }
        return wbAnalysis?.let { runCatching { AutoRules.whiteBalance(it) }.getOrNull() }
    }

    /**
     * The photo's features for Auto and smart presets: the background analysis when it succeeded (with the lines of
     * the Optics analysis for auto-straighten), else the sample and the white balance alone.
     */
    private suspend fun photoFeatures(inputs: AutoInputs): PhotoFeatures {
        val found = inputs.features.await()
        val lines = opticsAnalysis?.await()?.let { ImageLines(it.width, it.height, it.segments) }
        return PhotoFeatures(
            inputs.sample,
            inputs.wb,
            found?.faces.orEmpty(),
            found?.saliency,
            lines,
            found?.lens,
        )
    }

    /** The smart presets' correction on this photo (docs/adr/0020); `null` before the photo is open. */
    suspend fun smartCorrection(): SmartCorrection? = autoInputs?.let { SmartCorrector(photoFeatures(it)) }.also {
        if (it != null) smart = it
    }

    /** [smartCorrection], kept once made (the photo's features do not change). */
    @Volatile
    private var smart: SmartCorrection? = null

    /** This photo's correction once known, else the app's fallback (the style only). */
    private val photoSmart: SmartCorrection get() = smart ?: smartCorrection

    /** What Auto needs once the proxy is gone: the analysis sample, the Auto WB analysis and the photo's features. */
    private class AutoInputs(val sample: PipelineInput, val wb: WbAnalysis?, val features: Deferred<PhotoFeatures?>)

    @Volatile
    private var autoInputs: AutoInputs? = null

    private var autoJob: Job? = null

    /** The proxy's colour metadata without its pixels (for the eyedropper maths); set once uploaded. */
    @Volatile
    private var photoInput: PipelineInput? = null

    private fun colourOnly(input: PipelineInput) = input.colourOnly()

    // ------------------------------------------------------------------ presets (docs/adr/0019)

    /** The live previews of the presets panel; null until the proxy is decoded. */
    @Volatile
    var thumbnails: PresetThumbnails? = null
        private set

    private val _presetEvents = MutableSharedFlow<PresetEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** One-shot outcomes of preset imports and exports (the screen shows them as toasts). */
    val presetEvents: SharedFlow<PresetEvent> = _presetEvents.asSharedFlow()

    private val presetFiles by lazy { PresetFilesIo(context.contentResolver, presets) }

    /** The applied preset while its step is still the history's last word, else null. */
    private fun currentSession(): PresetSession? {
        val state = _state.value
        return state.presets.session?.takeIf { it.isCurrent(state.edit.history) }
    }

    /** What a strength between the photo and a preset knows about the photo (the as-shot white balance of a RAW). */
    private fun applyContext(): ApplyContext {
        val edit = _state.value.edit
        return if (edit.isRaw) ApplyContext(edit.asShot.temperatureK, edit.asShot.tint) else ApplyContext()
    }

    /** The recipe the presets panel previews each preset on: the applied preset's base while one is current. */
    fun presetBase(): Recipe = currentSession()?.before ?: _state.value.edit.committed

    /** [preset] at full strength on [base], as the panel previews it. */
    fun presetPreview(preset: Preset, base: Recipe): Recipe = preset.apply(base, 1f, applyContext(), photoSmart)

    /**
     * Applies a preset as one history step (docs/adr/0019). While the last applied preset is still current, a tap on
     * another one replaces it (browsing presets), else it applies on top of the edit.
     */
    fun applyPreset(uuid: String) {
        viewModelScope.launch {
            val preset = presets.preset(uuid) ?: return@launch
            val history = _state.value.edit.history
            val below = currentSession()?.below(history) ?: history
            if (preset.smart != null) smart = smart ?: smartCorrection()
            val next = withContext(Dispatchers.Default) { preset.apply(below.current, 1f, applyContext(), photoSmart) }
            commitPreset(history, below, preset, next, 1f)
            presets.markUsed(uuid)
        }
    }

    /** Per drag frame: the applied preset at [strength] (0..2) on its base; nothing is committed. */
    fun setPresetStrength(strength: Float) {
        val session = currentSession() ?: return
        val recipe = session.preset.apply(session.before, strength, applyContext(), photoSmart)
        val next = _state.updateAndGet {
            it.copy(
                edit = it.edit.copy(recipe = recipe),
                presets = it.presets.copy(session = session.copy(strength = strength)),
            )
        }
        renderer()?.setRecipe(next.edit.recipe)
    }

    /** Strength released: the applied preset's step is replaced by the new strength (removed at 0). */
    @Suppress("ReturnCount")
    fun commitPresetStrength() {
        val state = _state.value
        val session = state.presets.session ?: return
        val history = state.edit.history
        if (!session.isCurrent(history)) return
        if (state.edit.recipe == session.applied) return
        commitPreset(history, session.below(history), session.preset, state.edit.recipe, session.strength)
    }

    private fun commitPreset(history: EditHistory, below: EditHistory, preset: Preset, recipe: Recipe, s: Float) {
        val (next, session) = PresetSteps.commit(below, preset, recipe, s)
        _state.update {
            it.copy(
                edit = it.edit.copy(recipe = next.current, history = next),
                presets = it.presets.copy(session = session),
            )
        }
        renderer()?.setRecipe(next.current)
        val version = versionId ?: return
        if (below.cursor != history.cursor) writer.write(HistoryOp.Move(version, below.cursor))
        if (next !== below) writer.write(HistoryOp.Commit(version, next.current, next.entries[next.cursor].label))
    }

    /** Opens "Create preset" with the sections the edit changed (minus the photo-specific ones) ticked. */
    fun openCreatePreset() {
        val sections = PresetSection.suggestedFor(_state.value.edit.committed)
        _state.update { it.copy(presets = it.presets.copy(creating = CreatePresetDraft(sections))) }
    }

    fun dismissCreatePreset() = _state.update { it.copy(presets = it.presets.copy(creating = null)) }

    /** Saves the committed edit's [sections] as a preset named [name] in a user group (or a new one). */
    fun savePreset(name: String, groupId: Long?, newGroup: String?, sections: Set<PresetSection>) {
        val recipe = _state.value.edit.committed
        dismissCreatePreset()
        if (sections.isEmpty() || name.isBlank()) return
        viewModelScope.launch {
            val group = newGroup?.takeIf { it.isNotBlank() }?.let { presets.createGroup(it) } ?: groupId
            presets.create(name, PresetPatch.fromSections(recipe, sections), group)
            _presetEvents.tryEmit(PresetEvent.Created(name.trim()))
        }
    }

    fun setPresetFavourite(uuid: String, favourite: Boolean) {
        viewModelScope.launch { presets.setFavourite(uuid, favourite) }
    }

    /** "Smart corrections": [kinds] run on each photo before the style (empty: a plain preset again). */
    fun setPresetSmart(uuid: String, kinds: Set<SmartRules.Kind>) {
        viewModelScope.launch {
            val current = presets.preset(uuid)?.smartRules ?: SmartRules()
            presets.setSmart(uuid, if (kinds.isEmpty()) null else current.withKinds(kinds))
        }
    }

    fun renamePreset(uuid: String, name: String) {
        viewModelScope.launch { presets.rename(uuid, name) }
    }

    fun duplicatePreset(uuid: String, name: String) {
        viewModelScope.launch { presets.duplicate(uuid, name) }
    }

    fun deletePreset(uuid: String) {
        viewModelScope.launch { presets.delete(uuid) }
    }

    fun movePreset(uuid: String, groupId: Long) {
        viewModelScope.launch { presets.move(uuid, groupId) }
    }

    /** Moves a preset one place earlier ([delta] -1) or later (+1) within its group. */
    fun shiftPreset(uuid: String, delta: Int) {
        val group = _state.value.presets.library.groups.firstOrNull { g -> g.presets.any { it.uuid == uuid } } ?: return
        val order = group.presets.map { it.uuid }.toMutableList()
        val from = order.indexOf(uuid)
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from == to) return
        order.add(to, order.removeAt(from))
        viewModelScope.launch { presets.reorder(order) }
    }

    fun renamePresetGroup(groupId: Long, name: String) {
        viewModelScope.launch { presets.renameGroup(groupId, name) }
    }

    fun deletePresetGroup(groupId: Long) {
        viewModelScope.launch { presets.deleteGroup(groupId) }
    }

    /** Moves a group one place earlier or later among the stored groups. */
    fun shiftPresetGroup(groupId: Long, delta: Int) {
        val order = _state.value.presets.library.groups.mapNotNull { it.rowId }.toMutableList()
        val from = order.indexOf(groupId)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from == to) return
        order.add(to, order.removeAt(from))
        viewModelScope.launch { presets.reorderGroups(order) }
    }

    fun keepPack(packRowId: Long) {
        viewModelScope.launch { presets.keepPack(packRowId) }
    }

    fun removePack(packRowId: Long) {
        viewModelScope.launch { presets.removePack(packRowId) }
    }

    /** Imports a `.revealpreset` or `.revealpack` from [uri] (SAF). */
    fun importPresets(uri: Uri) {
        viewModelScope.launch { _presetEvents.tryEmit(presetFiles.import(uri)) }
    }

    /** Writes a preset to the document [uri] (SAF "create document"). */
    fun exportPreset(uuid: String, uri: Uri) {
        viewModelScope.launch { _presetEvents.tryEmit(presetFiles.exportPreset(uuid, uri)) }
    }

    /** Writes a user group as a pack to [uri]. */
    fun exportPresetGroup(groupId: Long, uri: Uri, author: String, licence: String) {
        viewModelScope.launch { _presetEvents.tryEmit(presetFiles.exportGroup(groupId, uri, author, licence)) }
    }

    private companion object {
        /** Screen proxy bounds (ADR 0002 "Proxies and tiles"): about the screen's long edge. */
        const val MIN_PROXY_EDGE = 2400
        const val MAX_PROXY_EDGE = 3000

        /** Half-size of the eyedropper's sampled square, in proxy pixels (a 9x9 average). */
        const val EYEDROPPER_RADIUS = 4

        /** Half-size of the targeted adjustment's sampled square, in analysis-sample pixels (a 3x3 average). */
        const val TARGETED_RADIUS = 1

        /** "No version" for queries that exclude the current one. */
        const val NO_VERSION = -1L

        const val EVENT_BUFFER = 8
    }
}
