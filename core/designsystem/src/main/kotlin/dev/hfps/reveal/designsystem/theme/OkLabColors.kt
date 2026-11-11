@file:Suppress("MagicNumber")

package dev.hfps.reveal.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Content colours of the colour tools (colour mixer swatches and slider tracks, grading wheels): colours *of the
 * photo*, not chrome, defined in OkLab (Björn Ottosson's sRGB matrices) so a hue on screen is the same hue the
 * pipeline's colour tools work on. Out-of-sRGB colours keep their lightness and hue and lose chroma until they fit.
 */
object OkLabColors {
    /** Lightness and chroma of swatches and wheel rims: clearly coloured, not neon, readable on the dark panel. */
    const val SWATCH_L: Float = 0.68f
    const val SWATCH_C: Float = 0.13f

    /** The sRGB colour of OkLab ([l], [c], [hueDegrees]). */
    fun color(l: Float, c: Float, hueDegrees: Float, alpha: Float = 1f): Color {
        var lo = 0f
        var hi = c
        var rgb = linear(l, c, hueDegrees)
        if (!inGamut(rgb)) {
            repeat(GAMUT_STEPS) {
                val mid = (lo + hi) / 2f
                if (inGamut(linear(l, mid, hueDegrees))) lo = mid else hi = mid
            }
            rgb = linear(l, lo, hueDegrees)
        }
        return Color(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]), alpha)
    }

    /** A horizontal gradient through [hues] (degrees) at [l], [c]. */
    fun hueGradient(hues: List<Float>, l: Float = SWATCH_L, c: Float = SWATCH_C): Brush =
        Brush.horizontalGradient(hues.map { color(l, c, it) })

    /** A horizontal gradient from grey to the colour of [hueDegrees] (a saturation track). */
    fun saturationGradient(hueDegrees: Float): Brush =
        Brush.horizontalGradient(listOf(color(SWATCH_L, 0f, hueDegrees), color(SWATCH_L, SWATCH_C, hueDegrees)))

    /** A horizontal gradient from dark to light in the colour of [hueDegrees] (a luminance track). */
    fun luminanceGradient(hueDegrees: Float, c: Float = SWATCH_C): Brush =
        Brush.horizontalGradient(listOf(color(0.35f, c * 0.7f, hueDegrees), color(0.9f, c * 0.7f, hueDegrees)))

    /** [steps] + 1 colours around the hue circle (0..360°), for sweep gradients of wheels. */
    fun hueCircle(steps: Int = 36, l: Float = SWATCH_L, c: Float = SWATCH_C): List<Color> =
        (0..steps).map { color(l, c, it * 360f / steps) }

    private fun linear(l: Float, c: Float, hueDegrees: Float): FloatArray {
        val h = Math.toRadians(hueDegrees.toDouble())
        val a = c * cos(h).toFloat()
        val b = c * sin(h).toFloat()
        val l1 = l + 0.3963377774f * a + 0.2158037573f * b
        val m1 = l - 0.1055613458f * a - 0.0638541728f * b
        val s1 = l - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l1 * l1 * l1
        val m3 = m1 * m1 * m1
        val s3 = s1 * s1 * s1
        return floatArrayOf(
            4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
            -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
            -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
        )
    }

    private fun inGamut(rgb: FloatArray) = rgb.all { it in -1e-4f..1.0001f }

    private fun encode(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    private const val GAMUT_STEPS = 12
}
