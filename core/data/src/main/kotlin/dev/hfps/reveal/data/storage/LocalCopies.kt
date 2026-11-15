package dev.hfps.reveal.data.storage

import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.hfps.image.ImageInput
import java.io.File
import java.io.FileInputStream
import java.util.UUID

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
     * [input] copied into a new file of [dir] and closed; the returned input reads the copy and deletes it when closed.
     * On failure [input] is closed and the error thrown.
     */
    fun copy(input: ImageInput, dir: File): ImageInput {
        dir.mkdirs()
        val file = File(dir, UUID.randomUUID().toString())
        try {
            input.use { source -> copyTo(source, file) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            file.delete()
            throw e
        }
        return ImageInput(input.name, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
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

    /** Leftovers of a process that died while a copy was open. */
    fun sweep(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

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
