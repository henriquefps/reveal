package dev.hfps.reveal.pipeline.cpu

/**
 * Immutable 3x3 matrix in row-major order, in double precision. Colour-transform parameters (camera matrix,
 * chromatic adaptation, working space) are always computed in `Double` and rounded to `Float` once, when they are
 * handed to an operator or uploaded to the GPU, so the CPU reference and the shaders use the very same constants.
 */
class Mat3(vararg values: Double) {
    private val v: DoubleArray = values.copyOf()

    init {
        require(v.size == SIZE) { "Mat3 needs 9 values, got ${v.size}" }
    }

    /** Element at [row], [col]. */
    operator fun get(row: Int, col: Int): Double = v[row * COLS + col]

    /** Matrix product `this * other`. */
    @Suppress("SpreadOperator")
    operator fun times(other: Mat3): Mat3 = Mat3(
        *DoubleArray(SIZE) { i ->
            val r = i / COLS
            val c = i % COLS
            this[r, 0] * other[0, c] + this[r, 1] * other[1, c] + this[r, 2] * other[2, c]
        },
    )

    /** Matrix times column vector. */
    fun times(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        this[0, 0] * x + this[0, 1] * y + this[0, 2] * z,
        this[1, 0] * x + this[1, 1] * y + this[1, 2] * z,
        this[2, 0] * x + this[2, 1] * y + this[2, 2] * z,
    )

    /** Matrix times column vector. */
    fun times(vec: DoubleArray): DoubleArray = times(vec[0], vec[1], vec[2])

    /** The inverse; throws for a singular matrix. */
    fun inverse(): Mat3 {
        val a = this[0, 0]
        val b = this[0, 1]
        val c = this[0, 2]
        val d = this[1, 0]
        val e = this[1, 1]
        val f = this[1, 2]
        val g = this[2, 0]
        val h = this[2, 1]
        val i = this[2, 2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(kotlin.math.abs(det) > SINGULAR_EPSILON) { "Singular matrix (det=$det)" }
        val s = 1.0 / det
        return Mat3(
            (e * i - f * h) * s, (c * h - b * i) * s, (b * f - c * e) * s,
            (f * g - d * i) * s, (a * i - c * g) * s, (c * d - a * f) * s,
            (d * h - e * g) * s, (b * g - a * h) * s, (a * e - b * d) * s,
        )
    }

    /** Row-major copy of the nine values rounded to float. */
    fun toFloatArray(): FloatArray = FloatArray(SIZE) { v[it].toFloat() }

    override fun equals(other: Any?): Boolean = other is Mat3 && v.contentEquals(other.v)

    override fun hashCode(): Int = v.contentHashCode()

    override fun toString(): String = v.joinToString(prefix = "Mat3(", postfix = ")")

    companion object {
        private const val SIZE = 9
        private const val COLS = 3
        private const val SINGULAR_EPSILON = 1e-12

        /** The identity matrix. */
        val IDENTITY: Mat3 = Mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        /** Diagonal matrix. */
        fun diagonal(x: Double, y: Double, z: Double): Mat3 = Mat3(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, z)
    }
}
