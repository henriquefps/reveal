package dev.hfps.reveal.pipeline.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current recipe schema version. Bumped only by a breaking change together with a migrator (docs/adr/0003). */
const val RECIPE_VERSION: Int = 1

/**
 * The edit model of one photo version: an immutable tree where every default means "no change". Originals are never
 * modified; a photo's look is its recipe. Groups follow the develop pipeline order (docs/adr/0002). Groups without
 * operators yet are empty placeholders that reserve the JSON name and say what will live there, so adding
 * fields later is an additive, non-breaking change.
 */
@Serializable
data class Recipe(
    @SerialName("v") val v: Int = RECIPE_VERSION,
    @SerialName("profile") val profile: Profile = Profile(),
    @SerialName("light") val light: Light = Light(),
    @SerialName("colour") val colour: Colour = Colour(),
    @SerialName("effects") val effects: Effects = Effects(),
    @SerialName("detail") val detail: Detail = Detail(),
    @SerialName("optics") val optics: Optics = Optics(),
    @SerialName("geometry") val geometry: Geometry = Geometry(),
    @SerialName("masks") val masks: List<Mask> = emptyList(),
    @SerialName("heals") val heals: List<Heal> = emptyList(),
) {
    /** True when nothing is edited, i.e. this recipe renders the original. */
    fun isDefault(): Boolean = this == Recipe(v = v)

    /**
     * True when the user changed nothing: the default recipe, or the default plus only the lens corrections a new
     * photo starts with ([Optics.forNewPhoto], docs/adr/0015). The library's "edited" mark follows this.
     */
    fun isUnedited(): Boolean = isDefault() ||
        (copy(optics = Optics()).isDefault() && optics in Optics.NEW_PHOTO_DEFAULTS)

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
    /** Colour or black & white (docs/adr/0013); B&W converts with [Colour.greyMixer]. */
    @SerialName("treatment") val treatment: Treatment = Treatment.COLOUR,
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
 * Light: exposure, the five tone sliders and the tone curve. Tone sliders are -100..+100 (0 = no change); ranges and
 * steps live in [RecipeRanges]. Highlight recovery is not a field: it is an always-on reconstruction, not a user
 * control.
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
    /** The tone curve (parametric + point curves, docs/adr/0013). */
    @SerialName("curve") val curve: Curves = Curves(),
) {
    init {
        require(exposureEv.isFinite()) { "exposure must be finite" }
        require(contrast.isFinite() && highlights.isFinite() && shadows.isFinite()) { "tone values must be finite" }
        require(whites.isFinite() && blacks.isFinite()) { "tone values must be finite" }
    }
}

/**
 * Colour: white balance, vibrance and saturation, the colour mixer (HSL), colour grading and the grey mixer of the
 * black & white treatment ([Profile.treatment], docs/adr/0013).
 */
