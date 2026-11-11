package dev.hfps.reveal.pipeline.gl

import kotlin.math.ln

/**
 * How the output pass reads the stage texture, decided on the CPU so no result depends on a driver's
 * screen-space derivative precision (ADR 0002, "Output pass sampling").
 */
internal object OutputSampling {
    /** Shader mode: `texelFetch` of the exact texel at LOD 0 (1:1 view, readback and export). */
    const val MODE_TEXEL_FETCH = 0

    /** Shader mode: `textureLod` at the explicit LOD from [lod]; the filter comes from the texture parameters. */
    const val MODE_LOD = 1

    /** Screen scale from which magnified pixels are shown as nearest rather than bilinear. */
    const val NEAREST_ABOVE_SCALE = 2f

    fun mode(scale: Float): Int = if (scale == 1f) MODE_TEXEL_FETCH else MODE_LOD

    /** Explicit mip level for [scale] screen pixels per image pixel: `max(0, log2(1 / scale))`, exactly 0 at 1:1. */
    fun lod(scale: Float): Float = if (scale >=
        1f
    ) {
        0f
    } else {
        (-ln(scale.toDouble()) / ln(2.0)).toFloat().coerceAtLeast(0f)
    }

    fun nearestMagnification(scale: Float): Boolean = scale >= NEAREST_ABOVE_SCALE
}
