# ADR 0021: On-device mask models

Status: accepted (M4/S spike), amended by M4/C (below). Date: 2026-11-12. Precedes M4 "Masks" (`docs/plan/milestones.md`); builds on
`docs/plan/architecture.md` "On-device ML", ADR 0010 / 0020 (faces and the saliency map, BlazeFace in pure Kotlin) and
ADR 0002 (pipeline order). Platform-neutral where it can be: the masks are model outputs plus documented maths.

## Context

M4 needs AI masks: **subject** (the main subject, any kind), **background** (its inverse), **sky**, **people** (all
persons), and **people parts** (skin/face, hair, eyes, lips, clothes) if good enough. The owner's constraints:
everything offline and on the device, models bundled in the APK at build time (pinned checksums, never a runtime
download), no Play-services-only path unless every bundled option is clearly worse, and a licence that allows
redistribution inside a paid AGPL-3.0-or-later app (weights and training-data terms; non-commercial is rejected).

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Runtime | **LiteRT 2.3.2** (`com.google.ai.edge.litert:litert` + `litert-gpu`, Apache-2.0), CompiledModel API, GPU (OpenCL on Adreno) with the CPU registered too (ops the GPU lacks run on XNNPACK), CPU-only fallback. No NNAPI/QNN, no ONNX Runtime. |
| People + parts | **MediaPipe Selfie Multiclass 256** (Apache-2.0): people, hair, face skin, body skin, skin, clothes. |
| Sky | **DeepLab-v3 MobileNetV2 ADE20K** (Apache-2.0 weights; ADE20K terms accepted by the owner), converted for the GPU at build time (M4/C: `deeplab-v3-ade20k-sky@1`). |
| Subject | **MediaPipe MagicTouch** (Apache-2.0), seeded automatically (faces, else saliency peaks), and by taps. |
| Background | `1 - subject` (or `1 - people`); no model. |
| Eyes, lips | Not from a segmenter (every bundled option lacks them; BiSeNet/CelebAMask-HQ is non-commercial). Drawn from face landmarks (M4/C): MediaPipe Face Landmarker's landmark model (Apache-2.0) run by LiteRT on Reveal's BlazeFace crops; eyes, irises, lips, teeth, eyebrows as soft polygons. |
| Edge refinement | Colour guided filter, fast form: coefficients on a ~512 px grid (M4/C: size-independent, per-kind radius/eps), all passes on the GPU (M4/C) with the CPU reference kept; one GPU pass at full resolution (8.6 ms at 24 MP on the S24). |
| APK budget | ≈ 34 MB per arm64 install. M4/C, release: runtime 9.4 MB + models 19.6 MB = **29.0 MB** (Selfie Multiclass stored with float16 weights). |
| Recipe | A mask stores the model reference `<id>@<version>`, the kind, prompts, refinement parameters; never pixels. Cache keyed by photo fingerprint + model id@version + parameters. |
| Delivery | `core/ml/models.json` (URL or a derivation from a pinned file, SHA-256, size, licence) + `scripts/ml/fetch_models.py` (stdlib, cached by checksum, `--check`), run by Gradle into `:core:ml`'s generated assets; a release set and a debug (lab) set (M4/C). |

## Candidates

Licences: **weights** licence / **training data** terms. Size is the file the build fetches (fp32 unless noted).
Speed on the owner's Galaxy S24 Ultra (Adreno 750, debug build, `MaskBenchmark`, warm median of 10 runs on a
768 x 512 photo): model inference, then with Reveal's pre/post-processing; cold = compile + first run. Quality on COCO
val2017 panoptic (CC BY 4.0 annotations; 80 images per set with permissive image licences, host runs, IoU at 0.5 and
boundary F at 0.75 % of the diagonal; images never committed). "Subject" on COCO is a proxy: the dominant object
(largest "thing", ≥ 15 % of the frame, ≥ 4x the next one).

