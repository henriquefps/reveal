# ADR 0012: Detail and full-resolution tiles

Status: accepted (M2/Z); amended by M2/C (section "M2/C: Detail at the display rate and the noise model", version 8)
and M2/P (section "M2/P: every Detail and Effects drag at the display rate", version 10) and M2/T (section "Pixel
alignment at 1:1", no pixel change).
Date: 2026-11-11. Amends ADR 0002 (slot 11, `PIPELINE_VERSION` 6, "Proxies and tiles"),
ADR 0009 (the Detail apron of export tiles). Builds on ADR 0007 (Light grids) and ADR 0008 (OkLab).

Two things that only make sense together: the editor shows **full-resolution detail when zoomed in** (it only had the
~2400 px proxy, so 100 % was a magnified proxy), and the **Detail** tools (sharpening, luminance and colour noise
reduction), which are judged at 100 %. Code: `:core:pipeline` `cpu/DetailOp.kt`, `cpu/Half.kt`, `gl/DetailShaders.kt`,
`gl/DetailPasses.kt`, `gl/ZoomTiles.kt`, `gl/ZoomShaders.kt`, `zoom/ZoomGrid.kt`, `gl/TiledRenderer.kt` (zoom API),
`input/TileSource.kt` (`RegionTileSource`); `:feature:editor` `panels/DetailPanel.kt`, `open/PhotoOpener.kt`
(`openFullResolution`), `EditorViewModel.loadFullResolution`.

## Detail (slot 11)

After the colour operators (slot 9), before effects and geometry (ADR 0002): noise reduction, then sharpening, in
**OkLab** (`L` for luminance, `a, b` for colour; ADR 0008's space: nearly variance-stabilising for shot noise after the
tone curve, and an exact inverse). The develop stage writes OkLab directly when Detail runs (`DEVELOP_FRAG`,
`uLabOut`), so the operator adds no conversion pass.

| Slider | Range (default) | What it does |
| --- | --- | --- |
| Sharpening amount | 0-150 (0) | gain of the shaped high-pass, 1.2 at 100 |
| Radius | 0.5-3.0 px (1.0) | sigma of the Gaussian, full-resolution pixels |
| Detail | 0-100 (25) | low = edges only (texture cored away, halos limited), high = texture too |
| Masking | 0-100 (0) | restricts sharpening to edges; dragging it shows the mask (white = sharpened) |
| Luminance | 0-100 (0) | threshold of the luminance wavelet shrinkage, `0.06 * amount^1.5` |
| Luminance detail | 0-100 (50) | fine levels' threshold x `mix(1.5, 0.3, detail)`: higher keeps fine detail (and noise) |
| Luminance contrast | 0-100 (0) | coarse levels' threshold x `(1 - 0.85 contrast)`: keeps local contrast (can look blotchy) |
| Colour | 0-100 (0) | chroma range sigma `0.06 * amount` of the colour smoothing |
| Colour detail | 0-100 (50) | protects colour edges (range x `mix(1.6, 0.6, d)`) and stops colour at luminance edges |
| Colour smoothness | 0-100 (50) | adds the two coarsest levels (8 and 16 px) for low-frequency blotches |

Every amount defaults to 0 and the operator is skipped (bit-exact identity) until one is on; the shaping sliders do
nothing alone (tested on CPU and GPU). The recipe's `detail` group was a reserved empty object; old recipes (`{}`)
read the defaults, so no `RECIPE_VERSION` bump (tested). History labels in English and pt-BR.

### Noise reduction: edge-avoiding à-trous wavelet

Five levels of the B3-spline à-trous transform (Starck, Murtagh, Bijaoui 1998), 5x5 taps with holes of 1, 2, 4, 8, 16
px, each level's smoothing **edge-stopped** (Dammertz, Sewtz, Hanika, Lensch 2010, "Edge-avoiding À-Trous Wavelet
Transform", HPG):

- **Luminance**: smoothing weighted by `exp(-dL^2 / 2 sigma_e^2)`, `sigma_e = tau * mix(8, 3, detail)`; the level's
  detail `d = L_i - L_i+1` is shrunk with `d * (d^2 / (d^2 + tau^2))^2` (a smooth garrote: details well above `tau`
  pass almost whole, those below vanish, no hard knee) and accumulated; the result is the coarsest level plus the
  shrunk details. `tau` per level = the slider's threshold x `[1, 0.62, 0.38, 0.22, 0.13]` (finer levels carry more of
  the noise). The amount curve `amount^1.5` was calibrated on the CC0 Sony A7C files: at ISO 2000 values 10-20 clean
  the noise and keep the leaf's texture, 35+ starts to smooth it; at ISO 12800 the noise goes progressively up to
  70-100 (a linear curve made 45 already plastic at ISO 2000).
- **Colour**: smoothing of `a, b` weighted by `exp(-|dab|^2 / 2 sigma_c^2 - dL^2 / 2 sigma_cl^2)` (chroma distance and
  a luminance guard: colour does not bleed across edges), mixed in per level, cascaded (colour noise is
  low-frequency: the coarse levels matter).

Measured (JVM, `DetailOpTest`, synthetic card with 12 % per-channel noise): luminance NR 60 leaves 38 % of the flat
`L` noise (0.020 -> 0.0076 RMS) and keeps the step edge within 3 %; colour NR 60 leaves 12 % of the chroma noise
(0.034 -> 0.004) and changes `L` by under 0.002.

