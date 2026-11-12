package dev.hfps.reveal.bench

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.refine.RefineDefaults
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import dev.hfps.reveal.ml.service.EngineLoader
import dev.hfps.reveal.ml.service.MaskRequest
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.auto.SaliencyMap
import dev.hfps.reveal.pipeline.faces.FaceDetector
import dev.hfps.reveal.pipeline.faces.FaceInput
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Release-build measurements (the `bench` build type only: minified like release, id `dev.hfps.reveal.bench`), driven
 * by adb because a minified app cannot host instrumented tests (docs/adr/0019 and 0020 "Measurements"). Results are
 * logged under `REVEAL_BENCH`; `scripts/bench/release-bench.sh` runs the whole sequence.
 *
 * - `--es mode faces`: the photo analysis on every file in `Android/data/<id>/files/samples` (pushed by adb: public
 *   CC0 samples or the public-domain test portrait only): the proxy the editor analyses (`--ei edge`, default 2400)
 *   and the batch's (1200 px); per photo the median of `--ei runs` (default 5) after a warm-up, for the face input,
 *   the detector, the subject map and the whole `PhotoFeatures.of`, with the faces found.
 * - `--es mode presets`: 60 presets in one group (first in the panel), the first sample opened in the editor with
 *   the presets panel; logs the grid's bounds for the `input swipe` scrolls (frame times: `dumpsys gfxinfo`).
 * - `--es mode masks`: every AI mask kind (docs/adr/0021 "M4/C") through the app's `MaskService` (Hilt, release
 *   models, R8 rules) on every sample's recipe-independent 576 px render, `--es backend cpu` for LiteRT's CPU only
 *   (emulators); per kind the model, backend, model and refinement ms and coverage. No cache: every run computes.
 * - `--es mode init`: creates the samples folder (adb pushes into it afterwards).
 * - `--es mode cleanup`: removes the bench presets and the photo it put in Downloads.
 */
@AndroidEntryPoint
class BenchActivity : ComponentActivity() {
    @Inject
    lateinit var presets: PresetRepository

    @Inject
    lateinit var maskService: MaskService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val runs = intent.getIntExtra("runs", RUNS)
        val edge = intent.getIntExtra("edge", EDITOR_EDGE)
        when (intent.getStringExtra("mode")) {
            // In the foreground until done: a background process runs on the little cores (and Samsung freezes it).
            "faces" -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
                scope.launch {
                    faces(app, edge, runs)
                    finish()
                }
                return
            }

            "presets" -> scope.launch { presets(app) }

