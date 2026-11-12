package dev.hfps.reveal.data.thumb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.hfps.image.ImageInput
import dev.hfps.image.decode.BitmapDecoding
import dev.hfps.image.decode.IntRect
import dev.hfps.image.decode.Orientation
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ImageKind
import dev.hfps.image.preview.FirstImageLoader
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.isAccessError
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A display image for the viewer and the upright pixel size of the photo it shows (for 100 % zoom). */
class DisplayImage(val bitmap: Bitmap, val fullWidth: Int, val fullHeight: Int, val developed: Boolean)

/**
 * Full-resolution regions of a photo's camera rendering (JPEG/HEIF/PNG/WebP) for 100 % zoom: `BitmapRegionDecoder` on
 * the document's descriptor, in upright coordinates (EXIF orientation applied). Close it when the page goes away.
 */
class RegionSource internal constructor(
    private val input: ImageInput,
    private val decoder: BitmapRegionDecoder,
    private val orientation: Int,
) : Closeable {
    private val storedWidth = decoder.width
    private val storedHeight = decoder.height

    val width: Int get() = Orientation.displayedSize(storedWidth, storedHeight, orientation).first
    val height: Int get() = Orientation.displayedSize(storedWidth, storedHeight, orientation).second

    /** The upright region [rect] decoded at [sampleSize] (a power of two), upright. */
    @Synchronized
    fun decode(rect: IntRect, sampleSize: Int): Bitmap {
        val clipped = rect.intersect(width, height)
        if (clipped.isEmpty) throw IOException("Empty region $rect")
        val stored = Orientation.toStored(clipped, storedWidth, storedHeight, orientation)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        val tile = decoder.decodeRegion(Rect(stored.left, stored.top, stored.right, stored.bottom), options)
            ?: throw IOException("Region decode failed")
        return BitmapDecoding.orient(tile, orientation)
    }

    @Synchronized
    override fun close() {
        decoder.recycle()
        input.close()
    }
}

/**
 * Images for the full-screen viewer (ADR 0006, "Viewer"). Fast path: the RAW's embedded preview or the camera JPEG
 * at screen size ([FirstImageLoader]). An edited photo shows its edit (a develop render). Zooming in uses the
 * camera rendering's full-resolution regions ([region]); a RAW without one gets a larger develop render
 * ([developed]: a half-size RAW decode, about 3000 px for a 24 MP camera).
 */
