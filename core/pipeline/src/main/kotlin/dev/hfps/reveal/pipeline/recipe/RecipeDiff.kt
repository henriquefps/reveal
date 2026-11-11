package dev.hfps.reveal.pipeline.recipe

import java.util.Locale
import kotlin.math.abs

/**
 * What a history step changed. The UI maps each to a string resource ("Exposure %1$s"); no text lives here. Each
 * field belongs to exactly one copy/paste [group].
 */
enum class HistoryField(val group: RecipeGroup) {
    BASE_LOOK(RecipeGroup.PROFILE),
    PROFILE_AMOUNT(RecipeGroup.PROFILE),
    EXPOSURE(RecipeGroup.LIGHT),
    CONTRAST(RecipeGroup.LIGHT),
    HIGHLIGHTS(RecipeGroup.LIGHT),
    SHADOWS(RecipeGroup.LIGHT),
    WHITES(RecipeGroup.LIGHT),
    BLACKS(RecipeGroup.LIGHT),
    WB_MODE(RecipeGroup.COLOUR),
    TEMPERATURE(RecipeGroup.COLOUR),
    TINT(RecipeGroup.COLOUR),
    REL_TEMPERATURE(RecipeGroup.COLOUR),
    REL_TINT(RecipeGroup.COLOUR),
    VIBRANCE(RecipeGroup.COLOUR),
    SATURATION(RecipeGroup.COLOUR),
    CROP(RecipeGroup.GEOMETRY),
    ANGLE(RecipeGroup.GEOMETRY),
    ROTATE(RecipeGroup.GEOMETRY),
    FLIP_H(RecipeGroup.GEOMETRY),
    FLIP_V(RecipeGroup.GEOMETRY),
    ASPECT(RecipeGroup.GEOMETRY),
    SHARPEN_AMOUNT(RecipeGroup.DETAIL),
    SHARPEN_RADIUS(RecipeGroup.DETAIL),
    SHARPEN_DETAIL(RecipeGroup.DETAIL),
    SHARPEN_MASKING(RecipeGroup.DETAIL),
    NR_LUMINANCE(RecipeGroup.DETAIL),
    NR_LUMINANCE_DETAIL(RecipeGroup.DETAIL),
    NR_LUMINANCE_CONTRAST(RecipeGroup.DETAIL),
    NR_COLOUR(RecipeGroup.DETAIL),
    NR_COLOUR_DETAIL(RecipeGroup.DETAIL),
    NR_COLOUR_SMOOTHNESS(RecipeGroup.DETAIL),
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

    private const val QUARTER_TURN_DEGREES = 90

    /** Steps that turn [before] into [after], in pipeline order; empty if nothing user-visible changed. */
    fun between(before: Recipe, after: Recipe): List<HistoryStep> = buildList {
        profile(before.profile, after.profile)
        light(before.light, after.light)
        colour(before.colour, after.colour)
        detail(before.detail, after.detail)
        geometry(before.geometry, after.geometry)
    }

    private fun MutableList<HistoryStep>.profile(before: Profile, after: Profile) {
        if (before.base != after.base) add(HistoryStep(HistoryField.BASE_LOOK, key(after.base)))
        scalar(HistoryField.PROFILE_AMOUNT, RecipeRanges.profileAmount, before.amountPercent, after.amountPercent)
    }

    private fun MutableList<HistoryStep>.light(before: Light, after: Light) {
        scalar(HistoryField.EXPOSURE, RecipeRanges.exposure, before.exposureEv, after.exposureEv)
        scalar(HistoryField.CONTRAST, RecipeRanges.contrast, before.contrast, after.contrast)
        scalar(HistoryField.HIGHLIGHTS, RecipeRanges.highlights, before.highlights, after.highlights)
        scalar(HistoryField.SHADOWS, RecipeRanges.shadows, before.shadows, after.shadows)
        scalar(HistoryField.WHITES, RecipeRanges.whites, before.whites, after.whites)
        scalar(HistoryField.BLACKS, RecipeRanges.blacks, before.blacks, after.blacks)
    }

