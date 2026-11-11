package dev.hfps.reveal.feature.editor.crop

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.geometry.Box
import dev.hfps.reveal.pipeline.gl.ViewTransform
import org.junit.Test

class CropViewTest {
    @Test
    fun `the frame is centred and fills the slot minus the margin`() {
        val view = CropView.fit(Box(100.0, 50.0, 300.0, 150.0), 1000f, 800f, 50f, 100f)

        assertThat(view.scale).isEqualTo(4.5f)
        assertThat(view.centerX).isEqualTo(200f)
        assertThat(view.centerY).isEqualTo(100f)
        val tl = CropView.toSlot(view, 100.0, 50.0, 1000f, 800f)
        assertThat(tl.x).isWithin(1e-3f).of(50f)
    }

    @Test
    fun `the scale never exceeds the maximum`() {
        assertThat(CropView.fit(Box(0.0, 0.0, 2.0, 2.0), 1000f, 800f, 0f, 3f).scale).isEqualTo(3f)
    }

    @Test
    fun `slot and plane coordinates round trip`() {
        val view = ViewTransform(2.5f, 120f, 80f)
        val (x, y) = CropView.toPlane(view, Offset(333f, 44f), 600f, 400f)
        val back = CropView.toSlot(view, x, y, 600f, 400f)
        assertThat(back.x).isWithin(1e-3f).of(333f)
        assertThat(back.y).isWithin(1e-3f).of(44f)
    }

    @Test
    fun `interpolation starts and ends at the views and zooms geometrically`() {
        val a = ViewTransform(1f, 0f, 0f)
        val b = ViewTransform(4f, 10f, 20f)
        assertThat(CropView.lerp(a, b, 0f)).isEqualTo(a)
        assertThat(CropView.lerp(a, b, 1f).scale).isWithin(1e-5f).of(4f)
        assertThat(CropView.lerp(a, b, 0.5f).scale).isWithin(1e-5f).of(2f)
    }
}
