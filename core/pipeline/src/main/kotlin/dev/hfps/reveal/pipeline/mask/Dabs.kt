@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.StrokePressure
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The dabs of a brush component for an image of [imageWidth] x [imageHeight] pixels (docs/adr/0022, "Brush raster"), in
 * that image's pixels: [DAB_FLOATS] floats per dab, in painting order: centre x, centre y, radius, inner radius
 * fraction (`1 - feather`), flow (0..1, pressure included), erase (1) or paint (0), density (0..1, the most coverage a
 * painting dab moves towards; an erasing dab removes at most that share), auto-mask (1: the dab only covers pixels of
 * a colour close to the one under its centre, [AUTO_NEAR] / [AUTO_FAR]). The CPU reference and the GPU evaluate exactly
 * these numbers.
 */
class Dabs(val imageWidth: Int, val imageHeight: Int, val data: FloatArray) {
    /** Number of dabs. */
    val size: Int get() = data.size / DAB_FLOATS

    /** True when some dab is an auto-mask dab (the layer then reads the pre-local image). */
    val autoMask: Boolean by lazy { (0 until size).any { data[it * DAB_FLOATS + 7] != 0f } }

    /**
     * Index of the first dab that differs from [other] (both for the same image size), i.e. the length of the common
     * prefix: appending points to the last stroke keeps the earlier dabs.
     */
    @Suppress("ReturnCount")
    fun commonPrefix(other: Dabs): Int {
        if (other.imageWidth != imageWidth || other.imageHeight != imageHeight) return 0
        val n = min(size, other.size)
        for (k in 0 until n) {
            for (j in 0 until DAB_FLOATS) {
                if (data[k * DAB_FLOATS + j] != other.data[k * DAB_FLOATS + j]) return k
            }
        }
        return n
    }

    /** The pixel box (x0, y0, x1, y1), inclusive, whose centres a dab [k] can reach. */
    fun box(k: Int, out: IntArray) {
        val o = k * DAB_FLOATS
        val cx = data[o]
        val cy = data[o + 1]
        val r = data[o + 2]
        out[0] = ceil(cx - r - HALF).toInt()
        out[1] = ceil(cy - r - HALF).toInt()
        out[2] = floor(cx + r - HALF).toInt()
        out[3] = floor(cy + r - HALF).toInt()
    }

    companion object {
        const val DAB_FLOATS: Int = 8

        /**
         * Auto-mask (docs/adr/0022 "Brush auto-mask"): a pixel whose pre-local OkLab colour lies within [AUTO_NEAR] of
         * the colour under the dab's centre (the range masks' distance `sqrt(0.25 dL^2 + da^2 + db^2)`) takes the
         * whole dab, beyond [AUTO_FAR] none, smoothstep between.
         */
        const val AUTO_NEAR: Float = 0.03f
        const val AUTO_FAR: Float = 0.09f

        /** Dab spacing along the stroke as a fraction of the dab's diameter. */
        const val SPACING: Float = 0.15f

        /** Smallest spacing, in rendered pixels. */
        const val MIN_SPACING_PX: Float = 0.5f

        /** Pressure response `k(p) = PRESSURE_FLOOR + (1 - PRESSURE_FLOOR) p` of a dab's radius and flow. */
        const val PRESSURE_FLOOR: Float = 0.25f

        private const val HALF = 0.5f
        private const val PERCENT = 100f

        /** `k(p)`: how pressure scales a dab's radius and flow. */
        fun pressure(p: Float): Float = PRESSURE_FLOOR + (1f - PRESSURE_FLOOR) * p.coerceIn(0f, 1f)

        /**
         * The dabs of [strokes] in order: one dab at each stroke's first point, then one every [SPACING] times the
         * diameter (at least [MIN_SPACING_PX]) of arc length, the pressure interpolated along each segment. Placement
         * only depends on the points before a dab, so appending points only appends dabs.
         */
        @Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")
        fun of(strokes: List<BrushStroke>, imageWidth: Int, imageHeight: Int): Dabs {
            val out = FloatArrayBuilder()
            val long = max(imageWidth, imageHeight).toFloat()
            for (stroke in strokes) {
                val points = stroke.decoded
                if (points.isEmpty()) continue
                val radius0 = max(stroke.size, 0f) * HALF * long
                if (radius0 <= 0f) continue
                val inner = 1f - (stroke.feather / PERCENT).coerceIn(0f, 1f)
                val flow0 = (stroke.flow / PERCENT).coerceIn(0f, 1f)
                val erase = if (stroke.erase) 1f else 0f
                val density = (stroke.density / PERCENT).coerceIn(0f, 1f)
                val auto = if (stroke.autoMask) 1f else 0f
                val sizePressure = stroke.pressure != StrokePressure.FLOW
                val flowPressure = stroke.pressure != StrokePressure.SIZE
                fun radius(p: Float): Float = if (sizePressure) radius0 * pressure(p) else radius0
                fun dab(x: Float, y: Float, p: Float) {
                    val k = pressure(p)
                    out.add(x, y, radius(p), inner, if (flowPressure) flow0 * k else flow0, erase, density, auto)
                }
                fun spacing(p: Float): Float = max(SPACING * 2f * radius(p), MIN_SPACING_PX)
                var ax = points[0].x * imageWidth
                var ay = points[0].y * imageHeight
                var ap = points[0].pressure
                dab(ax, ay, ap)
                // Arc length still to go before the next dab.
                var next = spacing(ap)
                for (i in 1 until points.size) {
                    val bx = points[i].x * imageWidth
                    val by = points[i].y * imageHeight
                    val bp = points[i].pressure
                    val dx = bx - ax
                    val dy = by - ay
                    val length = sqrt(dx * dx + dy * dy)
                    var done = 0f
                    while (length - done >= next) {
                        done += next
                        val t = done / length
                        val p = ap + (bp - ap) * t
                        dab(ax + dx * t, ay + dy * t, p)
                        next = spacing(p)
                    }
                    next -= length - done
                    ax = bx
                    ay = by
                    ap = bp
                }
            }
            return Dabs(imageWidth, imageHeight, out.toArray())
        }
    }
}

