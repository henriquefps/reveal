package dev.hfps.reveal.ml.inpaint

import dev.hfps.reveal.ml.ModelId
import dev.hfps.reveal.ml.PinnedModel

/**
 * An inpainting model (docs/adr/0024): a fixed [side] x [side] input of NHWC (rgb x (1 - hole), hole), sRGB-encoded
 * in 0..1 with hole = 1 where the model paints, and an NHWC rgb output in 0..1 at the same size.
 */
data class InpaintModelSpec(
    override val id: ModelId,
    override val version: Int,
    override val asset: String,
    override val sha256: String,
    val side: Int,
) : PinnedModel {
    init {
        require(version > 0 && side > 0) { "version and side must be positive" }
    }

    /** The Remove method that names this model in a recipe (docs/adr/0023): `model:<id>@<version>`. */
    val method: String get() = "$METHOD_PREFIX$key"

    companion object {
        /** Prefix of a model's Remove method (docs/adr/0023 `Heal.METHOD_MODEL_PREFIX`). */
        const val METHOD_PREFIX: String = "model:"
    }
}

/** The bundled inpainting models (pinned in `core/ml/models.json`, kept in step by `MaskModelsTest`). */
object InpaintModels {
    /**
     * big-LaMa (Suvorov et al. 2022, Apache-2.0 weights, Places2), converted by `scripts/ml/convert_big_lama.py` (the
     * Fourier units as DFT matrix products: every op on LiteRT's GPU) and stored with int8 convolution weights by
     * `scripts/ml/convert_int8_weights.py`. S24 Ultra GPU: 272 ms warm, 3.8 s to compile (docs/adr/0024).
     */
    val BIG_LAMA = InpaintModelSpec(
        id = ModelId("big-lama"),
        version = 1,
        asset = "big-lama-v1.tflite",
        sha256 = "797cfbc5703550c7e40d3582d9b572e33f9147795f4489278158b15a71d40df3",
        side = 512,
    )

    /** Every bundled inpainting model. */
    val bundled: List<InpaintModelSpec> = listOf(BIG_LAMA)

    /** The model a Remove `method` names, or null when this app does not have it. */
    fun of(method: String): InpaintModelSpec? = bundled.firstOrNull { it.method == method }
}
