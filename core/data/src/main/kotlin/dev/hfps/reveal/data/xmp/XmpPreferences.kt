package dev.hfps.reveal.data.xmp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The XMP switch of Settings (ADR 0026 "Settings"): [readEdits] applies edits found in sidecars (another device's
 * recipe, Lightroom's values). On by default. Sidecars are never written automatically (0.9.0, ADR 0026 "Automatic
 * writes removed"): the `writeEdits` switch of earlier versions is gone, its stored value is removed on first run;
 * Export XMP is the only writer.
 */
class XmpPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _readEdits = MutableStateFlow(prefs.getBoolean(KEY_READ, true))

    init {
        if (prefs.contains(KEY_WRITE_REMOVED)) prefs.edit { remove(KEY_WRITE_REMOVED) }
    }

    val readEdits: StateFlow<Boolean> = _readEdits.asStateFlow()

    fun setReadEdits(on: Boolean) {
        prefs.edit { putBoolean(KEY_READ, on) }
        _readEdits.value = on
    }

    companion object {
        const val FILE = "xmp"
        const val KEY_READ = "readEdits"

        /** The key of the removed "write edits to sidecars" switch (0.8 and earlier). */
        const val KEY_WRITE_REMOVED = "writeEdits"
    }
}
