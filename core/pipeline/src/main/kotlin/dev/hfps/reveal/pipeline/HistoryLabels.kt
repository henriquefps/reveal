package dev.hfps.reveal.pipeline

import android.content.Context
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.StepLabel

/** Resolves [HistoryStep]s to localised history-list labels ("Exposure +0.70"). */
object HistoryLabels {
    /**
     * The history-list title of a committed step: its action's name ("Paste edits", "Reset Light"), else its only
     * change ("Exposure +0.70"), else the first change and how many more.
     */
    fun format(context: Context, label: StepLabel): String {
        val action = label.action
        val more = label.changes.size - 1
        return when {
            action != null -> action(context, action, label.groups, label.name)

            more < 0 -> ""

            more == 0 -> format(context, label.changes[0])

            else -> context.resources.getQuantityString(
                R.plurals.history_and_more,
                more,
                format(context, label.changes[0]),
                more,
            )
        }
    }

    /** The changes of [label] one per line (the detail under a named step). */
    fun details(context: Context, label: StepLabel): List<String> = label.changes.map { format(context, it) }

    /** The localised name of a copy/paste group ("Light"). */
    fun groupName(context: Context, group: RecipeGroup): String = context.getString(GROUP_NAMES.getValue(group))

    /** The string resource of a copy/paste group's name, for composables. */
    fun groupNameRes(group: RecipeGroup): Int = GROUP_NAMES.getValue(group)

    private fun action(context: Context, action: HistoryAction, groups: List<RecipeGroup>, name: String?): String =
        when {
            action == HistoryAction.PRESET && name != null ->
                context.getString(R.string.history_action_preset_named, name)

            action == HistoryAction.SNAPSHOT && name != null ->
                context.getString(R.string.history_action_snapshot_named, name)

            else -> action(context, action, groups)
        }

    private fun action(context: Context, action: HistoryAction, groups: List<RecipeGroup>): String = when (action) {
        HistoryAction.RESET_GROUP -> context.getString(
            R.string.history_action_reset_group,
            groups.joinToString(", ") { groupName(context, it) },
        )

        else -> context.getString(ACTION_LABELS.getValue(action))
    }

    private val ACTION_LABELS: Map<HistoryAction, Int> = mapOf(
        HistoryAction.RESET_ALL to R.string.history_action_reset_all,
        HistoryAction.AS_OPENED to R.string.history_action_as_opened,
        HistoryAction.PASTE to R.string.history_action_paste,
        HistoryAction.PREVIOUS to R.string.history_action_previous,
        HistoryAction.AUTO to R.string.history_action_auto,
        HistoryAction.PRESET to R.string.history_action_preset,
        HistoryAction.AUTO_MIX to R.string.history_action_auto_mix,
        HistoryAction.CURVE_PRESET to R.string.history_action_curve_preset,
        HistoryAction.LENS_PROFILE to R.string.history_action_lens_profile,
        HistoryAction.SNAPSHOT to R.string.history_action_snapshot,
        HistoryAction.VIRTUAL_COPY to R.string.history_action_virtual_copy,
    )

    private val GROUP_NAMES: Map<RecipeGroup, Int> = mapOf(
        RecipeGroup.PROFILE to R.string.recipe_group_profile,
        RecipeGroup.LIGHT to R.string.recipe_group_light,
        RecipeGroup.COLOUR to R.string.recipe_group_colour,
        RecipeGroup.EFFECTS to R.string.recipe_group_effects,
        RecipeGroup.DETAIL to R.string.recipe_group_detail,
        RecipeGroup.OPTICS to R.string.recipe_group_optics,
        RecipeGroup.GEOMETRY to R.string.recipe_group_geometry,
        RecipeGroup.MASKS to R.string.recipe_group_masks,
        RecipeGroup.HEALS to R.string.recipe_group_heals,
    )

    /** The label of [step] in the app's current language. */
    fun format(context: Context, step: HistoryStep): String {
        val value = step.value
        val field = step.field
        return when {
            // Fields whose "back to default" is its own sentence.
            value == null && field in AS_SHOT_LABELS -> context.getString(AS_SHOT_LABELS.getValue(field))

            field == HistoryField.CROP ->
                context.getString(if (value == null) R.string.history_crop_reset else R.string.history_crop)

            field in ENUM_FIELDS -> {
                val (prefix, label) = ENUM_FIELDS.getValue(field)
                context.getString(label, named(context, prefix, value))
            }

            field in NO_VALUE_LABELS -> context.getString(NO_VALUE_LABELS.getValue(field))

            field.ordinal > HistoryField.TREATMENT.ordinal -> colourTool(context, field, value)

            else -> context.getString(VALUE_LABELS.getValue(field), value)
        }
    }

