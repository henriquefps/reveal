package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import org.junit.Test

/**
 * The preview keeps everything but Detail and Effects across a drag of their sliders ([DevelopParams.withLocal],
 * docs/adr/0012 "M2/P"): that must equal resolving the whole recipe, every field included.
 */
class DevelopParamsTest {
    @Test
    fun `withLocal on the rest of the recipe equals resolving the whole recipe`() {
        val input = SyntheticImages.noisyCard(32, 24, seed = 2)
        val recipe = Recipe(
            profile = Profile(BaseLook.CAMERA_MATCH, 120f),
            light = Light(0.3f, contrast = 20f),
            optics = Optics(removeCa = true, purpleAmount = 10f),
            detail = Detail(nrLuminance = 40f, nrColour = 50f, sharpenAmount = 60f),
            effects = Effects(texture = 20f, clarity = 30f, vignetteAmount = -20f, grainAmount = 10f),
        )
        for (scale in listOf(1f, 2.5f)) {
            val base = DevelopParams.from(input, recipe.withoutLocal(), scale)
            assertThat(base.withLocal(input, recipe, scale)).isEqualTo(DevelopParams.from(input, recipe, scale))
        }
    }
}
