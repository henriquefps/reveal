package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Test

class ProfileLooksTest {
    private fun render(rgb: FloatArray, profile: Profile): FloatArray =
        rgb.copyOf().also { ProfileOp.apply(it, ProfileLooks.resolve(profile)) }

    private fun chroma(rgb: FloatArray, i: Int = 0): Float {
        val l = FloatArray(3).also { OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], it) }
        return sqrt(l[1] * l[1] + l[2] * l[2])
    }

    private val greys =
        FloatArray(3 * 40) { (it / 3).let { k -> 0.0005f * StrictMath.pow(1.3, k.toDouble()).toFloat() } }

    @Suppress("NestedBlockDepth")
    @Test
    fun `every look and amount keeps greys neutral and bounded`() {
        for (look in BaseLook.entries) {
            for (amount in listOf(0f, 35f, 100f, 160f, 200f)) {
                val out = render(greys, Profile(look, amount))
                for (p in 0 until greys.size / 3) {
                    assertThat(out[p * 3]).isWithin(2e-4f).of(out[p * 3 + 1])
                    assertThat(out[p * 3 + 2]).isWithin(2e-4f).of(out[p * 3 + 1])
                    assertThat(out[p * 3 + 1]).isLessThan(1f)
                    if (p > 0) assertThat(out[p * 3 + 1]).isAtLeast(out[(p - 1) * 3 + 1] - 1e-5f)
                }
            }
        }
    }

    @Test
    fun `wide-gamut and negative inputs leave every look with non-negative luminance`() {
        val random = kotlin.random.Random(2)
        val rgb = FloatArray(3 * 2000) { random.nextFloat() * 1.2f - 0.15f }
        for (look in BaseLook.entries) {
            for (amount in listOf(100f, 200f)) {
                val out = render(rgb, Profile(look, amount))
                for (p in 0 until out.size / 3) {
                    val y = 0.2627f * out[p * 3] + 0.678f * out[p * 3 + 1] + 0.0593f * out[p * 3 + 2]
                    // A strong chroma boost may push one channel of a saturated colour outside Rec.2020 (clipped at
                    // the output); the luminance stays non-negative.
                    assertThat(y).isAtLeast(-1e-3f)
                }
            }
        }
    }

    @Test
    fun `amount 0 is Standard and Standard ignores the amount`() {
        val scene = SyntheticImages.scene(32, 24).linearRgb
        val standard = render(scene, Profile())
        for (look in BaseLook.entries) {
            val zero = render(scene, Profile(look, 0f))
            for (i in scene.indices) assertThat(zero[i]).isWithin(1e-6f).of(standard[i])
        }
        assertThat(render(scene, Profile(BaseLook.STANDARD, 30f))).isEqualTo(standard)
    }

    @Test
    fun `vivid adds chroma with the amount, neutral removes it, monochrome removes all of it`() {
        val colour = floatArrayOf(0.05f, 0.03f, 0.012f)
        val std = chroma(render(colour, Profile()))
        val vivid50 = chroma(render(colour, Profile(BaseLook.VIVID, 50f)))
        val vivid100 = chroma(render(colour, Profile(BaseLook.VIVID, 100f)))
        val vivid200 = chroma(render(colour, Profile(BaseLook.VIVID, 200f)))
        assertThat(vivid50).isGreaterThan(std)
        assertThat(vivid100).isGreaterThan(vivid50)
        assertThat(vivid200).isGreaterThan(vivid100)
        assertThat(chroma(render(colour, Profile(BaseLook.NEUTRAL)))).isLessThan(std)
        for (amount in listOf(100f, 200f)) {
            val mono = render(colour, Profile(BaseLook.MONOCHROME, amount))
            assertThat(abs(mono[0] - mono[1]) + abs(mono[2] - mono[1])).isLessThan(1e-5f)
        }
    }

    @Test
    fun `neutral is flatter and vivid steeper than standard around grey`() {
        fun slope(look: BaseLook): Float {
            val g = (0.18 / ProfileLooks.STANDARD_FILMIC.gain).toFloat()
            val lo = render(floatArrayOf(g * 0.8f, g * 0.8f, g * 0.8f), Profile(look))[1]
            val hi = render(floatArrayOf(g * 1.25f, g * 1.25f, g * 1.25f), Profile(look))[1]
            return hi / lo
        }
        assertThat(slope(BaseLook.NEUTRAL)).isLessThan(slope(BaseLook.STANDARD))
        assertThat(slope(BaseLook.VIVID)).isGreaterThan(slope(BaseLook.STANDARD))
    }

    @Test
    fun `portrait mutes skin more than other hues and landscape lifts greens and blues more than skin`() {
        val skin = floatArrayOf(0.06f, 0.04f, 0.03f)
        val green = floatArrayOf(0.025f, 0.05f, 0.02f)
        val std = Profile()
        fun ratio(rgb: FloatArray, look: BaseLook) = chroma(render(rgb, Profile(look))) / chroma(render(rgb, std))
        assertThat(ratio(skin, BaseLook.PORTRAIT)).isLessThan(ratio(green, BaseLook.PORTRAIT))
        assertThat(ratio(green, BaseLook.LANDSCAPE)).isGreaterThan(ratio(skin, BaseLook.LANDSCAPE))
        assertThat(ratio(green, BaseLook.LANDSCAPE)).isGreaterThan(1.1f)
    }

    @Test
    fun `display-referred input ignores the profile`() {
        val (input, _) = SyntheticImages.srgb8Chart()
        val a = CpuDevelop.renderLinear(input, Recipe())
        val b = CpuDevelop.renderLinear(input, Recipe(profile = Profile(BaseLook.VIVID, 200f)))
        assertThat(b).isEqualTo(a)
        // ...but vibrance and saturation apply to every input.
        val c = CpuDevelop.renderLinear(input, Recipe(colour = Colour(saturation = 40f)))
        assertThat(c).isNotEqualTo(a)
    }

    @Test
    fun `the default render of a RAW is about one and a half stops brighter than M0 at grey`() {
        // M0 rendered scene grey 0.18 as display 0.18; Standard now maps 0.18 / 2^gain there (ADR 0008).
        val g = 0.18f / ProfileLooks.STANDARD_FILMIC.gain.toFloat()
        val raw = PipelineInput(
            SyntheticImages.rawRamp(1, 1, 1f).kind,
            1,
            1,
            FloatArray(3) { g / SyntheticImages.TEST_AS_SHOT[it] },
            SyntheticImages.TEST_CAMERA_TO_XYZ,
            SyntheticImages.TEST_AS_SHOT,
        )
        val out = CpuDevelop.renderLinear(raw, Recipe())
        assertThat(out[1]).isWithin(1e-3f).of(0.18f)
        assertThat(ProfileLooks.STANDARD_FILMIC.gainEv).isGreaterThan(1.0)
    }
}
