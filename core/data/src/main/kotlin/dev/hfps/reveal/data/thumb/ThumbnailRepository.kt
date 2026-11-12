package dev.hfps.reveal.data.thumb

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import dev.hfps.image.ImageInput
import dev.hfps.image.cache.CacheKeys
import dev.hfps.image.cache.FileIdentity
import dev.hfps.image.cache.Variant
import dev.hfps.image.decode.BitmapDecoding
import dev.hfps.image.decode.Sampling
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.raw.RawDecoder
import dev.hfps.image.source.readRange
import dev.hfps.reveal.data.db.CacheEntryEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.handoff.HandoffFallbacks
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.isAccessError
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.pipeline.PIPELINE_VERSION
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.preset.ApplyContext
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Grid thumbnails (ADR 0006, "Thumbnails"): the RAW's embedded preview (the fast path: two small reads), else the
 * camera/phone JPEG subsampled while decoding; for an edited photo, a small render of its recipe through the
 * develop pipeline ([EditedThumbnails]). Upright, long edge [EDGE] px, kept in a memory LRU and as JPEGs in
 * `cacheDir/thumbs`, indexed in `cache_entries` with an LRU cap of [maxBytes]. An unplugged source shows what is
 * cached and nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailRepository(
    private val db: RevealDatabase,
    private val trees: DocumentTreeFactory,
    private val edits: EditRepository,
    private val edited: EditedThumbnails?,
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Hand-offs Reveal can no longer read show their local preview (ADR 0011 "Unreadable hand-offs"). */
    private val previews: LocalPreviews? = null,
    /** Reads the server's copy of an unreadable hand-off (ADR 0011 "Fallbacks"); null without sync (tests). */
    private val resolver: ContentResolver? = null,
    private val serverAuthority: String? = null,
) {
    private val fallbacks = HandoffFallbacks(db, previews, serverAuthority)
    private val cache = db.cacheDao()
    private val photos = db.photoDao()
    private val io = Dispatchers.IO.limitedParallelism(PARALLELISM)
    private val memory = object : LruCache<String, Bitmap>(memoryBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val touched = ConcurrentHashMap.newKeySet<String>()
    private val puts = AtomicInteger()

    /** The thumbnail if it is in memory already (lets a recycled grid cell draw in the same frame). */
    fun inMemory(photoId: Long, editTime: Long?): Bitmap? = memory.get(memoryKey(photoId, editTime))

    /**
     * The thumbnail of [photoId]; [editTime] is the time its recipe last changed (null = not edited), so a new edit
     * gets a new thumbnail. Null when nothing can be shown (unplugged and not cached, unreadable file).
     */
    suspend fun thumbnail(photoId: Long, versionId: Long, editTime: Long?): Bitmap? {
        val memoryKey = memoryKey(photoId, editTime)
        memory.get(memoryKey)?.let { return it }
        return withContext(io) {
            memory.get(memoryKey) ?: load(photoId, versionId, editTime)?.also { memory.put(memoryKey, it) }
        }
    }

    /** What a thumbnail is made from: the photo's main file (the RAW of a pair) and its camera rendering. */
    private class Target(
        val photoId: Long,
        val sourceId: Long,
        val main: PhotoFileEntity,
        val rendered: PhotoFileEntity?,
        val identity: FileIdentity,
        /** A Server photo: a failed read means the server is out of reach, never that the file is unreadable. */
        val server: Boolean = false,
    )

    private suspend fun target(photoId: Long): Target? {
        val photo = photos.get(photoId)
        val server = photo?.let { db.sourceDao().get(it.sourceId) }?.kind == SourceKind.SERVER
        // A Server photo's thumbnail comes from a file the server holds (FIX/V).
        val files = photos.files(photoId).let { all -> if (server) ServerTree.reachable(all) { it.onServer } else all }
        val main = files.firstOrNull { it.role == FileRole.RAW } ?: files.firstOrNull { it.role == FileRole.RENDERED }
        if (photo == null || main == null) return null
        val identity = FileIdentity(photo.sourceId.toString(), main.relativePath, main.size, main.modified)
        return Target(
            photoId,
            photo.sourceId,
            main,
            files.firstOrNull {
                it.role == FileRole.RENDERED
            },
            identity,
            server,
        )
    }

    private suspend fun load(photoId: Long, versionId: Long, editTime: Long?): Bitmap? {
        val target = target(photoId) ?: return null
        return editTime?.let { loadEdited(target, versionId, it) } ?: loadPreview(target)
    }

    private suspend fun loadEdited(target: Target, versionId: Long, editTime: Long): Bitmap? {
        if (edited == null) return null
        val key = CacheKeys.key(target.identity, Variant.THUMB, "edit:$versionId:$editTime:$PIPELINE_VERSION:$EDGE")
        return fromDisk(key)
            ?: renderEdited(target, versionId, editTime)?.also { save(key, target.photoId, KIND_EDITED, it) }
    }

    private suspend fun loadPreview(target: Target): Bitmap? {
        val key = CacheKeys.key(target.identity, Variant.THUMB, EDGE)
        return fromDisk(key) ?: decodePreview(target)?.also { save(key, target.photoId, KIND_PREVIEW, it) }
    }

    /** An available source's tree, or null (unplugged, access lost, removed). */
    private suspend fun availableTree(sourceId: Long): DocumentTree? =
        db.sourceDao().get(sourceId)?.takeIf { it.state == SourceState.AVAILABLE }?.let(trees::open)

    @Suppress("ReturnCount")
    private suspend fun decodePreview(target: Target): Bitmap? {
        val tree = availableTree(target.sourceId) ?: return null
        val main = target.main
        return try {
            tree.openInput(main.documentId, main.name).use { input ->
                val rendered = target.rendered?.takeIf { it !== main }
                embeddedPreview(input)
                    ?: rendered?.let { tree.openInput(it.documentId, it.name).use(::decodeStandard) }
                    ?: decodeStandard(input)
            }.also { if (main.unreadableAt != null) photos.setReadable(main.documentId) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (isAccessError(e) && !target.server) {
                // A hand-off whose grant ended (ADR 0011): remember it, and show its local preview.
                photos.setUnreadable(main.documentId, clock())
                localPreview(main)?.let { return it }
                serverPreview(main)?.let { return it }
                Log.i(TAG, "No access to photo ${target.photoId} and no preview")
                return null
            }
            Log.w(TAG, "No thumbnail for photo ${target.photoId}", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No memory for the thumbnail of photo ${target.photoId}", e)
            null
        }
    }

    @Suppress("ReturnCount")
    private suspend fun renderEdited(target: Target, versionId: Long, editTime: Long): Bitmap? {
        val renderer = edited
        val tree = availableTree(target.sourceId)
        if (renderer == null || tree == null) return null
        return try {
            val recipe = edits.recipe(versionId)
            tree.openInput(target.main.documentId, target.main.name).use { renderer.render(it, recipe, EDGE) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            if (isAccessError(e) && !target.server) {
                photos.setUnreadable(target.main.documentId, clock())
                return renderFallback(renderer, target, versionId)
            }
            Log.w(TAG, "Edited thumbnail of version $versionId ($editTime) failed; showing the original", e)
            null
        }
    }

    /**
     * A small upright linear copy of [versionId]'s photo for the photo analysis (smart presets in a batch,
     * docs/adr/0020): the editor's proxy decode at [longEdge] px, mapped like the editor's input. `null` when the
     * source is offline.
     */
    suspend fun analysisInput(versionId: Long, longEdge: Int = ANALYSIS_EDGE): PipelineInput? = withContext(io) {
        val photoId = photos.photoIdOfVersion(versionId) ?: return@withContext null
        val target = target(photoId) ?: return@withContext null
        val tree = availableTree(target.sourceId) ?: return@withContext null
        try {
            tree.openInput(target.main.documentId, target.main.name).use { input ->
                val orientation = if (input.type.isRaw) {
                    null
                } else {
                    runCatching {
                        input.inspection.metadata.orientation
                    }.getOrNull()
                }
                val proxy = ProxyBuilder.decodeProxy(input, longEdge)
                val metadata = runCatching { input.inspection.metadata }.getOrNull()
                PipelineInputMapper.map(proxy.image, proxy.info, orientation ?: proxy.info.orientation, metadata)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "No analysis input for version $versionId", e)
            null
        }
    }

    /**
     * What a preset's strength needs to know about the photo of [versionId] (docs/adr/0019 "Strength"): its RAW's
     * as-shot white balance, read from the header only (no decode, a few ms), so a batch blends every photo from its
     * own as-shot value. [ApplyContext.UNKNOWN] for a JPEG, a RAW without a colour matrix or an offline source.
     */
    suspend fun applyContext(versionId: Long): ApplyContext = withContext(io) {
        val photoId = photos.photoIdOfVersion(versionId) ?: return@withContext ApplyContext.UNKNOWN
        val target = target(photoId) ?: return@withContext ApplyContext.UNKNOWN
        val tree = availableTree(target.sourceId) ?: return@withContext ApplyContext.UNKNOWN
        try {
            tree.openInput(target.main.documentId, target.main.name).use { input ->
                if (!input.type.isRaw) return@use ApplyContext.UNKNOWN
                ApplyContext.of(PipelineInputMapper.asShot(RawDecoder.readInfo(input.descriptor)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "No as-shot white balance for version $versionId", e)
            ApplyContext.UNKNOWN
        }
    }

    /** The local display image of an unreadable hand-off (ADR 0011), scaled to [EDGE]; null without one. */
    private fun localPreview(main: PhotoFileEntity): Bitmap? {
        val bytes = fallbacks.localDisplayJpeg(main) ?: return null
        return BitmapDecoding.decode(bytes, EDGE, 1).bitmap
    }

    /**
     * An unreadable hand-off's thumbnail from the sync server (ADR 0011 "Fallbacks"): the original's embedded preview
     * read by range, or the server's smart preview JPEG; null when the server has neither or cannot be reached.
     */
    @Suppress("ReturnCount")
    private suspend fun serverPreview(main: PhotoFileEntity): Bitmap? {
        val resolver = resolver ?: return null
        val uri = fallbacks.serverDisplay(main) ?: return null
        return try {
            ImageInput.fromUri(resolver, uri, main.name).use { input ->
                runCatching { embeddedPreview(input) }.getOrNull() ?: decodeStandard(input)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.i(TAG, "No server preview for a hand-off: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * The edit of an unreadable hand-off (ADR 0011 "Fallbacks"): rendered from its smart preview (local, else the
     * server's), else from the server's original; null without any.
     */
    @Suppress("ReturnCount")
    private suspend fun renderFallback(renderer: EditedThumbnails, target: Target, versionId: Long): Bitmap? {
        val recipe = edits.recipe(versionId)
        return try {
            val preview = fallbacks.preview(target.main)
            val local = preview?.takeIf { it.scheme == "file" }?.path?.let(::File)
            val decoded = when {
                local != null -> local.inputStream().use(SmartPreviewCodec::decode)
                preview != null -> resolver?.openInputStream(preview)?.use(SmartPreviewCodec::decode)
                else -> null
            }
            if (decoded != null) {
                return renderer.renderPreview(decoded.input, maxOf(decoded.fullWidth, decoded.fullHeight), recipe, EDGE)
            }
            val resolver = resolver ?: return null
            val original = fallbacks.original(target.main) ?: return null
            ImageInput.fromUri(resolver, original, target.main.name).use { renderer.render(it, recipe, EDGE) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.i(TAG, "No fallback render of version $versionId: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The embedded JPEG of a RAW (or a JPEG's own EXIF thumbnail is too small: never used). */
    private fun embeddedPreview(input: ImageInput): Bitmap? {
        val inspection = input.inspection
        val preview = inspection.takeIf { it.type.isRaw }?.previewFor(EDGE) ?: return null
        val bytes = input.withSource { it.readRange(preview.offset, preview.length.toInt()) }
        return BitmapDecoding.decode(bytes, EDGE, inspection.metadata.orientation).bitmap
    }

    /** A JPEG/HEIF/PNG/WebP decoded subsampled straight from its descriptor (no full-size bitmap, no copy). */
    private fun decodeStandard(input: ImageInput): Bitmap {
        val fd = input.descriptor.fileDescriptor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not decodable: ${input.name}")
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        val options = BitmapFactory.Options().apply {
            inSampleSize = Sampling.sampleSize(bounds.outWidth, bounds.outHeight, EDGE)
        }
        val decoded =
            BitmapFactory.decodeFileDescriptor(fd, null, options) ?: throw IOException("Decode failed: ${input.name}")
        val orientation = runCatching { input.inspection.metadata.orientation }.getOrDefault(1)
        return BitmapDecoding.orient(scaleDown(decoded), orientation)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val long = maxOf(bitmap.width, bitmap.height)
        if (long <= EDGE) return bitmap
        val scale = EDGE.toFloat() / long
        val scaled = bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    // ------------------------------------------------------------------ disk cache

    private suspend fun fromDisk(key: String): Bitmap? {
        val entry = cache.get(key) ?: return null
        val file = File(dir, entry.fileName)
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        if (bitmap == null) {
            cache.delete(listOf(key))
        } else {
            touched += key
            if (touched.size >= TOUCH_BATCH) flushTouches()
        }
        return bitmap
    }

    private suspend fun save(key: String, photoId: Long, kind: String, bitmap: Bitmap) {
        runCatching {
            dir.mkdirs()
            val name = "$key.jpg"
            val file = File(dir, name)
            val temp = File(dir, "$name.tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!temp.renameTo(file)) throw IOException("rename failed")
            cache.put(CacheEntryEntity(key, photoId, kind, name, file.length(), clock()))
        }.onFailure { Log.w(TAG, "Could not cache thumbnail $key", it) }
        if (puts.incrementAndGet() % TRIM_EVERY == 0) trim()
    }

    private suspend fun flushTouches() {
        val keys = touched.toList()
        touched.removeAll(keys.toSet())
        keys.chunked(SQL_ARGS).forEach { cache.touch(it, clock()) }
    }

    /** Deletes the least recently used thumbnails until the cache is under [maxBytes]; drops orphan files. */
    suspend fun trim() = withContext(io) {
        flushTouches()
        var total = cache.totalBytes()
        while (total > maxBytes) {
            val oldest = cache.oldest(TRIM_BATCH)
            if (oldest.isEmpty()) break
            oldest.forEach { File(dir, it.fileName).delete() }
            cache.delete(oldest.map { it.key })
            total -= oldest.sumOf { it.bytes }
        }
        val known = cache.fileNames().toHashSet()
        dir.listFiles()?.filter { it.isFile && it.name !in known }?.forEach { it.delete() }
    }

    /** Settings' "clear cache". */
    suspend fun clear() = withContext(io) {
        memory.evictAll()
        cache.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun memoryKey(photoId: Long, editTime: Long?) = "$photoId:${editTime ?: 0}"

    companion object {
        private const val TAG = "RevealThumbs"

        /** Long edge of a thumbnail: a 3-column cell on a 1440 px wide phone is 480 px. */
        const val EDGE = 512
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024

        /** Long edge of the analysis input (the features need 576 px; lines are better with more). */
        const val ANALYSIS_EDGE = 1200
        private const val PARALLELISM = 3
        private const val JPEG_QUALITY = 85
        private const val TRIM_EVERY = 50
        private const val TRIM_BATCH = 200
        private const val TOUCH_BATCH = 64
        private const val SQL_ARGS = 900
        private const val KIND_PREVIEW = "thumb"
        private const val KIND_EDITED = "thumb-edited"
        private const val MEMORY_SHARE = 8
        private const val MAX_MEMORY = 96 * 1024 * 1024

        private fun memoryBytes(): Int =
            (Runtime.getRuntime().maxMemory() / MEMORY_SHARE).coerceAtMost(MAX_MEMORY.toLong()).toInt()
    }
}
