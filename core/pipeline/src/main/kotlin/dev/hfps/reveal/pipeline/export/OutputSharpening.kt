package dev.hfps.reveal.pipeline.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the exported picture is for: the medium decides the sharpening radius (docs/adr/0009 "Output sharpening"). */
@Serializable
enum class SharpenMedium {
    /** Phones, monitors, the web: the smallest radius. */
    @SerialName("screen")
    SCREEN,

    /** Print on matte paper (the ink spreads most): the largest radius and amount. */
    @SerialName("matte")
    MATTE,

    /** Print on glossy paper: between screen and matte. */
    @SerialName("glossy")
    GLOSSY,
}

/** How strongly the export is sharpened for its medium. */
@Serializable
enum class SharpenAmount {
    @SerialName("low")
    LOW,

    @SerialName("standard")
    STANDARD,

    @SerialName("high")
    HIGH,
}

/**
 * Sharpening for output (product.md "Export and share"): applied by the export at the **output size**, after the
 * resampling (`cpu/OutputSharpenOp`, GLSL `ExportShaders.SHARPEN_FRAG`). Not part of the recipe: an export option.
 */
@Serializable
data class OutputSharpening(
    val medium: SharpenMedium = SharpenMedium.SCREEN,
    val amount: SharpenAmount = SharpenAmount.STANDARD,
)
