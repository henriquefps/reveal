package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import org.junit.Test

/** The recipe's `heals` (docs/adr/0023): additive, round-trips, tolerant of newer apps. */
class HealCodecTest {
    @Test
    fun `a spot round-trips`() {
        val recipe = Recipe(
            heals = listOf(
                Heal("a", Heal.KIND_HEAL, 0.2f, 0.3f, 0.01f, dx = 0.05f, dy = -0.02f, autoSource = false),
                Heal("b", Heal.KIND_REMOVE, radius = 0.02f, stroke = "AAEC", method = "model:lama@2"),
                Heal("c", Heal.KIND_RED_EYE, 0.5f, 0.5f, 0.005f, darken = 80f),
            ),
        )
        assertThat(RecipeCodec.Default.decode(RecipeCodec.Default.encode(recipe))).isEqualTo(recipe)
    }

    @Test
    fun `the placeholder of older recipes renders nothing`() {
        val recipe = RecipeCodec.Default.decode("""{"v":1,"heals":[{"id":"h1"}]}""")
        assertThat(recipe.heals.single().renders).isFalse()
        assertThat(HealOp.active(recipe.heals)).isFalse()
    }

    @Test
    fun `a kind from a newer app is kept and renders nothing`() {
        val recipe = RecipeCodec.Default.decode(
            """{"v":1,"heals":[{"id":"n","kind":"generative","radius":0.1,"x":0.5,"y":0.5}]}""",
        )
        val heal = recipe.heals.single()
        assertThat(heal.type).isEqualTo(HealKind.UNKNOWN)
        assertThat(heal.renders).isFalse()
        assertThat(RecipeCodec.Default.encode(recipe)).contains("\"kind\":\"generative\"")
    }

    @Test
    fun `a model method falls back to the patch method when the app does not have it`() {
        assertThat(RemoveFills().methodFor("model:missing@1")).isEqualTo(Heal.METHOD_PATCH)
        assertThat(RemoveFills().methodFor(Heal.METHOD_PATCH)).isEqualTo(Heal.METHOD_PATCH)
    }

    @Test
    fun `the display encoding's table follows the sRGB curve and inverts`() {
        var v = 0f
        while (v < 20f) {
            assertThat(RemoveColour.encode(v)).isWithin(2e-5f).of(RemoveColour.exact(v))
            assertThat(RemoveColour.decode(RemoveColour.encode(v))).isWithin(1e-4f + 1e-4f * v).of(v)
            assertThat(RemoveColour.encode(-v)).isWithin(0f).of(-RemoveColour.encode(v))
            v += 0.0137f
        }
    }
}
