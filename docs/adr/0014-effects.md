# ADR 0014: Effects

Status: accepted (M2/C). Date: 2026-11-11. Amends ADR 0002 (slot 12, the post-crop finish after slot 13,
`PIPELINE_VERSION` 8), ADR 0009 (the Effects pre-pass and the export's post-crop finish) and ADR 0012 (Detail's passes,
which slot 12 shares; the Detail rewrite and its drag cost are in ADR 0012, "M2/C"). Builds on ADR 0007 (the
low-resolution grid) and ADR 0008 (OkLab).

The Effects panel (docs/plan/product.md, "Effects"): **Texture, Clarity, Dehaze** (±100, edge-aware), a **post-crop
vignette** (amount, midpoint, roundness, feather, highlights) and **grain** (amount, size, roughness). Code: `:core:pipeline`
`cpu/EffectsOp.kt` (`EffectsParams`, `EffectsGrid`, `PostParams`, `PostCropOp`), the final pass of `cpu/DetailOp.kt`,
`gl/EffectsShaders.kt`, `gl/EffectsPasses.kt`, `gl/DetailShaders.kt` (`FINAL_FRAG`, `BLUR_FRAG`), the output passes
(`Shaders.OUTPUT_FRAG`, `ExportShaders.OUTPUT_FRAG`); `:feature:editor` `panels/EffectsPanel.kt`.

## Where each effect sits

| Effect | Slot | Domain | Neighbourhood |
| --- | --- | --- | --- |
| Texture | 12, in Detail's final pass | OkLab `L`, denoised and sharpened | a cross-bilateral Gaussian in Detail's blur passes (tile apron) |
| Clarity | 12, same pass | OkLab `L` | the low-resolution grid (no apron) |
| Dehaze | 12, same pass, after the colour is back to linear RGB | linear Rec.2020, display-referred | the grid |
| Vignette | after slot 13 (geometry), before the output transform | linear Rec.2020 | none (pointwise in output coordinates) |
| Grain | last, after the vignette | linear Rec.2020 | none |

- **Slot 12 shares Detail's final pass** (ADR 0012): the develop stage writes OkLab when slot 11 or 12 runs, one pass
  applies sharpening, texture, clarity, the colour NR result, the conversion back to RGB and dehaze. No extra full-
  resolution pass for clarity or dehaze; texture adds a second channel to the existing blur passes.
- **The vignette and the grain are post-crop**: they act on the geometry operator's output (what is exported), so the
  vignette is centred on the crop and follows turns and flips, and the grain is the picture's, not the sensor's. Both
  are pointwise functions of the output pixel, so they live in the output passes (the editor's and the export's), cost
  nothing extra and need no apron or pre-pass.

## Texture

`L += T * t * c^2 / (c^2 + t^2)`, with the band `t = L - B(L)`, `B` a separable **cross-bilateral** Gaussian of sigma
2.5 full-resolution pixels (scaled by the render's pixel scale like Detail's blur: held at 0.5 rendered px with the gain
reduced in proportion), each tap weighted by `exp(-dL^2 / 2c^2)` against the axis' centre, `c = 0.05` (OkLab L).
`T = 1.5` at +100, `-1` at -100 (the band is removed: a smoothing that keeps edges). The bilateral blur never averages
across a strong edge and the shaping `c^2 / (c^2 + t^2)` leaves strong details alone, so there is **no halo**: a first
version with a plain Gaussian changed the pixels next to a strong edge by 0.037 L; the bilateral one by 0.0002.

## Clarity

The guided filter of ADR 0007 on the grid, self-guided on OkLab `L` (cell means of `L` and `L^2`, box radius 3 cells,
`eps = 0.003`): the base `B = a L + b` (coefficients upsampled per pixel with the four nearest cells, bilinear times a
range weight on each cell's mean `L`, sigma 0.1: ADR 0007's joint bilateral upsampling). Then
`L += C * w(B) * d * c^2 / (c^2 + d^2)`, `d = L - B`, `w(B) = clamp(4 B (1 - B))` (midtones), `C = clarity / 100`,
`c = 0.07`. Strong edges are in the base (a windowed variance above `eps` gives `a -> 1`), and a detail far above `c`
is the side of an edge the base could not follow and is not amplified: no halo. `eps` trades halo for strength; 0.0025
was invisible on real photos (foliage counted as edges), 0.02 haloed (0.033 L next to a two-stop edge); 0.003 with the
rolloff gives +81 % local contrast at +100 for a 0.008 L halo.

## Dehaze (dark channel prior)

He, Sun, Tang 2009, "Single Image Haze Removal Using Dark Channel Prior" (CVPR; TPAMI 2011), on the grid. The haze
model is `I = J t + A (1 - t)` (`A` the atmospheric light, `t` the transmission); haze-free outdoor patches have a dark
channel (the minimum over a patch and the three channels) near 0, so `1 - t ~ min_patch min_c I_c / A_c`.

1. Rows and cells (`EffectsShaders.ROWS_FRAG`, `CELLS_FRAG`, MRT): per cell the mean `(L, L^2, a, b)` and the minimum
   of each linear channel, from the develop output **before Detail** (so a Detail drag does not rebuild the grid; NR and
   sharpening keep cell means).
