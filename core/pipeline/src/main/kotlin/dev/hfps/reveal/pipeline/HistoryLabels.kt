package dev.hfps.reveal.pipeline

import android.content.Context
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep

/** Resolves [HistoryStep]s to localised history-list labels ("Exposure +0.70"). */
object HistoryLabels {
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
    )

    private val ENUM_FIELDS: Map<HistoryField, Pair<String, Int>> = mapOf(
        HistoryField.BASE_LOOK to ("look" to R.string.history_base_look),
        HistoryField.WB_MODE to ("wb" to R.string.history_wb_mode),
        HistoryField.ASPECT to ("aspect" to R.string.history_aspect),
    )

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
    )
}
