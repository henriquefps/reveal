package dev.hfps.reveal.ml.service

import android.content.res.AssetManager
import dev.hfps.reveal.ml.LandmarkModelSpec
import dev.hfps.reveal.ml.Mask
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.MaskPrompt
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.ModelSpec
import dev.hfps.reveal.ml.PinnedModel
import dev.hfps.reveal.ml.SegmentationModel
import dev.hfps.reveal.ml.face.FaceLandmarker
import dev.hfps.reveal.ml.face.FaceLandmarks
import dev.hfps.reveal.ml.face.FaceParts
import dev.hfps.reveal.ml.refine.GuidedRefiner
import dev.hfps.reveal.ml.refine.RefineDefaults
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.InferenceEngine
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One request for an AI mask (docs/adr/0021 "M4/C", "MaskService").
 *
 * - [photoKey]: the photo's identity for the cache (its content fingerprint, `FileFingerprint`).
 * - [image]: the model input, the photo's upright display image independent of the recipe, at most
 *   [MaskService.MAX_INPUT] px on its long side (ADR 0021 "Model input"). The caller renders it; the mask comes back
 *   at its size, in its (upright image) coordinates.
 * - [inputVersion]: the version of the caller's model-input rendering; part of the cache key, so changing how the
 *   input is rendered never serves stale masks.
 * - [model]: the `<id>@<version>` a recipe stores; null = the current model for [kind] ([MaskService.modelFor]).
 * - [prompts]: taps (normalised image coordinates) for the subject and the background: added to the automatic
 *   seeds when [autoSeeds], else the only seeds.
 * - [refine]: the edge refinement ([RefineDefaults.of] by default; null = none).
 * - [cues]: the photo's faces and saliency when the caller already has them (the editor's `PhotoFeatures`).
 */
class MaskRequest(
    val photoKey: String,
    val image: MlImage,
    val kind: MaskKind,
    val inputVersion: Int = 1,
    val model: String? = null,
    val prompts: List<MaskPrompt> = emptyList(),
    val autoSeeds: Boolean = true,
    val refine: RefineDefaults.Setting? = RefineDefaults.of(kind),
    val cues: PhotoCues? = null,
)

/**
 * A mask from [MaskService]: [mask] at the request image's size (refined when asked), [raw] the model's own output
 * before refinement (at the model's resolution, or the image's for face parts) for a renderer that refines on the GPU
 * at its own size ([dev.hfps.reveal.ml.refine.GpuGuidedRefiner]), the [model] key, and where it came from.
 */
class MaskOutput(
    val kind: MaskKind,
    val model: String,
    val mask: Mask,
    val raw: Mask,
    val refine: RefineDefaults.Setting?,
    val fromCache: Boolean,
    val backend: Backend?,
    val modelMs: Double,
    val refineMs: Double,
)

/** Loads a pinned model's runtime (the APK's assets on the device; fakes in tests). */
fun interface EngineLoader {
    fun load(model: PinnedModel): InferenceEngine

    companion object {
        /** From the APK's assets with LiteRT: the GPU first, then the CPU ([LiteRtEngine.load]). */
        fun assets(assets: AssetManager): EngineLoader = EngineLoader { model ->
            LiteRtEngine.load(LiteRtEngine.Source.Asset(assets, MaskModels.assetPath(model)))
        }
    }
}

/**
 * Every AI mask kind through one entry point (docs/adr/0021 "M4/C"): subject (MagicTouch at automatic seeds plus
 * taps), background (its inverse), sky (the converted DeepLab ADE20K), people, hair, face skin, body skin, skin and
 * clothes (Selfie Multiclass), eyes, irises, lips, teeth and eyebrows (face landmarks + polygons).
 *
 * - **Cache** ([cache], optional): the model's unrefined output and the refined mask are stored separately, keyed by
 *   photo + model `<id>@<version>` + kind + input version and size + seeds (+ the refinement for the refined one), so
 *   a refinement change never re-runs the model, and one Selfie Multiclass run fills all six of its kinds. Every
 *   mask is quantised to 8 bits whether it comes from the cache or not, so both paths give identical results.
 * - **Models**: loaded lazily ([loader]: GPU, else CPU) and one at a time (each compiled model holds 30-140 MB on the
 *   GPU; ADR 0021's PSS column), except the face landmark model (2.6 MB), which stays with it. [release] frees them
 *   (the editor closing, `onTrimMemory`).
 * - **Threads**: one request runs at a time ([Mutex]); the work runs on [dispatcher]; LiteRT's CPU fallback uses
 *   [LiteRtEngine.CPU_THREADS] threads; the CPU refinement at ≤ 1024 px is parallel over rows (tens of ms).
 * - **Cancellation**: checked between the steps (cues, each seed's run, refinement); a model run itself is not
 *   interruptible (≤ 50 ms on the GPU, up to ~0.5 s on the CPU).
 */
