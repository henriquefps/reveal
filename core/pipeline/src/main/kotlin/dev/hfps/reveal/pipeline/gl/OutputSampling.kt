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

    /** Shader mode: Catmull-Rom at LOD 0 through explicit texel fetches (straightened geometry, 1:1 and above). */
    const val MODE_CATMULL_ROM = 2

    /**
     * Shader mode: four bilinear samples of level 0 at an explicit LOD 0 spread over the screen pixel's footprint
     * (minified views down to [BOX_MIN_SCALE]), so the editor's fit view needs no mips. On-screen only, like the mips.
     */
    const val MODE_BOX = 3

    /** Smallest screen scale [MODE_BOX] covers (a footprint of 3 texels). */
    const val BOX_MIN_SCALE = 1f / 3f

    /** Screen scale from which magnified pixels are shown as nearest rather than bilinear. */
    const val NEAREST_ABOVE_SCALE = 2f

    /**
     * The sampling of [scale] screen pixels per output pixel. Without straightening ([axisAligned]) the output pixels
     * are image texels (exact copies at 1:1); with it, 1:1 and magnified views resample with Catmull-Rom like the
     * geometry operator, and minified views read the mips (an on-screen-only approximation).
     */
    fun mode(scale: Float, axisAligned: Boolean = true): Int = when {
        scale < 1f -> MODE_LOD
        !axisAligned -> MODE_CATMULL_ROM
        scale == 1f -> MODE_TEXEL_FETCH
        else -> MODE_LOD
    }

    /**
     * The editor's sampling of its working texture: [mode], except [MODE_BOX] for axis-aligned views minified down to
     * [BOX_MIN_SCALE] (docs/adr/0012, "M2/P": generating the mips of the 2400 px working texture cost about a
     * millisecond per frame on the Adreno 750). Zoom tiles keep [mode] (their atlas has its own two levels).
     */
    fun stageMode(scale: Float, axisAligned: Boolean = true): Int =
        if (axisAligned && scale < 1f && scale >= BOX_MIN_SCALE) MODE_BOX else mode(scale, axisAligned)

    /** True when [stageMode] reads mip levels above 0 (the working texture's mips must be current). */
    fun needsMips(scale: Float, axisAligned: Boolean = true): Boolean =
        stageMode(scale, axisAligned) == MODE_LOD && lod(scale) > 0f

    /**
     * True when [stageMode] reads level 0 of the working texture: every mode but [MODE_BOX] at 50 % or less (which
     * reads level 1 when only it is current) and [MODE_LOD] at LOD 1 or more.
     */
    fun readsLevel0(scale: Float, axisAligned: Boolean = true): Boolean = when (stageMode(scale, axisAligned)) {
        MODE_BOX -> scale > HALF_SCALE
        MODE_LOD -> lod(scale) < 1f
        else -> true
    }

    private const val HALF_SCALE = 0.5f

    /**
     * Whether the output point snaps to its output pixel centre: Catmull-Rom at 1:1 (the operator's exact samples) and
     * from [NEAREST_ABOVE_SCALE] (pixel peeping shows the export's pixels, magnified as squares).
     */
    fun snapToOutputPixels(scale: Float, axisAligned: Boolean): Boolean =
        !axisAligned && (scale == 1f || scale >= NEAREST_ABOVE_SCALE)

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