| Model | Licence (weights / data) | Size | S24 GPU ms (warm / +pre-post / cold) | S24 CPU ms | PSS + | COCO (IoU / p10 / BF) | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Selfie Multiclass 256** (MediaPipe) | Apache-2.0 / Google's own data (model card) | 16.4 MB | 9.1 / 36 / 182 | 290 | 140 MB | people 0.70 / 0.36 / 0.69 | people + hair, face/body skin, clothes, accessories; probabilities soft (background ≤ 0.92: stretched 0.1..0.9) |
| Selfie Segmenter (MediaPipe) | Apache-2.0 / Google | 0.25 MB (fp16) | 2.4 / 17 / 71 | 8.6 | 29 MB | people 0.58 / 0.08 / 0.58 | selfies only; misses small people |
| Hair Segmenter (MediaPipe) | Apache-2.0 / Google | 0.8 MB | 4.9 / 48 / 171 | 33 | 59 MB | — | 512 px hair; close to Multiclass's hair on the review sheets |
| **MagicTouch** interactive (MediaPipe) | Apache-2.0 / Google | 6.2 MB | 8.7 / 41 / 134 | 195 | 99 MB | subject, with a tap: 0.72 / 0.41 / 0.58; auto-seeded (saliency, top 3): 0.64 / 0.13 | the object under a point; any kind |
| DeepLab-v3 VOC (MediaPipe) | Apache-2.0 / PASCAL VOC (Flickr images) | 2.8 MB | (not on device) | — | — | people 0.58; subject 0.50 | 21 VOC classes only |
| LR-ASPP MobileNetV3 VOC (torchvision, LiteRT export) | BSD-3-Clause / COCO subset (CC BY 4.0 annotations, Flickr images) | 12.9 MB (3.5 MB int8 weights exists) | 25.6 / 173 / 280 | 197 | 199 MB | people **0.75** / 0.51 / 0.64; subject 0.51 | trained on COCO train: the COCO score flatters it |
| **DeepLab-v3 MNV2 ADE20K** (TF model zoo) | Apache-2.0 / ADE20K (research terms, see below) | 2.4 MB | 374 / 1130 / 1632 | 438 | 636 MB | sky **0.87** / 0.77 / 0.79 | GPU rejects SPACE_TO_BATCH_ND: 91 of 135 ops on the CPU; 151-class output decoded on the CPU |
| DeepLab-v3 Xception-65 ADE20K | Apache-2.0 / ADE20K | 41.7 MB | 2711 / 2785 / 3959 | 2717 | 652 MB | sky 0.92 / 0.78 / 0.84 | GPU result drifts from the CPU's (mean 0.14): CPU only, too slow |
| MODNet (portrait matting) | Apache-2.0 (relicensed from CC BY-NC-SA; verify upstream) / own + matting data | 26.2 MB | 34 / 58 / 270 | 1212 | 83 MB | people 0.50 / 0.09 / 0.53 | one portrait; fails on scenes |
| IS-Net general (DIS) | Apache-2.0 / DIS5K (academic terms) | 176 MB | 131 / 219 / 2740 | 9335 | 1120 MB | subject 0.43 / 0.22 / 0.29 | too large, poor on the proxy |
| U²-Net-p | Apache-2.0 / DUTS-TR (no stated licence) | 4.6 MB | (host only) | — | — | subject 0.64 / 0.03 / 0.54 | no LiteRT file upstream: needs a PyTorch conversion in the build; brittle (p10 0.03) |
| BiRefNet-lite (ONNX) | MIT / DIS5K, HRSOD… | 224 MB | (host only, 8 s CPU) | — | — | subject 0.67 / 0.01 / 0.59 | quality reference; far over budget |
| UniSal saliency | Apache-2.0 | 6.5 MB | — | — | — | — | a heatmap, not a mask; Reveal's own saliency map (ADR 0010) serves the seeds |
| ML Kit Subject Segmentation | Google ML Kit terms; Play services only | (downloaded by Play services) | — | — | — | — | reference only: unbundled, needs Play services and a runtime download; not measured |
| SegFormer (ADE20K sky, NVIDIA) | NVIDIA Source Code License (non-commercial) | — | — | — | — | — | **rejected** (licence) |
| BiSeNet face parsing (CelebAMask-HQ) | MIT / CelebAMask-HQ non-commercial | 52.6 MB | — | — | — | — | **rejected** (data terms): eyes/lips/brows would have come from it |
| RMBG-1.4 / 2.0 (BRIA), EdgeSAM | CC BY-NC / S-Lab (non-commercial) | — | — | — | — | — | **rejected** (licence) |
| PIDNet Cityscapes (sky class) | MIT / Cityscapes non-commercial | 30.6 MB | — | — | — | — | **rejected** (data terms; street scenes only) |
| SAM 2.1 tiny, CLIPSeg | Apache-2.0 | 97 MB, 270 MB | — | — | — | — | over the budget; SAM-class models are the candidates if M4 wants box/scribble prompts |

