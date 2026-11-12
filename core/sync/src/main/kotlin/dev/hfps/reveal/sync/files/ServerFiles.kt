@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.files

import android.os.SystemClock
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A file the server serves, read by range through [cache]. */
class RemoteFile internal constructor(
    val size: Long,
    private val key: String,
    private val cache: RangeCache,
    private val fetch: (Long, Int) -> ByteArray,
) {
    /** Reads up to [length] bytes at [offset] into [into]; 0 at the end. */
    fun read(offset: Long, length: Int, into: ByteArray): Int = cache.read(key, size, offset, length, into, fetch)
}

/**
 * The files of server photos for [ServerFilesProvider] (ADR 0018 "Server photos"): originals by range (sizes from the
 * library, so cached blocks read offline too), smart previews downloaded whole into the cache, and the display JPEG
 * inside a preview. [api] is the paired server's client, null when not paired.
 */
class ServerFiles(private val db: RevealDatabase, private val api: () -> SyncApi?, cacheDir: File) {
    private val ranges = RangeCache(File(cacheDir, "sync-originals"))
    private val previews = File(cacheDir, "sync-previews")

    /** Fingerprints the server could not serve, with when it said so (asked again after [NEGATIVE_MS]). */
    private val missing = ConcurrentHashMap<String, Long>()

    suspend fun knownSize(fingerprint: String): Long? = db.photoDao().filesByFingerprint(listOf(fingerprint))
        .firstOrNull()?.file?.size?.takeIf { it > 0 }

    /** The original of [fingerprint] when the server can serve it (NAS or backup), else null. */
    suspend fun original(fingerprint: String): RemoteFile? {
        val client = api() ?: return null
        val photoId = db.photoDao().filesByFingerprint(listOf(fingerprint)).firstOrNull()?.file?.photoId
        val onServer = photoId?.let { db.syncDao().cloud(it) }?.originalOnServer == true
        var size = knownSize(fingerprint)
        if (!onServer || size == null) {
            val since = missing[fingerprint]
            if (since != null && SystemClock.elapsedRealtime() - since < NEGATIVE_MS) return null
            size = try {
                client.originalSize(fingerprint)
            } catch (e: SyncHttpException) {
                if (e.status == HTTP_UNAVAILABLE) throw IOException("the server is re-indexing this file", e) else null
            }
            if (size == null) {
                missing[fingerprint] = SystemClock.elapsedRealtime()
                return null
            }
        }
        ranges.trim()
        return RemoteFile(size, fingerprint.removePrefix("v1:"), ranges) { offset, length ->
            client.readOriginal(fingerprint, offset, length)
        }
    }

    /** The smart preview of the photo whose main file is [fingerprint], downloaded into the cache; null without one. */
    suspend fun smartPreview(fingerprint: String): File? {
        val file = db.photoDao().filesByFingerprint(listOf(fingerprint)).firstOrNull()?.file ?: return null
        val sha = db.syncDao().cloud(file.photoId)?.previewSha ?: return null
        val target = File(previews, "$sha.rvsp")
        if (target.exists()) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val client = api() ?: return null
        previews.mkdirs()
        val temp = File(previews, "$sha.tmp")
        try {
            client.downloadBlob(sha) { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) throw IOException("could not store a preview")
        } finally {
            temp.delete()
        }
        trimPreviews()
        return target
    }

    /** The display JPEG of [fingerprint]'s smart preview (what the grid and viewer show of a preview-only photo). */
    suspend fun previewJpeg(fingerprint: String): File? {
        val preview = smartPreview(fingerprint) ?: return null
        val jpeg = File(previews, preview.nameWithoutExtension + ".jpg")
        if (!jpeg.exists()) {
            val bytes = preview.inputStream().use(SmartPreviewCodec::readJpeg)
            if (bytes.isEmpty()) return null
            val temp = File(previews, preview.nameWithoutExtension + ".jpg.tmp")
            temp.writeBytes(bytes)
            temp.renameTo(jpeg)
        }
        return jpeg
    }

    private fun trimPreviews() {
        val files = previews.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= PREVIEW_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    /** Forgets every cached server file (unpair, "clear cache"). */
    fun clear() {
        ranges.clear()
        previews.deleteRecursively()
        missing.clear()
    }

    /** Bytes held by the caches. */
    fun cachedBytes(): Long = ranges.bytes() + (previews.listFiles()?.sumOf { it.length() } ?: 0L)

    private companion object {
        const val NEGATIVE_MS = 5 * 60 * 1000L
        const val HTTP_UNAVAILABLE = 503
        const val PREVIEW_CACHE_BYTES = 512L * 1024 * 1024
    }
}
