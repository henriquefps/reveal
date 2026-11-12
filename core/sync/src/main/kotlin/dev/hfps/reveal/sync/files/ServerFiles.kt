@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.files

import android.os.SystemClock
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.storage.CacheArea
import dev.hfps.reveal.data.storage.CacheItem
import dev.hfps.reveal.data.storage.StorageKind
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
class ServerFiles(
    private val db: RevealDatabase,
    private val api: () -> SyncApi?,
    cacheDir: File,
    /** The caches' cap on their own; the app passes Settings' maximum cache size (ADR 0018 FIX/W). */
    private val maxBytes: () -> Long = { RangeCache.DEFAULT_MAX_BYTES },
    /** After a trim: the app's cache budget trims all caches together. */
    private val onTrimmed: () -> Unit = {},
) {
    private val ranges = RangeCache(File(cacheDir, "sync-originals"), maxBytes)
    private val previews = File(cacheDir, "sync-previews")

    /** The two caches as budget areas (Settings -> Storage: "Server files"). */
    val areas: List<CacheArea> = listOf(
        object : CacheArea {
            override val kind = StorageKind.SERVER_FILES

            override suspend fun items() = ranges.files().map { (key, bytes, used) -> CacheItem(key, bytes, used) }

            override suspend fun evict(keys: Collection<String>) = ranges.evict(keys)

            override suspend fun clear() = ranges.clear()
        },
        object : CacheArea {
            override val kind = StorageKind.SERVER_FILES

            override suspend fun items() = previews.listFiles()?.filter { it.isFile }
                ?.map { CacheItem(it.name, it.length(), it.lastModified()) }.orEmpty()

            override suspend fun evict(keys: Collection<String>) = keys.forEach { File(previews, it).delete() }

            override suspend fun clear() {
                previews.deleteRecursively()
                missing.clear()
            }
        },
    )

    /** Fingerprints the server could not serve, with when it said so (asked again after [NEGATIVE_MS]). */
    private val missing = ConcurrentHashMap<String, Long>()

    suspend fun knownSize(fingerprint: String): Long? = db.photoDao().filesByFingerprint(listOf(fingerprint))
        .firstOrNull()?.file?.size?.takeIf { it > 0 }

    /**
     * The original of [fingerprint] when the server can serve it (NAS or backup), else null. What this device knows is
     * per file (`photo_files.on_server`): a RAW + JPEG shot often has only its RAW on the server, and trusting the
     * photo's flag for its JPEG read a file the server never had (`404`, FIX/V). Anything not known is asked first.
     */
    suspend fun original(fingerprint: String): RemoteFile? {
        val client = api() ?: return null
        val onServer = db.syncDao().knownOnServer(fingerprint)
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
        onTrimmed()
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
        val cap = minOf(PREVIEW_CACHE_BYTES, maxBytes())
        for (file in files) {
            if (total <= cap) break
            total -= file.length()
            file.delete()
        }
        onTrimmed()
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
