package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.R

/** String resources of the built-in presets' names and groups (docs/adr/0020), by [BuiltInPresets] key. */
object BuiltInPresetNames {
    /** The name of the built-in preset [key], `null` when it is not one. */
    fun name(key: String): Int? = names[key]

    /** The name of [group]. */
    fun group(group: BuiltInPresets.Group): Int = groups.getValue(group.key)

    private val names: Map<String, Int> = mapOf(
        "smart_natural_portrait" to R.string.preset_smart_natural_portrait,
        "smart_bright_outdoor" to R.string.preset_smart_bright_outdoor,
        "smart_moody_indoor" to R.string.preset_smart_moody_indoor,
        "smart_clean_auto" to R.string.preset_smart_clean_auto,
        "natural_clean" to R.string.preset_natural_clean,
        "natural_soft" to R.string.preset_natural_soft,
        "natural_crisp" to R.string.preset_natural_crisp,
        "natural_airy" to R.string.preset_natural_airy,
        "portrait_soft_skin" to R.string.preset_portrait_soft_skin,
        "portrait_warm_glow" to R.string.preset_portrait_warm_glow,
        "portrait_studio" to R.string.preset_portrait_studio,
        "portrait_moody" to R.string.preset_portrait_moody,
        "portrait_golden" to R.string.preset_portrait_golden,
        "landscape_vivid" to R.string.preset_landscape_vivid,
        "landscape_clear_sky" to R.string.preset_landscape_clear_sky,
        "landscape_lush" to R.string.preset_landscape_lush,
        "landscape_golden_hour" to R.string.preset_landscape_golden_hour,
        "landscape_misty" to R.string.preset_landscape_misty,
        "bw_classic" to R.string.preset_bw_classic,
        "bw_high_contrast" to R.string.preset_bw_high_contrast,
        "bw_soft_matte" to R.string.preset_bw_soft_matte,
        "bw_dramatic_sky" to R.string.preset_bw_dramatic_sky,
        "bw_selenium" to R.string.preset_bw_selenium,
        "bw_sepia" to R.string.preset_bw_sepia,
        "film_warm" to R.string.preset_film_warm,
        "film_cool" to R.string.preset_film_cool,
        "film_faded" to R.string.preset_film_faded,
        "film_cinematic" to R.string.preset_film_cinematic,
        "film_vintage" to R.string.preset_film_vintage,
        "matte_soft" to R.string.preset_matte_soft,
        "matte_warm" to R.string.preset_matte_warm,
        "matte_cool" to R.string.preset_matte_cool,
        "warm_sunlit" to R.string.preset_warm_sunlit,
        "warm_cosy" to R.string.preset_warm_cosy,
        "cool_crisp" to R.string.preset_cool_crisp,
        "cool_nordic" to R.string.preset_cool_nordic,
    )

    private val groups: Map<String, Int> = mapOf(
        "smart" to R.string.preset_group_smart,
        "natural" to R.string.preset_group_natural,
        "portrait" to R.string.preset_group_portrait,
        "landscape" to R.string.preset_group_landscape,
        "bw" to R.string.preset_group_bw,
        "film" to R.string.preset_group_film,
        "matte" to R.string.preset_group_matte,
        "warm" to R.string.preset_group_warm,
        "cool" to R.string.preset_group_cool,
    )
}
