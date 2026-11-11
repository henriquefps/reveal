package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PIPELINE_VERSION
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.util.zip.CRC32
import kotlin.math.abs
import org.junit.Test

class CpuDevelopTest {
    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xff

    @Test
    fun `gradient with default recipe encodes as sRGB below the knee`() {
        val input = SyntheticImages.grayRamp(256, 1, max = 0.8f)

        val out = CpuDevelop.render(input, Recipe(), OutputSettings(dither = false))

        for (x in 0 until 256) {
            val linear = 0.8f * x / 255f
            val expected = Math.round(OutputTransformOp.oetf(linear) * 255f)
            assertThat(abs(out.u(x * 4) - expected)).isAtMost(1)
            assertThat(out.u(x * 4 + 1)).isEqualTo(out.u(x * 4))
            assertThat(out.u(x * 4 + 2)).isEqualTo(out.u(x * 4))
            assertThat(out.u(x * 4 + 3)).isEqualTo(255)
        }
    }

    @Test
    fun `one stop of exposure raises mid grey by one stop before the curve`() {
        val input = SyntheticImages.grayRamp(2, 1, max = 0.18f)
        val plain = CpuDevelop.renderLinear(input, Recipe())
        val plusOne = CpuDevelop.renderLinear(input, Recipe(light = Light(1f)))

        assertThat(plain[3]).isWithin(1e-5f).of(0.18f)
        assertThat(plusOne[3]).isWithin(1e-5f).of(0.36f)
    }

    @Test
    fun `colour checker neutral patches stay neutral and match the sRGB source`() {
        val input = SyntheticImages.colorChecker(cell = 1)

        val out = CpuDevelop.render(input, Recipe(), OutputSettings(dither = false))

        // Row 3 holds the grey ramp: white 243 ... black 52 (source 8-bit values), within one level.
        val expected = listOf(243, 200, 160, 122, 85, 52)
        for (i in expected.indices) {
            val o = (18 + i) * 4
            assertThat(abs(out.u(o) - out.u(o + 1))).isAtMost(1)
            assertThat(abs(out.u(o + 2) - out.u(o + 1))).isAtMost(1)
            // Patches below the curve's knee return the source values; 243 (0.896 linear) is on the shoulder.
            if (expected[i] < 200) assertThat(abs(out.u(o) - expected[i])).isAtMost(1)
        }
        // A saturated patch (patch 15, "yellow") keeps its hue ordering R > G > B.
        val y = 15 * 4
        assertThat(out.u(y)).isGreaterThan(out.u(y + 1))
        assertThat(out.u(y + 1)).isGreaterThan(out.u(y + 2))
    }

    @Test
    fun `out of range input is handled without NaN and clips only at the output`() {
        val input = SyntheticImages.outOfRange()

        val linear = CpuDevelop.renderLinear(input, Recipe())
        val out = CpuDevelop.render(input, Recipe(), OutputSettings(dither = false))

        for (v in linear) assertThat(v.isFinite()).isTrue()
        // Display-referred input has no base curve: 16x over white stays 16 in the working space; negatives stay.
        assertThat(linear[5 * 3 + 1]).isWithin(1e-3f).of(16f)
        assertThat(linear.min()).isLessThan(0f)
        // The negative-red pixel clips to 0 at the output; white and 16x white are both 252-255.
        assertThat(out.u(0)).isEqualTo(0)
        assertThat(out.u(5 * 4)).isAtLeast(250)
        assertThat(out.u(3 * 4)).isEqualTo(0)
        assertThat(out.u(4 * 4)).isEqualTo(0)
    }

    @Test
    fun `scene-referred input gets the shoulder, display-referred does not`() {
        val raw = SyntheticImages.rawRamp(64, 1, max = 16f)
        val linear = CpuDevelop.renderLinear(raw, Recipe())

        // The shoulder keeps even 16x over-white input below 1.0 and monotonic.
        for (i in 0 until 64) {
            assertThat(linear[i * 3 + 1]).isAtMost(1f)
            if (i > 0) assertThat(linear[i * 3 + 1]).isAtLeast(linear[(i - 1) * 3 + 1])
        }
        // Standard (ADR 0008) approaches its white asymptote: 16x over white is within a few percent of it.
        assertThat(linear[63 * 3 + 1]).isGreaterThan(0.97f)
    }

