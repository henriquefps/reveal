# ADR 0015: Optics (lens profiles, CA, defringe), Transform, Upright and auto-straighten

Status: accepted (M2/D). Date: 2026-11-11. Amends ADR 0002 (slots 3, 10b and 13; `PIPELINE_VERSION` 9), ADR 0003
(recipe fields), ADR 0009 (export tiles of a warped geometry) and ADR 0012 (zoom tiles through the warp).

## Decision in one paragraph

A photo's lens profile (the correction data a Sony ARW embeds, else the lensfun database matched from the EXIF) is part
of `PipelineInput` (`lens`). The recipe's new `optics` group switches it on and scales it; `geometry.transform` holds
the Transform sliders and Upright. Vignetting is a per-pixel gain at slot 3; distortion, lateral CA and the Transform
are **one** coordinate warp folded into the existing slot 13 resampling (`GeometryMapping.warp`: a homography plus a
per-channel radial table), so the image is resampled once; defringe is a neighbourhood operator between the colour
stage and Detail (slot 10b). New photos with a profile start with the corrections on as one undoable history step.
Upright and auto-straighten read straight lines found by a small LSD-style detector and write the ordinary sliders.

## Lens profiles

Every function of a `LensProfile` (`optics/LensProfile.kt`) works on the radius **normalised to the half diagonal**
(0 at the centre, 1 at a corner) of the image being rendered, so the proxy, the zoom tiles and the export agree:

- `distortion(r)`: source radius / corrected radius (output to source, as resampling looks up pixels);
- `chromatic(c, r)`: red's and blue's extra radial scale, multiplying `distortion`;
- `vignetting(r)`: relative illumination at source radius `r` (the correction divides by it).

### Sony knots

`:core:image` already read the three SR2 blocks (`SonyLensCorrectionReader`, the same data as `0x7032/0x7035/0x7037`
of the raw IFD). Scales as darktable's `lens.cc` (embedded metadata v2): distortion `1 + D * 2^-14`, CA
`1 + R * 2^-21` / `1 + B * 2^-21` (green 1), vignetting `2^(0.5 - 2^(V * 2^-13 - 1))`, piecewise linear between knots
and flat beyond the last. **Knot radii:** darktable places knot `i` of `n` at `(i + 0.5) / (n - 1)`; we measured the
camera's own JPEG against the RAW (public CC0 samples: the embedded 1920 x 1080 / 1440 x 1080 previews, phase
correlation on a grid of patches, a radial fit with one free scale) and `i / (n - 1)` fits better:

| Sample (CC0) | knots | no correction | `(i + 0.5) / (n - 1)` | `i / (n - 1)` |
| --- | --- | --- | --- | --- |
| ZV-E10, E PZ 16-50mm at 29 mm | 11 | 1.67 px rms | 0.29 px rms (median 0.20) | **0.23 px rms (median 0.13)** |
| A7C, FE 24-105mm F4 G at 105 mm | 16 | 1.02 px rms | 0.60 px rms (median 0.36) | **0.50 px rms (median 0.33)** |

(pixels of the half-size RAW; 442 and 229 matched patches.) APS-C bodies write 11 knots, full frame 16.

### lensfun fallback

