package dev.hfps.reveal.data.handoff

import android.net.Uri
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import java.io.File
import java.io.IOException

/**
 * A [DocumentTree] whose document ids are `file:` URIs (the JVM has no content provider to hand-off from), plus
 * [aliases]: `content:` URIs standing for a file, as MediaStore's `.../images/media/<id>` (the same parent for every
 * photo, whatever its name). Documents in [refused] throw a `SecurityException`, as a gallery URI does once its
 * temporary grant ended (ADR 0011 "Unreadable hand-offs").
 */
internal class FileUriTree : DocumentTree {
    override val rootDocumentId = ""
    val aliases = HashMap<String, File>()
    val refused = HashSet<String>()

    private fun file(id: String): File {
        if (id in refused) throw SecurityException("no access to $id")
        return aliases[id] ?: File(Uri.parse(id).path!!)
    }

    override fun children(documentId: String): List<DocumentEntry> = emptyList()

    override fun stat(documentId: String): DocumentEntry? = runCatching { file(documentId) }.getOrNull()
        ?.takeIf { it.isFile }?.let { DocumentEntry(documentId, it.name, "", it.length(), it.lastModified()) }

    override fun openInput(documentId: String, name: String): ImageInput = ImageInput.fromFile(file(documentId))

    override fun readBytes(documentId: String, maxBytes: Int): ByteArray = file(documentId).readBytes()

    override fun writeBytes(documentId: String, bytes: ByteArray) = file(documentId).writeBytes(bytes)

    override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry =
        throw IOException("no folder")

    override fun uri(documentId: String): Uri = Uri.parse(documentId)
}
