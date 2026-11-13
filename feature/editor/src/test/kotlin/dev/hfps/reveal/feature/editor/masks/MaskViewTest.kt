package dev.hfps.reveal.feature.editor.masks

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Transform
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Test

/** Masks on screen: image fractions through the geometry and zoom, handle drags, pressure (docs/adr/0022 "M4/B"). */
class MaskViewTest {
    private val w = 3000
    private val h = 2000

    private fun view(recipe: Recipe, scale: Float = 0.3f): MaskView {
        val mapping = GeometryMapping.of(recipe, null, w, h)
        return MaskView(mapping, ViewTransform(scale, mapping.outputWidth / 2f, mapping.outputHeight / 2f), 1080f, 900f)
    }

    private val points = listOf(Offset(0.5f, 0.5f), Offset(0.1f, 0.2f), Offset(0.83f, 0.71f), Offset(0.33f, 0.9f))

    private fun assertRoundTrip(v: MaskView, tolerance: Float) {
        for (p in points) {
            val slot = checkNotNull(v.toSlot(p.x, p.y))
            val back = checkNotNull(v.toImage(slot))
            assertThat(back.x).isWithin(tolerance).of(p.x)
            assertThat(back.y).isWithin(tolerance).of(p.y)
        }
    }

    @Test
    fun `image fractions round-trip through the plain view`() {
        val v = view(Recipe())
        assertRoundTrip(v, 1e-5f)
        // The centre of the image is the centre of the slot at fit.
        val c = checkNotNull(v.toSlot(0.5f, 0.5f))
        assertThat(c.x).isWithin(1e-3f).of(540f)
        assertThat(c.y).isWithin(1e-3f).of(450f)
    }