PSS + = the process's PSS after load and 11 runs minus before (includes the decode buffers). On the review sheets
(owner's two private shoots, 31 camera JPEGs, aggregates only; the sheets are outside the repo) the people models
agree closely on these portraits (mean people area 0.46 Multiclass, 0.44 LR-ASPP, 0.45 Selfie, 0.45 MODNet);
Multiclass's hair and face-skin follow the hairline and face well at 256 px; parts are coarse on small faces.

**Training-data terms (owner decision flagged, not blocking).** The recommended MediaPipe models are trained on
Google's own data under Apache-2.0 model cards: clean. The sky model's weights are Apache-2.0 (TensorFlow model
zoo), but ADE20K's images come with research-oriented terms; models trained on it are widely redistributed under the
weights' licence, and Reveal ships no ADE20K images. No permissively licensed sky model with clean data was found;
the fallback, if the owner prefers to avoid ADE20K entirely, is a sky mask built from MagicTouch seeded in the top
band plus a colour/position prior (worse on branches). LR-ASPP (not recommended) has the same caveat with COCO's
Flickr images.

## Recommendation per mask kind

- **People** — Selfie Multiclass: one 16.4 MB model gives people and parts, 9 ms on the GPU, works on several
  people and full bodies. LR-ASPP scores higher on COCO (it is trained on COCO) and costs 12.9 MB more; Selfie
  Segmenter and MODNet fail on scenes. Revisit LR-ASPP's int8 file (3.5 MB) only if M4's review finds Multiclass
  missing people in crowds.
- **People parts** — Selfie Multiclass: hair, face skin, body skin, skin (face + body), clothes. Eyes, lips, teeth,
  brows: face landmarks (M4 evaluation), never CelebAMask-HQ models.
- **Sky** — DeepLab-v3 MobileNetV2 ADE20K, 2.4 MB, IoU 0.87 (worst decile 0.77). Its cost on the S24 (≈ 1.1 s
  end to end, once per photo, cached) is acceptable; M4's conversion step should rewrite the SPACE_TO_BATCH /
  BATCH_TO_SPACE pairs as dilated convolutions (full GPU) and trim the output to the sky logit and a log-sum-exp (no
  151-class readback), which should bring it under 50 ms.
- **Subject** — MagicTouch: with a tap it is the best of everything measured (IoU 0.72, 9 ms). Automatic subject =
  MagicTouch at seeds united: each face (its centre and the body below it, BlazeFace from ADR 0010) when there are
  faces, else the three strongest peaks of the saliency map. Auto-seeded it equals U²-Net-p on the COCO proxy (0.64)
  without another model; the user adds or subtracts with taps (M4's add/subtract/intersect).
- **Background** — the inverse of the subject (or of the people).

## Runtime

LiteRT's CompiledModel API with `Accelerator.GPU` + `Accelerator.CPU`: the GPU delegate takes every op it supports
(all ops of the three recommended MediaPipe models), the rest runs on XNNPACK in the same graph; when the GPU is
unavailable the model compiles for the CPU only. GPU and CPU agree within 0.0001-0.0032 mean absolute mask
difference (`MaskModelsDeviceTest.gpuMatchesCpu`), except Xception (rejected). The native size is 9.4 MB arm64
(`libLiteRt.so` 5.6, `libLiteRtClGlAccelerator.so` 3.3, `liblitert_jni.so` 0.5); Play delivers per ABI.

Rejected: Reveal's own pure-Kotlin graph runner (ADR 0020's BlazeFace path) — these networks are 10-100x the work of
BlazeFace and XNNPACK on the CPU is already 20-30x slower than the GPU (MagicTouch 195 vs 8.7 ms); NNAPI is
deprecated; the Qualcomm NPU needs vendor libraries per SoC; ONNX Runtime Mobile adds ~10 MB for no model we need.

## Model input

The models see the photo's **display image independent of the recipe**: the upright proxy rendered with the default
recipe (as-shot white balance, Standard look, no geometry; like ADR 0010's `FaceInput`), at ≤ 1024 px. So a mask does
not change when the user moves a slider, and it is cacheable per photo. Masks live in image coordinates (upright,
before crop/transform) and reach the output through `GeometryMapping` like every other operator. The debug lab
instead runs on the current edit's render (what the screen shows), to judge models on real photos.

## Edge refinement

The fast colour guided filter (He, Sun, Tang 2013; He and Sun 2015), `refine/GuidedRefiner` (CPU reference) and
`refine/GuidedFilterShader` (GLES 3.0): the model's mask and the guide are downscaled to 1/4, per window the linear
model `q = a · I + b` is solved (3 x 3 colour covariance + eps), the coefficients averaged, then one full-resolution
pass applies them with explicit fp32 bilinear weights (`texelFetch`, no hardware filtering: the GPU matches the CPU
within 0.002 at 8 bits). S24: 8.6 ms for the 24 MP pass; the coefficients take 0.6-0.9 s on the CPU at 1500 x 1000,
so M4 moves them to the GPU (box means as separable passes on the `LowResGrid` infrastructure of ADR 0007).

Effect: on COCO's polygon outlines refinement is neutral (±0.01 IoU / BF: the ground truth is coarser than the
refinement). On the owner's photos at 100 % it snaps the 256 px hair and face masks onto sharp hairlines and
high-contrast edges; on defocused backgrounds it changes little (the guide has no edge to follow), and single
flyaway hairs stay partly selected. M4 tunes radius/eps per kind (hair: smaller eps; sky: larger radius for branches)
on the review sheets.

## Masks in the recipe (M4)

Never pixels. An AI mask in the recipe (additive field, ADR 0003):

```json
{"type": "ai", "model": "selfie-multiclass@1", "kind": "HAIR", "prompts": [{"x": 0.42, "y": 0.31}],
 "refine": {"radius": 8, "eps": 0.001}, "invert": false}
```

- `model` = `ModelSpec.key` (`<id>@<version>`, `ModelId` rules: an id is never reused for a different file). A new
  file is a new version; the app keeps every model a stored recipe names, or migrates the recipe explicitly.
- The cache (`cache_entries`, ADR 0006) stores the model-resolution soft mask (8-bit, ≤ 513 px) keyed by photo
  fingerprint + `model@version` + kind + prompts + the model-input version; refinement runs at render time on the GPU
  (deterministic, cheap), so the cache stays small and exports refine at their own size.
- Sync carries the recipe; each device recomputes the mask (GPU/CPU differences ≤ 0.003 mean, below visibility).
- M4 adds local adjustments (a mask-weighted second develop in the pipeline), the gradients, brush and range masks,
  the combine operations and the mask list. None of that is in this ADR.

## Delivery and size

`core/ml/models.json` pins each file (URL, SHA-256, bytes, licence, `bundled`); `scripts/ml/fetch_models.py` (stdlib)
downloads into `~/.cache/reveal-ml/<sha256>`, rejects any checksum mismatch, and copies the bundled ones into
`core/ml/build/generated/ml-assets/ml/`, which the `fetchMlModels` task adds to `:core:ml`'s assets. `--check`
re-downloads every pinned file. `MaskModelsTest` keeps the Kotlin specs and the manifest in step. The spike bundles
the recommended three plus LR-ASPP, Hair Segmenter, Selfie Segmenter and MODNet for comparison in the lab; M4 trims to
the recommended set (25.0 MB of models). Until M4 ships masks, `:core:ml` is a `debugImplementation` of the app and
the editor: release builds carry no LiteRT and no model.

## Consequences

- `:core:ml`: `MaskModel`/`SegmentationModel`, `ModelSpec`/`MaskModels`, `InputSpec`/`OutputSpec` (pure Kotlin,
  JVM-tested), `LiteRtEngine`, `GuidedRefiner` + shader; instrumented `MaskModelsDeviceTest`, `GuidedFilterGpuTest`,
  `MaskBenchmark`.
- Debug-only mask lab in the editor (More ⋯ → Mask lab): every bundled model, kind, refinement, tap for MagicTouch,
  timings on screen. No release UI.
- Open for M4: GPU coefficients, the ADE20K graph rewrite, face-landmark eyes/lips, the recipe field and cache, and
  a decision by the owner on ADE20K's data terms.

## Amendment M4/C: release delivery, GPU sky, eyes and lips, GPU refinement, `MaskService`

Date: 2026-11-12. Delivered by M4/C (`:core:ml`, `:core:data`, `:app`); the mask engine in `:core:pipeline` (M4/A) and
the editor's masks (M4/B) build on it. Measurements on cachy-fps (host CPU, x86_64 emulator with SwiftShader, LiteRT
CPU only there); the GPU numbers come from the owner's phone (commands in CLAUDE.md "On-device mask models").

