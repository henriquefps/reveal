@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.synthetic

import dev.hfps.reveal.pipeline.geometry.Perspective
import dev.hfps.reveal.pipeline.recipe.Transform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Synthetic scenes with known geometry for Upright, auto-straighten and CA tests (docs/adr/0015): a building facade
 * (window grid, floor lines, a horizon) seen through a known camera turn, and a checkerboard with known lateral CA.
 * Anti-aliased by 4 x 4 supersampling. Coordinates are the [dev.hfps.reveal.pipeline.geometry.Perspective] ones:
 * normalised to the half diagonal, centre 0, y down.
 */
object PerspectiveScenes {
    private const val SS = 4

    /**
     * The facade as a camera would see it when [Upright] with [correction] makes it upright again: the world (plumb
     * verticals, level floors) mapped by the inverse of the correction's homography, then turned by [tilt] degrees
     * (clockwise as seen) for straightening tests. Luminance 0..1, `width * height`.
     */
    fun facade(width: Int, height: Int, correction: Transform = Transform(), tilt: Double = 0.0): FloatArray {
        val toWorld = Perspective.forward(correction)
        val half = hypot(width.toDouble(), height.toDouble()) / 2
        val a = Math.toRadians(-tilt)
        val ca = cos(a)
        val sa = sin(a)
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0
                for (j in 0 until SS) {
                    for (i in 0 until SS) {
                        val u = (x + (i + 0.5) / SS - width / 2.0) / half
                        val v = (y + (j + 0.5) / SS - height / 2.0) / half
                        // Undo the tilt (a turn about the centre), then the camera's perspective.
                        val ux = ca * u - sa * v
                        val uy = sa * u + ca * v
                        val w = toWorld.map(ux, uy)
                        sum += if (w == null) 0.1 else world(w.x, w.y)
                    }
                }
                out[y * width + x] = (sum / (SS * SS)).toFloat()
            }
        }
        return out
    }

    /** The world: sky above the horizon, a facade with a grid of windows, a pavement below. */
    private fun world(x: Double, y: Double): Double {
        val horizon = -0.05
        if (y < -0.42) return 0.85
        if (abs(x) > 0.62) return if (y < horizon) 0.85 else 0.35
        if (y > 0.38) return 0.3
        // Facade: light wall, dark windows on a 0.12 x 0.11 grid, floor bands.
        val gx = floor((x + 0.62) / 0.124)
        val fx = (x + 0.62) / 0.124 - gx
        val fy = (y + 0.42) / 0.11 - floor((y + 0.42) / 0.11)
        val window = fx in 0.22..0.78 && fy in 0.2..0.75
        val band = fy < 0.06
        return when {
            window -> 0.15
            band -> 0.45
            else -> 0.7
        }
    }

    /**
     * A checkerboard (squares of [square] pixels) in linear RGB where red is the green image magnified by [redScale]
     * about the centre and blue by [blueScale] (lateral CA as a lens makes it); `width * height * 3`.
     */
    fun chromaticChecker(
        width: Int,
        height: Int,
        redScale: Double,
        blueScale: Double,
        square: Double = 23.0,
    ): FloatArray {
        val out = FloatArray(width * height * 3)
        val cx = width / 2.0
        val cy = height / 2.0

        // Exact box-filter coverage (supersampling would quantise sub-pixel edge positions): along one axis, the
        // share of [a, b] in the "even" squares.
        fun even(a: Double, b: Double): Double {
            fun f(v: Double): Double {
                val k = floor(v / square)
                val inEven = if (k.toLong() % 2 == 0L) v - k * square else 0.0
                return floor(k / 2 + 0.5).let { pairs -> pairs * square } + inEven
            }
            return (f(b) - f(a)) / (b - a)
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = (y * width + x) * 3
                for (c in 0 until 3) {
                    val m = when (c) {
                        0 -> redScale
                        2 -> blueScale
                        else -> 1.0
                    }
                    // The channel shows the scene at radius r where green shows it at r / m.
                    val ex = even(cx + (x - cx) / m, cx + (x + 1 - cx) / m)
                    val ey = even(cy + (y - cy) / m, cy + (y + 1 - cy) / m)
                    val same = ex * ey + (1 - ex) * (1 - ey)
                    out[o + c] = (0.8 - 0.75 * same).toFloat()
                }
            }
        }
        return out
    }
}
