package dev.hfps.reveal.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.data.thumb.GpuEditedThumbnails
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.input.BitmapTileSource
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/adr/0023 "FIX/H": the library's edited thumbnail (and the viewer's developed page, the same renderer) shows the
 * whole edit. One recipe uses every tool (profile, Light with a curve, colour with the mixer and grading, Detail,
 * Effects, crop + straighten + Transform, three kinds of mask, heal, clone and a Remove stroke); its thumbnail is
 * compared with the editor's render of the same recipe (2048 px proxy, the full decode as the original) downscaled
 * to the thumbnail's size: the same output size (the geometry's, not the photo's) and the same pixels within a
 * tolerance (Detail and the local Effects work at each image's own scale), the removed object gone in both.
 * `REVEAL_THUMB_TEST`.
 */
@RunWith(AndroidJUnit4::class)
class EditedThumbnailTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aThumbnailShowsEveryTool(): Unit = runBlocking {
        val file = File(context.cacheDir, "thumb-test-${UUID.randomUUID()}.jpg").apply { writeBytes(scene()) }
        try {
            val recipe = everyTool()
            val thumbs = GpuEditedThumbnails(CoroutineScope(Dispatchers.Default))
            val thumb = thumbs.renderFile({ ImageInput.fromFile(file) }, recipe, EDGE)
            val (ew, eh, editor) = editorRender(file, recipe)
            Log.i(TAG, "thumbnail ${thumb.width}x${thumb.height}, editor ${ew}x$eh")
            // The geometry's output size (a crop), scaled.
            val aspect = ew.toFloat() / eh
            assertThat(abs(thumb.width.toFloat() / thumb.height - aspect)).isLessThan(0.02f)
            assertThat(maxOf(thumb.width, thumb.height)).isAtMost(EDGE)
            val small = downscale(editor, ew, eh, thumb.width, thumb.height)
            val px = IntArray(thumb.width * thumb.height)
            thumb.getPixels(px, 0, thumb.width, 0, 0, thumb.width, thumb.height)
            var sum = 0.0
            val diffs = IntArray(px.size)
            for (i in px.indices) {
                val d = (
                    abs(Color.red(px[i]) - small[i * 3]) + abs(Color.green(px[i]) - small[i * 3 + 1]) +
                        abs(Color.blue(px[i]) - small[i * 3 + 2])
                    ) / 3
                diffs[i] = d
                sum += d
            }
            diffs.sort()
            val mean = sum / px.size
            val p95 = diffs[diffs.size * 95 / 100]
            Log.i(TAG, "thumbnail vs editor: mean ${"%.2f".format(mean)}, p95 $p95, max ${diffs.last()} levels")
            assertThat(mean).isLessThan(MEAN_LEVELS)
            assertThat(p95).isAtMost(P95_LEVELS)
            // The removed object (red) is gone in the thumbnail: compared with the same edit without the Remove spot.
            val withoutRemove = recipe.copy(heals = recipe.heals.filter { h -> h.kind != Heal.KIND_REMOVE })
            val kept = thumbs.renderFile({ ImageInput.fromFile(file) }, withoutRemove, EDGE)
            val keptPx = IntArray(kept.width * kept.height)
            kept.getPixels(keptPx, 0, kept.width, 0, 0, kept.width, kept.height)
            val objectPx = (OBJECT_SIDE * thumb.width / (W * CROP_WIDTH)).let { it * it }
            Log.i(TAG, "red pixels: with Remove ${redPixels(px)}, without ${redPixels(keptPx)}, object ~$objectPx")
            assertThat(redPixels(keptPx) - redPixels(px)).isGreaterThan((objectPx * 0.6f).toInt())
        } finally {
            file.delete()
        }
    }

    /** The editor's render: the 2048 px proxy, the full decode as the original, its fills awaited. */
    private suspend fun editorRender(file: File, recipe: Recipe): Triple<Int, Int, ByteArray> {
        val proxy = ImageInput.fromFile(file).use { ProxyBuilder.decodeProxy(it, PROXY_EDGE) }
        val input = PipelineInputMapper.map(proxy.image, proxy.info)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val full = BitmapTileSource(checkNotNull(BitmapFactory.decodeFile(file.path, options)))
        val k = maxOf(full.width, full.height).toFloat() / maxOf(input.width, input.height)
        val renderer = GpuDevelopRenderer.create()
        val executor = Executors.newSingleThreadExecutor()
        try {
            renderer.upload(input, k)
            renderer.setFullResolution(full, k)
            renderer.setHealSource(RemoveFills(executor = executor))
            assertThat(renderer.awaitHealFills(recipe, FILL_WAIT_MS)).isTrue()
            return renderer.renderOutputImage(recipe)
        } finally {
            renderer.close()
            executor.shutdownNow()
        }
    }

    /** A textured gradient with a red square (removed), a dark dust spot (healed) and a blue one (cloned over). */
    private fun scene(): ByteArray {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val rnd = Random(7)
        val px = IntArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                val n = rnd.nextInt(17) - 8
                val r = (70 + 120 * x / W + n).coerceIn(0, 255)
                val g = (90 + 90 * y / H + n).coerceIn(0, 255)
                val b = (110 + 60 * (x + y) / (W + H) + n).coerceIn(0, 255)
                px[y * W + x] = Color.rgb(r, g, b)
            }
        }
        bitmap.setPixels(px, 0, W, 0, 0, W, H)
        val canvas = Canvas(bitmap)
        canvas.drawRect(
            OBJECT_X - 60f,
            OBJECT_Y - 60f,
            OBJECT_X + 60f,
            OBJECT_Y + 60f,
            Paint().apply {
                color =
                    Color.rgb(230, 20, 20)
            },
        )
        canvas.drawCircle(400f, 300f, 14f, Paint().apply { color = Color.rgb(30, 30, 30) })
        canvas.drawCircle(500f, 800f, 16f, Paint().apply { color = Color.rgb(20, 40, 220) })
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Suppress("LongMethod")
    private fun everyTool(): Recipe = Recipe(
        light = Light(
            exposureEv = 0.3f,
            contrast = 20f,
            highlights = -30f,
            shadows = 25f,
            whites = 10f,
            blacks = -10f,
            curve = Curves(parametric = ParametricCurve(highlights = -10f), red = Curves.MEDIUM_CONTRAST),
        ),
        colour = Colour(
            vibrance = 20f,
            saturation = -10f,
            mixer = ColourMixer(hue = BandValues(orange = 10f), saturation = BandValues(blue = 20f)),
            grading = ColourGrading(shadows = GradeWheel(220f, 15f), highlights = GradeWheel(50f, 10f)),
        ),
        detail = Detail(nrLuminance = 20f, nrColour = 30f, sharpenAmount = 40f),
        effects = Effects(texture = 15f, clarity = 15f, dehaze = 10f, vignetteAmount = -30f),
        geometry = Geometry(
            crop = CropRect(0.06f, 0.05f, 0.94f, 0.93f),
            angle = 2f,
            transform = Transform(vertical = 8f),
        ),
        masks = listOf(
            Mask(
                id = "linear",
                adjustments = LocalAdjustments(exposure = 0.6f),
                components = listOf(MaskComponent.Linear()),
            ),
            Mask(
                id = "radial",
                adjustments = LocalAdjustments(saturation = -60f),
                components = listOf(MaskComponent.Radial(cx = 0.3f, cy = 0.6f)),
            ),
            Mask(
                id = "brush",
                adjustments = LocalAdjustments(exposure = -0.7f),
                components = listOf(
                    MaskComponent.Brush(
                        strokes = listOf(
                            BrushStroke.of(
                                listOf(StrokePoint(0.6f, 0.8f, 1f), StrokePoint(0.9f, 0.85f, 1f)),
                                size = 0.03f,
                                feather = 50f,
                            ),
                        ),
                    ),
                ),
            ),
        ),
        heals = listOf(
            HealScenes.spot("dust", Heal.KIND_HEAL, W, H, 400f, 300f, 22f, ox = 60f),
            HealScenes.spot("blue", Heal.KIND_CLONE, W, H, 500f, 800f, 24f, ox = -70f),
            HealScenes.stroke(
                "object",
                Heal.KIND_REMOVE,
                W,
                H,
                listOf(OBJECT_X - 40f to OBJECT_Y, OBJECT_X + 40f to OBJECT_Y),
                95f,
            ),
        ),
    )

    private fun redPixels(px: IntArray): Int = px.count { Color.red(it) > 2 * Color.green(it) + 40 }

    /** Area-average of an RGBA8 image to [tw] x [th], RGB. */
    @Suppress("NestedBlockDepth")
    private fun downscale(rgba: ByteArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
        val out = IntArray(tw * th * 3)
        for (ty in 0 until th) {
            val y0 = ty * h / th
            val y1 = maxOf(y0 + 1, (ty + 1) * h / th)
            for (tx in 0 until tw) {
                val x0 = tx * w / tw
                val x1 = maxOf(x0 + 1, (tx + 1) * w / tw)
                val s = IntArray(3)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        for (c in 0..2) s[c] += rgba[(y * w + x) * 4 + c].toInt() and 0xff
                    }
                }
                val n = (y1 - y0) * (x1 - x0)
                for (c in 0..2) out[(ty * tw + tx) * 3 + c] = s[c] / n
            }
        }
        return out
    }

    private companion object {
        const val TAG = "REVEAL_THUMB_TEST"
        const val W = 1800
        const val H = 1200
        const val OBJECT_X = 1300f
        const val OBJECT_Y = 520f
        const val OBJECT_SIDE = 120f
        const val CROP_WIDTH = 0.88f
        const val EDGE = 512
        const val PROXY_EDGE = 2048
        const val JPEG_QUALITY = 95
        const val FILL_WAIT_MS = 60_000L
        const val MEAN_LEVELS = 6.0
        const val P95_LEVELS = 18
    }
}