### Release delivery and size

- Every build carries `:core:ml` (the app, the editor; `:core:data` provides `MaskService`). The mask lab stays in
  `src/debug`. `core/ml/models.json` now has two sets: `bundled` (every build) and `lab` (debug only: the published
  Selfie Multiclass, DeepLab ADE20K, LR-ASPP, Hair Segmenter, Selfie Segmenter, MODNet for comparison);
  `fetchMlModelsRelease` / `fetchMlModelsDebug` copy each set into the variant's assets. Entries are keyed by
  `<id>@<version>`, the key a recipe stores; a new file of a model is a new version (`selfie-multiclass@2`).
- A **derived** entry is made on the build machine from another pinned entry by a stdlib script and pinned by its
  output checksum like a download (`--check` re-derives): `convert` (`convert_deeplab_sky.py`,
  `convert_fp16_weights.py`, over `tflite_flatbuffer.py`, a minimal deterministic TFLite reader/writer that refuses
  any table or option it does not know rather than rewrite it lossily) and `extract` (a member of a `.task` zip).
- **Selfie Multiclass float16** (`selfie-multiclass@2`): the published float32 file with its 86 convolution filters
  stored as float16 behind DEQUANTIZE ops (TensorFlow's float16 weight layout): 16.4 → 8.3 MB. Against the original
  on LiteRT's CPU (host, the 6 public fixtures, per class at 256 px): mean absolute difference ≤ 0.00023, IoU at 0.5
  ≥ 0.9957 (`scripts/ml/verify_fp16.py`). The GPU computes in half precision anyway.
- R8: `core/ml/consumer-rules.pro` keeps `com.google.ai.edge.litert.**` and `org.tensorflow.lite.**` (LiteRT's JNI
  finds them by name; the AAR has no rules for them). Proved on the emulator with the R8-minified `bench` build
  (`--es mode masks`: every kind through the Hilt `MaskService` and through a CPU-only service) and the release APK
  signed with the debug key (installs, opens a RAW).
- ABIs: the app keeps `arm64-v8a` and `x86_64` (`ndk.abiFilters`): LibRaw has no 32-bit build, so LiteRT's
  `armeabi-v7a` natives (6.3 MB) were dead weight. Play delivers one ABI per device from the App Bundle, so the
  phone receives only arm64; `x86_64` stays in the universal APK so the release can be checked on an emulator.

| Release APK (`:app:assembleRelease`, unsigned) | Universal APK | arm64-v8a install (APK minus x86_64 libs) |
| --- | --- | --- |
| Before (88da15e, 0.5.5 + spike: no `:core:ml` in release) | 11.1 MB | 9.7 MB |
| After (M4/C) | 52.9 MB | 39.2 MB |
| of which LiteRT natives | 21.6 MB (arm64 9.4 + x86_64 12.2) | 9.4 MB |
| of which models | 19.6 MB (multiclass@2 8.3, MagicTouch 6.2, face landmarks 2.6, sky 2.5) | 19.6 MB |

The ML part of an arm64 install is 29.0 MB, under the ≈ 34 MB budget (25.0 MB of models in the spike's plan, plus
the 2.6 MB landmark model, minus the 8.1 MB float16 saves). Models are stored uncompressed (memory-mapped).

### Sky under 50 ms on the GPU: the conversion

`scripts/ml/convert_deeplab_sky.py` rewrites the pinned `deeplab-v3-ade20k@1`:

1. The 10 SPACE_TO_BATCH_ND → DEPTHWISE_CONV_2D (VALID) → BATCH_TO_SPACE_ND triples (TensorFlow's lowering of the
   atrous convolutions, rates 2 and 4) become the depthwise convolution with dilation = the block size and SAME
   padding, only where provably identical (stride 1, odd kernel, paddings before minus crops before = dilation ×
   (kernel − 1) / 2, block output the size of its input).
2. The 151 logits (513 × 513 × 151 after two bilinear upsamplings: 159 MB of float readback a run) become one
   channel at the logits' 129 × 129, then the same upsampling: `D = L − L_sky` (1 × 1 conv), `s = Σ_{c≠sky}
   exp(min(D_c, 6)) + 1e-4` (MINIMUM, EXP, 1 × 1 conv), `z = −log s` (LOG, MUL) = the sky logit against the
   log-sum-exp of the 150 other classes; `sigmoid(z)` is exactly the sky's softmax probability. The clamp keeps a
   half-precision sum finite (150 × e⁶ < 65504) and only bites where the sky's probability is < 0.0025; the 1e-4
   keeps the log finite where the sky wins by far (≤ 0.9999). Readback: 1.05 MB.

Ops of the converted graph (135 → 121): CONV_2D 43, DEPTHWISE_CONV_2D 19 (10 dilated), ADD 20, DEQUANTIZE 17
(float16 constants), MUL 11, RESIZE_BILINEAR 5, CONCATENATION 2, AVERAGE_POOL_2D 1, MINIMUM 1, EXP 1, LOG 1: all on
LiteRT's GPU op list (the original's 91 CPU ops came from SPACE_TO_BATCH_ND / BATCH_TO_SPACE_ND, which it lacks).
2.5 MB.

Equivalence (converted vs original, sky mask at 0.5 and mean absolute difference of the probability):

| Run | Images | Worst IoU | Worst mean diff | CPU time original → converted |
| --- | --- | --- | --- | --- |
| Host, LiteRT 2.3.0 Python, 4 threads (`verify_sky.py`) | 6 public fixtures (3 with sky) | 0.9936 | 0.0008 | 106 → 79 ms (median) |
| x86_64 emulator, LiteRT 2.3.2 CPU (`SkyConversionTest`) | 4 (outdoor group, landscape, portrait, trio) | 0.9988 | 0.0008 | 4.9-7.0 s → 0.44-0.79 s |

The phone's GPU time (target < 50 ms: the original ran 374 ms with 91 ops on the CPU) and the GPU/CPU parity come
from `MaskBenchmark` and `MaskModelsDeviceTest#gpuMatchesCpu` on the S24 (orchestrator run; LiteRT logs the
partition when it compiles).

