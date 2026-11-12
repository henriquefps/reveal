# ADR 0022: Masks and local adjustments

Status: accepted (M4/A). Date: 2026-11-12. Amends ADR 0002 (slot 10 "Local adjustments / masks"), ADR 0003 (the
recipe's `masks[]`), ADR 0009 (export tiles carry the masks), ADR 0012 (zoom tiles, Detail's passes) and ADR 0014
(Effects' final pass). Builds on ADR 0021 "Masks in the recipe (M4)" (AI masks are a model reference, never pixels) and
ADR 0007 (the low-resolution grid). `PIPELINE_VERSION` is unchanged: a recipe without masks renders bit for bit as
before (the golden CRCs of `CpuDevelopTest` did not move).

Code: `:core:pipeline` `recipe/Masks.kt` (model), `mask/` (stroke codec, dabs, CPU rasterisation, `MaskParams`, the AI
source interface), `cpu/LocalAdjustOps.kt` (the local operators' CPU reference), `gl/MaskShaders.kt` and
`gl/MaskPasses.kt` (GPU), and the existing operators' slots (`CpuDevelop`, `DetailOp`, `Shaders.DEVELOP_FRAG`,
`DetailShaders`). The editor UI is M4/B; the models are M4/C (`:core:ml`).

## Decision in one paragraph

A mask is a list of **components** (linear and radial gradients, brush strokes, colour range, luminance range, AI)
combined in order (add / subtract / intersect, each optionally inverted) into a **coverage** in 0..1 per pixel, in
upright image coordinates before crop and transform, and a set of **local adjustments** scaled by the mask's amount.
Coverage is evaluated once per mask definition into a float16 texture (one RGBA16F array layer per four masks) and
cached; brush strokes are rasterised deterministically (no hardware blending) into their own float16 layers and a stroke
in progress only re-rasterises the bins its new dabs touch. Every local adjustment is **the global operator it mirrors,
at a per-pixel amount**: the shader that already runs that operator adds `Σ coverage_i · local_i` to the global slider
value at each pixel (temperature/tint/exposure as a gain at the start of the develop pass, highlights/shadows and
contrast/whites/blacks inside their existing functions, texture/clarity/dehaze/sharpening/noise in Detail's passes), and
one OkLab step at slot 10 (after the colour stage) applies local saturation, hue and colour tint. So a local slider drag
re-runs exactly the passes a global drag of the same slider re-runs, no mask is re-rasterised, and the only extra
full-resolution passes are the coverage passes (when a mask's shape changes) and, for colour/luminance range masks, one
develop pass without local adjustments when a global slider changes.

## The recipe (additive, ADR 0003)

`Recipe.masks: List<Mask>`; old recipes (`"masks": []`, or `{"id": ""}` objects of the placeholder) decode unchanged.

```json
{"id": "6f1c…", "name": "Sky", "enabled": true, "amount": 100,
 "adjustments": {"exposure": -0.7, "dehaze": 25, "colourHue": 220, "colourSaturation": 15},
 "components": [
   {"type": "linear", "x0": 0.5, "y0": 0.0, "x1": 0.5, "y1": 0.45, "feather": 100},
   {"type": "ai", "op": "intersect", "model": "deeplab-ade20k@1", "kind": "SKY", "prompts": [],
    "refine": {"radius": 16, "eps": 0.01}, "invert": false},
   {"type": "brush", "op": "subtract", "strokes": [{"points": "AAEC…", "size": 0.04, "feather": 50, "flow": 100}]}
 ]}
```

- **Mask**: `id` (uuid, stable), `name`, `enabled` (the "visible" eye: false renders as if the mask were absent and keeps
  its definition), `amount` (0..100 %, scales every local adjustment of the mask: the mask's opacity), `adjustments`,
  `components`.
- **Positions** are fractions of the upright image's width and height (`x`, `y` in 0..1, y down; a gradient's or a
  stroke's points may lie outside). **Lengths** (radii, brush size) are fractions of the image's **long edge**, so a
  circle stays a circle and a stroke has the same shape in the proxy, the zoom tiles and the export. All shapes are
  evaluated in the isotropic plane `u = x W / L`, `v = y H / L` (`L = max(W, H)` of the image being rendered), at pixel
  centres. Masks follow crop, straighten, turns, flips, lens corrections and the Transform through `GeometryMapping`
  like every pixel operator: they are evaluated before slot 13.
- **Component** (`type` discriminator), each with `op` (`add` default, `subtract`, `intersect`) and `invert`:
  - `linear`: `x0, y0` (full effect) to `x1, y1` (none), `feather` 0..100;
  - `radial`: centre `cx, cy`, radii `rx, ry` (long-edge fractions), `rotation` (degrees, clockwise), `feather`
    0..100, `inside` (true = the effect inside the ellipse);
  - `brush`: `strokes`, each `points` (encoded, below), `size` (diameter, long-edge fraction), `feather` 0..100,
    `flow` 0..100, `erase`;
  - `colourRange`: `samples` (1..5 OkLab colours `{l, a, b}` picked on the image before local adjustments), `range`
    0..100, `smoothness` 0..100;
  - `luminanceRange`: `low`, `high` (OkLab L in percent, 0..100), `falloff` 0..100;
  - `ai`: exactly ADR 0021 (`model` = `<id>@<version>`, `kind` as the model's kind name, `prompts` `[{x, y}]`,
    `refine` `{radius, eps}` or null, `invert`).
- **Local adjustments** (`LocalAdjustments`, every default 0 = no change; `LocalAdjustment` enumerates them with their
  ranges for the editor): `temperature`, `tint` (−100..+100, relative), `exposure` (−4..+4 EV), `contrast`,
  `highlights`, `shadows`, `whites`, `blacks`, `texture`, `clarity`, `dehaze`, `saturation`, `sharpness`, `noise`
  (−100..+100), `hue` (−180..+180 degrees), `colourHue` (0..360) with `colourSaturation` (0..100) — the colour tint
  swatch. Moiré and defringe are not local (out of scope, as the brief says).

**Brush strokes are vectors, compact.** `points` is a string: base64url (no padding) of LEB128 varints of the zigzag
deltas of `(x, y, pressure)`, quantised to `x, y` × 32768 (a 6000 px image gets 0.18 px steps) and pressure × 255,
first point absolute. A hand-drawn 5,000-point stroke is under 24 KB of JSON (`StrokeCodecTest`; about 4 characters a
point) instead of ~200 KB as `{x, y, p}` objects. `BrushStroke.of(points, …)` also simplifies the polyline
(Ramer-Douglas-Peucker, tolerance 2 % of the dab radius, a point is kept when its pressure leaves the interpolated one
by more than 1/32): the editor calls it when a stroke ends; while the finger moves it appends raw points so the dab list
only grows (below).

**History** (`HistoryField`, group `MASKS`): `MASK_ADD`, `MASK_REMOVE`, `MASK_RENAME`, `MASK_TOGGLE`, `MASK_SHAPE`
(components), `MASK_ADJUST` (adjustments or amount) and `MASK_ORDER`, each with the mask's name as the value;
`history_mask_*` in both languages. Masks are matched by `id`.

**Presets**: masks are not presettable (`PresetFields.EXCLUDED` keeps `masks`): a mask's shape belongs to one photo, and
the preset format has no way to say "the sky of the target photo" yet. A later ADR can add AI-mask presets (a component
list without geometry, re-evaluated per photo).

**Copy/paste**: `RecipeGroup.MASKS` already existed; the chooser's default (`RecipePaste.DEFAULT_GROUPS`) now leaves
masks out like geometry, so masks are pasted only when chosen. The paste sheets list every group, so no UI change was
needed; M4/B decides whether "Masks" needs a per-mask choice.

**Sync**: unaffected; the recipe JSON already travels (`:core:sync` tests pass unchanged). AI masks are recomputed per
device (ADR 0021).

## Coverage: components and their combination

Each component gives `v` in 0..1 at a pixel centre; `sstep(e0, e1, x)` is GLSL's smoothstep with `e0 >= e1` read as a
step at `e1` (hard edges at feather 0), the same code on both sides (`MaskMath.sstep`).

| Component | Value |
| --- | --- |
| Linear | `t = (q − p0)·(p1 − p0) / |p1 − p0|²`, `v = 1 − sstep(0.5 − f/2, 0.5 + f/2, t)`, `f` = feather / 100 (100: the whole band is the transition, like Lightroom's default); zero-length = 0 |
| Radial | `q' = R(−θ)(q − c)`, `d = |(q'x / rx, q'y / ry)|`, `v = 1 − sstep(1 − f, 1, d)`; outside = `1 − v` |
| Brush | the stroke raster (below) |
| Colour range | `d = min over samples sqrt(0.25 ΔL² + Δa² + Δb²)` on the pre-local OkLab pixel, `r = 0.01 + 0.19 range/100`, `v = 1 − sstep(r (1 − s), r, d)`, `s` = smoothness / 100 |
| Luminance range | `x = clamp(L, 0, 1)` of the pre-local OkLab pixel, `v = sstep(lo − f, lo, x) (1 − sstep(hi, hi + f, x))`, all / 100 |
| AI | the source's mask, bilinear with explicit fp32 weights at `(px + 0.5) W_m / W − 0.5` (taps clamped), clamped to 0..1 |

Then `v = 1 − v` when `invert`, and in order from `cov = 0`: **add** `cov = max(cov, v)` (union: two overlapping 50 %
gradients stay 50 %), **subtract** `cov = cov (1 − v)`, **intersect** `cov = cov v` (Lightroom's "intersect with" of a
range: it multiplies). So a first component that subtracts or intersects gives 0; binary inputs give the boolean truth
tables (`MaskRasterTest`). The coverage is rounded to float16 (`q16`) as stored. **Range masks are evaluated on the image
before local adjustments** (slot 9's output in OkLab, float16 as the develop pass writes it), never on what other masks
did; "restricted by the components before them" is simply an `intersect` range after them.

**Brush raster.** Strokes become dabs (`mask/Dabs.kt`): along the polyline in the isotropic plane, one dab at the first
point, then every `0.15 × diameter` of arc length (at least half a rendered pixel), pressure interpolated;
`radius = size/2 · k(p)` and `flow = flow/100 · k(p)` with `k(p) = 0.25 + 0.75 p` (a finger or mouse sends 1). A dab's
alpha is `a = flow (1 − sstep(1 − f, 1, d / radius))`; per pixel, in dab order, a painting dab does
`cov = cov + (1 − cov) a`, an erasing dab `cov = cov (1 − a)`; the result is rounded to float16 once. The GPU evaluates
exactly this per pixel (`MaskShaders.BRUSH_FRAG`) from a dab texture (RGBA32F) and per-bin index lists (64 x 64 pixel
bins, built on the CPU), never with blending: the result is deterministic and equals the CPU's within float rounding,
whatever the driver. Appending points only appends dabs (placement is prefix-stable), so the renderer re-evaluates only
the bins the new dabs touch (`MaskPasses`, scissored), then the coverage of that layer in the same rectangle.

**Precision: float16 (RGBA16F layers), not R8.** The pipeline already requires renderable float16 and rounds stored
intermediates explicitly (`q16`, ADR 0012); 11 bits of mantissa give 0.0005 steps near full coverage where R8 gives
0.004 (a ±4 EV feathered gradient would step by 0.016 EV, visible as banding on skies), and R8 would need the driver's
rounding of float writes, which the parity rules avoid. Memory: 31 MB per four masks at a 2400 x 1600 proxy, 32 MB per
layer at a 2048 export tile.

**AI masks** (`mask/AiMaskSource.kt`): the engine never runs a model. It asks `AiMaskSource.mask(request, onReady)`
with the photo key, the `Ai` component and the size of the image being rendered; the source returns an `AiMask`
(`width x height` floats in 0..1 covering the whole upright image, any resolution) or null (not ready: the component
contributes 0, i.e. `invert` makes it 1) and calls `onReady` when it has it, and the renderer then renders again. The
source owns model inference, its cache (ADR 0021) and the edge refinement (`refine` travels with the component); the
engine samples what it gets. M4/C implements it in `:core:ml`, M4/B wires it into the renderers
(`GpuDevelopRenderer.setMaskSource`, `TiledRenderer.render(…, masks)`); the edited thumbnails and preset thumbnails
render without a source until then (AI components contribute nothing there).

## Local adjustments: the global operator at a local amount

Per pixel the **local sum** `S = Σ_i q16(cov_i) · amount_i/100 · adjustments_i` is formed in mask order over the
rendered masks (enabled, at least one non-zero adjustment, at most `MaskParams.MAX_MASKS` = 32; overlapping masks add,
like stacked adjustments). Where a mask needs a value the global recipe gives (`global`), the **total** is
`clamp(global + S, range)` with the global slider's range, and the operator is evaluated with it. A pixel whose local
sum for an operator is 0 takes the global operator's own pre-computed constants (and skips an operator that is off
globally), so **pixels outside every mask are bit-identical** to the render without masks, on the CPU and on the GPU.

| Local adjustment | Where (slot) | Same maths as |
| --- | --- | --- |
| Temperature, tint | start of the develop pass (scene-linear, before slot 5b) | the display-referred relative white balance (ADR 0008, `WhiteBalanceModel.relativeCct`): a Bradford adaptation from the pretended light to D65 in linear Rec.2020, as `A⁻¹ diag(g(t, n)) A`; `g` is a 21 x 21 table over the two sliders (step 10, Double, interpolated bilinearly with explicit weights on both sides) |
| Exposure | same place, `2^ev` | `ExposureOp` (global exposure is a gain on the same scene-linear data, slot 4) |
| Highlights, shadows | slot 5b, `LocalToneOp` | the same tone curve on the base layer with per-pixel stops; the base is the global guide's, shifted by the local gain's log-luminance change (exact where the mask is flat, so the scene texture and the guide never depend on masks) |
| Contrast, whites, blacks | slot 8, `GlobalToneOp` | the same curve with per-pixel constants |
| Saturation | slot 10 (new step) | `VibranceSaturationOp.gain` with the local value (cascaded after the global colour stage) |
| Hue | slot 10 | a rotation of the OkLab hue by the local angle (lightness and chroma kept) |
| Colour tint | slot 10 | the colour grading global wheel (`ColourGradingOp`: `(a, b) += L' · TINT · sat (cos h, sin h)`) |
| Noise | slot 11, Detail's luminance levels | the per-level threshold `τ_i = unit_i · (N/100)^1.5` with the total luminance amount `N` (the smoothing chain does not depend on amounts, ADR 0012 "M2/P", so only the shrinkage changes); a pixel whose total is 0 keeps its input `L` exactly |
| Sharpness | slot 11, the final pass | total amount `global + local` (0..150) sharpens with the global radius, detail and masking; below 0 it **softens**: `L = mix(L, G, min(−total/100, 1))` towards the sharpening Gaussian |
| Texture, clarity, dehaze | slot 12, the final pass | `EffectsParams` with the per-pixel total |

**Why local white balance and exposure act scene-referred, and why at the start of the develop pass.** Lightroom's local
exposure behaves like a change of exposure (highlights roll off through the tone curve instead of clipping), which needs
scene-linear light before the profile; doing it after the tone curve would hard-clip at white. The scene pass is the
only earlier place, but putting masks there would make the scene texture and the local-tone guide depend on every mask
(re-running two grid passes per local drag, and making range masks circular). At the start of the develop pass the
data is the same scene-linear light (exposure is a scalar gain that commutes with nothing in between), and the guide
needs only the shift `Δ = log2 Y' − log2 Y` added to the base: exact for a smooth mask, at worst a soft halo of the base
filter's size across a hard mask edge, which is where Lightroom's local exposure also behaves like a local gain.

**Cost.** No extra full-resolution pass per frame for any local slider drag. A develop pass gains a loop over the masks
(one coverage fetch per four masks and a few multiply-adds per mask) and, where a pixel is masked, the operators' own
maths. The stages that run only when a mask needs them (Light guide, Detail levels, blurs, the Effects grid) run as if
the global slider were on. Extra passes happen only when the masks' shapes change (brush bins, one coverage pass per
four masks) and, with a colour or luminance range, one develop pass without local adjustments (into its own OkLab
texture) when a global parameter changes; both are cached otherwise.

## Everywhere the same

- **Preview** (`GpuDevelopRenderer`): `MaskPasses` keeps the brush layers, the AI textures (by request), the pre-local
  OkLab texture (range masks only) and the coverage array, each keyed by what it depends on; `UploadedImage.params`
  keeps the global constants across a mask drag (`DevelopParams.withMasks`).
- **Zoom tiles** (`ZoomTiles` → `TiledRenderer.developTile`) and **export** (`TiledRenderer.render`): per tile (and per
  grid pre-pass tile when the Effects grid or a range mask needs the develop output) the brush bins of that region, the
  pre-local develop when a range mask exists, the coverage of the region, then the develop pass with masks and Detail.
  Coverage is pointwise, so masks add no apron of their own; `DevelopParams.reach(recipe)` includes the Detail and
  texture neighbourhoods that local sharpness, noise and texture switch on, so `ExportPlan.of(…, reach)` covers them.
- **Edited thumbnails** (`GpuEditedThumbnails`) and **preset thumbnails** (`PresetThumbnails`) render through
  `GpuDevelopRenderer`, so they show masks (AI components once M4/B gives them a source).
- **CPU reference** (`CpuDevelop.renderLinear(…, masks)`): the same order, the same float16 roundings.

## Overlay (for M4/B)

`GpuDevelopRenderer.setMaskOverlay(MaskOverlay(maskId, mode, colour, opacity))`: the output pass reads the mask's
coverage layer at the image point it shows (explicit LOD 0, bilinear; display only) and either blends `colour` (sRGB
`0xRRGGBB`, linearised) at `opacity · coverage` over the photo (`COLOUR`) or shows the coverage alone as grey, displayed
value = coverage (`GREYSCALE`), like Detail's Masking drag. The overlay mask's coverage is kept even when its
adjustments are all 0 or it is disabled (a new mask shows its shape before any slider moves). The post-crop vignette and
grain are off while an overlay is in greyscale mode. Nothing of it reaches the recipe, the histogram or an export.

## Parity and tests

CPU goldens per component and per adjustment on `SyntheticImages` (`MaskRasterTest`, `LocalAdjustOpsTest`), the
combination truth tables, codec round trip and backward compatibility (`MasksCodecTest`, `StrokeCodecTest`), the
history steps (`RecipeDiffTest`). On the GPU, `GpuParityTest#masksMatchCpuReference` runs a recipe per component type and
per local adjustment within ADR 0002's tolerances (the range masks' smoothstep edges are steep: a float16 difference of
the pre-local pixel flips a few pixels' coverage, so their recipe takes the Detail tolerance), `TiledRendererTest`
exports a masked recipe in tiles like the preview (no seams), `ZoomTilesTest` develops masked tiles like the CPU, and
`PipelineBenchmark#maskSliderDragAndBrushAppend` measures a five-mask recipe (one of each type, a 2,000-dab brush): a
local slider drag's frame time and the time to append dabs to a stroke in progress.

## Consequences

- `PIPELINE_VERSION` stays 10 (no change without masks).
- `RecipePaste.DEFAULT_GROUPS` no longer contains `MASKS`.
- Open for M4/B: the panel, mask list, pins and handles, the brush's auto-mask (a stroke flag that multiplies each dab
  by a colour similarity to the dab centre, evaluated on the pre-local image like the range masks), whether paste needs
  per-mask choices, and history coalescing of stroke-in-progress updates. Open for M4/C: `AiMaskSource` in `:core:ml`.
