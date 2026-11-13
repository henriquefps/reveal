@file:Suppress("ReturnCount")

package dev.hfps.reveal.feature.editor.masks

import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.refine.RefineDefaults
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.recipe.AiRefine
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.UUID

/** What a new mask or a new component is made of (the "+" chooser and "add to / subtract from / intersect with"). */
sealed interface NewComponent {
    /** An on-device model's kind (docs/adr/0021): the recipe records the model that makes it today. */
    data class Ai(val kind: MaskKind) : NewComponent

    data object Linear : NewComponent

    data object Radial : NewComponent

    data object Brush : NewComponent

    data object ColourRange : NewComponent

    data object LuminanceRange : NewComponent
}

/**
 * The editor's mask edits as pure functions of the recipe (docs/adr/0022 "M4/B (editor)"): the view model applies them
 * to the working recipe and commits one history step each (RecipeDiff labels them with `HistoryField.MASK_*`). Masks
 * are found by id; a missing id leaves the recipe unchanged.
 */
object MaskEdits {
    /** The most masks a recipe renders (`MaskParams.MAX_MASKS`): creating more is refused. */
    const val MAX_MASKS: Int = 32

    /** The default component of [kind]: centred shapes, a range around the mid-tones, an AI mask on today's model. */
    fun component(kind: NewComponent, op: MaskOp = MaskOp.ADD): MaskComponent = when (kind) {
        is NewComponent.Ai -> {
            val setting = RefineDefaults.of(kind.kind)
            MaskComponent.Ai(
                op = op,
                model = MaskService.modelFor(kind.kind).key,
                kind = kind.kind.name,
                refine = setting?.let { AiRefine(it.radius.toFloat(), it.eps) },
            )
        }

        NewComponent.Linear -> MaskComponent.Linear(op = op, x0 = HALF, y0 = LINEAR_START, x1 = HALF, y1 = LINEAR_END)

        NewComponent.Radial -> MaskComponent.Radial(op = op)

        NewComponent.Brush -> MaskComponent.Brush(op = op)

        NewComponent.ColourRange -> MaskComponent.ColourRange(op = op)

        NewComponent.LuminanceRange -> MaskComponent.LuminanceRange(op = op)
    }

    /** A new mask with one [kind] component, named [name], after the others; `null` when the recipe is full. */
    fun create(recipe: Recipe, kind: NewComponent, name: String, id: String = newId()): Pair<Recipe, Mask>? {
        if (recipe.masks.size >= MAX_MASKS) return null
        val mask = Mask(id = id, name = name, components = listOf(component(kind)))
        return recipe.copy(masks = recipe.masks + mask) to mask
    }

    /** The next default name: [template] (`"Mask %d"`) with the lowest number no mask uses yet. */
    fun nextName(recipe: Recipe, template: String): String {
        val used = recipe.masks.map { it.name }.toSet()
        var n = recipe.masks.size + 1
        // Lower numbers first: deleting "Mask 1" makes it available again.
        for (i in 1..n) {
            if (template.format(i) !in used) return template.format(i)
        }
        while (template.format(n) in used) n++
        return template.format(n)
    }

    fun rename(recipe: Recipe, id: String, name: String): Recipe =
        update(recipe, id) { it.copy(name = name.trim().take(MAX_NAME).ifEmpty { it.name }) }

    fun setEnabled(recipe: Recipe, id: String, enabled: Boolean): Recipe = update(recipe, id) {
        it.copy(enabled = enabled)
    }

    fun setAmount(recipe: Recipe, id: String, amount: Float): Recipe =
        update(recipe, id) { it.copy(amount = amount.coerceIn(0f, FULL)) }

    fun setAdjustment(recipe: Recipe, id: String, adjustment: LocalAdjustment, value: Float): Recipe =
        update(recipe, id) { it.copy(adjustments = it.adjustments.with(adjustment, adjustment.clamp(value))) }

    /** Every local adjustment of the mask back to 0 (the shape and the amount stay). */
    fun resetAdjustments(recipe: Recipe, id: String): Recipe =
        update(recipe, id) { it.copy(adjustments = LocalAdjustments(), amount = FULL) }

    /** A copy of the mask right after it, with a new id and [name]. */
    fun duplicate(recipe: Recipe, id: String, name: String, newId: String = newId()): Pair<Recipe, Mask>? {
        if (recipe.masks.size >= MAX_MASKS) return null
        val index = recipe.masks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val copy = recipe.masks[index].copy(id = newId, name = name)
        val masks = recipe.masks.toMutableList().apply { add(index + 1, copy) }
        return recipe.copy(masks = masks) to copy
    }

    fun delete(recipe: Recipe, id: String): Recipe = recipe.copy(masks = recipe.masks.filterNot { it.id == id })

