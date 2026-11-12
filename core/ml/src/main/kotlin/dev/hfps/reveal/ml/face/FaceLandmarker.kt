@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml.face

import dev.hfps.reveal.ml.LandmarkModelSpec
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.runtime.InferenceEngine
import kotlin.math.exp

/**
 * The landmarks of one face, in image pixels (continuous: pixel i covers i..i+1): MediaPipe's 478-point face mesh
 * with the irises (468-477). [presence] is the model's face probability for the crop it came from.
 */
class FaceLandmarks(val x: FloatArray, val y: FloatArray, val presence: Float) {
    init {
        require(x.size == y.size) { "x and y differ in size" }
    }

    /** The distance between the outer eye corners: the face's scale for soft edges. */
    val eyeSpan: Float
        get() {
            val dx = x[FaceParts.RIGHT_EYE_OUTER] - x[FaceParts.LEFT_EYE_OUTER]
            val dy = y[FaceParts.RIGHT_EYE_OUTER] - y[FaceParts.LEFT_EYE_OUTER]
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
}

/**
 * Face landmarks with MediaPipe Face Landmarker's landmark model (Apache-2.0) run by LiteRT (docs/adr/0021 "M4/C"),
 * without MediaPipe Tasks: the faces come from Reveal's own detector (BlazeFace, ADR 0010), each is cropped
 * ([FaceCrop]) to the model's 256 x 256 RGB input in 0..1, and the landmarks are mapped back to the image. Like
 * MediaPipe's tracking, a second pass re-crops around the first pass's landmarks (level eyes, tight box), which keeps
 * tilted and off-centre faces accurate.
 *
 * Not thread-safe (one engine); [close] releases it.
 */
class FaceLandmarker(private val engine: InferenceEngine, val spec: LandmarkModelSpec = MaskModels.FACE_LANDMARKER) :
    AutoCloseable {
    /** The landmarks of the face in [crop], or null when the model sees no face there. */
    fun landmarks(image: MlImage, crop: FaceCrop): FaceLandmarks? {
        val n = spec.side
        val outputs = engine.runAll(crop.sample(image, n))
        val points = outputs.first { it.size == spec.points * 3 }
        val logit = outputs.first { it.size == 1 }[0]
        val presence = (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
        if (presence < MIN_PRESENCE) return null
        val xs = FloatArray(spec.points)
        val ys = FloatArray(spec.points)
        for (i in 0 until spec.points) {
            val u = points[i * 3]
            val v = points[i * 3 + 1]
            xs[i] = crop.imageX(u, v, n)
            ys[i] = crop.imageY(u, v, n)
        }
        return FaceLandmarks(xs, ys, presence)
    }

    /** Every face of [crops] (one per detected face) refined by a second pass; faces the model rejects are dropped. */
    fun detect(image: MlImage, crops: List<FaceCrop>): List<FaceLandmarks> = crops.mapNotNull { crop ->
        val first = landmarks(image, crop) ?: return@mapNotNull null
        val tracked = FaceCrop.fromLandmarks(first.x, first.y, FaceParts.RIGHT_EYE_OUTER, FaceParts.LEFT_EYE_OUTER)
        landmarks(image, tracked) ?: first
    }

    override fun close() = engine.close()

    companion object {
        /** Below this face probability a crop holds no face (the model's own threshold in MediaPipe is 0.5). */
        const val MIN_PRESENCE = 0.5f
    }
}
