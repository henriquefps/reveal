@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml

import dev.hfps.reveal.ml.inpaint.InpaintModels
import dev.hfps.reveal.ml.tensor.Activation
import dev.hfps.reveal.ml.tensor.InputSpec
import dev.hfps.reveal.ml.tensor.Layout
import dev.hfps.reveal.ml.tensor.Normalisation
import dev.hfps.reveal.ml.tensor.OutputSpec

/**
 * The mask models of the M4 spike (docs/adr/0021): how each is fed and read. The pinned files (URL, SHA-256,
 * licence) are in `core/ml/models.json`; `MaskModelsTest` keeps the two in step. Assets live under `ml/` in the APK.
 */
object MaskModels {
    /** MediaPipe SelfieMulticlass's tensors and classes (both versions). */
    private fun selfieMulticlass(version: Int, sha256: String) = ModelSpec(
        id = ModelId("selfie-multiclass"),
        version = version,
        asset = "selfie-multiclass-v$version.tflite",
        sha256 = sha256,
        input = InputSpec(256, 256, Layout.NHWC, Normalisation.MINUS_ONE_ONE),
        output = OutputSpec(256, 256, 6, Layout.NHWC, Activation.SOFTMAX),
        kinds = mapOf(
            MaskKind.PEOPLE to KindRule(listOf(0), inverted = true, low = SOFT_LOW, high = SOFT_HIGH),
            MaskKind.HAIR to KindRule(listOf(1), low = SOFT_LOW, high = SOFT_HIGH),
            MaskKind.BODY_SKIN to KindRule(listOf(2), low = SOFT_LOW, high = SOFT_HIGH),
            MaskKind.FACE_SKIN to KindRule(listOf(3), low = SOFT_LOW, high = SOFT_HIGH),
            MaskKind.SKIN to KindRule(listOf(2, 3), low = SOFT_LOW, high = SOFT_HIGH),
            MaskKind.CLOTHES to KindRule(listOf(4), low = SOFT_LOW, high = SOFT_HIGH),
        ),
    )

    /**
     * MediaPipe SelfieMulticlass: people and their parts (background, hair, body skin, face skin, clothes, others).
     * Its probabilities are soft (the background tops out near 0.92), so every kind is stretched from 0.1..0.9.
     * Version 2 = the published file (version 1) with float16 convolution weights
     * (`scripts/ml/convert_fp16_weights.py`, docs/adr/0021 "M4/C"): 8.3 MB instead of 16.4, the same masks.
     */
    val SELFIE_MULTICLASS = selfieMulticlass(2, "8d66dc0645fda3ba08106594da0677f8558d3a3955c3248c1511c4190dde0c1d")

    /** The published float32 file of [SELFIE_MULTICLASS] (the lab's comparison). */
    val SELFIE_MULTICLASS_V1 = selfieMulticlass(1, "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0")

    /** MediaPipe SelfieSegmenter (square): one person-vs-background probability, made for selfies. */
    val SELFIE_SEGMENTER = ModelSpec(
        id = ModelId("selfie-segmenter"),
        version = 1,
        asset = "selfie-segmenter-v1.tflite",
        sha256 = "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b",
        input = InputSpec(256, 256, Layout.NHWC, Normalisation.ZERO_ONE),
        output = OutputSpec(256, 256, 1, Layout.NHWC, Activation.NONE),
        kinds = mapOf(MaskKind.PEOPLE to KindRule(listOf(0))),
    )

    /** MediaPipe HairSegmenter (512 px; the fourth input channel is a previous mask, zero for a still photo). */
    val HAIR_SEGMENTER = ModelSpec(
        id = ModelId("hair-segmenter"),
        version = 1,
        asset = "hair-segmenter-v1.tflite",
        sha256 = "2628cf3ce5f695f604cbea2841e00befcaa3624bf80caf3664bef2656d59bf84",
        input = InputSpec(512, 512, Layout.NHWC, Normalisation.ZERO_ONE, extraChannel = true),
        output = OutputSpec(512, 512, 2, Layout.NHWC, Activation.SOFTMAX),
        kinds = mapOf(MaskKind.HAIR to KindRule(listOf(1))),
    )

