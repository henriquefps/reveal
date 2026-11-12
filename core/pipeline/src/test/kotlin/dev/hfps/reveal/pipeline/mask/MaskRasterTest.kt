@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.LabColour
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Test

/** The masks' coverage on the CPU (docs/adr/0022, "Coverage"): each component's values and the combinations. */
class MaskRasterTest {
    private val w = 40
    private val h = 30
    private val whole = MaskRaster.Region(0, 0, w, h)

    private fun mask(vararg components: MaskComponent) =
        Mask("m", adjustments = LocalAdjustments(exposure = 1f), components = components.toList())

    private fun coverage(mask: Mask, pre: FloatArray? = null, ai: List<AiMask?>? = null): FloatArray {
        val plan = MaskPlan.of(listOf(mask), w, h)
        return MaskRaster.coverage(plan, whole, pre, ai ?: plan.ai.map { null }).single()
    }

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun at(c: FloatArray, x: Int, y: Int) = c[y * w + x]

    @Test
    fun `linear gradient follows its band and feather`() {
        val soft = coverage(mask(MaskComponent.Linear(x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 1f, feather = 100f)))
        for (y in 0 until h) {
            val expected = 1 - smoothstep(0.0, 1.0, (y + 0.5) / h)
            assertThat(at(soft, 7, y).toDouble()).isWithin(1e-3).of(expected)
            assertThat(at(soft, 31, y)).isEqualTo(at(soft, 7, y))
        }
        val hard = coverage(mask(MaskComponent.Linear(x0 = 0f, y0 = 0.5f, x1 = 1f, y1 = 0.5f, feather = 0f)))
        for (x in 0 until w) assertThat(at(hard, x, 10)).isEqualTo(if (x < w / 2) 1f else 0f)
        // Diagonal: perpendicular in pixels, so equal along the band's lines.
        val diagonal = coverage(mask(MaskComponent.Linear(x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f)))
        assertThat(at(diagonal, 0, 0)).isGreaterThan(at(diagonal, 20, 15))
        // A zero-length gradient is skipped.
        assertThat(coverage(mask(MaskComponent.Linear(x0 = 0.3f, y0 = 0.3f, x1 = 0.3f, y1 = 0.3f))).max()).isEqualTo(0f)
    }

    @Test
    fun `radial is an ellipse in pixels, turned and inverted`() {
        // Radii are long-edge fractions: rx 0.25 of 40 = 10 px, ry 0.125 = 5 px.
        val r = MaskComponent.Radial(cx = 0.5f, cy = 0.5f, rx = 0.25f, ry = 0.125f, feather = 0f)
        val c = coverage(mask(r))
        assertThat(at(c, 20, 15)).isEqualTo(1f)
        assertThat(at(c, 28, 15)).isEqualTo(1f)
        assertThat(at(c, 31, 15)).isEqualTo(0f)
        assertThat(at(c, 20, 18)).isEqualTo(1f)
        assertThat(at(c, 20, 21)).isEqualTo(0f)
        // Turned 90 degrees clockwise: the long axis is vertical.
        val turned = coverage(mask(r.copy(rotation = 90f)))
        assertThat(at(turned, 28, 15)).isEqualTo(0f)
        assertThat(at(turned, 20, 23)).isEqualTo(1f)
        // Outside and the generic invert agree.
        val outside = coverage(mask(r.copy(inside = false)))
        val inverted = coverage(mask(r.copy(invert = true)))
        assertThat(outside.toList()).isEqualTo(inverted.toList())
        assertThat(at(outside, 0, 0)).isEqualTo(1f)
        // Feathered: 1 inside the inner ellipse, falling to 0 at the edge.
        val soft = coverage(mask(r.copy(feather = 50f)))
        val d = sqrt(((24.5 - 20.0) / 10).let { it * it })
        assertThat(at(soft, 24, 15).toDouble()).isWithin(1e-3).of(1 - smoothstep(0.5, 1.0, d))
    }

