# ADR 0013: Tone curve and colour tools

Status: accepted (M2/B). Date: 2026-11-11. Amends ADR 0002 (slots 8 and 9, `PIPELINE_VERSION` 7) and ADR 0003
(additive recipe fields).

The tone curve (Light), the colour mixer (HSL), colour grading and black & white (Colour), with the targeted
adjustment and Auto mix. CPU reference in `cpu/Curves.kt` and `cpu/ColourTools.kt`, GLSL in `gl/ColourToolShaders.kt`
(its own `Tools` uniform block, binding 5), sampling and Auto mix in `auto/ColourSampling.kt`, recipe fields in
`recipe/ColourTools.kt`, slider binding in `recipe/ColourToolControls.kt`.

## Where they sit

| Slot (ADR 0002) | Operator | Domain |
| --- | --- | --- |
| 8 | contrast, whites, blacks (ADR 0007), then **tone curve** | display-referred, per channel, sRGB-encoded axes |
| 9 | vibrance/saturation (ADR 0008), then **colour mixer** *or* **black & white**, then **colour grading** | OkLab |

- **Tone curve after contrast/whites/blacks**: the curve is the user's last word on tone, as in every raw converter;
  the sliders shape the range first and the curve refines it. It is display-referred like the other slot 8
  operators (it needs a bounded 0..1 range to have "black", "white" and a histogram behind it).
- **Mixer after vibrance/saturation**: the global sliders set the overall colourfulness, the bands refine it.
- **Black & white replaces vibrance/saturation and the mixer** (they would only change the weights of a grey mix):
  the treatment is a profile-level switch (`profile.treatment`, copied with the Profile group) and its grey mixer
  (`colour.greyMixer`) lives with the colour fields. The colour mixer's values stay in the recipe and come back when
  the treatment returns to colour.
- **Grading last**, so a black & white picture can be toned (split toning is grading on a grey image).

With any colour tool on, slot 9 runs as **one OkLab round trip** per pixel (`ColourStageOp`: vibrance/saturation,
then the mixer or B&W, then grading, in Lab; the conversions between them cancel), instead of one per operator; without
a tool, vibrance/saturation runs exactly as before (ADR 0008). Measured on the phone: the HSL drag went from 12.9 to
10.4 ms per frame.

All are identity at their defaults and skipped (bit-exact identity, tested on CPU and GPU), so a recipe without them
renders as before; `PIPELINE_VERSION` moved to 7 once for the new operators (a sixth golden CRC pins them).

## Tone curve

`CurveOp`, per channel, on linear Rec.2020 values encoded with the **sRGB transfer function** (odd-extended for
negatives): `e = sign(v) oetf(|v|)`, `y = lut_c(e)`, `v' = sign(y) eotf(|y|)`. The axes are the encoded values so the
curve's 0..255 readout and the histogram behind it (the displayed photo's, sRGB-encoded) line up for greys; the
working primaries stay Rec.2020 (no gamut is lost). Outside 0..1 the curve continues with slope 1 (out-of-gamut and
above-white values stay ordered and finite).

**Per channel**, like the point curves of Lightroom, Capture One and darktable's RGB curve: an S-curve adds
saturation with contrast, and the R/G/B curves move the colour balance by tone (the classic cross-processing use). A
hue-preserving (luminance) curve would be a different tool; the colour grading wheels cover tone-dependent colour.

**Composition**: `channel curve ∘ RGB point curve ∘ parametric curve`, baked into 512-entry tables per channel on the
CPU (double, rounded once; `CurveLut`): `table`, the curve on the encoded axes (what the editor draws, and the
slope-1 extension), and `linear`, the **whole operator** `decode(curve(encode(v)))` sampled at `v = u²`,
`u = i/511`. In 0..1 the operator is a lerp of `linear` at `u = sqrt(v)`: one square root per channel instead of two
transfer-function `pow` (measured on the phone: the curve drag went from 12.2 to 10.2 ms per frame); the square-root
spacing puts half the samples below linear 0.25, where the encoded curve changes fastest. Outside 0..1 the encoded
path with the slope-1 extension runs. The operator is within 1e-3 (encoded, a third of an 8-bit level) of the exact
composition, and `table` within 2e-4 of the splines (4e-3 for a near-step between two points 0.04 apart;
`CurvesTest`). The GPU reads the same floats from a 512x1 RGBA32F texture with `texelFetch` (no filtering).

