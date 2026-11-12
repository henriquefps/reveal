# ADR 0021: On-device mask models

Status: accepted (M4/S spike). Date: 2026-11-12. Precedes M4 "Masks" (`docs/plan/milestones.md`); builds on
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
| Sky | **DeepLab-v3 MobileNetV2 ADE20K** (Apache-2.0 weights; ADE20K terms flagged below). |
| Subject | **MediaPipe MagicTouch** (Apache-2.0), seeded automatically (faces, else saliency peaks), and by taps. |
| Background | `1 - subject` (or `1 - people`); no model. |
| Eyes, lips | Not from a segmenter (every bundled option lacks them; BiSeNet/CelebAMask-HQ is non-commercial). M4 draws them from face landmarks (MediaPipe Face Landmarker, Apache-2.0: lip and eye contours) — to evaluate in M4. |
| Edge refinement | Colour guided filter, fast form: coefficients at 1/4 resolution, one GPU pass at full resolution (8.6 ms at 24 MP on the S24). |
| APK budget | ≈ 34 MB per arm64 install: runtime 9.4 MB + models 25.0 MB. Today in **debug builds only** (the mask lab). |
| Recipe | A mask stores the model reference `<id>@<version>`, the kind, prompts, refinement parameters; never pixels. Cache keyed by photo fingerprint + model id@version + parameters. |
| Delivery | `core/ml/models.json` (URL, SHA-256, size, licence) + `scripts/ml/fetch_models.py` (stdlib, cached by checksum, `--check`), run by Gradle into `:core:ml`'s generated assets. |

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
