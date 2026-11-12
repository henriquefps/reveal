package dev.hfps.reveal.data.sync

import android.content.ContentResolver
import android.net.Uri
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import java.io.IOException

/**
 * The [DocumentTree] of the Server source (ADR 0018 "Server photos"): photos only the sync server has. A document id is
 * `<fingerprint>/<file name>`; its URIs are served by `:core:sync`'s `ServerFilesProvider` (`<package>.sync`), which
 * reads the original by range from the server, so thumbnails, the viewer, the editor and the export open these files
 * like any other: [uri] is the original only, [openInput] (thumbnails, viewer) falls back to the smart preview's JPEG.
 * Nothing is ever written.
 */
class ServerTree(private val resolver: ContentResolver, private val authority: String) : DocumentTree {
    override val rootDocumentId: String = ""

    override fun children(documentId: String): List<DocumentEntry> = emptyList()

    override fun stat(documentId: String): DocumentEntry? =
        DocumentEntry(documentId, documentId.substringAfter('/'), "", 0, 0)

    /** For the grid and the viewer: the original, or the smart preview's display JPEG when the server lacks it. */
    override fun openInput(documentId: String, name: String): ImageInput =
        ImageInput.fromUri(resolver, uri(authority, DISPLAY, documentId), name)

    override fun readBytes(documentId: String, maxBytes: Int): ByteArray =
        throw IOException("Server photos have no sidecars")

    override fun writeBytes(documentId: String, bytes: ByteArray) = throw IOException("Server photos are read only")

    override fun create(parentDocumentId: String, name: String, mimeType: String): DocumentEntry =
        throw IOException("Server photos are read only")

    /** The original only (the editor and the export): never a stand-in. */
    override fun uri(documentId: String): Uri = uri(authority, ORIGINAL, documentId)

    companion object {
        const val ORIGINAL = "original"
        const val DISPLAY = "display"
        const val SMART = "smart"

        /** The provider's authority in an app whose package is [packageName]. */
        fun authority(packageName: String) = "$packageName.sync"

        /** The document id of a server file. */
        fun documentId(fingerprint: String, name: String) = "$fingerprint/$name"

        /** The fingerprint in a server document id. */
        fun fingerprint(documentId: String) = documentId.substringBefore('/')

        fun uri(authority: String, kind: String, documentId: String): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority)
            .appendPath(kind)
            .appendPath(fingerprint(documentId))
            .appendPath(documentId.substringAfter('/', fingerprint(documentId)))
            .build()

        /** The smart preview URI of any photo's main file (the editor opens it when the original is out of reach). */
        fun smartUri(authority: String, fingerprint: String, name: String): Uri =
            uri(authority, SMART, documentId(fingerprint, name))
    }
}