### Eyes, irises, lips, teeth, eyebrows: Face Landmarker

- Model: `face_landmarks_detector.tflite` (2.55 MB, float16 weights, 478 points = MediaPipe's face mesh v2 with the
  irises 468-477, a face-presence logit), extracted at build time from the pinned `face_landmarker.task` bundle
  (Apache-2.0); the bundle's own face detector and blendshape model are not used. Input 256 × 256 RGB in 0..1.
- Run by LiteRT, **without MediaPipe Tasks**: Tasks would ship a second native runtime next to LiteRT (several MB a
  ABI) and its short-range face detector (128 px input, faces filling the frame), which misses the small faces of
  group photos that Reveal's full-range BlazeFace with tiles (ADR 0010) finds. Reveal does the graph's glue itself:
  `face/FaceCrop` (MediaPipe's ROI: the detection box × 1.5 on its long side, turned so the eye keypoints are level;
  bilinear sampling with explicit weights) and a second, tracked pass around the first pass's landmarks (eye-corner
  line level, box × 1.5), which MediaPipe's video graph does between frames; faces with presence < 0.5 are dropped.
- Parts (`face/FaceParts`): closed contours of MediaPipe's `face_mesh_connections` (FACEMESH_LIPS outer and inner,
  _LEFT_EYE / _RIGHT_EYE, _IRISES, _EYEBROWS), smoothed by a closed Catmull-Rom spline (4 points per segment), drawn
  with a linear soft edge 2 % of the outer-eye-corner distance wide (at least 0.75 px): **EYES** = the two eye
  openings; **IRISES** = a circle through the iris contour, clipped to the eye opening; **LIPS** = outer minus inner
  contour; **TEETH** = the inner contour (the open mouth: teeth and tongue; empty when the mouth is closed);
  **EYEBROWS** = the brow outlines. Several faces unite (max). No guided refinement by default (the polygons follow
  the landmarks; refinement would bleed into skin of similar colour).
