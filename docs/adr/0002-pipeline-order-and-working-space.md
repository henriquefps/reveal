# ADR 0002: Pipeline order, working space and GPU/CPU numerics

Status: accepted (M0, W5). Date: 2026-11-10. Amended by ADR 0007 (M1 Light: slots 1, 5, 5b, 8; version 3), by
M1/G (slot 13 geometry, version 5; section "Geometry (slot 13)"), by ADR 0012 (M2: slot 11 Detail, version 6, the
editor's full-resolution tiles), by ADR 0013 (M2/B: tone curve in slot 8, colour mixer, black & white and colour
grading in slot 9; version 7), by ADR 0014 (M2/C: slot 12 Effects and the post-crop finish, version 8) and by ADR 0015
(M2/D: slot 3 vignetting, slot 10b defringe, lens geometry and the Transform in slot 13; version 9).

## Fixed operator order

Fixed in code (`CpuDevelop`, `GpuDevelopRenderer`); a user cannot reorder it. Each position has a reason.
Implemented in M0 are marked **(M0)**; the rest are the reserved slots from `docs/plan/architecture.md`.

1. **Raw white balance (M0)**: per-channel multipliers on un-balanced camera RGB. Must precede anything that
   clips or mixes channels: sensor saturation happens per channel, and highlight reconstruction needs to know
   which channels clipped *before* mixing. **Highlight clip (M0/W6, version 2):** when the input knows where its
   sensor saturated (`PipelineInput.saturation`, set for decoded RAWs), every balanced channel is clamped to
   `saturation * min(mul)`, the lowest balanced saturation point (dcraw's `-H 0`). Without it a clipped highlight,
   where green clips first and red/blue are multiplied above it, renders magenta; with it a fully clipped pixel is
   neutral white. It costs the colour of channels brighter than that point (bright saturated reds lose some
   saturation), which highlight reconstruction (slot 5) will recover. Synthetic inputs and future merged data with
   real headroom leave `saturation` null and are not clipped. **Replaced in version 3** (ADR 0007): no clamp here
   any more; highlight reconstruction runs right after this step, in balanced camera space.
2. **Camera matrix to working space (M0)**: camera RGB to XYZ (D65), Bradford adaptation, XYZ to Rec.2020. Colour
   operators below assume a defined, wide, well-behaved space.
3. **Lens corrections (M2/D, ADR 0015)**: the profile's vignetting is a gain here, on scene-linear light right after
   the matrix; distortion and lateral CA are coordinates, folded into the final resampling (step 13).
4. **Exposure (M0)**: a linear gain `2^EV` on scene-linear data. Before the tone curve so +EV recovers highlight
   headroom the curve then rolls off, instead of scaling already-compressed values.
5. **Highlight reconstruction (M1, ADR 0007)**: needs balanced data and the clipped-channel mask from step 1. It runs
   right after step 1 in camera space (exposure, a scalar gain, commutes with it).
   **5b. Highlights / Shadows (M1, ADR 0007)**: edge-aware local tone on scene-linear light, before the base look,
   so negative highlights can bring the RAW's headroom back before the shoulder compresses it.
6. **Profile / base look (M1, ADR 0008: Standard and six looks)**: turns scene-linear into display-referred values. Every
   user adjustment below acts on this base, so presets and profiles define the look and sliders refine it.
   **Only for scene-referred input.** `PipelineInput.kind` is `SCENE_REFERRED` (demosaiced RAW) or `DISPLAY_REFERRED`
   (JPEG/HEIF/PNG, already tone-mapped by a camera or editor). For display-referred input the base look is the
   identity (`ProfileParams.IDENTITY`), so the default recipe shows the original pixels (product
   principle: nothing is applied without the user seeing it). Tested: an sRGB 8-bit chart (every grey level plus colour
   patches) round-trips within 1 level with dither on, on the CPU and on the GPU. Exposure and white balance still
   apply to display-referred input; its out-of-range values clip only at the output.
7. **White balance fine / tint (M0 handles temperature and tint at step 1-2, see below)**.
8. **Tone (M1, ADR 0007)**: contrast, whites/blacks, then the **tone curve (M2, ADR 0013)**: display-referred, after
   the base look. Highlights/shadows moved to 5b (scene-referred).
9. **Colour (M1, ADR 0008: vibrance and saturation in OkLab)**: then the colour mixer (HSL) or black & white, then
   colour grading (M2, ADR 0013), all in OkLab, where hue and saturation are meaningful.
10. **Local adjustments / masks (later)**: after global colour so masks adjust the globally graded image.
    **10b. Defringe (M2/D, ADR 0015)**: desaturates purple/green fringes at strong edges, in OkLab, before Detail so
    noise reduction and sharpening see the clean edge; a neighbourhood operator with an apron in tiles.
11. **Detail (M2, ADR 0012)**: noise reduction then sharpening; NR before sharpening, both before effects. In OkLab,
    a neighbourhood operator at full resolution (the proxy scales its lengths); export tiles carry its apron.
12. **Effects (M2/C, ADR 0014)**: texture, clarity, dehaze in Detail's final pass (texture with an apron, clarity and
    dehaze on the low-resolution grid). The **vignette and grain are post-crop**: after slot 13, in the output passes,
    on the geometry operator's output (grain last so it is not sharpened, denoised or resampled away).
