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
    TEXTURE(RecipeGroup.EFFECTS),
    CLARITY(RecipeGroup.EFFECTS),
    DEHAZE(RecipeGroup.EFFECTS),
    VIGNETTE_AMOUNT(RecipeGroup.EFFECTS),
    VIGNETTE_MIDPOINT(RecipeGroup.EFFECTS),
    VIGNETTE_ROUNDNESS(RecipeGroup.EFFECTS),
    VIGNETTE_FEATHER(RecipeGroup.EFFECTS),
    VIGNETTE_HIGHLIGHTS(RecipeGroup.EFFECTS),
    GRAIN_AMOUNT(RecipeGroup.EFFECTS),
    GRAIN_SIZE(RecipeGroup.EFFECTS),
    GRAIN_ROUGHNESS(RecipeGroup.EFFECTS),

    // Optics and Transform (docs/adr/0015); before TREATMENT (HistoryLabels formats the later fields as colour tools).
    LENS_CORRECTIONS(RecipeGroup.OPTICS),
    LENS_DISTORTION(RecipeGroup.OPTICS),
    LENS_VIGNETTING(RecipeGroup.OPTICS),
    REMOVE_CA(RecipeGroup.OPTICS),
    DEFRINGE_PURPLE_AMOUNT(RecipeGroup.OPTICS),
    DEFRINGE_PURPLE_HUE(RecipeGroup.OPTICS),
    DEFRINGE_GREEN_AMOUNT(RecipeGroup.OPTICS),
    DEFRINGE_GREEN_HUE(RecipeGroup.OPTICS),
    UPRIGHT(RecipeGroup.GEOMETRY),
    GUIDES(RecipeGroup.GEOMETRY),
    TRANSFORM_VERTICAL(RecipeGroup.GEOMETRY),
    TRANSFORM_HORIZONTAL(RecipeGroup.GEOMETRY),
    TRANSFORM_ROTATE(RecipeGroup.GEOMETRY),
    TRANSFORM_ASPECT(RecipeGroup.GEOMETRY),
    TRANSFORM_SCALE(RecipeGroup.GEOMETRY),
    TRANSFORM_OFFSET_X(RecipeGroup.GEOMETRY),
    TRANSFORM_OFFSET_Y(RecipeGroup.GEOMETRY),

    // Masks (docs/adr/0022): the value is the mask's name.
    MASK_ADD(RecipeGroup.MASKS),
    MASK_REMOVE(RecipeGroup.MASKS),
    MASK_RENAME(RecipeGroup.MASKS),
    MASK_SHOW(RecipeGroup.MASKS),
    MASK_HIDE(RecipeGroup.MASKS),
    MASK_SHAPE(RecipeGroup.MASKS),
    MASK_ADJUST(RecipeGroup.MASKS),
    MASK_ORDER(RecipeGroup.MASKS),

    // Healing (docs/adr/0023): the value is the spot's kind key (`heal`, `clone`, `remove`, `redEye`).
    HEAL_ADD(RecipeGroup.HEALS),
    HEAL_DELETE(RecipeGroup.HEALS),
    HEAL_SOURCE(RecipeGroup.HEALS),
    HEAL_EDIT(RecipeGroup.HEALS),

    // Colour tools (docs/adr/0013). Scalar fields share their ControlId's name.
    TREATMENT(RecipeGroup.PROFILE),
    CURVE_SHADOWS(RecipeGroup.LIGHT),
    CURVE_DARKS(RecipeGroup.LIGHT),
    CURVE_LIGHTS(RecipeGroup.LIGHT),
    CURVE_HIGHLIGHTS(RecipeGroup.LIGHT),
    CURVE_SPLIT_SHADOWS(RecipeGroup.LIGHT),
    CURVE_SPLIT_MIDTONES(RecipeGroup.LIGHT),
    CURVE_SPLIT_HIGHLIGHTS(RecipeGroup.LIGHT),
    CURVE_RGB(RecipeGroup.LIGHT),
    CURVE_RED(RecipeGroup.LIGHT),
    CURVE_GREEN(RecipeGroup.LIGHT),
    CURVE_BLUE(RecipeGroup.LIGHT),
    HUE_RED(RecipeGroup.COLOUR),
    HUE_ORANGE(RecipeGroup.COLOUR),
    HUE_YELLOW(RecipeGroup.COLOUR),
    HUE_GREEN(RecipeGroup.COLOUR),
    HUE_AQUA(RecipeGroup.COLOUR),
    HUE_BLUE(RecipeGroup.COLOUR),
    HUE_PURPLE(RecipeGroup.COLOUR),
    HUE_MAGENTA(RecipeGroup.COLOUR),
    SAT_RED(RecipeGroup.COLOUR),
    SAT_ORANGE(RecipeGroup.COLOUR),
    SAT_YELLOW(RecipeGroup.COLOUR),
    SAT_GREEN(RecipeGroup.COLOUR),
    SAT_AQUA(RecipeGroup.COLOUR),
    SAT_BLUE(RecipeGroup.COLOUR),
    SAT_PURPLE(RecipeGroup.COLOUR),
    SAT_MAGENTA(RecipeGroup.COLOUR),
    LUM_RED(RecipeGroup.COLOUR),
    LUM_ORANGE(RecipeGroup.COLOUR),
    LUM_YELLOW(RecipeGroup.COLOUR),
    LUM_GREEN(RecipeGroup.COLOUR),
    LUM_AQUA(RecipeGroup.COLOUR),
    LUM_BLUE(RecipeGroup.COLOUR),
    LUM_PURPLE(RecipeGroup.COLOUR),
    LUM_MAGENTA(RecipeGroup.COLOUR),
    GREY_RED(RecipeGroup.COLOUR),
    GREY_ORANGE(RecipeGroup.COLOUR),
    GREY_YELLOW(RecipeGroup.COLOUR),
    GREY_GREEN(RecipeGroup.COLOUR),
    GREY_AQUA(RecipeGroup.COLOUR),
    GREY_BLUE(RecipeGroup.COLOUR),
    GREY_PURPLE(RecipeGroup.COLOUR),
    GREY_MAGENTA(RecipeGroup.COLOUR),
    GRADE_SHADOWS_HUE(RecipeGroup.COLOUR),
    GRADE_SHADOWS_SAT(RecipeGroup.COLOUR),
    GRADE_SHADOWS_LUM(RecipeGroup.COLOUR),
    GRADE_MIDTONES_HUE(RecipeGroup.COLOUR),
    GRADE_MIDTONES_SAT(RecipeGroup.COLOUR),
    GRADE_MIDTONES_LUM(RecipeGroup.COLOUR),
    GRADE_HIGHLIGHTS_HUE(RecipeGroup.COLOUR),
    GRADE_HIGHLIGHTS_SAT(RecipeGroup.COLOUR),
    GRADE_HIGHLIGHTS_LUM(RecipeGroup.COLOUR),
    GRADE_GLOBAL_HUE(RecipeGroup.COLOUR),
    GRADE_GLOBAL_SAT(RecipeGroup.COLOUR),
    GRADE_GLOBAL_LUM(RecipeGroup.COLOUR),
    GRADE_BLENDING(RecipeGroup.COLOUR),
    GRADE_BALANCE(RecipeGroup.COLOUR),
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
        effects(before.effects, after.effects)
        optics(before.optics, after.optics)
        geometry(before.geometry, after.geometry)
        masks(before.masks, after.masks)
        heals(before.heals, after.heals)
    }

    /**
     * Healing spots matched by id (docs/adr/0023): removed, added, then per kept spot a moved source and any other
     * change (shape, size, kind, feather, opacity, method, darken). The order of the spots is not a step.
     */
    private fun MutableList<HistoryStep>.heals(before: List<Heal>, after: List<Heal>) {
        val old = before.associateBy { it.id }
        val new = after.associateBy { it.id }
        before.filter { it.id !in new }.forEach { add(HistoryStep(HistoryField.HEAL_DELETE, it.kind)) }
        after.filter { it.id !in old }.forEach { add(HistoryStep(HistoryField.HEAL_ADD, it.kind)) }
        for (heal in after) {
            val was = old[heal.id] ?: continue
            if (changed(was.dx, heal.dx) || changed(was.dy, heal.dy) || was.autoSource != heal.autoSource) {
                add(HistoryStep(HistoryField.HEAL_SOURCE, heal.kind))
            }
            val rest = heal.copy(dx = was.dx, dy = was.dy, autoSource = was.autoSource)
            if (rest != was) add(HistoryStep(HistoryField.HEAL_EDIT, heal.kind))
        }
    }

    /**
     * Masks matched by id (docs/adr/0022): removed, added, then per kept mask its name, visibility, shape and
     * adjustments (amount included), then whether the kept masks changed order.
     */
    private fun MutableList<HistoryStep>.masks(before: List<Mask>, after: List<Mask>) {
        val old = before.associateBy { it.id }
        val new = after.associateBy { it.id }
        before.filter { it.id !in new }.forEach { add(HistoryStep(HistoryField.MASK_REMOVE, it.name)) }
        after.filter { it.id !in old }.forEach { add(HistoryStep(HistoryField.MASK_ADD, it.name)) }
        for (mask in after) {
            val was = old[mask.id] ?: continue
            if (was.name != mask.name) add(HistoryStep(HistoryField.MASK_RENAME, mask.name))
            if (was.enabled != mask.enabled) {
                add(HistoryStep(if (mask.enabled) HistoryField.MASK_SHOW else HistoryField.MASK_HIDE, mask.name))
            }
            if (was.components != mask.components) add(HistoryStep(HistoryField.MASK_SHAPE, mask.name))
            if (was.adjustments != mask.adjustments || changed(was.amount, mask.amount)) {
                add(HistoryStep(HistoryField.MASK_ADJUST, mask.name))
            }
        }
        val keptBefore = before.map { it.id }.filter { it in new }
        val keptAfter = after.map { it.id }.filter { it in old }
        if (keptBefore != keptAfter) add(HistoryStep(HistoryField.MASK_ORDER, null))
    }

    private fun MutableList<HistoryStep>.profile(before: Profile, after: Profile) {
        if (before.base != after.base) add(HistoryStep(HistoryField.BASE_LOOK, key(after.base)))
        scalar(HistoryField.PROFILE_AMOUNT, RecipeRanges.profileAmount, before.amountPercent, after.amountPercent)
        if (before.treatment != after.treatment) add(HistoryStep(HistoryField.TREATMENT, key(after.treatment)))
    }

    private fun MutableList<HistoryStep>.light(before: Light, after: Light) {
        scalar(HistoryField.EXPOSURE, RecipeRanges.exposure, before.exposureEv, after.exposureEv)
        scalar(HistoryField.CONTRAST, RecipeRanges.contrast, before.contrast, after.contrast)
        scalar(HistoryField.HIGHLIGHTS, RecipeRanges.highlights, before.highlights, after.highlights)
        scalar(HistoryField.SHADOWS, RecipeRanges.shadows, before.shadows, after.shadows)
        scalar(HistoryField.WHITES, RecipeRanges.whites, before.whites, after.whites)
        scalar(HistoryField.BLACKS, RecipeRanges.blacks, before.blacks, after.blacks)
        tools(Recipe(light = before), Recipe(light = after), ColourToolControls.parametric + ColourToolControls.splits)
        CurveChannel.entries.forEach { channel ->
            if (before.curve[channel] != after.curve[channel]) {
                add(HistoryStep(HistoryField.valueOf("CURVE_${channel.name}"), null))
            }
        }
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
        tools(Recipe(colour = before), Recipe(colour = after), colourControls)
    }

    private val colourControls: List<ControlId> =
        ColourToolControls.all.filterNot { it in ColourToolControls.parametric || it in ColourToolControls.splits }

    /** Scalar colour-tool controls in [ids]: their history field has the control's name. */
    private fun MutableList<HistoryStep>.tools(before: Recipe, after: Recipe, ids: List<ControlId>) {
        ids.forEach { id ->
            scalar(
                HistoryField.valueOf(id.name),
                RecipeRanges[id],
                ColourToolControls.get(before, id),
                ColourToolControls.get(after, id),
            )
        }
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

    private fun MutableList<HistoryStep>.effects(before: Effects, after: Effects) {
        val r = RecipeRanges
        scalar(HistoryField.TEXTURE, r.texture, before.texture, after.texture)
        scalar(HistoryField.CLARITY, r.clarity, before.clarity, after.clarity)
        scalar(HistoryField.DEHAZE, r.dehaze, before.dehaze, after.dehaze)
        scalar(HistoryField.VIGNETTE_AMOUNT, r.vignetteAmount, before.vignetteAmount, after.vignetteAmount)
        scalar(HistoryField.VIGNETTE_MIDPOINT, r.vignetteMidpoint, before.vignetteMidpoint, after.vignetteMidpoint)
        scalar(HistoryField.VIGNETTE_ROUNDNESS, r.vignetteRoundness, before.vignetteRoundness, after.vignetteRoundness)
        scalar(HistoryField.VIGNETTE_FEATHER, r.vignetteFeather, before.vignetteFeather, after.vignetteFeather)
        scalar(
            HistoryField.VIGNETTE_HIGHLIGHTS,
            r.vignetteHighlights,
            before.vignetteHighlights,
            after.vignetteHighlights,
        )
        scalar(HistoryField.GRAIN_AMOUNT, r.grainAmount, before.grainAmount, after.grainAmount)
        scalar(HistoryField.GRAIN_SIZE, r.grainSize, before.grainSize, after.grainSize)
        scalar(HistoryField.GRAIN_ROUGHNESS, r.grainRoughness, before.grainRoughness, after.grainRoughness)
    }

    private fun MutableList<HistoryStep>.optics(before: Optics, after: Optics) {
        val r = RecipeRanges
        if (before.lensCorrections != after.lensCorrections) {
            add(HistoryStep(HistoryField.LENS_CORRECTIONS, onOff(after.lensCorrections)))
        }
        scalar(HistoryField.LENS_DISTORTION, r.lensDistortion, before.distortionPercent, after.distortionPercent)
        scalar(HistoryField.LENS_VIGNETTING, r.lensVignetting, before.vignettingPercent, after.vignettingPercent)
        // The automatic CA estimate (caRed / caBlue) changes only together with this switch.
        if (before.removeCa != after.removeCa) add(HistoryStep(HistoryField.REMOVE_CA, onOff(after.removeCa)))
        scalar(HistoryField.DEFRINGE_PURPLE_AMOUNT, r.defringePurpleAmount, before.purpleAmount, after.purpleAmount)
        if (changed(before.purpleHueLow, after.purpleHueLow) || changed(before.purpleHueHigh, after.purpleHueHigh)) {
            add(HistoryStep(HistoryField.DEFRINGE_PURPLE_HUE, hueRange(after.purpleHueLow, after.purpleHueHigh)))
        }
        scalar(HistoryField.DEFRINGE_GREEN_AMOUNT, r.defringeGreenAmount, before.greenAmount, after.greenAmount)
        if (changed(before.greenHueLow, after.greenHueLow) || changed(before.greenHueHigh, after.greenHueHigh)) {
            add(HistoryStep(HistoryField.DEFRINGE_GREEN_HUE, hueRange(after.greenHueLow, after.greenHueHigh)))
        }
    }

    private fun MutableList<HistoryStep>.transform(before: Transform, after: Transform) {
        val r = RecipeRanges
        // Upright first: its step label names the mode ("Upright: Vertical and 2 more").
        if (before.upright != after.upright) add(HistoryStep(HistoryField.UPRIGHT, key(after.upright)))
        if (before.guides != after.guides) add(HistoryStep(HistoryField.GUIDES, "${after.guides.size}"))
        scalar(HistoryField.TRANSFORM_VERTICAL, r.transformVertical, before.vertical, after.vertical)
        scalar(HistoryField.TRANSFORM_HORIZONTAL, r.transformHorizontal, before.horizontal, after.horizontal)
        scalar(HistoryField.TRANSFORM_ROTATE, r.transformRotate, before.rotate, after.rotate)
        scalar(HistoryField.TRANSFORM_ASPECT, r.transformAspect, before.aspect, after.aspect)
        scalar(HistoryField.TRANSFORM_SCALE, r.transformScale, before.scale, after.scale)
        scalar(HistoryField.TRANSFORM_OFFSET_X, r.transformOffsetX, before.offsetX, after.offsetX)
        scalar(HistoryField.TRANSFORM_OFFSET_Y, r.transformOffsetY, before.offsetY, after.offsetY)
    }

    private fun onOff(on: Boolean): String = if (on) "on" else "off"

    private fun hueRange(low: Float, high: Float): String = "${RecipeRanges.defringePurpleHueLow.format(
        minOf(low, high),
    )}–${RecipeRanges.defringePurpleHueLow.format(maxOf(low, high))}"

    private fun MutableList<HistoryStep>.geometry(before: Geometry, after: Geometry) {
        transform(before.transform, after.transform)
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
