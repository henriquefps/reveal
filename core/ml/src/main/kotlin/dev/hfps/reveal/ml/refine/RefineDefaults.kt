package dev.hfps.reveal.ml.refine

import dev.hfps.reveal.ml.MaskKind
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The edge refinement each mask kind gets by default (docs/adr/0021 "M4/C", "Refinement per kind"). A [Setting] is
 * independent of the image size: the coefficients are fitted on a grid whose long side is about [GRID] pixels (the
 * guide shrinks by `subsample = long side / GRID`), so the preview, the cache (≤ 1024 px) and a 24 MP export refine a
 * mask the same way relative to the photo, and the GPU coefficient pass stays small. [radius] is in grid pixels
 * (4 = 0.8 % of the long side); [eps] is the guided filter's edge threshold (smaller follows weaker edges).
 */
object RefineDefaults {
    /** A size-independent refinement: [radius] in grid pixels, [eps] in 0..1 units². */
    data class Setting(val radius: Int, val eps: Float) {
        init {
            require(radius >= 1 && eps > 0f) { "invalid refinement" }
        }

        /** The guided filter's parameters for a guide of [width] x [height]. */
        fun params(width: Int, height: Int): GuidedRefiner.Params =
            GuidedRefiner.Params(subsample = subsample(width, height), radius = radius, eps = eps)
    }

    /** The coefficient grid's long side in pixels. */
    const val GRID = 512

    /** People, the subject, the background, skin, clothes: the spike's setting, at a size-independent scale. */
    val DEFAULT = Setting(radius = 4, eps = 1e-3f)

    /** Hair: a smaller eps, so the mask follows fine, low-contrast strands. */
    val HAIR = Setting(radius = 4, eps = 2e-4f)

    /** Sky: a larger window, so branches and foliage against the sky are cut out of it. */
    val SKY = Setting(radius = 8, eps = 1e-3f)

    /** The default for [kind]; null for the face parts (their polygons already follow the landmarks closely). */
    fun of(kind: MaskKind): Setting? = when {
        kind.isFacePart -> null
        kind == MaskKind.HAIR -> HAIR
        kind == MaskKind.SKY -> SKY
        else -> DEFAULT
    }

    /** The guide's shrink factor for a [width] x [height] guide: its long side over [GRID], at least 1. */
    fun subsample(width: Int, height: Int): Int = max(1, (max(width, height).toFloat() / GRID).roundToInt())
}
