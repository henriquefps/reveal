package dev.hfps.reveal.pipeline

import android.content.Context
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep

/** Resolves [HistoryStep]s to localised history-list labels ("Exposure +0.70"). */
object HistoryLabels {
    /** The label of [step] in the app's current language. */
    fun format(context: Context, step: HistoryStep): String = when (step.field) {
        HistoryField.EXPOSURE -> context.getString(R.string.history_exposure, step.value)

        HistoryField.TEMPERATURE ->
            if (step.value == null) {
                context.getString(R.string.history_temperature_as_shot)
            } else {
                context.getString(R.string.history_temperature, step.value)
            }

        HistoryField.TINT ->
            if (step.value == null) {
                context.getString(R.string.history_tint_as_shot)
            } else {
                context.getString(R.string.history_tint, step.value)
            }

        HistoryField.BASE_LOOK -> context.getString(R.string.history_base_look, step.value)
    }
}
