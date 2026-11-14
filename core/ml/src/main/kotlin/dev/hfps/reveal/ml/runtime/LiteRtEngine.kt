package dev.hfps.reveal.ml.runtime

import android.content.res.AssetManager
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer

/**
 * A model run by LiteRT's CompiledModel API (docs/adr/0021 "Runtime"): GPU (OpenCL, else OpenGL) when the model
 * compiles there, else the CPU (XNNPACK). [Backend.GPU] registers the CPU too, so a graph with an op the GPU lacks
 * (DeepLab's SPACE_TO_BATCH_ND) is partitioned instead of refused. The buffers are made once and reused, so a warm
 * run only copies the input in and the output out.
 */
class LiteRtEngine private constructor(private val model: CompiledModel, override val backend: Backend) :
    InferenceEngine {
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()

    override fun run(input: FloatArray): FloatArray {
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        return outputs[0].readFloat()
    }

    override fun runAll(input: FloatArray): List<FloatArray> {
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        return outputs.map { it.readFloat() }
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    /** Where the model file comes from. */
    sealed interface Source {
        class Asset(val assets: AssetManager, val path: String) : Source

        class File(val path: String) : Source
    }

    /**
     * Where the GPU keeps a model's compiled programs between loads ([dir], a directory the app owns; [key] = the
     * model's `<id>@<version>`): later loads skip most of the compile (big-LaMa: docs/adr/0024).
     */
    class GpuCache(val dir: String, val key: String)

    companion object {
        private const val TAG = "RevealMl"

        /** XNNPACK threads when a model runs on the CPU (docs/adr/0021 "M4/C", thread budget). */
        const val CPU_THREADS = 4

        /**
         * Loads [source] on the first of [prefer] that compiles it (GPU then CPU by default). A GPU that refuses the
         * model (no OpenCL/GL) falls back to the CPU; the CPU never fails for a valid model. The CPU uses
         * [cpuThreads] XNNPACK threads; [gpuCache] keeps the GPU's compiled programs on disk.
         */
        fun load(
            source: Source,
            prefer: List<Backend> = listOf(Backend.GPU, Backend.CPU),
            cpuThreads: Int = CPU_THREADS,
            gpuCache: GpuCache? = null,
        ): LiteRtEngine {
            var last: LiteRtException? = null
            for (backend in prefer) {
                try {
                    val options = CompiledModel.Options(backend.accelerators())
                    options.cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
                    if (gpuCache != null && backend == Backend.GPU) {
                        java.io.File(gpuCache.dir).mkdirs()
                        options.gpuOptions = CompiledModel.GpuOptions(
                            serializationDir = gpuCache.dir,
                            modelCacheKey = gpuCache.key,
                            serializeProgramCache = true,
                        )
                    }
                    val model = when (source) {
                        is Source.Asset -> CompiledModel.create(source.assets, source.path, options, environment)
                        is Source.File -> CompiledModel.create(source.path, options, environment)
                    }
                    return LiteRtEngine(model, backend)
                } catch (e: LiteRtException) {
                    Log.w(TAG, "model does not compile on $backend: ${e.message}")
                    last = e
                }
            }
            throw IllegalStateException("model could not be loaded on $prefer", last)
        }

        private val environment: Environment by lazy { Environment.create() }

        private fun Backend.accelerators(): Set<Accelerator> = when (this) {
            Backend.GPU -> setOf(Accelerator.GPU, Accelerator.CPU)
            Backend.CPU -> setOf(Accelerator.CPU)
            Backend.NPU -> setOf(Accelerator.NPU, Accelerator.CPU)
        }
    }
}
