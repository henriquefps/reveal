package dev.hfps.reveal.feature.editor.edit

import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.ControlRange
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance

/** The camera's own white balance, shown by the absolute sliders while the recipe holds `null` (as shot). */
data class AsShot(val temperatureK: Float = RecipeRanges.temperature.default, val tint: Float = 0f)

/**
 * Reads and writes one scalar control of a [Recipe] by [ControlId] (pure, JVM-tested), so panels bind sliders to the
 * recipe through [RecipeRanges] alone. White balance rules (docs/adr/0003): moving temperature or tint makes the mode
 * [WbMode.CUSTOM]; resetting both back to `null` makes it [WbMode.AS_SHOT].
 */
object RecipeControls {
    /** The value the slider of [id] shows for [recipe]; the absolute white balance falls back to [asShot]. */
    @Suppress("CyclomaticComplexMethod")
    fun get(recipe: Recipe, id: ControlId, asShot: AsShot = AsShot()): Float {
        val light = recipe.light
        val colour = recipe.colour
        val wb = colour.whiteBalance
        return when (id) {
            ControlId.PROFILE_AMOUNT -> recipe.profile.amountPercent
            ControlId.EXPOSURE -> light.exposureEv
            ControlId.CONTRAST -> light.contrast
            ControlId.HIGHLIGHTS -> light.highlights
            ControlId.SHADOWS -> light.shadows
            ControlId.WHITES -> light.whites
            ControlId.BLACKS -> light.blacks
            ControlId.TEMPERATURE -> wb.temperatureK ?: asShot.temperatureK
            ControlId.TINT -> wb.tint ?: asShot.tint
            ControlId.REL_TEMPERATURE -> wb.relTemperature
            ControlId.REL_TINT -> wb.relTint
            ControlId.VIBRANCE -> colour.vibrance
            ControlId.SATURATION -> colour.saturation
            ControlId.ANGLE -> recipe.geometry.angle
        }
    }

    /** [recipe] with [id] set to [value] (clamped to its [RecipeRanges] range). */
    @Suppress("CyclomaticComplexMethod")
    fun set(recipe: Recipe, id: ControlId, value: Float): Recipe {
        val v = RecipeRanges[id].clamp(value)
        val light = recipe.light
        val colour = recipe.colour
        val wb = colour.whiteBalance
        return when (id) {
            ControlId.PROFILE_AMOUNT -> recipe.copy(profile = recipe.profile.copy(amountPercent = v))
            ControlId.EXPOSURE -> recipe.copy(light = light.copy(exposureEv = v))
            ControlId.CONTRAST -> recipe.copy(light = light.copy(contrast = v))
            ControlId.HIGHLIGHTS -> recipe.copy(light = light.copy(highlights = v))
            ControlId.SHADOWS -> recipe.copy(light = light.copy(shadows = v))
            ControlId.WHITES -> recipe.copy(light = light.copy(whites = v))
            ControlId.BLACKS -> recipe.copy(light = light.copy(blacks = v))
            ControlId.TEMPERATURE -> recipe.withWb(wb.copy(temperatureK = v, mode = WbMode.CUSTOM))
            ControlId.TINT -> recipe.withWb(wb.copy(tint = v, mode = WbMode.CUSTOM))
            ControlId.REL_TEMPERATURE -> recipe.withWb(wb.copy(relTemperature = v, mode = WbMode.CUSTOM))
            ControlId.REL_TINT -> recipe.withWb(wb.copy(relTint = v, mode = WbMode.CUSTOM))
            ControlId.VIBRANCE -> recipe.copy(colour = colour.copy(vibrance = v))
            ControlId.SATURATION -> recipe.copy(colour = colour.copy(saturation = v))
            ControlId.ANGLE -> recipe.copy(geometry = recipe.geometry.copy(angle = v))
        }
    }

    /** [recipe] with [id] back to "no change" (the absolute white balance back to as shot). */
    fun reset(recipe: Recipe, id: ControlId): Recipe {
        val wb = recipe.colour.whiteBalance
        return when (id) {
            ControlId.TEMPERATURE -> recipe.withWb(wb.copy(temperatureK = null).settled())
            ControlId.TINT -> recipe.withWb(wb.copy(tint = null).settled())
            ControlId.REL_TEMPERATURE -> recipe.withWb(wb.copy(relTemperature = 0f).settled())
            ControlId.REL_TINT -> recipe.withWb(wb.copy(relTint = 0f).settled())
            else -> set(recipe, id, RecipeRanges[id].neutral)
        }
    }

    /** True when [id] holds its "no change" value in [recipe]. */
    fun isNeutral(recipe: Recipe, id: ControlId): Boolean = when (id) {
        ControlId.TEMPERATURE -> recipe.colour.whiteBalance.temperatureK == null
        ControlId.TINT -> recipe.colour.whiteBalance.tint == null
        else -> get(recipe, id) == RecipeRanges[id].neutral
    }

    /** The white balance a mode chip writes: presets set fixed values, as-shot clears, auto uses [auto]. */
    fun withWbMode(recipe: Recipe, mode: WbMode, auto: AsShot?): Recipe {
        val wb = recipe.colour.whiteBalance
        val next = when (mode) {
            WbMode.AS_SHOT -> wb.copy(temperatureK = null, tint = null, relTemperature = 0f, relTint = 0f)
            WbMode.AUTO -> auto?.let { wb.copy(temperatureK = it.temperatureK, tint = it.tint) } ?: return recipe
            WbMode.CUSTOM -> wb
            else -> wb.copy(temperatureK = WbPresets.temperature(mode), tint = 0f)
        }
        return recipe.withWb(next.copy(mode = mode))
    }

    /** [recipe] with the profile look [look] (amount kept). */
    fun withLook(recipe: Recipe, look: BaseLook): Recipe = recipe.copy(profile = recipe.profile.copy(base = look))

    /** The range descriptor of [id] (convenience for panels). */
    fun range(id: ControlId): ControlRange = RecipeRanges[id]

    private fun Recipe.withWb(wb: WhiteBalance) = copy(colour = colour.copy(whiteBalance = wb))

    private fun WhiteBalance.settled(): WhiteBalance {
        val absoluteAsShot = temperatureK == null && tint == null
        val relativeNeutral = relTemperature == 0f && relTint == 0f
        return if (absoluteAsShot && relativeNeutral) copy(mode = WbMode.AS_SHOT) else this
    }
}

/**
 * Fixed white-balance presets (kelvin, tint 0 on the locus): the usual photographic illuminants. Values follow the
 * common raw-converter presets; tint is left on the Planckian locus because the tint scale is Reveal's own.
 */
object WbPresets {
    private const val DAYLIGHT = 5500f
    private const val CLOUDY = 6500f
    private const val SHADE = 7500f
    private const val TUNGSTEN = 2850f
    private const val FLUORESCENT = 3800f
    private const val FLASH = 5500f

    /** Kelvin of a preset [mode]; throws for modes that are not fixed presets. */
    fun temperature(mode: WbMode): Float = when (mode) {
        WbMode.DAYLIGHT -> DAYLIGHT
        WbMode.CLOUDY -> CLOUDY
        WbMode.SHADE -> SHADE
        WbMode.TUNGSTEN -> TUNGSTEN
        WbMode.FLUORESCENT -> FLUORESCENT
        WbMode.FLASH -> FLASH
        WbMode.AS_SHOT, WbMode.AUTO, WbMode.CUSTOM -> error("$mode is not a fixed preset")
    }
}
