package dev.hfps.reveal.ml

/**
 * Stable identifier of an on-device model (for example `subject-segmenter-v1`). Masks store it in the recipe, so an
 * id is never reused for a different model: a new model gets a new id.
 */
@JvmInline
value class ModelId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Model id must be lowercase letters, digits and dashes: '$value'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    }
}