For other cameras and for JPEG/HEIF, `LensfunDatabase` (`scripts/lensfun/convert_lensfun.py`, pinned lensfun commit
`52cf1a7b0e`, 1059 cameras and 1577 calibrated lenses, 307 KB gzipped JSON in `core/pipeline/src/main/resources/
lensfun/`) matches the camera by maker + model (crop factor and mount; an unknown camera takes `focal35 / focal`) and the
lens by name (every number of the EXIF lens name must appear; the best share of matching words wins among lenses of the
camera's mount), then interpolates the calibrations linearly in focal length (vignetting also in aperture, at the
farthest focus distance). lensfun's conventions (from its `modifier.cpp` / `mod-coord.cpp` / `mod-color.cpp`) are
converted to our radius: distortion and TCA use Hugin units (`r = 1` at half the calibration image's height), so
`rho = r * (cropCal / cropImage) * hypot(aspectCal, 1)`, and lensfun keeps the centre's magnification at 1, giving
`P(rho / d) / d` with `d` the polynomial at 0 (`1 - k1` for poly3, `1 - a - b - c` for ptlens); TCA is evaluated at the
distorted radius; vignetting (`pa`) is in units of the calibration's half diagonal (`r * cropCal / cropImage`).
Cross-check: lensfun's calibration of the ZV-E10 kit lens agrees in sign and size with the camera's own data
(`LensfunDatabaseTest`).

**Licence.** The database is CC BY-SA 3.0 (lensfun `README.md`, `data/COPYING.CC_BY-SA_3.0`). We ship it as a separate
data asset, converted (an adaptation), under the same CC BY-SA 3.0 with attribution (`docs/licences.md`, the About
screen); it is not combined into the AGPL code, which only reads it, so the two licences apply side by side (as in
darktable and RawTherapee, GPL programs shipping the same database).

`LensResolver.resolve(info, metadata)` picks embedded data first, then lensfun. Both the editor's proxy, its full
resolution source (`TileSource.withLens`), the export and the edited thumbnails resolve the same profile.

## Recipe and defaults

`Optics` (all defaults = no change): `lensCorrections`, `distortion` and `vignetting` amounts (0-200 %, 100 = the
profile), `removeCa`, `caRed` / `caBlue` (the automatic estimate, derived values in 1e-4), and defringe: `purpleAmount`
/ `greenAmount` (0-20) with hue ranges (`purpleHueLow/High` 30-70, `greenHueLow/High` 40-60 of 0-100, as in other
editors). `Geometry.transform`: `vertical`, `horizontal` (-100..100), `rotate` (-10..10 degrees), `aspect`
(-100..100), `scale` (50-150 %), `offsetX/Y` (-100..100), `upright` (the mode that set them) and `guides` (2-4 lines of
Guided Upright, in the lens-corrected image, normalised). The JSON `optics` group was `{}`: it reads as the defaults.

**New photos.** "Lens corrections on by default" lives in the recipe, never as a hidden render rule: when the editor
opens a photo with **no stored history** and a profile, it commits one step, `HistoryAction.LENS_PROFILE` ("Lens profile
applied"), with `Optics.forNewPhoto` (corrections on for a RAW; CA removal on when the profile has CA data; JPEG/HEIF,
which cameras correct already, keep it off). Photos edited before have history, so their recipes and pixels stay as
they were. The step is undoable, and `Recipe.isUnedited()` treats exactly that change as unedited, so the library's
"edited" mark does not appear for it (the stored recipe, used by export and thumbnails, does include it).

**Amounts** scale the deviation from the identity: `1 + a (distortion - 1)` and `illumination^a`. CA has no amount.
**CA without profile data** (`CaEstimator`): red and blue modelled as green scaled about the centre; on edges across the
radius, a global search of the scale (1e-4 steps, +-40e-4, parabola) minimises the mismatch of normalised radial
profiles after a 1 px blur (on very sharp edges sub-pixel shifts otherwise read through the interpolation's phase).
Synthetic checkerboards: 8 / -7 (x1e-4) read as 8.9 / -7.9, 20 / -20 as 20.2 / -20.4, none as 0.

## Operators

- **Slot 3, vignetting** (`VignetteOp`, `SCENE_FRAG`): `rgb *= gain(r)` after the camera matrix, `r` the pixel centre's
  distance from the image centre in half diagonals of the **whole** image (tiles add their origin). The gain table is
  `LensTables` (256 entries over `r` in 0..1.5, linear between entries, flat beyond): the table is the operator's
  definition, read by the CPU and by `texelFetch` + explicit interpolation on the GPU (a 256 x 1 RGBA32F texture).
- **Slot 10b, defringe** (`DefringeOp`, `DefringeShaders`): in OkLab rounded to float16 (`Half`, `q16`) as the Detail
  stage stores it: `E` = lightness range in a `(2 radius + 1)^2` neighbourhood (radius 3 full-resolution pixels,
  scaled by the proxy), `e = smoothstep(0.05, 0.25, E)`, hue windows with 10 degree feathers (purple: OkLab hue 250 +
  1.1 x slider, green: 100 + 1.0 x slider), `k = e * max(purple strength x window, green strength x window)` with
  strength `1 - exp(-amount / 5)`, `(a, b) *= 1 - k`. Between the colour stage and Detail: the develop stage writes
  OkLab when it runs, the pass writes OkLab again when Detail follows (else linear RGB). Its radius adds to the tiles'
  apron (`DevelopParams.reach`). It cannot tell a purple rim from a fringe (neither can other editors): a purple object
  loses colour only within the radius of a strong edge.
- **Slot 13, the warp** (`geometry/Warp.kt`, `WarpShaders`): the crop's coordinates are now a **plane**: the
  lens-corrected, transformed image of the same size. For plane pixel `t`: `v = (t - C) / R` (centre, half diagonal),
  `h = M (v, 1)`, `u = h.xy / h.z` (`h.z <= 1e-6`: behind the horizon, outside), `r = |u|`, channel `c` reads
  `C + R * lambda_c(r) * u`. `M` is the Transform's inverse homography; `lambda_c` the lens table (RGB = per-channel
  `distortion x chromatic` at the amount, **auto-scaled**: `lambda(r) = l(r / s) / s` with `s` solved by bisection so
  the image border reads at or inside the source and touches it somewhere: no empty edge, the widest view). With a warp
  every sample is Catmull-Rom (one per channel when CA is removed, the green point deciding inside/outside); without
  one nothing changes (crops still copy pixels). `GeometryMapping.sourceRegion` bounds a warped tile by sampling its
  border densely (the map is continuous and one to one), plus a pixel; `ExportPlan` shrinks the tile edge until every
  region fits; the zoom finds its tiles through the warp (red and blue stay inside the slot's 8 px border).

### Transform

`Perspective.forward` (normalised coordinates, so the proxy and full resolution agree):
`F = Offset * Scale * Aspect * Rotate * Recentre * Tilt`, `Tilt = diag(1, 1, 1/f) Ry(h) Rx(v) diag(1, 1, f)` with a
fixed focal `f = 1` half diagonal (about 22 mm full frame) and 40 degrees of tilt at a slider value of 100 (positive
vertical grows the top, positive horizontal the right side); aspect `x 2^(a/200)`, `y 2^(-a/200)`; offset 0.5 half
diagonals at 100. A fixed focal keeps the recipe independent of the EXIF; any focal makes verticals parallel, the
aspect slider corrects the proportions. **Constrain crop** (`geometry.constrain`, default on): a crop is valid when its
straightened corners map back inside the corrected image (`CropEditing.isValid`; the transformed image is the
projective image of a rectangle, convex, so the corners decide); moving a transform slider shrinks the frame about its
centre from the frame at the start of the gesture (`CropEditing.transform`), so going back regrows it. The crop mode
shows the whole plane (the transformed image; outside = surround) under the frame, with a fine grid while a slider is
dragged.

## Upright and auto-straighten

`LineDetector`: a simplified LSD (Grompone von Gioi et al., IPOL 2012) without the a-contrario test: blur (sigma 0.8),
2x2 gradient, regions grown from strong pixels whose level-line angle is within 22.5 degrees, a segment along each
region's weighted principal axis when long (4 % of the long edge), thin (6:1) and dense (60 %). The editor runs it once
at open on a 1200 px luminance copy of the proxy (`OpticsAnalysis`), in source pixels; Upright maps the segments into
the corrected image by inverting the current lens table.

`Upright.solve`: lines within 30 degrees of plumb are vertical, of level horizontal; the cost is the length-weighted
squared angle of each mapped line to its axis, truncated at 2 degrees for detected lines (outliers: lines not vertical
in the world) and plain for guides. A coarse grid then Nelder-Mead, in stages: vertical + rotate on the verticals,
then horizontal + rotate with the vertical fixed (within 70 % of its range), then a local refinement of all; a strong
tilt squashes lines towards its horizon where any direction looks level, and the stages keep the search out of that
corner. Level = rotate only; Vertical = vertical + rotate; Full = all three; Auto = Full at 75 % of the perspective
(some convergence kept, as a natural look), the rotation solved again; Guided = the user's 2-4 lines, two vertical ones
solve the vertical slider, two horizontal ones the horizontal one, rotation always. The result writes the sliders
(`upright` records the mode; moving a slider by hand sets it to Off) and the crop refits; the crop mode's Done commits
it as one step.

Auto-straighten (the Auto chip next to the dial): the lines through the current Transform, each one's deviation from
its nearest axis, the weighted median, refined by the weighted mean of the lines within 2 degrees of it, negated.

Accuracy on synthetic facades (`PerspectiveScenes`, 900 x 600, 4x4 supersampled): auto-straighten within 0.2 degrees
at -8.3 / -2.25 / 0.6 / 3.7 / 12 degrees; Vertical recovers 35 / 2 within 1.5 / 0.2 and leaves every vertical within
0.3 degrees of plumb; Full recovers -25 / 20 / -1.5 within 2 / 2 / 0.3 (measured -24.96 / 20.01 / -1.50); Guided
recovers 30 within 0.5.

## Parity and performance

Emulator API 36.1 x86_64, host GPU (RTX 4080 through the translator); to be re-measured on the phone.

- `GpuParityTest.opticsMatchCpuReference` (lens + CA + vignetting at 150-200 %, defringe, defringe + Detail, the
  Transform with lens, straightening and a quarter turn, an unconstrained Transform; 4 images at 1:1 and at a 2.5x
  proxy): within ADR 0002's 8-bit tolerance (2 levels; the Detail tolerance of 4 when defringe runs, as its OkLab is
  stored in float16 like Detail's). Isolated: vignetting, distortion and defringe each within 1 level, linear error
  about 1e-3.
- `TiledRendererTest.opticsAndTransformExportLikeThePreview`: a warped export (lens + CA + Transform + straightening +
  defringe) stitches **bit-identically** at 256 and 1024 px tiles, equals the preview renderer within 1 level and the
  CPU reference within 4.
- `ExportFlowTest.transformedPhotoExportMatchesThePreview`: a transformed JPEG exported at full size vs the editor's
  render: mean 0.06 levels, p99 1, max 2.
- `PipelineBenchmark.transformDragFrameTime`, 2400 x 1600, ms per frame with `glFinish`: Transform drag with lens and
  CA on (three Catmull-Rom samples per pixel) mean 1.13 / p95 1.64; distortion amount drag (the lens table rebuilt on
  the CPU each frame: 32 bisections over 64 border radii) 2.15 / 3.75; defringe amount 1.81 / 2.40; exposure for
  reference 1.24 / 1.66. All far below a 120 Hz frame (8.3 ms).

**On the phone** (Galaxy S24 Ultra, Adreno 750; M2/P, ADR 0012 "M2/P", same benchmark with a 2 s continuous warm-up;
"pipelined" = sustained ms per frame with two frames in flight as a display pipelines them, "GPU" = timer query per
frame, "latency" = `glFinish` after every frame):

| Drag (2400 x 1600 proxy) | M2/D latency p50 | M2/P latency p50 / p95 | M2/P pipelined | M2/P GPU p50 / p95 |
| --- | --- | --- | --- | --- |
| Transform vertical, lens + CA, 1:1 into 2400 x 1600 | 27.8 | 13.9 / 14.8 | 8.71 | 6.30 / 8.58 |
| Transform vertical, lens + CA, fitted to 1080 x 720 (the editor) | - | 3.1 / 4.1 | 1.04 | 0.66 / 1.18 |
| lens distortion amount, fitted | - | 5.0 / 11.8 | 2.34 | 1.06 / 1.08 |
| defringe purple amount, fitted | - | 9.8 / 12.4 | 2.98 | 2.41 / 4.58 |

Per pass (GPU timer queries, 1:1): the warped output pass is the whole frame (three Catmull-Rom samples of 16 texel
fetches per pixel at 3.84 MP); the CPU side was 4.3 ms per frame of `DevelopParams` rebuilt because the geometry
changed (now kept: no parameter depends on the geometry, `DevelopParamsTest`), now 1.0 ms. A shared 5 x 5 fetch for
the three CA channels (25 fetches instead of 48, the same values) was measured and rejected: 8.6 ms GPU p50 against
5.5 for three separate samples on the Adreno (the weight selection cost more than the fetches saved). The editor
never draws 3.84 MP through the warp: its output pass covers the photo's rectangle on screen (at most the photo area,
about 0.8-1.6 MP) and zoom from 50 % shows tiles, so a Transform drag in the editor costs about a millisecond.

## Alternatives not taken

- Separate passes for lens distortion and the Transform: a second resampling softens the image and costs a full
  pass; folding both into slot 13 resamples once.
- Lens geometry at slot 3 (before every pixel operator): it would need neighbourhood operators to work on a moving
  grid and the export tiles to carry a lens-shaped apron; at slot 13 the pixel operators run on the camera's own grid.
- EXIF focal length in the Transform: more physical, but the same recipe would mean different things on two photos and
  Upright already solves for the result.