- Results (public-domain fixtures; `FaceLandmarksDeviceTest`, emulator CPU): the studio portrait's 1 face, the
  trio's 3, 8 of the outdoor group's 16 people (those BlazeFace finds; eye corners 9-11 px apart there, so the parts
  are a few pixels); 134-233 ms for one face (two passes) and 342 ms for 8 faces on the emulator's CPU. The portrait
  and trio overlays are in `docs/screenshots/masks/face-parts-*.jpg`. Limits: faces the detector misses get no
  parts, and on faces a few dozen pixels wide the parts are only a few pixels (the group's eyes).

### Edge refinement on the GPU and per-kind settings

- `refine/GpuGuidedRefiner`: every pass of the fast guided filter on the GPU, the same maths as the CPU reference
  (`GuidedRefiner`) pass for pass: the explicit-weight resample of `Resample.taps` (taps in a texture, x then y), the
  13 window statistics into 4 RGBA32F targets, separable clamped box means, the 3 × 3 solve by cofactors, the box
  mean of the coefficients, then the existing full-resolution apply pass. `texelFetch` everywhere, no hardware
  filtering, no derivatives. Parity on the emulator (SwiftShader): max |GPU − CPU| 0.00001 (1200 × 800, subsample 4)
  and 0.00022 (600 × 400, subsample 1) on the refined mask. The phone's cost for the 1500 × 1000 coefficients (the CPU
  takes 0.5-0.9 s) comes from `GuidedFilterGpuTest#gpuCoefficientsMatchTheCpu`. Memory: about 9 RGBA32F textures of
  the coefficient grid at once (600 × 400: 35 MB).
