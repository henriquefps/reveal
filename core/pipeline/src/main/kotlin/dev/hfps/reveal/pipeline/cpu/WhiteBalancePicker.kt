package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput

/**
 * White balance from the photo itself, for RAW input (absolute temperature/tint; display-referred input has no
 * camera response to invert and returns `null`). Built on [WhiteBalanceModel]: the camera's raw response to an
 * illuminant `W` is `cam = M^-1 W`, so a patch the user says is neutral, with mean raw RGB `cam`, was lit by
 * `W = M * cam`, whose [WhiteBalanceModel.cctAndTint] are the slider values that render that patch neutral.
 *
 * - [fromNeutral]: the eyedropper (mean raw RGB around the tapped point, read back from the renderer's source).
 * - [grayWorld]: "Auto" white balance, the grey-world estimate over the mid-tones of the proxy.
 */
object WhiteBalancePicker {
    /** Raw values below this (relative to white = 1) are too dark to judge colour. */
    private const val MIN_LEVEL = 0.02f

    /** Raw values above this share of saturation may be clipped in a channel and are skipped. */
    private const val MAX_SHARE_OF_SATURATION = 0.9f

    /** Tint range the sliders allow (RecipeRanges.tint). */
    private const val MAX_TINT = 150.0

    /**
     * Temperature and tint that make the raw camera colour [cameraRgb] (un-balanced, as in
     * [PipelineInput.linearRgb]) neutral, clamped to the slider ranges; `null` for display-referred input or a
     * patch with a non-positive channel.
     */
    @Suppress("ReturnCount")
    fun fromNeutral(input: PipelineInput, cameraRgb: FloatArray): WhiteBalanceModel.Cct? {
        if (!input.isRaw) return null
        if (cameraRgb.size < PipelineInput.CHANNELS || cameraRgb.take(PipelineInput.CHANNELS).any { !(it > 0f) }) {
            return null
        }
        val white = input.cameraToXyz.times(cameraRgb[0].toDouble(), cameraRgb[1].toDouble(), cameraRgb[2].toDouble())
        if (white[1] <= 0.0) return null
        val cct = WhiteBalanceModel.cctAndTint(white)
        return WhiteBalanceModel.Cct(
            cct.temperatureK.coerceIn(WhiteBalanceModel.MIN_TEMPERATURE_K, WhiteBalanceModel.MAX_TEMPERATURE_K),
            cct.tint.coerceIn(-MAX_TINT, MAX_TINT),
        )
    }

    /**
     * Grey-world estimate: the mean raw colour of every [stride]-th pixel (both axes) that is neither too dark nor
     * near saturation, made neutral with [fromNeutral]. `null` for display-referred input or an image with no usable
     * pixels.
     */
    @Suppress("ReturnCount")
    fun grayWorld(input: PipelineInput, stride: Int = DEFAULT_STRIDE): WhiteBalanceModel.Cct? {
        if (!input.isRaw) return null
        val ceiling = (input.saturation ?: 1f) * MAX_SHARE_OF_SATURATION
        val sum = DoubleArray(PipelineInput.CHANNELS)
        var n = 0
        val rgb = input.linearRgb
        for (y in 0 until input.height step stride) {
            for (x in 0 until input.width step stride) {
                val i = (y * input.width + x) * PipelineInput.CHANNELS
                val r = rgb[i]
                val g = rgb[i + 1]
                val b = rgb[i + 2]
                val hi = maxOf(r, g, b)
                if (minOf(r, g, b) >= MIN_LEVEL && hi <= ceiling) {
                    sum[0] += r.toDouble()
                    sum[1] += g.toDouble()
                    sum[2] += b.toDouble()
                    n++
                }
            }
        }
        if (n == 0) return null
        return fromNeutral(input, FloatArray(PipelineInput.CHANNELS) { (sum[it] / n).toFloat() })
    }

    private const val DEFAULT_STRIDE = 4
}
