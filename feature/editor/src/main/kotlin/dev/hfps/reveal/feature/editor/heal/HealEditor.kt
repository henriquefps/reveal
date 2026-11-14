package dev.hfps.reveal.feature.editor.heal

import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.HealStatus
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The view model's half of the Heal mode (docs/adr/0023 "Editor"): every [HealActions] verb as an edit of the working
 * recipe; a new spot, a released drag or slider, a deletion and a new source are one history step each (undo per
 * spot). New Heal and Clone spots get their source from the renderer's patch search on the proxy. [host] is the view
 * model.
 */
@Suppress("TooManyFunctions")
internal class HealEditor(private val scope: CoroutineScope, private val host: Host) : HealActions {
    /** What the editor gives the Heal mode. */
    interface Host {
        val ui: EditorUiState

        fun updateHeal(change: (HealUiState) -> HealUiState)

        /** Per frame: the working recipe changes (not committed). */
        fun edit(change: (Recipe) -> Recipe)

        /** The working recipe becomes one history step (nothing changed: none). */
        fun commit()

        fun renderer(): GpuDevelopRenderer?
    }

    private val recipe: Recipe get() = host.ui.edit.recipe
    private val heal: HealUiState get() = host.ui.heal

    /** The spots when the mode opened (Cancel returns to them). */
    private var entry: List<Heal>? = null

    /** The proxy's width / height (image fractions are of it). */
    private val aspect: Float
        get() = (host.ui.phase as? EditorPhase.Ready)?.let { it.width.toFloat() / it.height } ?: 1f

    override fun setOpen(open: Boolean) {
        if (heal.open == open) return
        entry = if (open) recipe.heals else null
        host.updateHeal {
            it.copy(
                open = open,
                painting = null,
                selected = if (open) it.selected else null,
                aiRemove = if (open) RemoveFills.shared.models().firstOrNull() else it.aiRemove,
            )
        }
        pushView()
    }

    override fun done() {
        entry = null
    }

    override fun cancel() {
        val before = entry ?: return
        entry = null
        if (recipe.heals != before) {
            host.edit { it.copy(heals = before) }
            host.commit()
        }
        host.updateHeal { it.copy(selected = null) }
    }

    @Suppress("ReturnCount")
    override fun setKind(kind: HealKind) {
        val settings = heal.settings.copy(kind = kind)
        val darkenGone = kind != HealKind.RED_EYE && heal.setting == HealSetting.DARKEN
        host.updateHeal { it.copy(settings = settings, setting = if (darkenGone) HealSetting.SIZE else it.setting) }
        val selected = HealEdits.find(recipe, heal.selected) ?: return
        if (selected.type == kind) return
        host.edit { HealEdits.update(it, selected.id) { h -> h.copy(kind = kind.key) } }
        host.commit()
        // Heal and Clone need a source; a spot that had none (Remove, red eye) gets one.
        val changed = HealEdits.find(recipe, selected.id) ?: return
        if (changed.hasSource && changed.dx == 0f && changed.dy == 0f) newSource(selected.id)
    }

    override fun setRemoveMethod(method: String) {
        host.updateHeal { it.copy(settings = it.settings.copy(removeMethod = method)) }
        val selected = HealEdits.find(recipe, heal.selected) ?: return
        if (selected.type != HealKind.REMOVE || selected.method == method) return
        host.edit { HealEdits.update(it, selected.id) { h -> h.copy(method = method) } }
        host.commit()
    }

    override fun setSetting(setting: HealSetting) {
        host.updateHeal { it.copy(setting = setting) }
    }

    override fun setSettings(settings: HealSettings) {
        host.updateHeal { it.copy(settings = settings) }
        val selected = HealEdits.find(recipe, heal.selected) ?: return
        host.edit { HealEdits.update(it, selected.id) { h -> HealEdits.applySettings(h, settings) } }
    }

    override fun commit() = host.commit()

    override fun tap(x: Float, y: Float) {
        if (x !in 0f..1f || y !in 0f..1f) return
        add(HealEdits.circle(x, y, newSpotSettings()))
    }

    override fun strokeStart(point: StrokePoint) {
        host.updateHeal { it.copy(painting = listOf(point)) }
    }

