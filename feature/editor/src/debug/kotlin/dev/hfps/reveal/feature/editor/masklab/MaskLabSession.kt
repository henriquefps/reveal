package dev.hfps.reveal.feature.editor.masklab

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.hfps.reveal.ml.Mask
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.MaskPrompt
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.ModelSpec
import dev.hfps.reveal.ml.SegmentationModel
import dev.hfps.reveal.ml.face.FaceLandmarker
import dev.hfps.reveal.ml.face.FaceParts
import dev.hfps.reveal.ml.refine.GuidedRefiner
import dev.hfps.reveal.ml.refine.RefineDefaults
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import dev.hfps.reveal.ml.service.PhotoCues
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the lab can run: a model (release-bundled or lab-only), MagicTouch seeded automatically, or the face parts. */
sealed interface LabModel {
    /** The test tag suffix (`mask-lab-model-<tag>`). */
    val tag: String

    /** The kinds this choice offers (background = the inverse of the subject or the people). */
    val kinds: List<MaskKind>

    /** Runs only once the user tapped the photo. */
    val needsTap: Boolean get() = false

    data class Plain(val spec: ModelSpec) : LabModel {
        override val tag: String get() = if (spec.version == 1) spec.id.value else "${spec.id}-v${spec.version}"
        override val needsTap: Boolean get() = spec.interactive
        override val kinds: List<MaskKind>
            get() {
                val own = spec.kinds.keys.toList()
                val inverse = MaskKind.SUBJECT in own || MaskKind.PEOPLE in own
                return own + if (inverse) listOf(MaskKind.BACKGROUND) else emptyList()
            }
    }

    /** MagicTouch seeded automatically, masks united (docs/adr/0021 "Subject"): [PhotoCues.seeds]. */
    data object AutoSubject : LabModel {
        override val tag: String = "${MaskModels.MAGIC_TOUCH.id}-auto"
        override val kinds: List<MaskKind> = listOf(MaskKind.SUBJECT, MaskKind.BACKGROUND)
    }

    /** Eyes, irises, lips, teeth and brows from face landmarks (docs/adr/0021 "M4/C"). */
    data object FaceParts : LabModel {
        override val tag: String = MaskModels.FACE_LANDMARKER.id.value
        override val kinds: List<MaskKind> = MaskKind.entries.filter { it.isFacePart }
    }

    companion object {
        val all: List<LabModel> =
            listOf(AutoSubject, FaceParts) + (MaskModels.bundled + MaskModels.lab).map { Plain(it) }
    }
}

/** The lab's run results: the tinted mask, how long it took, where it ran. */
data class LabResult(
    val tint: ImageBitmap,
    val backend: String,
    val loadMs: Long,
    val inferenceMs: Double,
    val totalMs: Double,
    val refineMs: Long,
    val coverage: Float,
)

/**
 * The debug mask lab's state (docs/adr/0021): one per process, reset when the lab closes. The input is the open photo
 * as the edit renders it now (`renderOutputImage`), so what the models see is what the screen shows.
 */
object MaskLabSession {
    private const val TAG = "RevealMaskLab"
    private const val TINT_ALPHA = 0.6f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var loaded: Loaded? = null

    private class Loaded(val spec: ModelSpec, val model: SegmentationModel, val backend: String)
    private var guide: MlImage? = null
    private var landmarker: FaceLandmarker? = null
    private var landmarkerBackend: String = ""