**Point curves** (`MonotoneSpline`): monotone piecewise cubic Hermite through the points (Fritsch & Butland 1984,
"A method for constructing local monotone piecewise cubic interpolants", SIAM J. Sci. Stat. Comput. 5(2)): interior
tangents are the weighted harmonic mean of the neighbouring secants (0 where the data turns), end tangents the end
secants. Every tangent lies in Fritsch-Carlson's monotonicity square, so the curve is monotone wherever the points
are, **never overshoots a point** (no clipped ringing near black or white, unlike a natural cubic spline or
Catmull-Rom) and is C1. Flat beyond the first and last point (black/white point clip). 2..16 points per curve; the
empty list (or the two corners) is the straight line. Presets: Linear, Medium contrast `(0,0) (32,22) (64,56)
(128,128) (192,196) (255,255)`, Strong contrast `(0,0) (32,16) (64,50) (128,128) (192,202) (255,255)` (8-bit
coordinates, the common raw-converter values).

**Parametric curve** (`ParametricCurveShape`): splits `s1 < s2 < s3` (default 25/50/75 %, at least 5 % apart and from
the ends) define four regions. Each region slider moves one knot at the middle of its region,
`c = s1/2, (s1+s2)/2, (s2+s3)/2, (s3+1)/2`, vertically by `d = a * 0.45 * gap` with `a` in -1..1 and `gap` the
distance to the neighbouring knot in the direction of the move (right when lifting, left when lowering; 0 and 1 are
fixed). Neighbours then always keep at least `0.1 gap` of vertical distance, so the monotone spline through
`(0,0), knots, (1,1)` is **strictly increasing for every slider combination** (tested on all 625 combinations of
-100/-50/0/50/100 at four split settings): black stays black, white stays white, the curve cannot fold.
Shadows +100 lifts encoded 0.125 to 0.2375 (about +1.7 stops at that tone), highlights -100 lowers 0.875 to 0.7625.

## Colour mixer (HSL)

**Bands** (`HueBands`): 8 centres in OkLab hue, the hues of typical colours of each name: red 25°, orange 55°,
yellow 105°, green 140°, aqua 195°, blue 255°, purple 295°, magenta 335° (sRGB red is 29°, orange 53°, skin 45-65°,
yellow 110°, foliage 135°, green 142°, cyan 195°, sky 250°, blue 264°, purple 294°, magenta 328°). OkLab, as for
vibrance/saturation (ADR 0008), because its hue lines are close to constant perceived hue: a band is the same colour
name at every lightness.

**Band shape**: a per-band value becomes a function of hue by **smoothstep interpolation between neighbouring
centres**: between `c_k` and `c_k+1`, `t = (h - c_k) / gap_k`, `s = t² (3 - 2t)`, `V(h) = (1 - s) V_k + s V_k+1`.
Why this shape:
- the two weights are a **partition of unity**: moving every band by the same amount equals one global change, and
  a hue exactly between two bands takes half of each;
- `V` is **C1** in hue (no kink a gradient could show as an edge) and stays between the two neighbours' values (no
  ringing, unlike a periodic cubic spline through the 8 values);
- each band reaches exactly to its neighbours' centres and no further: a band's influence is local, so a sky
  adjustment does not leak into skin. Raised-cosine or Gaussian lobes were rejected: they either do not sum to one
  (uneven strength between bands) or overlap three bands at once.

Per pixel, with `L, C, h` of the pixel and everything read at the **input** hue:
```
h' = Hue(h)                         periodic monotone Hermite through (c_k, c_k + shift_k), Fritsch-Butland tangents
C' = C (1 + S)                      S <= 0   (-100 greys that band)
C' = C (1 + S / (1 + (C/0.2)²))     S > 0    (saturated colours grow less; same soft curve as saturation, ADR 0008)
L' = L 2^(0.4 Lum g(C))             g(C) = q / (1 + q), q = (C / 0.06)²
```
- **Hue**: band centre `k` moves towards the next band (positive) or the previous one (negative) by 45 % of that gap
  (red +100 = +13.5° towards orange, -100 = -22.5° towards magenta). Neighbours keep their order (at least 10 % of
  the gap), and the map through the moved centres is a monotone Hermite, so the **hue map is strictly increasing:
  a hue gradient never folds, reverses or breaks** whatever the eight hue sliders are (tested on all alternating
  ±100 patterns and 400 random settings at 0.05° resolution). Adding the interpolated shift to `h` directly
  (`h + V(h)`) folds as soon as two neighbours shift towards each other.
