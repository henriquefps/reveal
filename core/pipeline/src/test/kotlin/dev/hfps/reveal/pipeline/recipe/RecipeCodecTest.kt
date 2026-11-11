package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class RecipeCodecTest {
    private val codec = RecipeCodec.Default

    @Test
    fun `defaults mean no change`() {
        val recipe = Recipe()

        assertThat(recipe.v).isEqualTo(RECIPE_VERSION)
        assertThat(recipe.light.exposureEv).isEqualTo(0f)
        assertThat(recipe.colour.whiteBalance).isEqualTo(WhiteBalance(null, null))
        assertThat(recipe.masks).isEmpty()
        assertThat(recipe.heals).isEmpty()
        assertThat(recipe.isDefault()).isTrue()
        assertThat(Recipe.DEFAULT).isEqualTo(recipe)
    }

    @Test
    fun `round trip keeps every field`() {
        val recipe = Recipe(
            light = Light(exposureEv = 0.7f),
            colour = Colour(WhiteBalance(temperatureK = 5600f, tint = -12.5f)),
            masks = listOf(Mask("m1")),
            heals = listOf(Heal("h1"), Heal("h2")),
        )

        assertThat(codec.decode(codec.encode(recipe))).isEqualTo(recipe)
        assertThat(recipe.isDefault()).isFalse()
    }

    @Test
    fun `default recipe round trips and has explicit field names`() {
        val text = codec.encode(Recipe())

        assertThat(text).isEqualTo(
            """{"v":1,"profile":{"base":"standard"},"light":{"exposure":0.0},""" +
                """"colour":{"whiteBalance":{"temperature":null,"tint":null}},""" +
                """"effects":{},"detail":{},"optics":{},"geometry":{},"masks":[],"heals":[]}""",
        )
        assertThat(codec.decode(text)).isEqualTo(Recipe())
    }

    @Test
    fun `missing fields take their defaults`() {
        val recipe = codec.decode("""{"v":1,"light":{"exposure":1.5}}""")

        assertThat(recipe).isEqualTo(Recipe(light = Light(1.5f)))
    }

    @Test
    fun `unknown fields at the current version are ignored`() {
        val recipe = codec.decode("""{"v":1,"light":{"exposure":1.0,"futureSlider":3},"newGroup":{"x":1}}""")

        assertThat(recipe.light.exposureEv).isEqualTo(1.0f)
    }

    @Test
    fun `a newer version is refused, never downgraded`() {
        val error = assertThrows(UnsupportedRecipeVersionException::class.java) { codec.decode("""{"v":2}""") }

        assertThat(error.found).isEqualTo(2)
        assertThat(error.supported).isEqualTo(RECIPE_VERSION)
    }

    @Test
    fun `broken input is reported as invalid`() {
        assertThrows(InvalidRecipeException::class.java) { codec.decode("not json") }
        assertThrows(InvalidRecipeException::class.java) { codec.decode("[1]") }
        assertThrows(InvalidRecipeException::class.java) { codec.decode("""{"light":{}}""") }
        assertThrows(InvalidRecipeException::class.java) { codec.decode("""{"v":"x"}""") }
        assertThrows(InvalidRecipeException::class.java) { codec.decode("""{"v":0}""") }
    }

    @Test
    fun `migrators run in order from the stored version and stamp the new version`() {
        val calls = mutableListOf<Int>()
        // Pretend v1 stored exposure as "ev" and v2 renamed it to "exposure" (the real schema is still v1).
        val migrators = mapOf(
            0 + 1 to RecipeMigrator { old ->
                calls += 1
                val ev = (old["light"] as JsonObject)["ev"]!!
                JsonObject(old + ("light" to JsonObject(mapOf("exposure" to ev))))
            },
            2 to RecipeMigrator { old ->
                calls += 2
                old
            },
        )
        val future = RecipeCodec(currentVersion = 3, migrators = migrators)

        // Decoding into the real model works because the test migrators produce the current field names.
        val decoded = future.decode("""{"v":1,"light":{"ev":2.0}}""")

        assertThat(calls).containsExactly(1, 2).inOrder()
        assertThat(decoded.light.exposureEv).isEqualTo(2.0f)
        assertThat(decoded.v).isEqualTo(3)
    }

    @Test
    fun `a missing migrator is an error`() {
        val codec = RecipeCodec(currentVersion = 2, migrators = emptyMap())

        assertThrows(InvalidRecipeException::class.java) { codec.decode("""{"v":1}""") }
    }

    @Test
    fun `a migrator cannot leave the old version stamped`() {
        val codec = RecipeCodec(
            currentVersion = 2,
            migrators = mapOf(1 to RecipeMigrator { JsonObject(it + ("v" to JsonPrimitive(1))) }),
        )

        assertThat(codec.decode("""{"v":1}""").v).isEqualTo(2)
    }

    @Test
    fun `the production migrator table covers every version below the current one`() {
        for (from in 1 until RECIPE_VERSION) assertThat(RECIPE_MIGRATORS).containsKey(from)
    }
}
