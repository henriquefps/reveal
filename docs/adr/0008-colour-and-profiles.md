# ADR 0008: Colour and profiles

Status: accepted (M1, Colour). Date: 2026-11-11. Amends ADR 0002 (slot 6 replaces the M0 Standard curve, slot 9,
`PIPELINE_VERSION` 4).

The profile slot (base looks: Standard, Neutral/Flat, Vivid, Portrait, Landscape, Monochrome, **Camera JPEG match**,
amount 0-200 %), the white-balance modes (presets, As shot, Auto, Custom/eyedropper, relative WB for display-referred
input) and vibrance/saturation. CPU reference in `cpu/ToneCurve.kt`, `cpu/ProfileLooks.kt`, `cpu/CameraMatchZvE10.kt`,
`cpu/ColourOperators.kt`, `cpu/OkLab.kt`, `cpu/WhiteBalanceModel.kt`, `cpu/AutoWhiteBalance.kt`; GLSL in
`gl/ColourShaders.kt` (its own `Look` uniform block, binding 3).

## The problem this fixes

M0 rendered a RAW's scene-linear values almost unchanged (identity up to 0.8, then a shoulder). Camera JPEG engines lift
mid-tones a lot: on the owner's ZV-E10 pairs the camera puts a raw value of 0.06 (Reveal's linear Rec.2020 after the
as-shot white balance) at display mid grey 0.18. M0's default render was therefore **13.8 L\* darker** than the
camera's JPEG on average (mean CIEDE2000 12.5, see "Calibration"), the "1.5 EV darker and flatter" the owner saw.

## Where it sits

| Slot | Operator | Domain |
| --- | --- | --- |
| 1-4, 5, 5b | WB, reconstruction, matrix, exposure, highlights/shadows (ADR 0002/0007) | scene-linear |
| **6** | **profile**: gain, matrix, per-channel curve, matrix back, hue treatment | scene -> display-referred |
| 8 | contrast, whites, blacks (ADR 0007) | display-referred |
| **9** | **vibrance, saturation** | display-referred, OkLab |

On the GPU slots 5b-9 are the develop stage (`Shaders.DEVELOP_FRAG`): `localTone`, `profileLook`, `globalTone`,
`vibranceSaturation`. The profile is **scene-referred input only** (ADR 0002): for display-referred input it is the
identity, so a JPEG still round-trips through the default recipe (tested within 1 level on CPU and GPU). Vibrance,
saturation and the relative white balance apply to every input.

## Profile model

```
v = pre * rgb                 pre = look matrix * 2^gain (into the curve space)
v = curve(v) per channel      16-knot monotone Hermite spline in log2 x (ToneCurve)
v = post * v                  curve space back to Rec.2020
v = HueTreatment(v)           OkLab: hue-dependent chroma gain and hue rotation (order-3 Fourier)
```