- **Saturation**: a chroma scale, hue and lightness kept; `C'` stays increasing in `C` for every slider position.
- **Luminance**: a gain on OkLab `L` (±100 = ×2^±0.4, about ±1.2 stops of luminance) with chroma unchanged, faded
  out for near-neutrals by `g(C)` (half at C 0.06, 0.86 at a typical saturated colour), because a grey has no
  reliable hue and noise in a grey wall must not flicker between bands. Neutrals are exactly untouched.

Every term is continuous in `(L, C, h)`, including at `C = 0`, so gradients stay smooth (`ColourToolsTest`: on a hue
sweep with strong hue, saturation and luminance settings, neighbouring pixels 0.5° apart stay within 4° of hue and
0.01 of lightness).

### Targeted adjustment

A vertical drag on the photo changes the bands under the finger (up = more), for the property chosen (hue,
saturation, luminance; or the grey mixer in B&W). The colour under the finger is read where the drag **starts** from
`SourceColours`: Auto's 512 px analysis sample rendered on the CPU reference with the recipe **without** the mixer,
grading and B&W (cached until something before them changes), averaged over 3x3 sample pixels. Reading the colour
before the mixer means a hue already moved still picks its own band, and a B&W photo still knows its colours. The
drag splits its change between the two bands with the operator's own weights (`HueBands.weights`), so dragging on an
orange halfway between red and yellow moves both by half: exactly "more of what the operator does at this hue".
0.5 slider units per dp (100 units over 200 dp), whole units, one history step on release; colours under OkLab
chroma 0.02 have no band and do nothing. The panel follows the band being adjusted and the hint names it.

## Black & white

`BlackWhiteOp`: the grey is the pixel's **OkLab lightness** (a neutral pixel keeps its value exactly; greys are
perceptually even), brightened or darkened per source colour by the grey mixer with the same band interpolation:
`L' = L 2^(0.5 G(h) g(C))`, output `OkLab(L', 0, 0)` (±100 = about ±1.5 stops of luminance). OkLab lightness over
Rec.2020 luminance: luminance renders saturated blues and reds much darker than they look; lightness is closer to
perceived brightness and leaves the creative choice to the mixer. The Monochrome *profile* (ADR 0008) stays as a
look; the treatment is the tool with a mixer.

### Auto mix

`AutoBlackWhite.mix(SourceColours)`, deterministic, plain slider values. A straight lightness conversion merges
colours of similar brightness (a red flower on green leaves); Auto spreads the greys of the photo's main colours
apart, with a mild warm-up/cool-down (the yellow-orange filter of black & white film: warm colours a little lighter,
blues a little darker, which also separates warm/cool pairs of equal lightness):

1. Per band, over the sample: share `s_k` (pixels weighted by band membership and `g(C)`), mean grey in stops
   `l_k = 3 log2 L`, mean chroma weight.
2. Bias `β_k = 0.25 cos(c_k - 60°)` stops; biased greys `b_k = l_k + β_k`.
3. Main colours: `s_k >= 2 %`; their weighted mean `b̄`, offsets `d_k = b_k - b̄`, spread `σ`.
4. If `σ` is under 0.6 stops, stretch: `κ = min(0.6 / max(σ, 0.05) - 1, 3)`, target `T_k = b̄ + (1 + κ) d_k`.
5. Value `v_k = 100 (T_k - l_k) / (1.5 ḡ_k)`, limited to ±60; other bands get the bias alone.

Tested: a red and a green of equal lightness end up more than half a stop apart (red lighter); an already well
separated photo gets the bias only; a grey photo gets the bias only. On the CC0 samples (sheets below) Auto lifts
warm foliage/skin bands by 14-34 and lowers blues by about 16.

## Colour grading

`ColourGradingOp`, in OkLab, tonal masks on `x = clamp(L, 0, 1)`:
```
shadows    = 1 - smoothstep(q1 - w, q1 + w, x)
highlights = smoothstep(q2 - w, q2 + w, x)
midtones   = 1 - shadows - highlights            (a partition of unity)
q1 = p - 0.25, q2 = p + 0.25, p = 0.55 - 0.2 balance          (mid grey is L 0.565)
w  = 0.05 + 0.2 blending  for the tints,   0.25 for luminance
L' = L 2^(0.2 (Σ mask_r lum_r + lum_global))                  (±100 = about ±0.6 stops)
(a, b) += L' (Σ mask_r t_r + t_global),  t_r = 0.15 sat_r (cos hue_r, sin hue_r)
```
- The tint is a fixed OkLab chroma vector **per unit of lightness**: it keeps the same strength relative to the
  pixel everywhere and black stays black (an additive tint on black would make coloured "black"). Saturation 100 on
  mid grey is chroma 0.085 (strong); 20-30 is the usual split-tone range. Hue is the OkLab hue in degrees, the same
  angle the wheel shows (counter-clockwise from the right).
