package dev.hfps.reveal.data

import android.net.Uri
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A [DocumentTree] over a directory: document id = path relative to [root] ("" = the root). */
class FileDocumentTree(private val root: File) : DocumentTree {
    var listings = 0
        private set

    override val rootDocumentId: String = ""

    private fun file(id: String) = if (id.isEmpty()) root else File(root, id)

    override fun children(documentId: String): List<DocumentEntry> {
        listings++
        val dir = file(documentId)
        if (!dir.isDirectory) throw FileNotFoundException(documentId)
        return dir.listFiles().orEmpty().sortedBy { it.name }.map { entry(it) }
    }

    override fun stat(documentId: String): DocumentEntry? = file(documentId).takeIf { it.exists() }?.let(::entry)

    override fun openInput(documentId: String, name: String): ImageInput = ImageInput.fromFile(file(documentId))

    override fun readBytes(documentId: String, maxBytes: Int): ByteArray {
        val f = file(documentId)
        if (f.length() > maxBytes) throw IOException("too big")
        return f.readBytes()
    }

    override fun writeBytes(documentId: String, bytes: ByteArray) = file(documentId).writeBytes(bytes)

    override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry {
        val f = File(file(parentDocumentId), name)
        check(f.createNewFile()) { "exists: $f" }
        return entry(f)
    }

    override fun uri(documentId: String): Uri = Uri.parse("file://${file(documentId).path}")

    private fun entry(f: File): DocumentEntry {
        val id = f.relativeTo(root).path
        return DocumentEntry(
            documentId = id,
            name = f.name,
            mimeType = if (f.isDirectory) "vnd.android.document/directory" else "application/octet-stream",
            size = if (f.isDirectory) 0 else f.length(),
            modified = f.lastModified(),
        )
    }
}