    /** MediaPipe MagicTouch interactive segmenter: the object under a tapped point. */
    val MAGIC_TOUCH = ModelSpec(
        id = ModelId("magic-touch"),
        version = 1,
        asset = "magic-touch-v1.tflite",
        sha256 = "e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25",
        input = InputSpec(512, 512, Layout.NHWC, Normalisation.ZERO_ONE, extraChannel = true),
        output = OutputSpec(512, 512, 1, Layout.NHWC, Activation.NONE),
        kinds = mapOf(MaskKind.SUBJECT to KindRule(listOf(0))),
        interactive = true,
    )

    /** torchvision LR-ASPP MobileNetV3-Large (PASCAL VOC classes: 0 background, 15 person). */
    val LRASPP_VOC = ModelSpec(
        id = ModelId("lraspp-mnv3-voc"),
        version = 1,
        asset = "lraspp-mnv3-voc-v1.tflite",
        sha256 = "173bc7931ed05de173385ddae155610a9260c5b0e1649719c4738cb31db8b06f",
        input = InputSpec(520, 520, Layout.NCHW, Normalisation.IMAGENET),
        output = OutputSpec(520, 520, 21, Layout.NCHW, Activation.SOFTMAX),
        kinds = mapOf(
            MaskKind.PEOPLE to KindRule(listOf(15)),
            MaskKind.SUBJECT to KindRule(listOf(0), inverted = true),
        ),
    )

    /**
     * DeepLab-v3 MobileNetV2 on ADE20K (150 classes + "other"; 3 = sky), as published: kept for the lab's comparison.
     * Its SPACE_TO_BATCH_ND ops keep most of it off the GPU; the app uses [DEEPLAB_ADE20K_SKY].
     */
    val DEEPLAB_ADE20K = ModelSpec(
        id = ModelId("deeplab-v3-ade20k"),
        version = 1,
        asset = "deeplab-v3-ade20k-v1.tflite",
        sha256 = "0acdf37d5db38e681646ce79557c2af516650ecca5377b15c9cb171f751556cb",
        input = InputSpec(513, 513, Layout.NHWC, Normalisation.MINUS_ONE_ONE),
        output = OutputSpec(513, 513, 151, Layout.NHWC, Activation.SOFTMAX),
        kinds = mapOf(MaskKind.SKY to KindRule(listOf(ADE20K_SKY))),
    )

    /**
     * The sky model the app uses: [DEEPLAB_ADE20K] converted by `scripts/ml/convert_deeplab_sky.py` (docs/adr/0021
     * "M4/C"): dilated convolutions instead of SPACE_TO_BATCH_ND / BATCH_TO_SPACE_ND (the whole graph on the GPU), and
     * one output channel, the sky logit against the log-sum-exp of the 150 other classes (its sigmoid is the sky's
     * softmax probability), instead of 151 logits.
     */
    val DEEPLAB_ADE20K_SKY = ModelSpec(
        id = ModelId("deeplab-v3-ade20k-sky"),
        version = 1,
        asset = "deeplab-v3-ade20k-sky-v1.tflite",
        sha256 = "f57fbaa14e106b5750f58f3b07b7ba390a42df58b0dfe671fddf15c1ec00b013",
        input = InputSpec(513, 513, Layout.NHWC, Normalisation.MINUS_ONE_ONE),
        output = OutputSpec(513, 513, 1, Layout.NHWC, Activation.SIGMOID),
        kinds = mapOf(MaskKind.SKY to KindRule(listOf(0))),
    )

