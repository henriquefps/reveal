package dev.hfps.reveal.ml.runtime

/** Where a model actually runs. */
enum class Backend { GPU, CPU, NPU }

/**
 * One loaded model with a single float input and a single float output (every mask model of docs/adr/0021).
 * Implementations: [LiteRtEngine] on the device; tests pass fakes.
 */
interface InferenceEngine : AutoCloseable {
    /** The backend the model was compiled for (after any fallback). */
    val backend: Backend

    /** Runs the model on [input] (the flattened input tensor) and returns the flattened output tensor. */
    fun run(input: FloatArray): FloatArray
}
