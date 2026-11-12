package dev.hfps.reveal.feature.editor.presets

import androidx.compose.runtime.Immutable
import dev.hfps.reveal.data.preset.PresetLibrary
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import kotlin.math.max

/**
 * The preset applied last (docs/adr/0019 "Applying and history"): what it was applied on ([before], with the history
 * cursor at [basePosition]), its [strength] (0..2) and the recipe of its step ([applied]; null when strength 0
 * removed the step). It stays the panel's "applied preset" while the history is where it left it; any other edit, an
 * undo or a jump ends it.
 */
@Immutable
data class PresetSession(
    val preset: Preset,
    val before: Recipe,
    val basePosition: Int,
    val strength: Float,
    val applied: Recipe?,
) {
    /** True while [history] still ends at this session's step (or at its base when strength 0 removed it). */
    fun isCurrent(history: EditHistory): Boolean = if (applied == null) {
        history.cursor == basePosition && history.current == before
    } else {
        history.cursor == basePosition + 1 &&
            history.current == applied &&
            history.entries[history.cursor].label.action == HistoryAction.PRESET
    }

    /** [history] with the cursor back on the session's base (the step stays above it until the next commit). */
    fun below(history: EditHistory): EditHistory = history.goTo(basePosition) ?: history
}

/** The presets part of the editor state: the library, and the applied preset while it is current. */
@Immutable
data class PresetsState(
    val library: PresetLibrary = PresetLibrary(),
    val session: PresetSession? = null,
    /** "Create preset" is open, prefilled with these sections; null = closed. */
    val creating: CreatePresetDraft? = null,
)

/** The "Create preset" sheet's starting values. */
@Immutable
data class CreatePresetDraft(val sections: Set<dev.hfps.reveal.pipeline.preset.PresetSection>)

/** Pure steps of applying a preset to an edit history (JVM-tested). */
object PresetSteps {
    /** The recipe groups a step from [before] to [after] touches, in group order. */
    fun groups(before: Recipe, after: Recipe): List<RecipeGroup> {
        val changed = RecipeDiff.between(before, after).map { it.field.group }.toSet()
        return RecipeGroup.entries.filter { it in changed }
    }

    /**
     * Commits [recipe] as [preset]'s step on [below] (whose cursor is the base): returns the new history (unchanged
     * when the recipe equals the base: no step) and the session that describes it.
     */
    fun commit(below: EditHistory, preset: Preset, recipe: Recipe, strength: Float): Pair<EditHistory, PresetSession> {
        val base = below.current
        val history = below.commit(recipe, HistoryAction.PRESET, groups(base, recipe), preset.name)
        val session = PresetSession(preset, base, below.cursor, strength, history?.current)
        return (history ?: below) to session
    }
}

/**
 * A small copy of the editor's proxy for the presets' live previews: at most [maxSide] pixels on the long side, each
 * output pixel the mean of its source cell (no aliasing at thumbnail size). Keeps the colour metadata, the noise
 * facts and the lens profile, so a preview develops like the photo.
 */
object PreviewInput {
    const val SIDE: Int = 360

    @Suppress("NestedBlockDepth")
    fun of(input: PipelineInput, maxSide: Int = SIDE): PipelineInput {
        val long = max(input.width, input.height)
        if (long <= maxSide) return input
        val w = max(1, (input.width.toLong() * maxSide / long).toInt())
        val h = max(1, (input.height.toLong() * maxSide / long).toInt())
        val c = PipelineInput.CHANNELS
        val out = FloatArray(w * h * c)
        for (y in 0 until h) {
            val y0 = (y.toLong() * input.height / h).toInt()
            val y1 = max(y0 + 1, ((y + 1L) * input.height / h).toInt())
            for (x in 0 until w) {
                val x0 = (x.toLong() * input.width / w).toInt()
                val x1 = max(x0 + 1, ((x + 1L) * input.width / w).toInt())
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (sy in y0 until y1) {
                    val row = sy * input.width
                    for (i in (row + x0) * c until (row + x1) * c step c) {
                        r += input.linearRgb[i]
                        g += input.linearRgb[i + 1]
                        b += input.linearRgb[i + 2]
                    }
                }
                val n = ((y1 - y0) * (x1 - x0)).toDouble()
                val o = (y * w + x) * c
                out[o] = (r / n).toFloat()
                out[o + 1] = (g / n).toFloat()
                out[o + 2] = (b / n).toFloat()
            }
        }
        return PipelineInput(
            input.kind,
            w,
            h,
            out,
            input.cameraToXyz,
            input.asShotMultipliers?.copyOf(),
            input.saturation,
            input.noise,
            input.lens,
        )
    }
}

/** The chip keys that are not groups. */
internal object PresetChips {
    const val FAVOURITES = "favourites"
    const val RECENT = "recent"
}
