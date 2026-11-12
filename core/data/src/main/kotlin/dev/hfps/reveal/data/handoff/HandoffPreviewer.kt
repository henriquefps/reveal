package dev.hfps.reveal.data.handoff

import android.util.Log
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Makes the two local previews from open documents (the real one decodes; tests write marker bytes). */
interface PreviewMaking {
    /** Writes the display JPEG of a shot (its RAW and/or rendering) to [out]. */
    fun display(raw: ImageInput?, rendered: ImageInput?, out: File)

    /** Writes the smart preview of the shot's main file to [out]. */
    fun smart(main: ImageInput, out: File)

    companion object {
        /** The app's: `SmartPreviewMaker`'s display JPEG and smart preview (the ones sync makes). */
        val DEFAULT: PreviewMaking = object : PreviewMaking {
            override fun display(raw: ImageInput?, rendered: ImageInput?, out: File) {
                out.writeBytes(SmartPreviewMaker.displayJpeg(raw, rendered, LocalPreviews.DISPLAY_EDGE))
            }

            override fun smart(main: ImageInput, out: File) {
                out.outputStream().buffered().use { SmartPreviewMaker.write(main, it) }
            }
        }
    }
}

/**
 * Keeps a local preview of each hand-off photo while Reveal can still read it (ADR 0011 "Unreadable hand-offs"): a
 * sender such as the Samsung Gallery grants access only while the receiving activity lives, after which the original
 * cannot be read again. Right after a hand-off, one shot at a time in the background (the photo the editor opens
 * first): the display image always, the smart preview when the grant is not persistable. A preview already there (the
 * same photo shared again) is not made twice.
 *
 * Memory: a smart preview decodes a 2560 px proxy (about 100 MB of heap), as the editor's open does. So the display
 * images of a batch come first, and smart previews wait while an editor is open ([editorOpened]); the photo the editor
 * opens gets its smart preview from the editor's own input instead (`LibraryRepository.keepSmartPreview`), with no
 * second decode.
 */
class HandoffPreviewer(
    private val tree: DocumentTree,
    val previews: LocalPreviews,
    private val scope: CoroutineScope,
    private val making: PreviewMaking = PreviewMaking.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One shot to preview: its main file's fingerprint and documents (URIs), [smart] = make the smart preview too. */
    data class Shot(
        val fingerprint: String,
        val main: Document,
        val raw: Document?,
        val rendered: Document?,
        val smart: Boolean,
    )

    /** A document of the hand-off tree: its id (the content URI) and name. */
    data class Document(val id: String, val name: String)

    private val lock = Mutex()
    private val jobs = ArrayList<Job>()
    private val editors = MutableStateFlow(0)

    /** Until when (Unix ms) an editor this hand-off opens is expected: it is created after `receive` returns. */
    @Volatile
    private var expectedUntil = 0L

    /** The hand-off opens the editor: smart previews wait for it (at most [EXPECT_MS] if it never comes). */
    fun expectEditor() {
        expectedUntil = clock() + EXPECT_MS
    }

    /** An editor opened (its decode and full-resolution zoom need the heap): smart previews wait until it closes. */
    fun editorOpened() {
        expectedUntil = 0L
        editors.update { it + 1 }
    }

    fun editorClosed() = editors.update { (it - 1).coerceAtLeast(0) }

    /** Previews [shots] in order, after the ones scheduled before: every display image, then the smart previews. */
    fun schedule(shots: List<Shot>) {
        if (shots.isEmpty()) return
        val job = scope.launch {
            lock.withLock {
                shots.forEach { display(it) }
                for (shot in shots.filter { it.smart }) {
                    quiet()
                    smart(shot)
                }
            }
        }
        synchronized(jobs) {
            jobs.removeAll { it.isCompleted }
            jobs += job
        }
    }

    /** Waits for every scheduled preview (tests, and the access check that follows a hand-off). */
    suspend fun idle() {
        synchronized(jobs) { jobs.toList() }.joinAll()
    }

    private fun display(shot: Shot) = guarded {
        if (previews.display(shot.fingerprint) == null) {
            write({ previews.putDisplay(shot.fingerprint, it) }) { out ->
                open(shot.raw).use { raw ->
                    open(shot.rendered).use { rendered -> making.display(raw, rendered, out) }
                }
            }
        }
    }

    private fun smart(shot: Shot) = guarded {
        if (previews.smart(shot.fingerprint) == null) {
            write({ previews.putSmart(shot.fingerprint, it) }) { out ->
                tree.openInput(shot.main.id, shot.main.name).use { making.smart(it, out) }
            }
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Unreadable already, or not an image we can develop: the photo shows what the sender's URI still gives.
            Log.w(TAG, "No local preview for a hand-off: ${e.javaClass.simpleName}")
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No memory for a hand-off's local preview", e)
        }
    }

    /** No editor open or about to open. */
    private suspend fun quiet() {
        while (true) {
            editors.first { it == 0 }
            val wait = expectedUntil - clock()
            if (wait <= 0 && editors.value == 0) return
            delay(wait.coerceIn(1, POLL_MS))
        }
    }

    private fun open(document: Document?): ImageInput? = document?.let { tree.openInput(it.id, it.name) }

    private inline fun write(store: (File) -> Unit, block: (File) -> Unit) {
        val temp = previews.temp()
        try {
            block(temp)
            store(temp)
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "RevealHandoff"
        const val EXPECT_MS = 30_000L
        const val POLL_MS = 250L
    }
}
