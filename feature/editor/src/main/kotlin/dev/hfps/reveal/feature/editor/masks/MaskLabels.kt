package dev.hfps.reveal.feature.editor.masks

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import dev.hfps.reveal.designsystem.components.slider.SliderSpec
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.ValueFormat
import dev.hfps.reveal.pipeline.recipe.ValueKind

/** Test tags of the Masks tool (locale-independent). */
object MaskTags {
    const val PANEL = "masks-panel"
    const val STRIP = "masks-strip"
    const val ADD = "masks-add"
    const val EMPTY = "masks-empty"
    const val HEADER = "masks-header"
    const val NAME = "masks-name"
    const val EYE = "masks-eye"
    const val OVERLAY = "masks-overlay"
    const val MENU = "masks-menu"
    const val AMOUNT = "masks-amount"
    const val RESET = "masks-reset"
    const val CHOOSER = "masks-chooser"
    const val RENAME_FIELD = "masks-rename-field"
    const val RENAME_SAVE = "masks-rename-save"
    const val AI_STATUS = "masks-ai-status"
    const val AI_RETRY = "masks-ai-retry"
    const val FULL = "masks-full"
    const val BRUSH_SIZE = "masks-brush-size"
    const val BRUSH_FEATHER = "masks-brush-feather"
    const val BRUSH_FLOW = "masks-brush-flow"
    const val BRUSH_DENSITY = "masks-brush-density"
    const val BRUSH_ERASE = "masks-brush-erase"
    const val BRUSH_AUTO = "masks-brush-auto"
    const val PRESSURE_FLOW = "masks-pressure-flow"
    const val PRESSURE_SIZE = "masks-pressure-size"
    const val PICK = "masks-pick"
    const val RANGE = "masks-range"
    const val SMOOTHNESS = "masks-smoothness"
    const val LUMA_LOW = "masks-luma-low"
    const val LUMA_HIGH = "masks-luma-high"
    const val LUMA_FALLOFF = "masks-luma-falloff"
    const val LUMA_MAP = "masks-luma-map"
    const val FEATHER = "masks-feather"
    const val INSIDE = "masks-inside"
    const val SUBJECT_ADD = "masks-subject-add"
    const val SUBJECT_SUBTRACT = "masks-subject-subtract"
    const val OVERLAY_OPTIONS = "masks-overlay-options"
    const val OVERLAY_GREY = "masks-overlay-grey"
    const val PHOTO_LAYER = "masks-photo-layer"
    const val HANDLES = "masks-handles"

    /** A mask's cell in the strip, by position. */
    fun mask(index: Int): String = "masks-mask-$index"

    /** A component row of the selected mask, by position. */
    fun component(index: Int): String = "masks-component-$index"

    fun componentInvert(index: Int): String = "masks-component-$index-invert"

    fun componentDelete(index: Int): String = "masks-component-$index-delete"

    fun componentOp(index: Int, op: MaskOp): String = "masks-component-$index-${op.name.lowercase()}"

    /** "Add to / subtract from / intersect with" of the selected mask. */
    fun combine(op: MaskOp): String = "masks-combine-${op.name.lowercase()}"

    /** A choice of the "+" chooser: `linear`, `radial`, `brush`, `colour`, `luminance` or an AI kind's name. */
    fun choice(key: String): String = "masks-choice-$key"

    fun choice(kind: NewComponent): String = choice(choiceKey(kind))

    fun choiceKey(kind: NewComponent): String = when (kind) {
        is NewComponent.Ai -> kind.kind.name.lowercase()
        NewComponent.Linear -> "linear"
        NewComponent.Radial -> "radial"
        NewComponent.Brush -> "brush"
        NewComponent.ColourRange -> "colour"
        NewComponent.LuminanceRange -> "luminance"
    }

    /** The slider of local adjustment [adjustment] of the selected mask. */
    fun adjustment(adjustment: LocalAdjustment): String = "masks-adjust-${adjustment.name.lowercase()}"

    /** A ⋯ menu item: `rename`, `duplicate`, `invert`, `earlier`, `later`, `delete`, `confirm`. */
    fun option(key: String): String = "masks-option-$key"
}

