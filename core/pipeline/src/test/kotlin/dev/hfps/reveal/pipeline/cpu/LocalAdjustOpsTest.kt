@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.mask.AiMask
import dev.hfps.reveal.pipeline.mask.AiMaskSource
import dev.hfps.reveal.pipeline.mask.MaskContext
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.LabColour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Test

/**
 * Local adjustments (docs/adr/0022): each is the global operator at a local amount, so a mask that covers everything
 * renders like the global slider; pixels no mask covers are bit-identical to the render without masks; amounts and
 * overlapping masks add; a golden checksum pins a five-mask render.
 */
class LocalAdjustOpsTest {
    private val scene = SyntheticImages.scene(64, 48)
    private val noisy = SyntheticImages.noisyCard(48, 40, seed = 3)
    private val chart = SyntheticImages.colorChecker(8)

    /** A component that covers the whole image (coverage 1 everywhere). */
    private val everywhere = MaskComponent.Radial(rx = 5f, ry = 5f, feather = 0f)

    /** The left half, hard edge. */
    private val leftHalf = MaskComponent.Linear(x0 = 0f, y0 = 0.5f, x1 = 1f, y1 = 0.5f, feather = 0f)

    private fun masked(adjustments: LocalAdjustments, vararg components: MaskComponent = arrayOf(everywhere)) =
        Mask("m", adjustments = adjustments, components = components.toList())

    private fun render(input: PipelineInput, recipe: Recipe, context: MaskContext = MaskContext.NONE) =
        CpuDevelop.renderLinear(input, recipe, masks = context)

    private fun assertClose(expected: FloatArray, actual: FloatArray, tolerance: Float, what: String) {
        var worst = 0f
        for (i in expected.indices step 3) {
            val scale = max(1f, max(abs(expected[i]), max(abs(expected[i + 1]), abs(expected[i + 2]))))
            for (c in 0 until 3) worst = max(worst, abs(expected[i + c] - actual[i + c]) / scale)
        }
        println("LOCAL $what: worst ${"%.2e".format(worst)}")
        assertThat(worst).isAtMost(tolerance)
    }

    @Test
    fun `a full mask renders like the global slider`() {
        data class Case(val input: PipelineInput, val adjustment: LocalAdjustment, val value: Float, val global: Recipe)
        val cases = listOf(
            Case(scene, LocalAdjustment.EXPOSURE, 0.8f, Recipe(light = Light(exposureEv = 0.8f))),
            Case(scene, LocalAdjustment.EXPOSURE, -1.3f, Recipe(light = Light(exposureEv = -1.3f))),
            Case(scene, LocalAdjustment.CONTRAST, 45f, Recipe(light = Light(contrast = 45f))),
            Case(scene, LocalAdjustment.HIGHLIGHTS, -70f, Recipe(light = Light(highlights = -70f))),
            Case(scene, LocalAdjustment.SHADOWS, 60f, Recipe(light = Light(shadows = 60f))),
            Case(scene, LocalAdjustment.WHITES, 35f, Recipe(light = Light(whites = 35f))),
            Case(scene, LocalAdjustment.BLACKS, -40f, Recipe(light = Light(blacks = -40f))),
            Case(scene, LocalAdjustment.SATURATION, 40f, Recipe(colour = Colour(saturation = 40f))),
            Case(scene, LocalAdjustment.SATURATION, -60f, Recipe(colour = Colour(saturation = -60f))),
            Case(scene, LocalAdjustment.TEXTURE, 70f, Recipe(effects = Effects(texture = 70f))),
            Case(scene, LocalAdjustment.CLARITY, -50f, Recipe(effects = Effects(clarity = -50f))),
            Case(scene, LocalAdjustment.DEHAZE, 60f, Recipe(effects = Effects(dehaze = 60f))),
            Case(scene, LocalAdjustment.DEHAZE, -40f, Recipe(effects = Effects(dehaze = -40f))),
            Case(noisy, LocalAdjustment.SHARPNESS, 80f, Recipe(detail = Detail(sharpenAmount = 80f))),
            Case(noisy, LocalAdjustment.NOISE, 60f, Recipe(detail = Detail(nrLuminance = 60f))),
            // Display-referred input: the relative white balance (on a node of the gain table).
            Case(chart, LocalAdjustment.TEMPERATURE, 30f, Recipe(colour = Colour(WhiteBalance(relTemperature = 30f)))),
            Case(chart, LocalAdjustment.TINT, -20f, Recipe(colour = Colour(WhiteBalance(relTint = -20f)))),
        )
        for (c in cases) {
            val expected = render(c.input, c.global)
            val local = render(c.input, Recipe(masks = listOf(masked(LocalAdjustments().with(c.adjustment, c.value)))))
            assertClose(expected, local, 2e-4f, "${c.adjustment} ${c.value}")
        }
        // The colour tint is colour grading's global wheel.
        val tint = render(scene, Recipe(colour = Colour(grading = ColourGrading(global = GradeWheel(210f, 40f)))))
        val local = render(
            scene,
            Recipe(masks = listOf(masked(LocalAdjustments(colourHue = 210f, colourSaturation = 40f)))),
        )
        assertClose(tint, local, 2e-4f, "colour tint")
    }

