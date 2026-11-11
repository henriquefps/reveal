package dev.hfps.reveal.editor

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.recipe.ControlId
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tablet editor (M2/E) on a tablet emulator (`EMULATOR_LCD=2560x1600@274 EMULATOR_DEVICE=pixel_tablet`, a Galaxy
 * Tab S9's screen): the filmstrip moves between photos keeping each edit, the panel's width is remembered, a stylus
 * moves a curve point while a finger touching at the same time is ignored, a rotation keeps the open panel and the
 * steps. Skipped on windows narrower than the tablet layout (840 dp). Locale-independent: tags and recipe values only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TabletEditorTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val folder = SyntheticFolder("tablet-strip")

    @Before
    fun tabletOnly() {
        assumeTrue("needs the tablet layout", context.resources.configuration.screenWidthDp >= TABLET_DP)
    }

    @After
    fun tearDown() = folder.delete()

    // ------------------------------------------------------------------ filmstrip

    @Test
    fun filmstripMovesToTheNextPhotoAndBackWithTheEditKept() {
        val rows = photos(4)
        launchEditor(rows[0]).use {
            waitDeveloped()
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.filmstripCell(rows[3].versionId)), TIMEOUT_MS)
            shot("editor-filmstrip")
            typeExposure("+1")
            assertThat(committedExposure()).isWithin(EPS).of(1f)
            val stepsOnFirst = history().entries.size

            // A tap on the next thumbnail: a new editor on that photo, clean.
            compose.onNodeWithTag(EditorTags.filmstripCell(rows[1].versionId)).performClick()
            waitForPhoto(rows[1])
            assertThat(committedExposure()).isWithin(EPS).of(0f)
            assertThat(selected(EditorTags.filmstripCell(rows[1].versionId))).isTrue()

            // The keyboard's left arrow goes back: the edit and its history are there.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            waitForPhoto(rows[0])
            assertThat(committedExposure()).isWithin(EPS).of(1f)
            assertThat(history().entries.size).isEqualTo(stepsOnFirst)

            // Ctrl+Z undoes it; the right arrow moves on.
            instrumentation.sendKeySync(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON),
            )
            instrumentation.sendKeySync(
                KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON),
            )
            compose.waitUntil(TIMEOUT_MS) { abs(committedExposure()) < EPS }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitForPhoto(rows[1])
        }
    }

    @Test
    fun panelResizeIsRemembered() {
        val rows = photos(2)
        var widened: Float
        launchEditor(rows[0]).use { scenario ->
            waitDeveloped()
            val before = bounds(EditorTags.PANEL).width
            // Drag the panel's left edge 120 px to the left: the panel grows by as much (within its range).
            compose.onNodeWithTag(EditorTags.PANEL_RESIZE).performTouchInput {
                swipeLeft(startX = centerX, endX = centerX - DRAG_PX, durationMillis = 400)
            }
            compose.waitForIdle()
            widened = bounds(EditorTags.PANEL).width
            assertThat(widened).isGreaterThan(before + DRAG_PX / 2)
            // A recreation (rotation, fold) and the stored preference both keep it.
            scenario.recreate()
            waitDeveloped()
            assertThat(bounds(EditorTags.PANEL).width).isWithin(2f).of(widened)
        }
        launchEditor(rows[1]).use {
            waitDeveloped()
            assertThat(bounds(EditorTags.PANEL).width).isWithin(2f).of(widened)
        }
    }

    // ------------------------------------------------------------------ stylus

    @Test
    fun aStylusMovesACurvePointWhileAFingerIsIgnored() {
        val rows = photos(1)
        launchEditor(rows[0]).use {
            waitDeveloped()
            click(EditorTags.TONE_CURVE_ROW)
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.CURVE_EDITOR), TIMEOUT_MS)
            val curve = bounds(EditorTags.CURVE_EDITOR)
            val photo = bounds(EditorTags.CANVAS)

            // Hovering pen: the tooltip appears over the curve.
            val hoverAt = curve.left + curve.width * 0.25f to curve.top + curve.height * 0.75f
            inject(stylus(MotionEvent.ACTION_HOVER_ENTER, hoverAt, hover = true))
            inject(stylus(MotionEvent.ACTION_HOVER_MOVE, hoverAt, hover = true))
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.HOVER_TOOLTIP), TIMEOUT_MS)
            shot("pen-hover-curve")
            inject(stylus(MotionEvent.ACTION_HOVER_EXIT, hoverAt, hover = true))

            // The pen lands on the middle of the straight curve, a finger taps the photo while it drags the new point
            // up by a fifth of the editor; a tap on the photo would hide the chrome, so the panel must stay.
            val start = curve.center.x to curve.center.y
            val end = curve.center.x to curve.center.y - curve.height * 0.2f
            val finger = photo.center.x to photo.center.y
            val down = SystemClock.uptimeMillis()
            inject(stylus(MotionEvent.ACTION_DOWN, start, downTime = down))
            stepTo(start, (start.first to start.second - curve.height * 0.05f), down)
            inject(
                withFinger(
                    MotionEvent.ACTION_POINTER_DOWN,
                    start.first to start.second - curve.height * 0.05f,
                    finger,
                    down,
                ),
            )
            for (i in 1..STEPS) {
                val t = i / STEPS.toFloat()
                val p = start.first to start.second - curve.height * (0.05f + 0.15f * t)
                inject(withFinger(MotionEvent.ACTION_MOVE, p, finger, down))
            }
            inject(withFinger(MotionEvent.ACTION_POINTER_UP, end, finger, down))
            inject(stylus(MotionEvent.ACTION_UP, end, downTime = down))
            compose.waitForIdle()

            compose.waitUntil(TIMEOUT_MS) { curvePoints().size == 3 }
            val middle = curvePoints()[1]
            assertThat(middle.first).isWithin(0.03f).of(0.5f)
            assertThat(middle.second).isWithin(0.04f).of(0.7f)
            // The finger's tap never arrived: the chrome (and the panel) are still there.
            compose.onNodeWithTag(EditorTags.PANEL).assertExists()
        }
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun rotationKeepsTheOpenPanelAndTheStepCount() {
        val rows = photos(1)
        launchEditor(rows[0]).use { scenario ->
            waitDeveloped()
            typeExposure("+1")
            typeExposure("+0,5")
            val steps = history().entries.size
            val renderer = EditorMetrics.renderer
            click(EditorTags.tool(ToolId.COLOUR))
            click(EditorTags.MIXER_ROW)
            val hueRed = EditorTags.control(ControlId.HUE_RED)
            compose.waitUntilAtLeastOneExists(hasTestTag(hueRed), TIMEOUT_MS)

            // Large screens ignore an app's orientation request (Android 12L+): turn the display itself.
            val automation = instrumentation.uiAutomation
            try {
                // A quarter turn from where the display is (tablets differ in their natural orientation), and back.
                var start = 0
                scenario.onActivity { start = it.display.rotation }
                for (rotation in listOf((start + 1) % QUARTERS, start)) {
                    val wasPortrait = portrait()
                    automation.setRotation(rotation)
                    compose.waitUntil(TIMEOUT_MS) { portrait() != wasPortrait }
                    waitDeveloped()
                    compose.waitUntilAtLeastOneExists(hasTestTag(hueRed), TIMEOUT_MS)
                    assertThat(history().entries.size).isEqualTo(steps)
                    assertThat(committedExposure()).isWithin(EPS).of(0.5f)
                    // Same ViewModel, same renderer: only the surface was recreated.
                    assertThat(EditorMetrics.renderer).isSameInstanceAs(renderer)
                    if (portrait()) {
                        Thread.sleep(ROTATION_SETTLE_MS)
                        shot("editor-portrait-mixer")
                    }
                }
            } finally {
                automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** [count] unique JPEGs in a synthetic folder, indexed, in the grid's order. */
    private fun photos(count: Int): List<PhotoRow> {
        for (i in 0 until count) {
            folder.jpeg("IMG_%04d.JPG".format(i), i, folder.gradientJpeg(i, 1200, 800))
            // A unique tail: each run is new content, so no earlier run's edits come back (ADR 0006 "Identity").
            File(folder.dir, "IMG_%04d.JPG".format(i)).appendBytes("\nreveal-test ${System.nanoTime()}\n".toByteArray())
        }
        val (sourceId, _) = folder.addAndIndex()
        val rows = runBlocking { folder.library.photoList(PhotoQuery(scope = LibraryScope.Source(sourceId))) }
        check(rows.size == count) { "indexed ${rows.size} of $count" }
        runBlocking { folder.library.setLastSelection("f:$sourceId") }
        return rows
    }

    /** The library reopens the folder; its grid -> viewer -> Edit opens the editor with the folder's filmstrip. */
    private fun launchEditor(row: PhotoRow): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(row.id)), TIMEOUT_MS)
        Thread.sleep(THUMBS_MS)
        shot("library-two-pane")
        compose.onNodeWithTag(LibraryTags.cell(row.id)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.EDIT), TIMEOUT_MS)
        compose.onNodeWithTag(ViewerTags.EDIT).performClick()
        return scenario
    }

    private fun waitDeveloped() {
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
        compose.waitForIdle()
    }

    /** Waits until the editor shows [row] (its thumbnail is the selected one) and has developed it. */
    private fun waitForPhoto(row: PhotoRow) {
        compose.waitUntil(TIMEOUT_MS) {
            runCatching { selected(EditorTags.filmstripCell(row.versionId)) }.getOrDefault(false)
        }
        waitDeveloped()
        compose.waitUntil(TIMEOUT_MS) { EditorMetrics.recipes != null }
    }

    /** A screenshot for the PR and docs (synthetic photos only) in the app's `files/tablet-shots`. */
    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(context.filesDir, "tablet-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun portrait(): Boolean {
        val config = context.resources.configuration
        return config.screenHeightDp > config.screenWidthDp
    }

    private fun selected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun committedExposure(): Float = checkNotNull(EditorMetrics.recipes).invoke().second.light.exposureEv

    private fun history() = checkNotNull(EditorMetrics.history).invoke()

    /** The committed RGB curve's points (x, y), the straight line's two ends when empty. */
    private fun curvePoints(): List<Pair<Float, Float>> {
        val points = checkNotNull(EditorMetrics.recipes).invoke().second.light.curve.rgb
        return points.map { it.x to it.y }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private fun typeExposure(text: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.EXPOSURE), TIMEOUT_MS)
        compose.onNodeWithTag(EditorTags.EXPOSURE + RevealSliderTags.VALUE_SUFFIX, useUnmergedTree = true)
            .performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
        compose.onNodeWithTag(NumericInputTags.FIELD).performTextReplacement(text)
        compose.onNodeWithTag(NumericInputTags.DONE).performClick()
        compose.waitForIdle()
    }

    @Suppress("unused")
    private fun button(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).onChildren().filterToOne(hasClickAction())

    private fun inject(event: MotionEvent) {
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun stepTo(from: Pair<Float, Float>, to: Pair<Float, Float>, down: Long) {
        for (i in 1..STEPS) {
            val t = i / STEPS.toFloat()
            val p = (from.first + (to.first - from.first) * t) to (from.second + (to.second - from.second) * t)
            inject(stylus(MotionEvent.ACTION_MOVE, p, downTime = down))
        }
    }

    private fun stylus(
        action: Int,
        at: Pair<Float, Float>,
        hover: Boolean = false,
        downTime: Long = SystemClock.uptimeMillis(),
    ): MotionEvent {
        val props = arrayOf(pointer(0, MotionEvent.TOOL_TYPE_STYLUS))
        val coords = arrayOf(coords(at, pressure = if (hover) 0f else 1f))
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    /** A two-pointer event: the stylus (pointer 0) at [pen], a finger (pointer 1) at [finger]. */
    private fun withFinger(
        action: Int,
        pen: Pair<Float, Float>,
        finger: Pair<Float, Float>,
        downTime: Long,
    ): MotionEvent {
        val masked = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }
        val props = arrayOf(pointer(0, MotionEvent.TOOL_TYPE_STYLUS), pointer(1, MotionEvent.TOOL_TYPE_FINGER))
        val coords = arrayOf(coords(pen, 1f), coords(finger, 1f))
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), masked, 2, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun pointer(id: Int, tool: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = tool
    }

    private fun coords(at: Pair<Float, Float>, pressure: Float) = MotionEvent.PointerCoords().apply {
        x = at.first
        y = at.second
        this.pressure = pressure
        size = 1f
    }

    @Suppress("unused")
    private val access get() = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)

    private companion object {
        const val TABLET_DP = 840
        const val TIMEOUT_MS = 30_000L
        const val EPS = 1e-3f
        const val DRAG_PX = 120f
        const val STEPS = 8
        const val THUMBS_MS = 1_500L
        const val QUARTERS = 4
        const val ROTATION_SETTLE_MS = 1_500L
    }
}
