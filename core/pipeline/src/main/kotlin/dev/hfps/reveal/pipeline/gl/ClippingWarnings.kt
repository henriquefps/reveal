package dev.hfps.reveal.pipeline.gl

/**
 * Clipping warnings drawn by the output pass over the on-screen photo (never into readbacks or exports unless asked).
 * The classification works on the display-encoded value of each pixel **before dithering** (`e` in the output
 * shader, 0..1 after the output transform's clamp and the sRGB OETF), so it says what the 8-bit display will show:
 *
 * - **Highlight clipping** (red): at least one channel encodes to 255, i.e. `max(e) >= 254.5 / 255`.
 * - **Shadow clipping** (blue): every channel encodes to 0, i.e. `max(e) < 0.5 / 255`.
 *
 * A pixel that is both (impossible) would be shown as a highlight. The shader mirrors [classify] line for line.
 */
data class ClippingWarnings(val shadows: Boolean = false, val highlights: Boolean = false) {
    /** The bit field the output pass reads (`dither.w`). */
    val bits: Int get() = (if (shadows) SHADOW_BIT else 0) or (if (highlights) HIGHLIGHT_BIT else 0)

    /** What a pixel with encoded channels [r], [g], [b] (0..1, before dithering) is, with these warnings on. */
    fun classify(r: Float, g: Float, b: Float): Clip {
        val m = maxOf(r, g, b)
        return when {
            highlights && m >= HIGHLIGHT_LEVEL -> Clip.HIGHLIGHT
            shadows && m < SHADOW_LEVEL -> Clip.SHADOW
            else -> Clip.NONE
        }
    }

    /** Result of [classify]. */
    enum class Clip { NONE, SHADOW, HIGHLIGHT }

    companion object {
        /** Warnings off. */
        val NONE: ClippingWarnings = ClippingWarnings()

        const val SHADOW_BIT: Int = 1
        const val HIGHLIGHT_BIT: Int = 2

        /** Encoded level from which a channel quantises to 255. */
        const val HIGHLIGHT_LEVEL: Float = 254.5f / 255f

        /** Encoded level below which a channel quantises to 0. */
        const val SHADOW_LEVEL: Float = 0.5f / 255f

        /** Overlay colour of clipped shadows, display-encoded 8-bit (also the shader's constant). */
        val SHADOW_RGB8: IntArray = intArrayOf(0, 102, 255)

        /** Overlay colour of clipped highlights, display-encoded 8-bit (also the shader's constant). */
        val HIGHLIGHT_RGB8: IntArray = intArrayOf(255, 26, 26)
    }
}
