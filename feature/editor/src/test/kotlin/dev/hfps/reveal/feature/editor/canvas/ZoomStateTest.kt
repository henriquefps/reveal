package dev.hfps.reveal.feature.editor.canvas

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.gl.ViewTransform
import org.junit.Test

class ZoomStateTest {
    private val published = mutableListOf<ViewTransform>()

    private fun state(hundred: Float = 1f): ZoomState = ZoomState(hundred, onChange = { published += it }).apply {
        setViewSize(1000, 2000)
        setImageSize(2400, 1600)
    }

    @Test
    fun `starts at fit, centred`() {
        val z = state()

        assertThat(z.scale).isWithin(1e-6f).of(1000f / 2400f)
        assertThat(z.isFit).isTrue()
        assertThat(z.centerX).isEqualTo(1200f)
        assertThat(z.centerY).isEqualTo(800f)
        assertThat(published.last().scale).isEqualTo(z.scale)
    }

    @Test
    fun `cannot zoom out below fit or in beyond 400 percent`() {
        val z = state()

        z.zoomBy(0.1f, 500f, 1000f)
        assertThat(z.scale).isEqualTo(z.fitScale)
        z.zoomBy(1000f, 500f, 1000f)
        assertThat(z.scale).isEqualTo(4f)
    }

    @Test
    fun `zoom keeps the point under the finger fixed`() {
        val z = state()
        z.scaleTo(1f, 500f, 1000f)
        // Focus on a point away from the middle: the image point under it must not move.
        val before = z.centerX + (200f - 500f) / z.scale
        z.zoomBy(2f, 200f, 1000f)

        val after = z.centerX + (200f - 500f) / z.scale
        assertThat(after).isWithin(1e-3f).of(before)
    }

    @Test
    fun `pan is limited to the image, and the image stays centred along an axis it does not fill`() {
        val z = state()
        z.scaleTo(1f, 500f, 1000f)
        assertThat(z.isCentered(axisX = false)).isTrue()

        z.panBy(-100000f, 100000f)

        assertThat(z.centerX).isEqualTo(2400f - 500f)
        assertThat(z.centerY).isEqualTo(800f)
        z.panBy(100000f, 0f)
        assertThat(z.centerX).isEqualTo(500f)
    }

    @Test
    fun `double tap goes to 100 percent then back to fit`() {
        val z = state()

        assertThat(z.doubleTapTarget()).isEqualTo(1f)
        z.scaleTo(z.doubleTapTarget(), 500f, 1000f)
        assertThat(z.doubleTapTarget()).isEqualTo(z.fitScale)
    }

    @Test
    fun `100 percent accounts for a downscaled proxy`() {
        val z = state(hundred = 2.5f)

        assertThat(z.oneToOneScale).isEqualTo(2.5f)
        assertThat(z.maxScale).isEqualTo(10f)
    }

    @Test
    fun `resizing the view at fit stays at fit`() {
        val z = state()

        z.setViewSize(1200, 1200)

        assertThat(z.isFit).isTrue()
        assertThat(z.scale).isWithin(1e-6f).of(0.5f)
    }
}
