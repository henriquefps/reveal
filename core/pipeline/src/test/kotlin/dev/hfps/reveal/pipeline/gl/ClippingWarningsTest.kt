package dev.hfps.reveal.pipeline.gl

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.gl.ClippingWarnings.Clip
import java.nio.ByteOrder
import org.junit.Test

class ClippingWarningsTest {
    private val both = ClippingWarnings(shadows = true, highlights = true)

    @Test
    fun bitsMatchTheShaderFlags() {
        assertThat(ClippingWarnings.NONE.bits).isEqualTo(0)
        assertThat(ClippingWarnings(shadows = true).bits).isEqualTo(1)
        assertThat(ClippingWarnings(highlights = true).bits).isEqualTo(2)
        assertThat(both.bits).isEqualTo(3)
    }

    @Test
    fun highlightsAreAnyChannelThatQuantisesTo255() {
        assertThat(both.classify(1f, 0.2f, 0.2f)).isEqualTo(Clip.HIGHLIGHT)
        assertThat(both.classify(0.2f, 254.5f / 255f, 0.2f)).isEqualTo(Clip.HIGHLIGHT)
        assertThat(both.classify(0.2f, 254.4f / 255f, 0.2f)).isEqualTo(Clip.NONE)
    }

    @Test
    fun shadowsAreEveryChannelThatQuantisesToZero() {
        assertThat(both.classify(0f, 0f, 0f)).isEqualTo(Clip.SHADOW)
        assertThat(both.classify(0.4f / 255f, 0f, 0.1f / 255f)).isEqualTo(Clip.SHADOW)
        assertThat(both.classify(0f, 0.6f / 255f, 0f)).isEqualTo(Clip.NONE)
        // A saturated blue with red and green at zero is not shadow clipping.
        assertThat(both.classify(0f, 0f, 0.5f)).isEqualTo(Clip.NONE)
    }

    @Test
    fun offWarningsNeverClassify() {
        assertThat(ClippingWarnings.NONE.classify(1f, 1f, 1f)).isEqualTo(Clip.NONE)
        assertThat(ClippingWarnings(highlights = true).classify(0f, 0f, 0f)).isEqualTo(Clip.NONE)
        assertThat(ClippingWarnings(shadows = true).classify(1f, 1f, 1f)).isEqualTo(Clip.NONE)
    }

    @Test
    fun theOutputBlockCarriesTheBitsInTheDitherWord() {
        val block = GpuDevelopRenderer.outputBlock(
            4,
            4,
            ViewTransform(1f, 2f, 2f),
            4,
            4,
            dev.hfps.reveal.pipeline.cpu.OutputSettings(),
            FloatArray(3),
            3,
        ).order(ByteOrder.nativeOrder())
        // dither uvec4 ends at float 32 (mat4 16 + image, viewport, surround 12 + dither 4); geometry follows.
        assertThat(block.getInt(DITHER_END * Float.SIZE_BYTES - Int.SIZE_BYTES)).isEqualTo(3)
    }

    private companion object {
        const val DITHER_END = 32
    }
}