13. **Geometry (M1/G, M2/D)**: crop, straighten, quarter turns and flips as a single resampling, after every pixel
    operator and before the output transform (see "Geometry (slot 13)"); since ADR 0015 the lens's distortion and
    lateral CA (per channel) and the Transform's homography are a warp in the same mapping: still one resampling.
14. **Output transform + dithering (M0)**: working space to sRGB or Display P3, OETF, 8-bit quantisation with
    dither. Always last: every operator works on linear light and the display encoding is a property of the output,
    not of the edit.

White balance as a *recipe* parameter (temperature/tint) is resolved in step 1-2: the multipliers and matrix are
derived from it (below). The slot 7 "fine/tint" of the plan therefore collapses into the same two stages in M0.

## Working space: linear Rec.2020 (D65)

Chosen over ProPhoto RGB (D50):

- **Gamut**: Rec.2020 contains sRGB and Display P3 fully, and covers nearly all real-world surface colours; ProPhoto
  is larger but its blue and green primaries are imaginary (outside the spectral locus), so saturation and HSL
  operators can produce non-physical colours and negative luminance. All Rec.2020 primaries are real.
- **Numerics with saturation/HSL**: real primaries keep per-channel operations (curves, saturation) monotonic and
  predictable; float16 handles the range (headroom to 65504, negatives allowed).
- **Cost**: D65 matches the camera matrix we carry (D65 calibrated), sRGB and Display P3, so the output transform
  is a single 3x3 with no adaptation. ProPhoto would add a D50 adaptation in and out.
- Negative components (out-of-gamut) are kept through the pipeline and clamped only at the output.

## Colour maths (sources)

- RGB to XYZ matrices are derived from the published primaries and the D65 white `xy = (0.3127, 0.3290)`
  (IEC 61966-2-1; SMPTE EG 432-1; ITU-R BT.2020), after Bruce Lindbloom, "RGB/XYZ Matrices". Tests check them
  against the published 4-digit matrices.
- Chromatic adaptation: **Bradford** (Lam 1985; Lindbloom, "Chromatic Adaptation") from the scene illuminant to
  D65.
- White balance follows the DNG specification's camera-neutral approach (Adobe DNG 1.6, ch. 6) reduced to one
  calibration illuminant: raw response to an illuminant `cam = M^-1 W`, multipliers `mul = cam_g / cam`, balanced
  camera RGB to XYZ with `M diag(1/mul)`, adapted `W -> D65`. `M` (`PipelineInput.cameraToXyz`) is the
  un-normalised camera-to-XYZ matrix for D65 (inverse of LibRaw `cam_xyz` / DNG ColorMatrix2).
