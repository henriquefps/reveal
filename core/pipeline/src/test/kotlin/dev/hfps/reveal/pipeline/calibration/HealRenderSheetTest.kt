package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.faces.FaceDetector
import dev.hfps.reveal.pipeline.faces.FaceFixtures
import dev.hfps.reveal.pipeline.faces.FaceInput
import dev.hfps.reveal.pipeline.heal.HealContext
import dev.hfps.reveal.pipeline.heal.PixelReader
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.heal.SourcePicker
import dev.hfps.reveal.pipeline.input.colourOnly
import dev.hfps.reveal.pipeline.mask.StrokeCodec
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Before/after sheets of healing (docs/adr/0023) on the public-domain fixtures, for the docs and the PR (no private
 * photo): sensor-dust blots added to the landscape's sky and healed by taps with the automatic source; a rock removed
 * with the patch method; red pupils painted at the portrait's detected eyes and fixed. Opt-in:
 * `REVEAL_HEAL_OUT=<dir> ./gradlew --no-configuration-cache :core:pipeline:testDebugUnitTest --tests
 * '*HealRenderSheetTest*' --rerun`.
 */
class HealRenderSheetTest {
    private val out = System.getenv("REVEAL_HEAL_OUT")

    @Test
    fun sheets() {
        assumeTrue("REVEAL_HEAL_OUT not set", out != null)
        val dir = File(checkNotNull(out)).apply { mkdirs() }
        val fills = RemoveFills(executor = Executors.newSingleThreadExecutor())
        dust(dir)
        remove(dir, fills)
        redEye(dir)
    }

    private fun dust(dir: File) {
        var image = FaceFixtures.input("landscape")
        val w = image.width
        val h = image.height
        val blots = listOf(120f to 30f, 260f to 55f, 480f to 40f, 640f to 70f)
        for ((x, y) in blots) image = HealScenes.withDust(image, x, y, 7f, depth = 0.35f)
        val reader = PixelReader.of(image)
        val spots = blots.mapIndexed { i, (x, y) ->
            val spot = HealScenes.spot("d$i", Heal.KIND_HEAL, w, h, x, y, 10f)
            val (dx, dy) = checkNotNull(SourcePicker.pick(spot, reader, image.colourOnly()))
            spot.copy(dx = dx, dy = dy)
        }
        sheet(dir, "healing-dust", image, Recipe(heals = spots), 0, 0, w, 110, HealContext.NONE, scale = 1)
    }

    private fun remove(dir: File, fills: RemoveFills) {
        val image = FaceFixtures.input("landscape")
        val w = image.width
        val h = image.height
        val rock = Heal(
            "rock",
            Heal.KIND_REMOVE,
            radius = 16f / max(w, h),
            stroke = StrokeCodec.encode(
                listOf(
                    StrokePoint(386f / w, 212f / h),
                    StrokePoint(410f / w, 208f / h),
                    StrokePoint(434f / w, 213f / h),
                ),
            ),
            feather = 30f,
        )
        sheet(dir, "healing-remove", image, Recipe(heals = listOf(rock)), 300, 150, 220, 130, HealContext(fills), 2)
    }

    private fun redEye(dir: File) {
        val clean = FaceFixtures.input("studio-trio")
        val w = clean.width
        val h = clean.height
        val face = FaceDetector.shipped().detect(FaceInput.from(clean)).first()
        val ex = face.keypoints[0] * w
        val ey = face.keypoints[1] * h
        val pupil = face.width * w * PUPIL_SHARE
        val rgb = clean.linearRgb.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x + 0.5f - ex
                val dy = y + 0.5f - ey
                if (dx * dx + dy * dy > pupil * pupil) continue
                val i = (y * w + x) * 3
                rgb[i] = 0.55f
                rgb[i + 1] = 0.03f
                rgb[i + 2] = 0.02f
            }
        }
        val image = PipelineInput.linearSrgb(w, h, rgb)
        val spot = HealScenes.spot("eye", Heal.KIND_RED_EYE, w, h, ex, ey, pupil * 2.2f)
        val size = (face.width * w * 0.5f).toInt()
        sheet(
            dir,
            "healing-red-eye",
            image,
            Recipe(heals = listOf(spot)),
            (ex - size / 2).toInt(),
            (ey - size / 3).toInt(),
            size,
            size * 2 / 3,
            HealContext.NONE,
            scale = 4,
        )
    }

    /** Before | after of the box (x, y, cw, ch), each scaled [scale] times (nearest), as a JPEG. */
    @Suppress("LongParameterList")
    private fun sheet(
        dir: File,
        name: String,
        image: PipelineInput,
        recipe: Recipe,
        x: Int,
        y: Int,
        cw: Int,
        ch: Int,
        heals: HealContext,
        scale: Int,
    ) {
        val before = CpuDevelop.render(image, Recipe())
        val after = CpuDevelop.render(image, recipe, heals = heals)
        val gap = 8
        val sheet = BufferedImage(cw * scale * 2 + gap, ch * scale, BufferedImage.TYPE_INT_RGB)
        for ((k, rgba) in listOf(before, after).withIndex()) {
            for (j in 0 until ch * scale) {
                for (i in 0 until cw * scale) {
                    val sx = (x + i / scale).coerceIn(0, image.width - 1)
                    val sy = (y + j / scale).coerceIn(0, image.height - 1)
                    val o = (sy * image.width + sx) * 4
                    val r = rgba[o].toInt() and 0xff
                    val g = rgba[o + 1].toInt() and 0xff
                    val b = rgba[o + 2].toInt() and 0xff
                    sheet.setRGB(k * (cw * scale + gap) + i, j, (r shl 16) or (g shl 8) or b)
                }
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = QUALITY
        }
        ImageIO.createImageOutputStream(File(dir, "$name.jpg")).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(sheet, null, null), params)
        }
        writer.dispose()
    }

    private companion object {
        const val PUPIL_SHARE = 0.035f
        const val QUALITY = 0.88f
    }
}
