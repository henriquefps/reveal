package dev.hfps.reveal.pipeline.recipe

/**
 * A named history step that is not described by its field changes alone (docs/adr/0003, "Committed steps"): a paste
 * of many fields reads "Paste edits", not twenty slider labels. The UI maps each to a string resource.
 */
enum class HistoryAction {
    /** Every group back to "no change". */
    RESET_ALL,

    /** One panel's group back to "no change" ([StepLabel.groups] names it). */
    RESET_GROUP,

    /** Back to the recipe the photo opens with: the default recipe plus the user's default settings. */
    AS_OPENED,

    /** Edits pasted from the clipboard ([StepLabel.groups] = the pasted groups). */
    PASTE,

    /** "Previous": the last edited photo's recipe applied ([StepLabel.groups] = the applied groups). */
    PREVIOUS,

    /** One-tap Auto (reserved for the Auto workstream). */
    AUTO,

    /** A preset applied (reserved for the presets workstream). */
    PRESET,

    /** Black & white Auto mix (docs/adr/0013): the grey mixer computed from the photo. */
    AUTO_MIX,

    /** A tone curve preset (Linear, Medium contrast, Strong contrast) applied to the point curve. */
    CURVE_PRESET,
}

/**
 * What one history step shows in the list: an optional [action] (named steps) with the [groups] it touched, and the
 * field [changes] from [RecipeDiff] (always stored, so a step can be explained in detail later).
 */
data class StepLabel(
    val changes: List<HistoryStep>,
    val action: HistoryAction? = null,
    val groups: List<RecipeGroup> = emptyList(),
)

/** One committed step: the full recipe *after* it (a snapshot, ADR 0006) and its label. */
data class HistoryEntry(val recipe: Recipe, val label: StepLabel)

/**
 * The undo/redo history of one version (docs/adr/0003 "Committed steps", ADR 0006 "History"), immutable. [entries]
 * are the committed steps in order, [cursor] the one shown (-1 = [base], the state before the first step). Undo and
 * redo move the cursor; a commit after an undo drops the steps above the cursor (a new branch). There is no limit:
 * a step is a recipe of a few KB.
 */
data class EditHistory(
    val entries: List<HistoryEntry> = emptyList(),
    val cursor: Int = entries.lastIndex,
    val base: Recipe = Recipe.DEFAULT,
) {
    init {
        require(cursor in -1..entries.lastIndex) { "cursor $cursor outside -1..${entries.lastIndex}" }
    }

    /** The recipe at the cursor. */
    val current: Recipe get() = recipeAt(cursor)

    val canUndo: Boolean get() = cursor >= 0
    val canRedo: Boolean get() = cursor < entries.lastIndex

    /** The recipe after the step at [position] (-1 = [base]). */
    fun recipeAt(position: Int): Recipe = if (position < 0) base else entries[position].recipe

    /**
     * Commits [recipe] as a new step after the cursor, labelled with its [RecipeDiff] from [current] and the
     * optional [action]/[groups]. Null when nothing user-visible changed (an empty diff is not a step, even for a
     * named action: "Reset all" on an unedited photo adds nothing).
     */
    fun commit(recipe: Recipe, action: HistoryAction? = null, groups: List<RecipeGroup> = emptyList()): EditHistory? {
        val changes = RecipeDiff.between(current, recipe)
        if (changes.isEmpty()) return null
        val kept = entries.subList(0, cursor + 1)
        return copy(entries = kept + HistoryEntry(recipe, StepLabel(changes, action, groups)), cursor = kept.size)
    }

    fun undo(): EditHistory? = goTo(cursor - 1)

    fun redo(): EditHistory? = goTo(cursor + 1)

    /** Jumps to the step at [position] (-1 = [base]); the steps after it stay available for redo. */
    fun goTo(position: Int): EditHistory? =
        if (position == cursor || position !in -1..entries.lastIndex) null else copy(cursor = position)
}

/** Copy/paste of recipe groups (docs/plan/product.md, "History, versions, copy/paste"). */
object RecipePaste {
    /** The chooser's default: everything but geometry (a crop rarely fits another frame). */
    val DEFAULT_GROUPS: Set<RecipeGroup> = RecipeGroup.entries.toSet() - RecipeGroup.GEOMETRY

    /** [target] with every group in [groups] taken from [source]; the other groups of [target] stay. */
    fun paste(source: Recipe, target: Recipe, groups: Set<RecipeGroup>): Recipe =
        RecipeGroup.entries.filter { it in groups }.fold(target) { recipe, group -> group.copy(source, recipe) }
}