    /** The localised name of an enum key (`history_value_<prefix>_<key>`); the key itself if there is no resource. */
    private fun named(context: Context, prefix: String, key: String?): String? =
        key?.let { VALUE_NAMES["${prefix}_$it"]?.let(context::getString) ?: it }

    private val AS_SHOT_LABELS: Map<HistoryField, Int> = mapOf(
        HistoryField.TEMPERATURE to R.string.history_temperature_as_shot,
        HistoryField.TINT to R.string.history_tint_as_shot,
    )

    private val NO_VALUE_LABELS: Map<HistoryField, Int> = mapOf(
        HistoryField.FLIP_H to R.string.history_flip_h,
        HistoryField.FLIP_V to R.string.history_flip_v,
        HistoryField.MASK_ORDER to R.string.history_mask_order,
    )

    private val ENUM_FIELDS: Map<HistoryField, Pair<String, Int>> = mapOf(
        HistoryField.BASE_LOOK to ("look" to R.string.history_base_look),
        HistoryField.WB_MODE to ("wb" to R.string.history_wb_mode),
        HistoryField.ASPECT to ("aspect" to R.string.history_aspect),
        HistoryField.TREATMENT to ("treatment" to R.string.history_treatment),
        HistoryField.LENS_CORRECTIONS to ("toggle" to R.string.history_lens_corrections),
        HistoryField.REMOVE_CA to ("toggle" to R.string.history_remove_ca),
        HistoryField.UPRIGHT to ("upright" to R.string.history_upright),
        HistoryField.HEAL_ADD to ("heal" to R.string.history_heal_add),
        HistoryField.HEAL_DELETE to ("heal" to R.string.history_heal_delete),
        HistoryField.HEAL_SOURCE to ("heal" to R.string.history_heal_source),
        HistoryField.HEAL_EDIT to ("heal" to R.string.history_heal_edit),
    )

    /** The label of a tone curve, colour mixer, grey mixer or colour grading step (docs/adr/0013). */
    private fun colourTool(context: Context, field: HistoryField, value: String?): String {
        val name = field.name
        val parts = name.split('_')
        val band = runCatching { ColourBand.valueOf(parts.last()) }.getOrNull()
        return when {
            name.startsWith("CURVE_") && field in CURVE_LABELS -> context.getString(CURVE_LABELS.getValue(field), value)

            name.startsWith("CURVE_") -> context.getString(
                R.string.history_point_curve,
                context.getString(channelName(CurveChannel.valueOf(name.removePrefix("CURVE_")))),
            )

            band != null && parts[0] == "GREY" ->
                context.getString(R.string.history_grey_mix, context.getString(bandName(band)), value)

            band != null -> context.getString(propertyLabel(parts[0]), context.getString(bandName(band)), value)

            field == HistoryField.GRADE_BLENDING -> context.getString(R.string.history_grade_blending, value)

            field == HistoryField.GRADE_BALANCE -> context.getString(R.string.history_grade_balance, value)

            else -> context.getString(
                propertyLabel(parts[2]),
                context.getString(regionName(GradeRegion.valueOf(parts[1]))),
                value,
            )
        }
    }

    private fun propertyLabel(property: String): Int = when (property) {
        "HUE" -> R.string.history_band_hue
        "SAT" -> R.string.history_band_saturation
        else -> R.string.history_band_luminance
    }

    private val CURVE_LABELS: Map<HistoryField, Int> = mapOf(
        HistoryField.CURVE_SHADOWS to R.string.history_curve_shadows,
        HistoryField.CURVE_DARKS to R.string.history_curve_darks,
        HistoryField.CURVE_LIGHTS to R.string.history_curve_lights,
        HistoryField.CURVE_HIGHLIGHTS to R.string.history_curve_highlights,
        HistoryField.CURVE_SPLIT_SHADOWS to R.string.history_curve_split_shadows,
        HistoryField.CURVE_SPLIT_MIDTONES to R.string.history_curve_split_midtones,
        HistoryField.CURVE_SPLIT_HIGHLIGHTS to R.string.history_curve_split_highlights,
    )

