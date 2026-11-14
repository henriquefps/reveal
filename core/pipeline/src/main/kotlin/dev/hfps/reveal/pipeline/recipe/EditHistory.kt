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

    /** A preset applied (docs/adr/0019); [StepLabel.name] is the preset's name. */
    PRESET,

    /** Black & white Auto mix (docs/adr/0013): the grey mixer computed from the photo. */
    AUTO_MIX,

    /** A tone curve preset (Linear, Medium contrast, Strong contrast) applied to the point curve. */
    CURVE_PRESET,

    /** The lens profile's corrections a new photo starts with (docs/adr/0015, "Defaults"). */
    LENS_PROFILE,

    /** A snapshot's recipe applied (docs/adr/0025); [StepLabel.name] is the snapshot's name. */
    SNAPSHOT,

    /** The first step of a virtual copy made from another version's edit (docs/adr/0025). */
    VIRTUAL_COPY,
}

/**
 * What one history step shows in the list: an optional [action] (named steps) with the [groups] it touched, and the
 * field [changes] from [RecipeDiff] (always stored, so a step can be explained in detail later). [name] names what the
 * action applied when it has a name of its own (a preset's).
 */
data class StepLabel(
    val changes: List<HistoryStep>,
    val action: HistoryAction? = null,
    val groups: List<RecipeGroup> = emptyList(),
    val name: String? = null,
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
    fun commit(
        recipe: Recipe,
        action: HistoryAction? = null,
        groups: List<RecipeGroup> = emptyList(),
        name: String? = null,
    ): EditHistory? {
        val changes = RecipeDiff.between(current, recipe)
        if (changes.isEmpty()) return null
        val kept = entries.subList(0, cursor + 1)
        return copy(entries = kept + HistoryEntry(recipe, StepLabel(changes, action, groups, name)), cursor = kept.size)
    }

    /**
     * Replaces the step at the cursor with [recipe] under the same label's action, groups and name (a preset's strength
     * changed after it was applied, docs/adr/0019), dropping the redo steps above it. When [recipe] no longer differs
     * from the step below, the step is removed. Null when there is no step at the cursor or nothing changes.
     */
    @Suppress("ReturnCount")
    fun amend(recipe: Recipe): EditHistory? {
        if (cursor < 0) return null
        val step = entries[cursor]
        if (step.recipe == recipe && cursor == entries.lastIndex) return null
        val below = copy(entries = entries.subList(0, cursor), cursor = cursor - 1)
        return below.commit(recipe, step.label.action, step.label.groups, step.label.name) ?: below
    }

    fun undo(): EditHistory? = goTo(cursor - 1)

    fun redo(): EditHistory? = goTo(cursor + 1)

    /** Jumps to the step at [position] (-1 = [base]); the steps after it stay available for redo. */
    fun goTo(position: Int): EditHistory? =
        if (position == cursor || position !in -1..entries.lastIndex) null else copy(cursor = position)
}

/** Copy/paste of recipe groups (docs/plan/product.md, "History, versions, copy/paste"). */
object RecipePaste {
    /**
     * The chooser's default: everything but geometry (a crop rarely fits another frame) and masks (a mask's shape
     * belongs to its photo: pasted only when chosen, docs/adr/0022).
     */
    val DEFAULT_GROUPS: Set<RecipeGroup> = RecipeGroup.entries.toSet() - RecipeGroup.GEOMETRY - RecipeGroup.MASKS

    /** [target] with every group in [groups] taken from [source]; the other groups of [target] stay. */
    fun paste(source: Recipe, target: Recipe, groups: Set<RecipeGroup>): Recipe =
        RecipeGroup.entries.filter { it in groups }.fold(target) { recipe, group -> group.copy(source, recipe) }
}
