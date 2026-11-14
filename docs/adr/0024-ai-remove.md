# ADR 0024: AI Remove (on-device inpainting)

Status: **accepted** (owner, 2026-11-14: big-LaMa with int8 weights; the APK growth and Places2's terms accepted).
Date: 2026-11-13 (spike), 2026-11-14 (decision, conversion, runtime). Builds on ADR 0021 (model rules: bundled at
build time, pinned checksums, never a runtime download, licences of weights and training data, APK budget, amended
below) and ADR 0023 (healing: the Remove tool, its patch fill and the contract a model method plugs into).

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Model | **big-LaMa** (Suvorov et al., WACV 2022; Samsung Research; Apache-2.0 weights, trained on Places2), `big-lama@1` |
| Training data | Places2 (MIT CSAIL, research terms): accepted by the owner, like ADE20K for the sky (ADR 0021) |
| Conversion | `scripts/ml/convert_big_lama.py` (host, PyTorch + litert-torch): the Fourier units as DFT matrix products, every op on LiteRT's GPU, checked against the original; the float32 file is this repository's release asset `ml-big-lama-fp32-1`, pinned in `models.json` (`big-lama-fp32@1`) |
| Shipped file | `big-lama@1` = the float32 file with int8 convolution weights (per output channel; the first convolution over rgb + mask stays float32), derived at build time by `scripts/ml/convert_int8_weights.py` (standard library): **53.6 MB** |
| Speed (S24 Ultra GPU) | **272 ms** a fill (warm), 293 ms the first run, 3.8 s to compile (the GPU program cache shortens later loads), +616 MB PSS while loaded; CPU fallback 7.1 s |
| Runtime | `:core:ml` `inpaint/`: `InpaintLayout` (crop ↔ the 512 x 512 input), `InpaintService` (loaded for a fill and released right after it; a free-memory check before loading; a failure falls back to Patch), `FillDiskCache` (`cache/inpaint`, budgeted with the AI masks); Hilt in `:core:data` |
| Recipe | Remove's `method = "model:big-lama@1"` (ADR 0023); the patch fill stays the fallback for an unknown method |
| APK budget | ADR 0021's ≈ 34 MB for ML becomes ≈ 85 MB (owner): models 73.2 MB + LiteRT 9.4 MB per arm64 install |

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
| **big-LaMa** (Samsung Research, 2021) | Apache-2.0 / Places2 (MIT CSAIL, research terms) | 208 MB fp32; 102 MB fp16; 53.6 MB int8 weights (shipped) | 3815 / **272** / 330 (after the conversion below) | 7111 | 616 MB | mean 0.0024 | **≈ 15** (host, ONNX) | quality bar; over the budget; conversion work (below) |
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
- A numeric proxy (each case's mask shape moved to 3 empty places of its image, 54 holes, PSNR / SSIM inside the
  hole against what was there; it ranks reconstruction, not realism, so the sheets decide): big-LaMa 22.7 dB / 0.614,
  MI-GAN 21.5 / 0.567, LaMa-Dilated 20.9 / 0.610, the simple PatchMatch 20.3 / 0.611.

## Recommendation (spike) and decision

The spike recommended big-LaMa with int8 weights if the owner accepted ≈ +51 MB (MI-GAN, 16 MB, as a cheap option for
small and medium removals otherwise). The owner chose big-LaMa (2026-11-14).

## Conversion

`scripts/ml/convert_big_lama.py` (run once on a host; its output is published, not rebuilt by the build):

1. The authors' checkpoint (`big-lama.zip`, sha256 `f1b358ca…75f6`, Hugging Face mirror of the LaMa release) and
   the LaMa source at a fixed commit (`advimman/lama@786f593`); the generator from the checkpoint's own config,
   weights loaded strictly (Lightning's objects in the checkpoint are skipped by a stub unpickler).
2. **Fourier units without FFT.** LiteRT has no FFT op on the GPU. Each unit (36: two per ResNet block x 18) works on
   the 64 x 64 bottleneck of a 512 x 512 input, so its `rfftn` / `irfftn` (orthonormal) become fixed matrix
   products: along W `x @ [C | -S]` (33 bins), along H `[re | im] @ [[C, -S], [S, C]]`, the inverses likewise
   (`a = 1` for the DC and Nyquist bins, 2 otherwise). Three LiteRT constraints shape the rewrite, each found on the
   way: (a) the GPU refuses a BATCH_MATMUL whose second operand is not constant ("non-constant tensor": the first
   export ran 39 of 1,844 nodes on the GPU, 15.8 s a fill), so every product has its constant on the right
   (FULLY_CONNECTED), with transposes so H is last; (b) the GPU handles at most 4-D tensors, so the spectrum is kept
   as [re of every channel, im of every channel] and the 1 x 1 convolution's and batch norm's channels are permuted
   instead of interleaving re/im (the original's layout) through a 5-D reshape; (c) litert-torch 0.10.0 converts
   `matmul(a, A) - matmul(b, B)` wrongly (each product alone is exact; the difference was off by 0.37), so each
   transform is one product on stacked operands. The rewrite matches `torch.fft` to 7e-6.
3. `ConvTranspose2d(k 3, s 2, p 1, output_padding 1)` (not lowered by the converter) = the unpadded transposed
   convolution cropped to `[1, 2H + 1)`.
4. Export: one NHWC input [1, 512, 512, 4] = (rgb x (1 − mask), mask), output NHWC [1, 512, 512, 3] in 0..1. The
   file is checked against the original on four inputs (max 7.6e-5, mean ≤ 1.4e-6) before it is written.
   Ops: CONV_2D 222, SLICE 294, ADD 219, TRANSPOSE 216, MUL 150, CONCATENATION 145, FULLY_CONNECTED 144,
   MIRROR_PAD 78, SUB 75, TRANSPOSE_CONV 3, LOGISTIC 1; 205.3 MB.

**int8 weights** (`convert_int8_weights.py`, over `tflite_flatbuffer.py`, now aware of FULLY_CONNECTED, SLICE,
MIRROR_PAD, BATCH_MATMUL options and signature defs): each CONV_2D / TRANSPOSE_CONV filter of ≥ 4,096 values becomes
int8 with one scale per output channel behind a DEQUANTIZE (the arithmetic stays floating point). Sensitivity, measured
group by group on host photos (mean |Δ| in the hole vs float32): Fourier-domain 1 x 1 convolutions 0.0014, spectral
transforms' 1 x 1 0.0045, transposed convolutions 0.0023, ResNet blocks' local 3 x 3 0.0059, down-sampling 0.0061,
last convolution 0.0039 — and the **first** 7 x 7 convolution over rgb + mask **0.0865** (its per-channel scale is
shared by inputs of very different ranges: whole fills turned green). That filter (12,544 values) stays float32; the
rest together: mean |Δ| 0.008-0.012, p99 0.04-0.06, visually the same fill (float16 weights: 0.0017, 103.8 MB).
Float32 → shipped: 205.3 → 53.6 MB.

## Speed and memory on the S24 Ultra

`InpaintBenchmark` (debug test APK, LiteRT 2.3.2, warm median of 10 at 512 x 512; crop1024 adds a 1024 px crop's
downscale, upscale and blend on the CPU):

| File | Backend | Load (compile) | First run | Warm | crop1024 | PSS + | GPU vs CPU |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `big-lama@1` (int8 weights) | GPU (1,771 of 1,771 nodes, 1 partition) | 3,815 ms | 293 ms | **272 ms** | 330 ms | 616 MB | mean 0.0024 |
| `big-lama@1` | CPU (XNNPACK, 4 threads) | 649 ms | 6,594 ms | 7,111 ms | 7,599 ms | 982 MB | — |
| first export (BATCH_MATMUL left constant) | GPU (39 of 1,844 nodes) | 3,522 ms | 22,523 ms | 15,839 ms | — | 1,413 MB | — |
| MI-GAN 512 (spike) | GPU | 1,890 ms | 61 ms | 47 ms | 93 ms | 89 MB | mean 0.0008 |

x86_64 emulator, LiteRT CPU (`InpaintDeviceTest`, a 360 x 300 crop): 15.0 s the model, 18.7 s with the load; a cached
fill 174 ms. The GPU program cache's effect on a cold load and the hole's GPU/CPU parity on a photo come from
`InpaintDeviceTest` on the phone (CLAUDE.md "AI Remove").

## Runtime (`:core:ml` `inpaint/`)

- **Input**: ADR 0023's Remove contract gives a method a context crop of the original (≤ 512 px, its display
  encoding: as-shot colour, exposure-normalised, sRGB curve continued above 1) and the hole. `InpaintLayout` resamples
  it so the longer side is 512 (explicit weights: bilinear up, area down), divides by the largest known value when it
  exceeds 1 (bright skies keep their level instead of clipping into a seam; multiplied back after), clamps negatives,
  fills the rest of the square with the content mirrored (the hole mirrored too: the model never sees blacked-out
  pixels marked known), and grows the hole by the resampling (never shrinks it). Back: the model's content area is
  resampled to the crop, the hole's pixels taken, every other pixel the input's own.
- **`InpaintService`**: blocking and serial (ADR 0023's `RemoveFills` calls methods from one worker); one model loaded
  at a time (GPU, else CPU; the GPU's compiled programs kept in the code cache, `LiteRtEngine.GpuCache`).
- **Memory** (the model holds +616 MB on the S24's GPU, +982 MB on its CPU): loaded for a fill and **released right
  after it** (`idleMs` = 0; a later fill reloads, from the GPU program cache), and loaded only when the system reports
  free memory above 20 x the model file (`MEMORY_PER_FILE_BYTE`, ≈ 1.07 GB for big-LaMa) and not `lowMemory`. A
  refusal, an `OutOfMemoryError` or a failed load or run frees what was loaded and throws
  `InpaintUnavailableException` (`lowMemory` says which): Remove then falls back to the patch fill with a message.
  PSS before / held / after release: `InpaintDeviceTest#memoryIsReturnedAfterRelease` on the phone.
- **Cache** (`FillDiskCache`, `cache/inpaint`): key = model `<id>@<version>` + the crop's size and exact content
  (SHA-256 of every rgb and hole value); since ADR 0023's crop is defined on the original (the same for preview, zoom
  and export) the key is the same everywhere. The hole's pixels only, float16-rounded, deflated, files named by the
  key's hash; a fresh fill is rounded the same way (hit = miss, bit for bit). A `CacheArea` of kind `MASKS` (Settings
  → Storage "AI masks and fills"), trimmed by the budget, emptied by "Clear cache".

## Integration with Remove (ADR 0023; lands with M5/H's merge)

`RemoveFills` maps `model:big-lama@1` to a `RemoveMethod` over `InpaintService` (no offsets: the engine upsamples the
crop's fill), so preview, zoom tiles and exports share one fill per key and an export waits for it like for the
patch fill. The model fills at most at the crop's resolution (≤ 512 px from ADR 0023): a large object in a 24 MP photo
is upsampled several times in the hole, so the engine adds grain matched to the surrounding pixels' fine detail at the
render's own resolution (deterministic by position). Remove's mode offers "AI" (default when the model loads on the
GPU) and "Patch".