    @Test
    fun `handles follow a crop, a quarter turn, a flip and a straighten`() {
        val geometry = Geometry(crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f), angle = 4f, rotate = 1, flipH = true)
        val v = view(Recipe(geometry = geometry))
        assertRoundTrip(v, 1e-4f)
        // A quarter turn clockwise: the image's top-left goes to the right of its bottom-left on screen.
        val topLeft = checkNotNull(v.toSlot(0.2f, 0.3f))
        val bottomLeft = checkNotNull(v.toSlot(0.2f, 0.7f))
        assertThat(abs(topLeft.y - bottomLeft.y)).isLessThan(abs(topLeft.x - bottomLeft.x))
    }

    @Test
    fun `the inverse of the Transform warp is found numerically`() {
        val geometry = Geometry(transform = Transform(vertical = 35f, horizontal = -20f, rotate = 3f))
        val v = view(Recipe(geometry = geometry))
        assertThat(v.mapping.warp).isNotNull()
        assertRoundTrip(v, 2e-4f)
    }

    @Test
    fun `isotropic plane keeps circles round on a 3 x 2 image`() {
        val v = view(Recipe())
        val iso = v.toIso(Offset(1f, 1f))
        assertThat(iso.x).isWithin(1e-6f).of(1f)
        assertThat(iso.y).isWithin(1e-6f).of(2f / 3f)
        assertThat(v.fromIso(iso).y).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `dragging a linear gradient's pin moves it, its start line keeps the end line, its centre line turns it`() {
        val v = view(Recipe())
        val c = MaskComponent.Linear(x0 = 0.5f, y0 = 0.2f, x1 = 0.5f, y1 = 0.6f, feather = 100f)
        val moved = MaskHandles.dragLinear(c, HandleId.CENTRE, Offset(0.5f, 0.3f), Offset(0.6f, 0.35f), v)
        assertThat(moved.y0 - c.y0).isWithin(1e-5f).of(0.05f * 1.5f)
        assertThat(moved.x1 - c.x1).isWithin(1e-5f).of(0.1f)
        val lines = MaskHandles.linearLines(c, v)
        val dragged = MaskHandles.dragLinear(c, HandleId.START, lines.start, lines.start - lines.axis * 0.05f, v)
        val after = MaskHandles.linearLines(dragged, v)
        assertThat((after.end - lines.end).getDistance()).isWithin(1e-5f).of(0f)
        assertThat((after.start - (lines.start - lines.axis * 0.05f)).getDistance()).isWithin(1e-5f).of(0f)
        assertThat(dragged.feather).isEqualTo(c.feather)
        // A quarter turn of the centre line around the pin.
        val centre = lines.centre
        val turned = MaskHandles.dragLinear(c, HandleId.ROTATE, centre + Offset(0.1f, 0f), centre + Offset(0f, 0.1f), v)
        val t = MaskHandles.linearLines(turned, v)
        assertThat(t.axis.x).isWithin(1e-4f).of(-1f)
        assertThat((t.centre - centre).getDistance()).isWithin(1e-5f).of(0f)
    }

    @Test
    fun `dragging a radial gradient's handles sets radius, rotation and feather`() {
        val v = view(Recipe())
        val c = MaskComponent.Radial(cx = 0.5f, cy = 0.5f, rx = 0.2f, ry = 0.1f, rotation = 0f, feather = 50f)
        val f = MaskHandles.radialFrame(c, v)
        val wider = MaskHandles.dragRadial(c, HandleId.RADIUS_X, f.toIso(0.2f, 0f), f.toIso(0.3f, 0.02f), v)
        assertThat(wider.rx).isWithin(1e-5f).of(0.3f)
        assertThat(wider.ry).isEqualTo(0.1f)
        val turned = MaskHandles.dragRadial(
            c,
            HandleId.ROTATE,
            f.centre + Offset(0f, -0.2f),
            f.centre + Offset(0.2f, 0f),
            v,
        )
        assertThat(turned.rotation).isWithin(1e-3f).of(90f)
        val soft = MaskHandles.dragRadial(c, HandleId.FEATHER, f.centre, f.toIso(0.2f * 0.25f, 0f), v)
        assertThat(soft.feather).isWithin(1e-3f).of(75f)
        val moved = MaskHandles.dragRadial(c, HandleId.CENTRE, f.centre, f.centre + Offset(0.1f, 0f), v)
        assertThat(moved.cx).isWithin(1e-5f).of(0.6f)
        // Never smaller than the minimum.
        assertThat(
            MaskHandles.dragRadial(c, HandleId.RADIUS_Y, f.centre, f.centre, v).ry,
        ).isEqualTo(MaskHandles.MIN_RADIUS)
    }

    @Test
    fun `the handle under a finger is found on screen, the pin first`() {
        val v = view(Recipe())
        val c = MaskComponent.Linear(x0 = 0.5f, y0 = 0.2f, x1 = 0.5f, y1 = 0.6f, feather = 100f)
        val knob = MaskHandles.isoLength(32f, v)
        val pin = checkNotNull(v.isoToSlot(MaskHandles.linearLines(c, v).centre))
        assertThat(hitHandle(c, v, pin + Offset(3f, 2f), 40f, knob)).isEqualTo(HandleId.CENTRE)
        val start = checkNotNull(v.toSlot(0.2f, 0.2f))
        assertThat(hitHandle(c, v, start, 40f, knob)).isEqualTo(HandleId.START)
        val centreLine = checkNotNull(v.toSlot(0.15f, 0.4f))
        assertThat(hitHandle(c, v, centreLine, 40f, knob)).isEqualTo(HandleId.ROTATE)
        assertThat(hitHandle(c, v, checkNotNull(v.toSlot(0.1f, 0.95f)), 40f, knob)).isNull()
        val r = MaskComponent.Radial(cx = 0.5f, cy = 0.5f, rx = 0.2f, ry = 0.1f)
        val edge = checkNotNull(v.isoToSlot(MaskHandles.radialFrame(r, v).toIso(0.2f, 0f)))
        assertThat(hitHandle(r, v, edge, 30f, knob)).isEqualTo(HandleId.RADIUS_X)
    }

    @Test
    fun `a pen's pressure goes into the stroke, a finger always paints at full pressure`() {
        assertThat(stroke(Offset(0.1f, 0.2f), 0.4f, pen = true).pressure).isEqualTo(0.4f)
        assertThat(stroke(Offset(0.1f, 0.2f), 1.7f, pen = true).pressure).isEqualTo(1f)
        assertThat(stroke(Offset(0.1f, 0.2f), 0.3f, pen = false).pressure).isEqualTo(1f)
    }

    @Test
    fun `the luminance map tints exactly the engine's luminance range`() {
        assertThat(LuminanceMap.coverage(0.75f, 50f, 100f, 10f)).isEqualTo(1f)
        assertThat(LuminanceMap.coverage(0.30f, 50f, 100f, 10f)).isEqualTo(0f)
        assertThat(LuminanceMap.coverage(0.45f, 50f, 100f, 10f)).isWithin(1e-6f).of(0.5f)
        // A hard edge at falloff 0.
        assertThat(LuminanceMap.coverage(0.49f, 50f, 80f, 0f)).isEqualTo(0f)
        assertThat(LuminanceMap.coverage(0.5f, 50f, 80f, 0f)).isEqualTo(1f)
    }

    @Test
    fun `angles normalise into a half turn either way`() {
        assertThat(MaskHandles.normaliseDegrees(190f)).isEqualTo(-170f)
        assertThat(MaskHandles.normaliseDegrees(-540f)).isEqualTo(-180f)
        assertThat(abs(MaskHandles.normaliseDegrees(720f))).isEqualTo(0f)
        assertThat(hypot(1f, 0f)).isEqualTo(1f)
    }
}
