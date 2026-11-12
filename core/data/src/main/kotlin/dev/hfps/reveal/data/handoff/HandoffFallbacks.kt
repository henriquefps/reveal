@file:Suppress("ReturnCount")

package dev.hfps.reveal.data.handoff

import android.net.Uri
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec

/**
 * Where a photo is read from when its hand-off document cannot be read any more (ADR 0011 "Fallbacks"), in order:
 *
 * 1. [original]: the original on the sync server (`photo_cloud.original_on_server`: a NAS file or a backup), read by
 *    range through `ServerFilesProvider` like a Server photo: full quality, no prompt;
 * 2. [preview]: a smart preview, the local one ([LocalPreviews]) first, else the server's (`photo_cloud.preview_sha`):
 *    editing works, the export is at preview size;
 * 3. nothing: the caller reports the error (share it again from the sending app).
 *
 * The editor, the viewer, the thumbnails and the export all ask this one place. Nothing here reads the document itself:
 * callers try it first and come here on an access error (`isAccessError`).
 */
class HandoffFallbacks(
    private val db: RevealDatabase,
    private val previews: LocalPreviews?,
    /** `ServerTree.authority(package)`; null where there is no Server provider (JVM tests without sync). */
    private val serverAuthority: String?,
) {
    /** The fallbacks of [file] (the photo's main file), each null when absent. */
    suspend fun of(file: PhotoFileEntity): Fallback = Fallback(original(file), preview(file))

    /** The server's copy of [file]'s original, or null (no server, not on it, no fingerprint yet). */
    suspend fun original(file: PhotoFileEntity): Uri? {
        val authority = serverAuthority ?: return null
        val fingerprint = file.fingerprint ?: return null
        if (db.syncDao().cloud(file.photoId)?.originalOnServer != true) return null
        return ServerTree.uri(authority, ServerTree.ORIGINAL, ServerTree.documentId(fingerprint, file.name))
    }

    /** A smart preview of [file]: the local one (a `file:` URI), else the server's; null without either. */
    suspend fun preview(file: PhotoFileEntity): Uri? {
        previews?.smart(file.fingerprint)?.let { return Uri.fromFile(it) }
        val authority = serverAuthority ?: return null
        val fingerprint = file.fingerprint ?: return null
        if (db.syncDao().cloud(file.photoId)?.previewSha == null) return null
        return ServerTree.smartUri(authority, fingerprint, file.name)
    }

    /** The local display JPEG of [file] (the display image, else the smart preview's), or null. */
    fun localDisplayJpeg(file: PhotoFileEntity): ByteArray? {
        val local = previews ?: return null
        val bytes = local.display(file.fingerprint)?.readBytes()
            ?: local.smart(file.fingerprint)?.inputStream()?.use(SmartPreviewCodec::readJpeg)
        return bytes?.takeIf { it.isNotEmpty() }
    }

    /**
     * What the grid and the viewer read from the server for [file]: the original, or the smart preview's display JPEG
     * when the server lacks it (`ServerTree.DISPLAY`); null when the server has neither.
     */
    suspend fun serverDisplay(file: PhotoFileEntity): Uri? {
        val authority = serverAuthority ?: return null
        val fingerprint = file.fingerprint ?: return null
        val cloud = db.syncDao().cloud(file.photoId) ?: return null
        if (!cloud.originalOnServer && cloud.previewSha == null) return null
        return ServerTree.uri(authority, ServerTree.DISPLAY, ServerTree.documentId(fingerprint, file.name))
    }
}

/** The fallbacks of an unreadable hand-off: [original] (full quality) and [preview] (a smart preview). */
data class Fallback(val original: Uri?, val preview: Uri?) {
    val isEmpty: Boolean get() = original == null && preview == null
}