- **Curve** (`ToneCurve`): knots at `x = 2^-10 .. 2^5`, one stop apart, values `y` (display-linear) and tangents
  `m = dy/dlog2 x`; linear toe below `2^-10` (C1 there, `m0 = y0 ln 2`), flat above the last knot (tangent 0).
  `limited` enforces Fritsch-Carlson monotonicity (values non-decreasing and below 1, `a² + b² <= 9`), so any blend
  or extrapolation of two curves stays monotone. **Negative inputs map to 0** (noise below black; colours outside
  the curve space's gamut, as a camera clips to its output gamut): the profile never outputs negative luminance,
  which luminance-driven tone operators (ADR 0007) cannot handle stably. The same spline serves every look, so the
  GPU evaluates one function from 16 `vec4` (log2, floor, Hermite).
- **Per-channel curve**, not luminance-preserving: bright saturated colours desaturate towards white instead of
  clipping to a flat hue (filmic), which is what camera engines do and what the calibration fits best.
- **Designed looks** use the `Filmic` family in stops around grey, `s = log2(x/0.18)`, `y = 0.18 * 2^F(s)`:
  `F = c s / (1 + (c s / A)^n)^(1/n)` for `s > 0` (shoulder towards `0.18 * 2^A`), `F = c s (1 + toe q/(1+q))`,
  `q = -s`, for `s <= 0` (the log slope deepens from `c` to `c (1 + toe)`). C1 at grey; tangents are the
  function's own, so the spline follows it within float precision of its shape.
- **Hue treatment** (`HueTreatment`): `gain(h) = max(0, 1 + c0 + c1 cos h + c2 sin h + ... + c6 sin 3h)`, the same
  series for a hue rotation `dh(h)`; `L` and neutrals are untouched; `cos kh, sin kh` come from `a/C, b/C` (no
  atan2). A raised-cosine lobe `amp ((1 + cos(h - h0))/2)^3` (FWHM 108°) is exactly an order-3 series
  (`HueTreatment.lobe`), which is how designed looks write "skin -10 %" or "greens +25 %".

### The looks

| Look | Tone | Colour |
| --- | --- | --- |
| **Standard** (default) | gain +1.313 EV, contrast `c` 1.134, toe 0.078 (all fitted to the camera); shoulder `n` 5 and white `A = log2(1/0.18)` designed | neutral (colorimetric) |
| Neutral/Flat | `c` 0.95, `n` 3, no toe | chroma -10 % |
| Vivid | `c` x1.12, toe +0.10 | chroma +22 % |
| Portrait | `c` x0.95 | chroma -4 %, skin (OkLab 55°) another -10 % |
| Landscape | `c` x1.06 | greens (140°) +25 %, blues (255°) +20 % |
| Monochrome | `c` x1.08 | channel mix 0.3227 R + 0.6180 G + 0.0593 B (Rec.2020 luminance with 0.06 moved from green to red: lighter skin, mild yellow-filter feel) |
| **Camera JPEG match** | fitted 16-knot curve in **sRGB primaries**, gain +1.645 EV | fitted 3x3 + hue treatment |

Standard's shoulder is designed, not fitted: the camera's JPEG patches stop below level 250, so the fit cannot see the
top (it found a hard shoulder towards 0.958, i.e. never brighter than level 250). With `n = 5` and the asymptote at
display white the sensor clip lands at about 0.98 and the last stop of RAW highlights stays separable instead of
flattening at the camera's clip.

**Amount** `t = amount/100` (0-2): `pre`, `post` and the curve knots blend `std + t (look - std)` (then `limited`),
hue coefficients are `t * look`. 0 % is exactly Standard (tested), 200 % extrapolates. Monochrome's colour stops at
100 % (`2 mix - I` would invert colours); its contrast continues to 200 %. Standard ignores the amount (ADR 0003).
Camera match's curve space differs from Standard's (sRGB primaries vs Rec.2020): the blend of `pre`/`post` is a smooth
path between the two, exact at both ends.

### Default profile: Standard

The default stays **Standard**, now carrying the camera's brightness and contrast. Reasons: the recipe default
(`profile.base = standard`, ADR 0003) does not change, so no stored recipe changes meaning; Standard works for every
camera, while Camera JPEG match's colour is the ZV-E10's taste (its 3x3 sits after each camera's own matrix, so on
other cameras it is "Sony-like", not a match); and the owner's main complaint, brightness, is fixed by Standard
(mean L\* difference to the camera +0.01). The rest of Standard's difference to the camera is colour: Standard is
colorimetric, the camera is more saturated in reds/greens. For an owner who compares with his camera JPEGs, **Camera
JPEG match is one tap away** and is what the planned Settings "default profile" (product.md) should offer; making it
the default is a one-line change (`Profile.base`'s default) plus a recipe-default decision, left to the owner.

## Calibration (Camera JPEG match)

**Data:** copies of the owner's 31 ZV-E10 RAW+JPEG pairs from two shooting days (17 + 14), Sigma 30 mm F1.4 DC DN,
in-camera JPEG settings as shot (the camera applies its picture profile, DRO and lens corrections). Copies only; they
were deleted after the run; nothing from them is in the repository (only the fitted parameters below and aggregates).

**Tooling** (`scripts/calibration/`, opt-in): `calibrate.sh <work dir> <folders...>` copies the pairs, decodes each ARW
with `rawdump.sh` (the vendored LibRaw built for the Mac with **the app's own decode parameters**: camera RGB, no WB,
no matrix, no curve, half size), runs `CameraMatchCalibrationTest` (JVM, skipped without `REVEAL_CALIBRATION_DIR`) and
deletes the copies. The test maps each RAW through `PipelineInputMapper` and `DevelopParams` (the app's own as-shot
multipliers, saturation and camera matrix), so the fit sees exactly Reveal's linear render.

**Patches:** the JPEG is the RAW's centred crop (confirmed per pair by a ±4 px search on log-luminance gradients);
16x16 JPEG px blocks are kept when the JPEG is smooth there (luma std <= 2.5 levels: robust to misalignment and the
camera's sharpening), unclipped (JPEG 3-250, RAW below 90 % of the clip) — 1 355 412 usable patches, 1 500 sampled per
pair (46 500).

**Fit:** Levenberg-Marquardt on per-patch CIEDE2000 (Sharma, Wu & Dalal 2005) between the model's sRGB render and the
JPEG, CIELAB D65. Camera match: gain, 3x3 with rows summing to 1 (white stays white), 16 knot values (monotone by
construction: logistic of a cumulative sum of exponentials), 14 hue coefficients; weak regularisation (curve maps
grey to grey, smooth knots, small hue coefficients). Hold-out by session: fit on one day, measure on the other, both
ways; the shipped constants are fitted on all pairs and re-measured through the real float operators.

**Model choice** (prototype on the same samples, hold-out by session, mean ΔE2000):

| Model | Train | Hold-out |
| --- | --- | --- |
| curve only (no matrix) | 1.88 | 2.72 |
| 3x3 + parametric `Filmic` curve | 1.27 | 1.53 |
| 3x3 + free 16-knot curve | 0.91 | 1.32 |
| 3x3 + free curve + order-3 hue treatment, curve in Rec.2020 | 0.77 | 0.98 / 1.10 |
| **3x3 + free curve + order-3 hue treatment, curve in sRGB primaries** | 0.74 | **0.95 / 0.94** |

A 3x3 alone leaves a hue-dependent chroma error (yellow-greens +2-3 chroma, purples -1.5, hue shifts up to 7°): the
camera's colour engine is not linear. The order-3 hue treatment (14 numbers, smooth, monotone-safe, free on the GPU
next to the OkLab round trip) removes most of it; a 3D LUT would add a texture and overfit 31 pairs of mostly two
scenes. The curve in the camera's own output primaries generalises better than in Rec.2020.

**Results** (CALIB lines of the final run; ΔE2000, 46 500 patches unless stated):

| Render vs the camera JPEG | Pairs | Mean | Median | p95 | Per-pair mean | Mean ΔL\* |
| --- | --- | --- | --- | --- | --- | --- |
| M0 default (knee 0.8, no gain) | 31 | 12.46 | 11.69 | 23.24 | 8.15-17.88 | -13.81 |
| **Standard** (shipped) | 31 | 2.25 | 1.93 | 4.97 | 1.48-3.34 | +0.01 |
| **Camera JPEG match** (shipped, all pairs) | 31 | 0.79 | 0.71 | 1.64 | 0.65-0.91 | +0.02 |
| Camera match (shipped), day 1 / day 2 only | 17 / 14 | 0.77 / 0.82 | 0.70 / 0.71 | 1.53 / 1.76 | | |
| Camera match, hold-out day 1 -> day 2 (fit on 17, measured on 14) | 14 | 0.95 | 0.87 | 1.86 | 0.85-1.09 | -0.27 |
| Camera match, hold-out day 2 -> day 1 (fit on 14, measured on 17) | 17 | 0.94 | 0.78 | 2.16 | 0.73-1.39 | +0.50 |

A ΔE2000 of 1 is about a just-noticeable difference side by side. What is left is mostly the camera's local tone (DRO)
and lens shading, which a global look cannot model.

Fitted constants (`CameraMatchZvE10.kt`): gain +1.64467 EV; `M` rows (1.33774, -0.506418, 0.168674),
(-0.237985, 1.21731, 0.0206748), (0.0915767, -0.362695, 1.27112); the 16 knots and 14 hue coefficients are in the file.
Re-run after a decoder or white-balance change (both move the input of the fit).

## White balance

- **Presets** (`WhiteBalanceModel.presetXy/presetCct/preset`), CIE 15:2004 chromaticities on this model's
  temperature/tint scale: Daylight = D55 (5501 K), Cloudy = D65 (6505 K), Shade = D75 (7511 K) on the CIE daylight
  locus; Tungsten = illuminant A (2854 K, on the locus); Fluorescent = F2 cool white (4224 K, green side: negative
  tint); Flash = daylight locus at 5900 K (5899 K). Daylight-locus lights sit slightly above the Planckian locus, so
  their tint is a little negative. RAW only; display-referred input offers As shot, Auto and Custom.
- **As shot**: `null` temperature/tint (ADR 0003); `asShot(input)` gives the values the sliders show.
- **Custom / eyedropper** (`neutralFromSample(input, rgb)`): `rgb` is the input's own linear pixel (or area mean). A
  RAW neutral's raw response is the light's, so the light is `cameraToXyz * rgb`; its temperature/tint make the sample
  neutral (tested to 0.5 % in kelvin, 0.5 tint, rendered neutral within 0.2 %). Display-referred: the sample's
  chromaticity is the pretended light and the relative mapping below is inverted.
- **Relative WB for display-referred input** (`relTemperature`, `relTint`, -100..+100): a Bradford (von Kries in
  sharpened cone space) adaptation in linear light from a pretended light to the file's white. `+relTemperature` makes
  the picture warmer: the pretended light is `0.75 * relTemperature` mired cooler than D65 (+100 = 12 690 K, -100 =
  4 371 K; mired steps are perceptually even). `+relTint` makes it magenta: the pretended light is `relTint` tint units
  greener (100 = 0.033 Duv). Both 0 is exactly the identity. RAW input ignores the relative pair and display-referred
  input the absolute one (ADR 0003).
- **Auto** (`AutoWhiteBalance.analyse(input) -> WbAnalysis`, `estimate`, `whiteBalance`): pure functions on a 192 px
  analysis copy of the input's own linear pixels (kept small so the editor can hold it after the proxy upload),
  clipped blocks flagged. Steps: usable blocks (unclipped, above the darkest 2 %); shades of grey p = 4 (Finlayson &
  Trezzi 2004) blended with white patch (brightest 3 %); **grey pixels**: blocks whose own colour is a plausible light
  (2100-14000 K, |Duv| <= 0.01) are probably neutral surfaces, their median in mired and tint is the main estimate
  (90 %; medians, so a bright sky or a lawn cannot dominate); **skin guard**: blocks with a skin chromaticity under the
  current estimate (OkLab hue 30-75°, chroma 0.04-0.15 at L about 0.7) are excluded and the estimate repeated, when they
  cover 3-50 % of the frame; result limited to 2000-15000 K, tint ±30. The output is plain slider values
  (`mode = AUTO`; temperature/tint for RAW, the relative pair otherwise).
  Tested on synthetic scenes under 2800-9000 K lights: within 4 mired and 1 tint; a 40 % skin portrait: the guard
  halves the error; a large clipped sky is ignored; a one-colour scene stays a plausible light. On the public samples:
  ZV-E10 indoor 6306 K (as shot 5998 K), Nikon D5600 sunny street 6046 K (as shot 5592 K). Shades of grey alone gave
  12 900 K on the street (sky and grass), which is why grey pixels lead.

## Vibrance and saturation (slot 9)

A chroma scale in **OkLab** (Ottosson 2020) on linear Rec.2020: `L` and hue kept exactly, so no hue shift and no
brightness change (tested: hue within 2e-3 rad, L within 1e-4). OkLab over CIELAB/JzAzBz: near-constant perceived hue
along its hue lines (CIELAB bends blues to purple), cheap (two 3x3, three cube roots), exact inverse, defined from
XYZ so it applies to our working space; JzAzBz is HDR-oriented and costlier for no gain at display range. The OkLab
`M1` is row-normalised so our D65 white (from xy, ADR 0002) is LMS (1, 1, 1) exactly (Ottosson's matrix assumes a
5th-digit-different D65, which left greys with 1e-4 chroma).

```
fs = 1 + s                          s <= 0  (-100 = grey)
fs = 1 + s / (1 + (C/0.2)²)         s > 0   soft: saturated colours grow less (gamut)
w  = (1 - min(C/0.25, 1))²          vibrance weight: muted colours most, saturated not at all
p  = 1 - 0.6 ((1 + cos(h - 55°))/2)^4   for v > 0: skin hues protected
C' = C fs (1 + v w p)
```

Chroma stays increasing in the input chroma at every slider position (the order of colourfulness never flips).
Out-of-gamut: the soft saturation curve keeps strong colours from blowing out; what still leaves Rec.2020 is clipped by
the output transform (a perceptual gamut mapping is later work, ADR 0002).

## GPU and parity

`gl/ColourShaders.kt` mirrors the CPU line for line; constants (OkLab matrices, skin hue) are the CPU's floats printed
into the GLSL. Explicit LOD / `texelFetch` only (no derivatives); arrays indexed by an `int` from `floor` (ES 3.0).
Parity (emulator, Apple M4 Pro via the emulator's GLES 3.0 translator), `GpuParityTest` with every look at 100/200 %,
vibrance/saturation extremes, relative WB and random recipes covering every field: the scene texture between the two
develop stages is fp16 (ADR 0007), and Camera match's matrix (coefficients up to about ±6 at 200 %) plus the OkLab
cube roots amplify that one rounding. Colour recipes therefore get their own documented tolerance: linear
`1e-5 + 8e-3 * max|channel|` (`4e-2` relative where reconstruction also runs), 8-bit output at most 6 levels. Measured:
worst 0.90 of the tolerance, mean abs error 3.3e-4 (the cap moved from 3e-4 to 4e-4 because Standard renders 1.3 EV
brighter and absolute errors grow with the values), 8-bit max 6 levels, 381 of 8.3 M values off by more than 1, mean
0.05 levels. Before the negative-input clamp of the curve, a saturated clipped highlight under Camera match produced
negative luminance and Light's global tone amplified the fp16 difference to 82 levels; the clamp removed it.

Benchmark (`PipelineBenchmark.colourDragFrameTime`, 2400x1600, Camera match 120 % + light sliders + vibrance drag,
`glFinish` per frame): mean 2.10 ms, p50 2.09, p95 2.36, max 2.49 (exposure drag 1.28 ms, light drag 1.87 ms on the
same run). Re-measure on the phone.

## PIPELINE_VERSION 4

The M0 Standard curve is replaced (RAW output changes for every recipe); vibrance/saturation and the relative WB are
new operators (identity at their defaults). `CpuDevelopTest` pins three CRCs: the scene with exposure/WB, the Light
recipe, and a colour CRC (every look at 140 %, vibrance/saturation, relative WB on the sRGB chart).

## Sources

CIE 15:2004 (daylight locus, illuminants A, D55/D65/D75, F2); Krystek 1985 (Planckian locus, ADR 0002); Lam 1985 /
Lindbloom (Bradford); Judd 1960 (mired); Finlayson & Trezzi 2004 (shades of grey); Ottosson 2020 (OkLab); Sharma, Wu &
Dalal 2005 (CIEDE2000); Fritsch & Carlson 1980 (monotone cubic interpolation).
