package dev.hfps.reveal.feature.editor.presets

import android.content.Context
import dev.hfps.reveal.data.preset.ImportResult
import dev.hfps.reveal.data.preset.PresetEvent
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.preset.PresetError

/** The short message of a preset import, export or creation (a toast); shared with the app's file opening. */
object PresetMessages {
    fun text(context: Context, event: PresetEvent): String? = when (event) {
        is PresetEvent.Created -> context.getString(R.string.presets_created, event.name)
        is PresetEvent.Imported -> imported(context, event.result)
        is PresetEvent.Refused -> context.getString(refused(event.error))
        PresetEvent.Exported -> context.getString(R.string.presets_exported)
        PresetEvent.ExportFailed -> context.getString(R.string.presets_export_failed)
    }

    private fun imported(context: Context, result: ImportResult): String {
        val main = when (result) {
            is ImportResult.PresetAdded -> context.getString(
                if (result.updated) R.string.presets_imported_updated else R.string.presets_imported,
                result.name,
            )

            is ImportResult.AlreadyThere -> context.getString(R.string.presets_already_there, result.name)

            is ImportResult.PackAdded -> if (result.updated) {
                context.getString(R.string.presets_imported_updated, result.name)
            } else {
                context.resources.getQuantityString(
                    R.plurals.presets_pack_added,
                    result.presets,
                    result.name,
                    result.presets,
                )
            }
        }
        val ignored = when (result) {
            is ImportResult.PresetAdded -> result.ignored.size
            is ImportResult.PackAdded -> result.ignored.size
            is ImportResult.AlreadyThere -> 0
        }
        return if (ignored == 0) {
            main
        } else {
            main + "\n" + context.resources.getQuantityString(R.plurals.presets_ignored, ignored, ignored)
        }
    }

    fun refused(error: PresetError?): Int = when (error) {
        PresetError.NEWER_VERSION -> R.string.presets_refused_newer
        PresetError.NOT_A_PRESET -> R.string.presets_refused_not_preset
        PresetError.TOO_LARGE -> R.string.presets_refused_too_large
        PresetError.INVALID_VALUE -> R.string.presets_refused_invalid
        PresetError.UNSAFE_ENTRY -> R.string.presets_refused_unsafe
        PresetError.MALFORMED -> R.string.presets_refused_malformed
        null -> R.string.presets_refused_unreadable
    }
}