Alternatives: **non-local means** (Buades 2005) gives the best texture but 7x7 search x 3x3 patch = 441 taps per pixel
per pass, about 4x this operator's cost at a single scale and blind to the low-frequency colour blotches of high
ISO; a **bilateral filter** halos and needs a wide kernel for chroma; the **guided filter** of ADR 0007 smooths
texture as much as noise. The à-trous wavelet is multi-scale in 5 passes of 25 taps, cheap at any scale (the steps
grow, not the kernel). An AI denoise remains a later milestone (`docs/plan/milestones.md`).

### Before / after at 100 % (CC0 Sony A7C files from raw.pixls.us, `scripts/fetch-samples.sh`)

480 px crops at 100 % rendered by the CPU reference (`DetailRenderSheetTest`, full-size AHD dump), before on the left:

- `docs/screenshots/detail/iso12800-before-after.jpg`: ISO 12800, luminance 60, colour 50, sharpening 40 with masking
  40. `iso12800-noise-reduction.jpg`: the same noise reduction without sharpening.
- `docs/screenshots/detail/iso2000-before-after.jpg`: ISO 2000, luminance 20, colour 30, sharpening 50 with masking
  20: the leaf's texture survives. `iso2000-sharpening.jpg`: sharpening 60, masking 30 alone.
- `docs/screenshots/detail/iso2000-sharpening-mask.jpg`: that sharpening and its masking overlay (white = sharpened).

### Sharpening: unsharp mask with texture coring, halo limit and edge mask

`h = L - G_sigma(L)` on the denoised `L` (separable Gaussian, radius `ceil(3 sigma)`), shaped by **coring**
`h * h^2 / (h^2 + t^2)`, `t = (1 - detail) * 0.006` (small amplitudes, i.e. texture and leftover noise, are not
sharpened at low detail) and the **halo limit** `h / (1 + |h| / c)`, `c = mix(0.04, 0.5, detail)` (strong edges get a
bounded overshoot), times the **mask** `smoothstep(m/2, m, |grad G|)` with `m = masking * 0.06` per full-resolution
pixel (`|grad G|` by central differences on the blurred `L`). `L += 1.2 * amount * shaped * mask`. The masking overlay
draws the mask as grey (`GpuDevelopRenderer.setDetailMask`, while the Masking slider is dragged).

### Scale: the proxy approximates 100 %

