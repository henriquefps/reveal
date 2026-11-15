package dev.hfps.reveal.data.storage

import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.hfps.image.ImageInput
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Local copies of other apps' documents for readers that keep a file open for a long time (ADR 0018 "W3/C", Hoard's
 * integration contract 11): a zoomable region decoder keeps its descriptor while the viewer page or the editor is
 * open, and a sender such as Hoard serves a large file through a proxy descriptor that holds one of its few read
 * slots until it is closed. Such readers copy the document into [dir] (open, copy, close at once) and read the copy;
 * the copy is deleted when its input is closed.
 */
object LocalCopies {
    /** Whether [uri] is a document of another app's provider (not `file:`, not one of [ownPackage]'s authorities). */
    fun isForeign(uri: Uri, ownPackage: String): Boolean =
        uri.scheme == "content" && uri.authority?.startsWith(ownPackage) != true

    /**
     * Whether [error] says Hoard's proxy descriptor is gone (Hoard 2.0.2: idle too long, parked, or its slot lost):
     * `EIO`. Never "unreadable": close the descriptor and open the URI again ([copy]'s `reopen`).
     */
    fun isDescriptorGone(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(MAX_CAUSES).any { e ->
        val message = e.message.orEmpty()
        (e is android.system.ErrnoException && e.errno == android.system.OsConstants.EIO) ||
            (e is IOException && (message.contains("EIO") || message.contains("I/O error")))
    }

    /**
     * [input] copied into a new file of [dir] and closed; the returned input reads the copy and deletes it when closed.
     * An `EIO` while copying (a Hoard descriptor that gave its slot back, [isDescriptorGone]) closes [input], opens the
     * document again through [reopen] (which goes through [FileOpens], bounded like `busy:`) and copies again, up to
     * [EIO_RETRIES] times. On failure [input] is closed and the error thrown.
     */
    fun copy(
        input: ImageInput,
        dir: File,
        reopen: (() -> ImageInput)? = null,
        copier: (ImageInput, File) -> Unit = ::copyTo,
    ): ImageInput {
        dir.mkdirs()
        val file = File(dir, UUID.randomUUID().toString())
        var current = input
        var attempt = 0
        while (true) {
            try {
                current.use { source -> copier(source, file) }
                break
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                if (reopen == null || attempt >= EIO_RETRIES || !isDescriptorGone(e)) {
                    file.delete()
                    throw e
                }
                attempt++
                file.delete()
                current = reopen()
            }
        }
        return ImageInput(current.name, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            file.delete()
        }
    }

    private fun copyTo(source: ImageInput, file: File) {
        FileInputStream(source.descriptor.fileDescriptor).channel.use { from ->
            file.outputStream().channel.use { to ->
                val size = source.descriptor.statSize
                if (size <= 0) {
                    Channels.copy(from, to)
                    return
                }
                var position = 0L
                while (position < size) position += to.transferFrom(from, position, size - position)
            }
        }
    }

    /** Leftovers of a process that died while a copy was open: the files of [dir] last written before [before]. */
    fun sweep(dir: File, before: Long = Long.MAX_VALUE) {
        dir.listFiles()?.forEach { if (it.lastModified() < before) it.delete() }
    }

    /**
     * [sweep] without touching the disk where it is called (a Hilt provider, possibly on the main thread before the
     * first frame): it runs in the background once the app settled ([AppStart]), and spares the copies this process
     * made meanwhile.
     */
    fun sweepAfterFirstFrame(dir: File) {
        val started = System.currentTimeMillis()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppStart.awaitSettled()
            sweep(dir, started)
        }
    }

    private const val EIO_RETRIES = 3
    private const val MAX_CAUSES = 8

    private object Channels {
        fun copy(from: java.nio.channels.FileChannel, to: java.nio.channels.FileChannel) {
            val buffer = java.nio.ByteBuffer.allocate(BUFFER)
            while (from.read(buffer) >= 0) {
                buffer.flip()
                while (buffer.hasRemaining()) to.write(buffer)
                buffer.clear()
            }
        }

        const val BUFFER = 1 shl 20
    }
}
