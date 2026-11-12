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
import dev.hfps.reveal.ml.refine.GuidedRefiner
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import dev.hfps.reveal.ml.tensor.Resample
import dev.hfps.reveal.pipeline.auto.SaliencyMap
import dev.hfps.reveal.pipeline.faces.DetectionImage
import dev.hfps.reveal.pipeline.faces.FaceDetector
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the lab can run: a bundled model, or MagicTouch seeded from the saliency map ("auto subject"). */
sealed interface LabModel {
    val spec: ModelSpec

    data class Plain(override val spec: ModelSpec) : LabModel

    /**
     * MagicTouch seeded automatically, masks united (docs/adr/0021 "Subject"): at every face (its centre and, below it,
     * the body) when the photo has faces, else at the three strongest saliency peaks.
     */
    data object AutoSubject : LabModel {
        override val spec: ModelSpec = MaskModels.MAGIC_TOUCH
    }

    /** The kinds this choice offers (background = the inverse of the subject or the people). */
    val kinds: List<MaskKind>
        get() {
            val own = if (this is AutoSubject) listOf(MaskKind.SUBJECT) else spec.kinds.keys.toList()
            val inverse = MaskKind.SUBJECT in own || MaskKind.PEOPLE in own
            return own + if (inverse) listOf(MaskKind.BACKGROUND) else emptyList()
        }

    companion object {
        val all: List<LabModel> = listOf(AutoSubject) + MaskModels.bundled.map { Plain(it) }
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
    private const val PEAKS = 3
    private const val PEAK_SUPPRESSION = 0.12f
    private const val SALIENCY_SIDE = 576
    private const val MAX_FACES = 8
    private const val BODY_OFFSET = 2f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var loaded: Loaded? = null

    private class Loaded(val spec: ModelSpec, val model: SegmentationModel, val backend: String)
    private var guide: MlImage? = null

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
        if (choice.spec.interactive && choice !is LabModel.AutoSubject && prompt == null) {
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
        val loadedModel = model(choice.spec, assets)
        val model = loadedModel.model
        val t1 = SystemClock.elapsedRealtime()
        val source = if (wanted == MaskKind.BACKGROUND) {
            if (MaskKind.SUBJECT in choice.kinds) MaskKind.SUBJECT else MaskKind.PEOPLE
        } else {
            wanted
        }
        var inference = 0.0
        var total = 0.0
        val prompts = when {
            choice is LabModel.AutoSubject -> autoSeeds(image)
            choice.spec.interactive -> listOfNotNull(prompt)
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
        val full = if (refine) {
            GuidedRefiner.refine(image, out, GuidedRefiner.Params(subsample = 2, radius = 8, eps = 1e-3f))
        } else {
            out.resized(image.width, image.height)
        }
        val refineMs = SystemClock.elapsedRealtime() - t2
        val r = (tint shr 16) and 0xff
        val g = (tint shr 8) and 0xff
        val b = tint and 0xff
        val px = IntArray(full.values.size) { i ->
            val a = (full.values[i] * TINT_ALPHA * 255f).toInt().coerceIn(0, 255)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(px, full.width, full.height, Bitmap.Config.ARGB_8888)
        Log.i(
            TAG,
            "${choice.spec.id} $wanted backend=${loadedModel.backend} load=${t1 - t0} ms inference=$inference ms " +
                "total=$total ms refine=$refineMs ms (${image.width}x${image.height})",
        )
        LabResult(bmp.asImageBitmap(), loadedModel.backend, t1 - t0, inference, total, refineMs, full.mean())
    }

    /** Seeds for the automatic subject: the faces (Reveal's detector, ADR 0010), else the saliency peaks. */
    private fun autoSeeds(image: MlImage): List<MaskPrompt> {
        val small = image.downscaled(SALIENCY_SIDE)
        val detection = DetectionImage(small.width, small.height, small.rgb)
        val faces = FaceDetector.shipped().detect(detection).take(MAX_FACES)
        if (faces.isNotEmpty()) {
            return faces.flatMap { f ->
                listOf(MaskPrompt(f.centreX, f.centreY), MaskPrompt(f.centreX, (f.centreY + BODY_OFFSET * f.height)))
            }.filter { it.y in 0f..1f }
        }
        return saliencyPeaks(SaliencyMap.of(detection))
    }

    /** The strongest peaks of Reveal's saliency map (ADR 0010), each suppressing its neighbourhood. */
    private fun saliencyPeaks(map: SaliencyMap): List<MaskPrompt> {
        val v = map.values.copyOf()
        val peaks = mutableListOf<MaskPrompt>()
        repeat(PEAKS) {
            val i = v.indices.maxBy { v[it] }
            val x = (i % map.width + 0.5f) / map.width
            val y = (i / map.width + 0.5f) / map.height
            peaks += MaskPrompt(x, y)
            for (j in v.indices) {
                val dx = (j % map.width + 0.5f) / map.width - x
                val dy = (j / map.width + 0.5f) / map.height - y
                if (dx * dx + dy * dy < PEAK_SUPPRESSION * PEAK_SUPPRESSION) v[j] = 0f
            }
        }
        return peaks
    }

    private fun MlImage.downscaled(side: Int): MlImage {
        val long = max(width, height)
        if (long <= side) return this
        val w = max(1, width * side / long)
        val h = max(1, height * side / long)
        return MlImage(w, h, Resample.interleaved(rgb, width, height, 3, w, h))
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
        }
        guide = null
        photo = null
        result = null
        prompt = null
        error = null
        running = false
    }
}
