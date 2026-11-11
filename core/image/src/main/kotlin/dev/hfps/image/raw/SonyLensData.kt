package dev.hfps.image.raw

import android.os.ParcelFileDescriptor
import dev.hfps.image.color.SonyLensCorrection
import dev.hfps.image.format.SonyLensCorrectionReader
import dev.hfps.image.source.BlockCachedSource
import dev.hfps.image.source.DescriptorSource

/** Reads Sony lens-correction parameters through a duplicate of the descriptor (a few small reads). */
internal object SonyLensData {
    fun read(descriptor: ParcelFileDescriptor): SonyLensCorrection? =
        DescriptorSource(descriptor.dup()).use { SonyLensCorrectionReader.read(BlockCachedSource(it)) }
}
