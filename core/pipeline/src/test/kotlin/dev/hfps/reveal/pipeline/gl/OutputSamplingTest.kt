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
    fun straightenedGeometryResamplesWithCatmullRomFromOneToOne() {
        assertThat(OutputSampling.mode(1f, axisAligned = false)).isEqualTo(OutputSampling.MODE_CATMULL_ROM)
        assertThat(OutputSampling.mode(3f, axisAligned = false)).isEqualTo(OutputSampling.MODE_CATMULL_ROM)
        assertThat(OutputSampling.mode(0.5f, axisAligned = false)).isEqualTo(OutputSampling.MODE_LOD)
        assertThat(OutputSampling.snapToOutputPixels(1f, axisAligned = false)).isTrue()
        assertThat(OutputSampling.snapToOutputPixels(1.5f, axisAligned = false)).isFalse()
        assertThat(OutputSampling.snapToOutputPixels(2f, axisAligned = false)).isTrue()
        assertThat(OutputSampling.snapToOutputPixels(1f, axisAligned = true)).isFalse()
    }

    @Test
    fun nearestFromTwoHundredPercent() {
        assertThat(OutputSampling.nearestMagnification(1.99f)).isFalse()
        assertThat(OutputSampling.nearestMagnification(2f)).isTrue()
    }

    @Test
    fun `the editor's fit views read level 0 or 1 without mips`() {
        // The phone's portrait fit (1080 px of a 2400 px proxy) and a landscape one.
        assertThat(OutputSampling.stageMode(0.45f)).isEqualTo(OutputSampling.MODE_BOX)
        assertThat(OutputSampling.stageMode(0.7f)).isEqualTo(OutputSampling.MODE_BOX)
        assertThat(OutputSampling.needsMips(0.45f)).isFalse()
        assertThat(OutputSampling.readsLevel0(0.45f)).isFalse()
        assertThat(OutputSampling.readsLevel0(0.7f)).isTrue()
        // Below a third: the mips (from level 1 when only it is current), never level 0 from 50 % down.
        assertThat(OutputSampling.stageMode(0.25f)).isEqualTo(OutputSampling.MODE_LOD)
        assertThat(OutputSampling.needsMips(0.25f)).isTrue()
        assertThat(OutputSampling.readsLevel0(0.25f)).isFalse()
        // 1:1, magnified and straightened views are unchanged and read level 0.
        assertThat(OutputSampling.stageMode(1f)).isEqualTo(OutputSampling.MODE_TEXEL_FETCH)
        assertThat(OutputSampling.stageMode(0.45f, axisAligned = false)).isEqualTo(OutputSampling.MODE_LOD)
        assertThat(OutputSampling.readsLevel0(2f)).isTrue()
    }
}
