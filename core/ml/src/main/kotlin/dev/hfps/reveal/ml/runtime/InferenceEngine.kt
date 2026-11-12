package dev.hfps.reveal.ml.runtime

/** Where a model actually runs. */
enum class Backend { GPU, CPU, NPU }

/**
 * One loaded model with a single float input (every mask model of docs/adr/0021). Implementations: [LiteRtEngine] on
 * the device; tests pass fakes.
 */
interface InferenceEngine : AutoCloseable {
    /** The backend the model was compiled for (after any fallback). */
    val backend: Backend

    /** Runs the model on [input] (the flattened input tensor) and returns its first output, flattened. */
    fun run(input: FloatArray): FloatArray = runAll(input).first()

    /** Runs the model on [input] and returns every output tensor, flattened, in the graph's output order. */
    fun runAll(input: FloatArray): List<FloatArray>
}
