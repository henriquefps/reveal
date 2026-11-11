package dev.hfps.reveal.pipeline.recipe

/**
 * The copy/paste groups of the chooser (docs/plan/product.md): every recipe field belongs to exactly one. Profile is
 * its own group (the base look and its amount travel together, separate from the light and colour sliders).
 */
enum class RecipeGroup {
    PROFILE,
    LIGHT,
    COLOUR,
    EFFECTS,
    DETAIL,
    OPTICS,
    GEOMETRY,
    MASKS,
    HEALS,
    ;

    /** [target] with this group's fields taken from [source]; everything else of [target] is untouched. */
    fun copy(source: Recipe, target: Recipe): Recipe = when (this) {
        PROFILE -> target.copy(profile = source.profile)
        LIGHT -> target.copy(light = source.light)
        COLOUR -> target.copy(colour = source.colour)
        EFFECTS -> target.copy(effects = source.effects)
        DETAIL -> target.copy(detail = source.detail)
        OPTICS -> target.copy(optics = source.optics)
        GEOMETRY -> target.copy(geometry = source.geometry)
        MASKS -> target.copy(masks = source.masks)
        HEALS -> target.copy(heals = source.heals)
    }

    /** [target] with this group reset to "no change". */
    fun reset(target: Recipe): Recipe = copy(Recipe.DEFAULT, target)
}
