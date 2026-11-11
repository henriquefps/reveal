package dev.hfps.reveal.pipeline.geometry

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import kotlin.random.Random
import org.junit.Test

class GeometryMappingTest {
    private val w = 300
    private val h = 200

    @Test
    fun `default geometry is the identity`() {
        val m = GeometryMapping.of(Geometry(), w, h)

        assertThat(m.isIdentity).isTrue()
        assertThat(m.outputWidth).isEqualTo(w)
        assertThat(m.outputHeight).isEqualTo(h)
        assertThat(m).isEqualTo(GeometryMapping.identity(w, h))
    }

    @Test
    fun `a crop maps output pixel centres onto the crop's pixel centres`() {
        val m = GeometryMapping.of(Geometry(crop = CropRect(0.1f, 0.25f, 0.6f, 0.75f)), w, h)

        assertThat(m.outputWidth).isEqualTo(150)
        assertThat(m.outputHeight).isEqualTo(100)
        assertThat(m.axisAligned).isTrue()
        assertThat(m.outputToImage(0.5, 0.5)).isEqualTo(Point(30.5, 50.5))
        assertThat(m.outputToImage(149.5, 99.5)).isEqualTo(Point(179.5, 149.5))
    }

    @Test
    fun `crop edges snap to whole pixels and keep at least one pixel`() {
        val m = GeometryMapping.of(Geometry(crop = CropRect(0.1004f, 0.5f, 0.1005f, 0.5001f)), w, h)

        assertThat(m.outputWidth).isEqualTo(1)
        assertThat(m.outputHeight).isEqualTo(1)
        assertThat(m.outputToImage(0.5, 0.5)).isEqualTo(Point(30.5, 100.5))
    }

    @Test
    fun `a clockwise quarter turn puts the top-left pixel top-right`() {
        val m = GeometryMapping.of(Geometry(rotate = 1), w, h)

        assertThat(m.outputWidth).isEqualTo(h)
        assertThat(m.outputHeight).isEqualTo(w)
        // Image top-left pixel (0.5, 0.5) -> output top-right (h - 0.5, 0.5).
        assertThat(m.imageToOutput(0.5, 0.5)).isEqualTo(Point(h - 0.5, 0.5))
        assertThat(m.imageToOutput(w - 0.5, 0.5)).isEqualTo(Point(h - 0.5, w - 0.5))
    }

    @Test
    fun `flips mirror the final picture`() {
        val hFlip = GeometryMapping.of(Geometry(flipH = true), w, h)
        val vFlip = GeometryMapping.of(Geometry(flipV = true), w, h)
        // Flip horizontal after a quarter turn still mirrors left/right as seen.
        val turnedFlip = GeometryMapping.of(Geometry(rotate = 1, flipH = true), w, h)
        val turned = GeometryMapping.of(Geometry(rotate = 1), w, h)

        assertThat(hFlip.imageToOutput(0.5, 0.5)).isEqualTo(Point(w - 0.5, 0.5))
        assertThat(vFlip.imageToOutput(0.5, 0.5)).isEqualTo(Point(0.5, h - 0.5))
        val seen = turned.imageToOutput(10.5, 20.5)
        assertThat(turnedFlip.imageToOutput(10.5, 20.5)).isEqualTo(Point(turned.outputWidth - seen.x, seen.y))
    }

    @Test
    fun `four quarter turns and two flips of each kind are the identity`() {
        val g = Geometry(crop = CropRect(0.2f, 0.1f, 0.9f, 0.8f), angle = 3f)
        val base = GeometryMapping.of(g, w, h)
        var turned = g
        repeat(4) { turned = CropEditing.rotate90(turned, clockwise = true) }
        val flipped = CropEditing.flip(CropEditing.flip(g, true), true)

        assertThat(GeometryMapping.of(turned, w, h)).isEqualTo(base)
        assertThat(GeometryMapping.of(flipped, w, h)).isEqualTo(base)
    }

    @Test
    fun `rotating left then right restores the mapping, also when mirrored`() {
        for (flipH in listOf(false, true)) {
            val g = Geometry(angle = -4f, flipH = flipH, rotate = 2)
            val back = CropEditing.rotate90(CropEditing.rotate90(g, clockwise = false), clockwise = true)
            assertThat(GeometryMapping.of(back, w, h)).isEqualTo(GeometryMapping.of(g, w, h))
        }
    }

    @Test
    fun `a turn as seen is the same picture turned, whatever the flips`() {
        for (flipH in listOf(false, true)) for (flipV in listOf(false, true)) for (k in 0..3) {
            val g = Geometry(rotate = k, flipH = flipH, flipV = flipV)
            val before = GeometryMapping.of(g, w, h)
            val after = GeometryMapping.of(CropEditing.rotate90(g, clockwise = true), w, h)
            // Clockwise as seen: output (x, y) of the old picture goes to (H' - y, x) with H' the old height.
            val p = before.imageToOutput(12.5, 34.5)
            assertThat(after.imageToOutput(12.5, 34.5)).isEqualTo(Point(before.outputHeight - p.y, p.x))
        }
    }

