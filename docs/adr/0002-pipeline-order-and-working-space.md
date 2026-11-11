# ADR 0002: Pipeline order, working space and GPU/CPU numerics

Status: accepted (M0, W5). Date: 2026-11-10.

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
   real headroom leave `saturation` null and are not clipped.
2. **Camera matrix to working space (M0)**: camera RGB to XYZ (D65), Bradford adaptation, XYZ to Rec.2020. Colour
   operators below assume a defined, wide, well-behaved space.
3. **Lens corrections (later)**: geometry on coordinates, so it is folded into the final resampling (step 13);
   vignetting/CA gains sit here because they act on scene-linear light.
4. **Exposure (M0)**: a linear gain `2^EV` on scene-linear data. Before the tone curve so +EV recovers highlight
   headroom the curve then rolls off, instead of scaling already-compressed values.
5. **Highlight reconstruction (later)**: needs scene-linear, balanced, exposure-scaled data and the clipped-channel
   mask from step 1.
6. **Profile / base look (M0, the "Standard" tone curve)**: turns scene-linear into display-referred values. Every
   user adjustment below acts on this base, so presets and profiles define the look and sliders refine it.
   **Only for scene-referred input.** `PipelineInput.kind` is `SCENE_REFERRED` (demosaiced RAW) or `DISPLAY_REFERRED`
   (JPEG/HEIF/PNG, already tone-mapped by a camera or editor). For display-referred input the base look is the
   identity (`DevelopParams.toneKnee = IDENTITY_KNEE`), so the default recipe shows the original pixels (product
   principle: nothing is applied without the user seeing it). Tested: an sRGB 8-bit chart (every grey level plus colour
   patches) round-trips within 1 level with dither on, on the CPU and on the GPU. Exposure and white balance still
   apply to display-referred input; its out-of-range values clip only at the output.
7. **White balance fine / tint (M0 handles temperature and tint at step 1-2, see below)**.
8. **Tone (later)**: contrast, highlights/shadows (edge-aware), whites/blacks, curves: display-referred, after the
   base look.
9. **Colour (later)**: vibrance, saturation, HSL, grading, B&W: in a space where saturation is meaningful.
10. **Local adjustments / masks (later)**: after global colour so masks adjust the globally graded image.
11. **Detail (later)**: noise reduction then sharpening; NR before sharpening, both before effects.
12. **Effects (later)**: texture/clarity/dehaze, vignette, grain: grain last so it is not sharpened or denoised.
13. **Geometry (later)**: crop/rotate/transform as a single resampling.
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

Per channel, identity up to a knee `k = 0.8`, then `k + (1-k) g(u)`, `u = (x-k)/(1-k)`,
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
so its CRC is unchanged; RAW output changes.

## Determinism

Same recipe + same file + same pipeline version = same pixels. CPU: single-threaded float32 with `StrictMath`,
byte-identical across runs (tested; the checksum above). GPU: deterministic on a given device and driver
(tested: two renders are byte-equal); not bit-identical across devices, which is why parity has a tolerance and
export caches are keyed by the version, not compared across devices.
Dither noise is a function of the output pixel position and a seed (lowbias32 integer hash, identical on CPU and
GPU), one value per pixel shared by the three channels (no chroma noise): `q = floor(255 v + n)`. The on-screen
pass dithers by screen position; an export dithers by image position with the same seed.

## Proxies and tiles (the seam)

`PipelineInput` is "a linear image": today a proxy (about 2400 px), uploaded once as `RGBA16F`. The stage-1 pass
writes the working-space texture 1:1 with `texelFetch`, and the output pass samples that texture through a view
transform (zoom/pan, mip-mapped bilinear, nearest above 200 % zoom). Tiles (M2) reuse both passes unchanged: a tile
is an input texture plus an offset/overlap, stage 1 renders it into a tile-sized target, the output pass takes the
tile's view transform. Local operators will be extra passes between stage 1 and the output pass reading the
working-space texture; stage 1 is already isolated for that.

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

