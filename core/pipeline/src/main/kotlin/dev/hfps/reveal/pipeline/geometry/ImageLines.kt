@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.geometry

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.optics.LensWarp
import dev.hfps.reveal.pipeline.recipe.Optics
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The straight lines of a photo (docs/adr/0015, "Line detection"), found once on a luminance copy of the proxy at
 * most [LINE_EDGE] px long: Upright, auto-straighten and the smart presets' straighten rule use them.
 *
 * @property segments lines in pixels of the [width] x [height] source image.
 */
class ImageLines(val width: Int, val height: Int, val segments: List<Segment>) {
    /** The lines in normalised corrected-image coordinates for [lens] and [optics]. */
    fun normalised(lens: LensProfile?, optics: Optics): List<Segment> =
        Upright.normalise(segments, width, height, LensWarp.cached(lens, optics, width, height))

    companion object {
        /** Long edge of the luminance copy the lines are found on. */
        const val LINE_EDGE: Int = 1200

        /** The lines of [input] (the upright linear proxy). */
        fun of(input: PipelineInput): ImageLines {
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
                Segment(it.x0 * step, it.y0 * step, it.x1 * step, it.y1 * step, it.weight)
            }
            return ImageLines(w, h, found)
        }
    }
}