    override fun strokeMove(points: List<StrokePoint>) {
        host.updateHeal { it.copy(painting = (it.painting ?: return@updateHeal it) + points) }
    }

    override fun strokeEnd() {
        val points = heal.painting ?: return
        host.updateHeal { it.copy(painting = null) }
        val settings = newSpotSettings()
        // A short stroke is a tap: a circle where it started.
        if (points.size < 2 || HealEdits.length(points, aspect) < settings.size) {
            tap(points.first().x, points.first().y)
            return
        }
        add(HealEdits.stroke(points, settings, aspect))
    }

    override fun strokeCancel() {
        host.updateHeal { it.copy(painting = null) }
    }

    override fun select(id: String?) {
        val spot = HealEdits.find(recipe, id)
        host.updateHeal {
            it.copy(
                selected = spot?.id,
                settings = spot?.let { s -> HealEdits.settingsOf(s, it.settings) } ?: it.settings,
            )
        }
    }

    override fun moveSource(id: String, dx: Float, dy: Float) {
        host.edit { HealEdits.update(it, id) { h -> h.copy(dx = dx, dy = dy, autoSource = false) } }
    }

    override fun moveTarget(id: String, x: Float, y: Float) {
        host.edit { HealEdits.update(it, id) { h -> HealEdits.moved(h, x, y) } }
    }

    override fun newSource(id: String) {
        val spot = HealEdits.find(recipe, id) ?: return
        scope.launch {
            val (dx, dy) = source(spot)
            host.edit { HealEdits.update(it, id) { h -> h.copy(dx = dx, dy = dy, autoSource = true) } }
            host.commit()
        }
    }

    override fun delete(id: String) {
        host.edit { HealEdits.delete(it, id) }
        host.commit()
        if (heal.selected == id) host.updateHeal { it.copy(selected = null) }
    }

    override fun setVisualise(on: Boolean) {
        host.updateHeal { it.copy(visualise = on) }
        pushView()
    }

    override fun setVisualiseStrength(strength: Float) {
        host.updateHeal { it.copy(visualiseStrength = strength) }
        pushView()
    }

    /** The renderer's Remove status: the controls show the spots still being filled and those that failed. */
    fun onStatus(status: HealStatus) {
        host.updateHeal { it.copy(pending = status.pending, failed = status.failed, fellBack = status.fellBack) }
    }

    /** Pushes the view options to the renderer (Visualise spots only while the mode is open). */
    fun pushView() {
        val state = heal
        host.renderer()?.setVisualiseSpots(state.visualiseStrength.takeIf { state.open && state.visualise })
    }

    /** After undo, redo or a reload: a selection that no longer exists is dropped. */
    fun onRecipeShown() {
        val selected = heal.selected ?: return
        if (HealEdits.find(recipe, selected) == null) host.updateHeal { it.copy(selected = null) }
    }

    /** The settings a new spot gets: Remove's method resolved (the app's model unless Patch was chosen). */
    private fun newSpotSettings(): HealSettings = heal.settings.let { it.copy(removeMethod = it.method(heal.aiRemove)) }

    /** Adds [spot] (with its automatic source when it needs one) as one step, and selects it. */
    private fun add(spot: Heal) {
        if (recipe.heals.size >= Heal.MAX_HEALS) {
            host.updateHeal { it.copy(full = true) }
            return
        }
        if (!spot.hasSource) {
            commitNew(spot)
            return
        }
        host.updateHeal { it.copy(picking = true) }
        scope.launch {
            val (dx, dy) = source(spot)
            host.updateHeal { it.copy(picking = false) }
            commitNew(spot.copy(dx = dx, dy = dy))
        }
    }

    private fun commitNew(spot: Heal) {
        val next = HealEdits.add(recipe, spot)
        if (next == null) {
            host.updateHeal { it.copy(full = true) }
            return
        }
        host.edit { next }
        host.commit()
        host.updateHeal { it.copy(selected = spot.id, full = false) }
    }

    /** The automatic source of [spot] (the renderer's search), else the fallback beside it. */
    private suspend fun source(spot: Heal): Pair<Float, Float> {
        val picked = runCatching { host.renderer()?.pickHealSource(spot.copy(dx = 0f, dy = 0f)) }.getOrNull()
        return picked ?: HealEdits.fallbackSource(spot, aspect)
    }
}
