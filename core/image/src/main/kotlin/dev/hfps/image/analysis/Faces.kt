package dev.hfps.image.analysis

import android.graphics.Bitmap

/**
 * Finds faces and eye states in an upright analysis image. Null = no detector (faces unknown).
 * Hoard's ML Kit implementation is not ported (no ML Kit dependency in `:core:image`); `:core:ml` provides one.
 */
fun interface Faces {
    fun detect(bitmap: Bitmap): List<DetectedFace>?
}
