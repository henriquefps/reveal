package dev.hfps.reveal.data.xmp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The XMP switches of Settings (ADR 0026 "Settings"): [writeEdits] puts each photo's edit into its sidecar (the recipe
 * and the Camera Raw overlap), [readEdits] applies edits found in sidecars (another device's recipe, Lightroom's
 * values). Both on by default. Ratings, labels and flags always travel (ADR 0006), whatever these say.
 */
class XmpPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _writeEdits = MutableStateFlow(prefs.getBoolean(KEY_WRITE, true))
    private val _readEdits = MutableStateFlow(prefs.getBoolean(KEY_READ, true))

    val writeEdits: StateFlow<Boolean> = _writeEdits.asStateFlow()
    val readEdits: StateFlow<Boolean> = _readEdits.asStateFlow()

    fun setWriteEdits(on: Boolean) {
        prefs.edit { putBoolean(KEY_WRITE, on) }
        _writeEdits.value = on
    }

    fun setReadEdits(on: Boolean) {
        prefs.edit { putBoolean(KEY_READ, on) }
        _readEdits.value = on
    }

    private companion object {
        const val FILE = "xmp"
        const val KEY_WRITE = "writeEdits"
        const val KEY_READ = "readEdits"
    }
}
