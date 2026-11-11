package dev.hfps.reveal.pipeline.gl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistogramTest {
    private fun rgba(vararg pixels: Int): ByteArray {
        val out = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { i, p ->
            out[i * 4] = (p shr 16 and 0xFF).toByte()
            out[i * 4 + 1] = (p shr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (p and 0xFF).toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    @Test
    fun binsEachChannelAndRec709Luma() {
        val h = Histogram.fromRgba8(rgba(0xFF0000, 0x00FF00, 0x808080, 0x000000))
        assertThat(h.total).isEqualTo(4)
        assertThat(h.red[255]).isEqualTo(1)
        assertThat(h.red[0]).isEqualTo(2)
        assertThat(h.green[255]).isEqualTo(1)
        assertThat(h.blue[0]).isEqualTo(3)
        assertThat(h.luma[54]).isEqualTo(1) // 0.2126 * 255
        assertThat(h.luma[182]).isEqualTo(1) // 0.7152 * 255
        assertThat(h.luma[128]).isEqualTo(1)
        assertThat(h.luma[0]).isEqualTo(1)
        assertThat(h.red.sum()).isEqualTo(4)
        assertThat(h.luma.sum()).isEqualTo(4)
    }

    @Test
    fun clippedSharesFollowTheClippingWarnings() {
        val h = Histogram.fromRgba8(rgba(0xFF0000, 0x000000, 0x000000, 0x010101))
        assertThat(h.highlightClipped).isWithin(1e-6f).of(0.25f)
        assertThat(h.shadowClipped).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun peakIgnoresTheEndBins() {
        val h = Histogram.fromRgba8(rgba(0x000000, 0x000000, 0x000000, 0x404040, 0x404040))
        assertThat(h.peak).isEqualTo(2)
    }
}
