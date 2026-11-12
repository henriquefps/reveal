@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.auto

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.Luma
import dev.hfps.reveal.pipeline.cpu.ProfileLooks
import dev.hfps.reveal.pipeline.cpu.ProfileOp
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.faces.DetectionImage
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.faces.FaceFixtures
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.exp
import kotlin.math.pow
import org.junit.Test

/**
 * The M3 analysis (docs/adr/0010, "Faces", "Subject"): face rules on fixtures of analysis values, the face and
 * subject histograms, the white balance's face rules, the saliency map, and Auto end to end on the public-domain
 * portraits (idempotent with features).
 */
class AutoFacesTest {
    private val table = DoubleArray(AutoAnalysis.BINS) { i ->
        val grey = 2.0.pow(AutoAnalysis.centre(i)).toFloat()
        val g = floatArrayOf(grey, grey, grey)
        ProfileOp.apply(g, ProfileLooks.resolve(Profile()))
        Luma.of(g[0], g[1], g[2]).toDouble()
    }

    private fun bump(centre: Double, sigma: Double = 0.4): DoubleArray {
        val h = DoubleArray(AutoAnalysis.BINS) { i ->
            val s = AutoAnalysis.centre(i)
            exp(-(s - centre) * (s - centre) / (2 * sigma * sigma))
        }
        val sum = h.sum()
        return DoubleArray(h.size) { h[it] / sum }
    }

    /** A scene keyed at [key] stops with a face whose skin sits at [face] stops covering [area]. */
    private fun scene(key: Double, face: Double?, area: Double = 0.02): AutoAnalysis {
        val h = bump(key, 1.5)
        return AutoAnalysis(
            h, h.copyOf(), table, 0.0, 0.1, true,
            faceHistogram = face?.let { bump(it) },
            faceArea = if (face == null) 0.0 else area,
            faceCount = if (face == null) 0 else 1,
        )
    }

    private val p = AutoParams()

    @Test
    fun `a face inside its band leaves the scene exposure alone`() {
        val plain = scene(-4.5, null)
        val ev = AutoRules.exposure(plain, p)
        // The face lands exactly on its target with the scene's exposure.
        val face = plain.sceneFor(p.faceTarget) - ev
        assertThat(AutoRules.exposure(scene(-4.5, face), p)).isEqualTo(ev)
        assertThat(AutoRules.exposure(scene(-4.5, face + p.faceTolerance * 0.9), p)).isEqualTo(ev)
    }

    @Test
    fun `a dark face is brought up to its band, a bright one down to it`() {
        val plain = scene(-4.5, null)
        val ev = AutoRules.exposure(plain, p)
        val target = plain.sceneFor(p.faceTarget)
        val dark = target - ev - 1.5 // backlit: 1.5 stops under the target with the scene's exposure
        val evDark = AutoRules.exposure(scene(-4.5, dark), p)
        assertThat(evDark).isGreaterThan(ev)
        // Full weight: the face ends on the band's lower edge.
        assertThat(dark + evDark).isWithin(0.011).of(target - p.faceTolerance)
        val bright = target - ev + 1.5
        val evBright = AutoRules.exposure(scene(-4.5, bright), p)
        assertThat(bright + evBright).isWithin(0.011).of(target + p.faceTolerance)
    }

    @Test
    fun `the face move is limited and smaller faces count less`() {
        val plain = scene(-4.5, null)
        val ev = AutoRules.exposure(plain, p)
        val target = plain.sceneFor(p.faceTarget)
        val veryDark = target - ev - 6.0
        assertThat(AutoRules.exposure(scene(-4.5, veryDark), p) - ev).isWithin(0.011).of(p.maxFaceShift)
        val tiny = AutoRules.faceWeight(scene(-4.5, -6.0, area = p.faceAreaFull / 4), p)
        assertThat(tiny).isWithin(1e-9).of(p.faceWeight * 0.5)
        assertThat(AutoRules.faceWeight(scene(-4.5, null), p)).isEqualTo(0.0)
    }

    @Test
    fun `highlight protection never pushes a face below its band`() {
        // A face in the band, a window far over the ceiling: protection would lower the exposure by up to 1 EV.
        val h = DoubleArray(AutoAnalysis.BINS).also {
            val a = bump(-6.0, 1.0)
            val b = bump(1.0, 0.3)
            for (i in it.indices) it[i] = 0.85 * a[i] + 0.15 * b[i]
        }
        val noFace = AutoAnalysis(h, h.copyOf(), table, 0.0, 0.1, true)
        val ev0 = AutoRules.exposure(noFace, p)
        val target = noFace.sceneFor(p.faceTarget)
        // Inside the band at the protected exposure, so protection is what would push it lower.
        val face = target - p.faceTolerance - ev0 + 0.2
        val withFace = AutoAnalysis(h, h.copyOf(), table, 0.0, 0.1, true, faceHistogram = bump(face), faceArea = 0.05, faceCount = 1)
        val ev = AutoRules.exposure(withFace, p)
        assertThat(face + ev).isAtLeast(target - p.faceTolerance - 0.011)
    }