- **Blending** widens the tint transitions (0: 0.05, nearly hard splits; 100: 0.25, the three regions overlap fully
  at the pivot). **Balance** moves the pivot: positive balance makes more of the picture "highlights".
- Luminance uses **fixed wide masks** (`w = 0.25`) whatever the blending: the masks' slope is then at most 3, so
  `L'` stays increasing in `L` for every combination of the four luminance sliders (`1 - ln2 0.2 6 > 0`, tested on all
  81 extreme combinations with every blending and balance); with blending-dependent masks a hard split plus opposite
  luminance on two regions would fold the tone scale.
- Wheels with only a hue and no saturation change nothing (the operator is skipped).

## GPU and parity

`gl/ColourToolShaders.kt` mirrors the CPU line for line; constants (band centres, gaps, the operators' exponents) are
the CPU's floats printed into the GLSL; arrays are indexed by integers from `floor` and loops (ES 3.0); `atan(y, x)`
for the hue (no derivatives). The `Tools` block holds 22 vec4; the curve table is a 512x1 RGBA32F texture on unit 3
(`uCurve`, uploaded only when the curve changes). A first version kept the table in the block (8.5 KB, within ES 3.0's
16 KB block size), but SwiftShader/ANGLE counts block members against `GL_MAX_FRAGMENT_UNIFORM_VECTORS` (261) and
refused to link the develop shader; a texture has no such limit on any driver. Both renderers (`GpuDevelopRenderer`,
`TiledRenderer`) own a `ColourToolShaders.Gl` (block + texture);
the develop shader calls `toneCurve` after the global tone and `colourTools` after vibrance/saturation.

Parity (`GpuParityTest`, emulator on the host GPU: NVIDIA RTX 4080 through the emulator's GLES 3.1 translator): the
colour tools add a hue-sweep image, 10 extreme recipes (curve presets, a steep channel curve, the parametric extremes,
every mixer property at alternating ±100, B&W with the grey mixer at its ends, every wheel at full strength with hard
blending, Camera match + B&W + curve + split tone) and 12 random tool recipes to the existing set. They use the
colour tolerance of ADR 0008, **multiplied by the tone curve's steepest slope** (at least 1): an fp16 rounding of the
scene texture before the curve comes out multiplied by the curve's slope, so a point pulled hard against its
neighbour (slope 7 in the extreme set) widens the error by the same factor; that is the curve's own sensitivity, not a
CPU/GPU difference. Measured: see the PR and the table below; the orchestrator re-measures on the phone.

| Quantity | Tolerance | Emulator (NVIDIA via translator) | **Phone (Adreno 750, GLES 3.2)** |
| --- | --- | --- | --- |
| Linear stage, every recipe incl. the tool recipes | per class (ADR 0002/0007/0008); tools: colour x curve slope (x1.5 on the noise image) | worst 0.80 of tolerance, mean abs error 2.6e-4 | worst 0.90, mean abs error 3.5e-4 |
| 8-bit output, sRGB / Display P3, whole set | 6 levels for colour/tool recipes, x curve slope; mean <= 0.1 | max 6 / 4 levels, mean 0.037 / 0.035 | max 10 / 9 levels (the slope-7 extreme on the noise image), mean 0.051 / 0.049; 1038 / 360 of 14.6 M values off by more than 1 |

The 8-bit allowance scales with the curve's slope like the linear one (the steep extreme reaches 9-10 levels on the
noise stress image on Adreno); colour tools on the noise image get 1.5x the linear colour tolerance (measured 1.13x
on Adreno: saturated noise with near-zero luminance and extra cube roots). Before the slope scaling, a random recipe
with an unrealistic zig-zag curve (slopes about 40) missed by 15x; the random tool recipes now draw curves the way
people do (1-3 monotone interior points) and the steep and folded cases live in the extreme set.

**Saturated images and the Light regression recipe #10** (+3 EV, whites +74, display-referred): on the new `hueSweep`
image it reaches 5 levels on Adreno and on the NVIDIA emulator, like ADR 0007's noise image, so `hueSweep` shares the
noise image's 6-level tone allowance. On the NVIDIA emulator `srgbChart` also exceeds its 2 levels for that recipe on
`main` (not on the phone); no colour tool is involved.

## Cost

Per pixel the develop pass adds, when on: two `pow` per channel and three table reads (curve), one OkLab round trip
plus an `atan`, a `sin`/`cos` pair and two `exp2` (mixer or B&W), one OkLab round trip and one `exp2` (grading).
`PipelineBenchmark` (2400x1600, every pass + mipmaps + output pass, `glFinish` after each frame), mean / p50 / p95:

| Drag | Emulator (NVIDIA RTX 4080 via translator) | **Phone (Adreno 750)** |
| --- | --- | --- |
| exposure (reference) | 0.85 / 0.80 / 1.13 ms | 8.38 / 8.10 / 10.36 ms |
| light (all Light sliders) | 0.85 / 0.80 / 1.15 | 8.68 / 8.50 / 10.06 |
| colour (Camera match + vibrance/saturation, no tools) | 0.86 / 0.80 / 1.19 | 10.79 / 10.74 / 12.13 |
| **curve**: RGB point dragged over a parametric + red channel curve, Light sliders on (table rebuilt and uploaded every frame) | 0.92 / 0.88 / 1.22 | 10.22 / 9.40 / 13.38 (before the sqrt table: 12.17 / 12.80 / 14.03) |
| **HSL**: orange hue dragged with saturation/luminance on other bands, grading, Camera match | 0.98 / 0.93 / 1.24 | 10.37 / 10.11 / 12.45 (before the fused round trip: 12.87 / 12.84 / 14.17) |

Phone numbers vary run to run by about 2 ms (the unchanged colour drag measured 7.38 ms in the previous run): the
`glFinish` per frame serialises CPU and GPU, and the phone warms up over the run. All drags stay well inside 60 Hz
(16.7 ms); at 120 Hz (8.3 ms) even the exposure drag is at the limit in this benchmark, so the editor's real
pipelined frame rate (`EditorMeasurementsTest`) is the measure to watch there. `curveDragFrameTime` and
`hslDragFrameTime` log under `REVEAL_BENCH`.

## Editor

- **Tone curve** sub-panel (Light → Tone curve, opens the tall panel): Point curve / Parametric; channel chips RGB,
  Red, Green, Blue; the square editor with the displayed photo's histogram behind the curve (luminance for RGB, the
  channel's own for R/G/B; the channel curves show faintly behind the RGB curve). Tap adds a point on the curve (so
  nothing changes until it moves), a drag moves the nearest point with the finger's movement (relative, so the finger
  never hides the point) or adds one where it started, dragging a point off the square removes it, double-tap resets
  the channel; a readout `In 128 → out 150` inside the editor (it never reflows the panel under the finger). Parametric:
  a vertical drag in a region moves that region's slider, plus the four region sliders and three split sliders.
  Presets for RGB. One history step per gesture ("Point curve (RGB)", "Curve shadows +20").
