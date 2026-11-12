package dev.hfps.reveal.ml.tensor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResampleTest {
    @Test
    fun `same size is a copy`() {
        val src = floatArrayOf(1f, 2f, 3f, 4f)
        assertThat(Resample.plane(src, 2, 2, 2, 2)).isEqualTo(src)
    }

    @Test
    fun `shrinking averages the exact footprint`() {
        // 4 x 1 -> 2 x 1: pairs; 3 x 1 -> 2 x 1: the middle pixel is split in halves.
        assertThat(Resample.plane(floatArrayOf(0f, 2f, 4f, 6f), 4, 1, 2, 1).toList()).containsExactly(1f, 5f).inOrder()
        val r = Resample.plane(floatArrayOf(0f, 3f, 6f), 3, 1, 2, 1)
        assertThat(r[0]).isWithin(1e-6f).of((0f * 1f + 3f * 0.5f) / 1.5f)
        assertThat(r[1]).isWithin(1e-6f).of((3f * 0.5f + 6f * 1f) / 1.5f)
    }

    @Test
    fun `shrinking keeps the mean`() {
        val src = FloatArray(37 * 23) { (it * 7919 % 101) / 100f }
        val out = Resample.plane(src, 37, 23, 10, 7)
        assertThat(out.average()).isWithin(1e-4).of(src.average())
    }

    @Test
    fun `enlarging is bilinear with aligned centres and clamped edges`() {
        // 2 -> 4: centres at -0.25, 0.25, 0.75, 1.25 of the source -> clamped 0, 0.25, 0.75, 1.
        val out = Resample.plane(floatArrayOf(0f, 1f), 2, 1, 4, 1)
        assertThat(out.toList()).containsExactly(0f, 0.25f, 0.75f, 1f).inOrder()
    }

    @Test
    fun `interleaved channels stay apart`() {
        val src = floatArrayOf(1f, 10f, 3f, 30f)
        val out = Resample.interleaved(src, 2, 1, 2, 1, 1)
        assertThat(out.toList()).containsExactly(2f, 20f).inOrder()
    }
}
