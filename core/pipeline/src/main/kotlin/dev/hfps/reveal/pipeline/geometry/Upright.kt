@file:Suppress("MagicNumber", "ReturnCount", "LoopWithTooManyJumpStatements")

package dev.hfps.reveal.pipeline.geometry

import dev.hfps.reveal.pipeline.optics.LensWarp
import dev.hfps.reveal.pipeline.recipe.GuideLine
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.UprightMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Upright and auto-straighten (docs/adr/0015, "Upright"): from straight lines of the lens-corrected image, the
 * Transform sliders (vertical, horizontal, rotate) that make near-vertical lines plumb and parallel and near-horizontal
 * lines level, or the straighten angle that levels them. Pure and JVM-tested on synthetic perspective scenes.
 *
 * Lines are in **normalised** corrected-image coordinates (centre 0, half diagonal 1, y down; [normalise]). A line is
 * vertical when within [CLASS_ANGLE] of plumb, horizontal when within it of level; diagonals are ignored. For a slider
 * vector the cost is `sum w * rho(deviation)` over the lines mapped by [Perspective.forward], the deviation being the
 * angle to the line's axis; `rho` is a quadratic truncated at [INLIER] (robust to the lines that are not vertical or
 * horizontal in the world) for detected lines and plain quadratic for guides. A coarse grid then Nelder-Mead finds
 * the minimum.
 *
 * - Level: rotate only (horizontals; verticals when there are none).
 * - Vertical: vertical + rotate (verticals parallel and plumb; horizontals level the rotation when verticals agree).
 * - Full: vertical + horizontal + rotate.
 * - Auto: Full at [AUTO_STRENGTH] of the perspective (converging lines partly kept, as a natural look), rotation then
 *   solved again.
 * - Guided: the user's lines: two or more vertical ones solve the vertical slider, two or more horizontal ones the
 *   horizontal slider, the rotation always.
 */
object Upright {
    /** Lines within this many degrees of plumb or level take part. */
    const val CLASS_ANGLE: Double = 30.0

    /** Deviations beyond this many degrees count as outliers. */
    const val INLIER: Double = 2.0

    const val AUTO_STRENGTH: Double = 0.75

    /** Shortest line used, in half diagonals. */
    const val MIN_LENGTH: Double = 0.04

    private const val MAX_LINES = 400

    /** A line of the corrected image (normalised) with its weight and its axis. */
    private class Line(
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
        val w: Double,
        val vertical: Boolean,
    )

    /**
     * Detector [segments] (pixels of the **source** image, [width] x [height]) in normalised corrected-image
     * coordinates: the lens's radial table inverted (green), or unchanged without [lens].
     */
    fun normalise(segments: List<Segment>, width: Int, height: Int, lens: LensWarp?): List<Segment> {
        val half = hypot(width.toDouble(), height.toDouble()) / 2
        fun point(x: Double, y: Double): Pair<Double, Double> {
            val sx = (x - width / 2.0) / half
            val sy = (y - height / 2.0) / half
            if (lens == null) return sx to sy
            val rho = hypot(sx, sy)
            if (rho == 0.0) return 0.0 to 0.0
            // Solve lambda(r) * r = rho by fixed point (lambda is within a few percent of 1 and smooth).
            var r = rho
            repeat(INVERSION_STEPS) { r = rho / lens.lambda(1, r.toFloat()) }
            return sx * r / rho to sy * r / rho
        }
        return segments.map { s ->
            val (ax, ay) = point(s.x0, s.y0)
            val (bx, by) = point(s.x1, s.y1)
            Segment(ax, ay, bx, by, s.weight)
        }
    }

    /** Guides (normalised 0..1 of a [width] x [height] corrected image) as normalised centred segments. */
    fun guides(guides: List<GuideLine>, width: Int, height: Int): List<Segment> {
        val half = hypot(width.toDouble(), height.toDouble()) / 2
        return guides.map { g ->
            Segment(
                (g.x0 * width - width / 2.0) / half,
                (g.y0 * height - height / 2.0) / half,
                (g.x1 * width - width / 2.0) / half,
                (g.y1 * height - height / 2.0) / half,
            )
        }
    }

