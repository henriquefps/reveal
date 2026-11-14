package dev.hfps.reveal.feature.export.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.data.export.ExportPresetRepository
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.feature.export.encode.EncoderSupport
import dev.hfps.reveal.feature.export.engine.ExportFailure
import dev.hfps.reveal.feature.export.engine.SizeEstimator
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportFormat
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.model.ExportPreset
import dev.hfps.reveal.feature.export.model.ExportPresets
import dev.hfps.reveal.feature.export.work.ExportJobItem
import dev.hfps.reveal.feature.export.work.ExportJobStore
import dev.hfps.reveal.feature.export.work.ExportWorker
import dev.hfps.reveal.feature.export.work.ItemStatus
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.export.PixelSize
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What to export: library versions (multi-select) or the photo open in the editor with its current recipe. */
sealed interface ExportRequest {
    val count: Int

    data class Versions(val versionIds: List<Long>) : ExportRequest {
        override val count: Int get() = versionIds.size
    }

    /** The editor's photo: a library [versionId] or a [uri] from another app, developed with [recipe]. */
    data class Current(val versionId: Long?, val uri: String?, val name: String, val recipe: Recipe) : ExportRequest {
        override val count: Int get() = 1
    }
}

/** A finished or running export as the sheet shows it. */
data class ExportRun(
    val jobId: String,
    val total: Int,
    val done: Int = 0,
    val fraction: Float = 0f,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /** File names (or originals) with their failure. */
    val failures: List<Pair<String, ExportFailure>> = emptyList(),
    val exported: Int = 0,
    /** Photos waiting for their original (only on another device); the job resumes by itself (ADR 0018). */
    val waiting: Int = 0,
    /**
     * The files just saved (MediaStore or SAF document URIs), offered by "Share" on the result. Empty when the
     * destination was the share sheet itself: those cache copies are gone once it returns.
     */
    val results: List<Uri> = emptyList(),
)

data class ExportUiState(
    val request: ExportRequest? = null,
    val options: ExportOptions = ExportOptions(),
    val support: EncoderSupport? = null,
    val authorship: Authorship = Authorship(),
    /** Full-resolution size of the (first) picture after its crop and turns, once known. */
    val fullSize: PixelSize? = null,
    val outputSize: PixelSize? = null,
    val estimateBytes: Long? = null,
    val estimating: Boolean = false,
    val run: ExportRun? = null,
    /** Content URIs to hand to the share sheet once (cleared by [ExportViewModel.onShareLaunched]). */
    val share: List<Uri> = emptyList(),
    /** Photos of the request Reveal can no longer read: they export from their preview (ADR 0011). */
    val previewOnly: Int = 0,
    /** The export presets (docs/adr/0025): built-ins, then the user's. */
    val presets: List<ExportPreset> = ExportPresets.builtIns,
    /**
     * The preset last picked (its [ExportPreset.key]) while the options still come from it, else the preset matching
     * the options; null = none. [presetModified]: the options changed since it was picked (offers "Update").
     */
    val activePreset: String? = null,
) {
    val active: ExportPreset? get() = presets.firstOrNull { it.key == activePreset }
    val presetModified: Boolean get() = active?.let { it.options != options } ?: false
}