- **TalkBack**: every point is an accessibility node ("Point 2", "In 64 → out 80") with Move up/down/left/right
  (one 8-bit level) and Delete actions; the editor has Add point and Reset curve actions. Wheels say their hue and
  saturation and have hue ±5°, saturation ±5 and reset actions; every value also has a slider.
- **Colour mixer** sub-panel: 8 swatches (a dot marks edited bands), hue / saturation / luminance of the selected band
  on gradient tracks drawn in OkLab (`OkLabColors`, the same hues as the operator), the targeted adjustment toggle and
  its property chips.
- **Colour grading** sub-panel: 3-way (three wheels with their luminance sliders) or one large wheel per region
  (shadows, midtones, highlights, global) with hue, saturation and luminance sliders; blending and balance. Wheels
  drag relative to where the thumb lands, double-tap resets a wheel.
- **Treatment** chips (Colour / Black & white) at the top of the Colour panel; in B&W vibrance and saturation are
  disabled and the Colour mixer row becomes the B&W mix (grey sliders per colour, Auto, targeted adjustment).

## Sources

Fritsch & Carlson 1980 ("Monotone Piecewise Cubic Interpolation", SIAM J. Numer. Anal. 17); Fritsch & Butland 1984
(SIAM J. Sci. Stat. Comput. 5); Ottosson 2020 (OkLab); IEC 61966-2-1 (sRGB transfer function).

## Screenshots

Before/after sheets on the public CC0 samples (`docs/screenshots/colour-tools/`, `ColourToolsRenderSheetTest`): as
opened, a tone curve, the colour mixer, colour grading, B&W with Auto mix, and B&W with a split tone, for the Sony
ZV-E10, Nikon D5600, Canon EOS R6 and Google Pixel 4a samples. Panel goldens: `app/src/test/screenshots/editor/`
(`phone_tone_curve*`, `phone_colour_mixer*`, `phone_grading*`, `phone_bw*`, `landscape_*`).
