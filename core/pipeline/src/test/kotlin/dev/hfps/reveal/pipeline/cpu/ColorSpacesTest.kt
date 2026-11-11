package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorSpacesTest {
    @Test
    fun `sRGB matrix matches the published values`() {
        val m = ColorSpaces.SRGB.toXyz
        // IEC 61966-2-1 / Lindbloom (D65): the 4-digit published matrix.
        assertThat(m[0, 0]).isWithin(2e-4).of(0.4124)
        assertThat(m[0, 1]).isWithin(2e-4).of(0.3576)
        assertThat(m[0, 2]).isWithin(2e-4).of(0.1805)
        assertThat(m[1, 0]).isWithin(2e-4).of(0.2126)
        assertThat(m[1, 1]).isWithin(2e-4).of(0.7152)
        assertThat(m[1, 2]).isWithin(2e-4).of(0.0722)
        assertThat(m[2, 0]).isWithin(2e-4).of(0.0193)
        assertThat(m[2, 1]).isWithin(2e-4).of(0.1192)
        assertThat(m[2, 2]).isWithin(2e-4).of(0.9505)
    }

    @Test
    fun `Rec2020 luminance row matches the standard`() {
        val m = ColorSpaces.REC2020.toXyz
        assertThat(m[1, 0]).isWithin(2e-4).of(0.2627)
        assertThat(m[1, 1]).isWithin(2e-4).of(0.6780)
        assertThat(m[1, 2]).isWithin(2e-4).of(0.0593)
    }

    @Test
    fun `white of every space maps to D65`() {
        for (space in listOf(ColorSpaces.SRGB, ColorSpaces.DISPLAY_P3, ColorSpaces.REC2020)) {
            val xyz = space.toXyz.times(1.0, 1.0, 1.0)
            for (i in 0..2) assertThat(xyz[i]).isWithin(1e-12).of(ColorSpaces.D65_XYZ[i])
        }
    }

    @Test
    fun `sRGB primaries are inside Rec2020`() {
        val toRec2020 = ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz
        for (primary in listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )) {
            for (c in toRec2020.times(primary)) assertThat(c).isAtLeast(-1e-9)
        }
        // And Rec2020 green is outside sRGB: negative components, which the pipeline keeps until the output clamp.
        val back = ColorSpaces.SRGB.fromXyz * ColorSpaces.REC2020.toXyz
        assertThat(back.times(0.0, 1.0, 0.0).min()).isLessThan(-0.5)
    }

    @Test
    fun `Bradford adaptation maps the source white to the destination white`() {
        val a = doubleArrayOf(0.96422, 1.0, 0.82521) // D50
        val adaptation = ColorSpaces.bradfordAdaptation(a, ColorSpaces.D65_XYZ)

        val mapped = adaptation.times(a)
        for (i in 0..2) assertThat(mapped[i]).isWithin(1e-12).of(ColorSpaces.D65_XYZ[i])
        // Lindbloom's published D50 -> D65 Bradford matrix, first row.
        assertThat(adaptation[0, 0]).isWithin(1e-4).of(0.9555766)
        assertThat(adaptation[0, 2]).isWithin(1e-4).of(0.0631636)
    }

    @Test
    fun `matrix inverse and product`() {
        val m = Mat3(2.0, 0.5, 0.0, 0.1, 1.0, 0.3, 0.0, 0.2, 3.0)
        val identity = m * m.inverse()
        for (r in 0..2) for (c in 0..2) assertThat(identity[r, c]).isWithin(1e-12).of(if (r == c) 1.0 else 0.0)
    }
}
