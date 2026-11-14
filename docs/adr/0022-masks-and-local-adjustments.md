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
Coverage is evaluated once per mask definition into a float16 texture (one RGBA16F layer per four masks) and
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

**Brush options (M4/B fields, engine M4/P).** A stroke has `density` (0..100, default 100), `pressure`
(`both` default, `flow`, `size`) and `autoMask` (default false); all additive (old strokes read the defaults and render
bit for bit as before, `BrushOptionsTest`). With `D = density / 100` a painting dab does `cov += max(0, D - cov) a` (it
moves the coverage towards `D`, never lowers it) and an erasing dab `cov *= 1 - a D` (it removes at most that share);
`D = 1` is exactly the M4/A formula. `pressure` says what `k(p)` scales: `both` the radius and the flow (M4/A), `flow`
the flow only (the radius and so the spacing stay at `size`), `size` the radius only. A dab is 8 floats (`Dabs`:
centre, radius, inner; flow, erase, density, auto), two RGBA32F texels on the GPU.

**Brush auto-mask.** A dab of an `autoMask` stroke only covers pixels of a colour close to the one under its centre:
`a = a · (1 − sstep(AUTO_NEAR, AUTO_FAR, dist))` with the range masks' OkLab distance
`dist = sqrt(0.25 ΔL² + Δa² + Δb²)` between the pixel's pre-local colour and the dab's **reference colour**, the
pre-local pixel under the dab centre, `(clamp(floor(cx)), clamp(floor(cy)))` in the image being rendered
(`Dabs.AUTO_NEAR` = 0.03, `AUTO_FAR` = 0.09: a little tighter than a colour range at 50 / 50, so a stroke along an edge
stops at it but follows a gradient within a surface). It reads the same pre-local image as the range masks (so it
never depends on other masks), and an auto-mask brush makes `MaskPlan.needsPre` true. The reference colours are a
small texture (one texel per dab, `MaskShaders.DAB_COLOURS_FRAG`): the preview takes them from its pre-local texture
(the whole image); a tiled render (zoom, export) fills them in a pre-pass over every pre-pass tile (scene,
pre-local develop, then the dabs whose centre lies in that tile), like the Effects grid, because a dab's centre may lie
in another tile. CPU reference: `MaskRaster.dabColours` and `brush(…, pre, colours)`. An auto-mask layer depends on
the pre-local image, so a global drag re-rasterises it whole (as it re-evaluates a range mask).

**Precision: float16 (RGBA16F layers), not R8.** The pipeline already requires renderable float16 and rounds stored
intermediates explicitly (`q16`, ADR 0012); 11 bits of mantissa give 0.0005 steps near full coverage where R8 gives
0.004 (a ±4 EV feathered gradient would step by 0.016 EV, visible as banding on skies), and R8 would need the driver's
rounding of float writes, which the parity rules avoid. Memory: 31 MB per four masks at a 2400 x 1600 proxy, 32 MB per
layer at a 2048 export tile. **Layers are cells of one 2D texture**
(side by side, then in rows, within `GL_MAX_TEXTURE_SIZE`; `MaskPasses.LayerGrid`), for the coverage, the brush rasters
and the AI masks alike, never a 2D array texture: the Android emulator's GL translator rejects a 2D array wider than its
3D limit (2048), and the editor's proxy is 2400-3000 px wide. The shaders add the layer's cell origin to their
`texelFetch`es; the overlay clamps its bilinear sample half a texel inside the cell.

**AI masks** (`mask/AiMaskSource.kt`): the engine never runs a model. It asks `AiMaskSource.mask(request, onReady)`
with the photo key, the `Ai` component and the size of the image being rendered; the source returns an `AiMask`
(`width x height` floats in 0..1 covering the whole upright image, any resolution) or null (not ready: the component is
**skipped**, so an inverted AI component never turns the whole frame on while its model runs) and calls `onReady` when
it has it; the renderer then renders again (the zoom tiles develop again). The renderer asks every frame, so `mask` must
be a cheap cache lookup and must tolerate repeated calls while a mask is pending. The
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
  OkLab texture (range masks only) and the coverage layers, each keyed by what it depends on; `UploadedImage.params`
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

