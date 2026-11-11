package dev.hfps.reveal.pipeline.recipe

/** Reads and writes the optics and transform controls of a [Recipe] by [ControlId] (docs/adr/0015). */
object OpticsControls {
    val optics: Set<ControlId> = RecipeRanges.optics.map { it.id }.toSet()
    val transform: Set<ControlId> = RecipeRanges.transform.map { it.id }.toSet()

    fun handles(id: ControlId): Boolean = id in optics || id in transform

    @Suppress("CyclomaticComplexMethod")
    fun get(recipe: Recipe, id: ControlId): Float {
        val o = recipe.optics
        val t = recipe.geometry.transform
        return when (id) {
            ControlId.LENS_DISTORTION -> o.distortionPercent
            ControlId.LENS_VIGNETTING -> o.vignettingPercent
            ControlId.DEFRINGE_PURPLE_AMOUNT -> o.purpleAmount
            ControlId.DEFRINGE_PURPLE_HUE_LOW -> o.purpleHueLow
            ControlId.DEFRINGE_PURPLE_HUE_HIGH -> o.purpleHueHigh
            ControlId.DEFRINGE_GREEN_AMOUNT -> o.greenAmount
            ControlId.DEFRINGE_GREEN_HUE_LOW -> o.greenHueLow
            ControlId.DEFRINGE_GREEN_HUE_HIGH -> o.greenHueHigh
            ControlId.TRANSFORM_VERTICAL -> t.vertical
            ControlId.TRANSFORM_HORIZONTAL -> t.horizontal
            ControlId.TRANSFORM_ROTATE -> t.rotate
            ControlId.TRANSFORM_ASPECT -> t.aspect
            ControlId.TRANSFORM_SCALE -> t.scale
            ControlId.TRANSFORM_OFFSET_X -> t.offsetX
            ControlId.TRANSFORM_OFFSET_Y -> t.offsetY
            else -> error("$id is not an optics or transform control")
        }
    }

    /**
     * [recipe] with [id] set to [value] (already clamped by the caller). A hue range end never crosses the other one
     * (the low end stays at or below the high end, at least [MIN_HUE_SPAN] apart). A transform slider moved by hand
     * leaves Upright ([UprightMode.OFF]: the sliders no longer are its result); its constrained crop is refitted by
     * the crop mode (`CropEditing.transform`).
     */
    @Suppress("CyclomaticComplexMethod")
    fun set(recipe: Recipe, id: ControlId, value: Float): Recipe {
        val o = recipe.optics
        val t = recipe.geometry.transform
        fun optics(next: Optics) = recipe.copy(optics = next)
        fun transform(next: Transform) =
            recipe.copy(geometry = recipe.geometry.copy(transform = next.copy(upright = UprightMode.OFF)))
        return when (id) {
            ControlId.LENS_DISTORTION -> optics(o.copy(distortionPercent = value))

            ControlId.LENS_VIGNETTING -> optics(o.copy(vignettingPercent = value))

            ControlId.DEFRINGE_PURPLE_AMOUNT -> optics(o.copy(purpleAmount = value))

            ControlId.DEFRINGE_PURPLE_HUE_LOW -> optics(
                o.copy(
                    purpleHueLow = minOf(
                        value,
                        o.purpleHueHigh - MIN_HUE_SPAN,
                    ),
                ),
            )

            ControlId.DEFRINGE_PURPLE_HUE_HIGH -> optics(
                o.copy(
                    purpleHueHigh = maxOf(
                        value,
                        o.purpleHueLow + MIN_HUE_SPAN,
                    ),
                ),
            )

            ControlId.DEFRINGE_GREEN_AMOUNT -> optics(o.copy(greenAmount = value))

            ControlId.DEFRINGE_GREEN_HUE_LOW -> optics(
                o.copy(greenHueLow = minOf(value, o.greenHueHigh - MIN_HUE_SPAN)),
            )

            ControlId.DEFRINGE_GREEN_HUE_HIGH -> optics(
                o.copy(greenHueHigh = maxOf(value, o.greenHueLow + MIN_HUE_SPAN)),
            )

            ControlId.TRANSFORM_VERTICAL -> transform(t.copy(vertical = value))

            ControlId.TRANSFORM_HORIZONTAL -> transform(t.copy(horizontal = value))

            ControlId.TRANSFORM_ROTATE -> transform(t.copy(rotate = value))

            ControlId.TRANSFORM_ASPECT -> transform(t.copy(aspect = value))

            ControlId.TRANSFORM_SCALE -> transform(t.copy(scale = value))

            ControlId.TRANSFORM_OFFSET_X -> transform(t.copy(offsetX = value))

            ControlId.TRANSFORM_OFFSET_Y -> transform(t.copy(offsetY = value))

            else -> error("$id is not an optics or transform control")
        }
    }

    /** Smallest hue range of a defringe colour (slider units). */
    const val MIN_HUE_SPAN: Float = 5f
}
