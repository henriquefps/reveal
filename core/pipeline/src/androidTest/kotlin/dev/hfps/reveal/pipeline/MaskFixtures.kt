package dev.hfps.reveal.pipeline

import dev.hfps.reveal.pipeline.mask.AiMask
import dev.hfps.reveal.pipeline.mask.AiMaskSource
import dev.hfps.reveal.pipeline.mask.MaskContext
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.LabColour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import dev.hfps.reveal.pipeline.recipe.StrokePressure
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Masked recipes and a fake AI source shared by the instrumented tests (docs/adr/0022). */
object MaskFixtures {
    /** A deterministic "model" mask: a soft blob in the upper middle, 48 x 32, whatever size is asked for. */
    val fakeAi: AiMask = AiMask(
        48,
        32,
        FloatArray(48 * 32) { i ->
            val x = (i % 48 + 0.5f) / 48f - 0.55f
            val y = (i / 48 + 0.5f) / 32f - 0.35f
            (1f - (x * x + y * y) * 9f).coerceIn(0f, 1f)
        },
    )

    /** A source that always has [fakeAi]. */
    val source: AiMaskSource = AiMaskSource { _, _ -> fakeAi }

    val context: MaskContext = MaskContext(source, "fixture")

    private val ai = MaskComponent.Ai(model = "fake@1", kind = "SUBJECT")

    /** A scribble of [n] points (fractions of the image), pressure varying. */
    fun scribble(n: Int, seed: Double = 0.0): List<StrokePoint> = List(n) { i ->
        val t = i / n.toDouble()
        StrokePoint(
            (0.2 + 0.6 * t).toFloat(),
            (0.55 + 0.2 * sin(t * 6 * PI + seed)).toFloat(),
            (0.5 + 0.5 * cos(t * 9 * PI)).toFloat(),
        )
    }

    private val brush = MaskComponent.Brush(
        strokes = listOf(
            BrushStroke.raw(scribble(60), size = 0.08f, feather = 60f, flow = 70f),
            BrushStroke.raw(scribble(20, 1.0).take(20), size = 0.04f, feather = 20f, erase = true),
        ),
    )

    private fun mask(id: String, adjustments: LocalAdjustments, vararg components: MaskComponent) =
        Mask(id, name = id, adjustments = adjustments, components = components.toList())

    /** A brush with M4/B's options: density, pressure on the flow (then on the size), an auto-mask stroke. */
    val brushOptions: Mask = Mask(
        "j",
        name = "j",
        adjustments = LocalAdjustments(exposure = 0.8f, saturation = 30f),
        components = listOf(
            MaskComponent.Brush(
                strokes = listOf(
                    BrushStroke.raw(
                        scribble(80),
                        size = 0.12f,
                        feather = 40f,
                        flow = 80f,
                        density = 60f,
                        autoMask = true,
                        pressure = StrokePressure.FLOW,
                    ),
                    BrushStroke.raw(
                        scribble(30, 0.5),
                        size = 0.05f,
                        erase = true,
                        density = 50f,
                        pressure = StrokePressure.SIZE,
                    ),
                ),
            ),
        ),
    )