class MaskService(
    private val loader: EngineLoader,
    private val cache: MaskDiskCache? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cuesOf: (MlImage) -> PhotoCues = PhotoCues::of,
) : AutoCloseable {
    private val mutex = Mutex()
    private class Loaded<T : AutoCloseable>(val model: PinnedModel, val runtime: T, val backend: Backend)

    private var loaded: Loaded<SegmentationModel>? = null
    private var landmarker: Loaded<FaceLandmarker>? = null
    private var lastCues: Pair<String, PhotoCues>? = null
    private var lastFaces: Pair<String, List<FaceLandmarks>>? = null

    /** The mask of [request]. Throws [IllegalArgumentException] for a model that cannot make the kind. */
    suspend fun mask(request: MaskRequest): MaskOutput = withContext(dispatcher) {
        require(max(request.image.width, request.image.height) <= MAX_INPUT) { "model input above $MAX_INPUT px" }
        val model = resolve(request)
        // The background is the subject inverted (refinement commutes with 1 - p: the guided filter is linear).
        val kind = if (request.kind == MaskKind.BACKGROUND) MaskKind.SUBJECT else request.kind
        val rawKey = rawKey(request, model, kind)
        val refinedKey = request.refine?.let { refinedKey(rawKey, it) }
        val out = mutex.withLock {
            cachedOutput(request, model, kind, rawKey, refinedKey) ?: compute(request, model, kind, rawKey, refinedKey)
        }
        if (request.kind == MaskKind.BACKGROUND) {
            MaskOutput(
                MaskKind.BACKGROUND, out.model, out.mask.inverted(), out.raw.inverted(), out.refine, out.fromCache,
                out.backend, out.modelMs, out.refineMs,
            )
        } else {
            out
        }
    }

    @Suppress("ReturnCount")
    private fun cachedOutput(
        request: MaskRequest,
        model: PinnedModel,
        kind: MaskKind,
        rawKey: String,
        refinedKey: String?,
    ): MaskOutput? {
        val c = cache ?: return null
        val raw = c.get(rawKey) ?: return null
        val refined = if (refinedKey ==
            null
        ) {
            raw.resized(request.image.width, request.image.height)
        } else {
            c.get(refinedKey)
        }
        return refined?.let { MaskOutput(kind, model.key, it, raw, request.refine, true, null, 0.0, 0.0) }
    }

    private suspend fun compute(
        request: MaskRequest,
        model: PinnedModel,
        kind: MaskKind,
        rawKey: String,
        refinedKey: String?,
    ): MaskOutput {
        val t0 = System.nanoTime()
        var backend: Backend? = null
        val raw = cache?.get(rawKey) ?: run {
            val made: Map<MaskKind, Mask> = when (model) {
                is LandmarkModelSpec -> {
                    // The landmarks serve every face part: kept for the last photo.
                    val key = "${photoKey(request)}|${model.key}"
                    val faces = lastFaces?.takeIf { it.first == key }?.second ?: run {
                        val cues = cues(request)
                        coroutineContext.ensureActive()
                        val lm = landmarker(model)
                        backend = lm.backend
                        val crops = cues.faces.map { it.crop(request.image.width, request.image.height) }
                        lm.runtime.detect(request.image, crops).also { lastFaces = key to it }
                    }
                    mapOf(kind to FaceParts.mask(kind, faces, request.image.width, request.image.height))
                }

                is ModelSpec -> {
                    val seg = segmentation(model)
                    backend = seg.backend
                    if (model.interactive) {
                        mapOf(kind to subject(request, seg.runtime))
                    } else {
                        seg.runtime.segment(request.image).masks
                    }
                }

                else -> error("unknown model type ${model.key}")
            }
            // One run of a multi-kind model fills the cache for all its kinds.
            val quantised = made.mapValues { MaskDiskCache.quantised(it.value) }
            for ((k, m) in quantised) cache?.put(rawKey(request, model, k), m)
            quantised.getValue(kind)
        }
        val t1 = System.nanoTime()
        coroutineContext.ensureActive()
        val setting = request.refine
        val mask = if (setting == null) {
            raw.resized(request.image.width, request.image.height)
        } else {
            val refined = GuidedRefiner.refine(
                request.image,
                raw,
                setting.params(request.image.width, request.image.height),
            )
            MaskDiskCache.quantised(refined).also { m -> refinedKey?.let { cache?.put(it, m) } }
        }
        val t2 = System.nanoTime()
        return MaskOutput(kind, model.key, mask, raw, setting, false, backend, (t1 - t0) / NANOS, (t2 - t1) / NANOS)
    }

    /** MagicTouch at every seed (the automatic ones and the taps), united (max). */
    private suspend fun subject(request: MaskRequest, model: SegmentationModel): Mask {
        val seeds = buildList {
            if (request.autoSeeds) addAll(cues(request).seeds())
            addAll(request.prompts)
        }
        require(seeds.isNotEmpty()) { "the subject needs automatic seeds or prompts" }
        var out: FloatArray? = null
        var w = 0
        var h = 0
        for (seed in seeds) {
            coroutineContext.ensureActive()
            val m = model.segment(request.image, seed).masks.getValue(MaskKind.SUBJECT)
            w = m.width
            h = m.height
            val acc = out ?: FloatArray(m.values.size)
            for (i in acc.indices) acc[i] = max(acc[i], m.values[i])
            out = acc
        }
        return Mask(w, h, checkNotNull(out))
    }

    private fun photoKey(request: MaskRequest): String =
        "${request.photoKey}|${request.inputVersion}|${request.image.width}x${request.image.height}"

    @Suppress("ReturnCount")
    private fun cues(request: MaskRequest): PhotoCues {
        request.cues?.let { return it }
        val key = photoKey(request)
        lastCues?.let { (k, c) -> if (k == key) return c }
        return cuesOf(request.image).also { lastCues = key to it }
    }

    private fun segmentation(spec: ModelSpec): Loaded<SegmentationModel> {
        loaded?.let { if (it.model == spec) return it }
        loaded?.runtime?.close()
        loaded = null
        val engine = loader.load(spec)
        return Loaded(spec, SegmentationModel(spec, engine), engine.backend).also { loaded = it }
    }

    private fun landmarker(spec: LandmarkModelSpec): Loaded<FaceLandmarker> {
        landmarker?.let { if (it.model == spec) return it }
        landmarker?.runtime?.close()
        val engine = loader.load(spec)
        return Loaded(spec, FaceLandmarker(engine, spec), engine.backend).also { landmarker = it }
    }

    /** Frees the loaded models (they load again on the next request). */
    suspend fun release() = mutex.withLock {
        close()
        lastCues = null
        lastFaces = null
    }

    override fun close() {
        loaded?.runtime?.close()
        loaded = null
        landmarker?.runtime?.close()
        landmarker = null
    }

    companion object {
        /** The model input's long side at most (docs/adr/0021 "Model input"). */
        const val MAX_INPUT = 1024

        /** The cache key format; bump it when the stored meaning changes. */
        private const val KEY_FORMAT = 1
        private const val NANOS = 1e6

        /** The model that makes [kind] today: what a new mask's recipe records. */
        fun modelFor(kind: MaskKind): PinnedModel = when (kind) {
            MaskKind.SUBJECT, MaskKind.BACKGROUND -> MaskModels.MAGIC_TOUCH

            MaskKind.SKY -> MaskModels.DEEPLAB_ADE20K_SKY

            MaskKind.PEOPLE, MaskKind.FACE_SKIN, MaskKind.BODY_SKIN, MaskKind.SKIN, MaskKind.HAIR, MaskKind.CLOTHES ->
                MaskModels.SELFIE_MULTICLASS

            MaskKind.EYES, MaskKind.IRISES, MaskKind.LIPS, MaskKind.TEETH, MaskKind.EYEBROWS ->
                MaskModels.FACE_LANDMARKER
        }

        /** The model [request] names (or the current one), checked against its kind. */
        fun resolve(request: MaskRequest): PinnedModel {
            val model = request.model?.let { key ->
                requireNotNull(MaskModels.pinned.find { it.key == key }) { "unknown model $key" }
            } ?: modelFor(request.kind)
            val kind = if (request.kind == MaskKind.BACKGROUND) MaskKind.SUBJECT else request.kind
            val ok = when (model) {
                is ModelSpec -> kind in model.kinds
                is LandmarkModelSpec -> kind.isFacePart
                else -> false
            }
            require(ok) { "${model.key} does not make $kind" }
            return model
        }

        /** The unrefined model output's cache key. */
        fun rawKey(request: MaskRequest, model: PinnedModel, kind: MaskKind): String {
            val seeds = if (model is ModelSpec && model.interactive) {
                "seeds:${if (request.autoSeeds) "auto" else "none"}" +
                    request.prompts.joinToString("") { ";%.4f,%.4f".format(Locale.ROOT, it.x, it.y) }
            } else {
                "seeds:-"
            }
            return "mask$KEY_FORMAT|${request.photoKey}|${model.key}|$kind|input${request.inputVersion}|" +
                "${request.image.width}x${request.image.height}|$seeds"
        }

        /** The refined mask's cache key. */
        fun refinedKey(rawKey: String, setting: RefineDefaults.Setting): String =
            "$rawKey|refine:${setting.radius},${"%.6f".format(Locale.ROOT, setting.eps)}"
    }
}
