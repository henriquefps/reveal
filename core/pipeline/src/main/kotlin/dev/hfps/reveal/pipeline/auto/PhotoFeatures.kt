package dev.hfps.reveal.pipeline.auto

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.WbAnalysis
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.faces.FaceDetector
import dev.hfps.reveal.pipeline.faces.FaceInput
import dev.hfps.reveal.pipeline.geometry.ImageLines
import dev.hfps.reveal.pipeline.optics.LensProfile

/**
 * What the correction rules know about a photo that does not depend on its recipe (docs/adr/0010): computed once
 * from the proxy while its pixels are on the heap (the editor at open, a batch per photo) and kept (a few MB).
 * Everything the rules then do is a pure function of these and the recipe, so Auto stays idempotent.
 *
 * @property sample Auto's point-sampled analysis copy ([AutoAnalyser.sample]).
 * @property wb the Auto white balance analysis (`null` when it could not be made).
 * @property faces detected faces, best first ([FaceDetector]; empty when none, or when detection was skipped).
 * @property saliency where the subject probably is ([SaliencyMap]).
 * @property lines the photo's straight lines (auto-straighten),  when not looked for.
 * @property lens the photo's lens profile (the lines are corrected through it).
 */
class PhotoFeatures(
    val sample: PipelineInput,
    val wb: WbAnalysis?,
    val faces: List<Face> = emptyList(),
    val saliency: SaliencyMap? = null,
    val lines: ImageLines? = null,
    val lens: LensProfile? = null,
) {
    companion object {
        /**
         * The features of [input] (the upright linear proxy); [detectFaces] false skips the face detector; [lines]
         * the photo's lines when already found (the editor's Optics analysis), else they are found here when
         * [findLines].
         */
        fun of(
            input: PipelineInput,
            detectFaces: Boolean = true,
            lines: ImageLines? = null,
            findLines: Boolean = false,
        ): PhotoFeatures {
            val image = FaceInput.from(input)
            val faces = if (detectFaces) FaceDetector.shipped().detect(image) else emptyList()
            return PhotoFeatures(
                sample = AutoAnalyser.sample(input),
                wb = runCatching { AutoWhiteBalance.analyse(input) }.getOrNull(),
                faces = faces,
                saliency = SaliencyMap.of(image),
                lines = lines ?: if (findLines) ImageLines.of(input) else null,
                lens = input.lens,
            )
        }
    }
}
