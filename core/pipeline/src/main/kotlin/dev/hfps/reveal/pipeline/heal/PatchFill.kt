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
 * The patch-based Remove method (docs/adr/0023, "Remove: the patch method"): multi-scale image completion in the
 * manner of Wexler, Shechtman and Irani (2007), "Space-time completion of video", with the PatchMatch nearest-neighbour
 * search of Barnes, Shechtman, Finkelstein and Goldman (2009). Deterministic: a fixed number of iterations and a
 * xorshift generator seeded by [RemoveInput.seed].
 *
 * 1. A pyramid of the crop (2 x 2 means; a coarse pixel is in the hole when any of its pixels is) down to where the
 *    hole's larger side is at most [PATCH] pixels or the crop's smaller side would drop under `4 PATCH`.
 * 2. At the coarsest level the hole starts as the membrane of its edge ([Membrane]) and every patch that touches the
 *    hole (a *target*) gets a random *source* patch: one fully inside the crop that touches no hole pixel.
 * 3. Per level, a few rounds of: PatchMatch (propagation from the left/up then right/down neighbours, then a random
 *    search in windows halving from the crop's size) minimising the sum of squared differences over the [PATCH] x
 *    [PATCH] patch (display encoding, 3 channels, pixels outside the crop skipped), then a vote: each hole pixel is the
 *    mean of what the target patches that cover it propose, weighted by `exp(-d / 2 sigma^2)` (`d` the patch's
 *    distance per value, `sigma^2` the median of them).
 * 4. The field is upsampled to the next level (`2 q + (p - 2 parent)`), and so is the voted hole.
 *
 * Returns the finest voted crop and, per hole pixel, the offset to the centre of its own patch's source: the engine
 * copies the photo's pixels through it at any resolution.
 */
object PatchFill : RemoveMethod {
    /** Patch side (odd). */
    const val PATCH: Int = 7
    private const val R = PATCH / 2
    private const val C = 3
    private const val MAX_LEVELS = 8
    private const val PM_ROUNDS = 2

    override fun fill(input: RemoveInput): RemoveOutput {
        val levels = pyramid(input)
        val rng = XorShift(input.seed)
        var nnf: Nnf? = null
        var nnfLevel = -1
        for (l in levels.indices.reversed()) {
            val level = levels[l]
            val prev = nnf
            if (prev == null) {
                coarsestFill(level)
                nnf = Nnf(level).also { it.randomise(rng) }
            } else {
                upsampleImage(levels[l + 1], level)
                nnf = Nnf(level).also { it.upsample(prev, rng) }
            }
            val field = checkNotNull(nnf)
            nnfLevel = l
            if (!field.hasSources) break
            val rounds = if (l == levels.lastIndex) {
                8
            } else if (l == 0) {
                3
            } else {
                4
            }
            repeat(rounds) { round ->
                repeat(PM_ROUNDS) { field.iterate(rng, (round + it) % 2 == 1) }
                field.vote()
            }
        }
        val top = levels[0]
        val field = nnf
        val offsets = if (field != null && field.hasSources && nnfLevel == 0) field.offsets() else null
        return RemoveOutput(top.img, offsets)
    }

    /** One level: image (display, 3 channels), hole flags. */
    internal class Level(val w: Int, val h: Int, val img: FloatArray, val hole: BooleanArray)

    private fun pyramid(input: RemoveInput): List<Level> {
        val levels = ArrayList<Level>()
        var level = Level(
            input.width,
            input.height,
            input.rgb.copyOf(),
            BooleanArray(input.width * input.height) { input.hole[it] > 0.5f },
        )
        levels += level
        while (levels.size < MAX_LEVELS) {
            val extent = holeExtent(level)
            if (extent <= PATCH || min(level.w, level.h) / 2 < 4 * PATCH) break
            level = downsample(level)
            levels += level
        }
        return levels
    }

    private fun holeExtent(level: Level): Int {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (y in 0 until level.h) {
            for (x in 0 until level.w) {
                if (!level.hole[y * level.w + x]) continue
                x0 = min(x0, x)
                y0 = min(y0, y)
                x1 = max(x1, x)
                y1 = max(y1, y)
            }
        }
        return if (x1 < 0) 0 else max(x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun downsample(fine: Level): Level {
        val w = (fine.w + 1) / 2
        val h = (fine.h + 1) / 2
        val img = FloatArray(w * h * C)
        val hole = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var n = 0
                val o = y * w + x
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val fx = x * 2 + dx
                        val fy = y * 2 + dy
                        if (fx >= fine.w || fy >= fine.h) continue
                        val i = fy * fine.w + fx
                        n++
                        if (fine.hole[i]) hole[o] = true
                        for (c in 0 until C) img[o * C + c] += fine.img[i * C + c]
                    }
                }
                for (c in 0 until C) img[o * C + c] /= n
            }
        }
        return Level(w, h, img, hole)
    }

    /** The hole of the coarsest level as the membrane of its edge. */
    private fun coarsestFill(level: Level) {
        val state = ByteArray(level.w * level.h) { if (level.hole[it]) Membrane.INSIDE else Membrane.FIXED }
        val values = level.img.copyOf()
        Membrane.solve(level.w, level.h, state, values)
        for (i in 0 until level.w * level.h) {
            if (level.hole[i]) for (c in 0 until C) level.img[i * C + c] = values[i * C + c]
        }
    }

    /** The hole of [fine] from the voted [coarse] (nearest parent). */
    private fun upsampleImage(coarse: Level, fine: Level) {
        for (y in 0 until fine.h) {
            for (x in 0 until fine.w) {
                val i = y * fine.w + x
                if (!fine.hole[i]) continue
                val p = min(y / 2, coarse.h - 1) * coarse.w + min(x / 2, coarse.w - 1)
                for (c in 0 until C) fine.img[i * C + c] = coarse.img[p * C + c]
            }
        }
    }

    /** The nearest-neighbour field of one level: for every target centre, its source centre and distance. */
    internal class Nnf(private val level: Level) {
        private val w = level.w
        private val h = level.h
        private val img = level.img
        private val hole = level.hole

        /** Centres whose patch touches the hole. */
        private val target = BooleanArray(w * h)

        /** Centres usable as sources: patch inside the crop, no hole pixel in it. */
        private val valid = BooleanArray(w * h)
        private val validList: IntArray
        private val nx = IntArray(w * h) { -1 }
        private val ny = IntArray(w * h) { -1 }
        private val dist = FloatArray(w * h)

        val hasSources: Boolean get() = validList.isNotEmpty()

        init {
            // Integral image of the hole flags.
            val sum = IntArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var row = 0
                for (x in 0 until w) {
                    if (hole[y * w + x]) row++
                    sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row
                }
            }
            fun count(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                val a = max(x0, 0)
                val b = max(y0, 0)
                val c = min(x1, w)
                val d = min(y1, h)
                if (a >= c || b >= d) return 0
                return sum[d * (w + 1) + c] - sum[b * (w + 1) + c] - sum[d * (w + 1) + a] + sum[b * (w + 1) + a]
            }
            val list = ArrayList<Int>()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val n = count(x - R, y - R, x + R + 1, y + R + 1)
                    target[i] = n > 0
                    if (n == 0 && x >= R && y >= R && x < w - R && y < h - R) {
                        valid[i] = true
                        list += i
                    }
                }
            }
            validList = list.toIntArray()
        }

        fun randomise(rng: XorShift) {
            if (!hasSources) return
            for (i in 0 until w * h) {
                if (!target[i]) continue
                val q = validList[rng.below(validList.size)]
                set(i, q % w, q / w)
            }
        }

        fun upsample(coarse: Nnf, rng: XorShift) {
            if (!hasSources) return
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!target[i]) continue
                    val px = min(x / 2, coarse.w - 1)
                    val py = min(y / 2, coarse.h - 1)
                    val pi = py * coarse.w + px
                    var qx = -1
                    var qy = -1
                    if (coarse.nx[pi] >= 0) {
                        qx = (coarse.nx[pi] * 2 + (x - px * 2)).coerceIn(R, w - R - 1)
                        qy = (coarse.ny[pi] * 2 + (y - py * 2)).coerceIn(R, h - R - 1)
                    }
                    if (qx < 0 || !valid[qy * w + qx]) {
                        val q = validList[rng.below(validList.size)]
                        qx = q % w
                        qy = q / w
                    }
                    set(i, qx, qy)
                }
            }
        }

        private fun set(i: Int, qx: Int, qy: Int) {
            nx[i] = qx
            ny[i] = qy
            dist[i] = distance(i % w, i / w, qx, qy, Float.MAX_VALUE)
        }

        /** Sum of squared differences between the patches at ([px], [py]) and ([qx], [qy]); stops above [best]. */
        private fun distance(px: Int, py: Int, qx: Int, qy: Int, best: Float): Float {
            var s = 0f
            for (dy in -R..R) {
                val ty = py + dy
                if (ty < 0 || ty >= h) continue
                val trow = ty * w
                val srow = (qy + dy) * w
                for (dx in -R..R) {
                    val tx = px + dx
                    if (tx < 0 || tx >= w) continue
                    val t = (trow + tx) * C
                    val u = (srow + qx + dx) * C
                    val a = img[t] - img[u]
                    val b = img[t + 1] - img[u + 1]
                    val c = img[t + 2] - img[u + 2]
                    s += a * a + b * b + c * c
                }
                if (s >= best) return s
            }
            return s
        }

        private fun tryCandidate(i: Int, qx: Int, qy: Int) {
            if (qx < R || qy < R || qx >= w - R || qy >= h - R) return
            if (!valid[qy * w + qx]) return
            if (qx == nx[i] && qy == ny[i]) return
            val d = distance(i % w, i / w, qx, qy, dist[i])
            if (d < dist[i]) {
                nx[i] = qx
                ny[i] = qy
                dist[i] = d
            }
        }

        /** One PatchMatch pass: propagation in scan order ([reverse]: from the bottom right), then random search. */
        fun iterate(rng: XorShift, reverse: Boolean) {
            val step = if (reverse) -1 else 1
            val ys = if (reverse) (h - 1 downTo 0) else (0 until h)
            for (y in ys) {
                val xs = if (reverse) (w - 1 downTo 0) else (0 until w)
                for (x in xs) {
                    val i = y * w + x
                    if (!target[i]) continue
                    val ax = x - step
                    if (ax in 0 until w) {
                        val n = y * w + ax
                        if (nx[n] >= 0) tryCandidate(i, nx[n] + step, ny[n])
                    }
                    val ay = y - step
                    if (ay in 0 until h) {
                        val n = ay * w + x
                        if (nx[n] >= 0) tryCandidate(i, nx[n], ny[n] + step)
                    }
                    var radius = max(w, h)
                    while (radius >= 1) {
                        val qx = nx[i] + rng.between(-radius, radius)
                        val qy = ny[i] + rng.between(-radius, radius)
                        tryCandidate(i, qx, qy)
                        radius /= 2
                    }
                }
            }
        }

        /** The vote: every hole pixel becomes the weighted mean of what the covering target patches propose. */
        fun vote() {
            val per = (PATCH * PATCH * C).toFloat()
            val ds = ArrayList<Float>()
            for (i in 0 until w * h) if (target[i] && nx[i] >= 0) ds += dist[i] / per
            if (ds.isEmpty()) return
            ds.sort()
            val sigma2 = max(ds[ds.size / 2], 1e-6f)
            val acc = FloatArray(w * h * C)
            val wsum = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!target[i] || nx[i] < 0) continue
                    val wt = StrictMath.exp((-(dist[i] / per) / (2f * sigma2)).toDouble()).toFloat()
                    for (dy in -R..R) {
                        val ty = y + dy
                        if (ty < 0 || ty >= h) continue
                        for (dx in -R..R) {
                            val tx = x + dx
                            if (tx < 0 || tx >= w) continue
                            val t = ty * w + tx
                            if (!hole[t]) continue
                            val s = ((ny[i] + dy) * w + nx[i] + dx) * C
                            acc[t * C] += wt * img[s]
                            acc[t * C + 1] += wt * img[s + 1]
                            acc[t * C + 2] += wt * img[s + 2]
                            wsum[t] += wt
                        }
                    }
                }
            }
            for (t in 0 until w * h) {
                if (!hole[t] || wsum[t] <= 0f) continue
                for (c in 0 until C) img[t * C + c] = acc[t * C + c] / wsum[t]
            }
            // The distances refer to the new image from here on.
            for (i in 0 until w * h) {
                if (target[i] && nx[i] >= 0) dist[i] = distance(x(i), y(i), nx[i], ny[i], Float.MAX_VALUE)
            }
        }

        private fun x(i: Int) = i % w

        private fun y(i: Int) = i / w

        /** Per pixel `(dx, dy)` from a hole pixel to its patch's source centre; NaN elsewhere. */
        fun offsets(): FloatArray {
            val out = FloatArray(w * h * 2) { Float.NaN }
            for (i in 0 until w * h) {
                if (!hole[i] || nx[i] < 0) continue
                out[i * 2] = (nx[i] - x(i)).toFloat()
                out[i * 2 + 1] = (ny[i] - y(i)).toFloat()
            }
            return out
        }
    }

    /** xorshift64*: the same sequence on every platform. */
    internal class XorShift(seed: Long) {
        private var s = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

        fun next(): Long {
            s = s xor (s ushr 12)
            s = s xor (s shl 25)
            s = s xor (s ushr 27)
            return s * 0x2545F4914F6CDD1DL
        }

        /** Uniform in 0 until [n]. */
        fun below(n: Int): Int = ((next() ushr 33) % n).toInt()

        /** Uniform in [a]..[b]. */
        fun between(a: Int, b: Int): Int = a + below(b - a + 1)
    }
}
