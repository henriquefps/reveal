// Numeric and geometric code: early returns validate, loops mirror the shaders, constants are the maths.
@file:Suppress("NestedBlockDepth", "MagicNumber")

package dev.hfps.reveal.feature.editor.edit

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.geometry.LineDetector
import dev.hfps.reveal.pipeline.geometry.Segment
import dev.hfps.reveal.pipeline.geometry.Upright
import dev.hfps.reveal.pipeline.optics.CaEstimator
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.optics.LensWarp
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GuideLine
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.UprightMode
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the Optics panel and the Transform tools need from the proxy, taken at open while its pixels are on the heap
 * (docs/adr/0015): the straight lines of a downscaled luminance copy (Upright, auto-straighten) and, when the lens
 * profile has no CA data, the automatic lateral CA estimate. Pure and JVM-tested.
 *
 * @property segments lines in **proxy** pixels of the source image ([width] x [height]).
 */
class OpticsAnalysis(val width: Int, val height: Int, val segments: List<Segment>, val ca: Pair<Float, Float>?) {
    /** The lines in normalised corrected-image coordinates for [lens] and [optics]. */
    fun lines(lens: LensProfile?, optics: Optics): List<Segment> =
        Upright.normalise(segments, width, height, LensWarp.cached(lens, optics, width, height))

    /**
     * [geometry] after Upright [mode] (its guides for [UprightMode.GUIDED]), the crop refitted when constrained;
     * `null` when there are too few lines to decide.
     */
    fun upright(mode: UprightMode, geometry: Geometry, lens: LensProfile?, optics: Optics): Geometry? {
        val current = geometry.transform
        val lines = if (mode == UprightMode.GUIDED) {
            Upright.guides(current.guides, width, height)
        } else {
            lines(lens, optics)
        }
        val t = Upright.solve(mode, lines, current) ?: return null
        val kept = if (mode == UprightMode.GUIDED) t else t.copy(guides = emptyList())
        return CropEditing.transform(geometry, kept, width, height)
    }

    /** [geometry] straightened so its dominant lines are level / plumb; `null` without lines. */
    fun straighten(geometry: Geometry, lens: LensProfile?, optics: Optics): Geometry? {
        val angle = Upright.straightenAngle(lines(lens, optics), geometry.transform) ?: return null
        return CropEditing.straighten(geometry, angle.toFloat(), width, height)
    }

    companion object {
        /** Long edge of the luminance copy the lines are found on. */
        const val LINE_EDGE: Int = 1200

        /** Analyses [input] (the upright linear proxy); [estimateCa] runs the CA estimate too. */
        fun of(input: PipelineInput, estimateCa: Boolean): OpticsAnalysis {
            val w = input.width
            val h = input.height
            val step = max(1, (max(w, h) + LINE_EDGE - 1) / LINE_EDGE)
            val gw = w / step
            val gh = h / step
            val rgb = input.linearRgb
            val gray = FloatArray(gw * gh)
            var peak = 1e-6f
            for (y in 0 until gh) {
                for (x in 0 until gw) {
                    var sum = 0f
                    for (j in 0 until step) {
                        for (i in 0 until step) {
                            val k = ((y * step + j) * w + x * step + i) * 3
                            sum += rgb[k] + 2 * rgb[k + 1] + rgb[k + 2]
                        }
                    }
                    val v = sum / (4 * step * step)
                    gray[y * gw + x] = v
                    if (v > peak) peak = v
                }
            }
            // Perceptual-ish (a square root) and normalised, as the detector's gradient threshold expects.
            for (i in gray.indices) gray[i] = sqrt(max(0f, gray[i]) / peak)
            val found = LineDetector.detect(gray, gw, gh).map {
                Segment(
                    it.x0 * step,
                    it.y0 * step,
                    it.x1 * step,
                    it.y1 * step,
                    it.weight,
                )
            }
            val ca = if (estimateCa) runCatching { CaEstimator.estimate(rgb, w, h) }.getOrNull() else null
            return OpticsAnalysis(w, h, found, ca)
        }

        /** Guides drawn so far plus [line], at most [Transform.MAX_GUIDES] (the oldest drops). */
        fun withGuide(transform: Transform, line: GuideLine): List<GuideLine> =
            (transform.guides + line).takeLast(Transform.MAX_GUIDES)
    }
}
