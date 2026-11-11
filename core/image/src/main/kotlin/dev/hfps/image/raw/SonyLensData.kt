package dev.hfps.image.raw

import android.os.ParcelFileDescriptor
import dev.hfps.image.color.SonyLensCorrection

/** Reads Sony lens-correction parameters (placeholder: filled in with the SR2 parser). */
internal object SonyLensData {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun read(descriptor: ParcelFileDescriptor): SonyLensCorrection? = null
}