- **Size-independent settings** (`refine/RefineDefaults`): the coefficient grid's long side is about 512 px
  (`subsample = long side / 512`), so the cache (≤ 1024 px), the preview and a 24 MP export refine a mask the same
  way relative to the photo, and the GPU grid stays small (coefficients from the preview's scale serve any output
  size: `apply` upsamples them). Radius in grid pixels, eps in 0..1 units²:

| Kind | Radius | eps | Why |
| --- | --- | --- | --- |
| People, subject, background, skin, face/body skin, clothes | 4 (0.8 % of the long side) | 1e-3 | the spike's setting, now size-independent |
| Hair | 4 | 2e-4 | a smaller eps follows fine, low-contrast strands |
| Sky | 8 | 1e-3 | a wider window cuts branches and foliage out of the sky |
| Eyes, irises, lips, teeth, eyebrows | none | — | landmark polygons |

To be tuned on the owner's review sheets in M4/B (the constants live in one place).

### `MaskService`: one entry point

`service/MaskService.mask(MaskRequest) → MaskOutput` (in `:core:ml`; `:core:data` provides it). An adapter to M4/A's
`AiMaskSource` maps: photo key (fingerprint) → `photoKey`; the model input (the upright recipe-independent render,
≤ 1024 px, "Model input" above) → `image` (+ `inputVersion` for the render's version); the recipe's kind, `model`
(`<id>@<version>`), prompts and refinement → the same fields; and gets a single-channel `Mask` at the input's size in
upright image coordinates (and the raw model output plus the setting, to refine on the GPU at render size).

- Kinds → models (`MaskService.modelFor`, what a new recipe records): subject / background → `magic-touch@1` (seeds:
  each face's centre and the body two face heights below, else the 3 strongest saliency peaks, plus taps; masks
  united; background = 1 − subject, which commutes with the linear guided filter); sky →
  `deeplab-v3-ade20k-sky@1`; people, hair, face skin, body skin, skin, clothes → `selfie-multiclass@2`; eyes,
  irises, lips, teeth, eyebrows → `face-landmarker@1`. A recipe naming a model that cannot make its kind is refused.
- Faces and saliency (`PhotoCues`): the caller's (`PhotoFeatures`) when given, else found once per photo (BlazeFace
  + saliency at 576 px).
