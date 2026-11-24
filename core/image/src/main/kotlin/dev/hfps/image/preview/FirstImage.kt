package dev.hfps.image.preview

import android.graphics.Bitmap
import dev.hfps.image.ImageInput
import dev.hfps.image.decode.BitmapDecoding
import dev.hfps.image.source.readRange
import java.io.IOException

/** Where the first image came from. */
enum class FirstImageSource {
    /** The JPEG embedded in the RAW (1616x1080 on a ZV-E10: a few hundred KB, read in two small reads). */
    EMBEDDED_PREVIEW,

    /** The camera JPEG/HEIF that was shot with the RAW (same stem, [dev.hfps.image.format.ShotPairing]). */
    PAIRED_RENDERING,
}

/** An upright, display-ready (sRGB) first image and what it cost. */
data class FirstImage(val bitmap: Bitmap, val source: FirstImageSource, val readBytes: Long, val millis: Long)

/**
 * The instant first image of a shot while the real decode runs: the embedded RAW preview when it is large enough for
 * the target, else the paired camera JPEG, else the largest preview available. It is a display image (the camera's
 * own look), not pipeline data.
 */
object FirstImageLoader {
    /** Minimum share of the target long edge an embedded preview must cover to be used without the paired rendering. */
    private const val PREVIEW_COVERAGE = 0.75

    @Suppress("ComplexCondition")
    fun load(raw: ImageInput?, rendered: ImageInput?, targetLongEdge: Int): FirstImage {
        val started = System.nanoTime()
        val inspection = raw?.inspection
        val preview = inspection?.largestPreview
        val previewLong = preview?.let { maxOf(it.width, it.height) } ?: 0
        val previewCovers = rendered == null || previewLong >= targetLongEdge * PREVIEW_COVERAGE
        val data: ByteArray
        val orientation: Int
        val source: FirstImageSource
        if (raw != null && inspection != null && preview != null && previewCovers) {
            val chosen = inspection.previewFor(targetLongEdge) ?: preview
            data = raw.withSource { it.readRange(chosen.offset, chosen.length.toInt()) }
            orientation = inspection.metadata.orientation
            source = FirstImageSource.EMBEDDED_PREVIEW
        } else if (rendered != null) {
            data = rendered.withSource { it.readRange(0, it.size.toInt()) }
            orientation = rendered.inspection.metadata.orientation
            source = FirstImageSource.PAIRED_RENDERING
        } else {
            throw IOException("No embedded preview and no paired rendering")
        }
        val decoded = BitmapDecoding.decode(data, targetLongEdge, orientation)
        return FirstImage(decoded.bitmap, source, data.size.toLong(), (System.nanoTime() - started) / NANOS_PER_MILLI)
    }

    /**
     * [load] reading one file at a time: [openRaw]'s input is opened, its embedded preview used when it covers the
     * target (or when there is no [openRendered]) and closed **before** [openRendered] is opened, so a caller whose
     * opens are throttled (Reveal's `FileOpens` slots) never holds one slot while waiting for a second (four such
     * callers used to wait for each other forever). Same choice of image as [load].
     */
    fun loadEach(openRaw: (() -> ImageInput)?, openRendered: (() -> ImageInput)?, targetLongEdge: Int): FirstImage {
        if (openRaw != null) {
            val embedded = openRaw().use { raw ->
                val preview = raw.inspection.largestPreview
                val previewLong = preview?.let { maxOf(it.width, it.height) } ?: 0
                val covers = preview != null && previewLong >= targetLongEdge * PREVIEW_COVERAGE
                if (openRendered == null || covers) load(raw, null, targetLongEdge) else null
            }
            if (embedded != null) return embedded
        }
        openRendered ?: throw IOException("No embedded preview and no paired rendering")
        return openRendered().use { rendered -> load(null, rendered, targetLongEdge) }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
