package dev.hfps.reveal.pipeline.gl

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Mask layers as cells of one 2D texture (docs/adr/0022): never wider or taller than the driver's limit. */
class LayerGridTest {
    @Test
    fun `cells go side by side, then in rows, within the limit`() {
        val proxy = LayerGrid.of(2400, 1600, 8, 16384)
        assertThat(proxy.columns).isEqualTo(6)
        assertThat(proxy.rows).isEqualTo(2)
        assertThat(proxy.width).isAtMost(16384)
        assertThat(proxy.origin(0)).isEqualTo(0 to 0)
        assertThat(proxy.origin(5)).isEqualTo(12000 to 0)
        assertThat(proxy.origin(6)).isEqualTo(0 to 1600)
        assertThat(proxy.capacity).isAtLeast(8)
        // One layer is the cell itself; a 2048 tile with 8 layers fits a 16384 texture in one row.
        assertThat(LayerGrid.of(2400, 1600, 1, 16384)).isEqualTo(LayerGrid(2400, 1600, 1, 1))
        assertThat(LayerGrid.of(2048, 2048, 8, 16384).rows).isEqualTo(1)
        // A small limit stacks rows; too many for it fails clearly.
        assertThat(LayerGrid.of(1000, 1000, 4, 2048)).isEqualTo(LayerGrid(1000, 1000, 2, 2))
        assertThrows(GlException::class.java) { LayerGrid.of(1000, 1000, 9, 2048) }
    }
}
