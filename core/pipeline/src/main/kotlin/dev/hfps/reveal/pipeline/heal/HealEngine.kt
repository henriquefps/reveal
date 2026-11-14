@file:Suppress(
    "MagicNumber",
    "TooManyFunctions",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.mask.MaskMath
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A repaired rectangle: [rgb] (3 floats a pixel) replaces the image over ([x0], [y0], [width] x [height]). */
class HealPatch(val x0: Int, val y0: Int, val width: Int, val height: Int, val rgb: FloatArray) {
    val x1: Int get() = x0 + width
    val y1: Int get() = y0 + height
}

/**
 * Where Remove spots get their fills (docs/adr/0023): the [source] (cache and worker) and the [original] photo the
 * crops are read from (the full-resolution image when there is one, so the editor and an export ask for the same
 * fill), with its [colour] matrices. [NONE]: Remove spots render nothing.
 */
class RemoveContext(val source: RemoveSource, val original: PixelReader?, val colour: PipelineInput?) {
    @Volatile
    private var identity: String? = null

    /** [RemoveSpec.identity] of [original], computed once. */
    fun identity(): String? {
        val reader = original ?: return null
        identity?.let { return it }
        return RemoveSpec.identity(reader).also { identity = it }
    }

    companion object {
        val NONE: RemoveContext = RemoveContext(RemoveSource.NONE, null, null)
    }
}

/**
 * The healing stage (docs/adr/0023): the spots of a recipe applied in order to the input image of one resolution
 * ([width] x [height], read through [base]), before every other operator. Each spot reads the image as the spots
 * before it left it and becomes a [HealPatch] over its shape's box; the healed image is [base] with the patches written
 * over it in order. The same code serves the CPU reference, the editor's preview (whose patches are uploaded into the
 * GPU's input texture), its zoom tiles and the export ([HealedTileSource]).
 *
 * [update] keeps the patches of spots whose inputs did not change (a drag of one spot recomputes that spot, and the
 * later spots that read where it wrote) and returns the rectangle where the healed image changed.
 */
class HealEngine(
    val width: Int,
    val height: Int,
    private val base: PixelReader,
    colour: PipelineInput,
    private val remove: RemoveContext = RemoveContext.NONE,
) {
    private val colourInfo = colour
    private val display: RemoveColour = RemoveColour.of(colour)
    private var spots: List<Heal> = emptyList()
    private var patches: List<HealPatch?> = emptyList()
    private var reads: List<IntArray?> = emptyList()
    private var waiting: List<Boolean> = emptyList()

    /** Ids of the Remove spots whose fill is still being computed. */
    var pending: Set<String> = emptySet()
        private set

    /** Ids of the Remove spots whose fill failed (they render nothing). */
    var failed: Set<String> = emptySet()
        private set

    /** The patches of the spots that render, in order (null: a Remove spot without its fill yet). */
    val current: List<HealPatch?> get() = patches

    /** The spots of the last [update] that render, in order. */
    val rendered: List<Heal> get() = spots

    /** True when no spot renders. */
    val isEmpty: Boolean get() = patches.all { it == null }

    /**
     * Brings the patches to [heals] (the spots that do not render are ignored). Returns the rectangle (x0, y0, x1,
     * y1; exclusive) where the healed image may differ from before, or null when nothing changed. [onReady] is called
     * (any thread) when a Remove fill that was missing arrives: call [update] again then.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    fun update(heals: List<Heal>, onReady: () -> Unit = {}): IntArray? {
        val list = heals.filter { it.renders }
        val newPatches = ArrayList<HealPatch?>(list.size)
        val newReads = ArrayList<IntArray?>(list.size)
        val newWaiting = ArrayList<Boolean>(list.size)
        val changed = ArrayList<IntArray>()
        val pend = HashSet<String>()
        val fail = HashSet<String>()
        fun compute(h: Heal) {
            val res = patch(h, newPatches, onReady)
            newPatches += res.patch
            newReads += res.reads
            newWaiting += res.waiting
            if (res.waiting) pend += h.id
            if (res.failed) fail += h.id
            res.patch?.let { changed += intArrayOf(it.x0, it.y0, it.x1, it.y1) }
        }
        var a = 0
        var b = 0
        while (b < list.size) {
            val h = list[b]
            val sameHere = a < spots.size && spots[a] == h
            if (sameHere) {
                val read = reads[a]
                val stale = waiting[a] || (read != null && changed.any { intersects(it, read) })
                if (stale) {
                    patches[a]?.let { changed += intArrayOf(it.x0, it.y0, it.x1, it.y1) }
                    compute(h)
                } else {
                    newPatches += patches[a]
                    newReads += read
                    newWaiting += false
                    if (h.id in failed) fail += h.id
                }
                a++
                b++
                continue
            }
            val later = (a + 1 until spots.size).firstOrNull { spots[it] == h }
            if (later != null &&
                list.subList(b, list.size).none { it.id in spots.subList(a, later).map { s -> s.id } }
            ) {
                // The old spots a until later were deleted.
                for (i in a until later) patches[i]?.let { changed += intArrayOf(it.x0, it.y0, it.x1, it.y1) }
                a = later
                continue
            }
            // A new spot, or this one changed: the old one at a (same id) is replaced.
            if (a < spots.size && spots[a].id == h.id) {
                patches[a]?.let { changed += intArrayOf(it.x0, it.y0, it.x1, it.y1) }
                a++
            }
            compute(h)
            b++
        }
        for (i in a until spots.size) patches[i]?.let { changed += intArrayOf(it.x0, it.y0, it.x1, it.y1) }
        spots = list
        patches = newPatches
        reads = newReads
        waiting = newWaiting
        pending = pend
        failed = fail
        if (changed.isEmpty()) return null
        val out = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        for (r in changed) {
            out[0] = min(out[0], r[0])
            out[1] = min(out[1], r[1])
            out[2] = max(out[2], r[2])
            out[3] = max(out[3], r[3])
        }
        return out
    }

    /**
     * The healed pixels of the rectangle ([x], [y], [w] x [h]) inside the image into [out] (`w * h * channels`,
     * [channels] 3 or 4: alpha untouched), which holds [base]'s pixels on entry.
     */
    fun overlay(x: Int, y: Int, w: Int, h: Int, out: FloatArray, channels: Int = 3) {
        overlay(patches, patches.size, x, y, w, h, out, channels)
    }

    /** The healed image (every spot) over the rectangle x0, y0, x1, y1, edge-clamped (tests, the source pick). */
    fun layer(x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val r = readLayer(patches, patches.size, x0, y0, x1, y1)
        return r.rgb
    }

    /** A reader of the image with the spots before the [index]th rendered spot applied (the source pick). */
    fun layerReader(index: Int = patches.size): PixelReader = object : PixelReader {
        override val width: Int get() = this@HealEngine.width
        override val height: Int get() = this@HealEngine.height
        override val threadSafe: Boolean get() = false

        override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
            base.read(x, y, w, h, out)
            overlay(patches, min(index, patches.size), x, y, w, h, out, 3)
        }
    }

    // ------------------------------------------------------------------ per spot

    private class Result(val patch: HealPatch?, val reads: IntArray?, val waiting: Boolean, val failed: Boolean)

    private fun patch(h: Heal, before: List<HealPatch?>, onReady: () -> Unit): Result = when (h.type) {
        HealKind.HEAL -> heal(h, before, seamless = true)
        HealKind.CLONE -> heal(h, before, seamless = false)
        HealKind.RED_EYE -> redEye(h, before)
        HealKind.REMOVE -> removeSpot(h, before, onReady)
        HealKind.UNKNOWN -> Result(null, null, waiting = false, failed = false)
    }

    /** The source offset of [h] in this image's pixels (whole pixels: the copy never resamples). */
    private fun offset(h: Heal): Pair<Int, Int> = (h.dx * width).roundToInt() to (h.dy * height).roundToInt()

    /**
     * Heal (seamless) or clone. Heal: `d = ln(T + e) - ln(S + e)` on the ring around the shape (3 x 3 means of the
     * target T and the source S), its membrane `m` inside, then `H = (S + e) exp(m) - e`: the source's texture with the
     * target's light (multiplicative Poisson blending). Clone: `H = S`. Then `T + alpha opacity (H - T)`.
     */
    @Suppress("LongMethod")
    private fun heal(h: Heal, before: List<HealPatch?>, seamless: Boolean): Result {
        val fp = Footprint.of(h, width, height) ?: return Result(null, null, waiting = false, failed = false)
        val (ox, oy) = offset(h)
        val gx0 = fp.x0 - 1
        val gy0 = fp.y0 - 1
        val gw = fp.width + 2
        val gh = fp.height + 2
        val t = readLayer(before, before.size, gx0 - 1, gy0 - 1, gx0 + gw + 1, gy0 + gh + 1)
        val s = readLayer(before, before.size, gx0 - 1 + ox, gy0 - 1 + oy, gx0 + gw + 1 + ox, gy0 + gh + 1 + oy)
        val reads = union(
            intArrayOf(gx0 - 1, gy0 - 1, gx0 + gw + 1, gy0 + gh + 1),
            intArrayOf(gx0 - 1 + ox, gy0 - 1 + oy, gx0 + gw + 1 + ox, gy0 + gh + 1 + oy),
        )
        val m = if (seamless) membrane(fp, gx0, gy0, gw, gh, t) { x, y, c -> mean3(s, x + ox, y + oy, c) } else null
        val op = (h.opacity / 100f).coerceIn(0f, 1f)
        val out = FloatArray(fp.width * fp.height * 3)
        for (j in 0 until fp.height) {
            for (i in 0 until fp.width) {
                val x = fp.x0 + i
                val y = fp.y0 + j
                val ti = t.index(x, y)
                val o = (j * fp.width + i) * 3
                val a = fp.alpha[j * fp.width + i] * op
                val si = s.index(x + ox, y + oy)
                for (c in 0..2) {
                    val tv = t.rgb[ti + c]
                    val hv = if (m != null) {
                        (max(s.rgb[si + c], 0f) + EPS) * exp(m[((j + 1) * gw + (i + 1)) * 3 + c]) - EPS
                    } else {
                        s.rgb[si + c]
                    }
                    out[o + c] = blend(tv, hv, a)
                }
            }
        }
        return Result(HealPatch(fp.x0, fp.y0, fp.width, fp.height, out), reads, waiting = false, failed = false)
    }

    /**
     * The membrane of `ln(T) - ln(source(x, y, c))` on the ring of [fp] over the grid ([gx0], [gy0], [gw] x [gh]):
     * per grid cell 3 values (inside cells solved).
     */
    @Suppress("LongParameterList")
    private fun membrane(
        fp: Footprint,
        gx0: Int,
        gy0: Int,
        gw: Int,
        gh: Int,
        t: Region,
        source: (Int, Int, Int) -> Float,
    ): FloatArray {
        val state = ByteArray(gw * gh)
        val values = FloatArray(gw * gh * 3)
        for (j in 0 until gh) {
            for (i in 0 until gw) {
                val x = gx0 + i
                val y = gy0 + j
                val k = j * gw + i
                state[k] = when {
                    x < 0 || y < 0 || x >= width || y >= height -> Membrane.ABSENT
                    fp.at(x, y) > 0f -> Membrane.INSIDE
                    else -> Membrane.FIXED
                }
                if (state[k] == Membrane.FIXED) {
                    for (c in 0..2) values[k * 3 + c] = ln(mean3(t, x, y, c)) - ln(source(x, y, c))
                }
            }
        }
        Membrane.solve(gw, gh, state, values)
        return values
    }

    /** Red eye: the red pupil inside the circle, found by redness, desaturated and darkened (docs/adr/0023). */
    @Suppress("LongMethod")
    private fun redEye(h: Heal, before: List<HealPatch?>): Result {
        val fp = Footprint.of(h, width, height) ?: return Result(null, null, waiting = false, failed = false)
        val t = readLayer(before, before.size, fp.x0, fp.y0, fp.x1(), fp.y1())
        val n = fp.width * fp.height
        val lin = FloatArray(n * 3)
        val weight = FloatArray(n)
        for (k in 0 until n) {
            val x = fp.x0 + k % fp.width
            val y = fp.y0 + k / fp.width
            display.linear(t.rgb, t.index(x, y), lin, k * 3)
            val r = lin[k * 3]
            val g = lin[k * 3 + 1]
            val b = lin[k * 3 + 2]
            val sum = r + g + b
            val redness = if (sum > 0f) (r - max(g, b)) / sum else 0f
            weight[k] = MaskMath.sstep(RED_LOW, RED_HIGH, redness) * MaskMath.sstep(DARK_LOW, DARK_HIGH, sum)
        }
        // Smooth the pupil weight (radius 8 % of the spot's radius, at least a pixel) so it has no speckles.
        val radiusPx = h.radius * max(width, height)
        val blur = max(1, (radiusPx * BLUR_SHARE).roundToInt())
        boxBlur(weight, fp.width, fp.height, blur)
        boxBlur(weight, fp.width, fp.height, blur)
        val op = (h.opacity / 100f).coerceIn(0f, 1f)
        val darken = 1f - DARKEN_RANGE * (h.darken / 100f).coerceIn(0f, 1f)
        val out = FloatArray(n * 3)
        val fixed = FloatArray(3)
        for (k in 0 until n) {
            val x = fp.x0 + k % fp.width
            val y = fp.y0 + k / fp.width
            val ti = t.index(x, y)
            val w = (weight[k] * fp.alpha[k] * op).coerceIn(0f, 1f)
            if (w <= 0f) {
                for (c in 0..2) out[k * 3 + c] = t.rgb[ti + c]
                continue
            }
            val g = lin[k * 3 + 1]
            val b = lin[k * 3 + 2]
            val grey = 0.5f * (g + b) * darken
            for (c in 0..2) fixed[c] = lin[k * 3 + c] + w * (grey - lin[k * 3 + c])
            display.camera(fixed, 0, out, k * 3)
        }
        return Result(HealPatch(fp.x0, fp.y0, fp.width, fp.height, out), fp.rect, waiting = false, failed = false)
    }

    /**
     * Remove: the fill of the region (docs/adr/0023 "Remove"), copied from the photo through the fill's offsets (or
     * upsampled from its pixels), then blended into the edge like Heal (its membrane), then
     * `T + alpha opacity (H - T)`.
     */
    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    private fun removeSpot(h: Heal, before: List<HealPatch?>, onReady: () -> Unit): Result {
        val fp = Footprint.of(h, width, height) ?: return Result(null, null, waiting = false, failed = false)
        val original = remove.original ?: return Result(null, fp.rect, waiting = false, failed = true)
        val colour = remove.colour ?: colourInfo
        val spec = RemoveSpec.of(h, original.width, original.height)
            ?: return Result(null, fp.rect, waiting = false, failed = false)
        val identity = remove.identity() ?: return Result(null, fp.rect, waiting = false, failed = true)
        val key = RemoveSpec.key(identity, spec, h, h.method)
        val request = RemoveRequest(key, h.method, spec, original.threadSafe) {
            spec.read(h, original, colour, RemoveSpec.seed(key))
        }
        val ctx = spec.rectIn(width, height)
        val gx0 = fp.x0 - 1
        val gy0 = fp.y0 - 1
        val gw = fp.width + 2
        val gh = fp.height + 2
        val reads = union(ctx, intArrayOf(gx0 - 1, gy0 - 1, gx0 + gw + 1, gy0 + gh + 1))
        val fill = remove.source.fill(request, onReady)
            ?: return Result(null, reads, waiting = !remove.source.failed(key), failed = remove.source.failed(key))
        // The proposed pixels over the grid grown by one (the ring's 3 x 3 means).
        val px0 = gx0 - 1
        val py0 = gy0 - 1
        val pw = gw + 2
        val ph = gh + 2
        val proposed = propose(fill, px0, py0, pw, ph, before)
        val t = readLayer(before, before.size, px0, py0, px0 + pw, py0 + ph)
        val prop = Region.of(px0, py0, pw, ph, proposed)
        val m = membrane(fp, gx0, gy0, gw, gh, t) { x, y, c -> mean3(prop, x, y, c) }
        val op = (h.opacity / 100f).coerceIn(0f, 1f)
        val out = FloatArray(fp.width * fp.height * 3)
        for (j in 0 until fp.height) {
            for (i in 0 until fp.width) {
                val x = fp.x0 + i
                val y = fp.y0 + j
                val ti = t.index(x, y)
                val pi = prop.index(x, y)
                val o = (j * fp.width + i) * 3
                val a = fp.alpha[j * fp.width + i] * op
                for (c in 0..2) {
                    val tv = t.rgb[ti + c]
                    val hv = (max(prop.rgb[pi + c], 0f) + EPS) * exp(m[((j + 1) * gw + (i + 1)) * 3 + c]) - EPS
                    out[o + c] = blend(tv, hv, a)
                }
            }
        }
        return Result(HealPatch(fp.x0, fp.y0, fp.width, fp.height, out), reads, waiting = false, failed = false)
    }

    /**
     * The fill's pixels over (x0, y0, w, h) at this resolution: through the offsets (the 4 crop pixels around the
     * point, bilinear weights, each copying the pixel its offset points at; whole pixels, no resampling), else the
     * fill's colours (bilinear, decoded), else the image itself.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun propose(fill: RemoveFill, x0: Int, y0: Int, w: Int, h: Int, before: List<HealPatch?>): FloatArray {
        val spec = fill.spec
        val cw = spec.width
        val ch = spec.height
        val sx = spec.virtualWidth.toFloat() / width
        val sy = spec.virtualHeight.toFloat() / height
        val offsets = fill.output.offsets
        val n = w * h
        val qx = IntArray(n * 4)
        val qy = IntArray(n * 4)
        val qw = FloatArray(n * 4)
        var bx0 = Int.MAX_VALUE
        var by0 = Int.MAX_VALUE
        var bx1 = Int.MIN_VALUE
        var by1 = Int.MIN_VALUE
        val out = FloatArray(n * 3)
        val pixelOnly = BooleanArray(n)
        for (k in 0 until n) {
            val x = x0 + k % w
            val y = y0 + k / w
            val u = (x + 0.5f) * sx - 0.5f - spec.x0
            val v = (y + 0.5f) * sy - 0.5f - spec.y0
            val ix = floor(u).toInt()
            val iy = floor(v).toInt()
            val fx = u - ix
            val fy = v - iy
            var any = false
            for (corner in 0..3) {
                val cx = (ix + (corner and 1)).coerceIn(0, cw - 1)
                val cy = (iy + (corner shr 1)).coerceIn(0, ch - 1)
                val wt = (if (corner and 1 == 0) 1f - fx else fx) * (if (corner shr 1 == 0) 1f - fy else fy)
                val slot = k * 4 + corner
                qw[slot] = 0f
                if (offsets == null || wt <= 0f) continue
                val dx = offsets[(cy * cw + cx) * 2]
                val dy = offsets[(cy * cw + cx) * 2 + 1]
                if (dx.isNaN()) continue
                val tx = (x + (dx / sx).roundToInt()).coerceIn(0, width - 1)
                val ty = (y + (dy / sy).roundToInt()).coerceIn(0, height - 1)
                qx[slot] = tx
                qy[slot] = ty
                qw[slot] = wt
                any = true
                bx0 = min(bx0, tx)
                by0 = min(by0, ty)
                bx1 = max(bx1, tx + 1)
                by1 = max(by1, ty + 1)
            }
            if (!any) pixelOnly[k] = true
        }
        val src = if (bx1 > bx0) readLayer(before, before.size, bx0, by0, bx1, by1) else null
        val t = readLayer(before, before.size, x0, y0, x0 + w, y0 + h)
        val disp = fill.output.rgb
        val tmp = FloatArray(3)
        for (k in 0 until n) {
            val x = x0 + k % w
            val y = y0 + k / w
            if (!pixelOnly[k] && src != null) {
                var sum = 0f
                for (corner in 0..3) {
                    val slot = k * 4 + corner
                    val wt = qw[slot]
                    if (wt <= 0f) continue
                    val si = src.index(qx[slot], qy[slot])
                    for (c in 0..2) out[k * 3 + c] += wt * src.rgb[si + c]
                    sum += wt
                }
                for (c in 0..2) out[k * 3 + c] /= sum
                continue
            }
            val u = (x + 0.5f) * sx - 0.5f - spec.x0
            val v = (y + 0.5f) * sy - 0.5f - spec.y0
            val inside = u > -1f && v > -1f && u < cw && v < ch
            if (inside && offsets == null) {
                bilinear(disp, cw, ch, u, v, tmp)
                fill.colour.fromDisplay(tmp, 0, out, k * 3)
            } else {
                val ti = t.index(x, y)
                for (c in 0..2) out[k * 3 + c] = t.rgb[ti + c]
            }
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun Footprint.x1() = x0 + width

    private fun Footprint.y1() = y0 + height

    /** The image (base + the first [count] patches of [list]) over x0, y0, x1, y1, clamped to the image. */
    @Suppress("LongParameterList")
    private fun readLayer(list: List<HealPatch?>, count: Int, x0: Int, y0: Int, x1: Int, y1: Int): Region {
        val r = Region.read({ x, y, w, h, out -> base.read(x, y, w, h, out) }, width, height, x0, y0, x1, y1)
        overlay(list, count, r.x0, r.y0, r.width, r.height, r.rgb, 3)
        return r
    }

    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun overlay(list: List<HealPatch?>, count: Int, x: Int, y: Int, w: Int, h: Int, out: FloatArray, ch: Int) {
        for (i in 0 until count) {
            val p = list[i] ?: continue
            val ax = max(x, p.x0)
            val ay = max(y, p.y0)
            val bx = min(x + w, p.x1)
            val by = min(y + h, p.y1)
            if (ax >= bx || ay >= by) continue
            for (yy in ay until by) {
                var s = ((yy - p.y0) * p.width + (ax - p.x0)) * 3
                var d = ((yy - y) * w + (ax - x)) * ch
                for (xx in ax until bx) {
                    out[d] = p.rgb[s]
                    out[d + 1] = p.rgb[s + 1]
                    out[d + 2] = p.rgb[s + 2]
                    s += 3
                    d += ch
                }
            }
        }
    }

    /** `t + a (h - t)`, exactly [h] at full strength and [t] at none. */
    private fun blend(t: Float, h: Float, a: Float): Float = when {
        a >= 1f -> h
        a > 0f -> t + a * (h - t)
        else -> t
    }

    private fun mean3(r: Region, x: Int, y: Int, c: Int): Float {
        var s = 0f
        for (dy in -1..1) for (dx in -1..1) s += r.rgb[r.index(x + dx, y + dy) + c]
        return s / 9f
    }

    private fun ln(v: Float): Float = StrictMath.log((max(v, 0f) + EPS).toDouble()).toFloat()

    private fun exp(v: Float): Float = StrictMath.exp(v.toDouble()).toFloat()

    private fun intersects(a: IntArray, b: IntArray): Boolean = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    private fun union(a: IntArray, b: IntArray): IntArray =
        intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))

    private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(v.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (d in -r..r) {
                    val xx = x + d
                    if (xx in 0 until w) s += v[y * w + xx]
                }
                tmp[y * w + x] = s / (2 * r + 1)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (d in -r..r) {
                    val yy = y + d
                    if (yy in 0 until h) s += tmp[yy * w + x]
                }
                v[y * w + x] = s / (2 * r + 1)
            }
        }
    }

    private fun bilinear(img: FloatArray, w: Int, h: Int, u: Float, v: Float, out: FloatArray) {
        val x0 = floor(u).toInt()
        val y0 = floor(v).toInt()
        val fx = u - x0
        val fy = v - y0
        out.fill(0f)
        for (corner in 0..3) {
            val cx = (x0 + (corner and 1)).coerceIn(0, w - 1)
            val cy = (y0 + (corner shr 1)).coerceIn(0, h - 1)
            val wt = (if (corner and 1 == 0) 1f - fx else fx) * (if (corner shr 1 == 0) 1f - fy else fy)
            for (c in 0..2) out[c] += wt * img[(cy * w + cx) * 3 + c]
        }
    }

    companion object {
        /** Offset of the log domain (values are linear, white = 1). */
        const val EPS: Float = 1e-3f

        /** Redness `(r - max(g, b)) / (r + g + b)` (linear sRGB, as shot) where the pupil weight starts and is full. */
        const val RED_LOW: Float = 0.12f
        const val RED_HIGH: Float = 0.3f

        /** Darkest pixels the redness counts on (sum of linear sRGB): noise below. */
        const val DARK_LOW: Float = 0.002f
        const val DARK_HIGH: Float = 0.01f

        /** The pupil weight's blur radius as a share of the spot's radius. */
        const val BLUR_SHARE: Float = 0.08f

        /** Darken 100 % multiplies the desaturated pupil by `1 - DARKEN_RANGE`. */
        const val DARKEN_RANGE: Float = 0.8f
    }
}
