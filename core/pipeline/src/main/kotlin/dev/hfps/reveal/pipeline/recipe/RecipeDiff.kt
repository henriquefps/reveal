package dev.hfps.reveal.pipeline.recipe

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a history step changed. The UI maps each to a string resource ("Exposure %1$s"); no text lives here. */
enum class HistoryField {
    EXPOSURE,
    TEMPERATURE,
    TINT,
    BASE_LOOK,
}

/**
 * One entry of the history list, for example "Exposure +0.70": the [field] picks the string resource and
 * [value] is the formatted, signed, locale-independent value to substitute (`+0.70`, `5600 K`, `-12`), or `null`
 * when the field returned to the camera's as-shot setting (the UI shows its own "as shot" text).
 */
data class HistoryStep(val field: HistoryField, val value: String?)

/** Diff of two recipes into history steps (docs/adr/0003, "History labels"). */
object RecipeDiff {
    /** Smallest change that counts as a step; below it two recipes are the same edit (rounding noise). */
    private const val EPSILON = 1e-4f

    private const val EV_DECIMALS = 2
    private const val MINUS = '−'

    /** Steps that turn [before] into [after], in pipeline order; empty if nothing user-visible changed. */
    fun between(before: Recipe, after: Recipe): List<HistoryStep> = buildList {
        if (before.profile.base != after.profile.base) {
            add(HistoryStep(HistoryField.BASE_LOOK, after.profile.base.name.lowercase(Locale.ROOT)))
        }
        if (abs(before.light.exposureEv - after.light.exposureEv) > EPSILON) {
            add(HistoryStep(HistoryField.EXPOSURE, signed(after.light.exposureEv, EV_DECIMALS)))
        }
        val wbBefore = before.colour.whiteBalance
        val wbAfter = after.colour.whiteBalance
        if (changed(wbBefore.temperatureK, wbAfter.temperatureK)) {
            val k = wbAfter.temperatureK
            add(HistoryStep(HistoryField.TEMPERATURE, k?.let { "${it.roundToInt()} K" }))
        }
        if (changed(wbBefore.tint, wbAfter.tint)) {
            val t = wbAfter.tint
            add(HistoryStep(HistoryField.TINT, t?.let { signed(it, 0) }))
        }
    }

    private fun changed(a: Float?, b: Float?): Boolean = when {
        a == null && b == null -> false
        a == null || b == null -> true
        else -> abs(a - b) > EPSILON
    }

    /** Explicit sign, U+2212 minus like the sliders' value labels, fixed decimals, never locale dependent. */
    private fun signed(value: Float, decimals: Int): String {
        val text = String.format(Locale.ROOT, "%.${decimals}f", abs(value))
        val isZero = text.all { it == '0' || it == '.' }
        return when {
            isZero -> text
            value < 0f -> "$MINUS$text"
            else -> "+$text"
        }
    }
}