    @Test
    fun `brush dabs paint, build up with flow and erase in order`() {
        // One dab of radius 5 px (size 0.25 of the 40 px long edge) at the centre, hard edge.
        val dot = BrushStroke.raw(listOf(StrokePoint(0.5f, 0.5f)), size = 0.25f, feather = 0f, flow = 100f)
        val one = coverage(mask(MaskComponent.Brush(strokes = listOf(dot))))
        assertThat(at(one, 20, 15)).isEqualTo(1f)
        assertThat(at(one, 24, 15)).isEqualTo(1f)
        assertThat(at(one, 26, 15)).isEqualTo(0f)
        // Two dabs at flow 50 on the same spot: 1 - (1 - 0.5)^2.
        val half = dot.copy(flow = 50f)
        val twice = coverage(mask(MaskComponent.Brush(strokes = listOf(half, half))))
        assertThat(at(twice, 20, 15)).isEqualTo(0.75f)
        // Then an erasing dab at flow 50 halves it.
        val erased = coverage(mask(MaskComponent.Brush(strokes = listOf(half, half, half.copy(erase = true)))))
        assertThat(at(erased, 20, 15)).isEqualTo(0.375f)
        // Pressure scales the radius: k(0) = 0.25.
        val light = BrushStroke.raw(listOf(StrokePoint(0.5f, 0.5f, 0f)), size = 0.25f, feather = 0f)
        val dabs = Dabs.of(listOf(light), w, h)
        assertThat(dabs.data[2]).isEqualTo(5f * Dabs.PRESSURE_FLOOR)
        assertThat(dabs.data[4]).isEqualTo(Dabs.PRESSURE_FLOOR)
    }

    @Test
    fun `dabs are spaced along the stroke and appending points only appends dabs`() {
        val points = List(40) { StrokePoint(0.1f + it * 0.02f, 0.5f + (it % 3) * 0.01f, 0.5f + it / 80f) }
        val all = Dabs.of(listOf(BrushStroke.raw(points, size = 0.3f)), w, h)
        for (n in listOf(1, 2, 10, 39)) {
            val prefix = Dabs.of(listOf(BrushStroke.raw(points.take(n), size = 0.3f)), w, h)
            assertThat(all.commonPrefix(prefix)).isEqualTo(prefix.size)
        }
        // Spacing: 0.15 of the diameter at the dab's pressure.
        val d = all.data
        for (k in 1 until all.size) {
            val dx = d[k * 6] - d[(k - 1) * 6]
            val dy = d[k * 6 + 1] - d[(k - 1) * 6 + 1]
            val spacing = maxOf(Dabs.SPACING * 2 * d[(k - 1) * 6 + 2], Dabs.MIN_SPACING_PX)
            assertThat(sqrt(dx * dx + dy * dy)).isAtMost(spacing * 1.001f + 1e-4f)
        }
    }

