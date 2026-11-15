@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.hfps.image.format.ImageFormats
import dev.hfps.reveal.data.storage.FileOpens
import dev.hfps.reveal.data.sync.ServerTree
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.runBlocking

/** What the provider needs from the app graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServerFilesEntryPoint {
    fun serverFiles(): ServerFiles
}

/**
 * Serves the files of photos only the sync server has to the app's own readers (ADR 0018 "Server photos"), so the grid,
 * the viewer, the editor and the export open them like any document (URIs from [ServerTree]):
 *
 * - `original/<fingerprint>/<name>`: the original, read by range from `/v1/originals` through a seekable proxy
 *   descriptor and a block cache ([RangeCache]) (the editor, the export);
 * - `display/<fingerprint>/<name>`: the same, or when the server cannot serve it the display JPEG of the photo's smart
 *   preview (grid and viewer of a preview-only photo);
 * - `smart/<fingerprint>/<name>`: the photo's smart preview (the editor's developable proxy).
 *
 * Not exported: other apps never see these URIs.
 */
class ServerFilesProvider : ContentProvider() {
    private val files: ServerFiles by lazy {
        EntryPointAccessors.fromApplication(checkNotNull(context).applicationContext, ServerFilesEntryPoint::class.java)
            .serverFiles()
    }
    private val handler: Handler by lazy { Handler(HandlerThread("reveal-sync-files").apply { start() }.looper) }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val (kind, fingerprint) = parse(uri) ?: throw FileNotFoundException("unknown document")
        return try {
            runBlocking {
                when (kind) {
                    ServerTree.ORIGINAL -> openOriginal(fingerprint, fallback = false, keep = true)

                    ServerTree.DISPLAY -> openOriginal(fingerprint, fallback = true, keep = false)

                    ServerTree.SMART -> files.smartPreview(fingerprint)
                        ?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

                    else -> null
                }
            } ?: throw FileNotFoundException("not available")
        } catch (e: IOException) {
            Log.i(TAG, "A server file could not be opened: ${e.javaClass.simpleName}")
            throw FileNotFoundException("not available").apply { initCause(e) }
        }
    }

    /**
     * The original: the kept whole file when there is one (a plain descriptor, ADR 0018 "W3/C"), else a proxy
     * descriptor over the block cache. Each proxy costs an appfuse mount through vold: only what the user opens asks
     * for one (the viewer, the editor, the export, an edited thumbnail); the grid's thumbnails and the Server photos'
     * headers come from the server without any (W3/C), so they no longer mount dozens per second. Counted in
     * [FileOpens]. [keep]: the editor or the export opens it, so the rest is downloaded and kept as one file.
     */
    private suspend fun openOriginal(fingerprint: String, fallback: Boolean, keep: Boolean): ParcelFileDescriptor? {
        files.keptOriginal(fingerprint)?.let {
            FileOpens.count()
            return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val remote = files.original(fingerprint)
        if (remote != null) {
            if (keep) files.keep(fingerprint, remote)
            val storage = checkNotNull(context).getSystemService(StorageManager::class.java)
            FileOpens.count()
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, RangeCallback(remote), handler)
        }
        if (!fallback) return null
        return files.previewJpeg(fingerprint)?.let {
            ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    /** Reads of the proxy descriptor, answered from the block cache or by range. */
    private class RangeCallback(private val remote: RemoteFile) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = remote.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
            remote.read(offset, size, data)
        } catch (e: IOException) {
            Log.i(TAG, "A range read failed: ${e.javaClass.simpleName}")
            throw ErrnoException("read", OsConstants.EIO)
        }

        override fun onRelease() = Unit
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray()?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val name = uri.lastPathSegment.orEmpty()
        val size = parse(uri)?.let { (_, fingerprint) -> runBlocking { files.knownSize(fingerprint) } }
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) name else size })
        }
    }

    override fun getType(uri: Uri): String? = when (parse(uri)?.first) {
        ServerTree.SMART -> "application/octet-stream"

        else -> ImageFormats.classify(uri.lastPathSegment.orEmpty()).let { type ->
            if (type.isRaw) "image/x-raw" else "image/*"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun parse(uri: Uri): Pair<String, String>? {
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val fingerprint = segments[1]
        if (!FINGERPRINT.matches(fingerprint)) return null
        return segments[0] to fingerprint
    }

    private companion object {
        const val TAG = "RevealSyncFiles"
        val FINGERPRINT = Regex("v1:[0-9a-f]{64}")
    }
}
