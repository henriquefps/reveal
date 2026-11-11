package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.proxy.LinearResample
import dev.hfps.image.standard.Linearize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class LinearImageTest {
    private fun image(width: Int, height: Int, value: (x: Int, y: Int, c: Int) -> Int): LinearImage {
        val data = ShortArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                for (c in 0..2) {
                    data[(y * width + x) * 3 + c] =
                        value(x, y, c).toShort()
                }
            }
        }
        return LinearImage(width, height, data)
    }

    @Test
    fun `size is validated and samples are unsigned`() {
        assertThrows(IllegalArgumentException::class.java) { LinearImage(2, 2, ShortArray(11)) }
        assertThrows(IllegalArgumentException::class.java) { LinearImage(0, 2, ShortArray(0)) }
        val i = image(2, 1) { x, _, c -> if (x == 1) 65535 - c else c }
        assertThat(i.sample(1, 0, 0)).isEqualTo(65535)
        assertThat(i.sample(1, 0, 2)).isEqualTo(65533)
        assertThat(i.sample(0, 0, 2)).isEqualTo(2)
    }

    @Test
    fun `downscale by two averages 2x2 blocks`() {
        val i = image(4, 2) { x, y, _ -> if (y == 0) (if (x < 2) 100 else 1000) else (if (x < 2) 300 else 3000) }
        val half = LinearResample.downscale(i, 2, 1)
        assertThat(half.sample(0, 0, 1)).isEqualTo(200)
        assertThat(half.sample(1, 0, 2)).isEqualTo(2000)
    }

    @Test
    fun `downscale keeps a constant image constant and preserves the mean at fractional ratios`() {
        val flat = image(7, 5) { _, _, _ -> 12345 }
        val small = LinearResample.downscale(flat, 3, 2)
        for (y in 0 until 2) for (x in 0 until 3) for (c in 0..2) assertThat(small.sample(x, y, c)).isEqualTo(12345)
        // 3 -> 2 columns: weights 1, 0.5 | 0.5, 1 over values 0, 30, 60.
        val ramp = image(3, 1) { x, _, _ -> x * 30 }
        val two = LinearResample.downscale(ramp, 2, 1)
        assertThat(two.sample(0, 0, 0)).isEqualTo(10) // (0 * 1 + 30 * 0.5) / 1.5
        assertThat(two.sample(1, 0, 0)).isEqualTo(50) // (30 * 0.5 + 60 * 1) / 1.5
    }

    @Test
    fun `downscale averages in linear light and never upsamples`() {
        val i = image(2, 1) { x, _, _ -> if (x == 0) 0 else 65535 }
        assertThat(LinearResample.downscale(i, 1, 1).sample(0, 0, 0)).isEqualTo(32768)
        assertThrows(IllegalArgumentException::class.java) { LinearResample.downscale(i, 4, 1) }
        assertThat(LinearResample.downscale(i, 2, 1)).isSameInstanceAs(i)
    }

    @Test
    fun `fit long edge`() {
        val i = image(6, 4) { _, _, _ -> 7 }
        val fitted = LinearResample.fitLongEdge(i, 3)
        assertThat(fitted.width to fitted.height).isEqualTo(3 to 2)
        assertThat(LinearResample.fitLongEdge(i, 100)).isSameInstanceAs(i)
    }

    @Test
    fun `native image copies and refuses use after close`() {
        val buffer = ByteBuffer.allocateDirect(2 * 1 * 3 * 2).order(ByteOrder.nativeOrder())
        for (v in intArrayOf(1, 2, 3, 65535, 0, 7)) buffer.putShort(v.toShort())
        var released = 0
        val native = NativeLinearImage(2, 1, buffer) { released++ }
        val copy = native.toLinearImage()
        assertThat(copy.sample(1, 0, 0)).isEqualTo(65535)
        assertThat(LinearResample.downscale(native, 1, 1).sample(0, 0, 0)).isEqualTo(32768)
        native.close()
        native.close()
        assertThat(released).isEqualTo(1)
        assertThrows(IllegalStateException::class.java) { native.buffer }
        assertThrows(IllegalArgumentException::class.java) { NativeLinearImage(3, 1, ByteBuffer.allocateDirect(4)) {} }
    }

    @Test
    fun `linearisation table and padded rows`() {
        val lut = Linearize.lut { it }
        assertThat(lut[0].toInt() and 0xFFFF).isEqualTo(0)
        assertThat(lut[255].toInt() and 0xFFFF).isEqualTo(65535)
        assertThat(lut[128].toInt() and 0xFFFF).isEqualTo(32896)
        // 2x2 pixels, rows padded to 12 bytes; alpha ignored.
        val rgba = ByteArray(24)
        rgba[0] = 255.toByte()
        rgba[4 + 1] = 255.toByte()
        rgba[12 + 2] = 255.toByte()
        rgba[12 + 3] = 7
        rgba[16] = 255.toByte()
        rgba[16 + 1] = 255.toByte()
        rgba[16 + 2] = 255.toByte()
        val out = Linearize.rgbaToLinear(rgba, 2, 2, 12, lut)
        assertThat(out.sample(0, 0, 0)).isEqualTo(65535)
        assertThat(out.sample(1, 0, 1)).isEqualTo(65535)
        assertThat(out.sample(0, 1, 2)).isEqualTo(65535)
        assertThat(out.sample(1, 1, 0)).isEqualTo(65535)
        assertThat(out.sample(0, 0, 1)).isEqualTo(0)
    }
}
