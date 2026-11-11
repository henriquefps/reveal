package dev.hfps.reveal.feature.export.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import dev.hfps.reveal.pipeline.gl.TileSink
import java.nio.ByteBuffer

/**
 * Collects the tiled render's 8-bit bands into one [bitmap] (native memory) in the output colour [space], without any
 * colour conversion: each band becomes a small bitmap of the same space, drawn 1:1 with `SRC` (opaque pixels, integer
 * offset, no filtering: an exact copy, checked by the export round-trip test).
 */
class BitmapSink(width: Int, height: Int, space: ColorSpace) : TileSink {
    val bitmap: Bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888, false, space)
    private val canvas = Canvas(bitmap)
    private val paint = Paint().apply {
        isFilterBitmap = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val space = space
    private val bands = HashMap<Long, Bitmap>()

    override fun accept(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer) {
        val key = (w.toLong() shl Int.SIZE_BITS) or h.toLong()
        val band = bands.getOrPut(key) { createBitmap(w, h, Bitmap.Config.ARGB_8888, false, space) }
        rgba.position(0)
        band.copyPixelsFromBuffer(rgba)
        canvas.drawBitmap(band, x.toFloat(), y.toFloat(), paint)
    }

    /** Frees the band bitmaps (the output [bitmap] stays). */
    fun releaseBands() {
        bands.values.forEach { it.recycle() }
        bands.clear()
    }
}
