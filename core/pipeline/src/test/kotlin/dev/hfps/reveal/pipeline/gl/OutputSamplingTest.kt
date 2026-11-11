package dev.hfps.reveal.pipeline.gl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutputSamplingTest {
    @Test
    fun lodIsExactlyZeroAtOneToOneAndWhenMagnified() {
        assertThat(OutputSampling.lod(1f)).isEqualTo(0f)
        assertThat(OutputSampling.lod(1.5f)).isEqualTo(0f)
        assertThat(OutputSampling.lod(8f)).isEqualTo(0f)
    }

    @Test
    fun lodIsLog2OfTheMinificationWhenZoomedOut() {
        assertThat(OutputSampling.lod(0.5f)).isWithin(1e-6f).of(1f)
        assertThat(OutputSampling.lod(0.25f)).isWithin(1e-6f).of(2f)
        assertThat(OutputSampling.lod(0.75f)).isWithin(1e-6f).of(0.4150375f)
    }

    @Test
    fun modeFetchesTexelsOnlyAtExactlyOneToOne() {
        assertThat(OutputSampling.mode(1f)).isEqualTo(OutputSampling.MODE_TEXEL_FETCH)
        assertThat(OutputSampling.mode(0.999f)).isEqualTo(OutputSampling.MODE_LOD)
        assertThat(OutputSampling.mode(2f)).isEqualTo(OutputSampling.MODE_LOD)
    }

    @Test
    fun nearestFromTwoHundredPercent() {
        assertThat(OutputSampling.nearestMagnification(1.99f)).isFalse()
        assertThat(OutputSampling.nearestMagnification(2f)).isTrue()
    }
}
