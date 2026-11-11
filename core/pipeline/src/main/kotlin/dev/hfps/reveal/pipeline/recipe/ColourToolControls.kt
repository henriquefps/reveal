package dev.hfps.reveal.pipeline.recipe

/**
 * Reads and writes the scalar controls of the tone curve, colour mixer, grey mixer and colour grading by [ControlId]
 * (docs/adr/0013). Shared by the editor's slider binding and [RecipeDiff].
 */
object ColourToolControls {
    private val bands = ColourBand.entries

    /** The parametric curve controls in panel order (highlights first, as the curve reads from the top). */
    val parametric: List<ControlId> = listOf(
        ControlId.CURVE_HIGHLIGHTS,
        ControlId.CURVE_LIGHTS,
        ControlId.CURVE_DARKS,
        ControlId.CURVE_SHADOWS,
    )

    /** The split controls, low to high. */
    val splits: List<ControlId> = listOf(
        ControlId.CURVE_SPLIT_SHADOWS,
        ControlId.CURVE_SPLIT_MIDTONES,
        ControlId.CURVE_SPLIT_HIGHLIGHTS,
    )

    /** Every control this object handles, in pipeline order. */
    val all: List<ControlId> = ControlId.entries.filter { handles(it) }

    /** True for the controls of this object. */
    fun handles(id: ControlId): Boolean = id.ordinal >= ControlId.CURVE_SHADOWS.ordinal

    /** The band of a mixer or grey control, `null` for the others. */
    fun band(id: ControlId): ColourBand? {
        val name = id.name
        val prefix = name.substringBefore('_')
        if (prefix !in setOf("HUE", "SAT", "LUM", "GREY")) return null
        return ColourBand.valueOf(name.substringAfter('_'))
    }

    /** The mixer property of a mixer control, `null` for the others. */
    fun mixerProperty(id: ControlId): MixerProperty? = when (id.name.substringBefore('_')) {
        "HUE" -> MixerProperty.HUE
        "SAT" -> MixerProperty.SATURATION
        "LUM" -> MixerProperty.LUMINANCE
        else -> null
    }

    /** The region of a grading wheel control (`GRADE_<region>_<property>`), `null` for the others. */
    fun region(id: ControlId): GradeRegion? {
        val parts = id.name.split('_')
        if (parts.size != GRADE_PARTS || parts[0] != "GRADE") return null
        return GradeRegion.valueOf(parts[1])
    }

    /** The property of a grading wheel control. */
    private fun gradeProperty(id: ControlId): MixerProperty = when (id.name.substringAfterLast('_')) {
        "HUE" -> MixerProperty.HUE
        "SAT" -> MixerProperty.SATURATION
        else -> MixerProperty.LUMINANCE
    }

    /** The value of [id] in [recipe]. */
    @Suppress("CyclomaticComplexMethod")
    fun get(recipe: Recipe, id: ControlId): Float {
        val curve = recipe.light.curve.parametric
        val colour = recipe.colour
        val band = band(id)
        val region = region(id)
        return when {
            band != null -> when (mixerProperty(id)) {
                MixerProperty.HUE -> colour.mixer.hue[band]
                MixerProperty.SATURATION -> colour.mixer.saturation[band]
                MixerProperty.LUMINANCE -> colour.mixer.luminance[band]
                null -> colour.greyMixer[band]
            }

            region != null -> {
                val wheel = colour.grading[region]
                when (gradeProperty(id)) {
                    MixerProperty.HUE -> wheel.hue
                    MixerProperty.SATURATION -> wheel.saturation
                    MixerProperty.LUMINANCE -> wheel.luminance
                }
            }

            else -> when (id) {
                ControlId.CURVE_SHADOWS -> curve.shadows
                ControlId.CURVE_DARKS -> curve.darks
                ControlId.CURVE_LIGHTS -> curve.lights
                ControlId.CURVE_HIGHLIGHTS -> curve.highlights
                ControlId.CURVE_SPLIT_SHADOWS -> curve.splitShadows
                ControlId.CURVE_SPLIT_MIDTONES -> curve.splitMidtones
                ControlId.CURVE_SPLIT_HIGHLIGHTS -> curve.splitHighlights
                ControlId.GRADE_BLENDING -> colour.grading.blending
                ControlId.GRADE_BALANCE -> colour.grading.balance
                else -> error("$id is not a colour tool control")
            }
        }
    }