2. A 3 x 3 erosion of the minima (He's patch; a lone bright object is not haze).
3. `A`: the largest eroded dark channel (16 x 16 block maxima down to 1 x 1), then the mean colour of every cell within
   0.02 of it (block sums; He takes the brightest pixel of the top 0.1 %). The first version took the single argmax
   cell: on flat bright areas CPU and GPU picked different near-tied cells; the mean is stable.
4. Per cell the haze share `h = clamp(min_c(min_c / A_c))`, refined by the same guided filter as clarity with `L` as the
   guide and `h` as the input (`a = cov(L, h) / (var L + 0.005)`): `h` follows the image's edges (He 2013's refinement).
5. Per pixel, in linear light after the colour is back to RGB: positive `J = (I - A) / max(1 - 0.95 h * amount, 0.25)
   + A`; negative `I + (A - I) * 0.6 |amount| (0.4 + 0.6 h)` (haze added, more where there already is some).

The transmission floor is 0.25 (He et al. use 0.1): at most a 4x stretch around `A`, so grey areas and noise are not
amplified without bound. None of the grid depends on the amounts: a Clarity or Dehaze drag is one final pass.

Measured (JVM `EffectsOpTest.dehaze...`, a synthetic hazy landscape with known `J` and `A`): RMS error to the haze-free
truth 0.412 -> 0.140 at +100, mean dark channel 0.515 -> 0.233 (+100) and 0.608 (-100), `A` found as a bright blue
(0.74, 0.76, 0.85; truth 0.78, 0.82, 0.88 in sRGB). On the CC0 street scene (`docs/screenshots/effects/dehaze.jpg`)
with little haze the effect is mild by design: the dark channel of sunlit foliage is already near 0.

## Vignette (post-crop)

On the output point `o` of a `W x H` output: per axis `v = (o - centre) * s`, `s = 1 / half size` blended to
`1 / Rc` (`Rc = sqrt((hx^2 + hy^2) / 2)`, a circle in pixels) by positive roundness, the superellipse exponent
`e = 2 + 6 * (-roundness)` for negative roundness, `d = (|vx|^e + |vy|^e)^(1/e) * sqrt 2 / 2^(1/e)` (the corners are at
`d = sqrt 2` for every shape), mask `w = smoothstep(r0 - f/2, r0 + f/2, d)` with `r0 = 0.4 + 1.1 midpoint`,
`f = 0.05 + 1.15 feather`. On the lightness `p = cbrt(Y)` (Rec.2020 luminance), one gain on the three channels (hue
kept): darkening `p (1 - 0.8 k w (1 - highlights * smoothstep(0.5, 0.9, p)))`, lightening `p + 0.7 k w (1 - p)` (a
pure black is lifted as a neutral). At -100 with the defaults the corners keep 20 % of their lightness and the middle
of the long edges 51 %; the centre is untouched.

## Grain

Value noise on a lattice whose cell is a fixed fraction of the output's long edge: `0.75 + 3.25 size` pixels of a
3000 px long edge (size 0..1). Each lattice point gets a triangular-distributed value from the dither's integer hash
(`hash(x + hash(y + seed * 0x9E3779B9))`, `lowbias32`, identical on CPU and GPU; fixed seed), interpolated with
smoothstep weights; roughness mixes an octave twice as fine (offset by 0.37 cells so the lattices never coincide):
`n = (n0 + r n1) / sqrt(1 + r^2)`. It is added to the lightness weighted to the midtones `4 p (1 - p)`, amplitude
`0.08 * 1.6 * amount`.

**Scaled to output size**: the lattice coordinate of an output pixel is `o * cells / long edge`, so the same
normalised point gets the same value at any resolution: the preview samples the field at the proxy's output pixels, the
100 % tiles at the full resolution's, an export at its own size (the export applies grain in its output pass, after
the Lanczos resampling). The grain keeps its size relative to the picture and the pattern is the same everywhere (tested:
the value at `o` of a 1000 px output equals the value at `2 o` of a 2000 px output exactly). Where a lattice cell is
smaller than a rendered pixel (fine grain in the fit view) the proxy shows a point sample of the same field: grain of
the same amplitude, at the proxy's own pixel size.

## Tiles and export

- **Texture** reads up to `ceil(3 * 2.5) = 8` full-resolution pixels around a pixel: `DevelopParams.reach` adds it to
  Detail's (`max(sharpening, texture)` share one blur), the export plans and the zoom tiles grow their input by it
  (ADR 0012, "apron"). Still at most 72 px.
- **Clarity and dehaze** read only the grid: `TiledRenderer` adds a third pre-pass after the Light grids, scene and
  develop stage of every cell-aligned pre-pass tile -> Effects rows (`EffectsPasses.rows`), then finishes the grid
  once. The zoom (`prepareZoom`) keys it on everything before slot 11 (`DevelopParams.withoutDetail()`), never the
  amounts: after a Light or Colour edit with clarity or dehaze on, the first 100 % tiles also wait for this pre-pass.
