package dev.hfps.reveal.data.preset

import android.content.Context
import dev.hfps.reveal.pipeline.preset.BuiltInPresetNames
import dev.hfps.reveal.pipeline.preset.BuiltInPresets

/**
 * Reveal's built-in presets (docs/adr/0020) as the library sees them: [BuiltInPresets] grouped by style, names
 * from the string resources of the current locale (read on every call, so a language change shows at once). Group
 * ids are as stable as the presets' ([BuiltInPresets.idOf] of `group_<key>`).
 */
class AppBuiltInPresets(private val context: Context) : BuiltInPresetSource {
    override fun groups(): List<BuiltInGroup> = BuiltInPresets.all.groupBy { it.group }.map { (group, presets) ->
        BuiltInGroup(
            uuid = BuiltInPresets.idOf("group_${group.key}"),
            name = context.getString(BuiltInPresetNames.group(group)),
            presets = presets.map { p ->
                val name = BuiltInPresetNames.name(p.key)?.let(context::getString) ?: p.preset.name
                p.preset.copy(name = name, group = context.getString(BuiltInPresetNames.group(group)))
            },
        )
    }
}
