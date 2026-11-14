package dev.hfps.reveal.feature.editor.heal

import androidx.compose.runtime.Immutable
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.StrokePoint

/**
 * The settings new spots get, and the selected spot's controls show (docs/adr/0023 "Editor"): the [kind], the [size]
 * (radius as a fraction of the image's long edge), [feather], [opacity] and the red-eye [darken], all in percent.
 */
@Immutable
data class HealSettings(
    val kind: HealKind = HealKind.HEAL,
    val size: Float = DEFAULT_SIZE,
    val feather: Float = DEFAULT_FEATHER,
    val opacity: Float = FULL,
    val darken: Float = DEFAULT_DARKEN,
) {
    /** One step of the `[` / `]` keys: about 15 % bigger or smaller, within [MIN_SIZE]..[MAX_SIZE]. */
    fun resized(steps: Int): HealSettings {
        var s = size
        repeat(kotlin.math.abs(steps)) { s = if (steps > 0) s * SIZE_STEP else s / SIZE_STEP }
        return copy(size = s.coerceIn(MIN_SIZE, MAX_SIZE))
    }

    companion object {
        const val DEFAULT_SIZE: Float = 0.012f
        const val MIN_SIZE: Float = 0.001f
        const val MAX_SIZE: Float = 0.15f
        const val DEFAULT_FEATHER: Float = 50f
        const val DEFAULT_DARKEN: Float = 50f
        const val FULL: Float = 100f
        private const val SIZE_STEP = 1.15f
    }
}

/** Which slider the compact (portrait) controls show. */
enum class HealSetting { SIZE, FEATHER, OPACITY, DARKEN }

/** The Heal mode's state (what is not in the recipe). */
@Immutable
data class HealUiState(
    /** The mode is open: the spots' overlay and the photo gestures work only then. */
    val open: Boolean = false,
    /** The selected spot's id. */
    val selected: String? = null,
    val settings: HealSettings = HealSettings(),
    /** The slider the compact controls show. */
    val setting: HealSetting = HealSetting.SIZE,
    /** Visualise spots (a high-pass view to find sensor dust) and its strength 0..100. */
    val visualise: Boolean = false,
    val visualiseStrength: Float = DEFAULT_VISUALISE,
    /** Remove spots whose fill is being computed, and those that failed (by id). */
    val pending: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    /** The stroke being painted (image fractions), drawn over the photo until it ends. */
    val painting: List<StrokePoint>? = null,
    /** A new spot is waiting for its automatic source. */
    val picking: Boolean = false,
    /** The last spot refused because the recipe has the most spots it may hold. */
    val full: Boolean = false,
) {
    companion object {
        const val DEFAULT_VISUALISE: Float = 50f
    }
}

/** What the Heal mode's controls and photo layer can ask for; defaults do nothing (screenshot tests, previews). */
@Suppress("TooManyFunctions")
interface HealActions {
    /** The mode opened (`true`) or closed. */
    fun setOpen(open: Boolean) {}

    /** Done: the spots stay (each was its own history step). */
    fun done() {}

    /** Cancel (and back): the spots return to what they were when the mode opened (one step). */
    fun cancel() {}

    /** The kind of new spots, and of the selected one (one step). */
    fun setKind(kind: HealKind) {}

    fun setSetting(setting: HealSetting) {}

    /** Per drag frame: a setting of new spots and of the selected one (not committed). */
    fun setSettings(settings: HealSettings) {}

    /** A slider was released: the working recipe becomes one step. */
    fun commit() {}

    /** A tap on the photo at image fraction ([x], [y]): a new circle spot (its source picked automatically). */
    fun tap(x: Float, y: Float) {}

    /** A stroke starts at [point] (image fractions). */
    fun strokeStart(point: StrokePoint) {}

    /** The stroke in progress gets [points]. */
    fun strokeMove(points: List<StrokePoint>) {}

    /** The stroke ends: a new stroke spot (one step). */
    fun strokeEnd() {}

    /** The stroke is dropped (a second finger arrived). */
    fun strokeCancel() {}

    fun select(id: String?) {}

    /** Per frame: the spot's source offset (image fractions); not committed. */
    fun moveSource(id: String, dx: Float, dy: Float) {}

    /** Per frame: a circle spot's centre (image fractions), its source moving with it; not committed. */
    fun moveTarget(id: String, x: Float, y: Float) {}

    /** Picks the selected spot's source again (one step). */
    fun newSource(id: String) {}

    fun delete(id: String) {}

    fun setVisualise(on: Boolean) {}

    /** Per drag frame. */
    fun setVisualiseStrength(strength: Float) {}

    companion object {
        val NONE: HealActions = object : HealActions {}
    }
}
