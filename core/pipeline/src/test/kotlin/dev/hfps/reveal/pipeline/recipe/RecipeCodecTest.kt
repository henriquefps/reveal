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

    private companion object {
        const val BANDS = """{"red":0.0,"orange":0.0,"yellow":0.0,"green":0.0,""" +
            """"aqua":0.0,"blue":0.0,"purple":0.0,"magenta":0.0}"""
        const val WHEEL = """{"hue":0.0,"saturation":0.0,"luminance":0.0}"""
    }

    @Test
    fun `colour tools round trip`() {
        val recipe = Recipe(
            profile = Profile(treatment = Treatment.BLACK_WHITE),
            light = Light(curve = Curves(parametric = ParametricCurve(lights = 20f), red = Curves.MEDIUM_CONTRAST)),
            colour = Colour(
                mixer = ColourMixer(hue = BandValues(aqua = -30f)),
                grading = ColourGrading(midtones = GradeWheel(30f, 20f, -5f), balance = 40f),
                greyMixer = BandValues(blue = -50f),
            ),
        )
        assertThat(codec.decode(codec.encode(recipe))).isEqualTo(recipe)
    }

    @Test
    fun `default recipe round trips and has explicit field names`() {
        val text = codec.encode(Recipe())

        assertThat(text).isEqualTo(
            """{"v":1,"profile":{"base":"standard","amount":100.0,"treatment":"colour"},""" +
                """"light":{"exposure":0.0,"contrast":0.0,"highlights":0.0,"shadows":0.0,""" +
                """"whites":0.0,"blacks":0.0,"curve":{"parametric":{"highlights":0.0,"lights":0.0,"darks":0.0,""" +
                """"shadows":0.0,"splitShadows":25.0,"splitMidtones":50.0,"splitHighlights":75.0},""" +
                """"rgb":[],"red":[],"green":[],"blue":[]}},""" +
                """"colour":{"whiteBalance":{"temperature":null,"tint":null,"mode":"as_shot",""" +
                """"relTemperature":0.0,"relTint":0.0},"vibrance":0.0,"saturation":0.0,""" +
                """"mixer":{"hue":$BANDS,"saturation":$BANDS,"luminance":$BANDS},""" +
                """"grading":{"shadows":$WHEEL,"midtones":$WHEEL,"highlights":$WHEEL,"global":$WHEEL,""" +
                """"blending":50.0,"balance":0.0},"greyMixer":$BANDS},""" +
                """"effects":{},"detail":{"sharpenAmount":0.0,"sharpenRadius":1.0,"sharpenDetail":25.0,""" +
                """"sharpenMasking":0.0,"nrLuminance":0.0,"nrLuminanceDetail":50.0,"nrLuminanceContrast":0.0,""" +
                """"nrColour":0.0,"nrColourDetail":50.0,"nrColourSmoothness":50.0},"optics":{},""" +
                """"geometry":{"crop":null,"angle":0.0,"rotate":0,"flipH":false,"flipV":false,""" +
                """"aspect":{"preset":"free","w":1,"h":1,"locked":false},"constrain":true},"masks":[],"heals":[]}""",
        )
        assertThat(codec.decode(text)).isEqualTo(Recipe())
    }

    @Test
    fun `a recipe stored before Detail had fields reads its defaults`() {
        // Detail was an empty reserved object until M2 (docs/adr/0012): additive, no RECIPE_VERSION bump.
        val recipe = codec.decode("""{"v":1,"light":{"exposure":0.5},"detail":{}}""")

        assertThat(recipe).isEqualTo(Recipe(light = Light(0.5f)))
        val edited = Recipe(detail = Detail(sharpenAmount = 40f, nrLuminance = 25f, nrColourSmoothness = 70f))
        assertThat(codec.decode(codec.encode(edited))).isEqualTo(edited)
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

    @Test
    fun `round trip keeps every M1 field`() {
        val recipe = Recipe(
            profile = Profile(BaseLook.CAMERA_MATCH, 140f),
            light = Light(0.7f, contrast = 12f, highlights = -30f, shadows = 25f, whites = 5f, blacks = -8f),
            colour = Colour(
                WhiteBalance(5600f, -12f, WbMode.CUSTOM, relTemperature = 10f, relTint = -4f),
                vibrance = 20f,
                saturation = -15f,
            ),
            geometry = Geometry(
                crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f),
                angle = -1.5f,
                rotate = 3,
                flipH = true,
                flipV = true,
                aspect = Aspect(AspectPreset.CUSTOM, 7, 5, locked = true),
            ),
        )

        assertThat(codec.decode(codec.encode(recipe))).isEqualTo(recipe)
        assertThat(recipe.isDefault()).isFalse()
    }

    @Test
    fun `a stored M0 recipe still decodes with the new fields at their defaults`() {
        val m0 = """{"v":1,"profile":{"base":"standard"},"light":{"exposure":0.7},""" +
            """"colour":{"whiteBalance":{"temperature":5600.0,"tint":-12.5}},""" +
            """"effects":{},"detail":{},"optics":{},"geometry":{},"masks":[],"heals":[]}"""

        val recipe = codec.decode(m0)

        assertThat(recipe).isEqualTo(
            Recipe(light = Light(0.7f), colour = Colour(WhiteBalance(temperatureK = 5600f, tint = -12.5f))),
        )
        assertThat(recipe.geometry).isEqualTo(Geometry())
        assertThat(recipe.profile.amountPercent).isEqualTo(100f)
    }

    @Test
    fun `every look, white balance mode and aspect has a distinct stable key`() {
        for (look in BaseLook.entries) {
            val text = codec.encode(Recipe(profile = Profile(look)))
            assertThat(codec.decode(text).profile.base).isEqualTo(look)
        }
        assertThat(codec.encode(Recipe(profile = Profile(BaseLook.CAMERA_MATCH)))).contains("\"camera_match\"")
        for (mode in WbMode.entries) {
            val text = codec.encode(Recipe(colour = Colour(WhiteBalance(mode = mode))))
            assertThat(codec.decode(text).colour.whiteBalance.mode).isEqualTo(mode)
        }
        for (preset in AspectPreset.entries) {
            val text = codec.encode(Recipe(geometry = Geometry(aspect = Aspect(preset))))
            assertThat(codec.decode(text).geometry.aspect.preset).isEqualTo(preset)
        }
    }

    @Test
    fun `invalid geometry is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CropRect(0.5f, 0f, 0.5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { CropRect(-0.1f, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(rotate = 4) }
        assertThrows(IllegalArgumentException::class.java) { Aspect(w = 0) }
    }
}
