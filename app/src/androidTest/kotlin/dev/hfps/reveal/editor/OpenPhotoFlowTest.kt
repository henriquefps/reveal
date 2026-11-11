package dev.hfps.reveal.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real open flow: a content URI handed to Reveal with `ACTION_VIEW` opens the editor, which shows a developed GPU
 * frame (or a friendly error). The RAW case needs the public sample pushed into the app (CLAUDE.md, "Editor tests").
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OpenPhotoFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Suppress("NestedBlockDepth")
    @Test
    fun anUneditedJpegIsShownAsItsSourceWithinOneLevel() {
        val jpeg = TestPhotos.syntheticJpeg()
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-card.jpg"), "image/jpeg", jpeg)
        try {
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                val renderer = checkNotNull(EditorMetrics.renderer)
                val rendered = runBlocking { renderer.renderOutput(Recipe.DEFAULT, OutputSettings(dither = true)) }
                val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                val pixels = IntArray(source.width * source.height)
                source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                assertThat(rendered.size).isEqualTo(pixels.size * 4)
                var worst = 0
                var sum = 0L
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val channels = intArrayOf(Color.red(p), Color.green(p), Color.blue(p))
                    for (c in 0..2) {
                        val d = abs((rendered[i * 4 + c].toInt() and 0xFF) - channels[c])
                        worst = maxOf(worst, d)
                        sum += d
                    }
                }
                Log.i(TAG, "jpeg round trip: max $worst levels, mean ${sum.toDouble() / (pixels.size * 3)}")
                assertThat(worst).isAtMost(1)
                assertScreenShowsPhoto()
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun aPublicSonyArwOpensWithItsPreviewThenTheDevelopedFrame() {
        val sample = TestPhotos.sample(ARW_SAMPLE)
        assumeTrue("push $ARW_SAMPLE into files/samples (CLAUDE.md)", sample != null)
        val uri = TestPhotos.insert(TestPhotos.uniqueName("DSC00002.ARW"), "image/x-sony-arw", sample!!)
        try {
            TestPhotos.launchView(uri, "image/x-sony-arw").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                assertThat(EditorMetrics.previewMs).isAtLeast(0L)
                assertThat(EditorMetrics.previewMs).isLessThan(EditorMetrics.developedMs)
                Log.i(TAG, "ARW ${EditorMetrics.summary()}")
                assertScreenShowsPhoto()
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun aDamagedRawShowsTheErrorState() {
        // A TIFF header and then garbage: classified as a RAW by name and magic, rejected by LibRaw.
        val bytes = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0, 0, 0) + ByteArray(64 * 1024) { (it * 31).toByte() }
        val uri = TestPhotos.insert(TestPhotos.uniqueName("broken.ARW"), "image/x-sony-arw", bytes)
        try {
            TestPhotos.launchView(uri, "image/x-sony-arw").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.ERROR), TIMEOUT_MS)
                compose.onNodeWithTag(EditorTags.ERROR).assertExists()
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun aFileThatIsNotAnImageShowsTheErrorState() {
        val uri = TestPhotos.insert(TestPhotos.uniqueName("notes.jpg"), "image/jpeg", "not a photo".toByteArray())
        try {
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.ERROR), TIMEOUT_MS)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    /** The middle of the screen is the photo, not the editor's flat surround. */
    private fun assertScreenShowsPhoto() {
        compose.waitForIdle()
        val shot: Bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val cx = shot.width / 2
        val cy = shot.height * 2 / 5
        var different = 0
        val radius = shot.width / 8
        for (dy in -radius..radius step 8) {
            for (dx in -radius..radius step 8) {
                val p = shot.getPixel(cx + dx, cy + dy)
                if (abs(Color.red(p) - SURROUND) + abs(Color.green(p) - SURROUND) + abs(Color.blue(p) - SURROUND) >
                    12
                ) {
                    different++
                }
            }
        }
        assertThat(different).isGreaterThan(0)
    }

    private companion object {
        const val TAG = "REVEAL_EDITOR_TEST"
        const val ARW_SAMPLE = "sony-zv-e10.arw"
        const val TIMEOUT_MS = 30_000L
        const val SURROUND = 0x1C
    }
}
