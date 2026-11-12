package dev.hfps.reveal.pipeline.faces

import java.util.Locale
import org.junit.Test

class FaceDetectorTest {
    @Test
    fun `prints faces`() {
        for (name in FaceFixtures.names) {
            val input = FaceFixtures.input(name)
            val t0 = System.nanoTime()
            val image = FaceInput.from(input)
            val t1 = System.nanoTime()
            val faces = FaceDetector.shipped().detect(image)
            val t2 = System.nanoTime()
            println(
                String.format(Locale.ROOT, "FACES %s %dx%d input %d ms detect %d ms: %d %s", name, image.width,
                    image.height, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, faces.size,
                    faces.joinToString { String.format(Locale.ROOT, "(%.2f %.3f,%.3f %.3f)", it.score, it.centreX, it.centreY, it.width) }),
            )
        }
    }
}
