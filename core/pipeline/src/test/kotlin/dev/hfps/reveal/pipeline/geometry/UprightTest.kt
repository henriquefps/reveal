package dev.hfps.reveal.pipeline.geometry

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.optics.CaEstimator
import dev.hfps.reveal.pipeline.recipe.GuideLine
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.UprightMode
import dev.hfps.reveal.pipeline.synthetic.PerspectiveScenes
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Test

class UprightTest {
    private val w = 900
    private val h = 600

    private fun lines(correction: Transform = Transform(), tilt: Double = 0.0): List<Segment> {
        val gray = PerspectiveScenes.facade(w, h, correction, tilt)
        return Upright.normalise(LineDetector.detect(gray, w, h), w, h, null)
    }

    @Test
    fun `the detector finds the facade's long straight lines`() {
        val gray = PerspectiveScenes.facade(w, h)
        val segments = LineDetector.detect(gray, w, h)
        assertThat(segments.size).isAtLeast(20)
        val axisAligned = segments.count { abs(it.angle) < 0.2 || abs(abs(it.angle) - 90) < 0.2 }
        // On an upright scene nearly every detected line is level or plumb to within 0.2 degrees.
        assertThat(axisAligned.toDouble() / segments.size).isAtLeast(0.9)
    }

    @Test
    fun `auto-straighten levels synthetic scenes within 0_2 degrees`() {
        for (tilt in listOf(-8.3, -2.25, 0.6, 3.7, 12.0)) {
            val angle = checkNotNull(Upright.straightenAngle(lines(tilt = tilt), Transform()))
            assertThat(angle).isWithin(0.2).of(-tilt)
        }
    }

    @Test
    fun `upright level recovers a rotation`() {
        val t = checkNotNull(Upright.solve(UprightMode.LEVEL, lines(Transform(rotate = 4f)), Transform()))
        assertThat(t.rotate.toDouble()).isWithin(0.2).of(4.0)
        assertThat(t.vertical).isEqualTo(0f)
        assertThat(t.upright).isEqualTo(UprightMode.LEVEL)
    }

    /** Largest deviation from plumb of the near-vertical lines once [t] applies (degrees). */
    private fun worstVertical(lines: List<Segment>, t: Transform): Double {
        val f = Perspective.forward(t)
        return lines.filter { abs(it.angle) > 60 && it.length > 0.08 }.maxOf { s ->
            val a = checkNotNull(f.map(s.x0, s.y0))
            val b = checkNotNull(f.map(s.x1, s.y1))
            var d = Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
            if (d > 90) d -= 180
            if (d < -90) d += 180
            abs(d)
        }
    }

    @Test
    fun `upright vertical makes converging verticals plumb`() {
        val truth = Transform(vertical = 35f, rotate = 2f)
        val found = lines(truth)
        assertThat(worstVertical(found, Transform())).isGreaterThan(3.0)
        val t = checkNotNull(Upright.solve(UprightMode.VERTICAL, found, Transform(scale = 90f)))
        assertThat(t.vertical.toDouble()).isWithin(1.5).of(35.0)
        assertThat(t.rotate.toDouble()).isWithin(0.2).of(2.0)
        assertThat(t.scale).isEqualTo(90f)
        assertThat(worstVertical(found, t)).isLessThan(0.3)
    }

    @Test
    fun `upright full recovers both keystones`() {
        val truth = Transform(vertical = -25f, horizontal = 20f, rotate = -1.5f)
        val t = checkNotNull(Upright.solve(UprightMode.FULL, lines(truth), Transform()))
        assertThat(t.vertical.toDouble()).isWithin(2.0).of(-25.0)
        assertThat(t.horizontal.toDouble()).isWithin(2.0).of(20.0)
        assertThat(t.rotate.toDouble()).isWithin(0.3).of(-1.5)
    }

    @Test
    fun `upright auto keeps part of the convergence and levels`() {
        val truth = Transform(vertical = 40f)
        val t = checkNotNull(Upright.solve(UprightMode.AUTO, lines(truth), Transform()))
        assertThat(t.vertical.toDouble()).isWithin(3.0).of(40.0 * Upright.AUTO_STRENGTH)
        assertThat(abs(t.rotate.toDouble())).isLessThan(0.5)
    }

    @Test
    fun `guided lines drive the sliders`() {
        // Two facade edges of the corrected scene, located through the known truth (normalised -> 0..1).
        val truth = Transform(vertical = 30f)
        val back = Perspective.forward(truth).inverse()
        val half = kotlin.math.hypot(w.toDouble(), h.toDouble()) / 2
        fun guide(x: Double, y0: Double, y1: Double): GuideLine {
            val a = checkNotNull(back.map(x, y0))
            val b = checkNotNull(back.map(x, y1))
            return GuideLine(
                ((a.x * half + w / 2) / w).toFloat(),
                ((a.y * half + h / 2) / h).toFloat(),
                ((b.x * half + w / 2) / w).toFloat(),
                ((b.y * half + h / 2) / h).toFloat(),
            )
        }
        val guides = listOf(guide(-0.62, -0.4, 0.35), guide(0.62, -0.4, 0.35))
        val t = checkNotNull(Upright.solve(UprightMode.GUIDED, Upright.guides(guides, w, h), Transform()))
        assertThat(t.vertical.toDouble()).isWithin(0.5).of(30.0)
        assertThat(abs(t.rotate.toDouble())).isLessThan(0.1)
        assertThat(t.horizontal).isEqualTo(0f)
    }

    @Test
    fun `too few lines decide nothing and off clears the sliders`() {
        assertThat(Upright.solve(UprightMode.VERTICAL, emptyList(), Transform())).isNull()
        val off = checkNotNull(Upright.solve(UprightMode.OFF, emptyList(), Transform(vertical = 12f, scale = 80f)))
        assertThat(off.vertical).isEqualTo(0f)
        assertThat(off.scale).isEqualTo(80f)
        assertThat(off.upright).isEqualTo(UprightMode.OFF)
    }

    @Test
    fun `lateral CA is estimated from a checkerboard`() {
        val rgb = PerspectiveScenes.chromaticChecker(600, 400, 1.0008, 0.9993)
        val (red, blue) = checkNotNull(CaEstimator.estimate(rgb, 600, 400))
        assertThat(red.toDouble()).isWithin(1.5).of(8.0)
        assertThat(blue.toDouble()).isWithin(1.5).of(-7.0)
        val none = PerspectiveScenes.chromaticChecker(600, 400, 1.0, 1.0)
        val (r0, b0) = checkNotNull(CaEstimator.estimate(none, 600, 400))
        assertThat(abs(r0.toDouble())).isLessThan(1.0)
        assertThat(abs(b0.toDouble())).isLessThan(1.0)
    }
}