    /** Localised colour band name ("Red"), shared with the editor's chips. */
    fun bandName(band: ColourBand): Int = when (band) {
        ColourBand.RED -> R.string.colour_band_red
        ColourBand.ORANGE -> R.string.colour_band_orange
        ColourBand.YELLOW -> R.string.colour_band_yellow
        ColourBand.GREEN -> R.string.colour_band_green
        ColourBand.AQUA -> R.string.colour_band_aqua
        ColourBand.BLUE -> R.string.colour_band_blue
        ColourBand.PURPLE -> R.string.colour_band_purple
        ColourBand.MAGENTA -> R.string.colour_band_magenta
    }

    /** Localised colour grading wheel name ("Shadows"), shared with the editor. */
    fun regionName(region: GradeRegion): Int = when (region) {
        GradeRegion.SHADOWS -> R.string.grade_region_shadows
        GradeRegion.MIDTONES -> R.string.grade_region_midtones
        GradeRegion.HIGHLIGHTS -> R.string.grade_region_highlights
        GradeRegion.GLOBAL -> R.string.grade_region_global
    }

    /** Localised point-curve channel name ("RGB", "Red"), shared with the editor's chips. */
    fun channelName(channel: CurveChannel): Int = when (channel) {
        CurveChannel.RGB -> R.string.curve_channel_rgb
        CurveChannel.RED -> R.string.curve_channel_red
        CurveChannel.GREEN -> R.string.curve_channel_green
        CurveChannel.BLUE -> R.string.curve_channel_blue
    }

    /** Fields shown as "<name> <signed value>". */
    private val VALUE_LABELS: Map<HistoryField, Int> = mapOf(
        HistoryField.EXPOSURE to R.string.history_exposure,
        HistoryField.CONTRAST to R.string.history_contrast,
        HistoryField.HIGHLIGHTS to R.string.history_highlights,
        HistoryField.SHADOWS to R.string.history_shadows,
        HistoryField.WHITES to R.string.history_whites,
        HistoryField.BLACKS to R.string.history_blacks,
        HistoryField.PROFILE_AMOUNT to R.string.history_profile_amount,
        HistoryField.TEMPERATURE to R.string.history_temperature,
        HistoryField.TINT to R.string.history_tint,
        HistoryField.REL_TEMPERATURE to R.string.history_rel_temperature,
        HistoryField.REL_TINT to R.string.history_rel_tint,
        HistoryField.VIBRANCE to R.string.history_vibrance,
        HistoryField.SATURATION to R.string.history_saturation,
        HistoryField.ANGLE to R.string.history_angle,
        HistoryField.ROTATE to R.string.history_rotate,
        HistoryField.SHARPEN_AMOUNT to R.string.history_sharpen_amount,
        HistoryField.SHARPEN_RADIUS to R.string.history_sharpen_radius,
        HistoryField.SHARPEN_DETAIL to R.string.history_sharpen_detail,
        HistoryField.SHARPEN_MASKING to R.string.history_sharpen_masking,
        HistoryField.NR_LUMINANCE to R.string.history_nr_luminance,
        HistoryField.NR_LUMINANCE_DETAIL to R.string.history_nr_luminance_detail,
        HistoryField.NR_LUMINANCE_CONTRAST to R.string.history_nr_luminance_contrast,
        HistoryField.NR_COLOUR to R.string.history_nr_colour,
        HistoryField.NR_COLOUR_DETAIL to R.string.history_nr_colour_detail,
        HistoryField.NR_COLOUR_SMOOTHNESS to R.string.history_nr_colour_smoothness,
        HistoryField.TEXTURE to R.string.history_texture,
        HistoryField.CLARITY to R.string.history_clarity,
        HistoryField.DEHAZE to R.string.history_dehaze,
        HistoryField.VIGNETTE_AMOUNT to R.string.history_vignette_amount,
        HistoryField.VIGNETTE_MIDPOINT to R.string.history_vignette_midpoint,
        HistoryField.VIGNETTE_ROUNDNESS to R.string.history_vignette_roundness,
        HistoryField.VIGNETTE_FEATHER to R.string.history_vignette_feather,
        HistoryField.VIGNETTE_HIGHLIGHTS to R.string.history_vignette_highlights,
        HistoryField.GRAIN_AMOUNT to R.string.history_grain_amount,
        HistoryField.GRAIN_SIZE to R.string.history_grain_size,
        HistoryField.GRAIN_ROUGHNESS to R.string.history_grain_roughness,
        HistoryField.LENS_DISTORTION to R.string.history_lens_distortion,
        HistoryField.LENS_VIGNETTING to R.string.history_lens_vignetting,
        HistoryField.DEFRINGE_PURPLE_AMOUNT to R.string.history_defringe_purple_amount,
        HistoryField.DEFRINGE_PURPLE_HUE to R.string.history_defringe_purple_hue,
        HistoryField.DEFRINGE_GREEN_AMOUNT to R.string.history_defringe_green_amount,
        HistoryField.DEFRINGE_GREEN_HUE to R.string.history_defringe_green_hue,
        HistoryField.GUIDES to R.string.history_guides,
        HistoryField.TRANSFORM_VERTICAL to R.string.history_transform_vertical,
        HistoryField.TRANSFORM_HORIZONTAL to R.string.history_transform_horizontal,
        HistoryField.TRANSFORM_ROTATE to R.string.history_transform_rotate,
        HistoryField.TRANSFORM_ASPECT to R.string.history_transform_aspect,
        HistoryField.TRANSFORM_SCALE to R.string.history_transform_scale,
        HistoryField.TRANSFORM_OFFSET_X to R.string.history_transform_offset_x,
        HistoryField.TRANSFORM_OFFSET_Y to R.string.history_transform_offset_y,
        HistoryField.MASK_ADD to R.string.history_mask_add,
        HistoryField.MASK_REMOVE to R.string.history_mask_remove,
        HistoryField.MASK_RENAME to R.string.history_mask_rename,
        HistoryField.MASK_SHOW to R.string.history_mask_show,
        HistoryField.MASK_HIDE to R.string.history_mask_hide,
        HistoryField.MASK_SHAPE to R.string.history_mask_shape,
        HistoryField.MASK_ADJUST to R.string.history_mask_adjust,
    )

