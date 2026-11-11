# ADR 0007: Light operators

Status: accepted (M1, Light). Date: 2026-11-11. Amends ADR 0002 (order of slots 5-8, the M0 highlight clip,
`PIPELINE_VERSION` 3).

The Light group of the editor: highlight reconstruction (always on, not a slider), Highlights, Shadows, Contrast,
Whites and Blacks, with Lightroom-like intuition (not its formulas), a CPU reference in `cpu/HighlightReconstruction.kt`
and `cpu/Tone.kt`, GLSL in `gl/LightShaders.kt`, low-res passes in `gl/LightPasses.kt`. Exposure (slot 4) is
unchanged.

## Where each operator sits, and why

| Slot (ADR 0002) | Operator | Domain |
| --- | --- | --- |
| 1 | raw white balance (no clamp any more) | un-balanced -> balanced camera RGB |
| 5, run right after 1 | **highlight reconstruction** | balanced camera RGB |
| 2, 4 | camera matrix, exposure | scene-linear Rec.2020 |
| **5b** (new) | **Highlights / Shadows** (local, edge-aware) | scene-linear log luminance |
| 6 | base look (profiles, ADR 0008) | scene -> display-referred |
| 8 | **Contrast, Whites, Blacks** (global) | display-referred lightness |

- **Reconstruction runs in camera space, right after the white balance.** Clipping happens per *camera* channel, so
  that is the only space where "which channel clipped" means anything; once the matrix mixes channels the
  information is gone. Exposure is a scalar gain and commutes with it, so this is the same as running it after
  exposure (slot 5's position in ADR 0002).
- **Highlights/Shadows are scene-referred** (before the base look), not display-referred as ADR 0002 slot 8 planned.
  Reason: after the Standard shoulder, everything above about 1.2x the reference white is within 0.2 % of 1.0, so a
  display-referred "highlights -100" could only darken a flat white; on scene-linear data it can pull the RAW's
  headroom back into range before the shoulder compresses it, which is what the user expects from the slider
  ("recover the sky"). The edge-aware base is also physically meaningful there (ratios of light).
- **Contrast, Whites, Blacks are display-referred** (after the base look): they shape the display range, its end
  points and its mid-tone slope, and are bounded there (contrast never pushes white past 1).

## Highlight reconstruction

`HighlightReconstructionOp`. Input: balanced camera RGB `b`, and the balanced clip level of each channel
`clip_c = saturation * mul_c` (`PipelineInput.saturation`, set for decoded RAWs; without it the operator does not
exist, as before).

1. Per channel, how clipped: `t = clamp((b/clip - 0.90) / (0.99 - 0.90), 0, 1)`; a ramp, not a threshold, so nothing
   jumps at the clip boundary.
2. **Neighbourhood chroma** on the low-res grid (below): each cell averages the chromaticity `b / sum(b)` of its
   pixels weighted by `(1 - max t) * y^2` (`y` = brightness relative to the clip: bright unclipped pixels describe a
   highlight's colour best). A push-pull pyramid (Gortler, Grzeszczuk, Szeliski, Cohen 1996, "The Lumigraph",
   SIGGRAPH; the pull-push hole filling) fills cells without data: push averages 2x2 cells up to 1x1; pull keeps a
   cell's own chroma in proportion to its confidence `min(1, weight / 0.05)` and takes the rest from the bilinearly
   upsampled coarser level; above the top level is the neutral chroma (1/3, 1/3, 1/3), so with no unclipped pixel
   anywhere the answer is neutral, never a guess.
3. **Per pixel**, with the cell chroma `C` (bilinear) and unclipped weights `u = 1 - t`: `k = sum(u b) / sum(u C)`,
   each clipped channel becomes `b + t * max(min(k C, 4 clip) - b, 0)` (never darker than the clipped value, at most
   two stops over its clip). Where every channel clipped (`f = min t`) the pixel blends to neutral at the brightest
   channel of `full * C`, `full = max(clip / C)`: the darkest light of the neighbourhood's chroma with every
   channel at least at its clip (`max clip` when `C` is neutral). That is the limit of step 3 as the last channel
   reaches its clip, so a blown core is not darker than its rim, and it is never magenta.

Measured (JVM, `HighlightReconstructionTest`, synthetic blobs with known truth): on the 2553 partially clipped pixels
the mean |log error| against the unclipped truth is **0.039** (M0's dcraw `-H 0` clamp: 0.236, 6x worse); at -2 EV
the reconstructed core of a blob keeps the light's red/green ratio within 8 %, and every pixel where green clipped
has green at least as strong as its weakest channel (no magenta).

**Limits.** One chroma per neighbourhood: a highlight that straddles two differently coloured objects gets their blend
near the boundary. Fully clipped areas are flat neutral: no texture is invented. Luminance across the rim/core
transition can dip by a few percent where the chroma estimate changes (measured 2.4 % and 6.6 % on the synthetic
blobs at -2 EV); a dark core would be tens of percent. Detail survives only where at least one channel did.
`PipelineInput.saturation` is the lowest per-channel saturation point, so a channel that saturates higher is treated
as clipping slightly early (harmless: its estimate is then `max(value, reconstruction)`).

## Highlights and Shadows (local tone)

`LocalToneOp`. Log luminance `l = log2(max(Y, 2^-20))`, Rec.2020 `Y`, 0 = the reference white, -2.47 = mid grey.

1. **Base layer** by a self-guided **fast guided filter** (He, Sun, Tang 2013, "Guided Image Filtering", IEEE TPAMI
   35(6); He, Sun 2015, "Fast Guided Filter", arXiv:1505.00996) on the low-res grid: cell means of `l` and `l^2`, box
   mean of radius 6 cells, `a = var / (var + eps)`, `b = mean (1 - a)`, `eps = 1/32` stop^2, box mean of `a` and `b`,
   then per pixel `B = a' l + b'`. Base/detail tone mapping after Durand and Dorsey 2002 ("Fast Bilateral Filtering
   for the Display of HDR Images", SIGGRAPH): the curve acts on `B`; the detail `l - B` is kept.
2. **Edge-aware upsampling of the coefficients** (joint bilateral upsampling, Kopf, Cohen, Lischinski, Uyttendaele
   2007, SIGGRAPH): the four nearest cells weighted by bilinear weight times `max(exp(-(l - mean_l)^2 / (2 * 0.5^2)),
   1e-3)` on each cell's mean `l`. Plain bilinear upsampling blends the dark and bright side's coefficients for the
   pixels of the cell next to an edge, a fringe one cell wide (10 px at the proxy; measured 0.11 stops at a 6.6-stop
   edge before this step, 0.009 after).
3. **Curve on the base**, two maps applied in sequence, each strictly monotonic, so their composition is monotonic
   for every pair of slider values (tested on a 0.001-stop grid for all pairs of -100, -50, 0, 50, 100):
   - shadows `B1 = B + S wS(B)`, `S = shadows/100 * 1.75` stops, `wS` a smooth bump: 0 below -12 stops (black stays
     black), 1 on [-6, -5.5], 0 above -1.5;
   - highlights `B2 = B1 + H wH(B1)`, `H = highlights/100 * 1` stop, `wH = smoothstep(-3, -0.5, B) + 0.6 ramp(B + 0.5)`,
     `ramp(x) = (sqrt(x^2 + 0.25) + x) / 2` (a smooth `max(0, x)`): negative highlights *compress* the range above
     white instead of shifting it.
4. **Apply** `rgb *= 2^(B2 - B)`: one gain on all three channels keeps chromaticity and hue.

What the sliders do (golden values, `ToneTest`): highlights -100 takes the reference white to -1.36 stops, two stops
over white to -0.51, four stops over to +0.29 (still bright, with its detail), mid grey -0.13, -8 stops not at all;
shadows +100 lifts deep shadows (-6 stops) by the full 1.75 stops, mid grey by about 0.5, leaves -14 stops and the
highlights alone. The intuition: -100 highlights on a bright sky darkens it and brings back the clouds with their
texture; +100 shadows on a backlit face lifts it by up to 1.75 stops with its local contrast and no bright rim where
it meets the sky.

### Quality vs cost (why the guided filter)

Halo metric (`ToneTest`): on a step edge, the spread in stops of the tone gain across one side, from the pixel next
to the edge to the middle of the half; a pointwise operator scores 0. Texture: peak-to-peak of a +-0.25-stop texture
on the dark side after shadows +100.

| Base layer | Halo, 0.02 / 2.0 edge (6.6 stops) | Halo, 0.06 / 0.24 edge (2 stops), both sliders | Texture kept | Cost (2400x1600, emulator, p50) |
| --- | --- | --- | --- | --- |
| none (pointwise curve) | 0 | 0 | 38 % | 0 |
| box mean of `l` (not edge-aware) | 1.44 (shadows) / 1.59 (highlights) | 0.69 | - | ~ guided |
| **guided filter + JBU, eps 1/32** | **0.000 / 0.009** | **0.031 / 0.033** | **79 %** | guide passes 1.4-1.9 ms |
| guided filter + JBU, eps 1/8 | 0.000 / 0.033 | 0.107 / 0.113 | 93 % | same |

`eps` trades halos for texture almost linearly; 1/32 was chosen because "no halos" is the product's explicit bar.
Resolution independence: the grid scales with the image, so a render at twice the size, downsampled, differs from
the proxy render by 0.0005 stops on average beyond what the base pipeline already differs (`ToneTest`).

Alternatives not taken in M1:
- **Bilateral grid** (Chen, Paris, Durand 2007, SIGGRAPH): its splat is a scatter; on ES 3.0 without compute or
  atomics it becomes a gather per grid cell over all intensity bins (8-16 MRT outputs), several times the guided
  filter's cost for a similar halo behaviour.
- **Local Laplacian filters** (Paris, Hasinoff, Kautz 2011, SIGGRAPH; Aubry et al. 2014, "Fast Local Laplacian
  Filters", ACM TOG): the reference for halo-free detail, but one Laplacian pyramid per intensity sample (8-12) over
  the full proxy, an order of magnitude more passes and bandwidth than the guided filter's six low-res passes.
  Revisit for Clarity/Texture (Effects), where multi-scale detail matters more than here.

## Contrast, Whites, Blacks (global tone)

`GlobalToneOp`, on lightness `p = cbrt(max(Y, 0))` (the cube root of CIE L*; mid grey -> 0.565, white -> 1).

- **Contrast**: two cubic Hermite segments (0, 0) -> (p0, p0) -> (1, 1), slope `g = 2^(contrast/100 * 0.7)` at the
  pivot (+100: 1.62, -100: 0.62) and `1/g` at both ends, identity from 1. C1 at the pivot, strictly monotonic
  (Fritsch, Carlson 1980, "Monotone Piecewise Cubic Interpolation", SIAM J. Numer. Anal. 17: `g^2 + g^-2 <= 9`).
  A power curve `p0 (p/p0)^g` was tried first: its slope is infinite at the ends for `g < 1`, which amplified any
  difference next to black and white (it failed GPU parity by 19 % at white under -83 contrast).
- **Whites**: `p + w smoothstep(p0, 1, p)`, `w = whites/100 * 0.12`: +100 sends the top of the range past white (more
  clipped whites), -100 brings white to lightness 0.88.
- **Blacks**: `p + k (1 - smoothstep(0, p0, p))`, `k = blacks/100 * 0.2`: +100 lifts black to a matte grey
  (Y = 0.008, sRGB ~22), -100 crushes everything below Y = 0.008.
- **Apply** as a gain `g = min(Y'/max(Y, 1e-5), 8)` on the channels plus the rest of the luminance change added as
  neutral (`Y' - max(Y, 0) g`, never negative): hue is kept, and blacks can lift a pure black (where no gain can)
  without multiplying deep-shadow noise by more than 8.

## GPU

Passes per frame (`GpuDevelopRenderer`), all `#version 300 es`, neighbourhoods with `texelFetch` only, bilinear
upsampling written out with fp32 weights (hardware bilinear weights are 8-bit on some GPUs; no derivatives, no
implicit LOD, ADR 0002's lesson):

1. *(only when the white balance changed and some pixel reaches the clip ramp)* reconstruction chroma: row segments
   (W/cell x H) -> cell means -> push (9 levels) -> pull (9 levels), RGBA32F;
2. **scene stage** (full res): WB, reconstruction apply, matrix, exposure -> scene texture (RGBA16F);
3. *(only when highlights or shadows are not 0)* guide: row segments -> cell moments -> box H -> box V + coefficients
   -> box H -> box V, RGBA32F;
4. **develop stage** (full res): highlights/shadows apply, base look, contrast/whites/blacks -> working texture
   (RGBA16F, mip-mapped) -> output pass.

Skipping: reconstruction is skipped for inputs without saturation or with nothing within 90 % of it (checked once at
upload on the CPU; the operator is then the identity), the guide when highlights and shadows are 0, the global tone
branch when contrast, whites and blacks are 0 (uniform flags); neutral sliders are bit-exact identity (tested on CPU
and GPU).

**Grid** (`LowResGrid`): cells of `ceil(longEdge / 256)` px (10 px at a 2400 px proxy), so the grid is about
256 cells long whatever the image size.

**Tile seam** (ADR 0002): every Light pass reads either one grid cell of the full-res image or the grid itself, and
both full-res stages are pointwise given the grid. Tiled export therefore needs **no overlap** for the Light group
(`LightPasses.REQUIRED_OVERLAP_PX = 0`), only tiles aligned to the cell size and a pre-pass that fills the grid (row
segments, cell means) from all tiles before the low-res passes run once for the whole image.

### Costs (emulator, host GPU Apple M4 Pro via the translator, 2400x1600, `PipelineBenchmark`)

Each frame: every pass + mipmaps + output pass into RGBA8, `glFinish`, 120 recipes after 10 warm-up frames.

| Drag | mean | p50 | p95 | max |
| --- | --- | --- | --- | --- |
| exposure (Light passes skipped; M0 measured 0.84-2.5 mean) | 1.48 ms | 1.41 | 2.01 | 2.16 |
| highlights/shadows with every Light slider active (guide + local + global tone) | 1.93 ms | 1.87 | 2.32 | 3.01 |
| temperature on a RAW with clipped highlights (reconstruction pyramid every frame, plus local tone) | 2.64 ms | 2.62 | 2.95 | 3.42 |

Well inside the 8 ms emulator budget (16 ms on the phone). On a busy host (other builds running) the same runs
measured p50 4.0 / 7.7 ms with p95 up to 58 ms: host contention, not the pipeline.

Per pass (instrumented once with `glFinish` between passes, p50): scene stage 0.7-1.0 ms, guide 1.4-1.9 ms, develop
stage 0.5-1.5 ms, reconstruction 1.6 ms (only while the white balance changes). The emulator's translator adds a
fixed cost per draw (~0.08 ms), which dominates the many tiny low-res passes; the phone re-measures.

## Numerics and parity

CPU float32 with `StrictMath` (`log`, `exp`, `pow`, `cbrt`), sums in the same order as the GPU (row segments, then
segments; box sums from -r to r). Low-res textures are RGBA32F, so the grid passes are near-exact; the full-res
textures stay RGBA16F (ADR 0002).

Parity (`GpuParityTest`, now 10 synthetic images incl. clipped highlights and a textured step edge, 22 recipes each:
the default, 9 Light extremes, 12 random recipes with random Light sliders):

| Quantity | Tolerance | Measured (emulator) |
| --- | --- | --- |
| Linear stage, M0 recipes (no tone slider) on non-reconstructing images | ADR 0002: `1e-6 + 3e-3 * scale` | worst 64 % of tolerance (unchanged) |
| Linear stage, Light recipes or reconstructing images | `2e-3 + 2e-2 * scale` | worst 87 % (random noise, contrast -100); highlights 63 %, clipped ramp 55 %, step edge 53 % |
| Linear stage, mean abs error, all | `<= 3e-4` | 2.6e-4 |
| 8-bit output, sRGB / Display P3 | max 2 levels (3 when reconstructing, 4 on random noise), mean `<= 0.1` | max 3 / 2 (reconstructing images, noise), 2 / 1 elsewhere; mean 0.045 / 0.044; 106 / 43 of 4,246,242 values off by more than 1 |

Two things amplify the fp16 roundings of ADR 0002, hence a looser tolerance for **Light** recipes (any tone slider
not 0) and images where highlights are **reconstructed**: `abs <= 2e-3 + 2e-2 * (largest |channel|)` in the linear
stage, 3 levels max in 8 bits (mean <= 0.1 as before). (1) Reconstruction reads the fp16 source near the clip, where
the 90-99 % ramp multiplies a source rounding by 11. (2) Tone gains computed from a tiny luminance (saturated noise
whose luminance is near zero) multiply relative errors far beyond the curve's slope. M0 recipes keep the old
tolerance. On the real sample RAWs (ZV-E10 and D5600, 1600 px proxies, all Light sliders) GPU and CPU differ by at
most **1 level** (mean 0.03).

### Parity on SwiftShader (M1, added with ADR 0008)

`scripts/emulator.sh` starts emulators with SwiftShader (software GLES via ANGLE/Vulkan) by default, whose `pow`/`exp2`
are less exact than the host GPU's. There, on main before ADR 0008, the random recipe "EV +2.99, contrast -22,
whites +74, blacks +8, WB 5320 K / tint 74" on the random-noise image failed the linear stage at **1.06x** the Light
tolerance (one pixel: CPU 1.8808, GPU 1.8389, red channel of a saturated noise pixel lifted far above white by the
whites gain). The same recipe passes on the host GPU (worst 0.87x, on another recipe). Not a logic mismatch: the
whites/blacks gain is `y2 / max(y, YMIN)` of a luminance near zero, so its sensitivity to the input is huge exactly
where the stress image puts saturated pixels with almost no luminance, and the less exact `pow` of SwiftShader is
enough to cross the line. ADR 0008's profile clamps negative curve inputs to 0, so luminance entering the global tone
is never negative; with it the linear stage passes on SwiftShader (worst 0.59x over every image and recipe, that
recipe now pinned in `LIGHT_EXTREMES`). Its 8-bit output on the noise image reaches **6 levels** on SwiftShader (a few
pixels, 143 of 8.5 M values off by more than 1, mean 0.02), so tone recipes on the random-noise image are allowed 6
levels (`OUTPUT_MAX_LEVELS_NOISE_TONE`); every other image and the real sample RAWs keep their limits.

## PIPELINE_VERSION

**3**: the M0 clamp of slot 1 is replaced by reconstruction (RAW output with clipped highlights changes), and the Light
operators exist (identity at their defaults, so no other output changes). `CpuDevelopTest` pins a second CRC over a
RAW with clipped highlights and the demo scene with every Light slider set.
