package dev.hfps.reveal.pipeline.faces

import dev.hfps.reveal.pipeline.PipelineInput
import javax.imageio.ImageIO
import kotlin.math.pow

/** The public-domain face fixtures (`src/test/resources/faces/README.md`) as pipeline inputs (linear sRGB). */
object FaceFixtures {
    val names: List<String> = listOf(
        "studio-trio",
        "studio-portrait",
        "cabin-group",
        "outdoor-group",
        "outdoor-person",
        "landscape",
    )

    private val linear = FloatArray(256) {
        val v = it / 255.0
        (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    fun input(name: String): PipelineInput {
        val image = checkNotNull(FaceFixtures::class.java.getResourceAsStream("/faces/$name.jpg")).use(ImageIO::read)
        val w = image.width
        val h = image.height
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = image.getRGB(x, y)
                val o = (y * w + x) * 3
                rgb[o] = linear[p shr 16 and 0xff]
                rgb[o + 1] = linear[p shr 8 and 0xff]
                rgb[o + 2] = linear[p and 0xff]
            }
        }
        return PipelineInput.linearSrgb(w, h, rgb)
    }
}
