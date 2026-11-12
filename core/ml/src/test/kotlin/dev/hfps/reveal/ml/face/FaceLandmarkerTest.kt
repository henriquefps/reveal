package dev.hfps.reveal.ml.face

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.InferenceEngine
import org.junit.Test

class FaceLandmarkerTest {
    /** Returns a fixed face in crop pixels (MediaPipe's output layout) and a presence logit. */
    private class FakeEngine(val points: FloatArray, val logit: Float) : InferenceEngine {
        val inputs = ArrayList<FloatArray>()
        var closed = false
        override val backend = Backend.CPU

        override fun runAll(input: FloatArray): List<FloatArray> {
            inputs += input
            return listOf(points, floatArrayOf(logit), floatArrayOf(0f))
        }

        override fun close() {
            closed = true
        }
    }

    private val image = MlImage(320, 240, FloatArray(320 * 240 * 3) { 0.5f })

    /** The synthetic face drawn in a 256 crop (centre 128, scale 40), as the model would output it. */
    private fun cropFace(): FloatArray {
        val f = SyntheticFace.landmarks(cx = 128f, cy = 128f, scale = 40f)
        return FloatArray(478 * 3) { i ->
            if (i % 3 == 0) {
                f.x[i / 3]
            } else if (i % 3 == 1) {
                f.y[i / 3]
            } else {
                0f
            }
        }
    }

    @Test
    fun `landmarks are mapped from the crop back into the image`() {
        val engine = FakeEngine(cropFace(), logit = 4f)
        val crop = FaceCrop(cx = 150f, cy = 100f, side = 128f, angle = 0f)
        val lm = FaceLandmarker(engine).landmarks(image, crop)!!
        assertThat(engine.inputs.single().size).isEqualTo(256 * 256 * 3)
        // Crop pixel 128 + 40 * 1 = 168 (left eye centre) -> image 150 + (168 - 128) / 2.
        val left = FaceParts.LEFT_IRIS[0]
        assertThat(lm.x[left]).isWithin(1e-3f).of(170f)
        assertThat(lm.y[left]).isWithin(1e-3f).of(100f - 10f)
        assertThat(lm.presence).isGreaterThan(0.98f)
    }

    @Test
    fun `a crop without a face is dropped`() {
        val engine = FakeEngine(cropFace(), logit = -3f)
        assertThat(FaceLandmarker(engine).landmarks(image, FaceCrop(100f, 100f, 64f, 0f))).isNull()
        assertThat(FaceLandmarker(engine).detect(image, listOf(FaceCrop(100f, 100f, 64f, 0f)))).isEmpty()
    }

    @Test
    fun `detect runs a second, tracked pass per face and keeps its result`() {
        val engine = FakeEngine(cropFace(), logit = 4f)
        val crops = listOf(FaceCrop(100f, 100f, 128f, 0f), FaceCrop(220f, 120f, 96f, 0.2f))
        val faces = FaceLandmarker(engine).detect(image, crops)
        assertThat(faces).hasSize(2)
        assertThat(engine.inputs).hasSize(4)
        // The tracked crop is centred on the first pass's landmarks: the eye line stays level for an upright crop.
        val f = faces[0]
        assertThat(f.y[FaceParts.RIGHT_EYE_OUTER]).isWithin(1e-2f).of(f.y[FaceParts.LEFT_EYE_OUTER])
    }

    @Test
    fun `the spec matches the landmark model`() {
        assertThat(MaskModels.FACE_LANDMARKER.side).isEqualTo(256)
        assertThat(MaskModels.FACE_LANDMARKER.points).isEqualTo(478)
        val engine = FakeEngine(cropFace(), 4f)
        FaceLandmarker(engine).close()
        assertThat(engine.closed).isTrue()
    }
}