    private val VALUE_NAMES: Map<String, Int> = mapOf(
        "look_standard" to R.string.history_value_look_standard,
        "look_neutral" to R.string.history_value_look_neutral,
        "look_vivid" to R.string.history_value_look_vivid,
        "look_portrait" to R.string.history_value_look_portrait,
        "look_landscape" to R.string.history_value_look_landscape,
        "look_monochrome" to R.string.history_value_look_monochrome,
        "look_camera_match" to R.string.history_value_look_camera_match,
        "wb_as_shot" to R.string.history_value_wb_as_shot,
        "wb_auto" to R.string.history_value_wb_auto,
        "wb_daylight" to R.string.history_value_wb_daylight,
        "wb_cloudy" to R.string.history_value_wb_cloudy,
        "wb_shade" to R.string.history_value_wb_shade,
        "wb_tungsten" to R.string.history_value_wb_tungsten,
        "wb_fluorescent" to R.string.history_value_wb_fluorescent,
        "wb_flash" to R.string.history_value_wb_flash,
        "wb_custom" to R.string.history_value_wb_custom,
        "aspect_free" to R.string.history_value_aspect_free,
        "aspect_original" to R.string.history_value_aspect_original,
        "aspect_square" to R.string.history_value_aspect_square,
        "aspect_r4_5" to R.string.history_value_aspect_r4_5,
        "aspect_r5_4" to R.string.history_value_aspect_r5_4,
        "aspect_r3_2" to R.string.history_value_aspect_r3_2,
        "aspect_r2_3" to R.string.history_value_aspect_r2_3,
        "aspect_r16_9" to R.string.history_value_aspect_r16_9,
        "aspect_r9_16" to R.string.history_value_aspect_r9_16,
        "aspect_custom" to R.string.history_value_aspect_custom,
        "treatment_colour" to R.string.history_value_treatment_colour,
        "treatment_black_white" to R.string.history_value_treatment_black_white,
        "toggle_on" to R.string.history_value_toggle_on,
        "toggle_off" to R.string.history_value_toggle_off,
        "upright_off" to R.string.upright_off,
        "upright_auto" to R.string.upright_auto,
        "upright_level" to R.string.upright_level,
        "upright_vertical" to R.string.upright_vertical,
        "upright_full" to R.string.upright_full,
        "upright_guided" to R.string.upright_guided,
        "heal_heal" to R.string.history_value_heal_heal,
        "heal_clone" to R.string.history_value_heal_clone,
        "heal_remove" to R.string.history_value_heal_remove,
        "heal_redEye" to R.string.history_value_heal_red_eye,
    )
}