@Serializable
data class Colour(
    @SerialName("whiteBalance") val whiteBalance: WhiteBalance = WhiteBalance(),
    /** Vibrance, -100..+100 (0 = no change): saturation weighted towards the less saturated colours. */
    @SerialName("vibrance") val vibrance: Float = 0f,
    /** Saturation, -100..+100 (0 = no change). */
    @SerialName("saturation") val saturation: Float = 0f,
    /** The colour mixer (HSL, 8 bands); not applied in black & white. */
    @SerialName("mixer") val mixer: ColourMixer = ColourMixer(),
    /** Colour grading (shadows / midtones / highlights / global wheels). */
    @SerialName("grading") val grading: ColourGrading = ColourGrading(),
    /** Black & white: grey brightness per source colour, -100..+100; used only by [Treatment.BLACK_WHITE]. */
    @SerialName("greyMixer") val greyMixer: BandValues = BandValues(),
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

/**
 * Effects (slot 12 and the post-crop finish, docs/adr/0014): texture, clarity and dehaze (edge-aware, on the working
 * image after Detail), then the post-crop vignette and grain (on the geometry operator's output, so they follow the
 * crop). Every amount defaults to 0 (no change); the shaping sliders do nothing until their amount is on. Ranges and
 * steps live in [RecipeRanges].
 *
 * The JSON group was an empty object (`{}`) before these fields existed; it reads as the defaults (additive).
 */
@Serializable
data class Effects(
    /** Texture, -100..+100: medium-scale detail (skin, foliage); negative smooths it, strong edges are left alone. */
    @SerialName("texture") val texture: Float = 0f,
    /** Clarity, -100..+100: local contrast of the midtones at a large scale, edge-aware (no halos). */
    @SerialName("clarity") val clarity: Float = 0f,
    /** Dehaze, -100..+100: removes (positive) or adds (negative) atmospheric haze (dark channel prior). */
    @SerialName("dehaze") val dehaze: Float = 0f,
    /** Post-crop vignette amount, -100..+100: negative darkens the corners, positive lightens them. */
    @SerialName("vignetteAmount") val vignetteAmount: Float = 0f,
    /** Vignette midpoint, 0..100: lower reaches further towards the centre. */
    @SerialName("vignetteMidpoint") val vignetteMidpoint: Float = 50f,
    /** Vignette roundness, -100..+100: 0 follows the frame's aspect, +100 is a circle, -100 a rounded rectangle. */
    @SerialName("vignetteRoundness") val vignetteRoundness: Float = 0f,
    /** Vignette feather, 0..100: width of the transition. */
    @SerialName("vignetteFeather") val vignetteFeather: Float = 50f,
    /** Vignette highlights, 0..100: keeps bright areas bright under a darkening vignette. */
    @SerialName("vignetteHighlights") val vignetteHighlights: Float = 0f,
    /** Grain amount, 0..100 (0 = off). */
    @SerialName("grainAmount") val grainAmount: Float = 0f,
    /** Grain size, 0..100: a fraction of the picture, so the same in the preview, at 100 % and in an export. */
    @SerialName("grainSize") val grainSize: Float = 25f,
    /** Grain roughness, 0..100: 0 is smooth and regular, 100 is clumpy and irregular. */
    @SerialName("grainRoughness") val grainRoughness: Float = 50f,
) {
    init {
        require(
            listOf(
                texture, clarity, dehaze, vignetteAmount, vignetteMidpoint, vignetteRoundness, vignetteFeather,
                vignetteHighlights, grainAmount, grainSize, grainRoughness,
            ).all { it.isFinite() },
        ) { "effects values must be finite" }
    }
}

/**
 * Detail (slot 11, docs/adr/0012): luminance and colour noise reduction, then sharpening. Every amount defaults to 0
 * (no change); the other sliders shape the operator once its amount is on. Ranges and steps live in [RecipeRanges].
 * Lengths are in pixels of the **full-resolution** image, so the edit means the same at 100 % and in a proxy (the
 * operators scale them, docs/adr/0012 "Scale").
 *
 * The JSON group was an empty object (`{}`) before these fields existed; it reads as the defaults (additive).
 */
@Serializable
data class Detail(
    /** Sharpening amount, 0..150 (0 = off). */
    @SerialName("sharpenAmount") val sharpenAmount: Float = 0f,
    /** Sharpening radius in full-resolution pixels, 0.5..3.0. */
    @SerialName("sharpenRadius") val sharpenRadius: Float = 1f,
    /** Sharpening detail, 0..100: low sharpens edges only (halo suppression), high also fine texture. */
    @SerialName("sharpenDetail") val sharpenDetail: Float = 25f,
    /** Sharpening masking, 0..100: 0 sharpens everywhere, higher restricts it to edges. */
    @SerialName("sharpenMasking") val sharpenMasking: Float = 0f,
    /** Luminance noise reduction, 0..100 (0 = off). */
    @SerialName("nrLuminance") val nrLuminance: Float = 0f,
    /** Luminance detail, 0..100: higher keeps more fine detail (and more noise). */
    @SerialName("nrLuminanceDetail") val nrLuminanceDetail: Float = 50f,
    /** Luminance contrast, 0..100: higher keeps more local contrast at the coarse scales (can look blotchy). */
    @SerialName("nrLuminanceContrast") val nrLuminanceContrast: Float = 0f,
    /** Colour noise reduction, 0..100 (0 = off). */
    @SerialName("nrColour") val nrColour: Float = 0f,
    /** Colour detail, 0..100: higher protects thin colour edges (less bleeding, some speckles remain). */
    @SerialName("nrColourDetail") val nrColourDetail: Float = 50f,
    /** Colour smoothness, 0..100: higher also smooths large, low-frequency colour blotches. */
    @SerialName("nrColourSmoothness") val nrColourSmoothness: Float = 50f,
) {
    init {
        require(
            listOf(
                sharpenAmount, sharpenRadius, sharpenDetail, sharpenMasking, nrLuminance, nrLuminanceDetail,
                nrLuminanceContrast, nrColour, nrColourDetail, nrColourSmoothness,
            ).all { it.isFinite() },
        ) { "detail values must be finite" }
    }
}

/**
 * Optics (docs/adr/0015): the lens profile's corrections, lateral chromatic aberration and defringe. Every default is
 * "no change"; a **new** photo with a lens profile starts from [Optics.forNewPhoto] instead (the default of a new edit
 * lives in its first recipe, so recipes saved before these fields existed keep their pixels). The JSON group was an
 * empty object (`{}`) before these fields existed; it reads as the defaults (additive).
 *
 * Distortion and lateral CA are coordinate operators folded into the geometry resampling (slot 13); vignetting is a
 * per-pixel gain right after the camera matrix (slot 3); defringe is a neighbourhood operator on edges (slot 10b).
 */
@Serializable
data class Optics(
    /** Apply the photo's lens profile (distortion and vignetting; see [removeCa] for its CA data). */
    @SerialName("lensCorrections") val lensCorrections: Boolean = false,
    /** Distortion correction strength in percent, 0..200 (100 = the profile's). */
    @SerialName("distortion") val distortionPercent: Float = FULL,
    /** Vignetting correction strength in percent, 0..200 (100 = the profile's). */
    @SerialName("vignetting") val vignettingPercent: Float = FULL,
    /**
     * Remove lateral chromatic aberration: from the profile when it has CA data (whether or not [lensCorrections] is
     * on), else from the automatic estimate stored in [caRed] / [caBlue].
     */
    @SerialName("removeCa") val removeCa: Boolean = false,
    /** Automatic CA estimate: red's radial scale relative to green, minus one, in units of 1e-4 (derived value). */
    @SerialName("caRed") val caRed: Float = 0f,
    /** Automatic CA estimate for blue (see [caRed]). */
    @SerialName("caBlue") val caBlue: Float = 0f,
    /** Defringe purple amount, 0..20 (0 = off). */
    @SerialName("purpleAmount") val purpleAmount: Float = 0f,
    /** Purple hue range start, 0..100 (0 = blue, 100 = magenta-red). */
    @SerialName("purpleHueLow") val purpleHueLow: Float = PURPLE_LOW,
    @SerialName("purpleHueHigh") val purpleHueHigh: Float = PURPLE_HIGH,
    /** Defringe green amount, 0..20 (0 = off). */
    @SerialName("greenAmount") val greenAmount: Float = 0f,
    /** Green hue range start, 0..100 (0 = yellow-green, 100 = cyan-green). */
    @SerialName("greenHueLow") val greenHueLow: Float = GREEN_LOW,
    @SerialName("greenHueHigh") val greenHueHigh: Float = GREEN_HIGH,
) {
    init {
        require(
            listOf(
                distortionPercent, vignettingPercent, caRed, caBlue, purpleAmount, purpleHueLow, purpleHueHigh,
                greenAmount, greenHueLow, greenHueHigh,
            ).all { it.isFinite() },
        ) { "optics values must be finite" }
    }

    companion object {
        private const val FULL = 100f
        const val PURPLE_LOW: Float = 30f
        const val PURPLE_HIGH: Float = 70f
        const val GREEN_LOW: Float = 40f
        const val GREEN_HIGH: Float = 60f

        /** Every optics [forNewPhoto] can give with a profile. */
        val NEW_PHOTO_DEFAULTS: Set<Optics> = setOf(
            Optics(lensCorrections = true),
            Optics(lensCorrections = true, removeCa = true),
        )

        /**
         * The optics a new edit starts with (docs/adr/0015, "Defaults"): lens corrections on for a RAW with a profile,
         * and CA removal on when that profile carries CA data. JPEG/HEIF were usually corrected by the camera already:
         * their profile is offered but off.
         */
        fun forNewPhoto(hasProfile: Boolean, profileHasCa: Boolean, isRaw: Boolean): Optics {
            val on = hasProfile && isRaw
            return Optics(lensCorrections = on, removeCa = on && profileHasCa)
        }
    }
}

/**
 * Geometry: transform (perspective), crop, straighten, quarter turns and flips, applied as one resampling. The crop is
 * a normalised rectangle in the **transformed, un-rotated** image plane (EXIF orientation, lens corrections and
 * [transform] applied; [angle], [rotate] and the flips are not): the editor shows the full plane while cropping and
 * the crop follows the transform. Masks and heals stay in image
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
    /**
     * Constrain the crop to the image: straightening and handle drags shrink/stop the frame so no empty corner
     * enters it. An editing option stored with the edit (like [aspect]); it never changes pixels by itself.
     */
    @SerialName("constrain") val constrain: Boolean = true,
    /** Perspective and the other transform sliders, plus Upright (docs/adr/0015); applied before the crop. */
    @SerialName("transform") val transform: Transform = Transform(),
) {
    init {
        require(angle.isFinite()) { "angle must be finite" }
        require(rotate in 0..MAX_QUARTER_TURNS) { "rotate must be 0..3 quarter turns" }
    }

    private companion object {
        const val MAX_QUARTER_TURNS = 3
    }
}