    @Test
    fun `an sRGB 8-bit image round trips through the default recipe within one level, dither on`() {
        val (input, source) = SyntheticImages.srgb8Chart()

        val out = CpuDevelop.render(input, Recipe(), OutputSettings(dither = true))

        var maxDiff = 0
        for (i in source.indices) maxDiff = maxOf(maxDiff, abs(out.u(i) - source.u(i)))
        assertThat(maxDiff).isAtMost(1)
        assertThat(Recipe().isDefault()).isTrue()
    }

    @Test
    fun `display P3 content stays unchanged when output is P3`() {
        val (srgb, _) = SyntheticImages.srgb8Chart()
        val p3 = PipelineInput.linearDisplayP3(srgb.width, srgb.height, srgb.linearRgb)

        val out = CpuDevelop.render(p3, Recipe(), OutputSettings(OutputSpace.DISPLAY_P3, dither = false))

        // Same numbers in, same numbers out (as P3-encoded values): grey and colour alike.
        val direct = OutputTransformOp.apply(
            FloatArray(p3.linearRgb.size).also { System.arraycopy(p3.linearRgb, 0, it, 0, it.size) }
                .also {
                    CameraMatrixOp.apply(
                        it,
                        (ColorSpaces.REC2020.fromXyz * ColorSpaces.DISPLAY_P3.toXyz).toFloatArray(),
                    )
                },
            p3.width,
            p3.height,
            OutputSettings(OutputSpace.DISPLAY_P3, dither = false),
        )
        assertThat(out).isEqualTo(direct)
    }

    @Test
    fun `temperature changes warm and cool the render and tint moves green magenta`() {
        val input = SyntheticImages.rawNoise(1, 1, 3).let {
            // A neutral grey under the as-shot light.
            PipelineInput(
                InputKind.SCENE_REFERRED,
                1,
                1,
                SyntheticImages.TEST_AS_SHOT.map { m ->
                    0.25f / m
                }.toFloatArray(),
                it.cameraToXyz,
                it.asShotMultipliers,
            )
        }
        val asShot = CpuDevelop.renderLinear(input, Recipe())
        // Telling the model the light was 3500 K means the picture is corrected towards blue.
        val corrected = CpuDevelop.renderLinear(input, Recipe(colour = Colour(WhiteBalance(temperatureK = 3500f))))
        val magenta = CpuDevelop.renderLinear(input, Recipe(colour = Colour(WhiteBalance(tint = 50f))))

        assertThat(asShot[0]).isWithin(1e-4f).of(asShot[1])
        assertThat(asShot[2]).isWithin(1e-4f).of(asShot[1])
        assertThat(corrected[2]).isGreaterThan(corrected[0])
        // Positive tint tells the model the light was magenta, so the correction pushes the picture to green.
        assertThat(magenta[1]).isGreaterThan(magenta[0])
        assertThat(magenta[1]).isGreaterThan(magenta[2])
    }

    @Test
    fun `render is deterministic and does not modify the input`() {
        val input = SyntheticImages.scene(64, 48)
        val before = input.linearRgb.copyOf()
        val recipe = Recipe(light = Light(0.7f), colour = Colour(WhiteBalance(5200f, 8f)))

        val a = CpuDevelop.render(input, recipe)
        val b = CpuDevelop.render(input, recipe)

        assertThat(a).isEqualTo(b)
        assertThat(input.linearRgb).isEqualTo(before)
    }

