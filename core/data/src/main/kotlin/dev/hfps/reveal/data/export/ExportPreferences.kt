package dev.hfps.reveal.data.export

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Author and copyright written into exports (Settings, docs/adr/0009 "Metadata"); blank = none. */
data class Authorship(val author: String = "", val copyright: String = "")

/**
 * Small persistent export settings (docs/adr/0009): the author/copyright of Settings, the last export options of the
 * sheet (JSON owned by `:feature:export`) and the remembered SAF folder. SharedPreferences: a handful of keys read
 * at sheet/worker start, no queries, nothing a Room table would add.
 */
class ExportPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _authorship = MutableStateFlow(readAuthorship())

    /** Author and copyright; updated by [setAuthorship]. */
    val authorship: StateFlow<Authorship> = _authorship.asStateFlow()

    fun setAuthorship(value: Authorship) {
        prefs.edit {
            putString(KEY_AUTHOR, value.author.trim())
            putString(KEY_COPYRIGHT, value.copyright.trim())
        }
        _authorship.value = readAuthorship()
    }

    /** The last options used in the export sheet (JSON), or null. */
    var lastOptionsJson: String?
        get() = prefs.getString(KEY_OPTIONS, null)
        set(value) = prefs.edit { putString(KEY_OPTIONS, value) }

    /** The remembered export folder (a persisted SAF tree URI) and its display name. */
    var folderUri: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) = prefs.edit { putString(KEY_FOLDER, value) }

    var folderName: String?
        get() = prefs.getString(KEY_FOLDER_NAME, null)
        set(value) = prefs.edit { putString(KEY_FOLDER_NAME, value) }

    /** Next number of the "sequence" file-name template (survives restarts so names do not repeat). */
    fun nextSequence(count: Int): Int {
        val first = prefs.getInt(KEY_SEQUENCE, 1)
        prefs.edit { putInt(KEY_SEQUENCE, first + count) }
        return first
    }

    private fun readAuthorship() =
        Authorship(prefs.getString(KEY_AUTHOR, "").orEmpty(), prefs.getString(KEY_COPYRIGHT, "").orEmpty())

    private companion object {
        const val FILE = "export"
        const val KEY_AUTHOR = "author"
        const val KEY_COPYRIGHT = "copyright"
        const val KEY_OPTIONS = "lastOptions"
        const val KEY_FOLDER = "folderUri"
        const val KEY_FOLDER_NAME = "folderName"
        const val KEY_SEQUENCE = "sequence"
    }
}
