package dev.hfps.reveal.data.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import dev.hfps.image.ImageInput
import dev.hfps.image.source.FileFingerprint
import java.io.FileNotFoundException
import java.io.IOException

/** One document of a tree as listed (a single `DocumentsContract` row). */
data class DocumentEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long,
    val flags: Int = 0,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/**
 * The content fingerprint of a document (`FileFingerprint`: size, first and last MiB; ADR 0006 "Identity"), or null
 * when it cannot be read. Blocks: run it on an IO dispatcher.
 */
fun DocumentTree.fingerprint(documentId: String, name: String): String? = runCatching {
    openInput(documentId, name).use { input -> input.withSource(FileFingerprint::of) }
}.onFailure { Log.i("RevealIdentity", "No fingerprint for a document: ${it.javaClass.simpleName}") }.getOrNull()

/**
 * Read (and sidecar write) access to one added folder. The SAF implementation is [SafDocumentTree]; tests use an
 * in-memory or file-backed one. Every call blocks: run it on an IO dispatcher. Originals are only ever opened for
 * reading; the only writes are XMP sidecars.
 */
interface DocumentTree {
    val rootDocumentId: String

    /** The children of a directory document. Throws [IOException] (or `SecurityException`) when unreachable. */
    fun children(documentId: String): List<DocumentEntry>

    /** One document's current row, or null when it no longer exists. */
    fun stat(documentId: String): DocumentEntry?

    /** Opens a document for reading through its descriptor (never copied). */
    fun openInput(documentId: String, name: String): ImageInput

    /** Reads a small document fully (sidecars), at most [maxBytes]. */
    fun readBytes(documentId: String, maxBytes: Int): ByteArray

    /** Replaces a document's content (truncating). */
    fun writeBytes(documentId: String, bytes: ByteArray)

    /** Creates a document in a directory and returns its row. The provider may adjust the name. */
    fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry

    /** The content URI of a document (to hand to the editor or another app). */
    fun uri(documentId: String): Uri
}

/** A Storage Access Framework tree (`ACTION_OPEN_DOCUMENT_TREE` grant). */
class SafDocumentTree(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    override val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : DocumentTree {

    override fun children(documentId: String): List<DocumentEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = resolver.query(uri, COLUMNS, null, null, null)
            ?: throw FileNotFoundException("No listing for $documentId")
        return cursor.use { c ->
            val out = ArrayList<DocumentEntry>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) out += c.entry()
            out
        }
    }

    override fun stat(documentId: String): DocumentEntry? = runCatching {
        resolver.query(uri(documentId), COLUMNS, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.entry() else null
        }
    }.getOrNull()

    override fun openInput(documentId: String, name: String): ImageInput =
        ImageInput.fromUri(resolver, uri(documentId), name)

    override fun readBytes(documentId: String, maxBytes: Int): ByteArray {
        val stream = resolver.openInputStream(uri(documentId)) ?: throw FileNotFoundException(documentId)
        return stream.use { input ->
            val bytes = input.readNBytesCompat(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("Document $documentId is larger than $maxBytes bytes")
            bytes
        }
    }

    override fun writeBytes(documentId: String, bytes: ByteArray) {
        val stream = resolver.openOutputStream(uri(documentId), "wt") ?: throw FileNotFoundException(documentId)
        stream.use { it.write(bytes) }
    }

    override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: throw IOException("The provider refused to create $name")
        val id = DocumentsContract.getDocumentId(created)
        return stat(id) ?: DocumentEntry(id, name, mimeType, 0, System.currentTimeMillis())
    }

    override fun uri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun Cursor.entry(): DocumentEntry = DocumentEntry(
        documentId = getString(COL_ID),
        name = getString(COL_NAME).orEmpty(),
        mimeType = getString(COL_MIME).orEmpty(),
        size = if (isNull(COL_SIZE)) 0 else getLong(COL_SIZE),
        modified = if (isNull(COL_MODIFIED)) 0 else getLong(COL_MODIFIED),
        flags = if (isNull(COL_FLAGS)) 0 else getInt(COL_FLAGS),
    )

    companion object {
        private const val COL_ID = 0
        private const val COL_NAME = 1
        private const val COL_MIME = 2
        private const val COL_SIZE = 3
        private const val COL_MODIFIED = 4
        private const val COL_FLAGS = 5

        /** In the order of the `COL_` indices. */
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

/** `InputStream.readNBytes` is API 33; this reads up to [limit] bytes on every API level. */
internal fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    var remaining = limit
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}

private const val BUFFER = 16 * 1024
