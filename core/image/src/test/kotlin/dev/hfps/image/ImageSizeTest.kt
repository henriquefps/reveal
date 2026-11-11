package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageSizeTest {
    @Test
    fun `fitLongEdge scales a 24 MP landscape to a screen proxy`() {
        val proxy = ImageSize(6000, 4000).fitLongEdge(3000)

        assertThat(proxy).isEqualTo(ImageSize(3000, 2000))
    }

    @Test
    fun `fitLongEdge never scales up`() {
        assertThat(ImageSize(800, 600).fitLongEdge(3000)).isEqualTo(ImageSize(800, 600))
    }

    @Test
    fun `megapixels of 6000 by 4000 is 24`() {
        assertThat(ImageSize(6000, 4000).megapixels).isWithin(1e-9).of(24.0)
    }

    @Test
    fun `non positive dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ImageSize(0, 10) }
    }
}
