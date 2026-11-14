package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Test

/** Red eye and the automatic source pick (docs/adr/0023). */
class RedEyeAndSourceTest {
    @Test
    fun `red eye neutralises the pupil and leaves the skin`() {
        val w = 80
        val h = 80
        val eye = HealScenes.redEye(w, h, iris = 14f, pupil = 7f)
        val spot = HealScenes.spot("e", Heal.KIND_RED_EYE, w, h, 40f, 40f, 16f)
        val out = HealOp.apply(eye, listOf(spot)).linearRgb
        val centre = (40 * w + 40) * 3
        // Not red any more: the red channel is no longer far above green and blue, and darker than before.
        assertThat(out[centre]).isLessThan(eye.linearRgb[centre] * 0.3f)
        assertThat(abs(out[centre] - out[centre + 1])).isLessThan(0.03f)
        // Skin outside the circle untouched; the dark iris barely moves.
        val skin = (5 * w + 5) * 3
        for (c in 0..2) assertThat(out[skin + c]).isEqualTo(eye.linearRgb[skin + c])
        val iris = (40 * w + 52) * 3
        assertThat(abs(out[iris] - eye.linearRgb[iris])).isLessThan(0.01f)
    }

    @Test
    fun `the source pick avoids the shape and finds clean texture`() {
        val w = 200
        val h = 150
        val clean = HealScenes.texturedGradient(w, h)
        // The spot, and a second blemish right next to it that a naive pick would copy.
        val dusty = HealScenes.withDust(HealScenes.withDust(clean, 100f, 75f, 8f), 100f, 100f, 9f, depth = 0.8f)
        val spot = HealScenes.spot("h", Heal.KIND_HEAL, w, h, 100f, 75f, 10f)
        val reader = PixelReader.of(dusty)
        val (dx, dy) = checkNotNull(SourcePicker.pick(spot, reader, dusty.colourOnlyForTest()))
        val ox = dx * w
        val oy = dy * h
        assertThat(hypot(ox, oy)).isAtLeast(19f)
        // The source circle does not contain the other blemish.
        assertThat(hypot(100f + ox - 100f, 75f + oy - 100f)).isGreaterThan(18f)
        // Deterministic.
        assertThat(SourcePicker.pick(spot, reader, dusty.colourOnlyForTest())).isEqualTo(dx to dy)
    }

    private fun dev.hfps.reveal.pipeline.PipelineInput.colourOnlyForTest() =
        dev.hfps.reveal.pipeline.PipelineInput(kind, 1, 1, FloatArray(3), cameraToXyz, asShotMultipliers)
}
