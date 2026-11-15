package dev.hfps.reveal.sync.thumbs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File

/** How [LibraryThumbs] re-encodes and decodes thumbnails (a pass-through in JVM tests). */
interface ThumbCodec {
    /** The server's preview [bytes] re-encoded with a long edge of at most [edge] px; null when not decodable. */
    fun shrink(bytes: ByteArray, edge: Int): ByteArray?

    fun decode(bytes: ByteArray): Bitmap?

    fun decode(file: File): Bitmap?
}

/** Android's codecs: the server's WebP decoded subsampled, scaled to the edge, written as lossy WebP. */
object AndroidThumbCodec : ThumbCodec {
    @Suppress("ReturnCount")
    override fun shrink(bytes: ByteArray, edge: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= edge) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
        val scale = edge.toFloat() / maxOf(decoded.width, decoded.height)
        val small = if (scale < 1f) {
            decoded.scale(
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
            )
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        return try {
            ByteArrayOutputStream().also { small.compress(webp, LibraryThumbs.QUALITY, it) }.toByteArray()
        } finally {
            small.recycle()
        }
    }

    override fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    override fun decode(file: File): Bitmap? = if (file.isFile) BitmapFactory.decodeFile(file.path) else null

    @Suppress("DEPRECATION")
    private val webp: Bitmap.CompressFormat
        get() = if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
}
