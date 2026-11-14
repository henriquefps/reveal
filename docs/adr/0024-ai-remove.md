# ADR 0024: AI Remove (on-device inpainting)

Status: **draft (M5/R spike)**, waiting for the owner's decision. Date: 2026-11-13. Builds on ADR 0021 (model rules:
bundled at build time, pinned checksums, never a runtime download, licences of weights and training data, APK
budget) and ADR 0022 (masks); the Remove tool and its patch fill are M5/H's.

## Context

The owner's one missing Lightroom feature is an AI object remover: paint over a person, a pole or a sign and the photo
is filled as if it was never there. It must run offline on the device (his Galaxy S24 Ultra does it natively with
Samsung's models). If the quality is clearly bad, Remove stays patch-based (M5/H).

## Candidates

Licences: **weights** / **training data**. Size is the file a build would ship. S24 Ultra (Adreno 750) numbers are
from `InpaintBenchmark` (debug test APK, LiteRT 2.3.2 CompiledModel, files pushed into `files/models`): load =
compile, warm = median of 10 model runs at 512 x 512, crop1024 = a 1024 px crop's downscale + run + upscale + blend
(Kotlin, CPU), PSS = process memory added. Quality = the CC0 review set below.

| Model | Licence (weights / data) | Size | S24 GPU load / warm / crop1024 ms | S24 CPU warm ms | PSS + | GPU vs CPU | Review set (clean fills of 19) | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **big-LaMa** (Samsung Research, 2021) | Apache-2.0 / Places2 (MIT CSAIL, research terms) | 208 MB fp32; 102 MB fp16; ≈ 51 MB int8 weights | not measured (no LiteRT file yet; LaMa-Dilated, same trunk: 148 ms) | — | — | — | **≈ 15** (host, ONNX) | quality bar; over the budget; conversion work (below) |
| **MI-GAN 512 Places2** (Picsart, ICCV 2023) | MIT (`LICENSE-WEIGHTS`) / Places2 | 16.3 MB (fp16 weights; ≈ 8.2 MB as int8 weights) | 1890 / **46.5** / 93 | 467 | 89 MB GPU, 390 MB CPU | mean 0.0008, max 0.089 | ≈ 8 | shippable; good on simple backgrounds, invents objects on large or busy removals |
| LaMa-Dilated (Qualcomm AI Hub export) | Apache-2.0 code / **CelebA-HQ** (non-commercial) | 174 MB | 1872 / 148 / 184 | 4802 | 937 MB | mean 0.0002 | 0 | **rejected**: trained on faces (smears scenes), data terms, AI Hub asset licence |
| MAT (CVPR 2022) | **CC BY-NC 4.0** | — | — | — | — | — | — | **rejected** (licence) |
| FcF (WACV 2023), CoModGAN | research only / StyleGAN2 (NVIDIA non-commercial) | — | — | — | — | — | — | **rejected** (licence) |
| AOT-GAN, ZITS | Apache-2.0 / Places2 | ~60 MB, ~300 MB | — | — | — | — | — | not tried: below LaMa in their own papers (AOT-GAN) or bigger than it (ZITS) |
| Simple PatchMatch (spike's host baseline) | own code | — | — | — | — | — | ≈ 2 | weaker than a tuned patch fill: M5/H's own fill is the real comparison |

**No system API.** Samsung's Object Eraser lives in Samsung Gallery with no third-party SDK; Google's Magic Eraser is
Google Photos only; ML Kit and AICore (Gemini Nano) offer no inpainting. Nothing a third-party app may call offline.

**Training data.** Every usable model is trained on Places2 (MIT CSAIL; images with research and education terms):
the same situation as ADE20K for the sky model (ADR 0021), which the owner accepted. Reveal ships no Places2 image;
the weights carry MIT (MI-GAN) or Apache-2.0 (LaMa) licences. Owner decision flagged.

## Quality: the review set

19 removals in 10 CC0 photos from Wikimedia Commons (beaches, Dutch streets, park benches, poles, a road sign, a
monument park; small, medium, large, very large and thin masks drawn by hand). Crop policy (what `InpaintService`
would do): a square crop around the dilated mask, side = max(512, 2 x the mask's extent), seen by the model at
512 x 512; the fill is resized back and blended inside a feathered mask. Sheets (outside the repo): the owner's
`reveal-specs/review/m5-remove-inpainting/` (`sheet-1..4.jpg`, a 100 % detail sheet, the sources).

- **big-LaMa**: clean on 15 of 19, including the big and busy ones (a child on a bicycle in a crowded street, two
  men on cobbles by a canal, a whole bench across grass and path). Faint ghosts on two small people (old film,
  a man behind a car), a smudge in dense leaves.
- **MI-GAN**: clean on sand, sky, grass, poles and the sunset beach (8 of 19); on removals larger than about a
  fifth of the crop in busy scenes it paints new objects (dark leg-like strokes where the cyclist was, a white
  building where the truck was, a strip of plants where the bench was).
- **Hybrid** (MI-GAN's fill, then PatchMatch texture transfer at the crop's resolution): no better; it kept
  MI-GAN's invented shapes and smeared them.
- **Resolution**: every model fills at 512 px; a 2880 px crop (a large object in a 24 MP photo) is upscaled 5.6x in
  the hole, which looks soft at 100 % next to sharp surroundings (detail sheet). Phase 2 adds matched grain/noise in
  the hole (cheap) and may tile big crops.
- A numeric proxy (shape-matched synthetic holes in empty areas, PSNR/SSIM against the original) is being run; it
  ranks reconstruction, not realism, so the sheets decide.

## Recommendation

1. **MI-GAN (A)** fits: +16.3 MB (or ≈ 8.2 MB with int8 weights through a stdlib derive script like
   `convert_fp16_weights.py`, to verify on the GPU), 46 ms on the S24's GPU, fully on the GPU (509 nodes), clean
   licence (MIT). But it is **clearly worse than big-LaMa** on the removals people care about most (people in
   busy scenes, large objects): as "AI" next to "Patch" it would disappoint there.
2. **big-LaMa (B)** is the quality the owner expects, at an APK cost: int8 weights ≈ 51 MB (the arm64 install
   grows from ≈ 39 to ≈ 90 MB; the ML part from 29 to ≈ 80 MB, against ADR 0021's ≈ 34 MB budget). The conversion is
   real work but has a known path: Carve's ONNX export already replaces the FFT of the Fourier units by DFT matrix
   products (MatMul/Einsum with sin/cos constants, no FFT op), all ops LiteRT's GPU runs; the same rewrite in PyTorch,
   exported by `litert-torch` (or ONNX → LiteRT), int8 weights, then pinned. Not stdlib-derivable: the converted file
   would be a pinned artefact (a release asset of this repo plus the host script that makes it). Expected speed: the
   same trunk as LaMa-Dilated (148 ms on the S24) plus the DFT products; to be measured.

**Recommended: B (big-LaMa, int8 weights), if the owner accepts ≈ +51 MB.** Otherwise A as a cheap "AI" option
limited to small and medium removals, with Patch as the default; or no AI Remove at all.

## Integration (phase 2, after the decision)

`models.json` entry (bundled); `:core:ml` `InpaintService` (crop around the region at the model's size, run, paste
back with a feathered blend at full resolution, matched grain; cache keyed like AI masks by photo fingerprint + model
`<id>@<version>` + region); the heal recipe's `method = model:<id>@<version>` (M5/H); preview, zoom and export
identical (export waits like AI masks); Remove's "AI" / "Patch" switch; device tests and a benchmark on the phone.