    /** [recipe] with [id] set to [value] (clamped to its range; splits kept in order). */
    @Suppress("CyclomaticComplexMethod")
    fun set(recipe: Recipe, id: ControlId, value: Float): Recipe {
        val v = RecipeRanges[id].clamp(value)
        val colour = recipe.colour
        val band = band(id)
        val region = region(id)
        return when {
            band != null -> {
                val mixer = colour.mixer
                val next = when (mixerProperty(id)) {
                    MixerProperty.HUE -> colour.copy(mixer = mixer.copy(hue = mixer.hue.with(band, v)))

                    MixerProperty.SATURATION ->
                        colour.copy(mixer = mixer.copy(saturation = mixer.saturation.with(band, v)))

                    MixerProperty.LUMINANCE ->
                        colour.copy(mixer = mixer.copy(luminance = mixer.luminance.with(band, v)))

                    null -> colour.copy(greyMixer = colour.greyMixer.with(band, v))
                }
                recipe.copy(colour = next)
            }

            region != null -> {
                val wheel = colour.grading[region]
                val next = when (gradeProperty(id)) {
                    MixerProperty.HUE -> wheel.copy(hue = v)
                    MixerProperty.SATURATION -> wheel.copy(saturation = v)
                    MixerProperty.LUMINANCE -> wheel.copy(luminance = v)
                }
                recipe.copy(colour = colour.copy(grading = colour.grading.with(region, next)))
            }

            id == ControlId.GRADE_BLENDING -> recipe.copy(
                colour = colour.copy(grading = colour.grading.copy(blending = v)),
            )

            id == ControlId.GRADE_BALANCE -> recipe.copy(
                colour = colour.copy(grading = colour.grading.copy(balance = v)),
            )

            else -> recipe.withParametric(setParametric(recipe.light.curve.parametric, id, v))
        }
    }

    private fun setParametric(p: ParametricCurve, id: ControlId, v: Float): ParametricCurve = when (id) {
        ControlId.CURVE_SHADOWS -> p.copy(shadows = v)

        ControlId.CURVE_DARKS -> p.copy(darks = v)

        ControlId.CURVE_LIGHTS -> p.copy(lights = v)

        ControlId.CURVE_HIGHLIGHTS -> p.copy(highlights = v)

        // A split stays between its neighbours (SPLIT_GAP apart), so dragging one never crosses another.
        ControlId.CURVE_SPLIT_SHADOWS -> p.copy(splitShadows = v.coerceIn(MIN_SPLIT, p.splitMidtones - SPLIT_GAP))

        ControlId.CURVE_SPLIT_MIDTONES ->
            p.copy(splitMidtones = v.coerceIn(p.splitShadows + SPLIT_GAP, p.splitHighlights - SPLIT_GAP))

        ControlId.CURVE_SPLIT_HIGHLIGHTS ->
            p.copy(splitHighlights = v.coerceIn(p.splitMidtones + SPLIT_GAP, MAX_SPLIT))

        else -> error("$id is not a parametric curve control")
    }

    private fun Recipe.withParametric(p: ParametricCurve): Recipe =
        copy(light = light.copy(curve = light.curve.copy(parametric = p)))

    /** [recipe] with the colour mixer's [property] of every band in [deltas] moved by its delta (clamped). */
    fun nudgeMixer(recipe: Recipe, property: MixerProperty, deltas: FloatArray): Recipe =
        bands.fold(recipe) { r, band ->
            val d = deltas[band.ordinal]
            if (d == 0f) r else set(r, ControlId.mixer(property, band), get(r, ControlId.mixer(property, band)) + d)
        }

    /** [recipe] with every band's grey mixer value moved by its delta in [deltas] (clamped). */
    fun nudgeGrey(recipe: Recipe, deltas: FloatArray): Recipe = bands.fold(recipe) { r, band ->
        val d = deltas[band.ordinal]
        if (d == 0f) r else set(r, ControlId.grey(band), get(r, ControlId.grey(band)) + d)
    }

    private const val GRADE_PARTS = 3
    private const val SPLIT_GAP = 5f
    private const val MIN_SPLIT = 5f
    private const val MAX_SPLIT = 95f
}