/** The local adjustments grouped like the global panels (docs/plan/ux.md: Light, Colour, Effects, Detail). */
internal enum class AdjustmentGroup(@param:StringRes val title: Int, val adjustments: List<LocalAdjustment>) {
    LIGHT(
        R.string.editor_tool_light,
        listOf(
            LocalAdjustment.EXPOSURE,
            LocalAdjustment.CONTRAST,
            LocalAdjustment.HIGHLIGHTS,
            LocalAdjustment.SHADOWS,
            LocalAdjustment.WHITES,
            LocalAdjustment.BLACKS,
        ),
    ),
    COLOUR(
        R.string.editor_tool_colour,
        listOf(
            LocalAdjustment.TEMPERATURE,
            LocalAdjustment.TINT,
            LocalAdjustment.HUE,
            LocalAdjustment.SATURATION,
            LocalAdjustment.COLOUR_HUE,
            LocalAdjustment.COLOUR_SATURATION,
        ),
    ),
    EFFECTS(
        R.string.editor_tool_effects,
        listOf(LocalAdjustment.TEXTURE, LocalAdjustment.CLARITY, LocalAdjustment.DEHAZE),
    ),
    DETAIL(R.string.editor_tool_detail, listOf(LocalAdjustment.SHARPNESS, LocalAdjustment.NOISE)),
}

internal object MaskLabels {
    @StringRes
    fun adjustment(a: LocalAdjustment): Int = when (a) {
        LocalAdjustment.TEMPERATURE -> R.string.editor_temperature
        LocalAdjustment.TINT -> R.string.editor_tint
        LocalAdjustment.EXPOSURE -> R.string.editor_exposure
        LocalAdjustment.CONTRAST -> R.string.editor_contrast
        LocalAdjustment.HIGHLIGHTS -> R.string.editor_highlights
        LocalAdjustment.SHADOWS -> R.string.editor_shadows
        LocalAdjustment.WHITES -> R.string.editor_whites
        LocalAdjustment.BLACKS -> R.string.editor_blacks
        LocalAdjustment.TEXTURE -> R.string.editor_effects_texture
        LocalAdjustment.CLARITY -> R.string.editor_effects_clarity
        LocalAdjustment.DEHAZE -> R.string.editor_effects_dehaze
        LocalAdjustment.HUE -> R.string.masks_adjust_hue
        LocalAdjustment.SATURATION -> R.string.editor_saturation
        LocalAdjustment.SHARPNESS -> R.string.masks_adjust_sharpness
        LocalAdjustment.NOISE -> R.string.masks_adjust_noise
        LocalAdjustment.COLOUR_HUE -> R.string.masks_adjust_colour_hue
        LocalAdjustment.COLOUR_SATURATION -> R.string.masks_adjust_colour_strength
    }

    /** The slider of a local adjustment: its range, centred at 0 (the colour tint's hue and strength from 0). */
    fun spec(a: LocalAdjustment): SliderSpec = when (a) {
        LocalAdjustment.EXPOSURE -> SliderSpec(a.min, a.max, 0f, EV_STEP, EV_STEP, bipolar = true)
        LocalAdjustment.COLOUR_HUE -> SliderSpec(a.min, a.max, 0f, 1f, 1f, bipolar = false)
        LocalAdjustment.COLOUR_SATURATION -> SliderSpec(a.min, a.max, 0f, 1f, 1f, bipolar = false)
        else -> SliderSpec(a.min, a.max, 0f, 1f, 1f, bipolar = true)
    }

    fun format(a: LocalAdjustment, value: Float): String = when (a) {
        LocalAdjustment.EXPOSURE -> ValueFormat.format(ValueKind.EV_SIGNED, value)
        LocalAdjustment.HUE -> ValueFormat.signed(value, 0) + "°"
        LocalAdjustment.COLOUR_HUE -> ValueFormat.format(ValueKind.HUE, value)
        LocalAdjustment.COLOUR_SATURATION -> ValueFormat.format(ValueKind.INT, value)
        else -> ValueFormat.format(ValueKind.SIGNED_INT, value)
    }

    @StringRes
    fun kind(kind: MaskKind): Int = maskKindName(kind)

    @StringRes
    fun component(c: MaskComponent): Int = when (c) {
        is MaskComponent.Linear -> R.string.masks_type_linear
        is MaskComponent.Radial -> R.string.masks_type_radial
        is MaskComponent.Brush -> R.string.masks_type_brush
        is MaskComponent.ColourRange -> R.string.masks_type_colour
        is MaskComponent.LuminanceRange -> R.string.masks_type_luminance
        is MaskComponent.Ai -> AiMasksKinds.of(c)?.let(::maskKindName) ?: R.string.masks_type_ai_unknown
        is MaskComponent.Unknown -> R.string.masks_type_unknown
    }

    @StringRes
    fun choice(kind: NewComponent): Int = when (kind) {
        is NewComponent.Ai -> maskKindName(kind.kind)
        NewComponent.Linear -> R.string.masks_type_linear
        NewComponent.Radial -> R.string.masks_type_radial
        NewComponent.Brush -> R.string.masks_type_brush
        NewComponent.ColourRange -> R.string.masks_type_colour
        NewComponent.LuminanceRange -> R.string.masks_type_luminance
    }

