@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml.service

import dev.hfps.reveal.ml.MaskPrompt
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.face.FaceCrop
import dev.hfps.reveal.ml.tensor.Resample
import dev.hfps.reveal.pipeline.auto.SaliencyMap
import dev.hfps.reveal.pipeline.faces.DetectionImage
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.faces.FaceDetector
import kotlin.math.max

/**
 * A detected face in normalised image coordinates (0..1, origin top-left): the detector's box and the two eye
 * keypoints (the subject's right eye is on the image's left). Reveal's BlazeFace (ADR 0010) gives them; the editor
 * already has them in its `PhotoFeatures`, so a caller can pass them in ([MaskRequest.cues]).
 */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rightEyeX: Float,
    val rightEyeY: Float,
    val leftEyeX: Float,
    val leftEyeY: Float,
) {
    val centreX: Float get() = (left + right) / 2
    val centreY: Float get() = (top + bottom) / 2
    val height: Float get() = bottom - top

    /** The landmark model's crop of this face on a [width] x [height] image. */
    fun crop(width: Int, height: Int): FaceCrop = FaceCrop.fromDetection(
        left * width,
        top * height,
        right * width,
        bottom * height,
        rightEyeX * width,
        rightEyeY * height,
        leftEyeX * width,
        leftEyeY * height,
    )

    companion object {
        /** From the pipeline's detector output (keypoints: right eye, left eye, ...). */
        fun of(face: Face): FaceBox {
            val k = face.keypoints
            val hasEyes = k.size >= 4
            return FaceBox(
                face.left,
                face.top,
                face.right,
                face.bottom,
                if (hasEyes) k[0] else face.left + face.width * 0.3f,
                if (hasEyes) k[1] else face.centreY,
                if (hasEyes) k[2] else face.right - face.width * 0.3f,
                if (hasEyes) k[3] else face.centreY,
            )
        }
    }
}

/**
 * What the masks need to know about a photo besides its pixels (docs/adr/0021 "Subject"): its faces and its saliency
 * map (ADR 0010), from the model input. The editor computes both at open (`PhotoFeatures`) and may pass them;
 * otherwise [PhotoCues.of] finds them (BlazeFace + saliency on a 576 px copy, ~0.1-0.3 s on a phone's CPU).
 */
class PhotoCues(val faces: List<FaceBox>, val saliency: SaliencyMap?) {
    /**
     * The automatic seeds of the subject (MagicTouch, docs/adr/0021 "Subject"): each face's centre and a point
     * [BODY_OFFSET] face heights below it (the body), when there are faces; else the [PEAKS] strongest saliency peaks,
     * each suppressing a [PEAK_SUPPRESSION] radius around it.
     */
    @Suppress("ReturnCount")
    fun seeds(): List<MaskPrompt> {
        if (faces.isNotEmpty()) {
            return faces.take(MAX_FACES).flatMap { f ->
                listOf(MaskPrompt(f.centreX, f.centreY), MaskPrompt(f.centreX, f.centreY + BODY_OFFSET * f.height))
            }.filter { it.x in 0f..1f && it.y in 0f..1f }
        }
        val map = saliency ?: return listOf(MaskPrompt(0.5f, 0.5f))
        val v = map.values.copyOf()
        val peaks = ArrayList<MaskPrompt>()
        repeat(PEAKS) {
            val i = v.indices.maxBy { v[it] }
            if (v[i] <= 0f) return@repeat
            val x = (i % map.width + 0.5f) / map.width
            val y = (i / map.width + 0.5f) / map.height
            peaks += MaskPrompt(x, y)
            for (j in v.indices) {
                val dx = (j % map.width + 0.5f) / map.width - x
                val dy = (j / map.width + 0.5f) / map.height - y
                if (dx * dx + dy * dy < PEAK_SUPPRESSION * PEAK_SUPPRESSION) v[j] = 0f
            }
        }
        return peaks.ifEmpty { listOf(MaskPrompt(0.5f, 0.5f)) }
    }

    companion object {
        const val PEAKS = 3
        const val PEAK_SUPPRESSION = 0.12f
        const val MAX_FACES = 8
        const val BODY_OFFSET = 2f

        /** The side the face detector and the saliency map work at (ADR 0010's `FaceInput`). */
        const val SIDE = 576

        /** Faces and saliency of [image] (the model input), with Reveal's detectors. */
        fun of(image: MlImage): PhotoCues {
            val long = max(image.width, image.height)
            val small = if (long <= SIDE) {
                image
            } else {
                val w = max(1, image.width * SIDE / long)
                val h = max(1, image.height * SIDE / long)
                MlImage(w, h, Resample.interleaved(image.rgb, image.width, image.height, 3, w, h))
            }
            val detection = DetectionImage(small.width, small.height, small.rgb)
            val faces = FaceDetector.shipped().detect(detection).take(MAX_FACES).map(FaceBox::of)
            return PhotoCues(faces, SaliencyMap.of(detection))
        }
    }
}
