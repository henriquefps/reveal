package dev.hfps.reveal.ml

import dev.hfps.reveal.ml.tensor.InputSpec
import dev.hfps.reveal.ml.tensor.OutputSpec

/**
 * Which output channels make a mask kind: their probabilities summed (a class, or several classes together),
 * [inverted] when the kind is everything but them (people = not background), then stretched from [low]..[high] to
 * 0..1 (a model whose probabilities never reach 0 or 1 still gives a clean mask; 0.5 stays 0.5 when symmetric).
 */
data class KindRule(
    val channels: List<Int>,
    val inverted: Boolean = false,
    val low: Float = 0f,
    val high: Float = 1f,
) {
    init {
        require(low < high) { "low must be below high" }
    }

    fun of(planes: Array<FloatArray>): FloatArray = of(planes.indices.associateWith { planes[it] })

    /** The kind's mask from decoded channel planes (at least this rule's [channels]). */
    fun of(planes: Map<Int, FloatArray>): FloatArray {
        val n = planes.values.first().size
        val out = FloatArray(n)
        for (c in channels) {
            val p = planes.getValue(c)
            for (i in 0 until n) out[i] += p[i]
        }
        val span = high - low
        for (i in 0 until n) {
            val v = if (inverted) 1f - out[i] else out[i]
            out[i] = ((v - low) / span).coerceIn(0f, 1f)
        }
        return out
    }
}

/**
 * A model file the build pins (`core/ml/models.json`): its stable [id] and [version] (a recipe and the mask cache
 * refer to [key]), its asset under `ml/` in the APK and the asset's SHA-256.
 */
interface PinnedModel {
    val id: ModelId
    val version: Int
    val asset: String
    val sha256: String

    /** The cache key part of a mask made by this model: `<id>@<version>`. */
    val key: String get() = "$id@$version"
}

/**
 * Everything Reveal needs to run one bundled segmentation model: its stable [id] and [version] (what a recipe and a
 * mask cache refer to, docs/adr/0021 "Masks in the recipe"), the asset file and its SHA-256 (as pinned in
 * `core/ml/models.json`), the tensors, and how its classes map to [MaskKind]s. [interactive] models take a point
 * prompt in the fourth input channel.
 */
data class ModelSpec(
    override val id: ModelId,
    override val version: Int,
    override val asset: String,
    override val sha256: String,
    val input: InputSpec,
    val output: OutputSpec,
    val kinds: Map<MaskKind, KindRule>,
    val interactive: Boolean = false,
) : PinnedModel {
    init {
        require(version > 0) { "version must be positive" }
        require(!interactive || input.extraChannel) { "an interactive model needs a prompt channel" }
        require(kinds.values.all { r -> r.channels.all { it in 0 until output.channels } }) { "channel out of range" }
    }
}

/**
 * A face landmark model (MediaPipe's face mesh, docs/adr/0021 "M4/C"): a [side] x [side] RGB crop in 0..1 in, the
 * [points] landmarks (x, y, z in crop pixels) and a face-presence logit out.
 */
data class LandmarkModelSpec(
    override val id: ModelId,
    override val version: Int,
    override val asset: String,
    override val sha256: String,
    val side: Int,
    val points: Int,
) : PinnedModel
