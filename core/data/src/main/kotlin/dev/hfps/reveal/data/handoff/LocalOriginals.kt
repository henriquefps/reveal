package dev.hfps.reveal.data.handoff

import android.net.Uri
import dev.hfps.image.ImageInput
import dev.hfps.image.format.ImageFormats
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/**
 * Hand-off originals Reveal keeps itself (ADR 0011 "Amendment W4"): a photo that Hoard puts into MediaStore
 * **Downloads** (the formats `MediaStore.Images` refuses, such as CR3) is readable only through the intent's temporary
 * read grant, which no permission extends. So while that grant is valid the file is copied into [dir]
 * (`noBackupFilesDir/handoff-originals`), keyed by its content fingerprint, and the photo's file row points at the copy
 * (`file:` URI): it is read from there from then on, never marked unreadable when the grant ends, uploaded from there.
 *
 * It is not a cache: the copy may be the only readable one, so the cache budget never evicts it. Only forgetting the
 * photo (or the hand-off retention) deletes it.
 */
class LocalOriginals(val dir: File) {
    /** The file that holds the original of [fingerprint] named [name] (the extension is kept for the decoders). */
    fun fileFor(fingerprint: String, name: String): File {
        val extension = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(MAX_EXTENSION)
        val key = fingerprint.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return File(dir, if (extension.isEmpty()) key else "$key.$extension")
    }

    /** The `file:` document id a copy is filed under. */
    fun documentId(file: File): String = Uri.fromFile(file).toString()

    /** Whether [documentId] is one of these copies. */
    fun isLocal(documentId: String): Boolean = documentId.startsWith(Uri.fromFile(dir).toString() + "/")

    /**
     * Copies [input] (closed here) into the store when it is an image (decided by [name] and the file's own first
     * bytes, never by a MIME type: Downloads says `application/octet-stream`); returns the copy's file, or null when
     * the content is not an image. An existing copy of the same size is reused. A failure leaves no partial file.
     */
    fun copyIn(input: ImageInput, fingerprint: String, name: String): File? = input.use { source ->
        FileInputStream(source.descriptor.fileDescriptor).channel.use { from ->
            val head = ByteArray(HEAD_BYTES)
            val read = from.read(java.nio.ByteBuffer.wrap(head)).coerceAtLeast(0)
            if (!ImageFormats.classify(name, head.copyOf(read)).isDisplayable) return@use null
            val target = fileFor(fingerprint, name)
            val size = source.descriptor.statSize
            if (target.isFile && (size <= 0 || target.length() == size)) return@use target
            from.position(0)
            store(from, size, target)
            target
        }
    }

    /** Writes [from] to [target] through a temporary file, so a half-written copy is never taken for the original. */
    private fun store(from: java.nio.channels.FileChannel, size: Long, target: File) {
        dir.mkdirs()
        val temp = File(dir, "${UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().channel.use { to -> transfer(from, to) }
            if (size > 0 && temp.length() != size) throw IOException("short copy of ${target.name}")
            if (!temp.renameTo(target)) throw IOException("could not store ${target.name}")
        } finally {
            temp.delete()
        }
    }

    private fun transfer(from: java.nio.channels.FileChannel, to: java.nio.channels.FileChannel) {
        var position = 0L
        while (true) {
            val moved = to.transferFrom(from, position, CHUNK)
            if (moved <= 0) return
            position += moved
        }
    }

    /** Deletes the copies of [fingerprints] (their photo was forgotten); only copies, never anything else. */
    fun delete(fingerprints: Collection<String>) {
        val prefixes = fingerprints.map { fileFor(it, "").name }.toSet()
        dir.listFiles()?.filter { it.isFile && it.name.substringBefore('.') in prefixes }?.forEach { it.delete() }
    }

    /** Every copy, for Settings → Storage. */
    fun files(): List<File> = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()

    companion object {
        /** `content://media/<volume>/downloads/<id>`: the collection `MediaStore.Images` does not cover. */
        fun isDownloads(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == "media" &&
            uri.pathSegments.any { it == "downloads" }

        private const val HEAD_BYTES = 64
        private const val CHUNK = 8L * 1024 * 1024
        private const val MAX_EXTENSION = 8
    }
}
