@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.synthetic

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.mask.StrokeCodec
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic images and spots for the healing tests, parity checks and benchmarks (docs/adr/0023): a textured gradient
 * with dust, stripes with an object, an eye with a red pupil.
 */
object HealScenes {
    /** A smooth two-way gradient with a fine deterministic texture (a sky, a wall). */
    fun texturedGradient(w: Int, h: Int, texture: Float = 0.02f): PipelineInput {
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = 0.15f + 0.35f * x / w + 0.2f * y / h
                val t = texture * (hash(x, y) - 0.5f)
                val i = (y * w + x) * 3
                rgb[i] = base * 0.9f + t
                rgb[i + 1] = base + t
                rgb[i + 2] = base * 1.2f + t
            }
        }
        return PipelineInput.linearSrgb(w, h, rgb)
    }

    /** [image] with a dark soft blob (a dust spot) of [radius] at ([cx], [cy]). */
    fun withDust(image: PipelineInput, cx: Float, cy: Float, radius: Float, depth: Float = 0.5f): PipelineInput {
        val rgb = image.linearRgb.copyOf()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val d = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy)) / radius
                if (d >= 1f) continue
                val k = 1f - depth * (1f - d * d)
                val i = (y * image.width + x) * 3
                for (c in 0..2) rgb[i + c] *= k
            }
        }
        return PipelineInput.linearSrgb(image.width, image.height, rgb)
    }

    /** Diagonal stripes of period [period] pixels on a gradient (structure a fill must continue). */
    fun stripes(w: Int, h: Int, period: Float = 12f): PipelineInput {
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val s = 0.5f + 0.5f * sin(2.0 * Math.PI * (x + y) / period).toFloat()
                val i = (y * w + x) * 3
                rgb[i] = 0.1f + 0.4f * s
                rgb[i + 1] = 0.12f + 0.3f * s
                rgb[i + 2] = 0.2f + 0.1f * s
            }
        }
        return PipelineInput.linearSrgb(w, h, rgb)
    }

    /** [image] with a solid square object of side [side] at ([cx], [cy]). */
    fun withObject(image: PipelineInput, cx: Int, cy: Int, side: Int): PipelineInput {
        val rgb = image.linearRgb.copyOf()
        for (y in cy - side / 2 until cy + side / 2) {
            for (x in cx - side / 2 until cx + side / 2) {
                val i = (y * image.width + x) * 3
                rgb[i] = 0.9f
                rgb[i + 1] = 0.05f
                rgb[i + 2] = 0.05f
            }
        }
        return PipelineInput.linearSrgb(image.width, image.height, rgb)
    }

    /** Skin-coloured face area with an eye: a dark iris of [iris] px and a red pupil of [pupil] px at the centre. */
    fun redEye(w: Int, h: Int, iris: Float, pupil: Float): PipelineInput {
        val rgb = FloatArray(w * h * 3)
        val cx = w / 2f
        val cy = h / 2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy))
                val i = (y * w + x) * 3
                val (r, g, b) = when {
                    d < pupil -> Triple(0.55f, 0.06f, 0.05f)
                    d < iris -> Triple(0.06f, 0.04f, 0.03f)
                    else -> Triple(0.55f, 0.32f, 0.24f)
                }
                rgb[i] = r
                rgb[i + 1] = g
                rgb[i + 2] = b
            }
        }
        return PipelineInput.linearSrgb(w, h, rgb)
    }

    /** A spot of [kind] at pixel ([cx], [cy]) of a [w] x [h] image, [radius] pixels, source offset in pixels. */
    @Suppress("LongParameterList")
    fun spot(
        id: String,
        kind: String,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        ox: Float = 0f,
        oy: Float = 0f,
        feather: Float = Heal.DEFAULT_FEATHER,
    ): Heal = Heal(
        id = id,
        kind = kind,
        x = cx / w,
        y = cy / h,
        radius = radius / max(w, h),
        dx = ox / w,
        dy = oy / h,
        feather = feather,
    )

    /** A stroke spot through pixel points. */
    @Suppress("LongParameterList")
    fun stroke(
        id: String,
        kind: String,
        w: Int,
        h: Int,
        points: List<Pair<Float, Float>>,
        radius: Float,
        ox: Float = 0f,
        oy: Float = 0f,
    ): Heal = Heal(
        id = id,
        kind = kind,
        radius = radius / max(w, h),
        stroke = StrokeCodec.encode(points.map { StrokePoint(it.first / w, it.second / h) }),
        dx = ox / w,
        dy = oy / h,
    )

    /** Mean absolute difference over a box (all channels). */
    @Suppress("LongParameterList")
    fun meanError(a: FloatArray, b: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var s = 0.0
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                for (c in 0..2) {
                    val i = (y * w + x) * 3 + c
                    s += kotlin.math.abs(a[i] - b[i])
                    n++
                }
            }
        }
        return (s / n).toFloat()
    }

    private fun hash(x: Int, y: Int): Float {
        var v = x * 374761393 + y * 668265263
        v = (v xor (v ushr 13)) * 1274126177
        v = v xor (v ushr 16)
        return (v and 0xffff) / 65535f
    }
}
