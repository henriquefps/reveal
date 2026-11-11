package dev.hfps.reveal.pipeline.auto

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.BlackWhiteOp
import dev.hfps.reveal.pipeline.cpu.GreyParams
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Test

/** Targeted adjustment and black & white Auto mix (docs/adr/0013). */
class ColourSamplingTest {
    private fun okColour(l: Float, c: Float, hDeg: Double): FloatArray {
        val h = Math.toRadians(hDeg)
        return FloatArray(3).also { OkLab.toRgb(l, (c * cos(h)).toFloat(), (c * sin(h)).toFloat(), it) }
    }

    /** Two halves of equal OkLab lightness: a red and a green (the classic B&W merge), as linear Rec.2020. */
    private fun redGreen(width: Int = 40, height: Int = 20): SourceColours {
        val red = okColour(0.6f, 0.15f, 28.0)
        val green = okColour(0.6f, 0.13f, 140.0)
        val rgb = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                (
                    if (x <
                        width / 2
                    ) {
                        red
                    } else {
                        green
                    }
                    ).copyInto(rgb, (y * width + x) * 3)
            }
        }
        return SourceColours(width, height, rgb)
    }

    @Test
    fun `targeted weights pick the bands under the colour and ignore greys`() {
        val orange = TargetedAdjust.weights(okColour(0.7f, 0.12f, 55.0))!!
        assertThat(orange[ColourBand.ORANGE.ordinal]).isWithin(1e-3f).of(1f)
        val between = TargetedAdjust.weights(okColour(0.7f, 0.12f, 40.0))!!
        assertThat(between[ColourBand.RED.ordinal]).isWithin(1e-3f).of(0.5f)
        assertThat(between[ColourBand.ORANGE.ordinal]).isWithin(1e-3f).of(0.5f)
        assertThat(TargetedAdjust.weights(floatArrayOf(0.3f, 0.3f, 0.3f))).isNull()
        assertThat(
            TargetedAdjust.lead(TargetedAdjust.weights(okColour(0.5f, 0.12f, 250.0))!!),
        ).isEqualTo(ColourBand.BLUE)
        val d = TargetedAdjust.deltas(between, 40f)
        assertThat(d[ColourBand.RED.ordinal]).isEqualTo(10f)
        assertThat(d[ColourBand.ORANGE.ordinal]).isEqualTo(10f)
        assertThat(d.sum()).isEqualTo(20f)
    }

    @Test
    fun `source colours ignore the mixer, grading and black and white, so a B&W photo still has hues`() {
        val sample = PipelineInput.linearSrgb(2, 1, floatArrayOf(0.6f, 0.1f, 0.05f, 0.1f, 0.4f, 0.1f))
        val recipe = Recipe(
            profile = Profile(treatment = Treatment.BLACK_WHITE),
            colour = Colour(mixer = ColourMixer(saturation = BandValues(red = -100f))),
        )
        val plain = SourceColours.render(sample, Recipe())
        val seen = SourceColours.render(sample, recipe)
        assertThat(seen.rgb).isEqualTo(plain.rgb)
        assertThat(TargetedAdjust.weights(seen.mean(0, 0, 0))!![ColourBand.RED.ordinal]).isGreaterThan(0.5f)
        // The mean is over the clamped square.
        val mean = plain.mean(0, 0, 4)
        for (c in 0..2) assertThat(mean[c]).isWithin(1e-6f).of((plain.rgb[c] + plain.rgb[3 + c]) / 2)
    }

    @Test
    fun `auto mix separates two colours of equal lightness and is deterministic`() {
        val colours = redGreen()
        val mix = AutoBlackWhite.mix(colours)
        assertThat(AutoBlackWhite.mix(colours)).isEqualTo(mix)
        val rgb = colours.rgb.copyOf()
        BlackWhiteOp.apply(rgb, GreyParams.from(mix).values)
        val left = rgb[0]
        val right = rgb[(colours.width - 1) * 3]
        // Equal lightness before; at least half a stop apart after, the warm red lighter.
        assertThat(abs(ln(left / right) / ln(2.0f))).isAtLeast(0.5f)
        assertThat(left).isGreaterThan(right)
        mix.toList().forEach { assertThat(abs(it)).isAtMost(AutoBlackWhite.MAX_VALUE) }
    }

    @Test
    fun `auto mix leaves an already well separated photo with the bias only`() {
        val red = okColour(0.85f, 0.15f, 28.0)
        val blue = okColour(0.3f, 0.15f, 255.0)
        val rgb = FloatArray(20 * 3)
        for (i in 0 until 20) (if (i < 10) red else blue).copyInto(rgb, i * 3)
        val mix = AutoBlackWhite.mix(SourceColours(20, 1, rgb))
        // Spread is far above the target: no stretch; only the warm/cool bias (red up, blue down, both modest).
        assertThat(mix.red).isGreaterThan(0f)
        assertThat(mix.blue).isLessThan(0f)
        assertThat(abs(mix.red)).isLessThan(30f)
    }

    @Test
    fun `auto mix of a grey picture is the bias alone`() {
        val rgb = FloatArray(30) { 0.2f }
        val mix = AutoBlackWhite.mix(SourceColours(10, 1, rgb))
        assertThat(mix).isEqualTo(AutoBlackWhite.mix(SourceColours(10, 1, FloatArray(30) { 0.5f })))
        assertThat(mix.orange).isGreaterThan(0f)
        assertThat(mix.blue).isLessThan(0f)
        assertThat(mix).isNotEqualTo(BandValues())
    }
}
