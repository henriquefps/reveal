package dev.hfps.reveal.pipeline.faces

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import org.junit.Test

/**
 * The face detector on the public-domain fixtures (`src/test/resources/faces/README.md`): the faces a person counts
 * are found, nothing is found in an empty landscape, the result is deterministic and does not depend on exposure.
 */
class FaceDetectorTest {
    private val detector = FaceDetector.shipped()

    private fun faces(name: String, scale: Float = 1f): List<Face> {
        val input = FaceFixtures.input(name)
        val scaled = if (scale == 1f) {
            input
        } else {
            PipelineInput.linearSrgb(input.width, input.height, FloatArray(input.linearRgb.size) { input.linearRgb[it] * scale })
        }
        return detector.detect(FaceInput.from(scaled))
    }

    @Test
    fun `finds the faces of the fixtures`() {
        assertThat(faces("studio-trio")).hasSize(3)
        assertThat(faces("studio-portrait")).hasSize(1)
        assertThat(faces("cabin-group")).hasSize(7)
        assertThat(faces("outdoor-person")).hasSize(1)
        // 16 small faces at about 2 % of the width: most of them (the tiled pass).
        assertThat(faces("outdoor-group").size).isIn(10..16)
    }

    @Test
    fun `finds nothing in an empty landscape`() {
        assertThat(faces("landscape")).isEmpty()
    }

    @Test
    fun `boxes and keypoints sit on the face`() {
        val face = faces("studio-portrait").single()
        // The portrait's face: upper middle of the frame, about a fifth of the width.
        assertThat(face.centreX).isWithin(0.08f).of(0.43f)
        assertThat(face.centreY).isWithin(0.08f).of(0.37f)
        assertThat(face.width).isWithin(0.06f).of(0.22f)
        assertThat(face.keypoints).hasSize(12)
        // Eyes above the mouth, inside the box.
        val eyeY = (face.keypoints[1] + face.keypoints[3]) / 2
        assertThat(eyeY).isLessThan(face.keypoints[7])
        for (k in 0 until 4) {
            assertThat(face.keypoints[2 * k]).isIn(com.google.common.collect.Range.closed(face.left, face.right))
        }
    }

    @Test
    fun `detection is deterministic`() {
        assertThat(faces("cabin-group")).isEqualTo(faces("cabin-group"))
    }

    @Test
    fun `an under-exposed copy finds the same faces`() {
        val normal = faces("studio-trio")
        val dark = faces("studio-trio", scale = 0.25f)
        assertThat(dark).hasSize(normal.size)
        for ((a, b) in normal.sortedBy { it.centreX }.zip(dark.sortedBy { it.centreX })) {
            assertThat(b.centreX).isWithin(0.02f).of(a.centreX)
            assertThat(b.centreY).isWithin(0.02f).of(a.centreY)
        }
    }

    @Test
    fun `weighted suppression merges overlapping detections`() {
        val a = Face(0.1f, 0.1f, 0.3f, 0.3f, 0.9f, List(12) { 0.2f })
        val b = Face(0.12f, 0.1f, 0.32f, 0.3f, 0.6f, List(12) { 0.22f })
        val c = Face(0.6f, 0.6f, 0.7f, 0.7f, 0.7f, List(12) { 0.65f })
        val out = FaceDetector.suppress(listOf(b, c, a))
        assertThat(out).hasSize(2)
        assertThat(out[0].score).isEqualTo(0.9f)
        // Score-weighted: 0.1 * 0.9 + 0.12 * 0.6 over 1.5.
        assertThat(out[0].left).isWithin(1e-6f).of(0.108f)
        assertThat(out[1]).isEqualTo(c)
    }
}
