package dev.hfps.reveal.feature.editor.presets

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.reveal.data.preset.PresetDownloadException
import dev.hfps.reveal.data.preset.PresetDownloader
import dev.hfps.reveal.data.preset.PresetEvent
import dev.hfps.reveal.data.preset.PresetFilesIo
import dev.hfps.reveal.data.preset.PresetLinks
import dev.hfps.reveal.data.preset.PresetRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the "Import from a link" flow is (docs/adr/0019 "Import from a link"). */
sealed interface PresetLinkState {
    /** Nothing shown. */
    data object Closed : PresetLinkState

    /** The user types or pastes a link; [error] is the last refusal (not a link, not https). */
    data class Entering(val text: String = "", val error: LinkProblem? = null) : PresetLinkState

    /** The link is known: nothing is downloaded until the user confirms. */
    data class Confirming(val url: String, val host: String) : PresetLinkState

    /** Downloading: [received] of [total] bytes (total unknown when the server does not say). */
    data class Downloading(val url: String, val host: String, val received: Long = 0, val total: Long? = null) :
        PresetLinkState

    /** The file arrived and is being validated and imported. */
    data class Importing(val host: String) : PresetLinkState
}

enum class LinkProblem { NOT_A_LINK, NOT_HTTPS }

/**
 * Imports a `.revealpreset` / `.revealpack` from an `https` link: pasted in the presets panel's menu or shared to
 * Reveal (`MainActivity`). The user always confirms the host before anything is fetched; the download (size limit,
 * timeouts, https only: [PresetDownloader]) shows its progress and can be cancelled; the file then goes through the
 * same validation and trial flow as an opened file ([PresetFilesIo]). No background fetches, nothing automatic.
 */
@HiltViewModel
class PresetLinkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    presets: PresetRepository,
    private val downloader: PresetDownloader,
) : ViewModel() {
    private val files = PresetFilesIo(context.contentResolver, presets)
    private val _state = MutableStateFlow<PresetLinkState>(PresetLinkState.Closed)
    val state: StateFlow<PresetLinkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PresetEvent>(extraBufferCapacity = 4)

    /** The import's outcome (a toast), or why the download failed. */
    val events: SharedFlow<PresetEvent> = _events.asSharedFlow()

    private val _problems = MutableSharedFlow<LinkProblem>(extraBufferCapacity = 4)

    /** A shared text that held no usable link (a toast: there is no field to show it under). */
    val problems: SharedFlow<LinkProblem> = _problems.asSharedFlow()

    private var download: Job? = null

    /** Opens the link field (the panel's "Import from link…"). */
    fun enter() {
        if (_state.value is PresetLinkState.Closed) _state.value = PresetLinkState.Entering()
    }

    fun onText(text: String) {
        _state.update { if (it is PresetLinkState.Entering) PresetLinkState.Entering(text) else it }
    }

    /** The typed or pasted link: on to the confirmation, or the field shows why not. */
    fun submit() {
        val entering = _state.value as? PresetLinkState.Entering ?: return
        when (val parsed = PresetLinks.parse(entering.text)) {
            is PresetLinks.Parsed.Link -> _state.value = PresetLinkState.Confirming(parsed.url, parsed.host)
            PresetLinks.Parsed.NotHttps -> _state.value = entering.copy(error = LinkProblem.NOT_HTTPS)
            PresetLinks.Parsed.None -> _state.value = entering.copy(error = LinkProblem.NOT_A_LINK)
        }
    }

    /** A link shared or opened from another app: straight to the confirmation (never downloaded without it). */
    fun offer(parsed: PresetLinks.Parsed) {
        if (_state.value is PresetLinkState.Downloading || _state.value is PresetLinkState.Importing) return
        when (parsed) {
            is PresetLinks.Parsed.Link -> _state.value = PresetLinkState.Confirming(parsed.url, parsed.host)
            PresetLinks.Parsed.NotHttps -> _problems.tryEmit(LinkProblem.NOT_HTTPS)
            PresetLinks.Parsed.None -> _problems.tryEmit(LinkProblem.NOT_A_LINK)
        }
    }

    /** The user confirmed: download, then import like a file. */
    fun confirm() {
        val confirming = _state.value as? PresetLinkState.Confirming ?: return
        _state.value = PresetLinkState.Downloading(confirming.url, confirming.host)
        download = viewModelScope.launch {
            val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
            val file = File.createTempFile("link", ".part", dir)
            try {
                downloader.download(confirming.url, file) { received, total ->
                    _state.update {
                        if (it is PresetLinkState.Downloading) it.copy(received = received, total = total) else it
                    }
                }
                _state.value = PresetLinkState.Importing(confirming.host)
                val event = files.import(file)
                _state.value = PresetLinkState.Closed
                _events.tryEmit(event)
            } catch (e: PresetDownloadException) {
                Log.w(TAG, "preset link download failed: ${e.error}")
                _state.value = PresetLinkState.Closed
                _events.tryEmit(PresetEvent.DownloadFailed(e.error, e.status))
            } finally {
                file.delete()
            }
        }
    }

    /** Cancel, back or a dismissed sheet: stops a running download (the partial file is deleted). */
    fun cancel() {
        download?.cancel()
        download = null
        _state.value = PresetLinkState.Closed
    }

    override fun onCleared() {
        download?.cancel()
    }

    private companion object {
        const val TAG = "RevealPresets"

        /** Downloads in progress, under the app's cache (swept by the system; each file is deleted after use). */
        const val DIRECTORY = "preset-downloads"
    }
}
