package dev.hfps.reveal.pipeline.cpu

/**
 * Round to the nearest float16 (ties to even), returned as the float it stands for: what the Detail passes write into
 * their RGBA16F targets (docs/adr/0012, "Storage"). The GPU does the same with integer operations on the float's bits
 * (`q16` in `gl/DetailShaders.kt`), so a stored value is exactly representable and the driver's own conversion (often
 * truncation, which biased the wavelet cascade by a third of a level) never rounds it again; the CPU reference then
 * stores the very same values. Exact for the float16 normal range; smaller magnitudes keep 10 mantissa bits (finer
 * than float16's subnormals, an error below 6e-8).
 */
object Half {
    private const val ROUND = 0x0FFF
    private const val KEEP = 0xFFFFE000.toInt()
    private const val SHIFT = 13

    /** [x] rounded to 10 mantissa bits, ties to even. */
    fun round(x: Float): Float {
        val u = x.toRawBits()
        return Float.fromBits((u + ROUND + ((u ushr SHIFT) and 1)) and KEEP)
    }
}
