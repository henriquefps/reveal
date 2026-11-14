@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import kotlin.math.max
import kotlin.math.min

/**
 * The membrane of the seamless repair (docs/adr/0023, "Heal"): the harmonic function (`Δm = 0`) inside a region that
 * takes given values on its boundary, per channel. Solved by a cascadic multigrid: the problem restricted to coarser
 * grids (2 x 2 cells; a coarse cell is fixed when any of its cells is, with their mean), the coarsest solved by
 * Gauss-Seidel, then at each finer level a bilinear prolongation as the start and red-black Gauss-Seidel sweeps
 * ([sweeps] at the finest level, doubling per coarser level up to [MAX_SWEEPS]). Deterministic (fixed order and count).
 *
 * Cells are [INSIDE] (unknown), [FIXED] (a boundary value) or [ABSENT] (outside the image: no constraint, a reflecting
 * edge). An inside cell is the mean of its present 4-neighbours.
 */
internal object Membrane {
    const val INSIDE: Byte = 0
    const val FIXED: Byte = 1
    const val ABSENT: Byte = 2

    private const val C = 3
    private const val MIN_SIDE = 4
    private const val COARSEST_SWEEPS = 400
    private const val MAX_SWEEPS = 64
    const val SWEEPS: Int = 6

    /**
     * Solves in place: [values] (`w * h * 3`) holds the fixed cells' values on entry and gets the inside cells' on
     * return. [state] is per cell.
     */
    fun solve(w: Int, h: Int, state: ByteArray, values: FloatArray, sweeps: Int = SWEEPS) {
        solveLevel(w, h, state, values, sweeps, 0)
    }

