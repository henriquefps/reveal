package dev.hfps.image

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ImageInspector
import dev.hfps.image.format.ImageType
import dev.hfps.image.format.Inspection
import dev.hfps.image.source.BlockCachedSource
import dev.hfps.image.source.DescriptorSource
import dev.hfps.image.source.readRange
import java.io.File
import java.io.FileNotFoundException

/**
 * One image file opened through a file descriptor (a Storage Access Framework document, a USB drive file, a local
 * file). The descriptor is read in place with positional reads: the original is never copied. The caller owns the
 * [descriptor] and closes it (or closes this object, which closes it).
 */
class ImageInput(val name: String, val descriptor: ParcelFileDescriptor) : AutoCloseable {

    /** Type from the extension and the first bytes (magic wins on disagreement). */
    val type: ImageType by lazy {
        withSource { ImageFormats.classify(name, it.readRange(0, HEAD_BYTES)) }
    }

    /** Header metadata and embedded previews (a couple of reads of the first ~128 KiB), memoized. */
    val inspection: Inspection by lazy { withSource { ImageInspector.inspect(BlockCachedSource(it), name) } }

    /** Runs [block] on a source over a duplicate of the descriptor (closed afterwards; this input stays open). */
    fun <T> withSource(block: (DescriptorSource) -> T): T = DescriptorSource(descriptor.dup()).use(block)

    override fun close() = descriptor.close()

    companion object {
        private const val HEAD_BYTES = 64

        fun fromFile(file: File): ImageInput =
            ImageInput(file.name, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

        fun fromUri(resolver: ContentResolver, uri: Uri, name: String): ImageInput {
            val descriptor =
                resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("No descriptor for $uri")
            return ImageInput(name, descriptor)
        }
    }
}
