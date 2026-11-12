@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml

/**
 * An image as the mask models see it: [width] x [height] interleaved RGB, sRGB-encoded, in 0..1 (the display image
 * of the photo; docs/adr/0021 "Model input" says which one). Pure data so pre- and post-processing are JVM-tested.
 */
class MlImage(val width: Int, val height: Int, val rgb: FloatArray) {
    init {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(rgb.size == width * height * 3) { "rgb must have width*height*3 values" }
    }

    companion object {
        /** From packed ARGB pixels (an Android `Bitmap.getPixels` array). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): MlImage {
            require(argb.size == width * height) { "argb must have width*height values" }
            val rgb = FloatArray(width * height * 3)
            for (i in argb.indices) {
                val p = argb[i]
                rgb[i * 3] = ((p shr 16) and 0xff) / 255f
                rgb[i * 3 + 1] = ((p shr 8) and 0xff) / 255f
                rgb[i * 3 + 2] = (p and 0xff) / 255f
            }
            return MlImage(width, height, rgb)
        }
    }
}
