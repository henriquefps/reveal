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
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.data.mask.MaskInputs
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.CacheArea
import dev.hfps.reveal.data.storage.CacheItem
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.FileOpens
import dev.hfps.reveal.data.storage.StorageKind
import dev.hfps.reveal.data.storage.isAccessError
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.pipeline.PIPELINE_VERSION
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.preset.ApplyContext
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** The cap of this cache on its own; the app passes Settings' maximum cache size ([CacheBudget]). */
    private val maxBytes: () -> Long = { DEFAULT_MAX_BYTES },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Hand-offs Reveal can no longer read show their local preview (ADR 0011 "Unreadable hand-offs"). */
    private val previews: LocalPreviews? = null,
    /** Reads the server's copy of an unreadable hand-off (ADR 0011 "Fallbacks"); null without sync (tests). */
    private val resolver: ContentResolver? = null,
    private val serverAuthority: String? = null,
    /** After a trim: the app's [CacheBudget] trims all caches together. */
    private val onTrimmed: () -> Unit = {},
    /** The AI masks of edited thumbnails (docs/adr/0022 "M4/F"); null = AI mask parts are left out, as before. */
    private val aiMasks: AiMasks? = null,
    /**
     * The server's grid thumbnails (ADR 0018 "W3/C"): Server photos and hand-offs whose file the server holds show
     * them, never a read of the file through a content provider; null without sync (tests).
     */
    private val serverThumbs: (() -> ServerThumbs?)? = null,
) : CacheArea {
    private val fallbacks = HandoffFallbacks(db, previews, serverAuthority)
    private val cache = db.cacheDao()
    private val photos = db.photoDao()

    /** A cancelled cell's wait for a FileOpens slot ends with it (ADR 0018 "W3/C"). */
    private val io = Dispatchers.IO.limitedParallelism(PARALLELISM) + FileOpens.waits("thumbnails")

    /**
     * One smart preview decoded at a time (a full-size preview's planes are tens of MB): the home lists unreadable
     * hand-offs among thousands of photos (FIX/L), and a fling must not decode three at once into the heap.
     */
    private val smartDecode = Mutex()
    private val memory = object : LruCache<String, Bitmap>(memoryBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val touched = ConcurrentHashMap.newKeySet<String>()
    private val puts = AtomicInteger()

    /** The thumbnail if it is in memory already (lets a recycled grid cell draw in the same frame). */
    fun inMemory(photoId: Long, editTime: Long?, edge: Int = EDGE): Bitmap? =
        memory.get(memoryKey(photoId, editTime, sampleFor(edge)))

    /**
     * The thumbnail of [photoId]; [editTime] is the time its recipe last changed (null = not edited), so a new edit
     * gets a new thumbnail. Null when nothing can be shown (unplugged and not cached, unreadable file).
     */
    suspend fun thumbnail(photoId: Long, versionId: Long, editTime: Long?, edge: Int = EDGE): Bitmap? {
        val sample = sampleFor(edge)
        val memoryKey = memoryKey(photoId, editTime, sample)
        memory.get(memoryKey)?.let { return it }
        return withContext(io) {
            memory.get(memoryKey)
                ?: memory.get(memoryKey(photoId, editTime))?.takeIf { sample > 1 }?.let { shrink(it, sample) }
                    ?.also { memory.put(memoryKey, it) }
                ?: load(photoId, versionId, editTime, sample)?.also { memory.put(memoryKey, it) }
        }
    }

    /**
     * The power-of-two reduction of the [EDGE] px thumbnail whose long edge still covers [edge] px (W2/P): the
     * tablet's 13-column grid draws cells of about 215 px, so it decodes the cached JPEG at 1/2 (a quarter of the
     * pixels to decode, upload, keep in memory and sample on every frame); the phone's 3 columns keep [EDGE].
     */
    private fun sampleFor(edge: Int): Int {
        var sample = 1
        while (sample < MAX_SAMPLE && EDGE / (sample * 2) >= edge) sample *= 2
        return sample
    }

    /** [bitmap] reduced by [sample] (filtered; a fresh thumbnail that is also saved full size). */
    private fun shrink(bitmap: Bitmap, sample: Int): Bitmap = if (sample == 1) {
        bitmap
    } else {
        bitmap.scale((bitmap.width / sample).coerceAtLeast(1), (bitmap.height / sample).coerceAtLeast(1))
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
        /**
         * The file the server's thumbnail is asked for (ADR 0018 "W3/C"): a Server photo's or a hand-off's file the
         * server holds (its RAW first), null for folder photos (their file is on this device) and files it lacks.
         */
        val serverPrint: String? = null,
    ) {
        /** [error] says this device's file cannot be read (access refused or gone); never for a Server photo. */
        fun refused(error: Throwable): Boolean = !server && isAccessError(error)
    }

    private suspend fun target(photoId: Long): Target? {
        val photo = photos.get(photoId)
        val kind = photo?.let { db.sourceDao().get(it.sourceId) }?.kind
        val server = kind == SourceKind.SERVER
        // A Server photo's thumbnail comes from a file the server holds (FIX/V).
        val files = photos.files(photoId).let { all ->
            if (server) ServerTree.reachable(all, ServerTree::servable) else all
        }
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
            serverPrint = if (kind == SourceKind.FOLDER) {
                null
            } else {
                files.filter { it.onServer && it.fingerprint != null }
                    .sortedBy { if (it.role == FileRole.RAW) 0 else 1 }.firstOrNull()?.fingerprint
            },
        )
    }

    private suspend fun load(photoId: Long, versionId: Long, editTime: Long?, sample: Int = 1): Bitmap? {
        val target = target(photoId) ?: return null
        return editTime?.let { loadEdited(target, versionId, it, sample) } ?: loadPreview(target, sample)
    }

    private suspend fun loadEdited(target: Target, versionId: Long, editTime: Long, sample: Int = 1): Bitmap? {
        if (edited == null) return null
        // An edit with AI masks or Remove spots has two thumbnails: the complete one (its masks and fills ready; its
        // own key: it is made once, never rendered twice) and, until then, this quick one ([withAiMasks] replaces it).
        val withMasks = if (needsUpgrade(versionId, editTime)) {
            fromDisk(editedKey(target, versionId, editTime, ai = true), sample)
        } else {
            null
        }
        val key = editedKey(target, versionId, editTime, ai = false)
        return withMasks ?: fromDisk(key, sample)
            ?: serverEdit(target, versionId)?.also {
                save(key, target.photoId, KIND_EDITED, it)
            }?.let { shrink(it, sample) }
            ?: renderEdited(target, versionId, editTime)?.also { save(key, target.photoId, KIND_EDITED, it) }
                ?.let { shrink(it, sample) }
    }

    /**
     * The server's preview of an edit another device made (Hoard ADR 0020) of a photo whose file the server holds
     * (ADR 0018 "W3/C"): the grid shows it rather than downloading the original to render it here. Null for edits of
     * this device (their render is newer than what the server has) or when the server has none.
     */
    @Suppress("ReturnCount")
    private suspend fun serverEdit(target: Target, versionId: Long): Bitmap? {
        val print = target.serverPrint ?: return null
        val version = db.versionDao().version(versionId) ?: return null
        val device = version.editDevice
        if (device == null || device == EditRepository.THIS_DEVICE) return null
        val thumbs = serverThumbs?.invoke() ?: return null
        return try {
            thumbs.editedThumbnail(print, version.uuid)?.let { scaleTo(it, EDGE) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.i(TAG, "No server edit for version $versionId: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun editedKey(target: Target, versionId: Long, editTime: Long, ai: Boolean) = CacheKeys.key(
        target.identity,
        Variant.THUMB,
        "edit:$versionId:$editTime:$PIPELINE_VERSION:$EDGE:$EDITED_VERSION" + if (ai) ":ai" else "",
    )

    /**
     * True when [versionId]'s quick thumbnail may lack something the complete one has: AI mask parts, or Remove fills
     * (docs/adr/0023 "FIX/H": made in the background, never while the grid waits).
     */
    private suspend fun needsUpgrade(versionId: Long, editTime: Long): Boolean =
        (aiMasks != null && aiComponents(versionId, editTime).isNotEmpty()) || removes(versionId, editTime)

    /** Whether [versionId]'s recipe has a Remove spot (memoised like [aiComponents]). */
    private suspend fun removes(versionId: Long, editTime: Long): Boolean {
        val memo = "$versionId:$editTime"
        removeSpots[memo]?.let { return it }
        val any = edits.recipe(versionId).heals.any { it.renders && it.type == HealKind.REMOVE }
        removeSpots[memo] = any
        return any
    }

    private val removeSpots = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(AI_MEMO, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > AI_MEMO
        },
    )

    /** The AI components of [versionId]'s enabled masks. */
    private suspend fun aiComponents(versionId: Long, editTime: Long): List<MaskComponent.Ai> {
        val memo = "$versionId:$editTime"
        aiParts[memo]?.let { return it }
        val parts = edits.recipe(versionId).masks.filter { it.enabled }
            .flatMap { it.components.filterIsInstance<MaskComponent.Ai>() }
        aiParts[memo] = parts
        return parts
    }

    /** [aiComponents] per version and edit time: the grid asks once per cell. */
    private val aiParts = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<MaskComponent.Ai>>(AI_MEMO, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MaskComponent.Ai>>?) =
                size > AI_MEMO
        },
    )

    /** Memory keys whose bitmap already has its AI masks. */
    private val aiDone = ConcurrentHashMap.newKeySet<String>()

    /** Thumbnail keys whose AI masks this process could not make (a failed model): not tried again until restart. */
    private val aiFailed = ConcurrentHashMap.newKeySet<String>()

    /** One photo's masks at a time: each holds its model input (about 8 MB) while the service works. */
    private val aiLock = Mutex()

    /**
     * The edited thumbnail of [photoId] with its AI masks and Remove fills, after [thumbnail] showed it without them
     * (docs/adr/0022 "M4/F", docs/adr/0023 "FIX/H"): the masks come from the disk cache (what the editor, an export or
     * an earlier run made) or are computed here, one photo at a time, off the grid's thread; the fills from their store
     * (the editor's) or made at the thumbnail's size; the thumbnail is then rendered once, kept under its own key and
     * put where [inMemory] finds it. Suspends until then; null when the edit has neither, the source is not readable
     * or a mask could not be made (the quick thumbnail stays). Cancelling it (the cell left the screen) stops the
     * work; what is cached stays.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    suspend fun withAiMasks(photoId: Long, versionId: Long, editTime: Long?): Bitmap? {
        val renderer = edited
        if (renderer == null || editTime == null) return null
        val masks = aiMasks
        val memoryKey = memoryKey(photoId, editTime)
        if (memoryKey in aiDone && memory.get(memoryKey) != null) return null
        return withContext(io) {
            val target = target(photoId) ?: return@withContext null
            val components = if (masks != null) aiComponents(versionId, editTime) else emptyList()
            val fills = removes(versionId, editTime)
            if (components.isEmpty() && !fills) return@withContext null
            val key = editedKey(target, versionId, editTime, ai = true)
            fromDisk(key)?.let {
                memory.put(memoryKey, it)
                forgetReduced(photoId, editTime)
                aiDone += memoryKey
                return@withContext it
            }
            if (key in aiFailed) return@withContext null
            val tree = availableTree(target.sourceId) ?: return@withContext null
            aiLock.withLock {
                try {
                    val recipe = edits.recipe(versionId)
                    if (masks == null || components.isEmpty()) {
                        // Remove fills only: the render waits for them (the file is closed by then).
                        renderer.renderFile(open(tree, target), recipe, EDGE).also { bitmap ->
                            save(key, target.photoId, KIND_EDITED, bitmap)
                            memory.put(memoryKey, bitmap)
                            forgetReduced(photoId, editTime)
                            aiDone += memoryKey
                        }
                    } else {
                        renderWithAiMasks(tree, target, renderer, masks, components, recipe, key, editTime)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    Log.w(TAG, "AI masks of the thumbnail of version $versionId failed", e)
                    aiFailed += key
                    null
                }
            }
        }
    }

    /**
     * [versionId]'s edit of [photoId] rendered whole at [longEdge] (the long edge the develop works at; the output
     * is the geometry's: a crop is smaller): the same develop as the thumbnails and the viewer, with its AI masks
     * (from the masks' cache, else made here) and Remove fills. Not cached. Null when the photo cannot be read, there
     * is no GPU path, or a mask could not be made. For the server's edited previews (docs/adr/0027).
     */
    @Suppress("ReturnCount")
    suspend fun renderEdit(photoId: Long, versionId: Long, longEdge: Int): Bitmap? {
        val renderer = edited ?: return null
        return withContext(io) {
            val target = target(photoId) ?: return@withContext null
            val tree = availableTree(target.sourceId) ?: return@withContext null
            val recipe = edits.recipe(versionId)
            val components = recipe.masks.filter {
                it.enabled
            }.flatMap { it.components.filterIsInstance<MaskComponent.Ai>() }
            aiLock.withLock {
                val masks = aiMasks
                if (masks == null || components.isEmpty()) {
                    return@withLock renderer.renderFile(open(tree, target), recipe, longEdge)
                }
                val (decoded, inputs) = decodeWithMaskInputs(renderer, tree, target, recipe, longEdge)
                val (photoKey, small) = inputs ?: return@withLock null
                // The file is closed: the wait for the masks and the render hold no FileOpens slot.
                val session = masks.session(photoKey, small.width to small.height) { MaskInputs.render(small) }
                try {
                    if (!session.ready(components)) return@withLock null
                    renderer.render(decoded, recipe, longEdge, session, photoKey)
                } finally {
                    session.close()
                }
            }
        }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun renderWithAiMasks(
        tree: DocumentTree,
        target: Target,
        renderer: EditedThumbnails,
        masks: AiMasks,
        components: List<MaskComponent.Ai>,
        recipe: Recipe,
        key: String,
        editTime: Long,
    ): Bitmap? {
        val (decoded, inputs) = decodeWithMaskInputs(renderer, tree, target, recipe, EDGE)
        val (photoKey, small) = inputs ?: return null
        // The file is closed: the wait for the masks and the render hold no FileOpens slot.
        val session = masks.session(photoKey, small.width to small.height) { MaskInputs.render(small) }
        try {
            if (!session.ready(components)) {
                aiFailed += key
                return null
            }
            val bitmap = renderer.render(decoded, recipe, EDGE, session, photoKey)
            save(key, target.photoId, KIND_EDITED, bitmap)
            memory.put(memoryKey(target.photoId, editTime), bitmap)
            forgetReduced(target.photoId, editTime)
            aiDone += memoryKey(target.photoId, editTime)
            return bitmap
        } finally {
            session.close()
        }
    }

    /**
     * [target]'s file opened once for the develop decode and the AI masks' key and small input (closed before this
     * returns); the mask inputs are null when the file has no key or no small input.
     */
    private suspend fun decodeWithMaskInputs(
        renderer: EditedThumbnails,
        tree: DocumentTree,
        target: Target,
        recipe: Recipe,
        longEdge: Int,
    ): Pair<EditedThumbnails.Decoded, Pair<String, PipelineInput>?> {
        var inputs: Pair<String, PipelineInput>? = null
        val decoded = renderer.decode(open(tree, target), recipe, longEdge) { image ->
            val photoKey = MaskInputs.photoKey(image)
            val small = photoKey?.let { MaskInputs.smallInput(image, MaskInputs.THUMBNAIL_PROXY_EDGE) }
            if (photoKey != null && small != null) inputs = photoKey to small
        }
        return decoded to inputs
    }

    /** Opens [target]'s main file (in a FileOpens slot for another app's document; close it once read). */
    private fun open(tree: DocumentTree, target: Target): () -> ImageInput =
        { tree.openInput(target.main.documentId, target.main.name) }

    @Suppress("ReturnCount")
    private suspend fun loadPreview(target: Target, sample: Int = 1): Bitmap? {
        val key = CacheKeys.key(target.identity, Variant.THUMB, EDGE)
        fromDisk(key, sample)?.let { return it }
        when (val thumb = serverThumb(target)) {
            is ServerThumb.Image -> return scaleTo(thumb.bitmap, EDGE / sample)
            ServerThumb.Later -> return null
            ServerThumb.NotServed, null -> Unit
        }
        return decodePreview(target)?.also { save(key, target.photoId, KIND_PREVIEW, it) }?.let { shrink(it, sample) }
    }

    /** The server's thumbnail of [target]'s file (ADR 0018 "W3/C"), or null when it has none to offer. */
    @Suppress("ReturnCount")
    private suspend fun serverThumb(target: Target): ServerThumb? {
        val print = target.serverPrint ?: return null
        val thumbs = serverThumbs?.invoke() ?: return null
        return try {
            thumbs.thumbnail(print)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.i(TAG, "No server thumbnail for photo ${target.photoId}: ${e.javaClass.simpleName}")
            ServerThumb.Later
        }
    }

    /** [bitmap] scaled down so its long edge is at most [edge] (a library thumbnail is already small). */
    private fun scaleTo(bitmap: Bitmap, edge: Int): Bitmap {
        val long = maxOf(bitmap.width, bitmap.height)
        if (long <= edge) return bitmap
        val scale = edge.toFloat() / long
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
    }

    /** An available source's tree, or null (unplugged, access lost, removed). */
    private suspend fun availableTree(sourceId: Long): DocumentTree? =
        db.sourceDao().get(sourceId)?.takeIf { it.state == SourceState.AVAILABLE }?.let(trees::open)

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun decodePreview(target: Target): Bitmap? {
        val tree = availableTree(target.sourceId) ?: return null
        val main = target.main
        return try {
            // One file at a time: the main file is closed before its pair's JPEG is opened (never a FileOpens slot held
            // while waiting for another, ADR 0018 "W3/C").
            val rendered = target.rendered?.takeIf { it !== main }
            val image = tree.openInput(main.documentId, main.name).use { input ->
                embeddedPreview(input) ?: if (rendered == null) decodeStandard(input) else null
            } ?: tree.openInput(checkNotNull(rendered).documentId, rendered.name).use(::decodeStandard)
            if (main.unreadableAt != null) photos.setReadable(main.documentId)
            image
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (target.refused(e)) {
                // A hand-off whose grant ended (ADR 0011): remember it, and show its local preview.
                photos.setUnreadable(main.documentId, clock())
                localPreview(main)?.let { return it }
                (serverThumb(target) as? ServerThumb.Image)?.let { return scaleTo(it.bitmap, EDGE) }
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
            renderer.renderFile(open(tree, target), recipe, EDGE, waitForFills = false)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            if (target.refused(e)) {
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
            if (local != null || preview != null) {
                val rendered = smartDecode.withLock {
                    val decoded = when {
                        local != null -> local.inputStream().use(SmartPreviewCodec::decode)
                        else -> resolver?.openInputStream(checkNotNull(preview))?.use(SmartPreviewCodec::decode)
                    }
                    decoded?.let {
                        renderer.renderPreview(it.input, maxOf(it.fullWidth, it.fullHeight), recipe, EDGE)
                    }
                }
                if (rendered != null) return rendered
            }
            val resolver = resolver ?: return null
            val original = fallbacks.original(target.main) ?: return null
            renderer.renderFile({ ImageInput.fromUri(resolver, original, target.main.name) }, recipe, EDGE)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.i(TAG, "No fallback render of version $versionId: ${e.javaClass.simpleName}")
            null
        } catch (e: OutOfMemoryError) {
            // No thumbnail rather than a crash: the cell shows its placeholder, the viewer still opens the photo.
            Log.w(TAG, "No memory for the fallback render of version $versionId", e)
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

    /** A cached thumbnail, decoded at 1/[sample] (the JPEG decoder's own scaling: never the full size first). */
    private suspend fun fromDisk(key: String, sample: Int = 1): Bitmap? {
        val entry = cache.get(key) ?: return null
        val file = File(dir, entry.fileName)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path, options) else null
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
        val cap = maxBytes()
        while (total > cap) {
            val oldest = cache.oldest(TRIM_BATCH)
            if (oldest.isEmpty()) break
            oldest.forEach { File(dir, it.fileName).delete() }
            cache.delete(oldest.map { it.key })
            total -= oldest.sumOf { it.bytes }
        }
        val known = cache.fileNames().toHashSet()
        dir.listFiles()?.filter { it.isFile && it.name !in known }?.forEach { it.delete() }
        onTrimmed()
    }

    override val kind: StorageKind get() = StorageKind.THUMBNAILS

    override suspend fun items(): List<CacheItem> = withContext(io) {
        flushTouches()
        cache.all().map { CacheItem(it.key, it.bytes, it.lastAccess) }
    }

    override suspend fun evict(keys: Collection<String>): Unit = withContext(io) {
        keys.chunked(SQL_ARGS).forEach { chunk ->
            cache.entries(chunk).forEach { File(dir, it.fileName).delete() }
            cache.delete(chunk)
        }
        // The memory copies are keyed differently (photo and edit time); they go with their LRU.
    }

    /** Settings' "clear cache". */
    override suspend fun clear(): Unit = withContext(io) {
        memory.evictAll()
        cache.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun memoryKey(photoId: Long, editTime: Long?, sample: Int = 1) =
        "$photoId:${editTime ?: 0}" + if (sample > 1) "/$sample" else ""

    /** The reduced copies of a thumbnail that was just upgraded (its AI masks or fills arrived): made again from it. */
    private fun forgetReduced(photoId: Long, editTime: Long) {
        var sample = 2
        while (sample <= MAX_SAMPLE) {
            memory.remove(memoryKey(photoId, editTime, sample))
            sample *= 2
        }
    }

    companion object {
        private const val TAG = "RevealThumbs"
        private const val AI_MEMO = 4096

        /** Long edge of a thumbnail: a 3-column cell on a 1440 px wide phone is 480 px. */
        const val EDGE = 512
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024

        /** Long edge of the analysis input (the features need 576 px; lines are better with more). */
        const val ANALYSIS_EDGE = 1200
        private const val PARALLELISM = 3

        /** The smallest grid thumbnail is [EDGE] / 4 (128 px). */
        private const val MAX_SAMPLE = 4
        private const val JPEG_QUALITY = 85
        private const val TRIM_EVERY = 50
        private const val TRIM_BATCH = 200
        private const val TOUCH_BATCH = 64
        private const val SQL_ARGS = 900
        private const val KIND_PREVIEW = "thumb"
        private const val KIND_EDITED = "thumb-edited"

        /**
         * Bumped when edited thumbnails change without the pipeline (2: the geometry's output size and healing,
         * docs/adr/0023 "FIX/H"), so the ones cached before are made again.
         */
        private const val EDITED_VERSION = 2
        private const val MEMORY_SHARE = 8
        private const val MAX_MEMORY = 96 * 1024 * 1024

        private fun memoryBytes(): Int =
            (Runtime.getRuntime().maxMemory() / MEMORY_SHARE).coerceAtMost(MAX_MEMORY.toLong()).toInt()
    }
}
