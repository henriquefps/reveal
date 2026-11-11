package dev.hfps.reveal.pipeline.export

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The pixel size of an export (docs/adr/0009, "Size"). Every option fits the photo (after its crop) inside a size
 * and keeps its aspect ratio; none upscales: an option asking for more pixels than the photo has exports the photo
 * at its own size.
 */
@Serializable
sealed interface ExportSize {
    /** The output size for a photo of [width] x [height] (after the crop); both at least 1. */
    fun outputSize(width: Int, height: Int): PixelSize

    /** Full resolution. */
    @Serializable
    @SerialName("original")
    data object Original : ExportSize {
        override fun outputSize(width: Int, height: Int) = PixelSize(width, height)
    }

    /** The long edge in pixels ("Web 2048" is `LongEdge(2048)`). */
    @Serializable
    @SerialName("longEdge")
    data class LongEdge(val pixels: Int) : ExportSize {
        init {
            require(pixels > 0) { "long edge must be positive" }
        }

        override fun outputSize(width: Int, height: Int) = scaled(width, height, pixels.toDouble() / max(width, height))
    }

    /** About [megapixels] million pixels. */
    @Serializable
    @SerialName("megapixels")
    data class Megapixels(val megapixels: Float) : ExportSize {
        init {
            require(megapixels > 0f) { "megapixels must be positive" }
        }

        override fun outputSize(width: Int, height: Int) =
            scaled(width, height, sqrt(megapixels * MEGA / (width.toDouble() * height)))
    }

    /** [percent] of the size on each axis (1..100). */
    @Serializable
    @SerialName("percent")
    data class Percent(val percent: Int) : ExportSize {
        init {
            require(percent in 1..FULL_PERCENT) { "percent must be 1..100" }
        }

        override fun outputSize(width: Int, height: Int) = scaled(width, height, percent / FULL_PERCENT.toDouble())
    }

    /**
     * Fit inside a [width] x [height] box ("Instagram 4:5" is `FitBox(1080, 1350)`). A photo with another aspect ratio
     * is not cropped: it fits inside the box ([fitsExactly] tells the UI to say so).
     */
    @Serializable
    @SerialName("fitBox")
    data class FitBox(val width: Int, val height: Int) : ExportSize {
        init {
            require(width > 0 && height > 0) { "box must be positive" }
        }

        override fun outputSize(width: Int, height: Int): PixelSize =
            scaled(width, height, minOf(this.width.toDouble() / width, this.height.toDouble() / height))

        /** True when a [photoWidth] x [photoHeight] photo fills the box (same aspect within half a pixel). */
        fun fitsExactly(photoWidth: Int, photoHeight: Int): Boolean {
            val out = outputSize(photoWidth, photoHeight)
            val upscaleFree = out.width == width && out.height == height
            val sameAspect = kotlin.math.abs(photoWidth.toDouble() / photoHeight - width.toDouble() / height) <
                1.0 / maxOf(width, height)
            return upscaleFree || sameAspect
        }
    }

    companion object {
        /** "Instagram 4:5 (1080 x 1350)". */
        val INSTAGRAM_PORTRAIT: ExportSize = FitBox(INSTAGRAM_WIDTH, INSTAGRAM_HEIGHT)

        /** "Web 2048". */
        val WEB: ExportSize = LongEdge(WEB_LONG_EDGE)

        private const val MEGA = 1_000_000.0
        private const val FULL_PERCENT = 100
        private const val INSTAGRAM_WIDTH = 1080
        private const val INSTAGRAM_HEIGHT = 1350
        private const val WEB_LONG_EDGE = 2048

        /** [width] x [height] scaled by [factor] (never above 1), rounded, at least 1 x 1. */
        internal fun scaled(width: Int, height: Int, factor: Double): PixelSize {
            val f = factor.coerceAtMost(1.0)
            if (f >= 1.0) return PixelSize(width, height)
            return PixelSize((width * f).roundToInt().coerceIn(1, width), (height * f).roundToInt().coerceIn(1, height))
        }
    }
}

/** A size in pixels. */
@Serializable
data class PixelSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}