    var model: LabModel by mutableStateOf(LabModel.all.first())
    var kind: MaskKind by mutableStateOf(MaskKind.SUBJECT)
    var refine: Boolean by mutableStateOf(true)
    var prompt: MaskPrompt? by mutableStateOf(null)
    var photo: ImageBitmap? by mutableStateOf(null)
        private set
    var result: LabResult? by mutableStateOf(null)
        private set
    var running: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** Renders the photo with [recipe] (once per lab session) and runs the current choice. */
    fun open(renderer: GpuDevelopRenderer, recipe: Recipe, assets: AssetManager, tint: Int) {
        if (photo != null) return
        job = scope.launch {
            running = true
            runCatching {
                val (w, h, rgba) = renderer.renderOutputImage(recipe)
                val argb = IntArray(w * h) { i ->
                    val o = i * 4
                    (0xff shl 24) or ((rgba[o].toInt() and 0xff) shl 16) or
                        ((rgba[o + 1].toInt() and 0xff) shl 8) or (rgba[o + 2].toInt() and 0xff)
                }
                guide = MlImage.fromArgb(w, h, argb)
                photo = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.onFailure { fail(it) }
            running = false
            run(assets, tint)
        }
    }

    /** Runs the current model / kind / refine / prompt and publishes the tinted mask. */
    fun run(assets: AssetManager, tint: Int) {
        val image = guide ?: return
        val choice = model
        val wanted = kind
        if (choice.needsTap && prompt == null) {
            result = null
            return
        }
        job?.cancel()
        job = scope.launch {
            running = true
            error = null
            runCatching { compute(image, choice, wanted, assets, tint) }
                .onSuccess { result = it }
                .onFailure { fail(it) }
            running = false
        }
    }

    private suspend fun compute(
        image: MlImage,
        choice: LabModel,
        wanted: MaskKind,
        assets: AssetManager,
        tint: Int,
    ): LabResult = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()
        if (choice is LabModel.FaceParts) return@withContext faceParts(image, wanted, assets, tint, t0)
        val spec = if (choice is LabModel.Plain) choice.spec else MaskModels.MAGIC_TOUCH
        val loadedModel = model(spec, assets)
        val model = loadedModel.model
        val t1 = SystemClock.elapsedRealtime()
        val source = if (wanted == MaskKind.BACKGROUND) {
            if (MaskKind.SUBJECT in spec.kinds) MaskKind.SUBJECT else MaskKind.PEOPLE
        } else {
            wanted
        }
        var inference = 0.0
        var total = 0.0
        val prompts = when {
            choice is LabModel.AutoSubject -> PhotoCues.of(image).seeds()
            spec.interactive -> listOfNotNull(prompt)
            else -> listOf(null)
        }
        var mask: Mask? = null
        for (p in prompts) {
            val r = model.segment(image, p)
            inference += r.inferenceMs
            total += r.totalMs
            val m = r.masks.getValue(source)
            mask =
                mask?.let { a ->
                    Mask(a.width, a.height, FloatArray(a.values.size) { max(a.values[it], m.values[it]) })
                }
                    ?: m
        }
        var out = checkNotNull(mask)
        if (wanted == MaskKind.BACKGROUND) out = out.inverted()
        val t2 = SystemClock.elapsedRealtime()
        val setting = RefineDefaults.of(wanted)
        val full = if (refine && setting != null) {
            GuidedRefiner.refine(image, out, setting.params(image.width, image.height))
        } else {
            out.resized(image.width, image.height)
        }
        val refineMs = SystemClock.elapsedRealtime() - t2
        LabResult(tinted(full, tint), loadedModel.backend, t1 - t0, inference, total, refineMs, full.mean()).also {
            Log.i(
                TAG,
                "${spec.id} $wanted backend=${loadedModel.backend} load=${t1 - t0} ms inference=$inference ms " +
                    "total=$total ms refine=$refineMs ms (${image.width}x${image.height})",
            )
        }
    }

    /** Eyes, irises, lips, teeth or brows: faces (ADR 0010), landmarks on each, the part's polygons. */
    private fun faceParts(image: MlImage, wanted: MaskKind, assets: AssetManager, tint: Int, t0: Long): LabResult {
        val lm = landmarker ?: run {
            val engine = LiteRtEngine.load(
                LiteRtEngine.Source.Asset(assets, MaskModels.assetPath(MaskModels.FACE_LANDMARKER)),
            )
            landmarkerBackend = engine.backend.name
            FaceLandmarker(engine).also { landmarker = it }
        }
        val t1 = SystemClock.elapsedRealtime()
        val crops = PhotoCues.of(image).faces.map { it.crop(image.width, image.height) }
        val t2 = SystemClock.elapsedRealtimeNanos()
        val faces = lm.detect(image, crops)
        val t3 = SystemClock.elapsedRealtimeNanos()
        val mask = FaceParts.mask(wanted, faces, image.width, image.height)
        val drawMs = (SystemClock.elapsedRealtimeNanos() - t3) / 1_000_000
        Log.i(
            TAG,
            "face parts $wanted faces=${faces.size}/${crops.size} landmarks=${(t3 - t2) / 1e6} ms draw=$drawMs ms",
        )
        return LabResult(
            tinted(mask, tint),
            landmarkerBackend,
            t1 - t0,
            (t3 - t2) / 1e6,
            (t3 - t2) / 1e6,
            drawMs,
            mask.mean(),
        )
    }

    private fun tinted(full: Mask, tint: Int): ImageBitmap {
        val r = (tint shr 16) and 0xff
        val g = (tint shr 8) and 0xff
        val b = tint and 0xff
        val px = IntArray(full.values.size) { i ->
            val a = (full.values[i] * TINT_ALPHA * 255f).toInt().coerceIn(0, 255)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, full.width, full.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** One model loaded at a time (they are tens to hundreds of MB once compiled). */
    private fun model(spec: ModelSpec, assets: AssetManager): Loaded {
        loaded?.let { if (it.spec == spec) return it }
        loaded?.model?.close()
        loaded = null
        val engine = LiteRtEngine.load(LiteRtEngine.Source.Asset(assets, MaskModels.assetPath(spec)))
        return Loaded(spec, SegmentationModel(spec, engine), engine.backend.name).also { loaded = it }
    }

    private fun fail(t: Throwable) {
        Log.w(TAG, "mask lab failed", t)
        error = t.message ?: t.javaClass.simpleName
    }

    /** Leaves the lab: frees the model and the images. */
    fun close() {
        job?.cancel()
        scope.launch {
            loaded?.model?.close()
            loaded = null
            landmarker?.close()
            landmarker = null
        }
        guide = null
        photo = null
        result = null
        prompt = null
        error = null
        running = false
    }
}
