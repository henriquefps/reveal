package dev.hfps.reveal.data.editor

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The tablet editor's remembered layout: the side panel's width and whether the filmstrip shows. */
data class EditorLayout(
    /** Width of the side panel in dp; `null` = the default. */
    val panelWidthDp: Float? = null,
    val filmstripVisible: Boolean = true,
)

/**
 * Small persistent layout choices of the tablet editor (docs/plan/ux.md "Landscape and tablets"): the panel width the
 * user dragged and the filmstrip's shown/hidden state. SharedPreferences, like `ExportPreferences`: two keys read
 * when the editor opens.
 */
class EditorLayoutPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _layout = MutableStateFlow(read())

    val layout: StateFlow<EditorLayout> = _layout.asStateFlow()

    fun setPanelWidth(dp: Float) {
        prefs.edit { putFloat(KEY_PANEL_WIDTH, dp) }
        _layout.value = read()
    }

    fun setFilmstripVisible(visible: Boolean) {
        prefs.edit { putBoolean(KEY_FILMSTRIP, visible) }
        _layout.value = read()
    }

    private fun read() = EditorLayout(
        panelWidthDp = prefs.getFloat(KEY_PANEL_WIDTH, 0f).takeIf { it > 0f },
        filmstripVisible = prefs.getBoolean(KEY_FILMSTRIP, true),
    )

    private companion object {
        const val FILE = "editor_layout"
        const val KEY_PANEL_WIDTH = "panel_width_dp"
        const val KEY_FILMSTRIP = "filmstrip_visible"
    }
}
