package dev.hfps.reveal.pipeline.mask

import dev.hfps.reveal.pipeline.recipe.StrokePoint
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The compact text form of a brush stroke's points (docs/adr/0022, "Brush strokes are vectors, compact"): `x` and `y`
 * quantised to 1/[POSITION_STEPS] of the image's width and height, pressure to 1/[PRESSURE_STEPS]; the first point
 * absolute and every later one as the difference to the previous, each number zigzag-mapped and written as a LEB128
 * varint, the bytes as base64url without padding. About 3 bytes (4 characters) a point for a hand-drawn stroke.
 */
object StrokeCodec {
    /** Position steps per image width (or height). */
    const val POSITION_STEPS: Int = 32768

    /** Pressure steps (0..255). */
    const val PRESSURE_STEPS: Int = 255

    /** Simplification tolerance as a fraction of the dab radius. */
    const val SIMPLIFY_TOLERANCE: Float = 0.02f

    /** A point is kept when its pressure leaves the one interpolated from its neighbours by more than this. */
    const val PRESSURE_TOLERANCE: Float = 1f / 32f

    private const val VARINT_BITS = 7
    private const val VARINT_MORE = 0x80
    private const val VARINT_MASK = 0x7f
    private const val VALUES = 3

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** The quantised point of [p] (what [decode] gives back). */
    fun quantise(p: StrokePoint): StrokePoint = StrokePoint(
        p.x.toQuantised(POSITION_STEPS).toFloat() / POSITION_STEPS,
        p.y.toQuantised(POSITION_STEPS).toFloat() / POSITION_STEPS,
        p.pressure.coerceIn(0f, 1f).toQuantised(PRESSURE_STEPS).toFloat() / PRESSURE_STEPS,
    )

    /** [points] as text. */
    fun encode(points: List<StrokePoint>): String {
        val bytes = java.io.ByteArrayOutputStream(points.size * VALUES)
        var px = 0
        var py = 0
        var pp = 0
        for (p in points) {
            val x = p.x.toQuantised(POSITION_STEPS)
            val y = p.y.toQuantised(POSITION_STEPS)
            val q = p.pressure.coerceIn(0f, 1f).toQuantised(PRESSURE_STEPS)
            varint(bytes, zigzag(x - px))
            varint(bytes, zigzag(y - py))
            varint(bytes, zigzag(q - pp))
            px = x
            py = y
            pp = q
        }
        return encoder.encodeToString(bytes.toByteArray())
    }

    /** The points of [text]; an empty or malformed text gives the points read before the problem. */
    fun decode(text: String): List<StrokePoint> {
        if (text.isEmpty()) return emptyList()
        val bytes = runCatching { decoder.decode(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<StrokePoint>(bytes.size / VALUES)
        var i = 0
        var x = 0
        var y = 0
        var p = 0
        val v = IntArray(VALUES)
        while (i < bytes.size) {
            for (k in 0 until VALUES) {
                var shift = 0
                var value = 0
                while (true) {
                    if (i >= bytes.size) return out
                    val b = bytes[i++].toInt() and 0xff
                    value = value or ((b and VARINT_MASK) shl shift)
                    if (b and VARINT_MORE == 0) break
                    shift += VARINT_BITS
                    if (shift > Int.SIZE_BITS) return out
                }
                v[k] = unzigzag(value)
            }
            x += v[0]
            y += v[1]
            p += v[2]
            out += StrokePoint(
                x.toFloat() / POSITION_STEPS,
                y.toFloat() / POSITION_STEPS,
                (p.toFloat() / PRESSURE_STEPS).coerceIn(0f, 1f),
            )
        }
        return out
    }

    /**
     * [points] simplified (Ramer-Douglas-Peucker in the isotropic plane of an image of width/height [aspect], tolerance
     * [SIMPLIFY_TOLERANCE] times [radius], a long-edge fraction); a point whose pressure differs from the one
     * interpolated between the kept neighbours by more than [PRESSURE_TOLERANCE] is kept too. The ends are kept.
     */
    fun simplify(points: List<StrokePoint>, radius: Float, aspect: Float = 1f): List<StrokePoint> {
        if (points.size <= 2) return points
        val sx = if (aspect >= 1f) 1f else aspect
        val sy = if (aspect >= 1f) 1f / aspect else 1f
        val tolerance = max(radius * SIMPLIFY_TOLERANCE, 1e-6f)
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val pa = points[a]
            val pb = points[b]
            val ax = pa.x * sx
            val ay = pa.y * sy
            val dx = pb.x * sx - ax
            val dy = pb.y * sy - ay
            val len2 = dx * dx + dy * dy
            var worst = -1f
            var index = -1
            for (i in a + 1 until b) {
                val p = points[i]
                val px = p.x * sx - ax
                val py = p.y * sy - ay
                val t = if (len2 > 0f) ((px * dx + py * dy) / len2).coerceIn(0f, 1f) else 0f
                val ex = px - t * dx
                val ey = py - t * dy
                val distance = sqrt(ex * ex + ey * ey)
                val pressure = abs(p.pressure - (pa.pressure + (pb.pressure - pa.pressure) * t))
                val score = max(distance / tolerance, pressure / PRESSURE_TOLERANCE)
                if (score > worst) {
                    worst = score
                    index = i
                }
            }
            if (worst > 1f) {
                keep[index] = true
                stack.addLast(a to index)
                stack.addLast(index to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun Float.toQuantised(steps: Int): Int = (this * steps).roundToInt()

    private fun zigzag(v: Int): Int = (v shl 1) xor (v shr (Int.SIZE_BITS - 1))

    private fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

    private fun varint(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and VARINT_MASK.inv() != 0) {
            out.write((v and VARINT_MASK) or VARINT_MORE)
            v = v ushr VARINT_BITS
        }
        out.write(v)
    }
}
