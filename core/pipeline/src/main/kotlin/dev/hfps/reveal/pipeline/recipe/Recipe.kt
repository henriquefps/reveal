package dev.hfps.reveal.pipeline.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current recipe schema version. Bumped only by a breaking change together with a migrator (docs/adr/0003). */
const val RECIPE_VERSION: Int = 1

/**
 * The edit model of one photo version: an immutable tree where every default means "no change". Originals are never
 * modified; a photo's look is its recipe. Groups follow the develop pipeline order (docs/adr/0002). Groups without
 * operators yet (effects, detail, optics) are empty placeholders that reserve the JSON name and say what
 * will live there, so adding fields later is an additive, non-breaking change.
 */
@Serializable
data class Recipe(
    @SerialName("v") val v: Int = RECIPE_VERSION,
    @SerialName("profile") val profile: Profile = Profile(),
    @SerialName("light") val light: Light = Light(),
    @SerialName("colour") val colour: Colour = Colour(),
    @SerialName("effects") val effects: Effects = Effects,
    @SerialName("detail") val detail: Detail = Detail,
    @SerialName("optics") val optics: Optics = Optics,
    @SerialName("geometry") val geometry: Geometry = Geometry(),
    @SerialName("masks") val masks: List<Mask> = emptyList(),
    @SerialName("heals") val heals: List<Heal> = emptyList(),
) {
    /** True when nothing is edited, i.e. this recipe renders the original. */
    fun isDefault(): Boolean = this == Recipe(v = v)

    companion object {
        /** The recipe that changes nothing. */
        val DEFAULT: Recipe = Recipe()
    }
}

/** The profile: the base look applied before the user's own adjustments, and how strongly. */
@Serializable
data class Profile(
    @SerialName("base") val base: BaseLook = BaseLook.STANDARD,
    /** Strength of the look in percent (0..200); 100 = the look as designed. Ignored by [BaseLook.STANDARD]. */
    @SerialName("amount") val amountPercent: Float = 100f,
) {
    init {
        require(amountPercent.isFinite()) { "profile amount must be finite" }
    }
}

/** Base looks (docs/plan/product.md, "Auto and profiles"). The JSON key stays `base` (no rename, no migration). */
@Serializable
enum class BaseLook {
    /** Neutral: identity up to a knee, then a smooth highlight shoulder that never hard-clips (docs/adr/0002). */
    @SerialName("standard")
    STANDARD,

    /** Neutral/flat: lower contrast, more room for grading. */
    @SerialName("neutral")
    NEUTRAL,

    @SerialName("vivid")
    VIVID,

    /** Gentle skin rendering. */
    @SerialName("portrait")
    PORTRAIT,

    @SerialName("landscape")
    LANDSCAPE,

    @SerialName("monochrome")
    MONOCHROME,

    /** Calibrated to the camera's own JPEG rendering. */
    @SerialName("camera_match")
    CAMERA_MATCH,
}

/**
 * Light: exposure and the five tone sliders. Tone sliders are -100..+100 (0 = no change); ranges and steps live in
 * [RecipeRanges]. Highlight recovery is not a field: it is an always-on reconstruction, not a user control.
 */
@Serializable
data class Light(
    /** Exposure compensation in stops (EV); a linear gain of `2^ev` on scene-linear data. 0 = no change. */
    @SerialName("exposure") val exposureEv: Float = 0f,
    @SerialName("contrast") val contrast: Float = 0f,
    @SerialName("highlights") val highlights: Float = 0f,
    @SerialName("shadows") val shadows: Float = 0f,
    @SerialName("whites") val whites: Float = 0f,
    @SerialName("blacks") val blacks: Float = 0f,
) {
    init {
        require(exposureEv.isFinite()) { "exposure must be finite" }
        require(contrast.isFinite() && highlights.isFinite() && shadows.isFinite()) { "tone values must be finite" }
        require(whites.isFinite() && blacks.isFinite()) { "tone values must be finite" }
    }
}

/** Colour: white balance, vibrance and saturation now; HSL, colour grading and B&W later. */
@Serializable
data class Colour(
    @SerialName("whiteBalance") val whiteBalance: WhiteBalance = WhiteBalance(),
    /** Vibrance, -100..+100 (0 = no change): saturation weighted towards the less saturated colours. */
    @SerialName("vibrance") val vibrance: Float = 0f,
    /** Saturation, -100..+100 (0 = no change). */
    @SerialName("saturation") val saturation: Float = 0f,
) {
    init {
        require(vibrance.isFinite() && saturation.isFinite()) { "vibrance and saturation must be finite" }
    }
}

/** White-balance presets (the chooser in the Colour panel). */
@Serializable
enum class WbMode {
    /** The camera's setting; [WhiteBalance.temperatureK] and [WhiteBalance.tint] decide whether it is overridden. */
    @SerialName("as_shot")
    AS_SHOT,

    /** Estimated from the image; the app writes the resulting temperature/tint (derived values). */
    @SerialName("auto")
    AUTO,

    @SerialName("daylight")
    DAYLIGHT,

    @SerialName("cloudy")
    CLOUDY,

    @SerialName("shade")
    SHADE,

    @SerialName("tungsten")
    TUNGSTEN,

    @SerialName("fluorescent")
    FLUORESCENT,

    @SerialName("flash")
    FLASH,

    /** Temperature/tint set by hand or by the eyedropper. */
    @SerialName("custom")
    CUSTOM,
}