    @Test
    fun `a local hue turns every hue by its angle and keeps lightness and chroma`() {
        val base = render(chart, Recipe())
        val turned = render(chart, Recipe(masks = listOf(masked(LocalAdjustments(hue = 30f)))))
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (i in base.indices step 3) {
            OkLab.fromRgb(base[i], base[i + 1], base[i + 2], a)
            OkLab.fromRgb(turned[i], turned[i + 1], turned[i + 2], b)
            assertThat(b[0].toDouble()).isWithin(2e-4).of(a[0].toDouble())
            val ca = sqrt(a[1] * a[1] + a[2] * a[2])
            assertThat(sqrt(b[1] * b[1] + b[2] * b[2]).toDouble()).isWithin(2e-4).of(ca.toDouble())
            if (ca > 0.02f) {
                var d = Math.toDegrees((atan2(b[2], b[1]) - atan2(a[2], a[1])).toDouble())
                if (d < -180) d += 360
                if (d > 180) d -= 360
                assertThat(d).isWithin(0.2).of(30.0)
            }
        }
    }

    @Suppress("NestedBlockDepth")
    @Test
    fun `pixels no mask covers are unchanged`() {
        val everything = LocalAdjustments(
            temperature = 30f, tint = -10f, exposure = 1f, contrast = 40f, highlights = -50f, shadows = 50f,
            whites = 20f, blacks = -20f, saturation = 30f, hue = 20f, colourHue = 40f, colourSaturation = 30f,
        )
        val globals = listOf(
            Recipe(),
            Recipe(light = Light(exposureEv = 0.3f, contrast = 20f, highlights = -30f, shadows = 20f, blacks = 10f)),
        )
        for (global in globals) {
            val plain = render(scene, global)
            // Covers nothing: bit-identical everywhere.
            val nowhere = MaskComponent.Radial(cx = 5f, cy = 5f, rx = 0.01f, ry = 0.01f, feather = 0f)
            val none = render(scene, global.copy(masks = listOf(masked(everything, nowhere))))
            assertThat(none.toList()).isEqualTo(plain.toList())
            // The left half: the right half is bit-identical (per-pixel adjustments read nothing around a pixel).
            val half = render(scene, global.copy(masks = listOf(masked(everything, leftHalf))))
            for (y in 0 until scene.height) {
                for (x in scene.width / 2 until scene.width) {
                    for (c in 0 until 3) {
                        val i = (y * scene.width + x) * 3 + c
                        assertThat(half[i]).isEqualTo(plain[i])
                    }
                }
            }
            assertThat(half.toList()).isNotEqualTo(plain.toList())
        }
        // Neighbourhood adjustments (sharpness, noise, texture) with the global ones off: pixels farther than their
        // reach from the mask are bit-identical too (their total is 0: the levels keep the input lightness exactly).
        val detail = LocalAdjustments(sharpness = 60f, noise = 50f, texture = 40f)
        val plain = render(noisy, Recipe(detail = Detail(nrColour = 30f)))
        val half = render(noisy, Recipe(detail = Detail(nrColour = 30f), masks = listOf(masked(detail, leftHalf))))
        val reach = DevelopParams.reach(Recipe(masks = listOf(masked(detail, leftHalf))))
        for (y in 0 until noisy.height) {
            for (x in noisy.width / 2 + reach until noisy.width) {
                for (c in 0 until 3) {
                    val i = (y * noisy.width + x) * 3 + c
                    assertThat(half[i]).isEqualTo(plain[i])
                }
            }
        }
    }