    /**
     * The Transform for [mode] from [base] (its aspect, scale and offsets are kept) with the normalised [lines] (for
     * [UprightMode.GUIDED], the guide lines), or `null` when there are too few lines to decide. [UprightMode.OFF]
     * gives [base] with the three sliders back at zero.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun solve(mode: UprightMode, lines: List<Segment>, base: Transform): Transform? {
        val cleared = base.copy(vertical = 0f, horizontal = 0f, rotate = 0f, upright = mode)
        if (mode == UprightMode.OFF) return cleared.copy(upright = UprightMode.OFF, guides = emptyList())
        val guided = mode == UprightMode.GUIDED
        val classified = classify(lines, guided)
        val verticals = classified.count { it.vertical }
        val horizontals = classified.size - verticals
        if (classified.isEmpty()) return null
        // Level reads the horizontals, Vertical the verticals; both fall back to the other set.
        val used = when (mode) {
            UprightMode.LEVEL -> classified.filter { !it.vertical }.ifEmpty { classified }
            UprightMode.VERTICAL -> classified.filter { it.vertical }.ifEmpty { classified }
            else -> classified
        }
        val robust = !guided
        if (mode == UprightMode.LEVEL) return minimise(listOf(Param.R), used, cleared, robust)
        // Stages keep the search out of degenerate corners (a strong tilt squashes lines towards its horizon, where
        // any direction looks level): verticals first, then the horizontals with the vertical fixed, then a local
        // refinement of everything from there.
        val verticalLines = classified.filter { it.vertical }
        var best = cleared
        if (verticals >= 2) {
            best = minimise(listOf(Param.V, Param.R), verticalLines, best, robust)
        } else if (mode == UprightMode.VERTICAL) {
            best = minimise(listOf(Param.R), used, best, robust)
        }
        if (mode == UprightMode.VERTICAL) return best
        if (horizontals >= 2) {
            best = minimise(listOf(Param.H, Param.R), classified, best, robust, range = HORIZONTAL_RANGE)
        } else if (verticals < 2) {
            best = minimise(listOf(Param.R), classified, best, robust)
        }
        val all = buildList {
            if (verticals >= 2) add(Param.V)
            if (horizontals >= 2) add(Param.H)
            add(Param.R)
        }
        best = refine(all, classified, best, robust)
        if (mode == UprightMode.AUTO) {
            val partial = best.copy(
                vertical = (best.vertical * AUTO_STRENGTH).toFloat(),
                horizontal = (best.horizontal * AUTO_STRENGTH).toFloat(),
            )
            best = minimise(listOf(Param.R), classified, partial, robust)
        }
        return best
    }

    /**
     * The straighten angle (degrees, the recipe's convention: positive turns the picture clockwise) that levels the
     * dominant near-horizontal and plumbs the near-vertical [lines] (normalised, corrected image) once [transform]
     * applies; `null` without usable lines. A weighted median, refined by the weighted mean of the lines within
     * [INLIER] of it.
     */
    fun straightenAngle(lines: List<Segment>, transform: Transform): Double? = straightenEstimate(lines, transform)?.angle

    /**
     * [straightenAngle] with how much the lines agree: [StraightenEstimate.support] is the share of the axis-aligned
     * line length within [INLIER] of the median, [StraightenEstimate.length] that length (normalised units). Smart
     * presets straighten only on a clear consensus (docs/adr/0010, "Auto-straighten").
     */
    fun straightenEstimate(lines: List<Segment>, transform: Transform): StraightenEstimate? {
        val f = Perspective.forward(transform)
        val devs = ArrayList<Pair<Double, Double>>()
        for (s in lines.take(MAX_LINES)) {
            if (s.length < MIN_LENGTH) continue
            val a = f.map(s.x0, s.y0) ?: continue
            val b = f.map(s.x1, s.y1) ?: continue
            val seen = Segment(a.x, a.y, b.x, b.y, s.weight)
            val dev = axisDeviation(seen.angle) ?: continue
            devs += dev to s.weight * seen.length
        }
        if (devs.isEmpty()) return null
        val median = weightedMedian(devs)
        val near = devs.filter { abs(it.first - median) <= INLIER }
        val nearWeight = near.sumOf { it.second }
        val mean = near.sumOf { it.first * it.second } / nearWeight
        return StraightenEstimate(-mean, nearWeight / devs.sumOf { it.second }, nearWeight)
    }

