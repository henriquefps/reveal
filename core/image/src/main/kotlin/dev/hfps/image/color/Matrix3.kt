// Colour constants and fixed array layouts: named inline, not as constants.
@file:Suppress("MagicNumber")

package dev.hfps.image.color

import kotlin.math.abs

/**
 * A 3x3 matrix of doubles, row-major: `values[3 * row + column]`. Used for colour transforms
 * (`output = matrix * input`, input a column vector of R, G, B or X, Y, Z).
 */
class Matrix3(values: List<Double>) {
    /** The nine entries, row-major. */
    val values: List<Double> = values.toList()

    init {
        require(this.values.size == SIZE) { "A 3x3 matrix has 9 entries, got ${this.values.size}" }
    }

    constructor(vararg entries: Double) : this(entries.toList())

    /** Entry at [row], [column] (both 0..2). */
    operator fun get(row: Int, column: Int): Double = values[3 * row + column]

    /** Matrix product `this * other` (apply [other] first). */
    operator fun times(other: Matrix3): Matrix3 = Matrix3(
        List(SIZE) { i ->
            val row = i / 3
            val column = i % 3
            this[row, 0] * other[0, column] + this[row, 1] * other[1, column] + this[row, 2] * other[2, column]
        },
    )

    /** `this * (x, y, z)`. */
    fun apply(x: Double, y: Double, z: Double): Triple<Double, Double, Double> = Triple(
        this[0, 0] * x + this[0, 1] * y + this[0, 2] * z,
        this[1, 0] * x + this[1, 1] * y + this[1, 2] * z,
        this[2, 0] * x + this[2, 1] * y + this[2, 2] * z,
    )

    /** Matrix with each column scaled: `this * diag(x, y, z)`. */
    fun scaleColumns(x: Double, y: Double, z: Double): Matrix3 {
        val scale = listOf(x, y, z)
        return Matrix3(List(SIZE) { i -> values[i] * scale[i % 3] })
    }

    val determinant: Double
        get() = this[0, 0] * (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) -
            this[0, 1] * (this[1, 0] * this[2, 2] - this[1, 2] * this[2, 0]) +
            this[0, 2] * (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0])

    /** The inverse, or null when the matrix is singular (determinant below 1e-12). */
    fun inverse(): Matrix3? {
        val det = determinant
        if (abs(det) < SINGULAR) return null
        val inv = 1.0 / det
        return Matrix3(
            (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) * inv,
            (this[0, 2] * this[2, 1] - this[0, 1] * this[2, 2]) * inv,
            (this[0, 1] * this[1, 2] - this[0, 2] * this[1, 1]) * inv,
            (this[1, 2] * this[2, 0] - this[1, 0] * this[2, 2]) * inv,
            (this[0, 0] * this[2, 2] - this[0, 2] * this[2, 0]) * inv,
            (this[0, 2] * this[1, 0] - this[0, 0] * this[1, 2]) * inv,
            (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0]) * inv,
            (this[0, 1] * this[2, 0] - this[0, 0] * this[2, 1]) * inv,
            (this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]) * inv,
        )
    }

    /** Entry-wise comparison within [tolerance]. */
    fun isCloseTo(other: Matrix3, tolerance: Double): Boolean =
        values.indices.all { abs(values[it] - other.values[it]) <= tolerance }

    override fun equals(other: Any?): Boolean = other is Matrix3 && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String =
        values.chunked(3).joinToString(prefix = "[", postfix = "]", separator = "; ") { row ->
            row.joinToString(" ") { "%.5f".format(it) }
        }

    companion object {
        private const val SIZE = 9
        private const val SINGULAR = 1e-12

        val IDENTITY = Matrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}