    @Test
    fun `round trips image to output to image`() {
        val random = Random(7)
        repeat(200) {
            val l = random.nextFloat() * 0.5f
            val t = random.nextFloat() * 0.5f
            val g = Geometry(
                crop = CropRect(l, t, l + 0.1f + random.nextFloat() * 0.4f, t + 0.1f + random.nextFloat() * 0.4f),
                angle = random.nextFloat() * 90f - 45f,
                rotate = random.nextInt(4),
                flipH = random.nextBoolean(),
                flipV = random.nextBoolean(),
            )
            for (m in listOf(GeometryMapping.of(g, w, h), GeometryMapping.editing(g, w, h))) {
                val x = random.nextDouble() * w
                val y = random.nextDouble() * h
                val o = m.imageToOutput(x, y)
                val back = m.outputToImage(o.x, o.y)
                assertThat(back.x).isWithin(1e-9).of(x)
                assertThat(back.y).isWithin(1e-9).of(y)
            }
        }
    }

    @Test
    fun `straightening turns about the crop centre clockwise as seen`() {
        val g = Geometry(crop = CropRect(0.2f, 0.2f, 0.6f, 0.8f), angle = 10f)
        val m = GeometryMapping.of(g, w, h)
        val c = m.outputToImage(m.outputWidth / 2.0, m.outputHeight / 2.0)
        // The crop centre is the output centre.
        assertThat(c.x).isWithin(1e-9).of(120.0)
        assertThat(c.y).isWithin(1e-9).of(100.0)
        // A point to the right of the centre in the image appears turned clockwise (below the horizontal).
        val o = m.imageToOutput(c.x + 50, c.y)
        assertThat(o.y).isGreaterThan(m.outputHeight / 2.0)
    }

    @Test
    fun `the editing plane's frame holds exactly the operator's output`() {
        val random = Random(11)
        repeat(100) {
            val g = Geometry(
                crop = CropRect(0.1f, 0.2f, 0.7f, 0.9f),
                angle = random.nextFloat() * 20f - 10f,
                rotate = random.nextInt(4),
                flipH = random.nextBoolean(),
                flipV = random.nextBoolean(),
            )
            // Pixel-exact crop edges so snapping does not separate the two.
            val final = GeometryMapping.of(g, 100, 100)
            val plane = GeometryMapping.editing(g, 100, 100)
            val frame = GeometryMapping.frame(g, 100, 100)
            assertThat(frame.width).isWithin(1e-4).of(final.outputWidth.toDouble())
            assertThat(frame.height).isWithin(1e-4).of(final.outputHeight.toDouble())
            val ox = random.nextDouble() * final.outputWidth
            val oy = random.nextDouble() * final.outputHeight
            val viaFinal = final.outputToImage(ox, oy)
            val viaPlane = plane.outputToImage(frame.left + ox, frame.top + oy)
            assertThat(viaPlane.x).isWithin(1e-4).of(viaFinal.x)
            assertThat(viaPlane.y).isWithin(1e-4).of(viaFinal.y)
        }
    }

    @Test
    fun `the editing frame does not move while straightening`() {
        val g = Geometry(crop = CropRect(0.1f, 0.3f, 0.5f, 0.7f), rotate = 1)
        val before = GeometryMapping.frame(g, w, h)
        val after = GeometryMapping.frame(g.copy(angle = 12f), w, h)

        assertThat(after).isEqualTo(before)
        // ... and the crop centre is the fixed point of the plane's rotation.
        val plane = GeometryMapping.editing(g.copy(angle = 12f), w, h)
        val c = plane.imageToOutput(0.3 * w, 0.5 * h)
        assertThat(c.x).isWithin(1e-9).of(before.centerX)
        assertThat(c.y).isWithin(1e-9).of(before.centerY)
    }

    @Test
    fun `the source region of a tile covers every sample with the filter margin`() {
        val g = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f), angle = 20f)
        val m = GeometryMapping.of(g, w, h)
        val region = checkNotNull(m.sourceRegion(10, 20, 64, 48))
        for (y in 20 until 68) for (x in 10 until 74) {
            val p = m.outputToImage(x + 0.5, y + 0.5)
            if (p.x !in 0.0..w.toDouble() || p.y !in 0.0..h.toDouble()) continue
            assertThat(p.x - 2).isAtLeast(region.left - 1e-9)
            assertThat(p.x + 2).isAtMost(region.right + 1e-9)
            assertThat(p.y - 2).isAtLeast(region.top - 1e-9)
            assertThat(p.y + 2).isAtMost(region.bottom + 1e-9)
        }
    }
}