    /**
     * MediaPipe Face Landmarker's landmark model (478 points: face mesh + irises), extracted from the pinned `.task`
     * bundle at build time. Eyes, irises, lips, teeth and brows are polygons over its points
     * ([dev.hfps.reveal.ml.face.FaceParts]).
     */
    val FACE_LANDMARKER = LandmarkModelSpec(
        id = ModelId("face-landmarker"),
        version = 1,
        asset = "face-landmarker-v1.tflite",
        sha256 = "c7d54204ce0448474c7f3fa9af494787c0965cbdd6f20fc72867e43046bd43d5",
        side = 256,
        points = 478,
    )

    /**
     * DeepLab-v3 Xception-65 on ADE20K: the larger, better sky (output at 1/4 resolution). Not bundled: 42 MB, and its
     * GPU result drifts from the CPU's (docs/adr/0021); benchmarked from a pushed file.
     */
    val DEEPLAB_ADE20K_XCEPTION = ModelSpec(
        id = ModelId("deeplab-v3-ade20k-xception"),
        version = 1,
        asset = "deeplab-v3-ade20k-xception-v1.tflite",
        sha256 = "f3f7ce57f86e382edd265b6158cd95ca68c5d70adc82549618b2ff0ac9ecd2ec",
        input = InputSpec(513, 513, Layout.NHWC, Normalisation.MINUS_ONE_ONE),
        output = OutputSpec(129, 129, 151, Layout.NHWC, Activation.SOFTMAX),
        kinds = mapOf(MaskKind.SKY to KindRule(listOf(ADE20K_SKY))),
    )

    /** MODNet portrait matting: a soft alpha of the portrait's person. */
    val MODNET = ModelSpec(
        id = ModelId("modnet"),
        version = 1,
        asset = "modnet-v1.tflite",
        sha256 = "f7d1e9eca9562abddb60e4b0a3ed0c29441ef09cd621954978f1613914cc64bc",
        input = InputSpec(512, 512, Layout.NCHW, Normalisation.MINUS_ONE_ONE),
        output = OutputSpec(512, 512, 1, Layout.NCHW, Activation.NONE),
        kinds = mapOf(MaskKind.PEOPLE to KindRule(listOf(0))),
    )

    /** DIS IS-Net general use (1024 px, 176 MB fp32): not bundled; benchmarked from a pushed file. */
    val ISNET = ModelSpec(
        id = ModelId("isnet-general"),
        version = 1,
        asset = "isnet-general-v1.tflite",
        sha256 = "0c3c93b6a2a65e7c69137ec82596944e6bfb97d982c75bab03acb0738dbaa087",
        input = InputSpec(1024, 1024, Layout.NCHW, Normalisation.CENTRED),
        output = OutputSpec(1024, 1024, 1, Layout.NCHW, Activation.NONE),
        kinds = mapOf(MaskKind.SUBJECT to KindRule(listOf(0))),
    )

    private const val ADE20K_SKY = 3
    private const val SOFT_LOW = 0.1f
    private const val SOFT_HIGH = 0.9f

    /**
     * The segmentation models every build carries (release included): what [dev.hfps.reveal.ml.service.MaskService]
     * uses.
     */
    val bundled: List<ModelSpec> = listOf(SELFIE_MULTICLASS, MAGIC_TOUCH, DEEPLAB_ADE20K_SKY)

    /** The models only debug builds carry, for the mask lab's comparisons. */
    val lab: List<ModelSpec> =
        listOf(SELFIE_MULTICLASS_V1, LRASPP_VOC, DEEPLAB_ADE20K, HAIR_SEGMENTER, SELFIE_SEGMENTER, MODNET)

    /** Every segmentation model with a spec (bundled, lab or neither). */
    val all: List<ModelSpec> = bundled + lab + DEEPLAB_ADE20K_XCEPTION + ISNET

    /** Every pinned file with a Kotlin spec, segmentation or not (kept in step with `models.json` by a test). */
    val pinned: List<PinnedModel> = all + FACE_LANDMARKER + InpaintModels.bundled

    /** The asset path of any pinned model. */
    fun assetPath(model: PinnedModel): String = "ml/${model.asset}"
}
