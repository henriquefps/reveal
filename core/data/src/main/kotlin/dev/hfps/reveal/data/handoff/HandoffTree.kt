package dev.hfps.reveal.data.handoff

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.readNBytesCompat
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The [DocumentTree] of the "Opened from other apps" source (ADR 0011): there is no folder to walk, a document id is
 * the content URI string itself, so thumbnails, the viewer, the editor and sidecar writes use the same code as for an
 * added folder. Sidecars can only be written when the sender handed over a writable `.xmp` item: nothing is created.
 */
class HandoffTree(private val resolver: ContentResolver) : DocumentTree {
    override val rootDocumentId: String = ""

    override fun children(documentId: String): List<DocumentEntry> = emptyList()

    override fun stat(documentId: String): DocumentEntry? = stat(documentId.toUri())

    /** Name, size and (when the provider has it) mtime of [uri]; null when it cannot be queried. */
    fun stat(uri: Uri): DocumentEntry? =
        query(uri, STAT_COLUMNS) ?: query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))

    private fun query(uri: Uri, columns: Array<String>): DocumentEntry? = runCatching {
        resolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }
                ?.let(c::getString)
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            val modified = c.getColumnIndex(COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }
                ?.let(c::getLong)
            DocumentEntry(
                documentId = uri.toString(),
                name = name ?: uri.lastPathSegment.orEmpty().substringAfterLast('/'),
                mimeType = resolver.getType(uri).orEmpty(),
                size = size ?: 0,
                modified = modified ?: 0,
            )
        }
    }.getOrNull()

    override fun openInput(documentId: String, name: String): ImageInput =
        ImageInput.fromUri(resolver, documentId.toUri(), name)

    override fun readBytes(documentId: String, maxBytes: Int): ByteArray {
        val stream = resolver.openInputStream(documentId.toUri()) ?: throw FileNotFoundException(documentId)
        return stream.use { input ->
            val bytes = input.readNBytesCompat(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("Document $documentId is larger than $maxBytes bytes")
            bytes
        }
    }

    override fun writeBytes(documentId: String, bytes: ByteArray) {
        val stream = resolver.openOutputStream(documentId.toUri(), "wt") ?: throw FileNotFoundException(documentId)
        stream.use { it.write(bytes) }
    }

    override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry =
        throw IOException("Reveal cannot create documents next to a hand-off: no folder grant")

    override fun uri(documentId: String): Uri = documentId.toUri()

    private companion object {
        /** `MediaColumns.DATE_MODIFIED` is in seconds; `DocumentsContract` uses `last_modified` in milliseconds. */
        const val COLUMN_LAST_MODIFIED = "last_modified"
        val STAT_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, COLUMN_LAST_MODIFIED)
    }
}