    private fun solveLevel(w: Int, h: Int, state: ByteArray, values: FloatArray, sweeps: Int, depth: Int) {
        if (state.none { it == INSIDE }) return
        if (min(w, h) <= MIN_SIDE || (w <= MIN_SIDE * 2 && h <= MIN_SIDE * 2)) {
            initialiseFlat(w, h, state, values)
            relax(w, h, state, values, COARSEST_SWEEPS)
            return
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val cs = ByteArray(cw * ch) { ABSENT }
        val cv = FloatArray(cw * ch * C)
        restrict(w, h, state, values, cw, ch, cs, cv)
        solveLevel(cw, ch, cs, cv, sweeps, depth + 1)
        prolong(w, h, state, values, cw, ch, cs, cv)
        val n = min(sweeps shl min(depth, 8), MAX_SWEEPS).coerceAtLeast(sweeps)
        relax(w, h, state, values, n)
    }

    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun restrict(
        w: Int,
        h: Int,
        state: ByteArray,
        values: FloatArray,
        cw: Int,
        ch: Int,
        cs: ByteArray,
        cv: FloatArray,
    ) {
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                var fixed = 0
                var inside = 0
                val sum = FloatArray(C)
                for (dy in 0..1) {
                    val y = cy * 2 + dy
                    if (y >= h) continue
                    for (dx in 0..1) {
                        val x = cx * 2 + dx
                        if (x >= w) continue
                        val i = y * w + x
                        when (state[i]) {
                            FIXED -> {
                                fixed++
                                for (c in 0 until C) sum[c] += values[i * C + c]
                            }

                            INSIDE -> inside++
                        }
                    }
                }
                val o = cy * cw + cx
                when {
                    fixed > 0 -> {
                        cs[o] = FIXED
                        for (c in 0 until C) cv[o * C + c] = sum[c] / fixed
                    }

                    inside > 0 -> cs[o] = INSIDE

                    else -> cs[o] = ABSENT
                }
            }
        }
    }

    /** Inside cells of the fine grid start from the coarse solution, bilinear over the present coarse cells. */
    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun prolong(
        w: Int,
        h: Int,
        state: ByteArray,
        values: FloatArray,
        cw: Int,
        ch: Int,
        cs: ByteArray,
        cv: FloatArray,
    ) {
        for (y in 0 until h) {
            // Fine cell centre in coarse cell coordinates (coarse centre i at 2i + 0.5).
            val fy = (y - 0.5f) / 2f
            val y0 = kotlin.math.floor(fy).toInt()
            val ty = fy - y0
            for (x in 0 until w) {
                val i = y * w + x
                if (state[i] != INSIDE) continue
                val fx = (x - 0.5f) / 2f
                val x0 = kotlin.math.floor(fx).toInt()
                val tx = fx - x0
                var wsum = 0f
                val acc = FloatArray(C)
                for (k in 0..3) {
                    val cx = x0 + (k and 1)
                    val cy = y0 + (k shr 1)
                    if (cx < 0 || cy < 0 || cx >= cw || cy >= ch) continue
                    val o = cy * cw + cx
                    if (cs[o] == ABSENT) continue
                    val wx = if (k and 1 == 0) 1f - tx else tx
                    val wy = if (k shr 1 == 0) 1f - ty else ty
                    val wt = wx * wy
                    if (wt <= 0f) continue
                    wsum += wt
                    for (c in 0 until C) acc[c] += wt * cv[o * C + c]
                }
                if (wsum > 0f) {
                    for (c in 0 until C) values[i * C + c] = acc[c] / wsum
                } else {
                    val o = min(y / 2, ch - 1) * cw + min(x / 2, cw - 1)
                    for (c in 0 until C) values[i * C + c] = cv[o * C + c]
                }
            }
        }
    }

    /** Inside cells start at the mean of the fixed cells (the coarsest grid). */
    private fun initialiseFlat(w: Int, h: Int, state: ByteArray, values: FloatArray) {
        val sum = DoubleArray(C)
        var n = 0
        for (i in 0 until w * h) {
            if (state[i] != FIXED) continue
            n++
            for (c in 0 until C) sum[c] += values[i * C + c].toDouble()
        }
        for (i in 0 until w * h) {
            if (state[i] != INSIDE) continue
            for (c in 0 until C) values[i * C + c] = if (n == 0) 0f else (sum[c] / n).toFloat()
        }
    }

    /** [count] red-black Gauss-Seidel sweeps over the inside cells. */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    fun relax(w: Int, h: Int, state: ByteArray, values: FloatArray, count: Int) {
        repeat(count) {
            for (colour in 0..1) {
                for (y in 0 until h) {
                    var x = (y + colour) and 1
                    while (x < w) {
                        val i = y * w + x
                        if (state[i] == INSIDE) {
                            var n = 0
                            var r = 0f
                            var g = 0f
                            var b = 0f
                            if (x > 0 && state[i - 1] != ABSENT) {
                                n++
                                r += values[(i - 1) * C]
                                g += values[(i - 1) * C + 1]
                                b += values[(i - 1) * C + 2]
                            }
                            if (x < w - 1 && state[i + 1] != ABSENT) {
                                n++
                                r += values[(i + 1) * C]
                                g += values[(i + 1) * C + 1]
                                b += values[(i + 1) * C + 2]
                            }
                            if (y > 0 && state[i - w] != ABSENT) {
                                n++
                                r += values[(i - w) * C]
                                g += values[(i - w) * C + 1]
                                b += values[(i - w) * C + 2]
                            }
                            if (y < h - 1 && state[i + w] != ABSENT) {
                                n++
                                r += values[(i + w) * C]
                                g += values[(i + w) * C + 1]
                                b += values[(i + w) * C + 2]
                            }
                            if (n > 0) {
                                val inv = 1f / n
                                values[i * C] = r * inv
                                values[i * C + 1] = g * inv
                                values[i * C + 2] = b * inv
                            }
                        }
                        x += 2
                    }
                }
            }
        }
    }

    /** Largest |Δm| residual of the inside cells (tests). */
    fun residual(w: Int, h: Int, state: ByteArray, values: FloatArray): Float {
        var worst = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (state[i] != INSIDE) continue
                for (c in 0 until C) {
                    var n = 0
                    var s = 0f
                    for ((dx, dy) in NEIGHBOURS) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val j = ny * w + nx
                        if (state[j] == ABSENT) continue
                        n++
                        s += values[j * C + c]
                    }
                    if (n > 0) worst = max(worst, kotlin.math.abs(s / n - values[i * C + c]))
                }
            }
        }
        return worst
    }

    private val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
}