    @Test
    fun `amounts scale and overlapping masks add`() {
        val one = render(scene, Recipe(masks = listOf(masked(LocalAdjustments(exposure = 1f)))))
        val halfAmount = render(
            scene,
            Recipe(masks = listOf(masked(LocalAdjustments(exposure = 2f)).copy(amount = 50f))),
        )
        assertThat(halfAmount.toList()).isEqualTo(one.toList())
        val twoMasks = render(
            scene,
            Recipe(
                masks = listOf(masked(LocalAdjustments(exposure = 0.5f)), masked(LocalAdjustments(exposure = 0.5f))),
            ),
        )
        assertThat(twoMasks.toList()).isEqualTo(one.toList())
        // A disabled mask or amount 0 renders nothing.
        val plain = render(scene, Recipe())
        val off = render(scene, Recipe(masks = listOf(masked(LocalAdjustments(exposure = 1f)).copy(enabled = false))))
        assertThat(off.toList()).isEqualTo(plain.toList())
        val zero = render(scene, Recipe(masks = listOf(masked(LocalAdjustments(exposure = 1f)).copy(amount = 0f))))
        assertThat(zero.toList()).isEqualTo(plain.toList())
    }

    @Test
    fun `a gradient blends the adjustment across its band`() {
        val gradient = MaskComponent.Linear(x0 = 0f, y0 = 0.5f, x1 = 1f, y1 = 0.5f, feather = 100f)
        val plain = render(SyntheticImages.rawRamp(64, 8, 0.6f), Recipe())
        val local = render(
            SyntheticImages.rawRamp(64, 8, 0.6f),
            Recipe(masks = listOf(masked(LocalAdjustments(exposure = -1f), gradient))),
        )
        // Luminance ratio: about 2^-1 at the left edge, 1 at the right, monotonic in between (before the profile's
        // shoulder the gain shows as is).
        var last = 0.0
        for (x in 4 until 60) {
            val i = (4 * 64 + x) * 3
            val ratio = (local[i + 1] / plain[i + 1]).toDouble()
            assertThat(ratio).isAtLeast(last - 1e-6)
            last = ratio
        }
        val left = local[(4 * 64 + 2) * 3 + 1] / plain[(4 * 64 + 2) * 3 + 1]
        val right = local[(4 * 64 + 63) * 3 + 1] / plain[(4 * 64 + 63) * 3 + 1]
        assertThat(left.toDouble()).isWithin(0.15).of(0.5)
        assertThat(right.toDouble()).isWithin(1e-3).of(1.0)
    }

    @Test
    fun `negative sharpness softens`() {
        val plain = render(noisy, Recipe())
        val soft = render(noisy, Recipe(masks = listOf(masked(LocalAdjustments(sharpness = -100f)))))
        assertThat(
            highFrequency(soft, noisy.width, noisy.height),
        ).isLessThan(highFrequency(plain, noisy.width, noisy.height) * 0.8)
        // -100 local on +60 global: still softer than the original (the total is -40).
        val mixed = render(
            noisy,
            Recipe(detail = Detail(sharpenAmount = 60f), masks = listOf(masked(LocalAdjustments(sharpness = -100f)))),
        )
        assertThat(
            highFrequency(mixed, noisy.width, noisy.height),
        ).isLessThan(highFrequency(plain, noisy.width, noisy.height))
    }

    private fun highFrequency(rgb: FloatArray, w: Int, h: Int): Double {
        var s = 0.0
        for (y in 0 until h) for (x in 1 until w) s += abs(rgb[(y * w + x) * 3 + 1] - rgb[(y * w + x - 1) * 3 + 1])
        return s
    }

    @Test
    fun `a colour range selects on the image before local adjustments`() {
        // The chart's blue patch (row 2, column 0 of ColorChecker Classic: "blue", sRGB 56 61 150).
        val plain = render(chart, Recipe())
        val lab = DetailOp.toLab(plain, chart.width * chart.height)
        val blue = (2 * 8 + 4) * chart.width + 4
        val sample = LabColour(lab[blue * 4], lab[blue * 4 + 1], lab[blue * 4 + 2])
        val range = MaskComponent.ColourRange(samples = listOf(sample), range = 15f, smoothness = 0f)
        // A second mask's exposure must not change what the range selects.
        val other = masked(LocalAdjustments(exposure = 2f), MaskComponent.Linear(x0 = 0f, y0 = 0f, x1 = 0f, y1 = 1f))
        val local = render(chart, Recipe(masks = listOf(masked(LocalAdjustments(saturation = -100f), range), other)))
        val alone = render(chart, Recipe(masks = listOf(other)))
        var changed = 0
        for (p in 0 until chart.width * chart.height) {
            val i = p * 3
            val same = local[i] == alone[i] && local[i + 1] == alone[i + 1] && local[i + 2] == alone[i + 2]
            val inPatch = (p / chart.width) / 8 == 2 && (p % chart.width) / 8 == 0
            if (!same) {
                changed++
                assertThat(inPatch).isTrue()
            }
        }
        assertThat(changed).isEqualTo(64)
    }