/**
 * The Transform panel (docs/adr/0015): one homography of the lens-corrected image onto the crop's plane (same size),
 * about the image centre. Every default is "no change". Upright writes these sliders ([upright] records which mode
 * did, [guides] the lines of the Guided mode) so the user can fine-tune them afterwards.
 */
@Serializable
data class Transform(
    /** Vertical keystone, -100..+100: a tilt about the horizontal axis (positive = the top grows). */
    @SerialName("vertical") val vertical: Float = 0f,
    /** Horizontal keystone, -100..+100: a turn about the vertical axis (positive = the right side grows). */
    @SerialName("horizontal") val horizontal: Float = 0f,
    /** Rotation in degrees, -10..+10, positive = clockwise. */
    @SerialName("rotate") val rotate: Float = 0f,
    /** Aspect, -100..+100: positive stretches horizontally, negative vertically. */
    @SerialName("aspect") val aspect: Float = 0f,
    /** Scale in percent, 50..150 (100 = no change; above 100 zooms in). */
    @SerialName("scale") val scale: Float = 100f,
    /** Horizontal offset, -100..+100 (positive moves the image right). */
    @SerialName("offsetX") val offsetX: Float = 0f,
    /** Vertical offset, -100..+100 (positive moves the image down). */
    @SerialName("offsetY") val offsetY: Float = 0f,
    /** The Upright mode that produced the sliders (or [UprightMode.OFF] when set by hand). */
    @SerialName("upright") val upright: UprightMode = UprightMode.OFF,
    /** The Guided mode's lines, in the lens-corrected image (normalised 0..1, y down); at most four. */
    @SerialName("guides") val guides: List<GuideLine> = emptyList(),
) {
    init {
        require(listOf(vertical, horizontal, rotate, aspect, scale, offsetX, offsetY).all { it.isFinite() }) {
            "transform values must be finite"
        }
        require(guides.size <= MAX_GUIDES) { "at most $MAX_GUIDES guides" }
    }

    /** True when the transform changes no pixel ([upright] and [guides] alone do not). */
    val isIdentity: Boolean
        get() = vertical == 0f && horizontal == 0f && rotate == 0f && aspect == 0f && scale == NEUTRAL_SCALE &&
            offsetX == 0f && offsetY == 0f

    companion object {
        const val MAX_GUIDES: Int = 4
        private const val NEUTRAL_SCALE = 100f
    }
}