    @Test
    fun `golden checksum pins the pipeline maths to PIPELINE_VERSION`() {
        val input = SyntheticImages.scene(64, 48)
        val recipe = Recipe(light = Light(0.7f), colour = Colour(WhiteBalance(5200f, 8f)))

        val crc = CRC32().apply { update(CpuDevelop.render(input, recipe)) }.value

        // Version 2 added highlight clipping for input with a known saturation, version 3 replaced it with highlight
        // reconstruction and added the Light tone operators (pinned below); version 4 replaced the M0 Standard knee
        // curve with the profiles of docs/adr/0008, which changes this scene-referred render.
        // If this fails the pipeline output changed: that is only legitimate together with a PIPELINE_VERSION bump
        // (docs/adr/0002, "PIPELINE_VERSION rules"); update both here. Version 5 added geometry (slot 13), which a
        // recipe without geometry leaves out: same CRC.
        assertThat(PIPELINE_VERSION).isEqualTo(5)
        assertThat(crc).isEqualTo(GOLDEN_CRC_V4)
    }

    @Test
    fun `golden checksum pins the Light operators to PIPELINE_VERSION`() {
        val clipped = SyntheticImages.clippedHighlights(64, 48)
        val scene = SyntheticImages.scene(64, 48)
        val recipe = Recipe(
            light = Light(
                exposureEv = -0.5f,
                contrast = 30f,
                highlights = -70f,
                shadows = 60f,
                whites = 20f,
                blacks = -15f,
            ),
        )

        val crc = CRC32().apply {
            update(CpuDevelop.render(clipped, recipe))
            update(CpuDevelop.render(scene, recipe))
        }.value

        // Version 3: highlight reconstruction, highlights/shadows, contrast/whites/blacks (docs/adr/0007); version 4
        // the new Standard under them (docs/adr/0008).
        assertThat(PIPELINE_VERSION).isEqualTo(5)
        assertThat(crc).isEqualTo(GOLDEN_CRC_LIGHT_V4)
    }

    @Test
    fun `golden checksum pins the colour operators to PIPELINE_VERSION`() {
        val scene = SyntheticImages.scene(64, 48)
        val (chart, _) = SyntheticImages.srgb8Chart()
        val crc = CRC32().apply {
            for (look in BaseLook.entries) update(CpuDevelop.render(scene, Recipe(profile = Profile(look, 140f))))
            update(CpuDevelop.render(scene, Recipe(colour = Colour(vibrance = 70f, saturation = -20f))))
            update(
                CpuDevelop.render(
                    chart,
                    Recipe(colour = Colour(WhiteBalance(relTemperature = 30f, relTint = -20f), saturation = 40f)),
                ),
            )
        }.value

        // Version 4: profiles, vibrance/saturation, relative white balance (docs/adr/0008).
        assertThat(PIPELINE_VERSION).isEqualTo(5)
        assertThat(crc).isEqualTo(GOLDEN_CRC_COLOUR_V4)
    }

    @Test
    fun `golden checksum pins the geometry operator to PIPELINE_VERSION`() {
        val scene = SyntheticImages.scene(64, 48)
        val straightened = Recipe(
            light = Light(0.3f),
            geometry = Geometry(crop = CropRect(0.1f, 0.15f, 0.85f, 0.9f), angle = 7.3f, rotate = 1, flipH = true),
        )
        val turned = Recipe(geometry = Geometry(crop = CropRect(0.2f, 0f, 0.7f, 0.6f), rotate = 3, flipV = true))

        val crc = CRC32().apply {
            update(CpuDevelop.render(scene, straightened))
            update(CpuDevelop.render(scene, turned))
        }.value

        // Version 5: crop, straighten (Catmull-Rom), quarter turns and flips as one resampling (docs/adr/0002).
        assertThat(PIPELINE_VERSION).isEqualTo(5)
        assertThat(crc).isEqualTo(GOLDEN_CRC_GEOMETRY_V5)
    }

    private companion object {
        const val GOLDEN_CRC_V4: Long = 3273411752L
        const val GOLDEN_CRC_LIGHT_V4: Long = 2469709740L
        const val GOLDEN_CRC_COLOUR_V4: Long = 3805206770L
        const val GOLDEN_CRC_GEOMETRY_V5: Long = 0L
    }
}