    fun icon(c: MaskComponent): ImageVector = when (c) {
        is MaskComponent.Linear -> PhosphorRegular.Gradient
        is MaskComponent.Radial -> PhosphorRegular.CircleDashed
        is MaskComponent.Brush -> PhosphorRegular.PaintBrush
        is MaskComponent.ColourRange -> PhosphorRegular.Eyedropper
        is MaskComponent.LuminanceRange -> PhosphorRegular.DropHalf
        is MaskComponent.Ai -> AiMasksKinds.of(c)?.let(::kindIcon) ?: PhosphorRegular.WarningCircle
        is MaskComponent.Unknown -> PhosphorRegular.WarningCircle
    }

    fun icon(kind: NewComponent): ImageVector = when (kind) {
        is NewComponent.Ai -> kindIcon(kind.kind)
        NewComponent.Linear -> PhosphorRegular.Gradient
        NewComponent.Radial -> PhosphorRegular.CircleDashed
        NewComponent.Brush -> PhosphorRegular.PaintBrush
        NewComponent.ColourRange -> PhosphorRegular.Eyedropper
        NewComponent.LuminanceRange -> PhosphorRegular.DropHalf
    }

    fun kindIcon(kind: MaskKind): ImageVector = when (kind) {
        MaskKind.SUBJECT -> PhosphorRegular.UserFocus
        MaskKind.BACKGROUND -> PhosphorRegular.Mountains
        MaskKind.SKY -> PhosphorRegular.CloudSun
        MaskKind.PEOPLE -> PhosphorRegular.Users
        MaskKind.EYES, MaskKind.IRISES, MaskKind.LIPS, MaskKind.TEETH, MaskKind.EYEBROWS -> PhosphorRegular.Smiley
        else -> PhosphorRegular.User
    }

    @StringRes
    fun op(op: MaskOp): Int = when (op) {
        MaskOp.ADD -> R.string.masks_op_add
        MaskOp.SUBTRACT -> R.string.masks_op_subtract
        MaskOp.INTERSECT -> R.string.masks_op_intersect
    }

    @StringRes
    fun combine(op: MaskOp): Int = when (op) {
        MaskOp.ADD -> R.string.masks_combine_add
        MaskOp.SUBTRACT -> R.string.masks_combine_subtract
        MaskOp.INTERSECT -> R.string.masks_combine_intersect
    }

    fun opIcon(op: MaskOp): ImageVector = when (op) {
        MaskOp.ADD -> PhosphorRegular.Unite
        MaskOp.SUBTRACT -> PhosphorRegular.Subtract
        MaskOp.INTERSECT -> PhosphorRegular.Intersect
    }

    private const val EV_STEP = 0.01f
}

/** The AI kinds of the recipe's components. */
internal object AiMasksKinds {
    fun of(c: MaskComponent.Ai): MaskKind? = MaskKind.entries.firstOrNull { it.name == c.kind }

    /** The people parts, offered under "People" (docs/adr/0021). */
    val people: List<MaskKind> = listOf(
        MaskKind.PEOPLE,
        MaskKind.HAIR,
        MaskKind.FACE_SKIN,
        MaskKind.BODY_SKIN,
        MaskKind.SKIN,
        MaskKind.CLOTHES,
        MaskKind.EYES,
        MaskKind.IRISES,
        MaskKind.LIPS,
        MaskKind.TEETH,
        MaskKind.EYEBROWS,
    )

    /** The top-level AI choices. */
    val top: List<MaskKind> = listOf(MaskKind.SUBJECT, MaskKind.SKY, MaskKind.BACKGROUND)
}

/** A mask kind's name ("Sky", "Hair"…). */
@StringRes
internal fun maskKindName(kind: MaskKind): Int = when (kind) {
    MaskKind.SUBJECT -> R.string.masks_kind_subject
    MaskKind.BACKGROUND -> R.string.masks_kind_background
    MaskKind.SKY -> R.string.masks_kind_sky
    MaskKind.PEOPLE -> R.string.masks_kind_people
    MaskKind.FACE_SKIN -> R.string.masks_kind_face_skin
    MaskKind.BODY_SKIN -> R.string.masks_kind_body_skin
    MaskKind.SKIN -> R.string.masks_kind_skin
    MaskKind.HAIR -> R.string.masks_kind_hair
    MaskKind.CLOTHES -> R.string.masks_kind_clothes
    MaskKind.EYES -> R.string.masks_kind_eyes
    MaskKind.IRISES -> R.string.masks_kind_irises
    MaskKind.LIPS -> R.string.masks_kind_lips
    MaskKind.TEETH -> R.string.masks_kind_teeth
    MaskKind.EYEBROWS -> R.string.masks_kind_eyebrows
}
