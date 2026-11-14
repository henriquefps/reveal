package dev.hfps.reveal.data.xmp

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.UprightMode
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import kotlin.math.abs
import org.junit.Test

class CameraRawTest {
    private val raw = PhotoFrame(raw = true, orientation = 1, width = 6000, height = 4000)
    private val jpeg = PhotoFrame(raw = false, orientation = 1, width = 6000, height = 4000)

    /** Every mapped field away from its default, on Camera Raw's own grid (so the text form is exact). */
    private val edited = Recipe(
        profile = Profile(treatment = Treatment.BLACK_WHITE),
        light = Light(
            exposureEv = 0.7f,
            contrast = 15f,
            highlights = -40f,
            shadows = 30f,
            whites = 5f,
            blacks = -10f,
            curve = Curves(
                parametric = ParametricCurve(
                    highlights = -20f,
                    lights = 10f,
                    darks = 5f,
                    shadows = -5f,
                    splitMidtones = 45f,
                ),
                rgb = listOf(CurvePoint(0f, 20 / 255f), CurvePoint(128 / 255f, 140 / 255f), CurvePoint(1f, 1f)),
                blue = listOf(CurvePoint(0f, 0f), CurvePoint(64 / 255f, 70 / 255f), CurvePoint(1f, 1f)),
            ),
        ),
        colour = Colour(
            whiteBalance = WhiteBalance(temperatureK = 5600f, tint = 8f, mode = WbMode.CUSTOM),
            vibrance = 20f,
            saturation = -5f,
            mixer = ColourMixer(
                hue = BandValues(orange = -5f),
                saturation = BandValues(blue = -15f),
                luminance = BandValues(aqua = 12f),
            ),
            grading = ColourGrading(
                shadows = GradeWheel(hue = 220f, saturation = 15f, luminance = -5f),
                midtones = GradeWheel(hue = 40f, saturation = 5f, luminance = 3f),
                highlights = GradeWheel(hue = 50f, saturation = 20f, luminance = 4f),
                global = GradeWheel(hue = 30f, saturation = 2f, luminance = -1f),
                blending = 60f,
                balance = -10f,
            ),
            greyMixer = BandValues(blue = -30f, red = 12f),
        ),
        effects = Effects(
            texture = 10f,
            clarity = 12f,
            dehaze = 8f,
            vignetteAmount = -18f,
            vignetteMidpoint = 40f,
            vignetteRoundness = 10f,
            vignetteFeather = 60f,
            vignetteHighlights = 15f,
            grainAmount = 25f,
            grainSize = 30f,
            grainRoughness = 60f,
        ),
        detail = Detail(
            sharpenAmount = 60f,
            sharpenRadius = 1.2f,
            sharpenDetail = 30f,
            sharpenMasking = 20f,
            nrLuminance = 20f,
            nrLuminanceDetail = 45f,
            nrLuminanceContrast = 10f,
            nrColour = 30f,
            nrColourDetail = 40f,
            nrColourSmoothness = 55f,
        ),
        optics = Optics(
            lensCorrections = true,
            distortionPercent = 80f,
            vignettingPercent = 120f,
            removeCa = true,
            purpleAmount = 5f,
            purpleHueLow = 25f,
            purpleHueHigh = 75f,
            greenAmount = 3f,
            greenHueLow = 35f,
            greenHueHigh = 65f,
        ),
        geometry = Geometry(
            transform = Transform(
                vertical = -20f,
                horizontal = 5f,
                rotate = 1.5f,
                aspect = 3f,
                scale = 105f,
                offsetX = 2.5f,
                offsetY = -1.5f,
                upright = UprightMode.VERTICAL,
            ),
        ),
    )

    @Test
    fun `every mapped field survives recipe to Camera Raw to recipe`() {
        val values = CameraRaw.values(edited, raw)
        val groups = CameraRaw.fields.map { it.group }.toSet()
        assertThat(CameraRaw.apply(Recipe(), values, groups, raw)).isEqualTo(edited)
    }