- **Cache** (`service/MaskDiskCache`, `cache/masks`): the model's unrefined output and the refined mask separately,
  keyed by photo + `<id>@<version>` + kind + input version and size + seeds (+ radius/eps for the refined entry), so
  a refinement change never re-runs a model and one Selfie Multiclass run fills its six kinds. 8-bit, deflated, files
  named by the key's SHA-256 (no photo name or fingerprint on disk); fresh results are quantised the same way, so a
  hit and a miss give identical pixels. It is a `CacheArea` (`:core:data` `storage/MaskCacheArea`,
  `StorageKind.MASKS`, Settings → Storage "AI masks"): capped by `CacheBudget` on every write, trimmed with the other
  caches, emptied by "Clear cache". The adapter lives in `:core:data` (which now depends on `:core:ml`) because
  `:core:ml` must not depend on the library's database.
- **Memory and threads** (the editor also holds the proxy and the GL renderer): one segmentation model compiled at
  a time (GPU PSS 30-140 MB, ADR table) plus the landmark model (small); `release()` frees them (editor closed,
  `onTrimMemory`). One request at a time (a mutex); work on `Dispatchers.Default`; LiteRT's CPU fallback uses 4
  XNNPACK threads (`LiteRtEngine.CPU_THREADS`), so a CPU-only device keeps cores for the UI; the CPU refinement at
  ≤ 1024 px is parallel over rows (55-220 ms on the emulator). The last photo's cues and landmarks stay in memory
  (every face part reuses them).
- **Cancellation**: checked between steps (cues, each seed's run, refinement); a single model run is not
  interruptible (≤ 50 ms on a GPU, ≤ ~0.5 s on a CPU).
- Emulator CPU (614 × 768 portrait, first request includes compiling the model): subject 1.07 s (two seeds), sky
  0.42 s, Selfie Multiclass 0.21 s (all six kinds), face landmarks 0.09 s; every second request from the cache.

### Open (M4/B and the phone)

- The phone run: GPU times and op placement of the sky model, GPU/CPU parity of `selfie-multiclass@2` and the sky,
  the GPU coefficient pass's cost, the landmark model on the GPU.
- Tuning the refinement constants and the face-part feather on the owner's review sheets.
- The editor integration: the adapter to M4/A's `AiMaskSource`, the model-input render and its `inputVersion`.

