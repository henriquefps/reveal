@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Heal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The automatic source of a Heal or Clone spot (docs/adr/0023, "Source pick"): a deterministic patch search on the
 * image the spot repairs (the editor's proxy with the spots before it applied).
 *
 * Score of an offset `o` (lower is better), in the display encoding of [RemoveColour] (sRGB-like, so differences are
 * perceptual): the mean squared difference between the ring around the target and the ring around `target + o`
 * (the source's surroundings should look like the target's), plus how much more the source's inside varies than the
 * target's ring (a source with an edge or another blemish in it is penalised). Candidates: 24 directions x 7
 * distances from the smallest that does not overlap the shape ([MAX_OVERLAP]) up to 4.5 times it, inside the image;
 * the best is refined by a pattern search down to one pixel. Ties keep the first candidate.
 */
object SourcePicker {
    /** Largest share of the shape the source may overlap. */
    const val MAX_OVERLAP: Float = 0.05f
    private const val DIRECTIONS = 24
    private val DISTANCES = floatArrayOf(1f, 1.25f, 1.6f, 2f, 2.6f, 3.4f, 4.5f)
    private const val MAX_SAMPLES = 256

    /**
     * The source offset of [heal] as fractions of the width and height (`dx`, `dy`), searched on [layer] (the image the
     * spot repairs) with [colour]'s matrices; null when no candidate fits in the image.
     */
    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
    fun pick(heal: Heal, layer: PixelReader, colour: PipelineInput): Pair<Float, Float>? {
        val w = layer.width
        val h = layer.height
        val fp = Footprint.of(heal, w, h, feather = 0f) ?: return null
        val radius = max(1f, heal.radius * max(w, h))
        val ringWidth = max(2, (radius * 0.5f).roundToInt())
        // Shape and ring, as offsets from the box's corner.
        val shape = BooleanArray(fp.width * fp.height) { fp.alpha[it] > 0f }
        val gw = fp.width + 2 * ringWidth
        val gh = fp.height + 2 * ringWidth
        val grown = FloatArray(gw * gh)
        for (j in 0 until fp.height) {
            for (i in 0 until fp.width) {
                if (shape[j * fp.width + i]) grown[(j + ringWidth) * gw + i + ringWidth] = 1f
            }
        }
        val dilated = RemoveSpec.dilate(grown, gw, gh, ringWidth)
        val ring = ArrayList<Int>()
        val inside = ArrayList<Int>()
        for (j in 0 until gh) {
            for (i in 0 until gw) {
                val x = fp.x0 - ringWidth + i
                val y = fp.y0 - ringWidth + j
                if (x !in 0 until w || y !in 0 until h) continue
                val inShape = grown[j * gw + i] > 0f
                if (inShape) {
                    inside += pack(x, y)
                } else if (dilated[j * gw + i] > 0f) {
                    ring += pack(x, y)
                }
            }
        }
        if (ring.isEmpty() || inside.isEmpty()) return null
        val ringS = subsample(ring)
        val insideS = subsample(inside)
        // The smallest distance that does not overlap the shape, along each direction.
        val span = max(fp.width, fp.height).toFloat()
        val reach = (span * DISTANCES.last() * 2f).roundToInt() + ringWidth + 2
        val wx0 = max(0, fp.x0 - reach)
        val wy0 = max(0, fp.y0 - reach)
        val wx1 = min(w, fp.x0 + fp.width + reach)
        val wy1 = min(h, fp.y0 + fp.height + reach)
        val window = Region.read({ x, y, ww, hh, out -> layer.read(x, y, ww, hh, out) }, w, h, wx0, wy0, wx1, wy1)
        val disp = encode(window, colour)
        fun value(x: Int, y: Int, c: Int): Float = disp[window.index(x, y) + c]
        val target = FloatArray(ringS.size * 3)
        val targetMean = FloatArray(3)
        for ((k, p) in ringS.withIndex()) {
            for (c in 0..2) {
                target[k * 3 + c] = value(px(p), py(p), c)
                targetMean[c] += target[k * 3 + c] / ringS.size
            }
        }
        var targetVar = 0f
        for (k in ringS.indices) {
            for (c in 0..2) {
                val d = target[k * 3 + c] - targetMean[c]
                targetVar += d * d / ringS.size
            }
        }
        fun fits(ox: Int, oy: Int): Boolean = fp.x0 + ox - ringWidth >= 0 && fp.y0 + oy - ringWidth >= 0 &&
            fp.x0 + fp.width + ox + ringWidth <= w && fp.y0 + fp.height + oy + ringWidth <= h &&
            fp.x0 + ox - ringWidth >= wx0 && fp.y0 + oy - ringWidth >= wy0 &&
            fp.x0 + fp.width + ox + ringWidth <= wx1 && fp.y0 + fp.height + oy + ringWidth <= wy1
        fun overlap(ox: Int, oy: Int): Float {
            var n = 0
            var hit = 0
            for (j in 0 until fp.height) {
                for (i in 0 until fp.width) {
                    if (!shape[j * fp.width + i]) continue
                    n++
                    val si = i + ox
                    val sj = j + oy
                    if (si in 0 until fp.width && sj in 0 until fp.height && shape[sj * fp.width + si]) hit++
                }
            }
            return if (n == 0) 0f else hit.toFloat() / n
        }
        fun score(ox: Int, oy: Int): Float {
            var ssd = 0f
            for ((k, p) in ringS.withIndex()) {
                for (c in 0..2) {
                    val d = value(px(p) + ox, py(p) + oy, c) - target[k * 3 + c]
                    ssd += d * d
                }
            }
            ssd /= ringS.size
            val mean = FloatArray(3)
            for (p in insideS) for (c in 0..2) mean[c] += value(px(p) + ox, py(p) + oy, c) / insideS.size
            var variance = 0f
            for (p in insideS) {
                for (c in 0..2) {
                    val d = value(px(p) + ox, py(p) + oy, c) - mean[c]
                    variance += d * d / insideS.size
                }
            }
            return ssd + max(0f, variance - targetVar)
        }
        var best: Pair<Int, Int>? = null
        var bestScore = Float.MAX_VALUE
        for (a in 0 until DIRECTIONS) {
            val angle = 2.0 * PI * a / DIRECTIONS
            val ux = cos(angle).toFloat()
            val uy = sin(angle).toFloat()
            // The first distance along this direction that leaves the shape.
            var start = 1f
            while (start < span * 2f) {
                if (overlap((ux * start).roundToInt(), (uy * start).roundToInt()) <= MAX_OVERLAP) break
                start += 1f
            }
            start = max(start, radius * 0.5f) + ringWidth * 0.5f
            for (k in DISTANCES) {
                val ox = (ux * start * k).roundToInt()
                val oy = (uy * start * k).roundToInt()
                if (!fits(ox, oy) || overlap(ox, oy) > MAX_OVERLAP) continue
                val s = score(ox, oy)
                if (s < bestScore) {
                    bestScore = s
                    best = ox to oy
                }
            }
        }
        var (bx, by) = best ?: return null
        var step = max(1, (radius / 4f).roundToInt())
        while (step >= 1) {
            var moved = false
            for ((sx, sy) in STEPS) {
                val ox = bx + sx * step
                val oy = by + sy * step
                if (!fits(ox, oy) || overlap(ox, oy) > MAX_OVERLAP) continue
                val s = score(ox, oy)
                if (s < bestScore) {
                    bestScore = s
                    bx = ox
                    by = oy
                    moved = true
                }
            }
            if (!moved) step /= 2
        }
        return bx.toFloat() / w to by.toFloat() / h
    }

    private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1, 1 to -1, -1 to 1)

    private fun encode(region: Region, colour: PipelineInput): FloatArray {
        val base = RemoveColour.of(colour)
        val n = region.width * region.height
        val lum = FloatArray(n) { base.luminance(region.rgb, it * 3) }
        val enc = base.withGain(RemoveColour.gainFor(lum))
        val out = FloatArray(n * 3)
        for (i in 0 until n) enc.toDisplay(region.rgb, i * 3, out, i * 3)
        return out
    }

    private fun subsample(points: List<Int>): IntArray {
        if (points.size <= MAX_SAMPLES) return points.toIntArray()
        val step = points.size.toFloat() / MAX_SAMPLES
        return IntArray(MAX_SAMPLES) { points[(it * step).toInt()] }
    }

    private fun pack(x: Int, y: Int): Int = (y shl 16) or x

    private fun px(p: Int): Int = p and 0xffff

    private fun py(p: Int): Int = p ushr 16
}
