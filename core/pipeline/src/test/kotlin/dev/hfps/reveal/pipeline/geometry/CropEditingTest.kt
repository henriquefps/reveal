package dev.hfps.reveal.pipeline.geometry

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.pipeline.recipe.Aspect
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

class CropEditingTest {
    private val w = 300
    private val h = 200
    private val min = 8.0

    @Test
    fun `frame and withFrame round trip, and a full frame stores no crop`() {
        val g = Geometry(crop = CropRect(0.1f, 0.2f, 0.6f, 0.9f), rotate = 3, flipV = true, angle = 2f)
        val frame = CropEditing.frame(g, w, h)
        val back = CropEditing.withFrame(g, frame, w, h)
        val crop = checkNotNull(back.crop)

        assertThat(crop.left).isWithin(1e-6f).of(0.1f)
        assertThat(crop.top).isWithin(1e-6f).of(0.2f)
        assertThat(crop.right).isWithin(1e-6f).of(0.6f)
        assertThat(crop.bottom).isWithin(1e-6f).of(0.9f)
        val full = CropEditing.frame(Geometry(rotate = 1), w, h)
        assertThat(CropEditing.withFrame(g, full, w, h).crop).isNull()
    }

    @Test
    fun `straightening with the constraint shrinks the crop so no corner is empty`() {
        val start = Geometry()
        for (angle in listOf(-45f, -12.3f, 0.1f, 5f, 30f, 45f)) {
            val g = CropEditing.straighten(start, angle, w, h)
            assertThat(cornersInside(g)).isTrue()
            // The frame keeps its aspect (the image's) and its centre.
            val f = CropEditing.frame(g, w, h)
            assertThat(f.width / f.height).isWithin(1e-3).of(1.5)
            assertThat(f.centerX).isWithin(1e-3).of(150.0)
            assertThat(f.centerY).isWithin(1e-3).of(100.0)
        }
    }

    @Test
    fun `straightened crop is the largest that fits`() {
        val g = CropEditing.straighten(Geometry(), 10f, w, h)
        val f = CropEditing.frame(g, w, h)
        // Analytic: scale s with s * (w cos + h sin) <= w and s * (w sin + h cos) <= h.
        val rad = Math.toRadians(10.0)
        val s = minOf(w / (w * cos(rad) + h * sin(rad)), h / (w * sin(rad) + h * cos(rad)))
        assertThat(f.width).isWithin(0.05).of(w * s)
    }

    @Test
    fun `turning back during the same gesture regrows the crop`() {
        val start = Geometry(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f))
        val there = CropEditing.straighten(start, 40f, w, h)
        val back = CropEditing.straighten(start, 0f, w, h)