    private fun MutableList<HistoryStep>.colour(before: Colour, after: Colour) {
        val wbBefore = before.whiteBalance
        val wbAfter = after.whiteBalance
        if (wbBefore.mode != wbAfter.mode) add(HistoryStep(HistoryField.WB_MODE, key(wbAfter.mode)))
        if (changed(wbBefore.temperatureK, wbAfter.temperatureK)) {
            val k = wbAfter.temperatureK
            add(HistoryStep(HistoryField.TEMPERATURE, k?.let { RecipeRanges.temperature.format(it) }))
        }
        if (changed(wbBefore.tint, wbAfter.tint)) {
            val t = wbAfter.tint
            add(HistoryStep(HistoryField.TINT, t?.let { RecipeRanges.tint.format(it) }))
        }
        scalar(
            HistoryField.REL_TEMPERATURE,
            RecipeRanges.relTemperature,
            wbBefore.relTemperature,
            wbAfter.relTemperature,
        )
        scalar(HistoryField.REL_TINT, RecipeRanges.relTint, wbBefore.relTint, wbAfter.relTint)
        scalar(HistoryField.VIBRANCE, RecipeRanges.vibrance, before.vibrance, after.vibrance)
        scalar(HistoryField.SATURATION, RecipeRanges.saturation, before.saturation, after.saturation)
    }

    private fun MutableList<HistoryStep>.detail(before: Detail, after: Detail) {
        val r = RecipeRanges
        scalar(HistoryField.NR_LUMINANCE, r.nrLuminance, before.nrLuminance, after.nrLuminance)
        scalar(HistoryField.NR_LUMINANCE_DETAIL, r.nrLuminanceDetail, before.nrLuminanceDetail, after.nrLuminanceDetail)
        scalar(
            HistoryField.NR_LUMINANCE_CONTRAST,
            r.nrLuminanceContrast,
            before.nrLuminanceContrast,
            after.nrLuminanceContrast,
        )
        scalar(HistoryField.NR_COLOUR, r.nrColour, before.nrColour, after.nrColour)
        scalar(HistoryField.NR_COLOUR_DETAIL, r.nrColourDetail, before.nrColourDetail, after.nrColourDetail)
        scalar(
            HistoryField.NR_COLOUR_SMOOTHNESS,
            r.nrColourSmoothness,
            before.nrColourSmoothness,
            after.nrColourSmoothness,
        )
        scalar(HistoryField.SHARPEN_AMOUNT, r.sharpenAmount, before.sharpenAmount, after.sharpenAmount)
        scalar(HistoryField.SHARPEN_RADIUS, r.sharpenRadius, before.sharpenRadius, after.sharpenRadius)
        scalar(HistoryField.SHARPEN_DETAIL, r.sharpenDetail, before.sharpenDetail, after.sharpenDetail)
        scalar(HistoryField.SHARPEN_MASKING, r.sharpenMasking, before.sharpenMasking, after.sharpenMasking)
    }

    private fun MutableList<HistoryStep>.geometry(before: Geometry, after: Geometry) {
        if (before.crop != after.crop) add(HistoryStep(HistoryField.CROP, if (after.crop == null) null else "crop"))
        scalar(HistoryField.ANGLE, RecipeRanges.angle, before.angle, after.angle)
        if (before.rotate != after.rotate) {
            add(HistoryStep(HistoryField.ROTATE, "${after.rotate * QUARTER_TURN_DEGREES}°"))
        }
        if (before.flipH != after.flipH) add(HistoryStep(HistoryField.FLIP_H, null))
        if (before.flipV != after.flipV) add(HistoryStep(HistoryField.FLIP_V, null))
        if (before.aspect != after.aspect) add(HistoryStep(HistoryField.ASPECT, key(after.aspect.preset)))
    }

    private fun MutableList<HistoryStep>.scalar(field: HistoryField, range: ControlRange, before: Float, after: Float) {
        if (abs(before - after) > EPSILON) add(HistoryStep(field, range.format(after)))
    }

    /** The enum's stable lower-case key (`camera_match`), which `HistoryLabels` maps to a localised name. */
    private fun key(value: Enum<*>): String = value.name.lowercase(Locale.ROOT)

    private fun changed(a: Float?, b: Float?): Boolean = when {
        a == null && b == null -> false
        a == null || b == null -> true
        else -> abs(a - b) > EPSILON
    }
}
