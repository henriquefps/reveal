package dev.hfps.reveal.editor

import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.masklab.LabResult
import dev.hfps.reveal.feature.editor.masklab.MaskLabSession
import dev.hfps.reveal.ml.MaskKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The debug mask lab (docs/adr/0021) in the real editor: More ⋯ opens it on a public-domain photo of three people,
 * the automatic subject, the people mask and a tap with MagicTouch each produce a plausible mask. Locale-independent
 * (test tags and the lab's state, never strings). `-e shots true` saves a screenshot after each run in the app's
 * external files (`Android/data/dev.hfps.reveal.debug/files/mask-lab-*.png`, for the PR).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MaskLabTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun labRunsTheModelsOnTheOpenPhoto() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("faces/studio-trio.jpg")
            .use { it.readBytes() }
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-mask-lab.jpg"), "image/jpeg", bytes)
        try {
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                compose.onNodeWithTag(EditorTags.MORE).performClick()
                compose.waitUntilExactlyOneExists(hasTestTag(EditorTags.MENU_MASK_LAB), TIMEOUT_MS)
                compose.onNodeWithTag(EditorTags.MENU_MASK_LAB).performClick()
                compose.waitUntilExactlyOneExists(hasTestTag("mask-lab-overlay"), TIMEOUT_MS)

                // The default choice: the automatic subject (MagicTouch at the saliency peaks).
                val auto = result(null)
                log("auto subject", auto)
                shot("auto-subject")
                assertThat(MaskLabSession.kind).isEqualTo(MaskKind.SUBJECT)
                assertThat(auto.coverage).isIn(com.google.common.collect.Range.open(0.05f, 0.95f))

                // People with LR-ASPP: the crew fills a good part of the frame.
                select("mask-lab-model-lraspp-mnv3-voc")
                val lraspp = result(auto)
                select("mask-lab-kind-PEOPLE")
                val people = result(lraspp)
                log("people", people)
                shot("people")
                assertThat(MaskLabSession.kind).isEqualTo(MaskKind.PEOPLE)
                assertThat(people.coverage).isGreaterThan(0.2f)

                // MagicTouch waits for a tap, then selects what is under it (the middle astronaut).
                select("mask-lab-model-magic-touch")
                compose.onNodeWithTag("mask-lab-overlay").performTouchInput { click(center) }
                val tapped = result(people)
                log("tap", tapped)
                shot("tap")
                assertThat(tapped.coverage).isIn(com.google.common.collect.Range.open(0.02f, 0.8f))

                compose.onNodeWithTag(EditorTags.MODE_CANCEL).performClick()
                compose.waitUntilDoesNotExist(hasTestTag("mask-lab-overlay"), TIMEOUT_MS)
                assertThat(MaskLabSession.photo).isNull()
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    private fun shot(name: String) {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("shots") != "true") return
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(target.getExternalFilesDir(null), "mask-lab-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun select(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** The next result after [previous] (a new run), once the lab is idle. */
    private fun result(previous: LabResult?): LabResult {
        compose.waitUntil(RUN_MS) {
            val r = MaskLabSession.result
            !MaskLabSession.running && r != null && r !== previous
        }
        assertThat(MaskLabSession.error).isNull()
        return checkNotNull(MaskLabSession.result)
    }

    private fun log(what: String, r: LabResult) = Log.i(
        TAG,
        "$what: ${r.backend} load ${r.loadMs} ms inference ${r.inferenceMs} ms total ${r.totalMs} ms " +
            "refine ${r.refineMs} ms coverage ${r.coverage}",
    )

    private companion object {
        const val TAG = "REVEAL_EDITOR_TEST"
        const val TIMEOUT_MS = 30_000L
        const val RUN_MS = 60_000L
    }
}
