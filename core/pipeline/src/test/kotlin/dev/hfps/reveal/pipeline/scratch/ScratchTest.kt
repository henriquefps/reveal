package dev.hfps.reveal.pipeline.scratch

import dev.hfps.reveal.pipeline.geometry.LineDetector
import dev.hfps.reveal.pipeline.geometry.Perspective
import dev.hfps.reveal.pipeline.geometry.Upright
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.UprightMode
import dev.hfps.reveal.pipeline.synthetic.PerspectiveScenes
import org.junit.Test

class ScratchTest {
    @Test
    fun ca() {
        for ((r, b) in listOf(1.0008 to 0.9993, 1.002 to 0.998, 1.0 to 1.0)) {
            val rgb = PerspectiveScenes.chromaticChecker(600, 400, r, b)
            println("CA $r $b -> " + dev.hfps.reveal.pipeline.optics.CaEstimator.estimate(rgb, 600, 400))
        }
    }

    @Test
    fun debug() {
        val truth = Transform(vertical = -25f, horizontal = 20f, rotate = -1.5f)
        val gray = PerspectiveScenes.facade(900, 600, truth)
        val l = Upright.normalise(LineDetector.detect(gray, 900, 600), 900, 600, null)
        println("lines ${l.size} v=${l.count { kotlin.math.abs(it.angle) > 60 }} h=${l.count { kotlin.math.abs(it.angle) < 30 }}")
        val t = Upright.solve(UprightMode.FULL, l, Transform())
        println("found $t")
        for (c in listOf(truth, t!!)) {
            val f = Perspective.forward(c)
            println("corners " + listOf(-0.83 to -0.55, 0.83 to -0.55, -0.83 to 0.55, 0.83 to 0.55).map { (x, y) -> f.m[6] * x + f.m[7] * y + f.m[8] })
        }
    }
}
