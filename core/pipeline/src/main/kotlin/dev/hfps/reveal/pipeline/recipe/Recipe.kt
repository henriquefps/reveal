package dev.hfps.reveal.pipeline.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current recipe schema version. Bumped only by a breaking change together with a migrator (docs/adr/0003). */
const val RECIPE_VERSION: Int = 1

/**
 * The edit model of one photo version: an immutable tree where every default means "no change". Originals are never
 * modified; a photo's look is its recipe. Groups follow the develop pipeline order (docs/adr/0002). Only the groups
 * with operators implemented have fields; the others are empty placeholders that reserve the JSON name and say what
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
    @SerialName("geometry") val geometry: Geometry = Geometry,
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

/** The base look (profile): the tone mapping applied before the user's own adjustments. */
@Serializable
data class Profile(@SerialName("base") val base: BaseLook = BaseLook.STANDARD)

/** Base looks. Only [STANDARD] exists in M0; camera-matching and creative profiles come with M1/M2. */
@Serializable
enum class BaseLook {
    /** Neutral: identity up to a knee, then a smooth highlight shoulder that never hard-clips (docs/adr/0002). */
    @SerialName("standard")
    STANDARD,
}

/** Light: exposure now; contrast, highlights/shadows, whites/blacks and curves later. */
@Serializable
data class Light(
    /** Exposure compensation in stops (EV); a linear gain of `2^ev` on scene-linear data. 0 = no change. */
    @SerialName("exposure") val exposureEv: Float = 0f,
) {
    init {
        require(exposureEv.isFinite()) { "exposure must be finite" }
    }
}

/** Colour: white balance now; vibrance, saturation, HSL, colour grading and B&W later. */
@Serializable
data class Colour(@SerialName("whiteBalance") val whiteBalance: WhiteBalance = WhiteBalance())

/**
 * White balance as the scene illuminant the user says the light was. `null` keeps the camera's as-shot value (or
 * the as-shot tint), so the default is "no change" without the recipe needing to know the camera.
 */
@Serializable
data class WhiteBalance(
    /** Correlated colour temperature in kelvin (valid 2000..15000), or `null` for as-shot. */
    @SerialName("temperature") val temperatureK: Float? = null,
    /** Green-magenta tint (about -150..150, positive = magenta), or `null` for as-shot. */
    @SerialName("tint") val tint: Float? = null,
) {
    init {
        require(temperatureK == null || temperatureK.isFinite()) { "temperature must be finite" }
        require(tint == null || tint.isFinite()) { "tint must be finite" }
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

/** Geometry (crop, rotate, transform as one resampling, in image coordinates). Reserved. */
@Serializable
data object Geometry

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
