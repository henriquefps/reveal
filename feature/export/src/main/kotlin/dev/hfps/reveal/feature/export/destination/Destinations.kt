package dev.hfps.reveal.feature.export.destination

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.hfps.reveal.feature.export.model.ExportDestination
import java.io.File
import java.io.IOException

/** The destination cannot be written (folder gone, SD card removed, grant revoked). */
class DestinationUnavailableException(cause: Throwable?) : IOException("destination unavailable", cause)

/**
 * Writes a finished export file (encoded in the cache) to where the user asked (docs/adr/0009, "Destinations"):
 * MediaStore `Pictures/Reveal` (scoped storage, `IS_PENDING` while writing), a SAF folder (a new document in the
 * persisted tree), or the share cache served by a [FileProvider]. [onPartial] receives the target before any byte is
 * written, so a run killed half-way can delete it on resume (idempotent export).
 */
class Destinations(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun deliver(
        file: File,
        name: String,
        mimeType: String,
        destination: ExportDestination,
        onPartial: (Uri) -> Unit,
    ): Uri = when (destination) {
        ExportDestination.Pictures -> toPictures(file, name, mimeType, onPartial)
        is ExportDestination.Folder -> toFolder(file, name, mimeType, destination.treeUri.toUri(), onPartial)
        ExportDestination.Share -> toShare(file, name)
    }

    private fun toPictures(file: File, name: String, mimeType: String, onPartial: (Uri) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw DestinationUnavailableException(null)
        onPartial(uri)
        copy(file, uri)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun toFolder(file: File, name: String, mimeType: String, tree: Uri, onPartial: (Uri) -> Unit): Uri {
        val uri = writable {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, mimeType, name)
        }
        onPartial(uri)
        copy(file, uri)
        return uri
    }

    private fun toShare(file: File, name: String): Uri {
        val dir = ShareCache.dir(context).apply { mkdirs() }
        // One sub-folder per file keeps the display name exact even when two exports share a name.
        val slot = File(dir, System.nanoTime().toString()).apply { mkdirs() }
        val target = File(slot, name)
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        return FileProvider.getUriForFile(context, ShareCache.authority(context), target)
    }

    private fun copy(file: File, uri: Uri) {
        writable {
            resolver.openOutputStream(uri, "w")?.use { o -> file.inputStream().use { it.copyTo(o, BUFFER) } }
        }
    }

    /**
     * Runs a write to the destination; any failure (missing folder, revoked grant, removed SD card, I/O error) or a
     * null result becomes [DestinationUnavailableException].
     */
    private inline fun <T : Any> writable(block: () -> T?): T {
        val result = runCatching(block)
        val failure = result.exceptionOrNull()
        if (failure is DestinationUnavailableException) throw failure
        return result.getOrNull() ?: throw DestinationUnavailableException(failure)
    }

    /** Deletes a half-written target of an interrupted run (best effort). */
    fun deletePartial(uri: Uri) {
        runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null)
            }
        }
    }

    /** Free bytes where the export is encoded (the cache) and, for the gallery, on shared storage. */
    fun freeBytes(destination: ExportDestination): Long {
        val cache = StatFs(context.cacheDir.path).availableBytes
        return when (destination) {
            ExportDestination.Pictures ->
                minOf(cache, StatFs(Environment.getExternalStorageDirectory().path).availableBytes)

            else -> cache
        }
    }

    companion object {
        /** The album in the gallery. */
        const val ALBUM = "Reveal"
        private const val BUFFER = 256 * 1024
    }
}

/**
 * The share cache: exports shared to other apps live in `cache/share/` only until the share sheet returns, and
 * whatever is left (the app was killed meanwhile) is swept at the next start.
 */
object ShareCache {
    fun dir(context: Context): File = File(context.cacheDir, "share")

    fun authority(context: Context): String = "${context.packageName}.exports"

    /** Deletes every shared file (called when the share sheet returns, and at app start). */
    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Number of files waiting in the share cache (tests). */
    fun count(context: Context): Int = dir(context).walkTopDown().count { it.isFile }
}