    @Test
    fun `the analysis measures the faces' skin and ignores faces outside the crop`() {
        val w = 120
        val h = 80
        val rgb = FloatArray(w * h * 3) { 0.02f }
        // A bright "face" patch on the left.
        for (y in 20 until 50) for (x in 15 until 40) for (c in 0..2) rgb[(y * w + x) * 3 + c] = 0.4f
        val input = PipelineInput.linearSrgb(w, h, rgb)
        val face = Face(15f / w, 20f / h, 40f / w, 50f / h, 0.9f)
        val a = AutoAnalyser.analyse(input, Recipe(), listOf(face))
        assertThat(a.faceCount).isEqualTo(1)
        assertThat(checkNotNull(a.faceKey)).isWithin(0.1).of(kotlin.math.ln(0.4) / kotlin.math.ln(2.0))
        assertThat(a.faceArea).isWithin(1e-6).of(face.width.toDouble() * face.height)
        val cropped = AutoAnalyser.analyse(input, Recipe(geometry = Geometry(crop = CropRect(0.5f, 0f, 1f, 1f))), listOf(face))
        assertThat(cropped.faceKey).isNull()
        assertThat(cropped.faceCount).isEqualTo(0)
    }

    @Test
    fun `the subject map weights the key`() {
        val w = 120
        val h = 80
        val rgb = FloatArray(w * h * 3) { i -> if ((i / 3) % w >= w / 2) 0.3f else 0.01f }
        val input = PipelineInput.linearSrgb(w, h, rgb)
        val right = SaliencyMap(2, 1, floatArrayOf(0f, 1f))
        val left = SaliencyMap(2, 1, floatArrayOf(1f, 0f))
        val r = AutoAnalyser.analyse(input, Recipe(), saliency = right)
        val l = AutoAnalyser.analyse(input, Recipe(), saliency = left)
        assertThat(r.percentile(0.5, r.subjectHistogram)).isGreaterThan(l.percentile(0.5, l.subjectHistogram))
        assertThat(AutoRules.exposure(r, p)).isLessThan(AutoRules.exposure(l, p))
    }

    @Test
    fun `saliency finds a distinct object off centre`() {
        val w = 192
        val h = 128
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            val inObject = x in 130..170 && y in 40..80
            rgb[o] = if (inObject) 0.85f else 0.45f
            rgb[o + 1] = if (inObject) 0.2f else 0.5f
            rgb[o + 2] = if (inObject) 0.15f else 0.45f
        }
        val map = SaliencyMap.of(DetectionImage(w, h, rgb))
        assertThat(map.at(150f / w, 60f / h)).isGreaterThan(2 * map.at(30f / w, 100f / h))
        assertThat(SaliencyMap.of(DetectionImage(w, h, rgb))).isNotNull()
    }

    @Test
    fun `face blocks stay out of the white balance and the skin guard keeps skin natural`() {
        // A neutral grey room under a 3200 K light, a "face" of skin colour filling a third of the frame.
        val w = 96
        val h = 64
        val light = WhiteBalanceModel.illuminantXyz(3200.0, 0.0)
        val cam = dev.hfps.reveal.pipeline.cpu.ColorSpaces.SRGB.fromXyz
        val grey = cam.times(light.map { it * 0.3 }.toDoubleArray())
        val skin = cam.times(doubleArrayOf(light[0] * 0.42, light[1] * 0.33, light[2] * 0.2))
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = if (x in 30 until 66 && y in 10 until 54) skin else grey
            for (k in 0..2) rgb[(y * w + x) * 3 + k] = c[k].toFloat()
        }
        val input = PipelineInput.linearSrgb(w, h, rgb)
        val face = Face(30f / w, 10f / h, 66f / w, 54f / h, 0.95f)
        val wb = AutoWhiteBalance.analyse(input)
        val withFaces = AutoWhiteBalance.estimate(wb, faces = listOf(face))
        // The grey room decides the light: close to 3200 K.
        assertThat(withFaces.temperatureK).isWithin(250.0).of(3200.0)
        val hue = checkNotNull(AutoWhiteBalance.faceSkinHue(wb, listOf(face), withFaces))
        assertThat(hue).isIn(com.google.common.collect.Range.closed(22.0, 52.0))
    }

    @Test
    fun `auto with the photo's features is deterministic and idempotent`() {
        val input = FaceFixtures.input("cabin-group")
        val features = PhotoFeatures.of(input)
        assertThat(features.faces).isNotEmpty()
        val once = AutoRules.run(features, Recipe(), AutoScope.ALL)
        val twice = AutoRules.run(features, once, AutoScope.ALL)
        assertThat(twice).isEqualTo(once)
        assertThat(AutoRules.run(PhotoFeatures.of(input), Recipe(), AutoScope.ALL)).isEqualTo(once)
    }
}