        assertThat(CropEditing.frame(there, w, h).width).isLessThan(CropEditing.frame(start, w, h).width)
        assertThat(back.crop).isEqualTo(start.crop)
    }

    @Test
    fun `without the constraint straightening keeps the crop`() {
        val start = Geometry(constrain = false)
        assertThat(CropEditing.straighten(start, 20f, w, h).crop).isNull()
        assertThat(CropEditing.straighten(start, 20f, w, h).angle).isEqualTo(20f)
    }

    @Test
    fun `turning the constraint on shrinks an empty-cornered crop`() {
        val g = CropEditing.straighten(Geometry(constrain = false), 15f, w, h)
        assertThat(cornersInside(g)).isFalse()
        assertThat(cornersInside(CropEditing.setConstrain(g, true, w, h))).isTrue()
    }

    @Test
    fun `handle drags stay valid under random gestures`() {
        val random = Random(3)
        for (ratio in listOf(null, 1.5, 0.8)) {
            var g = CropEditing.straighten(Geometry(), 8f, w, h)
            if (ratio !=
                null
            ) {
                g = CropEditing.withAspect(g, Aspect(AspectPreset.CUSTOM, (ratio * 10).toInt(), 10, true), w, h)
            }
            repeat(300) {
                val start = CropEditing.frame(g, w, h)
                val handle = CropHandle.entries[random.nextInt(CropHandle.entries.size)]
                val box = CropEditing.dragHandle(
                    g, start, handle, random.nextDouble(-200.0, 200.0), random.nextDouble(-200.0, 200.0), ratio, w, h,
                    min,
                )
                assertThat(CropEditing.isValid(g, box, w, h)).isTrue()
                assertThat(box.width).isAtLeast(min - 1e-4)
                assertThat(box.height).isAtLeast(min - 1e-4)
                if (ratio != null) assertThat(box.width / box.height).isWithin(1e-4).of(ratio)
                g = CropEditing.withFrame(g, box, w, h)
                assertWithMessage("ratio $ratio step $it ${g.crop} box $box").that(cornersInside(g)).isTrue()
            }
        }
    }

    @Test
    fun `a free corner drag past one side keeps sliding along the other`() {
        val g = Geometry(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f))
        val start = CropEditing.frame(g, w, h)
        // Far right (blocked at the image edge) and 20 px down.
        val box = CropEditing.dragHandle(g, start, CropHandle.BOTTOM_RIGHT, 1000.0, 20.0, null, w, h, min)

        assertThat(box.right).isWithin(1e-3).of(w.toDouble())
        assertThat(box.bottom).isWithin(1e-6).of(start.bottom + 20)
        assertThat(box.left).isEqualTo(start.left)
    }

    @Test
    fun `a locked corner drag keeps the ratio and the opposite corner`() {
        val g = Geometry(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f))
        val start = CropEditing.frame(g, w, h)
        val box = CropEditing.dragHandle(g, start, CropHandle.TOP_LEFT, -10.0, -5.0, 1.0, w, h, min)

        assertThat(box.right).isEqualTo(start.right)
        assertThat(box.bottom).isEqualTo(start.bottom)
        assertThat(box.width).isWithin(1e-9).of(box.height)
    }

    @Test
    fun `panning moves the frame the other way and stops at the edge`() {
        val g = Geometry(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f))
        val start = CropEditing.frame(g, w, h)
        val moved = CropEditing.pan(g, start, 10.0, 1000.0, w, h)

        assertThat(moved.left).isWithin(1e-9).of(start.left - 10)
        assertThat(moved.top).isWithin(1e-3).of(0.0)
        assertThat(moved.width).isWithin(1e-9).of(start.width)
    }

    @Test
    fun `pinching zooms into the crop about the focus and keeps the shape`() {
        val g = Geometry()
        val start = CropEditing.frame(g, w, h)
        val box = CropEditing.pinch(g, start, 2.0, 150.0, 100.0, w, h, min)

        assertThat(box.width).isWithin(1e-9).of(150.0)
        assertThat(box.height).isWithin(1e-9).of(100.0)
        assertThat(box.centerX).isWithin(1e-9).of(150.0)
        // Pinching out past the image stops at the image.
        val out = CropEditing.pinch(g, box, 0.1, 150.0, 100.0, w, h, min)
        assertThat(out.width).isWithin(1e-2).of(300.0)
    }

    @Test
    fun `aspect presets refit the frame, transpose with quarter turns and swap`() {
        val g = CropEditing.withAspect(Geometry(), Aspect(AspectPreset.R4_5, locked = true), w, h)
        val f = CropEditing.frame(g, w, h)
        assertThat(f.width / f.height).isWithin(1e-4).of(0.8)
        assertThat(f.height).isWithin(1e-3).of(200.0)

        val turned = CropEditing.rotate90(g, clockwise = true)
        assertThat(turned.aspect.preset).isEqualTo(AspectPreset.R5_4)
        val tf = CropEditing.frame(turned, w, h)
        assertThat(tf.width / tf.height).isWithin(1e-4).of(1.25)

        val swapped = CropEditing.swapOrientation(g, w, h)
        assertThat(swapped.aspect.preset).isEqualTo(AspectPreset.R5_4)
        val sf = CropEditing.frame(swapped, w, h)
        assertThat(sf.width / sf.height).isWithin(1e-4).of(1.25)

        val original = CropEditing.withAspect(Geometry(rotate = 1), Aspect(AspectPreset.ORIGINAL, locked = true), w, h)
        assertThat(CropEditing.ratio(original, w, h)).isWithin(1e-9).of(200.0 / 300.0)
        assertThat(CropEditing.withAspect(g, Aspect(AspectPreset.FREE), w, h).aspect.locked).isFalse()
    }

    @Test
    fun `the horizon line levels a tilted line as seen, also when mirrored`() {
        // A line going down to the right by 5 degrees as seen: turning -5 degrees (anticlockwise) levels it.
        val dy = 100 * kotlin.math.tan(Math.toRadians(5.0))
        assertThat(CropEditing.horizonAngle(Geometry(), 0.0, 0.0, 100.0, dy).toDouble()).isWithin(1e-4).of(-5.0)
        // The same line drawn right to left.
        assertThat(CropEditing.horizonAngle(Geometry(), 100.0, dy, 0.0, 0.0).toDouble()).isWithin(1e-4).of(-5.0)
        // A near-vertical line is made plumb.
        assertThat(CropEditing.horizonAngle(Geometry(), 0.0, 0.0, dy, 100.0).toDouble()).isWithin(1e-4).of(5.0)
        // Mirrored picture: the recipe turns the other way; the result is level as seen.
        val mirrored = Geometry(flipH = true, angle = 2f)
        assertThat(CropEditing.horizonAngle(mirrored, 0.0, 0.0, 100.0, dy).toDouble()).isWithin(1e-4).of(7.0)
        assertThat(CropEditing.seenAngle(mirrored)).isEqualTo(-2f)
    }

    @Test
    fun `locking keeps the current shape, unlocking frees any preset`() {
        val g = Geometry(crop = CropRect(0.1f, 0.1f, 0.4f, 0.9f))
        val locked = CropEditing.setLocked(g, true)
        val f = CropEditing.frame(g, w, h)
        assertThat(CropEditing.ratio(locked, w, h)).isWithin(1e-9).of(f.width / f.height)
        val preset = g.copy(aspect = Aspect(AspectPreset.R16_9, locked = true))
        assertThat(CropEditing.setLocked(preset, false).aspect.preset).isEqualTo(AspectPreset.FREE)
    }

    /** The straightened crop's four corners lie inside the image (within float rounding). */
    private fun cornersInside(g: Geometry): Boolean {
        val box = GeometryMapping.cropBox(g, w, h, snap = false)
        val cx = box.centerX
        val cy = box.centerY
        val rad = Math.toRadians(-g.angle.toDouble())
        return listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1).all { (sx, sy) ->
            val vx = sx * box.width / 2
            val vy = sy * box.height / 2
            val x = cx + vx * cos(rad) - vy * sin(rad)
            val y = cy + vx * sin(rad) + vy * cos(rad)
            x >= -1e-3 && y >= -1e-3 && x <= w + 1e-3 && y <= h + 1e-3
        }
    }
}
