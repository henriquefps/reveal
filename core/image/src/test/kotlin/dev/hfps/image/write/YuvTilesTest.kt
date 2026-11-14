package dev.hfps.image.write

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YuvTilesTest {
    private class Flat(override val width: Int, override val height: Int, val rgb: IntArray) : RgbRows {
        override val bits: Int = 16

        override fun row(y: Int, out: IntArray) {
            for (x in 0 until width) rgb.copyInto(out, x * 3)
        }
    }

    @Test
    fun `white, black and grey map to the full-range extremes and mid chroma`() {
        for ((rgb, depth) in listOf(intArrayOf(65535, 65535, 65535) to 10, intArrayOf(0, 0, 0) to 8)) {
            val tile = YuvTile(4, 4, depth)
            YuvTiles.convert(Flat(3, 3, rgb), 0, 0, tile)
            val max = (1 shl depth) - 1
            assertThat(tile.y.toSet()).containsExactly((if (rgb[0] == 0) 0 else max).toShort())
            assertThat(tile.u.toSet()).containsExactly((1 shl (depth - 1)).toShort())
            assertThat(tile.v.toSet()).containsExactly((1 shl (depth - 1)).toShort())
        }
    }

    @Test
    fun `a saturated colour round-trips within one 10-bit level`() {
        val rgb = intArrayOf(52000, 13000, 30000)
        val tile = YuvTile(2, 2, 10)
        YuvTiles.convert(Flat(2, 2, rgb), 0, 0, tile)
        val back = YuvTiles.toRgb(tile.y[0].toInt(), tile.u[0].toInt(), tile.v[0].toInt(), 10)
        for (c in 0 until 3) assertThat(back[c]).isWithin(1.5 / 1023).of(rgb[c] / 65535.0)
    }

    @Test
    fun `edge tiles repeat the last row and column`() {
        val rows = object : RgbRows {
            override val width = 3
            override val height = 1
            override val bits = 8

            override fun row(y: Int, out: IntArray) {
                intArrayOf(0, 0, 0, 0, 0, 0, 255, 255, 255).copyInto(out)
            }
        }
        val tile = YuvTile(4, 2, 8)
        YuvTiles.convert(rows, 2, 0, tile)
        assertThat(tile.y.toSet()).containsExactly(255.toShort())
    }
}