Lengths are full-resolution pixels. A render whose pixels cover `s` full-resolution pixels (the proxy: `s` = the long
edge ratio, 2.5 for 24 MP on 2400 px; 1 for tiles and export) divides each level's step by `s` and rounds it, **skips
levels finer than 0.75 rendered px** (the proxy's binning already averaged that noise), divides the blur radius by `s`
(below 0.5 px it is held at 0.5 and the amount reduced in proportion), and measures the masking gradient per
full-resolution pixel. Measured (`DetailOpTest`): the 100 % render reduced 2x against the proxy rendered at `s = 2`
differs by 0.0039 RMS in `L` (0.0021 without Detail: the resampling itself), with similar residual noise.

### Storage: explicit float16 rounding

The GPU stores the OkLab input and every wavelet level in RGBA16F. Drivers convert float32 to float16 in a render
target with their own rounding (often truncation): the cascade then lost half an ulp per level and luminance NR came
out **0.51 levels darker** on average than the CPU. Every value the passes store is now rounded to the nearest float16
in the shader with integer operations on its bits (`q16`, ties to even; identical on every driver), the CPU reference
stores the very same values (`Half.round`), and each level's detail is taken against the **stored** smooth value, so
with no shrinkage the levels telescope back to the input exactly (tested: relative change under 0.2 %). After the
fix Detail's mean signed error is the pipeline's usual -0.04 levels.

### Parity (emulator, host GPU NVIDIA RTX 4080 via the translator)

| Quantity | Tolerance | Measured |
| --- | --- | --- |
| Linear stage, Detail recipes (8 in `GpuParityTest.DETAIL`, every image) | `4e-3 + 3e-2 * scale` | within; the worst ratio of the whole set stays 0.76 (a Light recipe); mean abs error of the set 2.6e-4 |
| 8-bit output, Detail recipes | 4 levels (6 on the random-noise stress image) | max 1 on every image but noise |
| Proxy scales 2.5 and 4 (`detailAtAProxyScaleMatchesCpuReference`) | 4 / 6 levels, mean <= 0.1 | max 4 (noise), mean 0.02 |
| Export tiles with apron vs one tile | bit-identical | 0 values differ (with and without straightening) |
| Export vs preview renderer, Detail on | <= 1 level | 0 levels |
| Export vs CPU reference, Detail on | <= 4 levels | max 1, 114,809 of 3.3 M values off by one |

## Detail in export tiles: the apron

Detail reads a neighbourhood: its **reach** (`DetailParams.reach`) is twice the step of every level that runs plus the
blur radius plus one for the gradient, at most `2 * 31 + 9 + 1 = 72` px at full resolution. `ExportPlan` grows every
tile's input region by it (clamped to the image) and shrinks the tile budget accordingly; the geometry pass then always
runs (an exact texel copy without geometry) and cuts the apron off. Taps are clamped to the image, then to the region,
so only apron pixels ever read a clamped tap. Detail's passes reuse textures that are free at that point of a tile
(source, scene, the two resampling targets), no extra memory. ADR 0009's three-space plan is otherwise unchanged.

## Full-resolution zoom

### Source

Once the proxy is on screen, `EditorViewModel.loadFullResolution` decodes the original **in the background**:
a RAW with the export's LibRaw path (`RawDecoder.decodeNative` -> `NativeRawTileSource`, LibRaw's 16-bit buffer, 6 bytes
per pixel), a JPEG/HEIF/PNG up to 40 MP decoded once like the export (`BitmapTileSource`, 4 bytes per pixel), larger
ones read in regions (`RegionTileSource`, the library viewer's `BitmapRegionDecoder`). A JPEG region decode has to
re-read everything above the region: about 250 ms per tile at 12 MP on the emulator, against 223 ms for one whole
decode and 50 ms per tile after it, hence the threshold. Failure (memory, unreadable) leaves the M1 proxy-only zoom.

### Tiles

`ZoomGrid`: cores of 512 px over the upright image; each tile lives in an atlas slot with a **border of 8 px** of
real neighbours (or the repeated edge at the image border), so the output pass filters across a core's edge inside one
slot, and its **2x2 means in level 1** (views between 50 % and 100 %; border and slot even, so level 1 of a slot is
exactly the 2x2 mean of level 0 everywhere). The **atlas** is one RGBA16F texture of 8 x 6 slots (4224 x 3168, levels
0-1); a small RGBA8 **index** texture (one texel per tile: slot, fade-in alpha, ready) tells the output pass where a
tile is. `OUTPUT_FRAG` computes the proxy colour as before and, where the index has a tile of the current edit, the
full-resolution output point of the same screen pixel (`ZoomView.fullOutput`: the same centre, `k` full pixels per
proxy pixel), its image point through the **full-resolution** geometry mapping, and samples the slot with the same
three modes as the proxy (exact texel at 100 %, Catmull-Rom when straightened, `textureLod` at the explicit LOD below
100 %; magnified bilinear, nearest from 200 %), never from derivatives (ADR 0002). Below 50 % of the full resolution
the proxy is enough (for a 24 MP photo it is then magnified at most 1.25x) and no tile is made.

### Developing a tile: the export's code on a shared context

The tiles are developed by a `TiledRenderer` (ADR 0009) whose EGL context **shares textures** with the editor's
(`EglCore.create(share)`), on its own thread and a single-thread executor: a tile never blocks a frame.
`prepareZoom` builds the **full-resolution Light grids** (highlight reconstruction's chroma, the local-tone guide)
with the export's pre-pass over cell-aligned tiles of the whole image, kept while what they depend on (white balance,
matrix, exposure, highlights/shadows on) is unchanged; `developTile` uploads the core plus its border plus the Detail
apron, runs the scene, develop and Detail passes at scale 1, and writes level 0 and level 1 of the slot
(`ZoomShaders.COPY_FRAG`), ending with `glFinish`. Before a slot is reused the worker waits on a fence placed after the
editor's last frame. Same shaders, same grids, same source as the export: **the 100 % view is the export's pixels**.

### Scheduling

- Tiles are made only for the **settled edit**: a recipe change hides the old tiles (the proxy shows, as fast as in
  M1) and 150 ms after the last change the visible tiles are developed, nearest to the view's centre first, one at a
  time, each fading in over 150 ms. A slider drag at 100 % therefore shows the proxy and the sharp tiles come back on
  release.
- Visible = the pane's clip or the **photo's frame** (not the surface under the panels).
- With nothing to develop, the settled edit's grids are **prefetched**, so the first zoom (and the first tiles after
  a white balance or exposure change) do not wait for the pre-passes.
- Slots are reused least-recently-shown first. No tiles in the crop mode's plane, while comparing to the original, or
  while the masking overlay shows.

### Memory budget

| Item | 24 MP RAW | 61 MP RAW | Where |
| --- | --- | --- | --- |
| Full-resolution source (LibRaw 16-bit RGB) | 145 MB | 366 MB | native heap, while the editor is open |
| LibRaw's decode peak (once, in the background) | ~250 MB | ~600 MB | native heap, transient |
| Atlas (48 slots, RGBA16F, 2 levels) | 134 MB | 134 MB | GPU |
| Worker tile textures (1024 px) | 60 MB | 60 MB | GPU |
| Full-resolution grids (rows target `gridWidth x height` RGBA32F) | 16 MB | 26 MB | GPU |
| Proxy Detail textures (3 RGBA16F + 2 R32F at 2400 x 1600) | 107 MB | 107 MB | GPU, only once Detail runs |

A 61 MP RAW peaks under 1.4 GB of native and GPU memory together, nothing on the Java heap: well within a 12 GB
phone. Standard images take 4 bytes per pixel instead of 6. Everything is freed when the editor closes.

### Consistency (tested)