- **Vignette and grain** are applied in the export's output pass at the export's output size, in the editor's output
  pass at the proxy's or (where tiles show) the full resolution's output size: no seam is possible (pointwise).

Tested (`TiledRendererTest.effectsTilesStitchAndMatchThePreviewAndTheCpu`, every effect with colour NR and a tone
curve, whose table stays bound on its own unit: slots 11-12 use units 8-14): 256 px tiles
equal one 1024 px tile **bit for bit**, also straightened and resized to 640 px; the export equals the preview renderer
(**0 levels**) and the CPU reference within **1 level**. `ZoomTilesTest` keeps passing (100 % view = export bit for bit).

## Parity and tolerances

`GpuParityTest.EFFECTS` (11 recipes: each local effect at both ends, combined with Detail, the post-crop finish with and
without a straightened crop) on every synthetic image plus a hazy landscape, and at proxy scales 2.5 and 4: within
Detail's tolerance (ADR 0012) except **positive dehaze**, whose division by the transmission multiplies the float16
storage differences of its input by up to `1 / 0.25 = 4`; its tolerance is Detail's times 4. Measured (emulator, host
GPU NVIDIA RTX 4080): linear stage worst 0.76 of the tolerance (a non-Effects recipe), 8-bit worst 15 levels on one
saturated sky pixel of the scene with Dehaze +100 (CPU and GPU OkLab inputs one float16 step apart, x4, landing out of
the sRGB gamut where the encoded levels are steep; the linear values there differ by 0.004), mean 0.04 levels over all
images and recipes. Every other Effects recipe stays within 4 levels. On the **phone** (Adreno 750) the whole suite
passes: linear stage worst 0.90 of the tolerance, mean abs error 3.5e-4 over the earlier classes and 4.6e-4 over the
Detail and Effects recipes (their own bound, 1e-3); 8-bit max 14 levels (the same dehaze pixel), mean 0.056; proxy
scales max 15, mean 0.03; export vs preview 0 levels, vs CPU 2. The hazy landscape runs only the Detail and Effects
recipes (it exists for dehaze and would otherwise shift the other classes' mean).

## Drag cost

None of the Effects sliders re-runs anything but the final pass and the output (the Detail intermediates, the develop
output and the grid are cached); the vignette and the grain do not even re-run the final pass (output uniforms). Emulator
(host GPU NVIDIA RTX 4080, `PipelineBenchmark.detailAndEffectsSliderDrags`, 2400 x 1600 at pixel scale 2.5, every Detail
and Effects slider on, two runs): p50 0.82-0.95 ms, the same as an exposure drag (0.89-0.91 ms). **Phone** (Adreno 750,
same benchmark): vignette 8.0 ms, grain 6.6 ms (p50), texture 13.1, clarity 10.0, dehaze 13.4 ms with every Detail and
Effects tool on (exposure in the same run: 9.3 ms): the final pass with everything on costs 6-8 ms there. ADR 0012
"M2/C" has the full table, the per-pass profile and the next levers. M2/P (ADR 0012, "M2/P") brought every Effects drag to the display rate with
every tool on: texture, clarity, dehaze 3.0-3.2 ms per frame pipelined (GPU p95 <= 5 ms), vignette and grain 1.2 ms.

## Recipe and UI

`Recipe.effects` was a reserved empty object; it now holds the eleven fields (defaults = no change; a stored `{}` reads
as the defaults, tested: additive, no `RECIPE_VERSION` bump). History labels in English and pt-BR (`history_texture`
...). The panel: Presence (texture, clarity, dehaze), Vignette after crop (amount; midpoint, roundness, feather and
highlights enabled once the amount is not 0), Grain (amount; size and roughness enabled once it is on). Goldens
`app/src/test/screenshots/editor/*effects*`; instrumented `EditorEffectsTest`. The vignette and the grain are hidden in
the crop mode's editing plane, on the original pane and under the sharpening mask overlay.

## Before / after (CC0, `EffectsRenderSheetTest`, CPU reference, before on the left)

`docs/screenshots/effects/`: `clarity.jpg` (+60), `dehaze.jpg` (+50), `dehaze-negative.jpg` (-50), `all.jpg` (texture
30, clarity 35, dehaze 20, vignette -25) on the Nikon D5600 street scene; `vignette-grain.jpg` (a crop, vignette -60,
feather 70, grain 40: centred on the crop) and `texture.jpg` / `texture-smooth.jpg` (+80 / -80 at 100 %) on the Sony
ZV-E10 sample.

## PIPELINE_VERSION

**8**: the Effects exist (identity at their defaults) and Detail changed (ADR 0012, "M2/C"); a seventh golden CRC pins
the Effects (`CpuDevelopTest`, five renders: local effects, negative ones with Detail, the post-crop finish on a
straightened crop, a lightening rectangular vignette, local effects at a proxy scale).

## Limits

- Dehaze estimates one atmospheric light for the photo; mixed lighting (a sunset sky over shade) is approximated.
- Clarity's scale is the grid's (about 1-3 % of the picture), whatever the photo's size; there is no radius slider.
- Grain is luminance-only (no colour grain) and has a fixed seed per pipeline version.