    /** One recipe per component type and per local adjustment, plus combinations; (name, recipe). */
    val recipes: List<Pair<String, Recipe>> = listOf(
        "linear exposure contrast" to Recipe(
            masks = listOf(
                mask(
                    "a",
                    LocalAdjustments(exposure = -1f, contrast = 40f),
                    MaskComponent.Linear(x0 = 0.2f, y0 = 0f, x1 = 0.6f, y1 = 0.8f, feather = 80f),
                ),
            ),
        ),
        "radial tone" to Recipe(
            light = Light(highlights = -20f),
            masks = listOf(
                mask(
                    "b",
                    LocalAdjustments(highlights = -60f, shadows = 50f, whites = 20f, blacks = -20f),
                    MaskComponent.Radial(cx = 0.45f, cy = 0.5f, rx = 0.3f, ry = 0.15f, rotation = 25f, feather = 60f),
                ),
            ),
        ),
        "brush white balance saturation" to Recipe(
            masks = listOf(mask("c", LocalAdjustments(temperature = 40f, tint = -30f, saturation = 40f), brush)),
        ),
        "colour range hue tint" to Recipe(
            masks = listOf(
                mask(
                    "d",
                    LocalAdjustments(hue = 40f, colourHue = 200f, colourSaturation = 40f),
                    MaskComponent.ColourRange(
                        samples = listOf(LabColour(0.6f, 0.08f, 0.06f), LabColour(0.5f, -0.05f, -0.08f)),
                        range = 70f,
                        smoothness = 70f,
                    ),
                ),
            ),
        ),
        "luminance range clarity dehaze" to Recipe(
            masks = listOf(
                mask(
                    "e",
                    LocalAdjustments(clarity = 60f, dehaze = 40f),
                    MaskComponent.LuminanceRange(low = 40f, high = 90f, falloff = 20f),
                ),
            ),
        ),
        "ai texture sharpness" to Recipe(
            masks = listOf(mask("f", LocalAdjustments(texture = 60f, sharpness = 50f), ai)),
        ),
        "noise and soften" to Recipe(
            detail = Detail(nrLuminance = 20f),
            masks = listOf(
                mask("g", LocalAdjustments(noise = 70f), MaskComponent.Radial(cx = 0.3f, cy = 0.4f, rx = 0.25f)),
                mask(
                    "h",
                    LocalAdjustments(sharpness = -80f),
                    MaskComponent.Linear(x0 = 1f, y0 = 0.5f, x1 = 0.5f, y1 = 0.5f),
                ),
            ),
        ),
        "combination" to Recipe(
            masks = listOf(
                mask(
                    "i",
                    LocalAdjustments(exposure = 0.7f, saturation = -30f),
                    MaskComponent.Linear(x0 = 0f, y0 = 0f, x1 = 0f, y1 = 1f),
                    MaskComponent.Radial(op = MaskOp.SUBTRACT, cx = 0.5f, cy = 0.3f, rx = 0.2f, feather = 50f),
                    MaskComponent.LuminanceRange(op = MaskOp.INTERSECT, invert = true, low = 85f, falloff = 10f),
                ),
            ),
        ),
        // M4/B's brush options (docs/adr/0022): density, pressure on the flow or the size, the auto-mask.
        "brush density pressure auto-mask" to Recipe(masks = listOf(brushOptions)),
        "five masks" to fiveMasks(brush),
    )

    /** One mask of each type (the AI's from [source]), every adjustment somewhere. */
    fun fiveMasks(brushComponent: MaskComponent.Brush = brush): Recipe = Recipe(
        light = Light(contrast = 10f),
        masks = listOf(
            mask(
                "sky",
                LocalAdjustments(exposure = -0.6f, dehaze = 30f, temperature = -15f, whites = 15f),
                MaskComponent.Linear(x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 0.55f),
            ),
            mask(
                "spot",
                LocalAdjustments(shadows = 40f, clarity = 30f, tint = 10f),
                MaskComponent.Radial(cx = 0.3f, cy = 0.7f, rx = 0.2f, ry = 0.12f, rotation = -20f),
            ),
            mask("paint", LocalAdjustments(saturation = 40f, sharpness = 40f, contrast = 20f), brushComponent),
            mask(
                "bright",
                LocalAdjustments(hue = 25f, noise = 40f, highlights = -40f),
                MaskComponent.LuminanceRange(low = 55f, high = 100f, falloff = 15f),
                MaskComponent.ColourRange(
                    op = MaskOp.INTERSECT,
                    samples = listOf(LabColour(0.7f, 0f, 0.05f)),
                    range = 90f,
                    smoothness = 80f,
                ),
            ),
            mask(
                "subject",
                LocalAdjustments(colourHue = 30f, colourSaturation = 30f, texture = 40f, blacks = -15f),
                ai,
            ),
        ),
    )
}