/** Upright modes (docs/adr/0015, "Upright"). */
@Serializable
enum class UprightMode {
    @SerialName("off")
    OFF,

    /** Balanced level and perspective corrections. */
    @SerialName("auto")
    AUTO,

    /** Level: horizontal lines level (rotation only). */
    @SerialName("level")
    LEVEL,

    /** Vertical: converging verticals made parallel and plumb. */
    @SerialName("vertical")
    VERTICAL,

    /** Full: vertical and horizontal perspective. */
    @SerialName("full")
    FULL,

    /** Guided: the user's 2-4 lines made vertical or horizontal. */
    @SerialName("guided")
    GUIDED,
}

/** A guide line from ([x0], [y0]) to ([x1], [y1]), normalised to the lens-corrected image (y down). */
@Serializable
data class GuideLine(
    @SerialName("x0") val x0: Float,
    @SerialName("y0") val y0: Float,
    @SerialName("x1") val x1: Float,
    @SerialName("y1") val y1: Float,
) {
    init {
        require(listOf(x0, y0, x1, y1).all { it.isFinite() }) { "guide must be finite" }
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

/** A heal/clone spot (source, destination, radius, in image coordinates). */
@Serializable
data class Heal(
    /** Stable identifier of the spot within the recipe. */
    @SerialName("id") val id: String = "",
)
