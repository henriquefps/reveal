package dev.hfps.reveal.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.heal.HealTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.faces.FaceDetector
import dev.hfps.reveal.pipeline.faces.FaceInput
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Heal mode on the real editor (docs/adr/0023 "Editor"): a tap heals a dust spot with an automatic source, a drag
 * paints a stroke spot, the selected spot's source is dragged, the kind switched, the spot deleted and the steps
 * undone; the spots come back when the photo opens again; Remove fills an object (the progress ends); red eye fixes a
 * red pupil painted onto the public-domain portrait at the detected eyes. Locale-independent (test tags), unique
 * photo content per run (the suite runs twice without `pm clear`). Numbers under `REVEAL_HEAL_TEST`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HealFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val history: EditHistory get() = checkNotNull(EditorMetrics.history).invoke()
    private val committed: Recipe get() = checkNotNull(EditorMetrics.recipes).invoke().second

    @Suppress("LongMethod")
    @Test
    fun tapPaintDragSourceUndoAndReopen() {
        val uri = photo("reveal-heal.jpg", dustyJpeg())
        var kept: List<Heal> = emptyList()
        try {
            open(uri) {
                enterHeal()
                // A tap on the dust: one step, a source picked automatically, the dust gone.
                val before = history.entries.size
                tapOnImage(DUST_X, DUST_Y)
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == before + 1 }
                assertThat(fields()).contains(HistoryField.HEAL_ADD)
                val spot = committed.heals.single()
                assertThat(spot.type).isEqualTo(HealKind.HEAL)
                assertThat(spot.autoSource).isTrue()
                assertThat(abs(spot.dx) + abs(spot.dy)).isGreaterThan(0f)
                val (w, h) = proxySize()
                val healed = luma(readHealed(w, h, DUST_X, DUST_Y))
                val ring = luma(readHealed(w, h, DUST_X + DUST_R * 3, DUST_Y))
                Log.i(TAG, "dust: centre $healed, surroundings $ring")
                assertThat(abs(healed - ring)).isLessThan(0.08f)

                // A drag paints a stroke spot.
                swipeOnImage(Offset(0.2f, 0.75f), Offset(0.45f, 0.78f))
                compose.waitUntil(TIMEOUT_MS) { committed.heals.size == 2 }
                assertThat(committed.heals.last().isStroke).isTrue()

                // Drag the selected spot's source: one step, no longer automatic.
                val stroke = committed.heals.last()
                val steps = history.entries.size
                val from = Offset(
                    stroke.strokePoints.first().x + stroke.dx,
                    stroke.strokePoints.first().y + stroke.dy,
                )
                swipeOnImage(from, Offset(from.x + 0.05f, from.y - 0.05f))
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == steps + 1 }
                assertThat(fields()).contains(HistoryField.HEAL_SOURCE)
                assertThat(committed.heals.last().autoSource).isFalse()

                // Clone instead of heal: one step on the selected spot.
                click(HealTags.kind(HealKind.CLONE))
                compose.waitUntil(TIMEOUT_MS) { committed.heals.last().type == HealKind.CLONE }

                // Delete, then undo it.
                click(HealTags.DELETE)
                compose.waitUntil(TIMEOUT_MS) { committed.heals.size == 1 }
                assertThat(fields()).contains(HistoryField.HEAL_DELETE)
                click(EditorTags.UNDO)
                compose.waitUntil(TIMEOUT_MS) { committed.heals.size == 2 }
                kept = committed.heals
                click(EditorTags.MODE_DONE)
            }
            // Reopening restores the spots from the stored history.
            open(uri) {
                compose.waitUntil(TIMEOUT_MS) { committed.heals == kept }
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun cancelRestoresTheSpotsOfTheStart() {
        val uri = photo("reveal-heal-cancel.jpg", dustyJpeg())
        try {
            open(uri) {
                enterHeal()
                tapOnImage(DUST_X, DUST_Y)
                compose.waitUntil(TIMEOUT_MS) { committed.heals.size == 1 }
                click(EditorTags.MODE_CANCEL)
                compose.waitUntil(TIMEOUT_MS) { committed.heals.isEmpty() }
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun removeFillsAnObject() {
        val uri = photo("reveal-heal-remove.jpg", dustyJpeg())
        try {
            open(uri) {
                enterHeal()
                click(HealTags.kind(HealKind.REMOVE))
                val (w, h) = proxySize()
                val red = redness(readHealed(w, h, OBJECT_X, OBJECT_Y))
                swipeOnImage(Offset(OBJECT_X - 0.04f, OBJECT_Y), Offset(OBJECT_X + 0.04f, OBJECT_Y))
                compose.waitUntil(TIMEOUT_MS) { committed.heals.any { it.type == HealKind.REMOVE } }
                // The fill is computed once on its worker (the full resolution's crop): the progress ends.
                compose.waitUntil(REMOVE_TIMEOUT_MS) {
                    redness(readHealed(w, h, OBJECT_X, OBJECT_Y)) < red * 0.3f
                }
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodesWithTag(HealTags.PROGRESS).fetchSemanticsNodes().isEmpty()
                }
                Log.i(TAG, "remove: redness $red -> ${redness(readHealed(w, h, OBJECT_X, OBJECT_Y))}")
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun redEyeOnThePortrait() {
        val (jpeg, eye) = redEyePortrait()
        val uri = photo("reveal-heal-eye.jpg", jpeg)
        try {
            open(uri) {
                enterHeal()
                click(HealTags.kind(HealKind.RED_EYE))
                val (w, h) = proxySize()
                val before = redness(readHealed(w, h, eye.x, eye.y))
                tapOnImage(eye.x, eye.y)
                compose.waitUntil(TIMEOUT_MS) { committed.heals.any { it.type == HealKind.RED_EYE } }
                val after = redness(readHealed(w, h, eye.x, eye.y))
                Log.i(TAG, "red eye: redness $before -> $after")
                assertThat(before).isGreaterThan(0.2f)
                assertThat(after).isLessThan(before * 0.4f)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    // ---------------------------------------------------------------------------------------------- photos

    /** A textured gradient with a dark dust spot and a red object (synthetic, 1500 x 1000). */
    private fun dustyJpeg(): ByteArray {
        val w = 1500
        val h = 1000
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = ((x * 7919 + y * 104729) % 23) - 11
            val v = (90 + 80 * x / w + 40 * y / h + n).coerceIn(0, 255)
            android.graphics.Color.rgb((v * 0.9).toInt(), v, (v * 1.1).toInt().coerceAtMost(255))
        }
        val bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }
        paint.color = android.graphics.Color.rgb(40, 40, 45)
        canvas.drawCircle(DUST_X * w, DUST_Y * h, DUST_R * w, paint)
        paint.color = android.graphics.Color.rgb(220, 20, 20)
        // Smaller than the default brush's band (1.2 % of the long edge each side of the stroke).
        canvas.drawRect(
            (OBJECT_X - 0.015f) * w,
            (OBJECT_Y - 0.008f) * h,
            (OBJECT_X + 0.015f) * w,
            (OBJECT_Y + 0.008f) * h,
            paint,
        )
        return jpeg(bitmap)
    }

    /**
     * The public-domain portrait with a red pupil painted on the first detected face's right eye; returns the JPEG
     * and the eye as image fractions.
     */
    private fun redEyePortrait(): Pair<ByteArray, Offset> {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("faces/studio-trio.jpg")
            .use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        fun lin(c: Int) = (c / 255f).toDouble().pow(2.2).toFloat()
        val rgb = FloatArray(w * h * 3) { i ->
            val p = px[i / 3]
            lin(
                when (i % 3) {
                    0 -> android.graphics.Color.red(p)
                    1 -> android.graphics.Color.green(p)
                    else -> android.graphics.Color.blue(p)
                },
            )
        }
        val face = FaceDetector.shipped().detect(FaceInput.from(PipelineInput.linearSrgb(w, h, rgb))).first()
        val eye = Offset(face.keypoints[0], face.keypoints[1])
        val pupil = face.width * w * PUPIL_SHARE
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(200, 25, 20)
        }
        Canvas(bitmap).drawCircle(eye.x * w, eye.y * h, pupil, paint)
        return jpeg(bitmap) to eye
    }

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()

    private fun photo(name: String, bytes: ByteArray): Uri =
        TestPhotos.insert(TestPhotos.uniqueName(name), "image/jpeg", bytes)

    // ---------------------------------------------------------------------------------------------- editor

    private fun open(uri: Uri, block: () -> Unit) {
        TestPhotos.launchView(uri, "image/jpeg").use {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
            compose.waitForIdle()
            block()
        }
        Thread.sleep(WRITE_MS)
    }

    private fun enterHeal() {
        click(EditorTags.tool(ToolId.HEAL))
        compose.waitUntilAtLeastOneExists(hasTestTag(HealTags.CONTROLS), TIMEOUT_MS)
    }

    private fun fields(): List<HistoryField> = history.entries.last().label.changes.map { it.field }

    private fun proxySize(): Pair<Int, Int> = checkNotNull(EditorMetrics.proxySize)

    /** The image fraction ([x], [y]) on the canvas at fit (the photo fitted and centred in the photo slot). */
    private fun onCanvas(x: Float, y: Float, canvasW: Float, canvasH: Float): Offset {
        val (w, h) = proxySize()
        val s = min(canvasW / w, canvasH / h)
        return Offset((canvasW - w * s) / 2f + x * w * s, (canvasH - h * s) / 2f + y * h * s)
    }

    private fun tapOnImage(x: Float, y: Float) {
        compose.onNodeWithTag(EditorTags.CANVAS).performTouchInput {
            val at = onCanvas(x, y, width.toFloat(), height.toFloat())
            down(at)
            up()
        }
        compose.waitForIdle()
    }

    private fun swipeOnImage(from: Offset, to: Offset) {
        compose.onNodeWithTag(EditorTags.CANVAS).performTouchInput {
            swipe(
                onCanvas(from.x, from.y, width.toFloat(), height.toFloat()),
                onCanvas(to.x, to.y, width.toFloat(), height.toFloat()),
                SWIPE_MS,
            )
        }
        compose.waitForIdle()
    }

    /** The healed proxy's mean over a 5 x 5 pixel block at image fraction ([x], [y]) (RGB, the input encoding). */
    private fun readHealed(w: Int, h: Int, x: Float, y: Float): FloatArray = runBlocking {
        val cx = (x * w).toInt()
        val cy = (y * h).toInt()
        val block = checkNotNull(EditorMetrics.renderer).readHealed(cx - 2, cy - 2, cx + 3, cy + 3)
        FloatArray(3) { c -> (0 until 25).map { block[it * 3 + c] }.average().toFloat() }
    }

    private fun luma(rgb: FloatArray): Float = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]

    private fun redness(rgb: FloatArray): Float = (rgb[0] - maxOf(rgb[1], rgb[2])) / (rgb.sum() + 1e-4f)

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TAG = "REVEAL_HEAL_TEST"
        const val TIMEOUT_MS = 30_000L
        const val REMOVE_TIMEOUT_MS = 120_000L
        const val WRITE_MS = 500L
        const val SWIPE_MS = 600L
        const val QUALITY = 95
        const val DUST_X = 0.3f
        const val DUST_Y = 0.35f
        const val DUST_R = 0.008f
        const val OBJECT_X = 0.7f
        const val OBJECT_Y = 0.6f
        const val PUPIL_SHARE = 0.035f
    }
}