/**
 * Bins of [BIN] x [BIN] pixels over a region of an image (origin [x], [y], [width] x [height]) with the indices of the
 * dabs that reach each bin, in dab order: what the GPU raster pass reads (`MaskShaders.BRUSH_FRAG`).
 *
 * @property offsets per bin (row-major) its first entry in [indices]; [counts] its number of entries.
 */
class DabBins(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val binsX: Int,
    val binsY: Int,
    val offsets: IntArray,
    val counts: IntArray,
    val indices: IntArray,
) {
    companion object {
        const val BIN: Int = 64

        /** Bins every dab of [dabs] into the region; dabs outside it are left out. */
        @Suppress("LongParameterList")
        fun of(dabs: Dabs, x: Int, y: Int, width: Int, height: Int): DabBins {
            val bx = (width + BIN - 1) / BIN
            val by = (height + BIN - 1) / BIN
            val lists = Array(bx * by) { IntArrayBuilder() }
            val box = IntArray(4)
            for (k in 0 until dabs.size) {
                dabs.box(k, box)
                val x0 = max(box[0] - x, 0)
                val y0 = max(box[1] - y, 0)
                val x1 = min(box[2] - x, width - 1)
                val y1 = min(box[3] - y, height - 1)
                if (x0 > x1 || y0 > y1) continue
                for (j in y0 / BIN..y1 / BIN) for (i in x0 / BIN..x1 / BIN) lists[j * bx + i].add(k)
            }
            val counts = IntArray(lists.size) { lists[it].size }
            val offsets = IntArray(lists.size)
            var total = 0
            for (i in lists.indices) {
                offsets[i] = total
                total += counts[i]
            }
            val indices = IntArray(total)
            for (i in lists.indices) lists[i].copyInto(indices, offsets[i])
            return DabBins(x, y, width, height, bx, by, offsets, counts, indices)
        }
    }
}

internal class FloatArrayBuilder {
    private var data = FloatArray(INITIAL)
    private var n = 0

    fun add(vararg values: Float) {
        if (n + values.size > data.size) data = data.copyOf(max(data.size * 2, n + values.size))
        values.copyInto(data, n)
        n += values.size
    }

    fun toArray(): FloatArray = data.copyOf(n)

    private companion object {
        const val INITIAL = 1024
    }
}

internal class IntArrayBuilder {
    private var data = IntArray(INITIAL)
    var size: Int = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun copyInto(dest: IntArray, offset: Int) {
        data.copyInto(dest, offset, 0, size)
    }

    private companion object {
        const val INITIAL = 8
    }
}