- Temperature and tint: Planckian locus in CIE 1960 UCS from Krystek's rational approximation (1985, Journal of
  Luminescence 33), valid 1000-15000 K; the sliders use 2000-15000 K. Tint is a displacement perpendicular to the
  locus, `Duv = -tint/3000` (tint +150 = 0.05 Duv toward magenta). `null` temperature/tint in the recipe means
  as-shot (the camera's multipliers), so the default recipe changes nothing without knowing the camera.
  `WhiteBalanceModel.asShot()` gives the K/tint a UI shows for "as shot".
- sRGB OETF (IEC 61966-2-1) for sRGB and Display P3 outputs.

## Base tone curve ("Standard")

**Superseded by ADR 0008** (M1): Standard is now a profile gain plus a filmic curve fitted to the camera's tone, and
the curve is a 16-knot spline shared by every look. The M0 curve, for the record: per channel, identity up to a knee
`k = 0.8`, then `k + (1-k) g(u)`, `u = (x-k)/(1-k)`,
`g(u) = u / (1+u^8)^(1/8)`: C1 at the knee (slope 1, second derivative 0), strictly monotonic, bounded by 1.0 but
never reaching it (no hard clip inside the curve), `f(1.0) = 0.983`. `(1+u^8)^(1/8)` is three square roots, exact
and cheap on both sides. Per-channel application desaturates bright colours slightly (filmic); a
luminance-preserving variant can arrive with the profiles in M1/M2.

## Precision: float32 CPU, float16 GPU

- CPU reference: `Float` (float32) arithmetic; all constants (matrices, multipliers) are computed in `Double` once
  and rounded to float, in `DevelopParams`, which the GPU also uses, so both start from identical constants.
  `StrictMath` for `pow`, so results do not depend on the JVM's intrinsics.
- GPU: `GL_RGBA16F` textures and render targets (source and working-space textures); shader arithmetic is
  `highp float` (fp32). The only fp16 roundings are texture storage: input upload and the stage-1 result.
  Float render targets require OpenGL ES 3.2 or `EXT_color_buffer_float`; the renderer checks this and probes a
  RGBA16F framebuffer at start-up and fails with `GlesRequirementException` naming what is missing.
- **ES version**: the plan targets GLES 3.2. The shaders are `#version 300 es` and use only ES 3.0 features plus
  float targets, because the emulator's GPU (host GPU via the emulator translator, Apple M4 Pro) reports ES 3.0 only
  with `EXT_color_buffer_float`. The code runs unchanged on a 3.2 phone. Revisit if a 3.1/3.2-only feature is needed.

## Parity tolerance

Measured on the emulator (OpenGL ES 3.0, host GPU, 5 synthetic images x 13 recipes each, random recipes seeded):

| Quantity | Tolerance (test) | Measured |
| --- | --- | --- |
| Linear working-space stage, per value | `abs <= 1e-6 + 3e-3 * (largest \|channel\| of that pixel)`; mean abs error `<= 3e-4` | max abs error 2.2e-3 on RAW-like and in-range images (0.11 on the 16x over-white ramp, where the tolerance scales with the value), mean 2.5e-4, worst 73 % of tolerance |
| 8-bit output (dither on, same seed), per value | max 2 levels (4 on the random-noise stress image); mean `<= 0.1` | max 1 level on gradients, checker, out-of-range and the scene; 3 on random noise; mean 0.035; 8 of 978,627 values off by more than 1 (sRGB) |

The tolerance is relative to the pixel's largest channel because a single fp16 rounding of a large channel
perturbs the small channels of the same pixel after the matrix mix; in the dark toe of sRGB (slope about 3300
levels per unit) that is worth up to ~3 levels on saturated random noise. Re-measure on the phone (the orchestrator
does) and update this table if the numbers move.

## PIPELINE_VERSION rules

`PIPELINE_VERSION` (`PipelineVersion.kt`) is stored with every render cache. Bump it when any pixel output for the
same (file, recipe) changes: a formula, a constant, the operator order, the dither, the working space, a decoder
mapping into `PipelineInput`. Do not bump for refactors, performance work with identical output, or new operators
whose recipe defaults are identity. `CpuDevelopTest` pins a CRC of a full CPU render of a synthetic scene to the
version; a legitimate change makes that test fail and the author updates the CRC and the version together.
Version 1 is this pipeline. Making the base look identity for display-referred input changes output for such
inputs, but version 1 has not shipped, so it stays 1 (no cache from an earlier pipeline exists).
**Version 2** (M0/W6): the highlight clip of slot 1 for input with a known saturation. The golden scene has none,
so its CRC is unchanged; RAW output changes. **Version 3** (M1, ADR 0007): highlight reconstruction replaces that
clip, and the Light operators exist; a second golden CRC pins them. **Version 4** (M1, ADR 0008): the profiles
replace the M0 Standard curve (RAW renders about 1.3 EV brighter, matching the camera), vibrance/saturation and the
relative white balance of display-referred input exist; a third golden CRC pins them. **Version 5** (M1/G): the
geometry operator (slot 13); a recipe without geometry renders as before, a fourth golden CRC pins the operator.
**Version 6** (M2, ADR 0012): the Detail operator (slot 11), identity at its defaults; a fifth golden CRC pins it.
**Version 7** (M2/B, ADR 0013): the tone curve, colour mixer, black & white and colour grading; identity at their
defaults (a recipe without them renders as before), a sixth golden CRC pins them.
**Version 8** (M2/C, ADR 0014 and ADR 0012 "M2/C"): the Effects (identity at their defaults) and the faster Detail
(separable luminance levels, colour on a half-resolution copy, the noise model): Detail's output changes; the Detail
CRC is re-pinned and a seventh one pins the Effects.
**Version 9** (M2/D, ADR 0015): vignetting correction (slot 3), defringe (slot 10b), lens distortion, lateral CA and the
Transform in slot 13; identity at their defaults (a recipe without them renders as before), an eighth golden CRC pins
them.

## Determinism

Same recipe + same file + same pipeline version = same pixels. CPU: single-threaded float32 with `StrictMath`,
byte-identical across runs (tested; the checksum above). GPU: deterministic on a given device and driver
(tested: two renders are byte-equal); not bit-identical across devices, which is why parity has a tolerance and
export caches are keyed by the version, not compared across devices.
Dither noise is a function of the output pixel position and a seed (lowbias32 integer hash, identical on CPU and
GPU), one value per pixel shared by the three channels (no chroma noise): `q = floor(255 v + n)`. The on-screen
pass dithers by screen position; an export dithers by image position with the same seed.

## Geometry (slot 13)

One resampling for crop + straighten + quarter turns + flips, the same maths everywhere:

- **`GeometryMapping`** (`geometry/`, pure Kotlin, JVM-tested): output pixel <-> image pixel for a `Geometry` on an
  image of W x H. `o = D * R(angle) * (p - c) + outputSize / 2`, where `c` is the crop centre (the recipe's normalised
  crop rounded to whole pixels of the image being rendered, at least one pixel), `R` turns clockwise as seen about it,
  and `D = F * Q^rotate` orients the result (quarter turns, then flips of the final picture). The output is the crop's
  size, swapped for odd quarter turns. Straightening is in the image's own orientation, so a level horizon stays level
  when the picture is turned or flipped; the crop mode converts the dial and the horizon line to "as seen" (a mirrored
  picture turns the other way in recipe terms). The CPU operator, the GPU output pass, the export's tiles
  (`sourceRegion` gives the image region a tile needs, filter margin included) and, later, masks and heals use it.
- **Filter**: without straightening every output pixel centre lands on an image pixel centre, so the operator copies
  pixels (crops, turns and flips never blur). With straightening: **Catmull-Rom** (Keys a = -0.5) in linear light, 4x4
  explicit fetches clamped to the edge, chosen over Lanczos-3 (36 taps) for cost on the 120 Hz phone with no visible
  difference at these angles; its small overshoot stays in float and is clamped by the output transform. Points outside
  the image (empty corners when the constraint is off) are black.
- **GPU**: folded into the output pass (no extra full-resolution pass, no extra texture): the view maps a screen pixel
  to an output point, the mapping (2 x vec4 in the `Output` block) to the working texture. Sampling, decided on the
  CPU (`OutputSampling`): 1:1 unstraightened = `texelFetch` (exact), straightened at 1:1 and magnified = Catmull-Rom at
  LOD 0 with the output point snapped to its pixel centre at 1:1 and from 200 % (so pixel peeping shows the export's
  pixels), minified = `textureLod` at the explicit LOD (on screen only). Readback/export render the output size.
- **Editor**: the zoom frames the operator's output; the histogram measures it; "hold to compare" keeps the geometry so
  the photo does not jump. The crop mode shows the **editing plane** (`GeometryMapping.editing`: the whole image
  straightened about the crop centre and oriented, outside the image = surround) with the crop frame as an axis-aligned
  box in it; the frame's content equals the operator's output (up to the edge snapping). `CropEditing` holds the
  gesture maths (handles with and without a ratio, pan, pinch, straighten with auto-constrain, horizon line, aspect
  presets, quarter turns, flips, swap) and keeps frames valid by searching along the gesture for the last valid frame.
- **Constrain to image** (`geometry.constrain`, default on): the straightened crop must lie inside the image;
  straightening shrinks the frame about its centre (from the frame at the start of the gesture, so turning back regrows
  it).

Parity (emulator, host GPU, `GpuParityTest.geometryMatchesCpuReferenceIn8Bit`, dither off, 4 images x 7 geometry
recipes incl. 7.3 deg and -31.7 deg straightening, quarter turns, flips, empty corners): **max 1 level, mean 0.069**;
unstraightened crops/turns/flips within 1 level everywhere (the stage's own fp16 rounding). The full 8-bit parity set
with the geometry recipes added stays within the existing per-image tolerances.

## Proxies and tiles (the seam)

`PipelineInput` is "a linear image": today a proxy (about 2400 px), uploaded once as `RGBA16F`. The stage-1 pass
writes the working-space texture 1:1 with `texelFetch`, and the output pass samples that texture through a view
transform (zoom/pan, mip-mapped bilinear, nearest above 200 % zoom). Tiles (M2) reuse both passes unchanged: a tile
is an input texture plus an offset/overlap, stage 1 renders it into a tile-sized target, the output pass takes the
tile's view transform. Local operators will be extra passes between stage 1 and the output pass reading the
working-space texture; stage 1 is already isolated for that. Since M1 (ADR 0007) stage 1 is two full-res stages
(scene, develop) with the Light group's low-res passes between them; they need no tile overlap, only cell-aligned
tiles and a grid pre-pass. Export implements this seam (ADR 0009, `TiledRenderer`): a `uOrigin` uniform, image-wide
grid pre-passes, Lanczos overlap per output tile. Since M2 (ADR 0012) the editor uses it too: above 50 % of the full
resolution the output pass reads developed full-resolution tiles (the export's renderer on a shared context) from an
atlas instead of the proxy, and the Detail operator gives every tile an apron.

### Output pass sampling (never from derivatives)

The output pass must not depend on a driver's derivative/LOD precision: on Adreno 750 `texture()` on the mipmapped
stage texture picked a slightly positive LOD at some 1:1 pixels and blended mip 1 into patch edges (up to 9 levels).
The rule, decided on the CPU (`OutputSampling`):

- view scale exactly 1 (readback, export, and on screen at 100 %): `texelFetch(uStage, floor(ip), 0)`; no filtering, no mips;
- minified (scale < 1): `textureLod` with the uniform LOD `log2(1 / scale)`, trilinear;
- magnified (scale > 1): `textureLod` at LOD 0, nearest from 200 %, bilinear below.

Only on-screen pixels at minification can change, so this has no cache key impact. `PIPELINE_VERSION` stays 2:
readback at 1:1 on a conforming GPU (the emulator, where LOD was already exactly 0) is bit-identical to before, and
the fix makes other GPUs match it. `GpuParityTest.hardEdgedPatchesMatchCpuWithinOneLevelAtOneToOne` guards it.

### Editor additions to the output pass (M1/E)

All in their own files under `gl/` and additive (no operator, no `PIPELINE_VERSION` change, readback and export
untouched unless a flag is passed):

- **Frame** (`ViewFrame`, `GpuDevelopRenderer.setFrame`): the editor's `SurfaceView` fills the screen behind the
  chrome; the frame is the visible rectangle between the panels. The zoom (`ViewTransform`) is in frame terms and
  `CompareLayout` shifts the centre (`c - (frameCentre - windowCentre) / scale`), so a panel snap re-fits the photo
  without resizing the surface.
- **Compare panes** (`CompareMode`, `setCompare`): split = the edit full-frame, then the original scissored to the
  "before" side; side by side = two scissored panes, each keeping the zoom relative to its own fit. The original is a
  second mip-mapped RGBA16F texture filled by running stage 1 with the defaults and blitting the stage texture
  (stage 1 stays a black box for the operator workers); it is freed as soon as no pane shows it.
- **Loupe** (`Loupe`, `setLoupe`): one more scissored pane at a higher scale, for the eyedropper.
- **Clipping warnings** (`ClippingWarnings`, `setClippingWarnings`, `dither.w` bits of the output block): on the
  encoded value before dithering, highlight = any channel quantises to 255 (`max(e) >= 254.5/255`, red
  `255,26,26`), shadow = every channel quantises to 0 (`max(e) < 0.5/255`, blue `0,102,255`). JVM test of the
  classification, GPU test (`ReadbackApisTest`) against the CPU render.
- **Histogram** (`HistogramReadback`, `histogramEnabled`/`onHistogram`): after a presented frame whose stage
  changed, the output pass is drawn into a 256 px RGBA8 target (explicit LOD) and read back synchronously, binned on
  the GL thread (RGB + Rec.709 luma, clipped shares). Rate-limited to one per 100 ms while the photo keeps changing,
  plus a trailing one, so it stays live while dragging.
- **Source sample** (`sampleSource(x, y, radius)`): mean of the uploaded (un-balanced) pixels around a point, read
  through a framebuffer on the source texture; `WhiteBalancePicker` turns it into temperature/tint
  (`W = M * cam`, then `cctAndTint`). The grey-world "Auto" estimate runs on the CPU proxy before upload.

## Output transforms

`OutputSpace.SRGB` and `OutputSpace.DISPLAY_P3` (sRGB curve on P3 primaries): one 3x3 from the working space,
clamp to [0, 1], OETF, dither. Gamut clipping is a plain clamp in M0; a perceptual gamut mapping is later work.
HDR (Rec.2020 PQ) is a later output transform in the same slot.

## Measurement (emulator, to be re-measured on the phone)

2400x1600 proxy, stage 1 + output pass into an RGBA8 target, `glFinish` after each frame, 120 exposure values
(`PipelineBenchmark`): first (cold) run mean 2.5 ms, p50 1.9 ms, p95 6.6 ms, max 9.7 ms per frame on the emulator's host GPU, warm
re-run mean 0.84 ms, p95 1.13 ms, max 1.26 ms; upload plus first frame 0.4-0.9 s. On-screen, the debug canvas coalesced 80 updates into 61 frames during a
slider drag. `GL_TIME_ELAPSED` is not exposed by `android.opengl` (no binding for `EXT_disjoint_timer_query`), so GPU
time is wall time up to `glFinish`.

## Measurement of the editor (M0/W6, emulator, to be re-measured on the phone)

The real editor (`EditorMeasurementsTest`): a 24 MP ARW opened through `ACTION_VIEW`, 2400x1600 proxy, then a finger
dragging the exposure slider for 4 s in real time (two `input swipe` gestures; the Compose test clock is not used, so
the UI runs live). Emulator API 36.1 arm64, host GPU (Apple M4 Pro via the emulator's translator), 60 Hz display,
`measureGpuTime` on (each frame ends with `glFinish` after the swap).

| | Public ZV-E10 (3 runs) | Owner ZV-E10 (3 files x 3 runs) |
| --- | --- | --- |
| Frames presented during the 4 s drag | 235-237 | 230-238 |
| Interval between presented frames, p50 / p95 / max | 16.6 / 19-20 / 36-79 ms | 16.6 / 19-22 / 30-78 ms |
| Render + swap per frame (stage 1 + mips + output pass), p50 / p95 | 2.1 / 2.4-2.9 ms | 2.0-2.2 / 2.3-3.7 ms (two runs 10.8-12.5 / 14.5-16.3 ms: host GPU contention) |
| Updates coalesced | 0 | 0 |

Every slider update was rendered (one per input event, about one per display frame), at the display rate; the frame
cost (about 2 ms) leaves a wide margin under the 16 ms budget. The occasional 30-80 ms gap is most likely the
emulator (host scheduling, the start of an `input swipe`): render + swap p95 stayed at or under 16.3 ms even in the
contended runs.
Time to the first developed frame: ADR 0004, "Measurements of the real open flow".


## Measurement of the M1 editor (M1/E, emulator, to be re-measured on the phone)

`EditorMeasurementsTest` on the public ZV-E10 ARW (2400x1600 proxy, 1080x2400 screen), 4 s real-time drag, with
`-e light true` (contrast +25, highlights -50, shadows +40, whites +15, blacks -10, so the local tone path runs) and
`-e profile true` (`GpuDevelopRenderer.profilePasses`: `glFinish` after every section; mean ms per frame).

**The emulator's GPU matters.** `scripts/emulator.sh` defaults to `-gpu swiftshader_indirect` (a CPU rasteriser):
there every pass is 15-50 ms (develop 45, mipmaps 24, scene 20, guided filter 16, output 15) and a drag shows about
50 ms per frame. With `EMULATOR_GPU=host` (Apple M4 Pro through the translator), the setup of the M0 numbers and of
`PipelineBenchmark`, the same drag runs at the display rate. Measure on the host GPU; `GpuParityTest` also passes
there while one SwiftShader run went just over tolerance on `rawNoise`.

| Host GPU, warm runs | exposure drag | highlights drag (scene + guide cached) |
| --- | --- | --- |
| params (CPU) | 0.13 | 0.14-0.23 |
| scene pass | 0.80 | not run |
| guided filter | 1.42-1.45 | not run |
| develop pass | 1.35-1.40 | 1.52-1.54 |
| mipmaps | 0.62-0.64 | 0.56-0.57 |
| output pass | 0.84-0.91 | 0.82-1.04 |
| GPU work per frame | about 5.1 ms | about 3.1 ms (was about 4.8 before the cache) |
| frames in 4 s, interval p50 / p95 | 179-191, 16.6 / 20-23 ms | 191-221, 16.6-17.1 / 21-40 ms |
| histograms (async PBO, 10 Hz cap) | 10-11, GL thread 1.1-1.8 ms | 15-17, GL thread 1.1-1.7 ms (one 7.4) |

"Swap" (13 ms with `glFinish`) is the wait for the 60 Hz vsync, not work. The max interval (about 0.8 s on the
exposure drag) is idle time: the swipe reaches the +5 EV clamp and nothing changes until it turns back. UI thread
(gfxinfo over the drag, emulator, indicative): p50 18-25 ms, 7-12 slow-UI-thread frames per 5 s window; slider rows
now memoise their callbacks so a drag recomposes only the dragged row's value. The phone (Adreno 750, 120 Hz)
decides the remaining budget.