**Measured** (M4/A, the Android emulator on an Apple M4 Pro Mac, arm64, `EMULATOR_GPU=host`: **not the phone**;
the phone numbers come later). `GpuParityTest#masksMatchCpuReference` (nine mask recipes on four images): worst error
0.14 of the tolerance, 8-bit difference at most 3 levels. Coverage on the GPU vs the CPU: at most 4.9e-4 (AI), 2.4e-4
(radial, combined), 3e-5 (brush). Masked export: 256 px tiles bit-identical to one tile, 1 level from the preview (16
values) and 3 from the CPU; masked zoom tiles at 100 %: 1 level from the CPU (mean 0.07), bit-identical to the export.
`PipelineBenchmark#maskSliderDragAndBrushAppend` (2400 x 1600 proxy shown 1080 x 720, five masks, a 2,467-dab brush):

| Drag | Pipelined ms / frame | Latency p50 ms (glFinish per frame) |
| --- | --- | --- |
| A mask's exposure (develop, Effects grid and Detail chain re-run) | 6.05 | 7.30 |
| A mask's clarity (only the Detail final pass and its blurs) | 1.37 | 3.07 |
| A mask's noise (shrinkage, blurs, final pass) | 2.69 | 3.64 |
| Global exposure, the same five masks on | 7.93 | 9.16 |
| The same global recipe without masks | 0.70 | 1.75 |
| A stroke in progress, 5 points a frame (six masks) | — | 10.14 (p95 11.03); brush + coverage update 0.91 (p95 1.18), 4.4 bins a frame |

`everyToolOnSliderDrags` (no masks) is unchanged: every slider meets the target (0.22-1.63 ms pipelined). The emulator's
host GPU is far faster than the S24's Adreno 750, so the ratios matter more than the milliseconds; the phone run is
the orchestrator's.

## Speed (M4/P)

The orchestrator measured M4/A on the owner's Galaxy Tab S9 Ultra (Adreno 740; `PipelineBenchmark
#maskSliderDragAndBrushAppend`, 2400 x 1600 proxy fitted to 1080 x 720, five masks, every Detail and Effects stage
some mask switches on): a global exposure drag 27.3 ms per frame pipelined (2.5 without masks), a mask's exposure
21.9, noise 10.3, clarity 5.1; a stroke in progress p50 46 / p95 84 ms (serialised). The emulator showed 1.4-8 ms for
the same: never trust emulator frame times here.

**Profile before** (GPU timer queries, `-e profile true`, ms per pass at the profiler's lower clock; mask exposure drag):
develop pass 13.3 (2.7 without masks), Effects grid 2.5, Detail luminance chain 4.4, shrinkage 5.3, blurs 3.0, final
pass 7.2. The first section after the masks' preparation also cost 10-12 ms of **CPU** per frame. Where it went:

1. **CPU, every frame**: `MaskPlan.of` was keyed on the whole masks (adjustments included), so every adjustment drag
   rebuilt the plan and every brush's dabs (2,467 dabs) and compared them; the coverage key held the strokes, so a
   stroke in progress always redrew every coverage layer whole (the "only the changed bins" path never ran).
2. **Adjustment fetches**: `localSum` read each mask's five-vec4 vector from a texture per pixel: 25-30 texel fetches
   per pixel with five masks, in the develop pass, the shrinkage and the final pass (about 3 ms of the develop pass).
3. **Everything downstream re-ran for a local change**: a stroke or a mask's slider changes a part of the image, but
   the develop pass, the Effects grid, the luminance chain, the shrinkage, the blurs and the final pass ran over the
   whole image.
4. **Global drags with range masks** re-ran the pre-local pass and all coverage layers (only the range masks read it).

**Changes** (no pixel changes: the golden CRCs, `GpuParityTest`, `TiledRendererTest` and `ZoomTilesTest` are unchanged
or within their tolerances; partial updates are bit-identical to a fresh render, `MaskRenderTest
#partialUpdatesEqualAFreshRender`):

- The plan is keyed on the masks' ids and components only, and the preview keeps the dabs per stroke list (a stroke
  in progress recomputes only its own). The coverage key leaves the strokes out (their layers report the bins that
  changed), so a stroke redraws its bins and the coverage in their rectangle.