/**
 * White balance as the scene illuminant the user says the light was. `null` keeps the camera's as-shot value (or
 * the as-shot tint), so the default is "no change" without the recipe needing to know the camera.
 *
 * One recipe serves RAW and display-referred (JPEG/PNG) inputs through two pairs of fields, never one reused pair:
 * [temperatureK]/[tint] are absolute (RAW only, kelvin) and [relTemperature]/[relTint] are relative -100..+100
 * (display-referred only; positive temperature = warmer, positive tint = magenta). The operator reads the pair that
 * matches the input and ignores the other; the UI shows the pair that matches the input. [mode] records which
 * preset produced the values; the presets (and Auto) write them, so rendering never depends on [mode] alone except
 * for [WbMode.AS_SHOT] (no override).
 */
@Serializable
data class WhiteBalance(
    /** Correlated colour temperature in kelvin (valid 2000..15000), or `null` for as-shot. */
    @SerialName("temperature") val temperatureK: Float? = null,
    /** Green-magenta tint (about -150..150, positive = magenta), or `null` for as-shot. */
    @SerialName("tint") val tint: Float? = null,
    @SerialName("mode") val mode: WbMode = WbMode.AS_SHOT,
    /** Relative temperature for display-referred inputs, -100..+100 (0 = no change). */
    @SerialName("relTemperature") val relTemperature: Float = 0f,
    /** Relative tint for display-referred inputs, -100..+100 (0 = no change). */
    @SerialName("relTint") val relTint: Float = 0f,
) {
    init {
        require(temperatureK == null || temperatureK.isFinite()) { "temperature must be finite" }
        require(tint == null || tint.isFinite()) { "tint must be finite" }
        require(relTemperature.isFinite() && relTint.isFinite()) { "relative white balance must be finite" }
    }
}

/** Effects (texture/clarity/dehaze, vignette, grain). Reserved. */
@Serializable
data object Effects

/** Detail (noise reduction, sharpening). Reserved. */
@Serializable
data object Detail

/** Optics (lens profile corrections, chromatic aberration, defringe). Reserved. */
@Serializable
data object Optics

/**
 * Geometry: crop, straighten, quarter turns and flips, applied as one resampling. The crop is a normalised rectangle
 * in the **oriented, un-rotated** image (EXIF orientation applied; [angle], [rotate] and the flips are not): the
 * editor shows the full image while cropping and the crop follows the transform. Masks and heals stay in image
 * coordinates (the same oriented, un-cropped, un-rotated space) so they follow geometry changes (docs/adr/0003).
 */
@Serializable
data class Geometry(
    /** The kept region, or `null` for the full image. */
    @SerialName("crop") val crop: CropRect? = null,
    /** Straighten angle in degrees, -45..+45 (0.1 step), positive = clockwise. */
    @SerialName("angle") val angle: Float = 0f,
    /** Quarter turns clockwise, 0..3. */
    @SerialName("rotate") val rotate: Int = 0,
    @SerialName("flipH") val flipH: Boolean = false,
    @SerialName("flipV") val flipV: Boolean = false,
    /** The crop frame's aspect constraint (UI state that belongs to the edit, so undo restores it). */
    @SerialName("aspect") val aspect: Aspect = Aspect(),
) {
    init {
        require(angle.isFinite()) { "angle must be finite" }
        require(rotate in 0..MAX_QUARTER_TURNS) { "rotate must be 0..3 quarter turns" }
    }

    private companion object {
        const val MAX_QUARTER_TURNS = 3
    }
}

/** Normalised crop rectangle: 0 <= left < right <= 1 and 0 <= top < bottom <= 1, y down. */
@Serializable
data class CropRect(
    @SerialName("left") val left: Float,
    @SerialName("top") val top: Float,
    @SerialName("right") val right: Float,
    @SerialName("bottom") val bottom: Float,
) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f) { "crop must lie inside the image" }
        require(left < right && top < bottom) { "crop must have a positive size" }
    }
}

/** Crop aspect presets; [CUSTOM] uses [Aspect.w] and [Aspect.h]. */
@Serializable
enum class AspectPreset {
    @SerialName("free")
    FREE,

    @SerialName("original")
    ORIGINAL,

    @SerialName("1_1")
    SQUARE,

    @SerialName("4_5")
    R4_5,

    @SerialName("5_4")
    R5_4,

    @SerialName("3_2")
    R3_2,

    @SerialName("2_3")
    R2_3,

    @SerialName("16_9")
    R16_9,

    @SerialName("9_16")
    R9_16,

    @SerialName("custom")
    CUSTOM,
}

/** The crop frame's aspect: a [preset], the custom [w]:[h] (used by [AspectPreset.CUSTOM]) and the lock. */
@Serializable
data class Aspect(
    @SerialName("preset") val preset: AspectPreset = AspectPreset.FREE,
    @SerialName("w") val w: Int = 1,
    @SerialName("h") val h: Int = 1,
    @SerialName("locked") val locked: Boolean = false,
) {
    init {
        require(w > 0 && h > 0) { "aspect ratio must be positive" }
    }
}

/** A local adjustment mask (definition in image coordinates: brush strokes, gradients, model id + parameters). */
@Serializable
data class Mask(
    /** Stable identifier of the mask within the recipe. */
    @SerialName("id") val id: String = "",
)

/** A heal/clone spot (source, destination, radius, in image coordinates). */
@Serializable
data class Heal(
    /** Stable identifier of the spot within the recipe. */
    @SerialName("id") val id: String = "",
)