    @Test
    fun `values are written as Lightroom writes them`() {
        val values = CameraRaw.values(edited, raw)
        assertThat(values["Exposure2012"]).isEqualTo("+0.70")
        assertThat(values["Highlights2012"]).isEqualTo("-40")
        assertThat(values["Contrast2012"]).isEqualTo("+15")
        assertThat(values["Temperature"]).isEqualTo("5600")
        assertThat(values["Tint"]).isEqualTo("+8")
        assertThat(values["WhiteBalance"]).isEqualTo("Custom")
        assertThat(values["SharpenRadius"]).isEqualTo("+1.2")
        assertThat(values["ConvertToGrayscale"]).isEqualTo("True")
        assertThat(values["PerspectiveUpright"]).isEqualTo("3")
        assertThat(values["ToneCurvePV2012"]).isEqualTo("0, 20;128, 140;255, 255")
        assertThat(values["ToneCurvePV2012Red"]).isEqualTo("0, 0;255, 255")
        val defaults = CameraRaw.values(Recipe(), raw)
        assertThat(defaults["Exposure2012"]).isEqualTo("0.00")
        assertThat(defaults["WhiteBalance"]).isEqualTo("As Shot")
        assertThat(defaults).doesNotContainKey("Temperature")
        assertThat(defaults["Sharpness"]).isEqualTo("0")
    }

    @Test
    fun `a JPEG's white balance is Lightroom's relative scale`() {
        val recipe =
            Recipe(
                colour = Colour(whiteBalance = WhiteBalance(mode = WbMode.CUSTOM, relTemperature = 12f, relTint = -4f)),
            )
        val values = CameraRaw.values(recipe, jpeg)
        assertThat(values["Temperature"]).isEqualTo("+12")
        assertThat(values["Tint"]).isEqualTo("-4")
        assertThat(CameraRaw.apply(Recipe(), values, setOf("wb"), jpeg)).isEqualTo(recipe)
    }

    @Test
    fun `as shot clears the white balance values and ignores the kelvin Lightroom writes with it`() {
        val start =
            Recipe(colour = Colour(whiteBalance = WhiteBalance(temperatureK = 4000f, tint = 3f, mode = WbMode.CUSTOM)))
        val read = CameraRaw.apply(
            start,
            mapOf("WhiteBalance" to "As Shot", "Temperature" to "5250", "Tint" to "+4"),
            setOf("wb"),
            raw,
        )
        assertThat(read.colour.whiteBalance).isEqualTo(WhiteBalance())
    }

    @Test
    fun `out of range and malformed values are clamped or skipped, never thrown`() {
        val read = CameraRaw.apply(
            Recipe(),
            mapOf(
                "Exposure2012" to "+9.5",
                "Contrast2012" to "abc",
                "Shadows2012" to "NaN",
                "Sharpness" to "-20",
                "ToneCurvePV2012" to "0, 0;0, 255",
                "PerspectiveUpright" to "17",
                "ParametricShadowSplit" to "99",
            ),
            CameraRaw.fields.map { it.group }.toSet(),
            raw,
        )
        assertThat(read.light.exposureEv).isEqualTo(5f)
        assertThat(read.light.contrast).isEqualTo(0f)
        assertThat(read.light.shadows).isEqualTo(0f)
        assertThat(read.detail.sharpenAmount).isEqualTo(0f)
        assertThat(read.light.curve.rgb).isEmpty()
        assertThat(read.geometry.transform.upright).isEqualTo(UprightMode.OFF)
        assertThat(read.light.curve.parametric.splitShadows).isEqualTo(95f)
    }

    @Test
    fun `normalise compares Camera Raw text by value`() {
        assertThat(CameraRaw.same("Contrast2012", "25", "+25.0")).isTrue()
        assertThat(CameraRaw.same("Exposure2012", "0.7", "+0.70")).isTrue()
        assertThat(CameraRaw.same("Exposure2012", "-0.00", "0")).isTrue()
        assertThat(CameraRaw.same("ConvertToGrayscale", "true", "True")).isTrue()
        assertThat(CameraRaw.same("WhiteBalance", "as shot", "As Shot")).isTrue()
        assertThat(CameraRaw.same("Contrast2012", "25", "26")).isFalse()
        assertThat(CameraRaw.same("ToneCurvePV2012", "0, 0;255,255", "0, 0;255, 255")).isTrue()
    }

    @Test
    fun `an axis-aligned crop is the corners in the stored image`() {
        val recipe = Recipe(geometry = Geometry(crop = CropRect(0.1f, 0.05f, 0.9f, 0.95f)))
        val crop = CameraRawGeometry.crop(recipe, raw)
        assertThat(crop["HasCrop"]).isEqualTo("True")
        assertThat(crop["CropLeft"]!!.toDouble()).isWithin(1e-6).of(0.1)
        assertThat(crop["CropTop"]!!.toDouble()).isWithin(1e-6).of(0.05)
        assertThat(crop["CropRight"]!!.toDouble()).isWithin(1e-6).of(0.9)
        assertThat(crop["CropBottom"]!!.toDouble()).isWithin(1e-6).of(0.95)
        assertThat(crop["CropAngle"]).isEqualTo("0.00")
        assertThat(CameraRawGeometry.crop(Recipe(), raw)).containsExactly("HasCrop", "False")
    }