    @Test
    fun `binned evaluation (the GPU's) equals the dab-by-dab raster`() {
        val random = kotlin.random.Random(3)
        val strokes = List(5) { s ->
            BrushStroke.raw(
                List(30) { StrokePoint(random.nextFloat(), random.nextFloat(), random.nextFloat()) },
                size = 0.02f + 0.05f * s,
                feather = 20f * s,
                flow = 30f + 15f * s,
                erase = s == 3,
            )
        }
        val big = 200
        val dabs = Dabs.of(strokes, big, big * 3 / 4)
        val region = MaskRaster.Region(13, 7, 150, 120)
        val direct = MaskRaster.brush(dabs, region)
        val bins = DabBins.of(dabs, region.x, region.y, region.width, region.height)
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                val b = (y / DabBins.BIN) * bins.binsX + x / DabBins.BIN
                var cov = 0f
                for (j in 0 until bins.counts[b]) {
                    val k = bins.indices[bins.offsets[b] + j]
                    val a = MaskRaster.dabAlpha(dabs.data, k * Dabs.DAB_FLOATS, x + region.x, y + region.y)
                    if (a <= 0f) continue
                    cov = if (dabs.data[k * Dabs.DAB_FLOATS + 5] != 0f) cov * (1f - a) else cov + (1f - cov) * a
                }
                assertThat(MaskMath.store(cov)).isEqualTo(direct[y * region.width + x])
            }
        }
        assertThat(direct.count { it > 0f }).isGreaterThan(1000)
    }

    @Test
    fun `colour and luminance ranges read the pre-local image`() {
        // Pre-local OkLab: L ramps left to right, a/b fixed except one blue square.
        val pre = FloatArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                pre[i] = x / (w - 1f)
                val blue = x in 5..9 && y in 5..9
                pre[i + 1] = if (blue) -0.03f else 0.05f
                pre[i + 2] = if (blue) -0.15f else 0.04f
            }
        }
        val colour = coverage(
            mask(MaskComponent.ColourRange(samples = listOf(LabColour(0.2f, -0.03f, -0.15f)), range = 50f, smoothness = 0f)),
            pre,
        )
        assertThat(at(colour, 7, 7)).isEqualTo(1f)
        assertThat(at(colour, 20, 20)).isEqualTo(0f)
        val luma = coverage(mask(MaskComponent.LuminanceRange(low = 40f, high = 60f, falloff = 10f)), pre)
        for (x in 0 until w) {
            val l = x / (w - 1.0)
            val expected = smoothstep(0.3, 0.4, l) * (1 - smoothstep(0.6, 0.7, l))
            assertThat(at(luma, x, 20).toDouble()).isWithin(1e-3).of(expected)
        }
        // The top of the range includes white.
        val top = coverage(mask(MaskComponent.LuminanceRange(low = 90f, high = 100f, falloff = 0f)), pre)
        assertThat(at(top, w - 1, 20)).isEqualTo(1f)
    }

    @Test
    fun `ai masks are bilinear with explicit weights, skipped while not ready`() {
        val ai = MaskComponent.Ai(model = "m@1", kind = "SKY")
        val m = AiMask(2, 2, floatArrayOf(0f, 1f, 1f, 1f))
        val c = coverage(mask(ai), ai = listOf(m))
        // Pixel (9, 7) sits at u = (9.5) * 2 / 40 - 0.5 = -0.025 -> clamped tap: 0.
        assertThat(at(c, 0, 0)).isEqualTo(0f)
        assertThat(at(c, w - 1, h - 1)).isEqualTo(1f)
        val u = (20.5f * 2f / w - 0.5f)
        val v = (15.5f * 2f / h - 0.5f)
        val top = u
        val bottom = 1f
        assertThat(at(c, 20, 15).toDouble()).isWithin(1e-3).of((top + (bottom - top) * v).toDouble())
        // Not ready: the component is skipped (an inverted one too: never a full mask while loading).
        assertThat(coverage(mask(ai.copy(invert = true)), ai = listOf(null)).max()).isEqualTo(0f)
        val withLinear = mask(MaskComponent.Linear(x0 = 0f, y0 = 0.5f, x1 = 1f, y1 = 0.5f, feather = 0f), ai.copy(op = MaskOp.INTERSECT))
        assertThat(at(coverage(withLinear, ai = listOf(null)), 3, 3)).isEqualTo(1f)
    }

    @Test
    fun `combinations follow the truth tables`() {
        // A = left half, B = top half (hard edges): four quadrants with known booleans.
        val a = MaskComponent.Linear(x0 = 0f, y0 = 0.5f, x1 = 1f, y1 = 0.5f, feather = 0f)
        val b = MaskComponent.Linear(x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 1f, feather = 0f)
        val quadrants = listOf(5 to 5, 30 to 5, 5 to 25, 30 to 25) // (A, B): (1,1) (0,1) (1,0) (0,0)
        val bits = listOf(true to true, false to true, true to false, false to false)
        fun table(c: FloatArray) = quadrants.map { (x, y) -> at(c, x, y) == 1f }
        for ((op, rule) in listOf<Pair<MaskOp, (Boolean, Boolean) -> Boolean>>(
            MaskOp.ADD to { x, y -> x || y },
            MaskOp.SUBTRACT to { x, y -> x && !y },
            MaskOp.INTERSECT to { x, y -> x && y },
        )) {
            for (invertB in listOf(false, true)) {
                val c = coverage(mask(a, b.copy(op = op, invert = invertB)))
                assertThat(table(c)).isEqualTo(bits.map { (x, y) -> rule(x, if (invertB) !y else y) })
                for (v in c) assertThat(v == 0f || v == 1f).isTrue()
            }
        }
        // A first component that subtracts or intersects gives nothing.
        assertThat(coverage(mask(a.copy(op = MaskOp.SUBTRACT))).max()).isEqualTo(0f)
        // Soft values: add is the max, subtract and intersect multiply.
        val half = MaskComponent.Radial(rx = 5f, ry = 5f, feather = 0f) // covers everything: 1
        val soft = MaskComponent.Linear(x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 1f, feather = 100f)
        val sAdd = coverage(mask(soft, soft))
        val sSub = coverage(mask(half, soft.copy(op = MaskOp.SUBTRACT)))
        val sMul = coverage(mask(soft, soft.copy(op = MaskOp.INTERSECT)))
        val single = coverage(mask(soft))
        for (i in single.indices) {
            assertThat(sAdd[i]).isEqualTo(single[i])
            assertThat(abs(sSub[i] - (1f - single[i]))).isAtMost(1e-3f)
            assertThat(abs(sMul[i] - single[i] * single[i])).isAtMost(1e-3f)
        }
    }

    @Test
    fun `coverage is stored as float16 and tiny values are flushed`() {
        assertThat(MaskMath.store(1e-6f)).isEqualTo(0f)
        assertThat(MaskMath.store(1.5f)).isEqualTo(1f)
        assertThat(MaskMath.store(0.3f)).isEqualTo(dev.hfps.reveal.pipeline.cpu.Half.round(0.3f))
        assertThat(MaskMath.sstep(0.5f, 0.5f, 0.49f)).isEqualTo(0f)
        assertThat(MaskMath.sstep(0.5f, 0.5f, 0.5f)).isEqualTo(1f)
    }
}
