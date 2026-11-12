package dev.hfps.reveal.ml

import dev.hfps.reveal.ml.tensor.Resample

/**
 * A soft mask: [width] x [height] coverage values in 0..1 (1 = selected), row-major. Masks come out of a model at its
 * own resolution and are resampled to the photo by the caller ([resized]); refinement ([dev.hfps.reveal.ml.refine])
 * snaps them to the photo's edges at full resolution.
 */
class Mask(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0) { "mask must not be empty" }
        require(values.size == width * height) { "values must have width*height entries" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    /** 1 - this (the background of a subject). */
    fun inverted(): Mask = Mask(width, height, FloatArray(values.size) { 1f - values[it] })

    /** Bilinear (pixel centres aligned, explicit weights) to [w] x [h]; area-averaged first when shrinking. */
    fun resized(w: Int, h: Int): Mask =
        if (w == width && h == height) this else Mask(w, h, Resample.plane(values, width, height, w, h))

    /** Mean coverage (the selected fraction of the frame). */
    fun mean(): Float = (values.sumOf { it.toDouble() } / values.size).toFloat()
}
