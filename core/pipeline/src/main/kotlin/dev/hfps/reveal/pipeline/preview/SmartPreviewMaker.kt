package dev.hfps.reveal.pipeline.preview

import android.graphics.Bitmap
import dev.hfps.image.ImageInput
import dev.hfps.image.format.ImageKind
import dev.hfps.image.preview.FirstImageLoader
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.raw.RawDecoder
import dev.hfps.image.source.CancelToken
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.input.UnsupportedCameraException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** A smart preview written to disk: its file, content address and pixel size. */
data class MadePreview(val file: File, val sha256: String, val size: Long, val width: Int, val height: Int)

/**
 * Makes a photo's smart preview (ADR 0018 "Smart previews"): the editor's own decode (`ProxyBuilder` at
 * [SmartPreviewCodec.LONG_EDGE], mapped like `PhotoOpener`) and the display JPEG (the RAW's embedded preview or the
 * JPEG, [JPEG_EDGE] px), in the [SmartPreviewCodec] format. Blocking and memory hungry (one 2560 px float proxy):
 * one at a time, from a worker.
 */
class SmartPreviewMaker(private val dir: File) {
    /** Writes the preview of [input] (a RAW, or the rendering of a photo without one) into [dir]. */
    fun make(input: ImageInput, cancel: CancelToken = CancelToken.NONE): MadePreview {
        dir.mkdirs()
        val temp = File.createTempFile("preview", ".tmp", dir)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val size = java.security.DigestOutputStream(temp.outputStream().buffered(BUFFER), digest).use { out ->
                write(input, out, cancel)
            }
            val sha = digest.digest().toHex()
            val file = File(dir, "$sha.rvsp")
            if (!temp.renameTo(file)) throw IOException("could not store the preview")
            return MadePreview(file, sha, file.length(), size.first, size.second)
        } finally {
            temp.delete()
        }
    }

    /**
     * Adopts a smart preview made earlier (a hand-off's local one, ADR 0011): copied into [dir] under its content
     * address, as [make] leaves it.
     */
    fun adopt(preview: File): MadePreview {
        dir.mkdirs()
        val temp = File.createTempFile("preview", ".tmp", dir)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            preview.inputStream().use { input ->
                java.security.DigestOutputStream(temp.outputStream().buffered(BUFFER), digest).use { input.copyTo(it) }
            }
            val (width, height) = preview.inputStream().use(SmartPreviewCodec::size)
            val sha = digest.digest().toHex()
            val file = File(dir, "$sha.rvsp")
            if (!temp.renameTo(file)) throw IOException("could not store the preview")
            return MadePreview(file, sha, file.length(), width, height)
        } finally {
            temp.delete()
        }
    }

    companion object {
        /** Long edge of the display JPEG inside a preview (grid and viewer of a preview-only photo). */
        const val JPEG_EDGE = 1600
        private const val JPEG_QUALITY = 85
        private const val BUFFER = 256 * 1024
        private const val BYTE_MASK = 0xFF

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

        /**
         * Writes the smart preview of [input] to [out]; returns its pixel size. Throws [IOException] for what cannot
         * be developed (not an image, a RAW without a colour matrix or without LibRaw).
         */
        fun write(input: ImageInput, out: OutputStream, cancel: CancelToken = CancelToken.NONE): Pair<Int, Int> {
            val type = input.type
            if (type.kind == ImageKind.OTHER || (type.isRaw && !RawDecoder.isAvailable)) {
                throw IOException("not a developable image")
            }
            val jpeg = runCatching {
                if (type.isRaw) displayJpeg(input, null, JPEG_EDGE) else displayJpeg(null, input, JPEG_EDGE)
            }.getOrDefault(ByteArray(0))
            val orientation =
                if (type.isRaw) null else runCatching { input.inspection.metadata.orientation }.getOrNull()
            val proxy = ProxyBuilder.decodeProxy(input, SmartPreviewCodec.LONG_EDGE, cancel)
            val metadata = runCatching { input.inspection.metadata }.getOrNull()
            val mapped = try {
                PipelineInputMapper.map(proxy.image, proxy.info, orientation ?: proxy.info.orientation, metadata)
            } catch (e: UnsupportedCameraException) {
                // A camera without a colour matrix cannot be developed here either: a plain failure, not a bug.
                throw IOException(e.message, e)
            }
            val turned = mapped.width != proxy.image.width
            val stats = proxy.stats
            val fullWidth = if (turned) stats.fullHeight else stats.fullWidth
            val fullHeight = if (turned) stats.fullWidth else stats.fullHeight
            SmartPreviewCodec.encode(mapped, jpeg, fullWidth, fullHeight, out)
            return mapped.width to mapped.height
        }

        /**
         * The display JPEG of a shot (long edge [edge]): the RAW's embedded preview, else its rendering, upright
         * (`FirstImageLoader`), at quality [JPEG_QUALITY].
         */
        fun displayJpeg(raw: ImageInput?, rendered: ImageInput?, edge: Int): ByteArray {
            val first = FirstImageLoader.load(raw, rendered, edge)
            return ByteArrayOutputStream().use { out ->
                val bitmap = first.bitmap
                val long = maxOf(bitmap.width, bitmap.height)
                val scaled = if (long > edge) {
                    val scale = edge.toFloat() / long
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    bitmap
                }
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }
}