    @Test
    fun `a portrait photo's crop is written in the stored landscape image`() {
        // Orientation 6: the stored image is turned 90 degrees clockwise to be seen, so its bottom edge becomes the
        // left edge as seen: a crop of the left half as seen is the bottom half of the stored image.
        val frame = PhotoFrame(raw = true, orientation = 6, width = 6000, height = 4000)
        val recipe = Recipe(geometry = Geometry(crop = CropRect(0f, 0f, 0.5f, 1f)))
        val crop = CameraRawGeometry.crop(recipe, frame)
        assertThat(crop["CropLeft"]!!.toDouble()).isWithin(1e-6).of(0.0)
        assertThat(crop["CropTop"]!!.toDouble()).isWithin(1e-6).of(0.5)
        assertThat(crop["CropRight"]!!.toDouble()).isWithin(1e-6).of(1.0)
        assertThat(crop["CropBottom"]!!.toDouble()).isWithin(1e-6).of(1.0)
    }

    @Test
    fun `crops with straightening round trip under every orientation`() {
        for (orientation in 1..8) {
            val frame = PhotoFrame(raw = true, orientation = orientation, width = 6000, height = 4000)
            for (angle in listOf(0f, 3.5f, -7.25f)) {
                val recipe = Recipe(geometry = Geometry(crop = CropRect(0.2f, 0.25f, 0.7f, 0.65f), angle = angle))
                val crop = CameraRawGeometry.crop(recipe, frame)
                val back = CameraRawGeometry.applyCrop(Recipe(), crop, frame).geometry
                val rect = back.crop!!
                assertThat(abs(back.angle - angle)).isLessThan(0.01f)
                assertThat(abs(rect.left - 0.2f)).isLessThan(1e-4f)
                assertThat(abs(rect.top - 0.25f)).isLessThan(1e-4f)
                assertThat(abs(rect.right - 0.7f)).isLessThan(1e-4f)
                assertThat(abs(rect.bottom - 0.65f)).isLessThan(1e-4f)
            }
        }
    }

    @Test
    fun `a straightened crop is not written without the photo's size, nor read back`() {
        val noSize = PhotoFrame(raw = true)
        val recipe = Recipe(geometry = Geometry(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f), angle = 2f))
        assertThat(CameraRawGeometry.crop(recipe, noSize)).isEmpty()
        val values = CameraRawGeometry.crop(recipe, raw)
        assertThat(CameraRawGeometry.applyCrop(Recipe(), values, noSize)).isEqualTo(Recipe())
    }

    @Test
    fun `a crop outside the image is refused`() {
        val values = mapOf(
            "HasCrop" to "True",
            "CropLeft" to "-0.3",
            "CropTop" to "0",
            "CropRight" to "0.5",
            "CropBottom" to "1",
        )
        assertThat(CameraRawGeometry.applyCrop(Recipe(), values, raw)).isEqualTo(Recipe())
    }

    @Test
    fun `orientation composes the photo's EXIF orientation with quarter turns and flips`() {
        assertThat(CameraRawGeometry.orientation(Recipe(), raw)).isEqualTo(1)
        assertThat(CameraRawGeometry.orientation(Recipe(geometry = Geometry(rotate = 1)), raw)).isEqualTo(6)
        assertThat(CameraRawGeometry.orientation(Recipe(geometry = Geometry(rotate = 2)), raw)).isEqualTo(3)
        assertThat(CameraRawGeometry.orientation(Recipe(geometry = Geometry(rotate = 3)), raw)).isEqualTo(8)
        assertThat(CameraRawGeometry.orientation(Recipe(geometry = Geometry(flipH = true)), raw)).isEqualTo(2)
        assertThat(CameraRawGeometry.orientation(Recipe(geometry = Geometry(flipV = true)), raw)).isEqualTo(4)
        val portrait = PhotoFrame(raw = true, orientation = 6)
        assertThat(CameraRawGeometry.orientation(Recipe(), portrait)).isEqualTo(6)
        assertThat(CameraRawGeometry.orientation(Recipe(geometry = Geometry(rotate = 1)), portrait)).isEqualTo(3)
        for (exif in 1..8) {
            for (target in 1..8) {
                val frame = PhotoFrame(raw = true, orientation = exif)
                val back = CameraRawGeometry.applyOrientation(Recipe(), target, frame)
                assertThat(CameraRawGeometry.orientation(back, frame)).isEqualTo(target)
            }
        }
    }
}
