package dev.hfps.reveal.ml.face

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.MlImage
import kotlin.math.PI
import org.junit.Test

class FaceCropTest {
    @Test
    fun `an upright crop maps its corners and centre onto the box`() {
        val crop = FaceCrop(cx = 100f, cy = 60f, side = 80f, angle = 0f)
        assertThat(crop.imageX(128f, 128f, 256)).isWithin(1e-4f).of(100f)
        assertThat(crop.imageY(128f, 128f, 256)).isWithin(1e-4f).of(60f)
        assertThat(crop.imageX(0f, 0f, 256)).isWithin(1e-4f).of(60f)
        assertThat(crop.imageY(0f, 0f, 256)).isWithin(1e-4f).of(20f)
        assertThat(crop.imageX(256f, 256f, 256)).isWithin(1e-4f).of(140f)
    }

    @Test
    fun `a turned crop's x axis follows the angle`() {
        val crop = FaceCrop(cx = 0f, cy = 0f, side = 256f, angle = (PI / 2).toFloat())
        // One crop pixel right of the centre is one image pixel down.
        assertThat(crop.imageX(129f, 128f, 256)).isWithin(1e-4f).of(0f)
        assertThat(crop.imageY(129f, 128f, 256)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `from a detection the crop is 1_5 x the long side and level with the eyes`() {
        val crop = FaceCrop.fromDetection(10f, 20f, 50f, 80f, 20f, 40f, 40f, 60f)
        assertThat(crop.cx).isEqualTo(30f)
        assertThat(crop.cy).isEqualTo(50f)
        assertThat(crop.side).isWithin(1e-4f).of(90f)
        assertThat(crop.angle).isWithin(1e-5f).of((PI / 4).toFloat())
    }

    @Test
    fun `from landmarks the crop recovers the frame they were drawn in`() {
        val truth = FaceCrop(cx = 200f, cy = 150f, side = 120f, angle = 0.3f)
        // A square of points (side 80 crop px in a 256 crop) plus two "eye corners" on its horizontal mid-line.
        val us = floatArrayOf(88f, 168f, 168f, 88f, 98f, 158f)
        val vs = floatArrayOf(88f, 88f, 168f, 168f, 128f, 128f)
        val xs = FloatArray(6) { truth.imageX(us[it], vs[it], 256) }
        val ys = FloatArray(6) { truth.imageY(us[it], vs[it], 256) }
        val found = FaceCrop.fromLandmarks(xs, ys, from = 4, to = 5)
        assertThat(found.angle).isWithin(1e-4f).of(0.3f)
        assertThat(found.cx).isWithin(1e-2f).of(200f)
        assertThat(found.cy).isWithin(1e-2f).of(150f)
        assertThat(found.side).isWithin(1e-2f).of(80f * 120f / 256f * FaceCrop.SCALE)
    }

    @Test
    fun `sampling an upright crop of a horizontal ramp reproduces the ramp`() {
        val w = 64
        val h = 32
        val rgb = FloatArray(w * h * 3) { i -> ((i / 3) % w + 0.5f) / w }
        val image = MlImage(w, h, rgb)
        val crop = FaceCrop(cx = 32f, cy = 16f, side = 16f, angle = 0f)
        val out = crop.sample(image, 16)
        // Crop pixel u covers image x = 24 + u .. 25 + u: its centre's ramp value.
        for (u in 0 until 16) assertThat(out[(8 * 16 + u) * 3]).isWithin(1e-5f).of((24 + u + 0.5f) / w)
    }
}