            "masks" -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
                val cpu = intent.getStringExtra("backend") == "cpu"
                scope.launch {
                    masks(app, cpu)
                    finish()
                }
                return
            }

            "cleanup" -> scope.launch { cleanup(app) }

            // The app creates its samples folder itself: a folder adb creates under Android/data is not the app's.
            "init" -> log("init: ${sampleDir(app).apply { mkdirs() }.absolutePath}")

            else -> log("unknown mode: ${intent.getStringExtra("mode")}")
        }
        finish()
    }

    private suspend fun faces(context: Context, editorEdge: Int, runs: Int) = withContext(Dispatchers.Default) {
        val files = samples(context)
        log("faces: ${files.size} files, $runs runs, ${Runtime.getRuntime().availableProcessors()} cpus")
        for ((index, file) in files.withIndex()) {
            for (edge in listOf(editorEdge, BATCH_EDGE)) {
                val input = runCatching { proxy(file, edge) }.getOrElse {
                    log("faces: file#$index unreadable: ${it.javaClass.simpleName}")
                    null
                } ?: continue
                val detector = FaceDetector.shipped()
                var faces = 0
                val prepare = LongArray(runs)
                val detect = LongArray(runs)
                val subject = LongArray(runs)
                val features = LongArray(runs)
                repeat(runs + 1) { run ->
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val image = FaceInput.from(input)
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    faces = detector.detect(image).size
                    val t2 = SystemClock.elapsedRealtimeNanos()
                    SaliencyMap.of(image)
                    val t3 = SystemClock.elapsedRealtimeNanos()
                    PhotoFeatures.of(input)
                    val t4 = SystemClock.elapsedRealtimeNanos()
                    if (run > 0) {
                        prepare[run - 1] = t1 - t0
                        detect[run - 1] = t2 - t1
                        subject[run - 1] = t3 - t2
                        features[run - 1] = t4 - t3
                    }
                }
                log(
                    "faces: file#$index ${input.width}x${input.height} faces $faces; median ms: face input " +
                        "${median(prepare)}, detector ${median(detect)}, subject ${median(subject)}, " +
                        "PhotoFeatures.of ${median(features)}",
                )
            }
        }
        log("faces: done")
    }

    private suspend fun masks(context: Context, cpuOnly: Boolean) = withContext(Dispatchers.Default) {
        val service = if (cpuOnly) {
            MaskService(
                EngineLoader { model ->
                    LiteRtEngine.load(
                        LiteRtEngine.Source.Asset(context.assets, MaskModels.assetPath(model)),
                        listOf(Backend.CPU),
                    )
                },
            )
        } else {
            maskService
        }
        val files = samples(context)
        log("masks: ${files.size} files, backend ${if (cpuOnly) "cpu" else "gpu then cpu"}")
        for ((index, file) in files.withIndex()) {
            val input = runCatching { proxy(file, BATCH_EDGE) }.getOrElse {
                log("masks: file#$index unreadable: ${it.javaClass.simpleName}")
                null
            } ?: continue
            val face = FaceInput.from(input)
            val image = MlImage(face.width, face.height, face.rgb)
            for (kind in MaskKind.entries) {
                val r = runCatching {
                    service.mask(MaskRequest("bench-$index", image, kind, refine = RefineDefaults.of(kind)))
                }
                r.onSuccess {
                    log(
                        "masks: file#$index ${image.width}x${image.height} $kind ${it.model} ${it.backend} " +
                            "model ${"%.0f".format(Locale.ROOT, it.modelMs)} ms, refine " +
                            "${"%.0f".format(Locale.ROOT, it.refineMs)} ms, " +
                            "coverage ${"%.3f".format(Locale.ROOT, it.mask.mean())}",
                    )
                }.onFailure { log("masks: file#$index $kind failed: $it") }
            }
        }
        if (cpuOnly) service.close() else service.release()
        log("masks: done")
    }

    private fun proxy(file: File, edge: Int): PipelineInput = ImageInput.fromFile(file).use { input ->
        val orientation = if (input.type.isRaw) {
            null
        } else {
            runCatching {
                input.inspection.metadata.orientation
            }.getOrNull()
        }
        val proxy = ProxyBuilder.decodeProxy(input, edge)
        val metadata = runCatching { input.inspection.metadata }.getOrNull()
        PipelineInputMapper.map(proxy.image, proxy.info, orientation ?: proxy.info.orientation, metadata)
    }

    @Suppress("MagicNumber") // The same 60 varied presets as PresetsMeasurementsTest; the grid bounds.
    private suspend fun presets(context: Context) {
        val file = samples(context).firstOrNull { it.name.endsWith(".arw", ignoreCase = true) }
            ?: samples(context).firstOrNull()
            ?: return log("presets: push a sample into ${sampleDir(context)}")
        val groupId = withContext(Dispatchers.IO) {
            cleanupPresets()
            val id = presets.createGroup(GROUP)
            repeat(PRESETS) { i ->
                val recipe = Recipe(
                    profile = Profile(BaseLook.entries[i % BaseLook.entries.size]),
                    light = Light(contrast = (i % 9 - 4) * 10f, exposureEv = (i % 5 - 2) * 0.2f),
                    colour = Colour(vibrance = (i % 7) * 8f, saturation = (i % 3 - 1) * 15f),
                    effects = Effects(clarity = (i % 4) * 10f, vignetteAmount = -(i % 6) * 8f),
                )
                presets.create("Look $i", PresetPatch.fromSections(recipe, PresetSection.entries), id)
            }
            val others = presets.library().first().groups.mapNotNull { it.rowId }.filter { it != id }
            presets.reorderGroups(listOf(id) + others)
            id
        }
        val uri = withContext(Dispatchers.IO) { insert(context, file) }
        log("presets: group $groupId with $PRESETS presets; opening the photo")
        // A fresh process (the script force-stops the app first): no earlier editor is left in EditorMetrics.
        EditorMetrics.renderer = null
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, if (file.name.endsWith(".arw", true)) "image/x-sony-arw" else "image/jpeg")
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitFor { EditorMetrics.renderer != null }
        delay(SETTLE_MS)
        withContext(Dispatchers.Main) { EditorMetrics.selectTool?.invoke(ToolId.PRESETS) }
        waitFor { EditorMetrics.presetGrid != null }
        delay(SETTLE_MS)
        val g = checkNotNull(EditorMetrics.presetGrid)
        log("presets: grid ${g[0]} ${g[1]} ${g[2]} ${g[3]}")
    }

    private suspend fun cleanup(context: Context) = withContext(Dispatchers.IO) {
        cleanupPresets()
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("Download/$FOLDER%"),
        )
        log("cleanup: done")
    }

    private suspend fun cleanupPresets() {
        presets.library().first().groups.filter { it.name == GROUP }.mapNotNull { it.rowId }
            .forEach { presets.deleteGroup(it) }
    }

    /** The sample in Downloads (a content URI, as from another app), with a fresh name so each run is a new open. */
    private fun insert(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "bench-${System.nanoTime()}-${file.name}")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "timed out" }
            delay(POLL_MS)
        }
    }

    private fun sampleDir(context: Context): File = File(context.getExternalFilesDir(null), "samples")

    private fun samples(context: Context): List<File> =
        sampleDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    private fun median(values: LongArray): String =
        "%.1f".format(Locale.ROOT, values.sorted()[values.size / 2] / NS_PER_MS)

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "REVEAL_BENCH"
        const val GROUP = "Bench presets"
        const val FOLDER = "RevealBench"
        const val PRESETS = 60
        const val RUNS = 5
        const val EDITOR_EDGE = 2400
        const val BATCH_EDGE = 1200
        const val SETTLE_MS = 1_500L
        const val TIMEOUT_MS = 120_000L
        const val POLL_MS = 100L
        const val NS_PER_MS = 1_000_000.0

        /** Outlives the activity (it finishes at once): the measurement runs in the app's process. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
