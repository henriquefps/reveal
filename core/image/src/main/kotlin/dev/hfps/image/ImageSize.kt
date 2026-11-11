package dev.hfps.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel dimensions of an image, used for decode targets, proxies and tiles. */
data class ImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
    }

    val longEdge: Int get() = max(width, height)

    val megapixels: Double get() = width.toDouble() * height / MEGAPIXEL

    /** This size scaled down (never up) so that its long edge is at most [maxLongEdge], keeping the aspect ratio. */
    fun fitLongEdge(maxLongEdge: Int): ImageSize {
        require(maxLongEdge > 0) { "maxLongEdge must be positive: $maxLongEdge" }
        if (longEdge <= maxLongEdge) return this
        val scale = maxLongEdge.toDouble() / longEdge
        return ImageSize(
            width = max(1, min(maxLongEdge, (width * scale).roundToInt())),
            height = max(1, min(maxLongEdge, (height * scale).roundToInt())),
        )
    }

    private companion object {
        const val MEGAPIXEL = 1_000_000.0
    }
}
