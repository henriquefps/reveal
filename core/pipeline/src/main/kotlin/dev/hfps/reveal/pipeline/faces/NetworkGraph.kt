// Numeric kernels: the loops are the maths, written out for speed without allocation.
@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "DestructuringDeclarationWithTooManyEntries",
)

package dev.hfps.reveal.pipeline.faces

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A small feed-forward convolutional network, executed in pure Kotlin (docs/adr/0010, "Faces"): the face detector's
 * graph converted by `scripts/faces/convert_blazeface.py` from its TensorFlow Lite file. Pure Kotlin on purpose: the
 * same code and the same float operations on the JVM (tests, the calibration on the owner's photos) and on every
 * device, no native runtime in the APK, and no network. Activations are NHWC float32; the float16 weights are widened
 * once at load. Only the operators this model uses are implemented, each exactly as TensorFlow Lite's reference
 * kernels define them (SAME padding, half-pixel bilinear resize).
 *
 * Thread-safe: [run] keeps its activations local, so several tiles can run at once.
 */
class NetworkGraph private constructor(
    private val shapes: Array<IntArray>,
    private val constants: Array<FloatArray?>,
    private val ints: Array<IntArray?>,
    private val ops: List<Op>,
    private val input: Int,
    private val outputs: IntArray,
) {
    private class Op(val code: Int, val inputs: IntArray, val outputs: IntArray, val options: IntArray)

    /** Index of the last op reading each tensor (graph outputs are never dropped). */
    private val lastUse = IntArray(shapes.size) { -1 }.also { last ->
        ops.forEachIndexed { i, op -> for (t in op.inputs) last[t] = i }
        for (t in outputs) last[t] = Int.MAX_VALUE
    }

    /** The input's shape (`[1, height, width, channels]`). */
    val inputShape: IntArray get() = shapes[input].copyOf()

    /** Runs the network on [data] (NHWC, [inputShape]); returns the graph's outputs in order. */
    fun run(data: FloatArray): List<FloatArray> {
        require(data.size == shapes[input].fold(1, Int::times)) { "input has the wrong size" }
        val values = arrayOfNulls<FloatArray>(shapes.size)
        values[input] = data
        for ((index, op) in ops.withIndex()) {
            val out = op.outputs[0]
            fun v(i: Int) = values[op.inputs[i]] ?: checkNotNull(constants[op.inputs[i]]) { "tensor not computed" }
            values[out] = when (op.code) {
                CONV -> conv(v(0), shapes[op.inputs[0]], v(1), shapes[op.inputs[1]], v(2), shapes[out], op.options)

                DEPTHWISE -> depthwise(
                    v(0),
                    shapes[op.inputs[0]],
                    v(1),
                    shapes[op.inputs[1]],
                    v(2),
                    shapes[out],
                    op.options,
                )

                MAX_POOL -> maxPool(v(0), shapes[op.inputs[0]], shapes[out], op.options)

                ADD -> add(v(0), v(1))

                RELU -> FloatArray(v(0).size) { max(0f, v(0)[it]) }

                PAD -> pad(v(0), shapes[op.inputs[0]], checkNotNull(ints[op.inputs[1]]), shapes[out])

                RESIZE_BILINEAR -> resize(v(0), shapes[op.inputs[0]], shapes[out], op.options)

                RESHAPE -> v(0).copyOf()

                else -> error("unsupported operator ${op.code}")
            }
            // Drop what no later op reads: the peak stays at a few MB per run.
            for (t in op.inputs) if (lastUse[t] == index && t != input) values[t] = null
        }
        return outputs.map { checkNotNull(values[it]) }
    }

    private fun conv(
        x: FloatArray,
        xs: IntArray,
        w: FloatArray,
        ws: IntArray,
        b: FloatArray,
        os: IntArray,
        o: IntArray,
    ): FloatArray {
        val (ih, iw, ic) = Triple(xs[1], xs[2], xs[3])
        val (oc, kh, kw) = Triple(ws[0], ws[1], ws[2])
        val (oh, ow) = os[1] to os[2]
        val sw = o[1]
        val sh = o[2]
        val padTop = padBefore(o[0], ih, oh, sh, kh)
        val padLeft = padBefore(o[0], iw, ow, sw, kw)
        val out = FloatArray(oh * ow * oc)
        if (kh == 1 && kw == 1 && sh == 1 && sw == 1) {
            // Pointwise: the bulk of the network's work.
            for (p in 0 until oh * ow) {
                val xi = p * ic
                val oi = p * oc
                for (c in 0 until oc) {
                    var acc = b[c]
                    val wi = c * ic
                    for (i in 0 until ic) acc += x[xi + i] * w[wi + i]
                    out[oi + c] = acc
                }
            }
            return out
        }
        for (y in 0 until oh) {
            for (xx in 0 until ow) {
                val oi = (y * ow + xx) * oc
                for (c in 0 until oc) {
                    var acc = b[c]
                    for (ky in 0 until kh) {
                        val sy = y * sh - padTop + ky
                        if (sy < 0 || sy >= ih) continue
                        for (kx in 0 until kw) {
                            val sx = xx * sw - padLeft + kx
                            if (sx < 0 || sx >= iw) continue
                            val xi = (sy * iw + sx) * ic
                            val wi = ((c * kh + ky) * kw + kx) * ic
                            for (i in 0 until ic) acc += x[xi + i] * w[wi + i]
                        }
                    }
                    out[oi + c] = acc
                }
            }
        }
        return out
    }

    private fun depthwise(
        x: FloatArray,
        xs: IntArray,
        w: FloatArray,
        ws: IntArray,
        b: FloatArray,
        os: IntArray,
        o: IntArray,
    ): FloatArray {
        val (ih, iw, ch) = Triple(xs[1], xs[2], xs[3])
        val (kh, kw) = ws[1] to ws[2]
        val (oh, ow) = os[1] to os[2]
        val sw = o[1]
        val sh = o[2]
        val padTop = padBefore(o[0], ih, oh, sh, kh)
        val padLeft = padBefore(o[0], iw, ow, sw, kw)
        val out = FloatArray(oh * ow * ch)
        for (y in 0 until oh) {
            for (xx in 0 until ow) {
                val oi = (y * ow + xx) * ch
                System.arraycopy(b, 0, out, oi, ch)
                for (ky in 0 until kh) {
                    val sy = y * sh - padTop + ky
                    if (sy < 0 || sy >= ih) continue
                    for (kx in 0 until kw) {
                        val sx = xx * sw - padLeft + kx
                        if (sx < 0 || sx >= iw) continue
                        val xi = (sy * iw + sx) * ch
                        val wi = (ky * kw + kx) * ch
                        for (c in 0 until ch) out[oi + c] += x[xi + c] * w[wi + c]
                    }
                }
            }
        }
        return out
    }

    private fun maxPool(x: FloatArray, xs: IntArray, os: IntArray, o: IntArray): FloatArray {
        val (ih, iw, ch) = Triple(xs[1], xs[2], xs[3])
        val (oh, ow) = os[1] to os[2]
        val (sw, sh, fw, fh) = listOf(o[1], o[2], o[3], o[4])
        val padTop = padBefore(o[0], ih, oh, sh, fh)
        val padLeft = padBefore(o[0], iw, ow, sw, fw)
        val out = FloatArray(oh * ow * ch) { Float.NEGATIVE_INFINITY }
        for (y in 0 until oh) {
            for (xx in 0 until ow) {
                val oi = (y * ow + xx) * ch
                for (ky in 0 until fh) {
                    val sy = y * sh - padTop + ky
                    if (sy < 0 || sy >= ih) continue
                    for (kx in 0 until fw) {
                        val sx = xx * sw - padLeft + kx
                        if (sx < 0 || sx >= iw) continue
                        val xi = (sy * iw + sx) * ch
                        for (c in 0 until ch) out[oi + c] = max(out[oi + c], x[xi + c])
                    }
                }
            }
        }
        return out
    }

    private fun add(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == b.size) { "ADD needs equal shapes" }
        return FloatArray(a.size) { a[it] + b[it] }
    }

    private fun pad(x: FloatArray, xs: IntArray, p: IntArray, os: IntArray): FloatArray {
        val out = FloatArray(os.fold(1, Int::times))
        val (ih, iw, ic) = Triple(xs[1], xs[2], xs[3])
        val (ow, oc) = os[2] to os[3]
        for (y in 0 until ih) {
            for (xx in 0 until iw) {
                val src = (y * iw + xx) * ic
                val dst = ((y + p[2]) * ow + xx + p[4]) * oc + p[6]
                System.arraycopy(x, src, out, dst, ic)
            }
        }
        return out
    }

    private fun resize(x: FloatArray, xs: IntArray, os: IntArray, o: IntArray): FloatArray {
        require(o[0] == 0 && o[1] == 1) { "only half-pixel-centre bilinear resizing is implemented" }
        val (ih, iw, ch) = Triple(xs[1], xs[2], xs[3])
        val (oh, ow) = os[1] to os[2]
        val out = FloatArray(oh * ow * ch)
        val scaleY = ih.toFloat() / oh
        val scaleX = iw.toFloat() / ow
        for (y in 0 until oh) {
            val fy = (y + 0.5f) * scaleY - 0.5f
            val y0 = max(floor(fy).toInt(), 0)
            val y1 = min(ceil(fy).toInt(), ih - 1)
            val dy = fy - floor(fy)
            for (xx in 0 until ow) {
                val fx = (xx + 0.5f) * scaleX - 0.5f
                val x0 = max(floor(fx).toInt(), 0)
                val x1 = min(ceil(fx).toInt(), iw - 1)
                val dx = fx - floor(fx)
                val oi = (y * ow + xx) * ch
                val a = (y0 * iw + x0) * ch
                val bb = (y0 * iw + x1) * ch
                val c0 = (y1 * iw + x0) * ch
                val d = (y1 * iw + x1) * ch
                for (c in 0 until ch) {
                    out[oi + c] = x[a + c] * (1 - dy) * (1 - dx) + x[bb + c] * (1 - dy) * dx +
                        x[c0 + c] * dy * (1 - dx) + x[d + c] * dy * dx
                }
            }
        }
        return out
    }

    companion object {
        private const val CONV = 1
        private const val DEPTHWISE = 2
        private const val MAX_POOL = 3
        private const val ADD = 4
        private const val RELU = 5
        private const val PAD = 6
        private const val RESIZE_BILINEAR = 7
        private const val RESHAPE = 8
        private const val SAME = 0
        private const val MAGIC = 0x4E4E5652 // "RVNN" little-endian
        private const val VERSION = 1
        private const val KIND_HALF = 1
        private const val KIND_INT = 2

        /** TensorFlow's SAME padding before the first row/column (VALID: none). */
        private fun padBefore(padding: Int, input: Int, output: Int, stride: Int, kernel: Int): Int =
            if (padding == SAME) max((output - 1) * stride + kernel - input, 0) / 2 else 0

        /** Reads a gzipped graph written by `scripts/faces/convert_blazeface.py`. */
        fun read(stream: InputStream): NetworkGraph = DataInputStream(GZIPInputStream(stream).buffered()).use { s ->
            fun int() = Integer.reverseBytes(s.readInt())
            fun ints(n: Int) = IntArray(n) { int() }
            check(int() == MAGIC && int() == VERSION) { "not a Reveal network graph" }
            val count = int()
            val shapes = arrayOfNulls<IntArray>(count)
            val constants = arrayOfNulls<FloatArray>(count)
            val intData = arrayOfNulls<IntArray>(count)
            for (t in 0 until count) {
                shapes[t] = ints(int())
                val kind = int()
                val n = int()
                when (kind) {
                    KIND_HALF -> constants[t] = FloatArray(n) {
                        halfToFloat(java.lang.Short.reverseBytes(s.readShort()).toInt() and 0xFFFF)
                    }

                    KIND_INT -> intData[t] = ints(n)
                }
            }
            val ops = List(int()) {
                val code = int()
                val inputs = ints(int())
                val outputs = ints(int())
                Op(code, inputs, outputs, ints(int()))
            }
            val input = int()
            val outputs = ints(int())
            NetworkGraph(shapes.requireNoNulls(), constants, intData, ops, input, outputs)
        }

        /** The float an IEEE 754 binary16 bit pattern stands for (exact, subnormals included). */
        fun halfToFloat(bits: Int): Float {
            val sign = (bits and 0x8000) shl 16
            val exponent = (bits ushr 10) and 0x1F
            val mantissa = bits and 0x3FF
            return when {
                exponent == 0 -> {
                    val v = mantissa * HALF_SUBNORMAL
                    if (sign != 0) -v else v
                }

                exponent == 0x1F -> Float.fromBits(sign or 0x7F800000 or (mantissa shl 13))

                else -> Float.fromBits(sign or ((exponent + 112) shl 23) or (mantissa shl 13))
            }
        }

        private const val HALF_SUBNORMAL = 5.9604645e-8f // 2^-24
    }
}
