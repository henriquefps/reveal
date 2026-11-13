@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The masks' JSON (docs/adr/0022, "The recipe"): every component round-trips, older and newer recipes decode. */
class MasksCodecTest {
    private val codec = RecipeCodec.Default

    private val everything = Mask(
        id = "6f1c0d2e",
        name = "Sky",
        enabled = false,
        amount = 80f,
        adjustments = LocalAdjustments(exposure = -0.7f, dehaze = 25f, colourHue = 220f, colourSaturation = 15f),
        components = listOf(
            MaskComponent.Linear(x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 0.45f, feather = 100f),
            MaskComponent.Radial(op = MaskOp.SUBTRACT, cx = 0.3f, cy = 0.6f, rx = 0.1f, ry = 0.2f, rotation = 30f),
            MaskComponent.Brush(
                op = MaskOp.ADD,
                strokes = listOf(
                    BrushStroke.raw(listOf(StrokePoint(0.1f, 0.2f, 0.5f), StrokePoint(0.2f, 0.25f, 1f))),
                    BrushStroke.raw(listOf(StrokePoint(0.15f, 0.2f)), erase = true),
                ),
            ),
            MaskComponent.ColourRange(samples = listOf(LabColour(0.7f, -0.02f, -0.1f)), range = 40f),
            MaskComponent.LuminanceRange(op = MaskOp.INTERSECT, invert = true, low = 60f, high = 100f, falloff = 5f),
            MaskComponent.Ai(
                model = "selfie-multiclass@1",
                kind = "HAIR",
                prompts = listOf(MaskPoint(0.42f, 0.31f)),
                refine = AiRefine(8f, 0.001f),
            ),
        ),
    )

    @Test
    fun `every component round-trips`() {
        val recipe = Recipe(masks = listOf(everything, Mask("second", name = "Face")))
        val decoded = codec.decode(codec.encode(recipe))
        assertThat(decoded).isEqualTo(recipe)
        assertThat(decoded.masks[0].components.map { it.type })
            .containsExactly("linear", "radial", "brush", "colourRange", "luminanceRange", "ai").inOrder()
    }

    @Test
    fun `an ai component is written as ADR 0021 says`() {
        val json = Json.parseToJsonElement(codec.encode(Recipe(masks = listOf(everything)))).jsonObject
        val ai = json["masks"]!!.jsonArray[0].jsonObject["components"]!!.jsonArray[5].jsonObject
        assertThat(ai["type"]!!.jsonPrimitive.content).isEqualTo("ai")
        assertThat(ai["model"]!!.jsonPrimitive.content).isEqualTo("selfie-multiclass@1")
        assertThat(ai["kind"]!!.jsonPrimitive.content).isEqualTo("HAIR")
        assertThat(ai["prompts"]!!.jsonArray[0].jsonObject["x"]!!.jsonPrimitive.content).isEqualTo("0.42")
        assertThat(ai["refine"]!!.jsonObject["radius"]!!.jsonPrimitive.content).isEqualTo("8.0")
        assertThat(ai["invert"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `old recipes decode with no masks or the placeholder`() {
        val old = codec.encode(Recipe()).replace(""","masks":[]""", "")
        assertThat(old).doesNotContain("masks")
        assertThat(codec.decode(old).masks).isEmpty()
        val placeholder = codec.encode(Recipe()).replace(""""masks":[]""", """"masks":[{"id":"m"}]""")
        val mask = codec.decode(placeholder).masks.single()
        assertThat(mask).isEqualTo(Mask("m"))
        assertThat(mask.enabled).isTrue()
        assertThat(mask.adjustments.isNeutral).isTrue()
        assertThat(mask.components).isEmpty()
    }

    @Test
    fun `a component type from a newer app is kept and skipped`() {
        val newer = """{"type":"depth","op":"add","near":0.2,"far":0.6}"""
        val text = codec.encode(Recipe(masks = listOf(Mask("m"))))
            .replace(""""components":[]""", """"components":[$newer]""")
        val decoded = codec.decode(text)
        val component = decoded.masks.single().components.single()
        assertThat(component).isInstanceOf(MaskComponent.Unknown::class.java)
        assertThat(component.type).isEqualTo("depth")
        // Written back unchanged (sync and sidecars never lose it).
        val again = Json.parseToJsonElement(codec.encode(decoded)).jsonObject
        val written = again["masks"]!!.jsonArray[0].jsonObject["components"] as JsonArray
        assertThat(written[0]).isEqualTo(Json.parseToJsonElement(newer) as JsonObject)
    }

    @Test
    fun `local adjustments get and set by name, neutral means nothing`() {
        var a = LocalAdjustments()
        assertThat(a.isNeutral).isTrue()
        for ((i, adjustment) in LocalAdjustment.entries.withIndex()) {
            a = a.with(adjustment, i + 1f)
            assertThat(a[adjustment]).isEqualTo(i + 1f)
        }
        assertThat(a.isNeutral).isFalse()
        // A colour hue alone tints nothing.
        assertThat(LocalAdjustments(colourHue = 200f).isNeutral).isTrue()
        assertThat(LocalAdjustment.EXPOSURE.clamp(9f)).isEqualTo(4f)
        assertThat(LocalAdjustment.HUE.clamp(-400f)).isEqualTo(-180f)
    }
}
