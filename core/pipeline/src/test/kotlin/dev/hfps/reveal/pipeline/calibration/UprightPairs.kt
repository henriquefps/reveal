@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.File

/**
 * Upright copies for the calibrations that need them (faces are only found upright): the camera stores portrait
 * shots landscape with an EXIF orientation, and the RAW dumps are written unrotated (`user_flip = 0`), so both the
 * RAW input and the camera JPEG are turned by the JPEG's orientation tag.
 */
object UprightPairs {
    /** The EXIF orientation (1..8) of [jpg]; 1 when absent. */
    fun orientation(jpg: File): Int = DataInputStream(jpg.inputStream().buffered()).use { s ->
        if (s.readUnsignedShort() != 0xFFD8) return 1
        while (true) {
            val marker = s.readUnsignedShort()
            val length = s.readUnsignedShort() - 2
            if (marker == 0xFFDA || marker == 0xFFD9) return 1
            val data = ByteArray(length).also(s::readFully)
            if (marker == 0xFFE1 && String(data, 0, 4, Charsets.ISO_8859_1) == "Exif") return tiffOrientation(data, 6)
        }
        @Suppress("UNREACHABLE_CODE")
        1
    }

    private fun tiffOrientation(b: ByteArray, base: Int): Int {
        val little = b[base] == 'I'.code.toByte()
        fun u16(p: Int) = if (little) {
            (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
        } else {
            ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
        }
        fun u32(p: Int) = if (little) u16(p) or (u16(p + 2) shl 16) else (u16(p) shl 16) or u16(p + 2)
        val ifd = base + u32(base + 4)
        val count = u16(ifd)
        for (e in 0 until count) {
            val p = ifd + 2 + e * 12
            if (u16(p) == 0x0112) return u16(p + 8).coerceIn(1, 8)
        }
        return 1
    }

    /** Quarter turns clockwise that make an image with EXIF [orientation] upright (mirrored ones: none). */
    fun turns(orientation: Int): Int = when (orientation) {
        6 -> 1
        3 -> 2
        8 -> 3
        else -> 0
    }

    /** [input] turned [turns] quarter turns clockwise. */
    fun rotate(input: PipelineInput, turns: Int): PipelineInput {
        val t = ((turns % 4) + 4) % 4
        if (t == 0) return input
        val w = input.width
        val h = input.height
        val ow = if (t == 2) w else h
        val oh = if (t == 2) h else w
        val out = FloatArray(input.linearRgb.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (nx, ny) = when (t) {
                    1 -> (h - 1 - y) to x
                    2 -> (w - 1 - x) to (h - 1 - y)
                    else -> y to (w - 1 - x)
                }
                System.arraycopy(input.linearRgb, (y * w + x) * 3, out, (ny * ow + nx) * 3, 3)
            }
        }
        return PipelineInput(
            input.kind,
            ow,
            oh,
            out,
            input.cameraToXyz,
            input.asShotMultipliers,
            input.saturation,
            input.noise,
            input.lens,
        )
    }

    /** [image] turned [turns] quarter turns clockwise. */
    fun rotate(image: BufferedImage, turns: Int): BufferedImage {
        val t = ((turns % 4) + 4) % 4
        if (t == 0) return image
        val w = image.width
        val h = image.height
        val out = if (t ==
            2
        ) {
            BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        } else {
            BufferedImage(h, w, BufferedImage.TYPE_INT_RGB)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = image.getRGB(x, y)
                when (t) {
                    1 -> out.setRGB(h - 1 - y, x, p)
                    2 -> out.setRGB(w - 1 - x, h - 1 - y, p)
                    else -> out.setRGB(y, w - 1 - x, p)
                }
            }
        }
        return out
    }
}
