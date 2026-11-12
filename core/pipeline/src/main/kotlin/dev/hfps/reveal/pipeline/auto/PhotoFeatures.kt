package dev.hfps.reveal.pipeline.auto

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.WbAnalysis
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.faces.FaceDetector
import dev.hfps.reveal.pipeline.faces.FaceInput

/**
 * What the correction rules know about a photo that does not depend on its recipe (docs/adr/0010): computed once
 * from the proxy while its pixels are on the heap (the editor at open, a batch per photo) and kept (a few MB).
 * Everything the rules then do is a pure function of these and the recipe, so Auto stays idempotent.
 *
 * @property sample Auto's point-sampled analysis copy ([AutoAnalyser.sample]).
 * @property wb the Auto white balance analysis (`null` when it could not be made).
 * @property faces detected faces, best first ([FaceDetector]; empty when none, or when detection was skipped).
 * @property saliency where the subject probably is ([SaliencyMap]).
 */
class PhotoFeatures(
    val sample: PipelineInput,
    val wb: WbAnalysis?,
    val faces: List<Face> = emptyList(),
    val saliency: SaliencyMap? = null,
) {
    companion object {
        /** The features of [input] (the upright linear proxy); [detectFaces] false skips the face detector. */
        fun of(input: PipelineInput, detectFaces: Boolean = true): PhotoFeatures {
            val image = FaceInput.from(input)
            val faces = if (detectFaces) FaceDetector.shipped().detect(image) else emptyList()
            return PhotoFeatures(
                sample = AutoAnalyser.sample(input),
                wb = runCatching { AutoWhiteBalance.analyse(input) }.getOrNull(),
                faces = faces,
                saliency = SaliencyMap.of(image),
            )
        }
    }
}