- `ZoomTilesTest` (`:core:pipeline`, synthetic 1200 x 800 RAW-like image with clipped highlights, every Light slider
  and Detail): at 100 % the view centred where four tiles meet equals the **export of the same pixels bit for bit (0
  values differ)**, also straightened (Catmull-Rom, at most 1 level), and is within **1 level of the CPU reference
  of the whole image, also on the rows and columns next to the tile edges** (mean 0.045, signed -0.040 like the
  pipeline's own stage write): no seam. At 70 % the tiles
  differ from the proxy by 0.4 levels on average (detail only, same tone and colour); after a new edit the old tiles
  are not shown.
- `EditorZoomTest` (app): a 12 MP JPEG with 3-7 px gratings and noise (detail the proxy cannot hold), Detail on,
  double-tap to 100 % in the real editor: what the window shows across two tile edges matches the CPU reference of
  the full-resolution image within **2 levels (mean 0.06)**.

### Measurements (emulator, host GPU NVIDIA RTX 4080 via the translator; re-measure on the phone)

| | ZV-E10 ARW, 24 MP (CC0) | 12 MP JPEG |
| --- | --- | --- |
| Full-resolution decode (background, after the proxy) | 1.7-1.9 s | 0.22 s |
| Grid pre-passes (prefetched; only with clipped highlights or highlights/shadows) | 0.5-0.66 s | 0.01 s |
| Tile (512 px core + border + apron: upload, develop, Detail, copy), mean | 8-16 ms | 50 ms |
| Double-tap to 100 % until every visible tile is sharp (6 tiles) | 150-160 ms | 430 ms |
| Detail edit at 100 % until sharp again (150 ms settle included) | 280 ms | - |

**On the phone** (Galaxy S24 Ultra, Adreno 750, debug build, measured by the worker with the owner's permission):

| | ZV-E10 ARW, 24 MP (CC0) | 12 MP JPEG |
| --- | --- | --- |
| Full-resolution decode (background) | 0.95-1.04 s | 0.22 s |
| Tile, mean (the first, cold one ~100 ms) | 11-20 ms | 52-58 ms |
| Double-tap to 100 % until every visible tile is sharp (9 tiles) | 240-300 ms | 640-660 ms |
| Detail edit at 100 % until sharp again | 360-450 ms | - |
| 100 % view vs export / vs CPU | 0 values differ / max 1 level (`ZoomTilesTest`) | max 2 levels, mean 0.10 (`EditorZoomTest`) |

Detail on the 2400 px proxy on the phone (`PipelineBenchmark`): luminance NR drag with colour NR and sharpening
**23.2 ms** per frame (p95 26.6), sharpening drag 12.8 ms, against 9.0 ms for an exposure drag: a Detail drag runs at
about 43 fps. Detail parity on the Adreno: proxy scales max 5 levels (mean 0.03), export vs preview 0 levels, export
vs CPU max 1; luminance NR 100 on the random-noise stress image reaches 7 levels (allowance 8 there).

On-screen Detail on the proxy (`PipelineBenchmark`, 2400 x 1600, pixel scale 2.5, develop output cached): luminance
NR drag with colour NR and sharpening on **2.49 ms** per frame (p95 3.12), sharpening drag 1.92 ms (p95 2.47), against
0.89 ms for an exposure drag (the phone's numbers above). To bring a Detail drag to the display rate, the first
candidates are colour NR at half resolution (chroma is low-frequency) and a cheaper edge-stop than `exp`.

## PIPELINE_VERSION

**6**: the Detail operator exists (identity at its defaults, so no existing output changes); a fifth golden CRC pins
it (`CpuDevelopTest`, two recipes at scale 1 and one at a proxy scale).

**10** (M2/P): the luminance edge stop follows the noise, not the sliders, and the blurs store float16 (section
"M2/P"); the Detail and Effects golden CRCs changed with it.

## M2/C: Detail at the display rate and the noise model

M2/Z left a Detail drag at 23 ms per frame on the Adreno 750 (43 fps), against about 9 ms for a Light drag. Target: at
most 8.3 ms p50 and 12 ms p95 for every Detail slider at 2400 x 1600. Four changes, all mirrored in the CPU reference
(`DetailOp`), so the operator is still exactly what the GPU renders (`PIPELINE_VERSION` 8, ADR 0014):

1. **Separable luminance levels.** Each level's edge-avoiding smoothing runs along x then along y (5 + 5 taps, each axis
   edge-stopped against its own centre) instead of 5 x 5 = 25 taps, on single-channel textures (R16F for the axis,
   RG16F `(L_i, acc)` between levels) instead of RGBA16F. The shrinkage, the float16 storage and the telescoping are
   unchanged (relative change without shrinkage 0.17 %). Fetches per level: 25 RGBA16F -> 10 R/RG16F, `exp` per tap:
   2 -> 1.
2. **Colour on a half-resolution copy.** Colour noise is low-frequency: `(L, a, b)` is binned 2 x 2 and the colour
   levels run there (5 x 5, chroma range and luminance guard as before, half-resolution steps; the finest level is the
   binning itself). The full-resolution colour is `up(S) + r * (1 - exp(-|r|^2 / 8 sigma^2))^2` with the residual
   `r = ab - up(D)` (bilinear, explicit weights): colour finer than the copy is dropped where it is small (noise) and
   kept where it is a real colour edge. A quarter of the pixels. Measured: colour NR 60 leaves **10 %** of the chroma
   noise (M2/Z: 12 %), `L` unchanged.
3. **Levels skipped on the proxy.** A level runs when its rendered step is at least one pixel (was 0.75): on a 24 MP
   photo's 2400 px proxy (scale 2.5) luminance runs levels 2-4 (steps 2, 3, 6), colour levels 3-4 at half resolution;
   the finer ones were averaged away by the proxy's binning. The 100 % tiles and the export are unchanged in this
   respect (every level runs at scale 1).
4. **Caching per slider.** `DetailPasses` keeps the luminance levels while their parameters are unchanged, the colour
   levels likewise, and the blurs while the denoised luminance and the kernels are: a sharpening amount, detail or
   masking drag (and every Effects drag) is one final pass; a colour drag is the binning, its levels and the final
   pass; only a luminance drag re-runs the luminance levels and the blurs. Uniform locations are looked up once per
   program (they were looked up every pass: costly through the emulator's GL translator and wasted CPU on the phone).

Drag cost (`PipelineBenchmark.detailAndEffectsSliderDrags`, 2400 x 1600, pixel scale 2.5, every Detail and Effects
slider on; emulator with the host GPU NVIDIA RTX 4080, two runs, p50 / p95 ms):

| Drag | main (M2/Z) | M2/C |
| --- | --- | --- |
| exposure (reference) | 0.82 / 1.14 | 0.89-0.91 / 1.27-1.50 |
| luminance NR (colour NR + sharpening on; `detailDragFrameTime`) | 2.38 / 2.81-2.99 | 1.17-1.20 / 1.48-1.58 |
| luminance, luminance detail, luminance contrast (everything on) | - | 1.25-1.34 / 1.62-2.13 |
| colour, colour detail, colour smoothness | - | 0.94-1.02 / 1.30-1.64 |
| sharpening amount / radius / detail / masking | 1.77 / 2.27-2.41 (amount) | 0.84-0.96 / 1.11-1.29 |

On this host every slider other than luminance costs what an exposure drag costs, and luminance about 1.4x; but the
emulator's numbers are dominated by per-call overhead.

**On the phone** (Galaxy S24 Ultra, Adreno 750, debug build; the worker ran it there because the emulator kept
crashing, owner's rules: `--user 0`, CC0/synthetic only, apps removed after). Same benchmark, p50 / p95 ms; the phone
warms up over a run (the exposure reference moved from 8.3 to 9.3 ms between two runs), so compare within a run:

| Drag (everything on unless noted) | main (M2/Z) | M2/C, run 1 | M2/C, final |
| --- | --- | --- | --- |
| exposure (reference) | 9.0 | 8.3 / 11.0 | 9.3 / 11.6 |
| luminance NR, colour NR + sharpening on (`detailDragFrameTime`) | 23.2 / 26.6 | 23.0 / 26.6 | **17.6** / 22.1 |
| sharpening alone (`sharpenDragFrameTime`) | 12.8 | **7.0** / 9.1 | 9.7 / 12.6 (warm) |
| luminance / luminance detail / luminance contrast | - | 22.5-25.9 | 12.7-22.9 / 15-33 |
| colour / colour detail / colour smoothness | - | 16.5-17.2 | 13.7-15.9 / 17-19 |
| sharpening amount / radius / detail / masking | - | 12.6-15.2 | 12.1-15.8 / 14-20 |
| texture / clarity / dehaze (ADR 0014) | - | 14.7-15.8 | 10.0-13.4 / 15 |
| vignette / grain (ADR 0014, output pass only) | - | 15.8 / 15.7 | **8.0 / 6.6** |

Per pass (`-e profile true`, `glFinish` after every section, so each figure includes a sync; mean ms): luminance levels
(3 levels x 2 axes) 10-12, the two blurs 5-7, colour levels at half resolution 5.5-6.3, the final pass with everything
on 6-8, mipmaps 2.0-2.6, the output pass at 2400 x 1600 6-8. **On the Adreno a full-resolution pass at 2400 x 1600 costs
about 1.5-2 ms almost whatever it computes**: the number of passes matters, the number of taps much less (so the
separable levels, which halve the taps but double the passes, did not pay there, unlike on the emulator). Two changes
were made after the first phone run: the final pass is compiled per feature set (compile-time flags instead of uniform
branches: the general shader's register pressure slowed every pixel), and the vignette and grain no longer re-run a
stage (they are output uniforms).

**Target status:** at most 8.3 ms p50 / 12 ms p95 is met for sharpening alone (cool phone), the vignette and the grain;
it is **not** met with every Detail and Effects tool on at once (10-23 ms; a luminance drag went from 23.2 to 17.6 ms).
Next levers, in order of expected gain on the Adreno: run the luminance levels whose rendered step is 2 or more on a
half-resolution copy (as colour does; 3-4 fewer full-resolution passes during a luminance drag), back to one pass per
level (25 taps, half the passes), RG16F blur targets with the float16 rounding of ADR 0012, and the output pass at the
frame's size rather than the proxy's in the benchmark (the editor already draws it at screen size).

### Noise model

The thresholds of luminance and colour NR (`lumTau`, the chroma sigma) are multiplied by the photo's expected noise
relative to a reference capture (`cpu/NoiseModel.kt`, from `PipelineInput.noise`: EXIF ISO and camera model, set by
`PipelineInputMapper` and the RAW tile source), so an amount removes the same share of the noise at ISO 200 and ISO
12800: `factor = sqrt(ISO / 5000) * 5.9 um / pitch(camera)`, clamped to [0.1, 4]. Shot noise after the gain grows as
`sqrt(ISO)` and falls with the pixel's area (relative noise `1 / pitch`); the reference (factor 1) is the CC0 Sony A7C
(full frame 24 MP, 5.9 um) at ISO 5000, the middle of the two files the amount curve was calibrated on, so mid-to-high
ISO photos keep M2/Z's look. Defaults: no ISO -> factor 1 (M2/Z); unknown camera -> 3.9 um (APS-C 24 MP);
display-referred input (JPEG, HEIF: the camera already denoised it, the decoders report no ISO) -> 0.5. A table of
common bodies gives the pitch (sensor width / columns).

Check on the CC0 files (`NoiseCalibrationTest`, opt-in; full-size dumps, default render, OkLab `L` noise of the
flattest mid-tone 16 x 16 blocks of a 1000 px centre crop; `rawdump` now records the ISO):

| File | ISO | measured sigma | model factor | sigma / factor |
| --- | --- | --- | --- | --- |
| Canon EOS R6 | 100 | 0.0032 | 0.13 | 0.025 |
| Nikon D5600 | 200 | 0.0061 | 0.30 | 0.020 |
| Sony ZV-E10 | 400 | 0.0082 | 0.43 | 0.019 |
| Sony A7C | 2000 | 0.0098 | 0.63 | 0.016 |
| Sony A7C | 12800 | 0.0217 | 1.60 | 0.014 |

Same camera, ISO 2000 -> 12800: measured 2.2x, model 2.5x. Across five cameras sigma / factor stays within about
+-30 % (the low-ISO files have a floor of real texture in their "flattest" blocks), against a 17x spread of the factor:
the model normalises what it should. Without the model, amount 50 would mean a 7x different strength on these files.

## M2/P: every Detail and Effects drag at the display rate

M2/C left drags with every Detail and Effects tool on at 10-23 ms on the Adreno 750. Target: every Detail and Effects
slider drag at most 8.3 ms p50 and 12 ms p95 with every Detail, Effects and colour tool on, without changing the
committed pixels beyond ADR 0002's tolerance.

### Measuring on a phone

- **GPU timer queries.** `PassProfiler` times each section with `GL_EXT_disjoint_timer_query` (`TIME_ELAPSED`, no
  `glFinish` between passes) where the driver has it, else with `glFinish` after each section; it also records the GL
  thread's CPU time per section. `glGenerateMipmap` is invisible to the timer on the Adreno (0.01 ms) but costs about
  1-1.4 ms (2-3 ms with a sync).
- **The GPU clock follows its load.** The same pass costs up to 2x more after an idle second, and a frame serialised
  with `glFinish` lets the GPU idle between frames, so it runs at a low clock and pays the wake-up: M2/C's "latency"
  (`glFinish` per frame) reads 2-4x the GPU's frame cost. Benchmarks now warm up with 2 s of continuous rendering, and
  report three numbers: **pipelined** (sustained ms per frame with two frames in flight, fenced, as a display's swap
  chain runs them: the display rate), **GPU per frame** (one timer query around each pipelined frame, p50 / p95) and
  **latency** (one frame then `glFinish`, M2/C's measure). The target is read on the display rate: pipelined <= 8.3 ms
  and GPU per frame p50 <= 8.3, p95 <= 12.
- **As the editor draws.** `everyToolOnSliderDrags` sets Light, Camera JPEG match, the tone curve, the mixer, grading,
  Detail and Effects, and draws the output fitted to the photo frame of the owner's phone (1080 x 720 of a 2400 x 1600
  proxy, `-e frame`); M2/C's `detailAndEffectsSliderDrags` drew it at 1:1 into 2400 x 1600, which the editor never does.
  `EditorMeasurementsTest -e all true -e drag <control>` measures the editor itself with a finger (`input swipe`).

### Profile before (main, GPU timer queries, fit view, mean ms per pass)

| Pass | luminance drag | colour drag | sharpening / texture / clarity drag |
| --- | --- | --- | --- |
| parameters (CPU, every frame) | 3.0-4.2 | 3.3-4.0 | 1.3-4.1 |
| luminance levels (3 levels x 2 axes) | 4.9-6.9 | - | - |
| blurs (2 passes, RG32F) | 2.6-4.0 | - | - |
| colour levels (binning + 2-3 half-res levels) | - | 2.5-3.5 | - |
| final pass (full resolution) | 2.8-4.0 | 4.1-4.4 | 3.2-5.1 |
| mipmaps of the working texture | ~1.2 (hidden) | ~1.2 | ~1.2 |
| output pass (1080 x 720, trilinear) | 0.6-0.9 | 0.9-1.1 | 0.7-1.2 |

Interleaved breakdown of the final pass (sharpening drag, every tool on, variants alternating frame by frame): 3.9 ms
with everything, 2.2 with sharpening alone, 1.9 for a pass that only reads the OkLab input, converts and writes: **a
2400 x 1600 pass costs about 2 ms whatever it computes** (memory traffic), colour NR's upsampling and the Effects
grid about 0.6 each. `mediump` made it slower (6.8 against 5.3 ms).

### Changes

1. **The luminance smoothing chain no longer depends on the sliders** (pixels change, `PIPELINE_VERSION` 10). The
   edge stop of each level was `sigma_e = tau * mix(8, 3, detail)`, so every luminance drag re-ran 2 passes per level.
   It is now `sigma_e = 1.5 * LUM_TAU_MAX * LUM_LEVEL[i] * noiseFactor` (`DetailParams.EDGE_SIGMA`): it follows the
   expected noise, and amount, detail and contrast act through the shrinkage alone. Measured on the synthetic card
   (`DetailOpTest`, and a sweep of the factor 1-3): the flat-area noise left at amounts 30-60 equals the old stop's
   (0.0015 / 0.0075 L at noise 0.06 / 0.12, amount 60), and edges and thin lines survive high amounts much better
   (amount 100: step edge kept 99 % instead of 78 %, a 1 px line 97 % instead of 62 %); Luminance detail 100 keeps a
   little more noise, as its name says. The preview keeps the chain `L_1 .. L_n` in a packed RGBA16F texture
   (`LUM_V_CHAIN_FRAG`, one channel per level, colour-masked) and a luminance drag is **one** pass (`SHRINK_FRAG`:
   every level's shrinkage in order, float16 as before: equal to the fused levels value for value). Tiles and the
   export keep the fused levels.
2. **The blurs store float16** (RG16F with `q16`, mirrored in `DetailOp.blur`): half the traffic of RG32F; the error is
   below 2^-11 relative, 0.05 of an 8-bit level after sharpening.
3. **The colour path's binning is kept** across a colour drag (it depends on the develop output only).
4. **Half-resolution final pass at the fit view.** When every pane reads only level 1 or coarser of the working
   texture (axis-aligned, 50 % or less, no tiles, no loupe, no mask overlay), the final pass writes level 1 directly:
   each fragment evaluates its 2 x 2 block (the colour upsampling's 3 x 3 half-resolution fetch shared by the four)
   and writes their mean. Level 0 is rendered on demand (one final pass, inputs cached) when anything reads it.
   `PreviewPassesTest`: the levels above match the full render's mips within 1 level (mean 0.01-0.03).
5. **No mips per frame.** The output pass reads the working texture with four bilinear samples at an explicit level
   spread over the pixel's footprint (`OutputSampling.MODE_BOX`, ADR 0002) for minified axis-aligned views down to
   1/3; mips are generated lazily below that, from level 1 when only it is current.
6. **The output pass covers the photo only.** On screen the window is the whole display behind the chrome; the pass
   ran its shader over 2.5 MP to show 0.8. It is now scissored to the output's rectangle, the rest cleared to the
   surround (4.0 -> 1.35 ms on screen).
7. **Parameters kept across a drag.** Everything but Detail and Effects is resolved once while the rest of the
   recipe (geometry aside) is unchanged (`DevelopParams.withLocal`): 1.3-4.2 -> 0.1-0.4 ms of CPU per frame.

Tried and rejected (measured): framebuffer invalidation before each pass (no change on the Adreno, kept as a hint);
the shrinkage inline in the first blur axis (4.6-5.6 ms against 4.0 for the separate passes); the vertical blur
inside the final pass (11-12.5 ms: the 2 x 2 block recomputes it); 16 explicit texel fetches for the minification
(1.9 ms against 0.8 for four bilinear samples); a half-resolution Detail render during a drag (a visible pop: 28 %
of the pixels of the noisy card more than 2 levels off at the displayed scale, dehaze's atmospheric light differs).

### After (phone, every tool on, fitted to 1080 x 720, ms)

| Drag | before: latency p50 / p95 | pipelined | GPU per frame p50 / p95 | latency p50 / p95 |
| --- | --- | --- | --- | --- |
| luminance | 27.9 / 39.5 | 6.31 | 5.41 / 8.34 | 8.65 / 12.18 |
| luminance detail | 20.5 / 34.8 | 5.4-9.8 | 5.3-9.1 / 7.8-9.2 | 11.5 / 14.1 |
| luminance contrast | 18.1 / 27.0 | 7.19 | 6.40 / 7.93 | 12.1 / 14.1 |
| colour | 17.4 / 21.6 | 4.00 | 3.47 / 4.82 | 8.59 / 11.1 |
| colour detail | 12.4 / 21.8 | 3.93 | 3.47 / 5.17 | 8.58 / 10.3 |
| colour smoothness | 8.5 / 18.3 | 4.33 | 4.09 / 5.28 | 10.1 / 12.4 |
| sharpening amount | 6.7 / 13.5 | 3.21 | 2.77 / 4.94 | 7.54 / 9.07 |
| sharpening radius | 8.1 / 14.1 | 4.42 | 4.33 / 4.88 | 12.1 / 14.4 |
| sharpening detail | 7.7 / 15.2 | 2.86 | 2.44 / 4.08 | 6.64 / 8.55 |
| sharpening masking | 5.8 / 8.5 | 3.05 | 2.74 / 4.30 | 7.38 / 8.64 |
| texture | 5.6 / 8.7 | 3.17 | 2.74 / 4.28 | 7.60 / 9.59 |
| clarity | 8.1 / 12.9 | 3.17 | 2.76 / 4.06 | 6.54 / 8.27 |
| dehaze | 7.1 / 8.4 | 2.96 | 2.46 / 5.03 | 7.67 / 9.00 |
| vignette | 4.2 / 6.3 | 1.27 | 0.82 / 1.30 | 3.11 / 4.36 |
| grain | 4.0 / 7.4 | 1.23 | 0.83 / 1.30 | 3.03 / 4.54 |

"Before" is main's code with the new profiler, on a cool phone with M2/C's 10-frame warm-up (its latency is the best
case: a warm phone read 1.5-2x more). The luminance-detail row varies with the phone's temperature (5.4-5.9 ms
pipelined in three runs, 9.8 in the last, hot one). A landscape frame (1560 x 1040, scale 0.65, so the full-resolution
final pass) stays within the target too: 1.9-7.3 ms pipelined, GPU p95 <= 8.7. At 1:1 into 2400 x 1600 (M2/C's
benchmark, never on screen) sharpening and texture drags still cost 10-17 ms: the full-resolution final pass and a
3.84 MP output.

Per pass after (GPU timer queries, fit view): shrinkage 2.0-2.2, blurs 3.2-4.4, final pass at half resolution
3.6-4.4, colour levels 1.4-2.1, output pass 0.8-1.0, mipmaps none, parameters 0.1-0.4 ms CPU.

**On screen** (`EditorMeasurementsTest -e all true`, CC0 ZV-E10, a finger dragging for 4 s, three runs each, main and
M2/P alternating on the same phone; render+swap with `glFinish`, p50 / p95 ms): luminance 9.3 / 23.7 -> 7.0 / 19.1,
colour 8.8 / 20.3 -> 7.4 / 13.4, sharpening radius 8.8 / 20.6 -> 7.4 / 17.3, texture 9.1 / 13.4 -> 7.7 / 10.4,
vignette 8.8 / 10.8 -> 6.8 / 8.7. Without the sync the editor presents 90-100 frames per second while the finger
moves on both builds, even for the vignette (1.4 ms of GPU): the drag's frame rate on screen is now set by how the
slider's updates arrive (the injected touch events), not by the renderer.

**Target status:** met at the display rate for every Detail and Effects slider (pipelined <= 7.2 ms; GPU per frame
p95 <= 9.2 ms; one hot run of luminance detail 9.8 ms pipelined) and for the Transform in the editor (ADR 0015). Not
met as serialised latency for the luminance, contrast, colour smoothness and sharpening radius drags (p50 10-12 ms,
p95 12-14 ms) nor on screen at p95 for luminance and sharpening radius (17-19 ms with `glFinish` per frame): those
drags re-run the shrinkage and both blurs, three full-resolution passes. Next levers: the blurs at half resolution at
the fit view (the final pass then reads them per 2 x 2 block: changes the sharpening of the fit view only, needs a
display-only parity argument), and a single 2D blur pass for small radii.

## Pixel alignment at 1:1 (M2/T)

`EditorZoomTest` failed on the owner's Galaxy Tab S9 Ultra (Adreno 740) with max 110 levels while it passed on the
phone (Adreno 750), on `main` before the tablet layout too. The cause is not the tiles. Every stage was checked on the
device: the decoded source, the upload, the scene pass, the develop pass and the atlas as written by the worker and as
read by the display context were all right. The cause was the **view**. At exactly one screen pixel per image pixel
the output pass reads `texelFetch(floor(p))`. The editor's double-tap to 100 % on that screen (k = 4000 / 2960) put
the screen pixel centres on full-resolution pixel **edges** (fraction 0 instead of 0.5), so `floor` followed the last
bit of float rounding: whole rows (and columns) took the neighbouring image row, differently on the GPU and in the CPU
reference, and differently from row to row on the GPU itself. That is a duplicated or skipped pixel row every dozen rows
on screen, not only a test failure: `ZoomTilesTest#aHundredPercentViewOnPixelEdgesShowsEveryPixelOnce` at the same k
on the tablet, before the fix, matched the export at no single offset (the best still differed in 9,364 values). With
a power-of-two k the maths is exact and the problem hid, so the phone and the earlier tests never saw it.

The fix is `zoom/PixelAlignment`. `GpuDevelopRenderer.drawOutput` moves the view centre by less than half an image
pixel whenever a pane reads by `texelFetch`: the full-resolution tiles at 100 %, or the proxy at scale 1, on an
axis-aligned mapping. The shift is computed in double from the same mapping the shader uses, so that every screen pixel
centre lands on an image pixel centre. `floor` then has half a pixel of margin on every GPU, and the CPU agrees.
`renderShown` returns the view as drawn, so tests and hit testing map pixels the same way. Nothing else changes: the
photo controller keeps its own view, and a shift of up to half a pixel at 100 % cannot be seen. Fitted and
magnified views and straightened geometry (Catmull-Rom) are untouched.

Regression checks: `PixelAlignmentTest` (JVM: worst-case views for five crop/turn/flip geometries, proxy and tiles;
every window pixel centre at fraction 0.5 ± 0.05 with the shader's float maths) and the instrumented
`ZoomTilesTest#aHundredPercentViewOnPixelEdgesShowsEveryPixelOnce` (k = 1200 / 888, pixel-edge view, bit-identical to
the export at one offset). On the tablet after the fix, `EditorZoomTest` measures max 4 levels and mean 0.11 (Detail
on), within the existing tolerance. Export was never affected (`TiledRendererTest`, `ExportFlowTest` and `GpuParityTest`
pass on the tablet): its output pass is 1:1 on pixel centres by construction.

## Limits and follow-ups

- The noise reduction did not know the camera's noise: fixed in M2/C (the noise model above). A per-image estimate
  (the finest wavelet level) would also cover in-camera NR differences and unknown cameras.
- Tiles are made from 50 % up; between the proxy's scale and 50 % a 61 MP photo's proxy is magnified up to 2x.
- A Detail drag cost 23 ms per frame on the Adreno 750 (above); M2/C and M2/P (sections above) brought every Detail
  and Effects drag to the display rate; the serialised latency of the luminance and blur drags is still 10-14 ms.
- `GpuParityTest.outputMatchesCpuReferenceIn8Bit` fails on the Linux host GPU (NVIDIA via the emulator's translator)
  for one Light-only regression recipe on the sRGB chart (5 levels, also on `main`); it passes on the phone.
