@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import dev.hfps.reveal.pipeline.recipe.StrokePressure
import kotlin.random.Random
import org.junit.Test

/**
 * The brush options of M4/B evaluated by the engine (docs/adr/0022 "Brush raster" and "Brush auto-mask"): density,
 * what pressure drives, and the auto-mask; and [MaskBounds] (M4/P), the box outside which a mask covers nothing.
 */
class BrushOptionsTest {
    private val w = 64
    private val h = 48
    private val whole = MaskRaster.Region(0, 0, w, h)

    private fun line(n: Int = 20, p: (Int) -> Float = { 1f }) =
        List(n) { StrokePoint(0.15f + 0.7f * it / (n - 1), 0.5f, p(it)) }

    /** M4/A's raster, written out: `cov += (1 - cov) a`, `cov *= 1 - a`. */
    @Suppress("NestedBlockDepth")
    private fun m4a(dabs: Dabs): FloatArray {
        val out = FloatArray(w * h)
        for (k in 0 until dabs.size) {
            val o = k * Dabs.DAB_FLOATS
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = MaskRaster.dabAlpha(dabs.data, o, x, y)
                    if (a <= 0f) continue
                    val i = y * w + x
                    out[i] = if (dabs.data[o + 5] != 0f) out[i] * (1f - a) else out[i] + (1f - out[i]) * a
                }
            }
        }
        return FloatArray(out.size) { MaskMath.store(out[it]) }
    }

    @Test
    fun `density 100 and pressure both are M4-A bit for bit`() {
        val random = Random(7)
        val strokes = List(4) { s ->
            BrushStroke.raw(
                List(25) { StrokePoint(random.nextFloat(), random.nextFloat(), random.nextFloat()) },
                size = 0.05f + 0.04f * s,
                feather = 25f * s,
                flow = 40f + 15f * s,
                erase = s == 2,
            )
        }
        val dabs = Dabs.of(strokes, w, h)
        assertThat(MaskRaster.brush(dabs, whole).toList()).isEqualTo(m4a(dabs).toList())
    }

    @Test
    fun `density caps what a stroke paints and what an eraser removes`() {
        val paint = BrushStroke.raw(line(), size = 0.2f, feather = 0f, flow = 100f, density = 40f)
        val painted = MaskRaster.brush(Dabs.of(listOf(paint), w, h), whole)
        assertThat(painted.max()).isWithin(1e-3f).of(0.4f)
        // Over full coverage, one density-30 eraser dab at full flow leaves 70 %.
        val full = BrushStroke.raw(line(), size = 0.3f, feather = 0f, flow = 100f)
        val dab = listOf(StrokePoint(0.5f, 0.5f, 1f))
        val eraser = BrushStroke.raw(dab, size = 0.2f, feather = 0f, flow = 100f, erase = true, density = 30f)
        val erased = MaskRaster.brush(Dabs.of(listOf(full, eraser), w, h), whole)
        val centre = erased[(h / 2) * w + w / 2]
        assertThat(centre).isWithin(1e-3f).of(0.7f)
        // Painting again over a coverage above the density leaves it as it is.
        val low = BrushStroke.raw(line(), size = 0.2f, feather = 0f, flow = 100f, density = 20f)
        val over = MaskRaster.brush(Dabs.of(listOf(full, low), w, h), whole)
        assertThat(over[(h / 2) * w + w / 2]).isEqualTo(1f)
    }

    @Test
    fun `pressure drives the flow, the size or both`() {
        val points = line { it / 19f }
        fun dabs(mode: StrokePressure) =
            Dabs.of(listOf(BrushStroke.raw(points, size = 0.2f, flow = 80f, pressure = mode)), w, h)
        val both = dabs(StrokePressure.BOTH)
        val flow = dabs(StrokePressure.FLOW)
        val size = dabs(StrokePressure.SIZE)
        val radius0 = 0.2f * 0.5f * w
        fun radii(d: Dabs) = (0 until d.size).map { d.data[it * Dabs.DAB_FLOATS + 2] }
        fun flows(d: Dabs) = (0 until d.size).map { d.data[it * Dabs.DAB_FLOATS + 4] }
        assertThat(radii(flow).distinct()).containsExactly(radius0)
        assertThat(flows(flow).max()).isGreaterThan(flows(flow).min())
        assertThat(flows(size).distinct()).containsExactly(0.8f)
        assertThat(radii(size).max()).isGreaterThan(radii(size).min())
        assertThat(radii(both).max()).isGreaterThan(radii(both).min())
        assertThat(flows(both).max()).isGreaterThan(flows(both).min())
        // A finger (p = 1) is the same in every mode.
        val finger = line()
        fun fingerDabs(mode: StrokePressure) =
            Dabs.of(listOf(BrushStroke.raw(finger, size = 0.2f, pressure = mode)), w, h).data.toList()
        assertThat(fingerDabs(StrokePressure.FLOW)).isEqualTo(fingerDabs(StrokePressure.BOTH))
        assertThat(fingerDabs(StrokePressure.SIZE)).isEqualTo(fingerDabs(StrokePressure.BOTH))
    }

    /** A pre-local image: left half one colour, right half another (OkLab, as the develop pass stores it). */
    private fun twoColours(): FloatArray = FloatArray(w * h * 4) { i ->
        val x = (i / 4) % w
        when (i % 4) {
            0 -> if (x < w / 2) 0.6f else 0.62f
            1 -> if (x < w / 2) 0.05f else -0.08f
            2 -> if (x < w / 2) 0.02f else 0.04f
            else -> 0f
        }
    }

    @Test
    fun `an auto-mask dab covers only the colour under its centre`() {
        val pre = twoColours()
        // Dabs centred left of the edge, big enough to cross it.
        val points = List(6) { StrokePoint(0.3f, 0.3f + 0.08f * it, 1f) }
        val auto = BrushStroke.raw(points, size = 0.5f, feather = 0f, flow = 100f, autoMask = true)
        val plain = BrushStroke.raw(points, size = 0.5f, feather = 0f, flow = 100f)
        val mask =
            Mask(
                "m",
                adjustments = LocalAdjustments(exposure = 1f),
                components = listOf(MaskComponent.Brush(strokes = listOf(auto))),
            )
        val plan = MaskPlan.of(listOf(mask), w, h)
        assertThat(plan.needsPre).isTrue()
        assertThat(MaskPlan.readsPre(mask)).isTrue()
        val cov = MaskRaster.coverage(plan, whole, pre).single()
        val plainCov = MaskRaster.brush(Dabs.of(listOf(plain), w, h), whole)
        val y = (0.45f * h).toInt()
        // Left of the edge: as without the auto-mask; right of it, inside the dab: nothing.
        assertThat(cov[y * w + w / 2 - 2]).isEqualTo(plainCov[y * w + w / 2 - 2])
        assertThat(plainCov[y * w + w / 2 + 2]).isGreaterThan(0.5f)
        assertThat(cov[y * w + w / 2 + 2]).isEqualTo(0f)
    }

    @Test
    fun `auto-mask weight is soft between its two distances`() {
        val pre = floatArrayOf(0.5f, 0f, 0f, 0f)
        fun weight(d: Float) = MaskRaster.autoWeight(pre, 0, floatArrayOf(0.5f, d, 0f), 0)
        assertThat(weight(0f)).isEqualTo(1f)
        assertThat(weight(Dabs.AUTO_NEAR)).isEqualTo(1f)
        assertThat(weight((Dabs.AUTO_NEAR + Dabs.AUTO_FAR) / 2)).isWithin(1e-4f).of(0.5f)
        assertThat(weight(Dabs.AUTO_FAR)).isEqualTo(0f)
    }

    @Test
    fun `dab colours are the pre-local pixel under each centre, clamped to the image`() {
        val pre = FloatArray(w * h * 4) { it.toFloat() }
        val stroke = BrushStroke.raw(
            listOf(StrokePoint(-0.2f, 0.5f, 1f), StrokePoint(0.5f, 0.5f, 1f)),
            size = 0.1f,
            autoMask = true,
        )
        val dabs = Dabs.of(listOf(stroke), w, h)
        val colours = MaskRaster.dabColours(dabs, pre, w, h)
        for (k in 0 until dabs.size) {
            val cx = MaskRaster.dabCentre(dabs.data[k * Dabs.DAB_FLOATS], w)
            val cy = MaskRaster.dabCentre(dabs.data[k * Dabs.DAB_FLOATS + 1], h)
            assertThat(colours[k * 3]).isEqualTo(pre[(cy * w + cx) * 4])
        }
        assertThat(MaskRaster.dabCentre(-3.5f, w)).isEqualTo(0)
        assertThat(MaskRaster.dabCentre(w + 2f, w)).isEqualTo(w - 1)
    }

    @Suppress("NestedBlockDepth")
    @Test
    fun `the coverage is zero outside the mask's bounds`() {
        val brush = MaskComponent.Brush(
            strokes = listOf(
                BrushStroke.raw(line(), size = 0.1f, feather = 50f),
                BrushStroke.raw(line(), size = 0.3f, erase = true),
            ),
        )
        val masks = listOf(
            listOf(MaskComponent.Linear(x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 0.55f)),
            listOf(MaskComponent.Linear(x0 = 0.1f, y0 = 0.9f, x1 = 0.6f, y1 = 0.3f, feather = 30f)),
            listOf(MaskComponent.Radial(cx = 0.3f, cy = 0.7f, rx = 0.2f, ry = 0.12f, rotation = -20f)),
            listOf(MaskComponent.Radial(cx = 0.9f, cy = 0.1f, rx = 0.3f, ry = 0.05f, rotation = 70f, feather = 0f)),
            listOf(brush),
            listOf(
                MaskComponent.Radial(cx = 0.2f, cy = 0.2f, rx = 0.15f),
                MaskComponent.Radial(op = MaskOp.ADD, cx = 0.8f, cy = 0.8f, rx = 0.1f),
            ),
            listOf(
                MaskComponent.Linear(x0 = 0f, y0 = 0f, x1 = 1f, y1 = 0f),
                MaskComponent.Radial(op = MaskOp.INTERSECT, cx = 0.3f, cy = 0.4f, rx = 0.1f),
            ),
            listOf(MaskComponent.Radial(cx = 0.5f, cy = 0.5f, rx = 0.1f, inside = false)),
            listOf(MaskComponent.Radial(cx = 0.5f, cy = 0.5f, rx = 0.1f, invert = true)),
        )
        for ((n, components) in masks.withIndex()) {
            val mask = Mask("m$n", adjustments = LocalAdjustments(exposure = 1f), components = components)
            val plan = MaskPlan.of(listOf(mask), w, h)
            val cov = MaskRaster.coverage(plan, whole, null).single()
            val box = MaskBounds.of(plan, 0)
            var inside = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = cov[y * w + x]
                    val within = x >= box[0] && x < box[2] && y >= box[1] && y < box[3]
                    if (!within) assertThat(c).isEqualTo(0f)
                    if (c > 0f) inside++
                }
            }
            // The boxes are not the whole image when they need not be.
            val area = (box[2] - box[0]).coerceAtLeast(0) * (box[3] - box[1]).coerceAtLeast(0)
            if (n in listOf(0, 2, 4, 6)) assertThat(area).isLessThan(w * h)
            assertThat(inside).isAtMost(area)
        }
    }
}