/**
 * The export sheet: options (remembered), the live size estimate, starting the background job and following it.
 * Exports always run in [ExportWorker] (one photo or many), so they survive the sheet and the app closing.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ExportPreferences,
    private val library: LibraryRepository,
    private val edits: EditRepository,
    private val presetStore: ExportPresetRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ExportUiState(
            options = rememberedOptions(),
            authorship = preferences.authorship.value,
        ),
    )
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var estimator: SizeEstimator? = null
    private var estimateJob: Job? = null
    private var observeJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val support = EncoderSupport.detect()
            _state.update {
                val options = if (!support.heif && it.options.format == ExportFormat.HEIF) {
                    it.options.copy(format = ExportFormat.JPEG)
                } else {
                    it.options
                }
                it.copy(support = support, options = options)
            }
        }
        viewModelScope.launch { preferences.authorship.collect { a -> _state.update { it.copy(authorship = a) } } }
        viewModelScope.launch {
            presetStore.observe().collect { stored ->
                _state.update { s ->
                    val presets = ExportPresets.all(stored)
                    val active = s.activePreset?.takeIf { key -> presets.any { it.key == key } }
                        ?: ExportPresets.matching(s.options, presets)?.key
                    s.copy(presets = presets, activePreset = active)
                }
            }
        }
    }

    // ------------------------------------------------------------------ export presets (docs/adr/0025)

    /** Applies [preset]'s options in one tap (made usable on this device: folder grant, HEIF support). */
    fun pickPreset(preset: ExportPreset) {
        val options = ExportPresets.resolve(preset, _state.value.support?.heif ?: true, ::folderGranted)
        _state.update { it.copy(activePreset = preset.key) }
        setOptions(options)
    }

    /** Saves the sheet's current options as a new preset named [name]; it becomes the active one. */
    fun savePreset(name: String) {
        val options = _state.value.options
        viewModelScope.launch {
            val uuid = presetStore.create(name, options.encode())
            _state.update { it.copy(activePreset = uuid) }
        }
    }

    fun renamePreset(uuid: String, name: String) {
        viewModelScope.launch { presetStore.update(uuid, name = name) }
    }

    /** Stores the sheet's current options in the user preset [uuid]. */
    fun updatePreset(uuid: String) {
        val options = _state.value.options
        viewModelScope.launch { presetStore.update(uuid, optionsJson = options.encode()) }
    }

    fun deletePreset(uuid: String) {
        _state.update { if (it.activePreset == uuid) it.copy(activePreset = null) else it }
        viewModelScope.launch { presetStore.delete(uuid) }
    }

    private fun folderGranted(treeUri: String): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri.toString() == treeUri && it.isWritePermission
    }

    /** Shows the sheet for [request] (a new request resets the estimate; a running job keeps being followed). */
    fun open(request: ExportRequest) {
        if (_state.value.request == request) return
        _state.update {
            it.copy(request = request, fullSize = null, outputSize = null, estimateBytes = null, previewOnly = 0)
        }
        estimator = null
        viewModelScope.launch(Dispatchers.IO) {
            val versions = when (request) {
                is ExportRequest.Versions -> request.versionIds
                is ExportRequest.Current -> listOfNotNull(request.versionId)
            }
            val unreadable = runCatching { library.unreadableCount(versions) }.getOrDefault(0)
            _state.update { if (it.request == request) it.copy(previewOnly = unreadable) else it }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val prepared = runCatching { prepareEstimator(request) }.getOrNull() ?: return@launch
            estimator = prepared
            _state.update { it.copy(fullSize = prepared.pictureSize) }
            refreshEstimate()
        }
    }

    fun setOptions(options: ExportOptions) {
        _state.update { s ->
            // The picked preset stays active while it is being adjusted; otherwise a preset the options match is.
            val keep = s.active?.let { it is ExportPreset.User || it.options == options } == true
            val active = if (keep) s.activePreset else ExportPresets.matching(options, s.presets)?.key
            s.copy(options = options, activePreset = active)
        }
        preferences.lastOptionsJson = options.encode()
        refreshEstimate()
    }

    /** A folder picked through SAF: keeps the grant and remembers it. */
    fun setFolder(treeUri: Uri, name: String) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        preferences.folderUri = treeUri.toString()
        preferences.folderName = name
        setOptions(_state.value.options.copy(destination = ExportDestination.Folder(treeUri.toString(), name)))
    }

    /** The remembered folder, if any (offered by the Folder chip before picking again). */
    fun rememberedFolder(): ExportDestination.Folder? {
        val uri = preferences.folderUri ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri &&
                it.isWritePermission
        }
        return if (granted) ExportDestination.Folder(uri, preferences.folderName.orEmpty()) else null
    }

    fun start() {
        val current = _state.value
        val request = current.request ?: return
        val items = when (request) {
            is ExportRequest.Versions -> request.versionIds.map { ExportJobItem(versionId = it) }

            is ExportRequest.Current -> listOf(
                ExportJobItem(
                    versionId = request.versionId,
                    uri = if (request.versionId == null) request.uri else null,
                    name = request.name,
                    recipeJson = RecipeCodec.Default.encode(request.recipe),
                ),
            )
        }
        val first = preferences.nextSequence(items.size)
        val jobId = ExportWorker.enqueue(context, items, current.options, first)
        _state.update { it.copy(run = ExportRun(jobId, items.size)) }
        follow(jobId, items.size, current.options.destination == ExportDestination.Share)
    }

    fun cancel() {
        val run = _state.value.run ?: return
        WorkManager.getInstance(context).cancelAllWorkByTag(run.jobId)
    }

    /** The share sheet was launched with [ExportUiState.share]. */
    fun onShareLaunched() = _state.update { it.copy(share = emptyList()) }

    /** Clears a finished run so the sheet shows the options again. */
    fun dismissRun() = _state.update { if (it.run?.finished == true) it.copy(run = null) else it }

    private fun follow(jobId: String, total: Int, share: Boolean) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfosByTagFlow(jobId).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val progress = info.progress
                val done = progress.getInt(ExportWorker.KEY_DONE, _state.value.run?.done ?: 0)
                val fraction = progress.getFloat(ExportWorker.KEY_FRACTION, _state.value.run?.fraction ?: 0f)
                if (info.state.isFinished) {
                    val states = withContext(Dispatchers.IO) { ExportJobStore(context).states(jobId) }
                    val job = withContext(Dispatchers.IO) { ExportJobStore(context).job(jobId) }
                    val failures = states.withIndex().filter { it.value.status == ItemStatus.FAILED }.map { (i, s) ->
                        val item = job?.items?.getOrNull(i)
                        (item?.name ?: item?.uri ?: "#${i + 1}") to
                            (
                                runCatching {
                                    ExportFailure.valueOf(s.failure.orEmpty())
                                }.getOrDefault(ExportFailure.FAILED)
                                )
                    }
                    val uris = states.filter {
                        it.status == ItemStatus.DONE
                    }.mapNotNull { it.resultUri?.toUri() }
                    _state.update {
                        it.copy(
                            run = ExportRun(
                                jobId,
                                total,
                                done = total,
                                fraction = 1f,
                                finished = true,
                                cancelled = info.state == WorkInfo.State.CANCELLED,
                                failures = failures,
                                exported = uris.size,
                                waiting = states.count { it.status == ItemStatus.WAITING },
                                results = if (share) emptyList() else uris,
                            ),
                            share = if (share && info.state == WorkInfo.State.SUCCEEDED) uris else emptyList(),
                        )
                    }
                } else {
                    _state.update { it.copy(run = it.run?.copy(done = done, fraction = fraction)) }
                }
            }
        }
    }

    private fun refreshEstimate() {
        val estimator = estimator ?: return
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch(Dispatchers.Default) {
            val options = _state.value.options
            _state.update { it.copy(estimating = true, outputSize = estimator.outputSize(options)) }
            delay(ESTIMATE_DEBOUNCE_MS)
            val bytes = runCatching { estimator.estimate(context, options) }.getOrNull()
            _state.update { it.copy(estimateBytes = bytes, estimating = false) }
        }
    }

    private suspend fun prepareEstimator(request: ExportRequest): SizeEstimator? = when (request) {
        is ExportRequest.Current -> {
            val uri = request.uri?.toUri() ?: request.versionId?.let { library.exportTarget(it)?.uri }
            uri?.let { SizeEstimator.prepare(context, it, request.name, request.recipe) }
        }

        is ExportRequest.Versions -> request.versionIds.firstOrNull()?.let { id ->
            val target = library.exportTarget(id) ?: return null
            SizeEstimator.prepare(context, target.uri, target.name, edits.recipe(id))
        }
    }

    private fun rememberedOptions(): ExportOptions {
        val options = ExportOptions.decode(preferences.lastOptionsJson)
        // A remembered folder whose grant is gone falls back to the gallery.
        val folder = options.destination as? ExportDestination.Folder ?: return options
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder.treeUri }
        return if (granted) options else options.copy(destination = ExportDestination.Pictures)
    }

    private companion object {
        const val ESTIMATE_DEBOUNCE_MS = 150L
    }
}
