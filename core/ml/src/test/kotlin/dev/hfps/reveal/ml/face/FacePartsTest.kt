package dev.hfps.reveal.ml.face

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.Mask
import dev.hfps.reveal.ml.MaskKind
import org.junit.Assert.assertThrows
import org.junit.Test

class FacePartsTest {
    private val w = 200
    private val h = 160
    private val face = SyntheticFace.landmarks(cx = 100f, cy = 80f, scale = 40f)

    private fun Mask.at(x: Float, y: Float): Float = this[x.toInt(), y.toInt()]

    /** The image point of a feature-relative position. */
    private fun p(f: SyntheticFace.Feature, dx: Float = 0f, dy: Float = 0f) =
        (100f + 40f * (f.x + dx)) to (80f + 40f * (f.y + dy))

    private fun mask(kind: MaskKind, faces: List<FaceLandmarks> = listOf(face)) = FaceParts.mask(kind, faces, w, h)

    @Test
    fun `eyes cover both eye openings and nothing between them`() {
        val m = mask(MaskKind.EYES)
        val (rx, ry) = p(SyntheticFace.RIGHT_EYE)
        val (lx, ly) = p(SyntheticFace.LEFT_EYE)
        assertThat(m.at(rx, ry)).isEqualTo(1f)
        assertThat(m.at(lx, ly)).isEqualTo(1f)
        assertThat(m.at(100f, ry)).isEqualTo(0f)
        // Area close to the two ellipses' (pi * 20 * 8 each).
        val area = m.values.sum()
        assertThat(area).isWithin(60f).of(2 * Math.PI.toFloat() * 20f * 8f)
    }

    @Test
    fun `lips are the outer contour minus the mouth opening, teeth the opening`() {
        val lips = mask(MaskKind.LIPS)
        val teeth = mask(MaskKind.TEETH)
        val (cx, cy) = p(SyntheticFace.LIPS_OUTER)
        assertThat(lips.at(cx, cy)).isEqualTo(0f)
        assertThat(teeth.at(cx, cy)).isEqualTo(1f)
        // Between the inner (ry 0.12) and outer (ry 0.35) contours, above the centre.
        val (ux, uy) = p(SyntheticFace.LIPS_OUTER, dy = -0.25f)
        assertThat(lips.at(ux, uy)).isEqualTo(1f)
        assertThat(teeth.at(ux, uy)).isEqualTo(0f)
    }

    @Test
    fun `irises stay inside the eye openings`() {
        val irises = mask(MaskKind.IRISES)
        val eyes = mask(MaskKind.EYES)
        val (ix, iy) = p(SyntheticFace.RIGHT_IRIS)
        assertThat(irises.at(ix, iy)).isEqualTo(1f)
        for (i in irises.values.indices) assertThat(irises.values[i]).isAtMost(eyes.values[i] + 1e-6f)
        assertThat(irises.mean()).isLessThan(eyes.mean())
    }

    @Test
    fun `brows are where the brows are`() {
        val m = mask(MaskKind.EYEBROWS)
        val (bx, by) = p(SyntheticFace.LEFT_BROW)
        assertThat(m.at(bx, by)).isEqualTo(1f)
        val (ex, ey) = p(SyntheticFace.LEFT_EYE)
        assertThat(m.at(ex, ey)).isEqualTo(0f)
    }

    @Test
    fun `several faces unite and no face gives an empty mask`() {
        val small = SyntheticFace.landmarks(cx = 40f, cy = 40f, scale = 12f)
        val both = mask(MaskKind.EYES, listOf(face, small))
        assertThat(both.at(40f - 12f, 40f - 6f)).isGreaterThan(0.9f)
        assertThat(both.at(p(SyntheticFace.RIGHT_EYE).first, p(SyntheticFace.RIGHT_EYE).second)).isEqualTo(1f)
        assertThat(mask(MaskKind.EYES, emptyList()).mean()).isEqualTo(0f)
    }

    @Test
    fun `the edge is soft, scaled to the face`() {
        val m = mask(MaskKind.EYES)
        val values = m.values.filter { it > 0f && it < 1f }
        assertThat(values).isNotEmpty()
        assertThat(FaceParts.FEATHER * face.eyeSpan).isGreaterThan(FaceParts.MIN_FEATHER)
    }

    @Test
    fun `only face parts are drawn`() {
        assertThrows(IllegalArgumentException::class.java) { mask(MaskKind.SKY) }
    }
}