    /** A straighten angle (degrees) with the share ([support], 0..1) and [length] of the lines that agree on it. */
    data class StraightenEstimate(val angle: Double, val support: Double, val length: Double)

    /** Deviation of a line at [angle] (degrees, (-90, 90]) from its nearest axis, or `null` for diagonals. */
    private fun axisDeviation(angle: Double): Double? = when {
        abs(angle) <= CLASS_ANGLE -> angle
        angle >= 90 - CLASS_ANGLE -> angle - 90
        angle <= -(90 - CLASS_ANGLE) -> angle + 90
        else -> null
    }

    private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
        val sorted = values.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        var acc = 0.0
        for ((v, w) in sorted) {
            acc += w
            if (acc >= total / 2) return v
        }
        return sorted.last().first
    }

    private fun classify(lines: List<Segment>, guided: Boolean): List<Line> = lines
        .filter { guided || it.length >= MIN_LENGTH }
        .sortedByDescending { it.weight * it.length }
        .take(MAX_LINES)
        .mapNotNull { s ->
            val a = s.angle
            val vertical = when {
                abs(a) <= CLASS_ANGLE || (guided && abs(a) < 45.0) -> false
                abs(a) >= 90 - CLASS_ANGLE || guided -> true
                else -> return@mapNotNull null
            }
            Line(s.x0, s.y0, s.x1, s.y1, if (guided) 1.0 else s.weight * s.length, vertical)
        }

    private enum class Param(val scale: Double) { V(100.0), H(100.0), R(10.0) }

    private fun with(base: Transform, params: List<Param>, x: DoubleArray): Transform {
        var t = base
        params.forEachIndexed { i, p ->
            val v = (x[i] * p.scale).toFloat()
            t = when (p) {
                Param.V -> t.copy(vertical = RecipeRanges.transformVertical.clamp(v))
                Param.H -> t.copy(horizontal = RecipeRanges.transformHorizontal.clamp(v))
                Param.R -> t.copy(rotate = RecipeRanges.transformRotate.clamp(v))
            }
        }
        return t
    }

    private fun cost(t: Transform, lines: List<Line>, robust: Boolean): Double {
        val f = Perspective.forward(t)
        val c2 = INLIER * INLIER
        var sum = 0.0
        for (l in lines) {
            val a = f.map(l.x0, l.y0)
            val b = f.map(l.x1, l.y1)
            val d = if (a == null || b == null) {
                90.0
            } else {
                val dx = b.x - a.x
                val dy = b.y - a.y
                // Angle to the line's own axis, in degrees.
                Math.toDegrees(if (l.vertical) atan2(dx, dy) else atan2(dy, dx)).let { fold(it) }
            }
            sum += l.w * if (robust) min(d * d, c2) else d * d
        }
        return sum
    }

    /** An angle between two directions of a line, folded into [-90, 90]. */
    private fun fold(a: Double): Double {
        var v = a
        while (v > 90) v -= 180
        while (v < -90) v += 180
        return v
    }

    /** The current values of [params] in [t], scaled to the search space. */
    private fun values(t: Transform, params: List<Param>): DoubleArray = DoubleArray(params.size) { i ->
        when (params[i]) {
            Param.V -> t.vertical / Param.V.scale
            Param.H -> t.horizontal / Param.H.scale
            Param.R -> t.rotate / Param.R.scale
        }
    }

    /**
     * Grid search of [params] over `[-range, range]` of their scaled range (the other sliders as in [base]), then
     * Nelder-Mead from the best grid point.
     */
    private fun minimise(
        params: List<Param>,
        lines: List<Line>,
        base: Transform,
        robust: Boolean,
        range: Double = 1.0,
    ): Transform {
        val n = params.size
        val steps = when (n) {
            1 -> 81
            2 -> 41
            else -> 15
        }
        var best = values(base, params)
        var bestCost = cost(with(base, params, best), lines, robust)
        val x = DoubleArray(n)
        fun scan(dim: Int) {
            if (dim == n) {
                val c = cost(with(base, params, x), lines, robust)
                if (c < bestCost) {
                    bestCost = c
                    best = x.copyOf()
                }
                return
            }
            for (k in 0 until steps) {
                x[dim] = range * (-1.0 + 2.0 * k / (steps - 1))
                scan(dim + 1)
            }
        }
        scan(0)
        val refined = nelderMead(best, 2.0 * range / (steps - 1)) { cost(with(base, params, it), lines, robust) }
        return with(base, params, refined)
    }

    /** Nelder-Mead of [params] from their values in [start] (a local refinement, no grid). */
    private fun refine(params: List<Param>, lines: List<Line>, start: Transform, robust: Boolean): Transform {
        val x0 = values(start, params)
        val refined = nelderMead(x0, REFINE_SIZE) { cost(with(start, params, it), lines, robust) }
        val candidate = with(start, params, refined)
        return if (cost(candidate, lines, robust) <= cost(start, lines, robust)) candidate else start
    }

    /** Nelder-Mead from [start] with an initial simplex of [size]; standard coefficients. */
    private fun nelderMead(start: DoubleArray, size: Double, f: (DoubleArray) -> Double): DoubleArray {
        val n = start.size
        val pts = Array(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += size } }
        val vals = DoubleArray(n + 1) { f(pts[it]) }
        repeat(NM_ITERATIONS) {
            val order = (0..n).sortedBy { vals[it] }
            val p = order.map { pts[it] }.toTypedArray()
            val v = order.map { vals[it] }.toDoubleArray()
            for (i in 0..n) {
                pts[i] = p[i]
                vals[i] = v[i]
            }
            if (abs(vals[n] - vals[0]) < NM_TOLERANCE && spread(pts) < NM_TOLERANCE) return pts[0]
            val centroid = DoubleArray(n) { d -> (0 until n).sumOf { pts[it][d] } / n }
            fun along(t: Double) = DoubleArray(n) { d -> centroid[d] + t * (pts[n][d] - centroid[d]) }
            val reflected = along(-1.0)
            val fr = f(reflected)
            when {
                fr < vals[0] -> {
                    val expanded = along(-2.0)
                    val fe = f(expanded)
                    if (fe < fr) {
                        pts[n] = expanded
                        vals[n] = fe
                    } else {
                        pts[n] = reflected
                        vals[n] = fr
                    }
                }

                fr < vals[n - 1] -> {
                    pts[n] = reflected
                    vals[n] = fr
                }

                else -> {
                    val contracted = along(0.5)
                    val fc = f(contracted)
                    if (fc < vals[n]) {
                        pts[n] = contracted
                        vals[n] = fc
                    } else {
                        for (i in 1..n) {
                            pts[i] = DoubleArray(n) { d -> pts[0][d] + 0.5 * (pts[i][d] - pts[0][d]) }
                            vals[i] = f(pts[i])
                        }
                    }
                }
            }
        }
        return pts[(0..n).minBy { vals[it] }]
    }

    private fun spread(pts: Array<DoubleArray>): Double {
        var s = 0.0
        for (p in pts) for (d in p.indices) s = maxOf(s, abs(p[d] - pts[0][d]))
        return s
    }

    private const val NM_ITERATIONS = 400
    private const val REFINE_SIZE = 0.02
    private const val HORIZONTAL_RANGE = 0.7
    private const val NM_TOLERANCE = 1e-9
    private const val INVERSION_STEPS = 8
}