    @Test
    fun `ai components come from the source and are skipped until ready`() {
        val ai = MaskComponent.Ai(model = "fake@1", kind = "SKY")
        val mask = masked(LocalAdjustments(exposure = 1f), ai)
        val plain = render(scene, Recipe())
        assertThat(render(scene, Recipe(masks = listOf(mask))).toList()).isEqualTo(plain.toList())
        var asked: String? = null
        val top = AiMaskSource { request, _ ->
            asked = "${request.photoKey}/${request.component.kind}/${request.imageWidth}x${request.imageHeight}"
            AiMask(1, 2, floatArrayOf(1f, 0f))
        }
        val local = render(scene, Recipe(masks = listOf(mask)), MaskContext(top, "photo-1"))
        assertThat(asked).isEqualTo("photo-1/SKY/64x48")
        val top0 = 2 * 64 * 3 + 30 * 3 + 1
        val bottom = 46 * 64 * 3 + 30 * 3 + 1
        // +1 EV through the profile's shoulder: brighter, compressed.
        assertThat(local[top0]).isGreaterThan(plain[top0] * 1.2f)
        assertThat(local[bottom]).isEqualTo(plain[bottom])
    }

    @Test
    fun `reach covers the neighbourhoods masks switch on`() {
        val base = DevelopParams.reach(Recipe())
        assertThat(base).isEqualTo(0)
        for (a in listOf(
            LocalAdjustments(sharpness = 20f),
            LocalAdjustments(noise = 20f),
            LocalAdjustments(texture = 20f),
        )) {
            assertThat(DevelopParams.reach(Recipe(masks = listOf(masked(a))))).isGreaterThan(0)
        }
        // Per-pixel adjustments need no apron.
        assertThat(DevelopParams.reach(Recipe(masks = listOf(masked(LocalAdjustments(exposure = 1f, clarity = 50f))))))
            .isEqualTo(0)
    }

    @Test
    fun `five masks of every kind render to a golden checksum`() {
        val stroke = BrushStroke.raw(
            List(60) { StrokePoint(0.2f + it * 0.01f, 0.6f + 0.1f * kotlin.math.sin(it * 0.2f), 0.5f + it / 120f) },
            size = 0.08f,
        )
        val recipe = Recipe(
            light = Light(contrast = 15f),
            detail = Detail(sharpenAmount = 30f),
            masks = listOf(
                masked(
                    LocalAdjustments(exposure = -0.6f, dehaze = 30f, temperature = -15f),
                    MaskComponent.Linear(y1 = 0.6f),
                ),
                masked(
                    LocalAdjustments(shadows = 40f, clarity = 30f),
                    MaskComponent.Radial(cx = 0.3f, cy = 0.7f, rx = 0.2f),
                ),
                masked(
                    LocalAdjustments(saturation = 40f, sharpness = -50f),
                    MaskComponent.Brush(strokes = listOf(stroke)),
                ),
                masked(
                    LocalAdjustments(hue = 25f, noise = 40f),
                    MaskComponent.LuminanceRange(low = 60f, high = 100f, falloff = 10f),
                    MaskComponent.Radial(op = MaskOp.SUBTRACT, cx = 0.8f, cy = 0.2f, rx = 0.1f),
                ),
                masked(
                    LocalAdjustments(colourHue = 30f, colourSaturation = 50f, texture = 40f, whites = 20f),
                    MaskComponent.ColourRange(samples = listOf(LabColour(0.6f, 0.05f, 0.1f)), range = 60f),
                ),
            ),
        )
        val input = SyntheticImages.scene(96, 64)
        val crc = CRC32().apply { update(CpuDevelop.render(input, recipe)) }.value
        println("LOCAL golden CRC: 0x${crc.toString(16)}")
        assertThat(crc).isEqualTo(GOLDEN_CRC_MASKS)
    }

    private companion object {
        const val GOLDEN_CRC_MASKS = 0xc4c51f20L
    }
}
