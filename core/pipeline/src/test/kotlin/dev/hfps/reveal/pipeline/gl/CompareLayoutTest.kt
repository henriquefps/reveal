package dev.hfps.reveal.pipeline.gl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompareLayoutTest {
    private val window = 1000 to 2000
    private val image = 3000 to 2000

    /** Image point the output shader shows at window pixel ([x], [y]) for [view] on this window. */
    private fun imageAt(view: ViewTransform, x: Float, y: Float): Pair<Float, Float> =
        ((x - window.first / 2f) / view.scale + view.centerX) to ((y - window.second / 2f) / view.scale + view.centerY)

    private fun panes(mode: CompareMode, frame: ViewFrame, view: ViewTransform) =
        CompareLayout.panes(mode, frame, window.first, window.second, image.first, image.second, view)

    @Test
    fun theWholeWindowFrameLeavesTheViewUnchanged() {
        val view = ViewTransform(0.3f, 1500f, 1000f)
        val p = panes(CompareMode.Off, ViewFrame.whole(window.first, window.second), view)
        assertThat(p).hasSize(1)
        assertThat(p[0].view).isEqualTo(view)
        assertThat(p[0].clip).isNull()
        assertThat(p[0].original).isFalse()
    }

    @Test
    fun theViewCentreIsShownAtTheFrameCentre() {
        // Portrait editor: photo in the top 1200 px, the panel below.
        val frame = ViewFrame(0f, 0f, 1000f, 1200f)
        val view = ViewTransform(1000f / 3000f, 1500f, 1000f)
        val shown = panes(CompareMode.Off, frame, view)[0].view
        val (ix, iy) = imageAt(shown, frame.centerX, frame.centerY)
        assertThat(ix).isWithin(1e-3f).of(1500f)
        assertThat(iy).isWithin(1e-3f).of(1000f)
        // The top-left image corner lands at the frame's top edge (fit width, centred vertically in the frame).
        val (cx, cy) = imageAt(shown, 0f, frame.centerY - 1000f * view.scale)
        assertThat(cx).isWithin(1e-2f).of(0f)
        assertThat(cy).isWithin(1e-2f).of(0f)
    }

    @Test
    fun splitDrawsTheEditThenTheOriginalClippedBeforeTheDivider() {
        val frame = ViewFrame(0f, 100f, 1000f, 1200f)
        val view = ViewTransform(0.5f, 1500f, 1000f)
        val lr = panes(CompareMode.Split(leftRight = true, position = 0.25f), frame, view)
        assertThat(lr.map { it.original }).containsExactly(false, true).inOrder()
        assertThat(lr[0].clip).isNull()
        assertThat(lr[1].clip).isEqualTo(ViewFrame(0f, 100f, 250f, 1200f))
        assertThat(lr[1].view).isEqualTo(lr[0].view)
        val tb = panes(CompareMode.Split(leftRight = false, position = 0.5f), frame, view)
        assertThat(tb[1].clip).isEqualTo(ViewFrame(0f, 100f, 1000f, 600f))
    }

    @Test
    fun sideBySideStacksATallFrameAndKeepsTheZoomRelativeToFit() {
        val frame = ViewFrame(0f, 0f, 1000f, 1600f)
        val fit = CompareLayout.fit(frame, image.first, image.second)
        val view = ViewTransform(fit * 2f, 1500f, 1000f)
        val p = panes(CompareMode.SideBySide, frame, view)
        assertThat(p.map { it.original }).containsExactly(true, false).inOrder()
        val top = checkNotNull(p[0].clip)
        assertThat(top).isEqualTo(ViewFrame(0f, 0f, 1000f, 800f))
        val paneFit = CompareLayout.fit(top, image.first, image.second)
        assertThat(p[0].view.scale).isWithin(1e-6f).of(paneFit * 2f)
        // Each pane shows the view centre at its own centre.
        for (pane in p) {
            val clip = checkNotNull(pane.clip)
            val (ix, iy) = imageAt(pane.view, clip.centerX, clip.centerY)
            assertThat(ix).isWithin(1e-2f).of(1500f)
            assertThat(iy).isWithin(1e-2f).of(1000f)
        }
    }

    @Test
    fun sideBySideIsLeftRightInAWideFrame() {
        val (a, b) = CompareLayout.sideBySide(ViewFrame(10f, 0f, 1000f, 400f))
        assertThat(a).isEqualTo(ViewFrame(10f, 0f, 500f, 400f))
        assertThat(b).isEqualTo(ViewFrame(510f, 0f, 500f, 400f))
    }

    @Test
    fun theLoupeShowsItsImagePointAtItsCentreClippedToItsSquare() {
        val loupe = Loupe(centerX = 300f, centerY = 500f, size = 200f, imageX = 1234f, imageY = 567f, scale = 2f)
        val pane = CompareLayout.loupePane(loupe, window.first, window.second)
        assertThat(pane.clip).isEqualTo(ViewFrame(200f, 400f, 200f, 200f))
        assertThat(pane.original).isFalse()
        val (ix, iy) = imageAt(pane.view, 300f, 500f)
        assertThat(ix).isWithin(1e-3f).of(1234f)
        assertThat(iy).isWithin(1e-3f).of(567f)
    }

    @Test
    fun `the output rectangle covers the fitted photo and nothing of the chrome`() {
        // A 2400 x 1600 output fitted to the phone's 1080 px wide window (scale 0.45), centred.
        val fit = ViewTransform(0.45f, 1200f, 800f)
        val r = checkNotNull(CompareLayout.outputRect(fit, 1080, 2340, 2400, 1600))
        // 1080 x 720 centred vertically, one pixel of margin, clamped to the window.
        assertThat(r.toList()).isEqualTo(listOf(0, 809, 1080, 722))
        // A clip (side by side) bounds it; an output entirely off screen draws nothing.
        val clipped =
            checkNotNull(CompareLayout.outputRect(fit, 1080, 2340, 2400, 1600, ViewFrame(0f, 0f, 540f, 2340f)))
        assertThat(clipped.toList()).isEqualTo(listOf(0, 809, 540, 722))
        assertThat(CompareLayout.outputRect(ViewTransform(1f, -5000f, 800f), 1080, 2340, 2400, 1600)).isNull()
    }
}