class PhotoImages(
    private val db: RevealDatabase,
    private val trees: DocumentTreeFactory,
    private val edits: EditRepository,
    private val edited: EditedThumbnails?,
    /** A hand-off Reveal can no longer read shows its local preview (ADR 0011 "Unreadable hand-offs"). */
    private val previews: LocalPreviews? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val photos = db.photoDao()

    private class Opened(val tree: DocumentTree, val raw: PhotoFileEntity?, val rendered: PhotoFileEntity?)

    private suspend fun open(photoId: Long): Opened? {
        val source = photos.get(photoId)?.let { db.sourceDao().get(it.sourceId) }
            ?.takeIf { it.state == SourceState.AVAILABLE } ?: return null
        val files = photos.files(photoId)
        return Opened(
            trees.open(source),
            files.firstOrNull { it.role == FileRole.RAW },
            files.firstOrNull { it.role == FileRole.RENDERED },
        )
    }

    /** The screen-size image of [photoId] (long edge about [longEdge]); null when its folder is not available. */
    suspend fun display(photoId: Long, longEdge: Int): DisplayImage? = withContext(Dispatchers.IO) {
        val photo = photos.get(photoId) ?: return@withContext null
        val opened = open(photoId) ?: return@withContext null
        val (fullWidth, fullHeight) = fullSize(photo.width, photo.height, photo.orientation, opened)
        if (photo.hasEdit) {
            developed(photoId, longEdge)?.let {
                return@withContext DisplayImage(it, fullWidth, fullHeight, developed = true)
            }
        }
        try {
            val raw = opened.raw?.let { opened.tree.openInput(it.documentId, it.name) }
            val rendered = try {
                opened.rendered?.let { opened.tree.openInput(it.documentId, it.name) }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                raw?.close()
                throw e
            }
            try {
                val first = FirstImageLoader.load(raw, rendered, longEdge)
                DisplayImage(
                    first.bitmap,
                    fullWidth.takeIf { it > 0 } ?: first.bitmap.width,
                    fullHeight.takeIf { it > 0 } ?: first.bitmap.height,
                    false,
                )
            } finally {
                raw?.close()
                rendered?.close()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (!isAccessError(e)) throw e
            localDisplay(opened, longEdge, fullWidth, fullHeight) ?: throw e
        }
    }

    /** A develop render of the version's recipe (default when unedited) at [longEdge]; null without a GPU path. */
    suspend fun developed(photoId: Long, longEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        val renderer = edited ?: return@withContext null
        val opened = open(photoId) ?: return@withContext null
        val main = opened.raw ?: opened.rendered ?: return@withContext null
        val version = photos.firstVersion(photoId) ?: return@withContext null
        val recipe = edits.recipe(version)
        runCatching {
            opened.tree.openInput(main.documentId, main.name).use { renderer.render(it, recipe, longEdge) }
        }.recoverCatching { e ->
            if (!isAccessError(e)) throw e
            // A hand-off whose grant ended: its local smart preview, developed the same way.
            photos.setUnreadable(main.documentId, clock())
            val file = previews?.smart(main.fingerprint) ?: throw e
            val decoded = file.inputStream().use(SmartPreviewCodec::decode)
            renderer.renderPreview(decoded.input, maxOf(decoded.fullWidth, decoded.fullHeight), recipe, longEdge)
                ?: throw e
        }.getOrNull()
    }

    /** The local display image of an unreadable hand-off (ADR 0011), or null without one. */
    @Suppress("ReturnCount")
    private suspend fun localDisplay(opened: Opened, longEdge: Int, fullWidth: Int, fullHeight: Int): DisplayImage? {
        val main = opened.raw ?: opened.rendered ?: return null
        photos.setUnreadable(main.documentId, clock())
        val local = previews ?: return null
        val bytes = local.display(main.fingerprint)?.readBytes()
            ?: local.smart(main.fingerprint)?.inputStream()?.use(SmartPreviewCodec::readJpeg)
            ?: return null
        if (bytes.isEmpty()) return null
        val bitmap = BitmapDecoding.decode(bytes, longEdge, 1).bitmap
        return DisplayImage(
            bitmap,
            fullWidth.takeIf { it > 0 } ?: bitmap.width,
            fullHeight.takeIf { it > 0 } ?: bitmap.height,
            false,
        )
    }

    /** Full-resolution regions of the camera rendering, or null (RAW only, unreadable, unsupported). */
    suspend fun region(photoId: Long): RegionSource? = withContext(Dispatchers.IO) {
        val opened = open(photoId) ?: return@withContext null
        val file = opened.rendered ?: return@withContext null
        runCatching { openRegion(opened.tree.openInput(file.documentId, file.name)) }.getOrNull()
    }

    private fun openRegion(input: ImageInput): RegionSource? {
        val kind = ImageFormats.classify(input.name).kind
        if (kind == ImageKind.RAW || kind == ImageKind.OTHER) {
            input.close()
            return null
        }
        val decoder = try {
            newDecoder(input.descriptor)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            input.close()
            throw e
        }
        val orientation = runCatching { input.inspection.metadata.orientation }.getOrDefault(1)
        return RegionSource(input, decoder, orientation)
    }

    private fun newDecoder(descriptor: ParcelFileDescriptor): BitmapRegionDecoder = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.S
    ) {
        BitmapRegionDecoder.newInstance(descriptor)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
    }

    /** Upright full size: the camera rendering's own bounds when there is one, else the header's size. */
    private fun fullSize(width: Int?, height: Int?, orientation: Int, opened: Opened): Pair<Int, Int> {
        val bounds = opened.rendered?.let { rendered ->
            runCatching {
                opened.tree.openInput(rendered.documentId, rendered.name).use { input ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(input.descriptor.fileDescriptor, null, options)
                    val o = runCatching { input.inspection.metadata.orientation }.getOrDefault(1)
                    Orientation.displayedSize(options.outWidth, options.outHeight, o)
                }
            }.getOrNull()
        }?.takeIf { it.first > 0 }
        return bounds
            ?: if (width == null || height == null) 0 to 0 else Orientation.displayedSize(width, height, orientation)
    }
}
