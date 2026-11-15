@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.files

import android.os.SystemClock
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.storage.CacheArea
import dev.hfps.reveal.data.storage.CacheItem
import dev.hfps.reveal.data.storage.CacheTier
import dev.hfps.reveal.data.storage.StorageKind
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * A file the server serves, read by range through [cache], or from the kept whole copy once there is one ([kept],
 * ADR 0018 "W3/C": the blocks may be dropped after it was assembled).
 */
class RemoteFile internal constructor(
    val size: Long,
    private val key: String,
    private val cache: RangeCache,
    private val kept: () -> File? = { null },
    private val fetch: (Long, Int) -> ByteArray,
) {
    /** Reads up to [length] bytes at [offset] into [into]; 0 at the end. */
    fun read(offset: Long, length: Int, into: ByteArray): Int {
        val whole = kept()
        if (whole != null && whole.length() == size) {
            if (offset >= size || length <= 0) return 0
            val n = minOf(size - offset, length.toLong()).toInt()
            java.io.RandomAccessFile(whole, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(into, 0, n)
            }
            return n
        }
        return cache.read(key, size, offset, length, into, fetch)
    }
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

    /** Whole originals of photos opened in the editor (ADR 0018 "W3/C"): `<fingerprint hex>`, served as files. */
    private val kept = File(cacheDir, KEPT_DIR)
    private val keeping = ConcurrentHashMap.newKeySet<String>()
    private val keeper = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "reveal-keep-original")
    }

    @Volatile
    private var pinnedAt = 0L

    @Volatile
    private var pinnedKeys: Set<String> = emptySet()

    /**
     * The originals to keep (fingerprint hex): every file of the [PINNED_PHOTOS] photos opened most recently in the
     * last [PINNED_DAYS] days (ADR 0018 "W3/C"), memoised for a minute.
     */
    suspend fun pinned(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - pinnedAt < PINNED_TTL_MS) return pinnedKeys
        pinnedKeys = db.photoDao().recentlyOpenedFingerprints(now - PINNED_DAYS * DAY_MS, PINNED_PHOTOS)
            .mapTo(HashSet()) { it.removePrefix("v1:") }
        pinnedAt = now
        return pinnedKeys
    }

    /** Forgets the memo (a photo was just opened). */
    fun repin() {
        pinnedAt = 0
    }

    /** The kept whole original of [fingerprint], or null (its last use is refreshed). */
    fun keptOriginal(fingerprint: String): File? {
        val file = File(kept, fingerprint.removePrefix("v1:"))
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * Downloads the rest of [remote] in the background and keeps it as one file (ADR 0018 "W3/C"): an original opened
     * in the editor or the export then opens from this device, whole, as a plain file. One at a time; nothing when it
     * is kept already or being kept. The blocks are dropped once the file is complete.
     */
    fun keep(fingerprint: String, remote: RemoteFile) {
        val key = fingerprint.removePrefix("v1:")
        if (File(kept, key).isFile || !keeping.add(key)) return
        keeper.execute {
            try {
                val buffer = ByteArray(RangeCache.BLOCK.toInt())
                var offset = 0L
                while (offset < remote.size) {
                    val n = remote.read(offset, minOf(RangeCache.BLOCK, remote.size - offset).toInt(), buffer)
                    if (n <= 0) break
                    offset += n
                }
                kept.mkdirs()
                val temp = File(kept, "$key.tmp")
                if (ranges.complete(key, remote.size) && ranges.assemble(key, remote.size, temp) &&
                    temp.renameTo(File(kept, key))
                ) {
                    ranges.evict(listOf(key))
                    onTrimmed()
                }
                temp.delete()
            } catch (e: IOException) {
                android.util.Log.i("RevealSyncFiles", "An original could not be kept: ${e.javaClass.simpleName}")
            } finally {
                keeping.remove(key)
            }
        }
    }

    /**
     * The caches as budget areas (Settings -> Storage: "Server files", "Originals kept"). Blocks and kept originals of
     * photos opened lately are [CacheTier.PINNED]: evicted after every ordinary cache (ADR 0018 "W3/C").
     */
    val areas: List<CacheArea> = listOf(
        object : CacheArea {
            override val kind = StorageKind.SERVER_FILES

            override suspend fun items(): List<CacheItem> {
                val pinned = pinned()
                return ranges.files().map { (key, bytes, used) ->
                    CacheItem(key, bytes, used, if (key in pinned) CacheTier.PINNED else CacheTier.NORMAL)
                }
            }

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
        object : CacheArea {
            override val kind = StorageKind.ORIGINALS_KEPT

            override suspend fun items(): List<CacheItem> {
                val pinned = pinned()
                return kept.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map {
                    CacheItem(
                        it.name,
                        it.length(),
                        it.lastModified(),
                        if (it.name in pinned) CacheTier.PINNED else CacheTier.NORMAL,
                    )
                }.orEmpty()
            }

            override suspend fun evict(keys: Collection<String>) = keys.forEach { File(kept, it).delete() }

            override suspend fun clear() {
                kept.deleteRecursively()
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
        ranges.trim(pinned())
        onTrimmed()
        val key = fingerprint.removePrefix("v1:")
        return RemoteFile(size, key, ranges, { File(kept, key).takeIf { it.isFile } }) { offset, length ->
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
        kept.deleteRecursively()
        missing.clear()
    }

    /** Bytes held by the caches. */
    fun cachedBytes(): Long = ranges.bytes() + (previews.listFiles()?.sumOf { it.length() } ?: 0L) +
        (kept.listFiles()?.sumOf { it.length() } ?: 0L)

    private companion object {
        const val NEGATIVE_MS = 5 * 60 * 1000L
        const val HTTP_UNAVAILABLE = 503
        const val PREVIEW_CACHE_BYTES = 512L * 1024 * 1024
        const val KEPT_DIR = "sync-kept"
        const val PINNED_PHOTOS = 50
        const val PINNED_DAYS = 7L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val PINNED_TTL_MS = 60_000L
    }
}