- The adjustment vector is reordered (`MaskParams.V_`: rows 0-2 for the develop pass, rows 3-4 for Detail's) and served
  from two small uniform blocks, `MaskDevelop` (32 x 3 vec4) and `MaskDetail` (32 x 2 vec4), uploaded only when their
  contents change; the coverage is fetched once per layer of four masks. (Sizes keep the develop shader's uniform
  blocks under ES 3.0's 224-vec4 minimum, for SwiftShader.)
- **Partial stage-1 updates.** When only the coverage changed inside a rectangle (a stroke in progress), or only some
  masks' adjustments changed (a drag of a mask's slider: the box their coverage can reach, `MaskBounds`), the develop
  pass runs scissored to that rectangle and every Detail and Effects pass to the rectangle grown by their reach
  (`DevelopParams.reach`); the Effects grid updates the row segments of the cells it touches (`EffectsPasses.update`)
  and then the final pass runs whole (dehaze's atmospheric light is global). Outside the rectangle every input is
  unchanged, so what is kept is exact; two details make it so: each luminance level's horizontal pass (one target
  shared by the levels) runs wider by its vertical taps, and the half-resolution colour levels (two ping-pong targets)
  run whole. Not used with defringe, the sharpening-mask view, or when the view switches between level 0 and level 1.
- Range masks: a global change re-evaluates only the masks that read the pre-local image (other channels kept with
  the colour mask; layers without such a mask skipped).
- **Fused range coverage.** In the preview a coverage layer that holds a colour or luminance range computes the
  pre-local colour itself (`MaskShaders.FUSED_COVERAGE_FRAG`: `Shaders.developGlobal`, the develop pass's unmasked
  path, then `q16(toOkLab(c))`, exactly what the separate pass stored) instead of reading a texture the pre-local pass
  wrote: one full-resolution pass and one RGBA16F texture fewer per global change (profiler clock: pre-local 3.5 +
  coverage 3.4 ms -> 5.8 ms fused; pipelined 19.1 -> 18.3 ms). An auto-mask brush still needs the pre-local texture
  (its dabs' reference colours and every pixel's), so with one the separate pass runs as before; tiles and the
  export keep it too.
- Profiling: `mask plan (CPU)` and `mask coverage` sections; the benchmark gained a reference drag (the same Detail and
  Effects stages on globally, no masks), an auto-mask brush, the stroke measured pipelined, and `-e only <text>`.

**After** (tablet, same benchmark, pipelined ms per frame and GPU per frame p50 / p95; "reference" = no masks, the
Detail and Effects stages the masks switch on set globally):

| Drag (tablet, Adreno 740) | main (M4/A) | M4/P pipelined | GPU per frame p50 / p95 |
| --- | --- | --- | --- |
| A mask's exposure (sky, linear: 55 % of the image) | 21.9 | **10.15** | 9.34 / 9.43 |
| A mask's noise | 10.3 | **6.50** | 5.87 / 7.15 |
| A mask's clarity | 5.1 | **3.26** | 2.72 / 5.57 |
| Global exposure, five masks on | 27.3 | **18.26** (19.14 before the fused range coverage) | 17.58 / 17.67 |
| Global exposure, an auto-mask brush too (no fusion: the brush reads the pre texture) | - | 24.38 | 23.02 / 23.09 |
| Reference: no masks, the same Detail and Effects stages on globally | - | 10.97 | 9.94 / 11.37 |
| Global exposure, masks off, no Detail/Effects (unchanged) | 2.5 | 2.89 | 2.36 / 5.07 |
| A stroke in progress, 5 points a frame, six masks | p50 46 / p95 84 (serialised) | **4.78** | 3.77 / 6.92 |
| The same stroke with the auto-mask | - | 4.81 | 3.75 / 6.88 |

Serialised (`glFinish` per frame, the GPU at a low clock), the stroke is p50 31 / p95 41 ms (was 46 / 84) of which
7 ms p50 is the GL thread's CPU for the brush and coverage update (dab binning and uploads, debug build).
`everyToolOnSliderDrags` (no masks) is unchanged and meets every target (1.4-6.3 ms pipelined, GPU p95 <= 10.8).
Profile with the uniform blocks, before the partial updates (mask exposure drag, profiler clock, whole image):
develop pass 12.9 against 4.6 for the reference's unmasked one; switching each masked operator off measured local tone
1.2, colour 0.7 and white balance 0.3 ms of GPU per frame, so what is left is the operators' own work. The partial
update then runs every pass of the drag over the mask's box only. Results are from the tablet; the phone was not used.

**Out of reach, and why.** A drag that changes the develop output of the whole image (a global slider with masks on)
must re-run the develop pass and every Detail and Effects stage over 3.84 MP. Without any mask, those stages alone
cost 10.8 ms pipelined on the Adreno 740: the 8.3 ms target is below what the same drag costs with no masks at all.
With masks the global drag adds the pre-local pass, the range masks' coverage and the masks' own operators (local
tone, white balance, colour: measured by switching each off, 1.2, 0.3 and 0.7 ms). The fusion of the pre-local pass
and the range coverage is done (above). The next lever is outside the masks and is a product decision, because it
changes the fit view's pixels: run the Detail stages (luminance chain, shrinkage, blurs) at half resolution when the
view reads level 1 only (ADR 0012 "M2/P", next levers). Estimate from the profile: those passes are about 13.5 of the
41.5 profiler-ms of the global drag, about 5-6 ms of its 18 pipelined; a quarter of the pixels would save roughly 3-4
ms (global drag about 14-15 ms, the no-mask reference about 7 ms), still above 8.3 for the global drag with masks.

## Consequences

- `PIPELINE_VERSION` stays 10 (no change without masks).
- `RecipePaste.DEFAULT_GROUPS` no longer contains `MASKS`.
- Open for M4/B: the panel, mask list, pins and handles, whether paste needs per-mask choices, and history coalescing
  of stroke-in-progress updates (answered in the amendment below). The brush's density, pressure mode and auto-mask
  are evaluated by the engine (M4/P, "Brush options" and "Brush auto-mask" above). Open for M4/C: `AiMaskSource` in
  `:core:ml`.

## Amendment: M4/B (editor)

Status: accepted (M4/B). Date: 2026-11-13. The Masks tool of the editor, its answers to the questions above, and the
recipe fields it added. Code: `:feature:editor` `masks/` (UI, gestures, the view model's half `MaskEditor`), `:core:data`
`mask/` (`AiMasks`, `MaskInputs`), `:feature:export` (AI masks in exports).

**Recipe additions (additive, `PIPELINE_VERSION` unchanged).** `BrushStroke.density` (0..100, default 100: a painting
dab moves the coverage towards `density/100`, `cov += max(0, d − cov) · a`; an erasing dab removes at most that share,
`cov *= 1 − a · d`), `BrushStroke.autoMask` (default false: each dab weighted by the colour similarity to the dab
centre on the pre-local image), `BrushStroke.pressure` (`both` default = M4/A's `k(p)` on radius and flow, `flow` or
`size`: what pen pressure scales; the editor writes `flow` by default) and `MaskComponent.Ai.autoSeeds` (default true:
the subject's automatic seeds with the taps; false = the taps alone, for "remove an object"). Strokes without the
fields render exactly as before. The engine side of density, auto-mask and pressure is M4/P's (`mask/Dabs`, the brush
raster; "Brush options" and "Brush auto-mask" above).

**The tool is a panel with a photo layer, not a mode.** ux.md drew Masks as a mode; in practice the panel needs the
whole card (the mask list, the parts, four groups of local sliders) and the tool strip stays useful (Light next to a
mask, Undo), so Masks is a `ToolBehaviour.Panel` that carries a `PhotoLayer`: gestures that see each pointer before
the canvas (handles, brush, eyedropper and subject taps take what they handle; everything else pans and zooms) and an
overlay drawn over the photo. Selecting another tool hides the handles and the overlay; the masks stay in the recipe.
The panel's reset is per mask ("Reset this mask's adjustments": every local slider to 0 and the amount to 100 %), not
a group reset that would delete every mask.

**Layout.** The masks are a horizontal row of 56 dp coverage thumbnails with "+" at the top of the panel (ux.md
"Masks"); the selected mask below: name (tap = rename), eye, overlay toggle, ⋯ (rename, duplicate, invert, move
left/right, delete with a confirmation), Amount, the parts (type icon, how it combines, AI status, invert, remove;
the selected part's op as chips), "Add / Subtract / Intersect" + a type, the selected part's own controls, then the
local adjustments grouped Light (exposure, contrast, highlights, shadows, whites, blacks), Colour (temperature,
tint, hue, saturation, colour hue with a hue track, colour strength with a saturation track), Effects (texture,
clarity, dehaze), Detail (sharpness, noise). An empty panel shows the choices directly. Phone portrait, landscape,
tablet side panel and docked panel use the same panel (only the strip changed in the other goldens).

**Creating.** AI kinds as `MaskService` offers them (Subject, Sky, Background; People opens People, Hair, Face skin,
Body skin, Skin, Clothes, Eyes, Irises, Lips, Teeth, Eyebrows), shapes (linear, radial, brush) and ranges (colour,
luminance). A new AI component records `MaskService.modelFor(kind)` and `RefineDefaults.of(kind)`; it is named after
its kind ("Sky", "Sky 2"), the others "Mask n" (lowest free number); at most `MaskParams.MAX_MASKS` (32) masks.

**AI masks.** `AiMasks` (`:core:data`, Hilt singleton over `MaskService`) gives each photo a `Session` that is the
renderer's `AiMaskSource`: a map lookup per frame; a miss starts the computation once (`MaskRequest` from the
component: kind, `model`, prompts, `autoSeeds || prompts empty`, refine) and every `onReady` given meanwhile is called
when the mask arrives. The editor keeps the proxy area-averaged to the model input's size at open (`MaskInputs
.downscale`, 8 MB) and renders the model input when a mask is first needed (`MaskInputs.render`: default recipe on the
CPU, sRGB, quantised to 8 bits, `MaskInputs.VERSION` = 1). The photo key of the masks cache is the model input's
content (`img:` + SHA-256 of its 8-bit pixels): no database lookup, the same photo gives the same key on any device,
and a hand-off without a library row works too. States per component: computing (a progress bar on the part and its
thumbnail; the first run compiles the model, about a second on the GPU), ready, failed (the mask is kept and renders
without the part; "Try again"), unavailable (a kind or model this app does not have: kept verbatim, skipped, said so;
never a crash). A new AI component is requested at once, before any slider makes the renderer ask. Exports: when a
rendered mask has an AI part, `ExportEngine` decodes a 2400 px proxy first (never on the heap with the full image),
renders its model input and passes a session to `TiledRenderer.render(…, masks)`; its key is its own input's content,
so an export may recompute a mask the editor made from its proxy (a slightly different decode). Edited and preset
thumbnails still render without AI parts (open: they would need a cache-only lookup).

**Handles.** All gesture and drawing maths is in the isotropic image plane (`MaskHandles`, JVM-tested) and reaches
the screen through `MaskView`: image fraction -> proxy pixel -> the geometry operator's plane through the inverse of
the lens and Transform warp (Newton's method on `Warp.source`, finite-difference Jacobian, 1e-3 px) -> output
(`GeometryMapping.toOutput`) -> slot through the zoom. So handles follow crop, straighten, turns, flips, lens
corrections, Transform and zoom. Linear: the pin moves it, the start and end lines move along the axis (the other line
stays, the feather keeps its share), the centre line turns it around the pin. Radial: pin, two radius handles each
way, a rotation knob above, the feather handle on the dashed inner ellipse; Inside/Outside chips. Grab radius 28 dp for
a finger, 16 dp for a pen; a hovering pen highlights the handle and names it in a tooltip.

**Brush.** Size (long-edge %), feather, flow, density, paint/erase, auto mask, and "Pen pressure: Flow / Size". A
stroke is drawn live: `BrushStroke.raw` of the points so far replaces the last stroke every move (historical pointer
positions included), so the dab list only grows and the renderer re-rasterises only new bins; on lift `BrushStroke.of`
(simplified) replaces it and the stroke is **one** history step (strokes in progress are never committed, which is the
coalescing ADR 0022 left open). A second finger cancels the stroke (the user is zooming). The pen's eraser end
(`TOOL_TYPE_ERASER`) and its held side button (`BUTTON_STYLUS_PRIMARY`) erase; once a pen is seen, fingers only pan
and zoom (`PhotoController.penSeen`, ADR 0016). The cursor shows the dab size and where the feather starts, under the
finger while painting and under the hovering pen.

**Ranges.** Colour range: a new one starts with the eyedropper on; a tap adds the mean OkLab colour of a 3 x 3 block
of the pre-local image (Auto's analysis sample rendered on the CPU with the recipe without masks, Detail, Effects and
geometry), at most five; Range and Smoothness sliders; Clear. Luminance range: Darkest / Brightest / Falloff, a bar
showing the band, and a luminance map (the pre-local lightness in grey, the band tinted, drawn through the geometry
without the small lens warp).

**Overlay.** The selected mask through `setMaskOverlay`: shown while a stroke, a handle drag or a mask slider is in
progress, and all the time when the overlay toggle (or `O`) is on; colour (six swatches) and opacity, or black and
white. Only while the Masks panel is the open tool.

**Invert (⋯ menu).** With one part it flips its invert flag. With several, De Morgan on the combination (the first part
inverted; a later `add v` becomes `subtract v`, `subtract v` becomes `add v`, `intersect v` becomes `add (1 − v)`):
exact for hard-edged or non-overlapping parts (`MaskEditsTest` checks `1 − coverage` on the CPU raster), within a
quarter where soft edges overlap (max vs product), and applying it twice gives the same coverage. A mask-level invert
flag would need the engine; this keeps M4/A's combination rules unchanged.

**History, copy and paste.** Create, delete, rename, show/hide, a part added/removed/recombined/inverted, a handle or
slider release, a stroke, a sample, a tap: one step each, labelled by `RecipeDiff` (`MASK_ADD`, `MASK_REMOVE`,
`MASK_RENAME`, `MASK_SHOW/HIDE`, `MASK_SHAPE`, `MASK_ADJUST`, `MASK_ORDER`). Undo, redo, the history list and reopening
restore masks; a selection that no longer exists is dropped. Paste: the chooser's Masks group **replaces** the
photo's masks with the copied ones (like every other group; idempotent, so pasting twice does not duplicate them);
masks stay out of the default groups (M4/A).

**Keyboard.** `M` opens the Masks tool; in it `[` / `]` resize the brush (about 15 % a step), `O` toggles the overlay,
`E` toggles erasing.

**Tests.** JVM: `MaskEditsTest` (edits, names, limits, invert exactness against `MaskRaster`), `MaskViewTest` (round
trips through crop/turn/flip/straighten and the Transform warp, handle drags and hit tests, pen pressure, the
luminance map), `AiMasksTest` (the session with a fake model: once, ready, unavailable, failed, retry; request
mapping; the model input and its key), `MasksCodecTest`. Goldens: `phone_masks*`, `landscape_masks`, `tablet_masks*`,
`tablet_portrait_masks`. Instrumented: `MasksFlowTest` (each hand-made type, history, reopen; subject, hair, eyes on a
public-domain portrait; sky on a public-domain landscape; a newer app's mask), `MaskPenTest` (injected stylus events:
pressure, eraser end, side button, fingers pan once a pen is seen).

## Amendment: M4/F (shared AI masks)

An AI mask is a function of the photo, the model and the component, never of the recipe's other fields, so it is made
once and read by every renderer. Three renderers ignored each other's work before M4/F: an export recomputed masks the
editor had cached, and thumbnails (library grid, filmstrip, presets) rendered AI parts as absent.

- **One key.** The masks cache's photo key is the file's content fingerprint (`MaskInputs.photoKey`, `FileFingerprint`)
  and the model input's size (`MaskInputs.size` of the upright proxy) goes into the key as before (ADR 0021 "Cache").
  The editor used `MaskInputs.contentKey` (a hash of the model input's pixels), which differs between proxies of the
  same file, so an export, whose proxy is 2400 px (the editor's is 2400-3000), never hit. `contentKey` remains for an
  input without a file (a smart preview the editor opens, a photo whose file cannot be read). A cache entry made under
  the old key is simply not found once; nothing else changes (`MaskInputs.VERSION` is not bumped: the input rendering is
  the same).
- **Look before rendering.** `MaskService.cached(MaskLookup)` reads the disk cache with no pixels (the key never reads
  them); `AiMasks.session(photoKey, size, input)` asks it first and calls `input` (the CPU render of the model input)
  only for a mask that is not cached. The export decodes a 2400 px proxy, area-averages it to the model input's size
  (about 8 MB) and passes that to the session; `AiMasks.Session.ready(components)` suspends until every part is READY,
  FAILED or UNAVAILABLE. The model input's size can differ by a pixel between two proxies of the same photo only when
  their own rounding sits on a .5 boundary; the result is a cache miss and a recomputation, never a wrong mask.
- **Thumbnails** (`ThumbnailRepository`): `thumbnail()` is unchanged and never waits for a model: an edit with AI parts
  is rendered without them under its usual key. `withAiMasks(photoId, versionId, editTime)` then makes the masks (one
  photo at a time behind `aiLock`; a 1024 px proxy of the original for the model input, the cache first), renders the
  thumbnail with them (`EditedThumbnails.renderWithMasks`: the shared renderer gets the session as its `AiMaskSource`
  for that render only), stores it under its own key (`...:ai`: the thumbnail cache key includes the AI masks'
  availability, so it is rendered once) and puts it in the memory cache under the cell's key. The grid cell and the
  filmstrip cell call it after `load` (`ThumbnailSource.upgrade`, default null) and swap the image; scrolling away
  cancels it. A failed model is not retried until the app restarts. Edits without an AI part pay one memoised recipe
  lookup (`aiParts`).
- **Presets' previews** (`PresetThumbnails.useMasks`): the preview renderer takes the editor's session as its source, so
  a base recipe with AI parts renders them once they are ready; the cell's cache key carries which masks are ready
  (`aiReady`), so each preview is rendered again when one arrives. Presets never contain masks (ADR 0019); this only
  concerns the photo's own masks under a preset.

Measured on the S24 Ultra (24 MP ZV-E10 RAW, sky + subject masks, models freed before each export, `AiMaskReuseTest`):
export with the masks in the cache 4.6 s, without 6.4 s (decode proxy + CPU model input + two models + refinement);
before M4/F every editor-made mask missed, so every export paid the 6.4 s.