    /** Moves the mask to position [to] (clamped). */
    fun move(recipe: Recipe, id: String, to: Int): Recipe {
        val from = recipe.masks.indexOfFirst { it.id == id }
        if (from < 0) return recipe
        val target = to.coerceIn(0, recipe.masks.lastIndex)
        if (from == target) return recipe
        val masks = recipe.masks.toMutableList()
        masks.add(target, masks.removeAt(from))
        return recipe.copy(masks = masks)
    }

    /**
     * The whole mask inverted (the ⋯ menu's Invert): with one component its invert flag; with several, De Morgan on
     * the combination of docs/adr/0022 (`add = max`, `subtract = c (1 - v)`, `intersect = c v`): the first component
     * is inverted, a later `add v` becomes `subtract v` (`1 - max(c, v)` -> `(1 - c)(1 - v)`), `subtract v` becomes
     * `add v` and `intersect v` becomes `add (1 - v)` (`1 - c v` -> `max(1 - c, 1 - v)`); applying it twice gives the
     * same coverage back (`subtract (1 - v)` is `intersect v`). Exact where the components are hard-edged or do not
     * overlap; where soft edges overlap, `max` and the product differ by at most a quarter (two 50 % values).
     */
    fun invert(recipe: Recipe, id: String): Recipe = update(recipe, id) { mask ->
        mask.copy(
            components = mask.components.mapIndexed { i, c ->
                when {
                    i == 0 -> withInvert(c, !c.invert)
                    c.op == MaskOp.ADD -> withOp(c, MaskOp.SUBTRACT)
                    c.op == MaskOp.SUBTRACT -> withOp(c, MaskOp.ADD)
                    else -> withInvert(withOp(c, MaskOp.ADD), !c.invert)
                }
            },
        )
    }

    /** Adds a [kind] component combined with [op] at the end of the mask's components. */
    fun addComponent(recipe: Recipe, id: String, kind: NewComponent, op: MaskOp): Recipe =
        update(recipe, id) { it.copy(components = it.components + component(kind, op)) }

    fun removeComponent(recipe: Recipe, id: String, index: Int): Recipe = update(recipe, id) { mask ->
        if (index !in
            mask.components.indices
        ) {
            mask
        } else {
            mask.copy(components = mask.components.filterIndexed { i, _ -> i != index })
        }
    }

    fun setComponentOp(recipe: Recipe, id: String, index: Int, op: MaskOp): Recipe =
        updateComponent(recipe, id, index) { withOp(it, op) }

    fun toggleComponentInvert(recipe: Recipe, id: String, index: Int): Recipe =
        updateComponent(recipe, id, index) { withInvert(it, !it.invert) }

    /** Replaces component [index] of the mask with [change] of it. */
    fun updateComponent(recipe: Recipe, id: String, index: Int, change: (MaskComponent) -> MaskComponent): Recipe =
        update(recipe, id) { mask ->
            if (index !in mask.components.indices) {
                mask
            } else {
                mask.copy(components = mask.components.mapIndexed { i, c -> if (i == index) change(c) else c })
            }
        }

    /** The mask with [id], or null. */
    fun find(recipe: Recipe, id: String?): Mask? = id?.let { recipe.masks.firstOrNull { m -> m.id == id } }

    fun update(recipe: Recipe, id: String, change: (Mask) -> Mask): Recipe {
        val index = recipe.masks.indexOfFirst { it.id == id }
        if (index < 0) return recipe
        val masks = recipe.masks.toMutableList()
        masks[index] = change(masks[index])
        return recipe.copy(masks = masks)
    }

    fun withOp(c: MaskComponent, op: MaskOp): MaskComponent = when (c) {
        is MaskComponent.Linear -> c.copy(op = op)
        is MaskComponent.Radial -> c.copy(op = op)
        is MaskComponent.Brush -> c.copy(op = op)
        is MaskComponent.ColourRange -> c.copy(op = op)
        is MaskComponent.LuminanceRange -> c.copy(op = op)
        is MaskComponent.Ai -> c.copy(op = op)
        is MaskComponent.Unknown -> c
    }

    fun withInvert(c: MaskComponent, invert: Boolean): MaskComponent = when (c) {
        is MaskComponent.Linear -> c.copy(invert = invert)
        is MaskComponent.Radial -> c.copy(invert = invert)
        is MaskComponent.Brush -> c.copy(invert = invert)
        is MaskComponent.ColourRange -> c.copy(invert = invert)
        is MaskComponent.LuminanceRange -> c.copy(invert = invert)
        is MaskComponent.Ai -> c.copy(invert = invert)
        is MaskComponent.Unknown -> c
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** The longest mask name kept. */
    const val MAX_NAME: Int = 60

    private const val HALF = 0.5f
    private const val LINEAR_START = 0.2f
    private const val LINEAR_END = 0.55f
    private const val FULL = 100f
}
