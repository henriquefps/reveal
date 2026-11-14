package dev.hfps.reveal.data.library

import android.util.Log
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RevealDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the sidecars of edited photos up to date (ADR 0026 "Writing"): every change of a master version's recipe (the
 * editor's commits, undo, paste, presets, a sync pull, an import) is written to that photo's sidecar through
 * [SidecarSync], [DEBOUNCE_MS] after the last change and off the main thread, so a burst of edits costs one write per
 * photo. Where it got to is `meta` [KEY_WRITTEN] (the newest `recipes.updated_at` written): edits made while the
 * setting was off are written when it is turned on, and edits from before this feature on its first run.
 */
@OptIn(FlowPreview::class)
class EditSidecars(
    private val db: RevealDatabase,
    private val sidecars: SidecarSync,
    private val enabled: Flow<Boolean>,
    private val isEnabled: () -> Boolean,
    /** The app's data scope (application lifetime). */
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val gate = Mutex()

    /** Starts watching (once, from the application); the first emission flushes what is pending. */
    fun start() {
        scope.launch {
            combine(db.editDao().observeLastChange(), enabled) { last, on -> last to on }
                .debounce(debounceMs)
                .collect { (_, on) ->
                    if (!on) return@collect
                    try {
                        flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        Log.w(TAG, "Could not write edit sidecars", e)
                    }
                }
        }
    }

    /** Writes every master version changed since the last flush; returns how many sidecars were written. */
    suspend fun flush(): Int = gate.withLock {
        if (!isEnabled()) return@withLock 0
        val meta = db.metaDao()
        val mark = meta.get(KEY_WRITTEN)?.toLongOrNull() ?: 0L
        val changed = db.editDao().changedMasters(mark)
        if (changed.isEmpty()) return@withLock 0
        var written = 0
        changed.map { it.photoId }.distinct().chunked(CHUNK).forEach { chunk ->
            written += sidecars.write(chunk, editsOnly = true).written
        }
        meta.put(MetaEntity(KEY_WRITTEN, changed.maxOf { it.updatedAt }.toString()))
        written
    }

    companion object {
        const val DEBOUNCE_MS = 2_000L
        const val KEY_WRITTEN = "xmp.editsWritten"
        private const val CHUNK = 100
        private const val TAG = "RevealSidecars"
    }
}
