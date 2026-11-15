package dev.hfps.reveal.data.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings -> Storage -> "Keep thumbnails of the whole library" (ADR 0018 "W3/C", default on): the server's thumbnail
 * of every photo it holds is fetched in the background (unmetered network, charging or idle) and kept, so the grid
 * works offline. Off: thumbnails are fetched only as the grid shows them, and evicted like any cache.
 */
class LibraryThumbsPreferences(private val prefs: SharedPreferences?) {
    private val keep = MutableStateFlow(prefs?.getBoolean(KEY_KEEP_ALL, true) ?: true)

    val keepAll: StateFlow<Boolean> = keep.asStateFlow()

    fun setKeepAll(on: Boolean) {
        prefs?.edit { putBoolean(KEY_KEEP_ALL, on) }
        keep.value = on
    }

    private companion object {
        const val KEY_KEEP_ALL = "libraryThumbs.keepAll"
    }
}
