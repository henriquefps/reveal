# ADR 0023: Healing

Status: accepted (M5/H). Date: 2026-11-14. Amends ADR 0002 (a slot before slot 1, "Healing"), ADR 0003 (the recipe's
`heals[]`, until now a placeholder), ADR 0009 and ADR 0012 (export and zoom tiles read the healed image). Builds on ADR
0022 (the mask brush: strokes, pressure, `MaskView`, partial updates). `PIPELINE_VERSION` is unchanged: a recipe without
spots renders bit for bit as before (the golden CRCs of `CpuDevelopTest` did not move).

Code: `:core:pipeline` `recipe/Heals.kt` (model), `heal/` (engine: `Footprint`, `Membrane`, `HealEngine`,
`SourcePicker`, `HealOp`, `HealedTileSource`, `Remove.kt` + `RemoveSpec` + `PatchFill` for Remove), the renderers
(`GpuDevelopRenderer`, `TiledRenderer`, `ZoomTiles`, `LightPasses.guidePartial`), the Visualise spots view in
`Shaders.OUTPUT_FRAG`; `:feature:editor` `heal/` (the Heal mode); `:feature:export` passes the Remove fills.

## Decision in one paragraph

Healing is **slot 0: it repairs the input image** (un-balanced camera RGB for a RAW, the file's linear RGB otherwise)
before raw white balance, so every operator (including highlight reconstruction's per-pixel step, the local-tone guide,
masks' range colours, Detail and Effects) sees the repaired pixels, a global slider never re-runs a repair, and sensor
dust is removed where it physically is. Each spot is computed **once per edit on the CPU** by one implementation
(`HealEngine`) into a patch of input pixels; the preview uploads the patches into a healed copy of its input texture,
the zoom tiles and the export read the full-resolution original through `HealedTileSource`, which applies the same
engine at that resolution. So preview, zoom and export run the same code, and GPU parity is the existing pipeline's
(the GPU reads healed pixels). Spots are applied in order, each reading the image as the spots before it left it.

## The recipe (additive, ADR 0003)

`Recipe.heals: List<Heal>`, each:

```json
{"id": "9b1…", "kind": "heal", "x": 0.31, "y": 0.42, "radius": 0.012, "stroke": "",
 "dx": 0.05, "dy": -0.01, "autoSource": true, "feather": 50, "opacity": 100, "method": "patch", "darken": 50}
```

- `kind`: `heal`, `clone`, `remove`, `redEye`. A kind this version does not know is kept verbatim and renders nothing
  (`HealKind.UNKNOWN`).
- Target: a circle at (`x`, `y`) of `radius`, or, when `stroke` is set, a brush stroke (the mask brush's point codec,
  `StrokeCodec`) painted with dabs of `radius` scaled by pen pressure (`k(p)` of the mask brush, size only). Positions
  are fractions of the upright image before crop and transform, lengths fractions of its long edge (as masks). The
  pre-M5 placeholder `{"id": …}` has `radius` 0 and renders nothing.
- Source: the target moved by (`dx`, `dy`) (fractions of width and height), rounded to whole pixels of the image being
  rendered (a copy never resamples). `autoSource` records whether the editor's search picked it.
- `feather` (soft edge, % of the radius), `opacity` (%), `darken` (red eye, %).
- `method` (Remove only): `patch`, or `model:<id>@<version>` (M5/R). A method the app does not have falls back to
  `patch` (the spot still renders).

**History** (`HistoryField`, group `HEALS`): `HEAL_ADD`, `HEAL_DELETE`, `HEAL_SOURCE` (offset or `autoSource`),
`HEAL_EDIT` (anything else), with the kind as the value (`history_heal_*`, `history_value_heal_*` in both languages).
Spots are matched by `id`; their order is not a step. **Presets** exclude `heals` (already in `PresetFields.EXCLUDED`).
**Copy/paste**: the "Healing" group exists since M1; it is now left out of `RecipePaste.DEFAULT_GROUPS` like masks
(pasting a shoot's sensor-dust spots onto its other photos is a deliberate choice). Sync: the recipe JSON travels.

## The operators

Each spot has a **footprint** (`Footprint`): alpha in 0..1 over its box at pixel centres, `1 - sstep(R(1-f), R, d)` for
a circle (`R` = radius x long edge, `f` = feather), the mask brush's dab raster for a stroke (full flow). Every kind ends
`out = T + alpha · opacity · (H − T)` (exactly `H` at full strength), `T` the image under the spot, `H` the repair.

| Kind | `H` |
| --- | --- |
| Clone | `S`: the source pixels |
| Heal | `(S + e) · exp(m) − e` with `m` the **membrane** of `d = ln(T + e) − ln(S + e)` on the ring around the shape (3 x 3 means), `e` = 1e-3: the source's texture with the target's light (multiplicative Poisson blending: the gradient-domain solution of Pérez et al. 2003 in the log domain, so it never goes negative and handles light changes as gains) |
| Remove | the fill's pixels (below), then blended into the edge with the same membrane |
| Red eye | in linear sRGB (as-shot white balance), pupil weight `sstep(0.12, 0.3, (r − max(g, b))/(r+g+b)) · sstep(0.002, 0.01, r+g+b)`, smoothed by two box blurs of 8 % of the radius; the pupil goes to `0.5 (g + b) (1 − 0.8 darken)` neutral, back to camera RGB |

**Membrane** (`Membrane`): the harmonic function inside the shape with the ring's values, by a cascadic multigrid
(coarse grids by 2 x 2, a coarse cell fixed when any of its cells is; coarsest by Gauss-Seidel; per finer level a
bilinear prolongation then red-black Gauss-Seidel, 6 sweeps at the finest, doubling per coarser level up to 64).
Cells outside the image reflect. Deterministic; `MembraneTest` checks a linear boundary gives the plane (within 2 % of
the boundary's span) and the residual.

**Source pick** (`SourcePicker`, editor only: the result is stored, so renders never search): on the proxy with the
spots before it applied, in the display encoding below, the mean squared difference between the ring around the target
and around `target + o`, plus how much more the source's inside varies than the target's ring (a source with an edge or
another blemish is penalised); 24 directions x 7 distances from the first non-overlapping one (overlap ≤ 5 %) to 4.5
times it, inside the image, then a pattern search down to one pixel. Deterministic.

## Remove

**The contract** (for M5/R, `heal/Remove.kt`): a method gets a `RemoveInput` — a **context crop** of the original photo
around the region (`width x height`, RGB in the **display encoding**: camera → linear sRGB with the photo's as-shot white
balance, times a gain that brings the crop's median luminance to 0.18, then the sRGB curve extended oddly above 1;
`RemoveColour`), the **hole** (1 = fill; the shape on the crop, dilated by 2 px) and a **seed** — and returns a
`RemoveOutput`: the filled crop (same size and encoding) and, optionally, **offsets** (per hole pixel, `(dx, dy)` to the
crop pixel whose neighbourhood fills it). `RemoveMethod.fill` must be pure and deterministic. `RemoveFills` maps method
names to methods (M5/R registers `model:<id>@<version>`), runs them on one worker thread and caches the last 64 fills
by key; `RemoveFills.shared` serves the editor, its zoom and the exports of the process.

**The crop is defined on the original** (`RemoveSpec`): the region's box grown by its larger side on every side,
clipped, and the original box-downscaled by an integer factor so its longer side is at most 512 px. The editor reads it
from the full-resolution source once it is loaded (until then the proxy), the export from its own full-resolution decode:
the same decode of the same file gives the same crop, the same key (`RemoveSpec.key`: an identity of the original —
its size and a 16 x 16 sample of its pixels, hashed — the crop, the shape and the method) and so the same fill (from the
cache, or recomputed identically). The crop is always the **original** image, not the image with the spots before it:
a key that never depends on other spots; the copy below reads the current image, so a healed dust spot that the fill's
offsets point at is copied healed.

**Applying a fill** at any resolution (`HealEngine.propose`): with offsets, each pixel of the box maps into the crop,
takes its four neighbouring crop pixels' offsets (bilinear weights), scales them to the image and copies the image's own
pixels there (whole pixels, no resampling): sharp at 100 % and in a 24 MP export whatever the crop's factor. Without
offsets (a model's output), the crop's pixels are sampled bilinearly and decoded. Then the membrane blends the result
into the edge and the footprint (feather, opacity) composites it.

**The patch method** (`PatchFill`): multi-scale image completion (Wexler, Shechtman and Irani 2007) with the PatchMatch
search (Barnes et al. 2009), 7 x 7 patches, SSD in the display encoding: a pyramid down to where the hole's larger side is
at most 7 px; at the coarsest level the hole starts as its edge's membrane; per level 3-8 rounds of two PatchMatch passes
(propagation alternating scan order, random search in halving windows) and a vote (each hole pixel the mean of what the
covering patches propose, weighted `exp(−d / 2σ²)`, σ² the median distance); the field and the voted hole are upsampled
to the next level. Sources are patches inside the crop that touch no hole pixel. Deterministic (xorshift64* seeded by the
key). Measured on the phone (Adreno 750 device, the CPU): a 120 px region (crop 360 px) 0.7-1.0 s cold, a 360 px region
(factor 3) 0.34 s.

**While a fill is computed** the spot renders nothing (the renderer asks every update; the source calls back when it is
ready and the preview, the zoom tiles re-render); the editor shows a spinning arc on the spot and a progress bar
(`HealStatus.pending`). A failed method (it threw) renders nothing and the controls say so. An export waits for its fills
(at most two minutes).

## Everywhere the same

- **Preview** (`GpuDevelopRenderer`): the engine reads the proxy's input texture back through a block cache
  (`GlSourceReader`, 128 px blocks read once); a change restores the changed rectangle of the healed texture from the
  source and writes the patches that meet it (grown to a closed set, so overlapping later patches are rewritten after
  earlier ones). The scene pass reads the healed texture; **highlight reconstruction's chroma grid reads the original**
  (a spot never changes how far-away clipped highlights are rebuilt; the per-pixel step still sees healed pixels), in
  the preview, the tiles and the CPU reference alike. "Show original" and the compare panes render unhealed.
- **Zoom tiles and export** (`TiledRenderer`): the source is wrapped in a `HealedTileSource` (kept by the zoom across
  edits, recomputing only changed spots); the zoom's grids include the spots when the guide or the Effects grid is on.
- **CPU reference** (`CpuDevelop.renderLinear(…, heals = HealContext(fills, original))`).

## Partial updates and speed

A new spot or a source drag changes the healed input inside a rectangle `R`. Then (`runStage1`, `prepareScene`): the
scene pass runs scissored to `R`; the local-tone guide recomputes only the row segments of the cells `R` touches
(`LightPasses.guidePartial`) and its low-resolution steps; the develop pass and every Detail and Effects pass run on `R`
grown by the guide's reach (`(2 · 6 + 3)` cells: two box means, the cell alignment and the bilinear neighbour) through
the masks' partial path (ADR 0022 "Speed"). Range masks and auto-mask brushes read the pre-local image, so with them a
spot change renders whole. `HealRenderTest#partialUpdatesEqualAFreshRender` checks the result is bit-identical to a
fresh render.

**Measured on the owner's phone** (Galaxy S24 Ultra, Adreno 750; `PipelineBenchmark#healSpotAndSourceDrag`, 2400 x 1600
proxy shown 1080 x 720, 30 dust spots, highlights/shadows + Detail + Effects on):

| | pipelined ms / frame | GPU per frame p50 / p95 |
| --- | --- | --- |
| Drag a spot's source, every stage on | **5.37** (target met) | 3.31 / 8.08 |
| Drag a spot's source, no other edit | **2.49** | 1.69 / 1.74 |
| A new spot every frame (on top of 30), every stage on | **6.04** (target met) | 4.32 / 9.98 |
| Global exposure with 30 spots | 17.4 | 13.7 / 20.9 |
| Global exposure without spots (reference) | 14.9 | 13.5 / 17.1 |

The global drag costs what it costs without spots on the GPU (the spots are never recomputed); both miss 8.3 ms because
of the Detail and Effects stages (ADR 0022 "Out of reach"). Remove: see above (once per edit, on its worker).

**Parity** (`HealRenderTest#healsMatchCpuReference`, phone): heal, clone, a stroke and Remove with the Light, Detail and
Effects stages: worst error 0.26 of ADR 0002's tolerance, 8-bit at most 1 level; a tiled export with 256 px tiles is
bit-identical to 1024 px tiles and 1 level from the preview.

## Visualise spots

A display-only view (`GpuDevelopRenderer.setVisualiseSpots(strength)`, the output pass's `spots` uniforms): the
luminance's relative difference to a coarse level of the working texture (three levels above the displayed one,
explicit LOD), `0.5 + gain (L − Lm) / (Lm + 0.02)`, shown in grey; `gain` 4..40 from the strength. Dust (a soft dark
disc) shows as a dark blot on flat grey. Never in the histogram, an export or the recipe.

## Editor

Heal is a **mode** (ux.md): the strip swaps to its controls (kind chips Heal / Clone / Remove / Red eye; Size, Feather,
Opacity and Darken (red eye) — one slider with chips in portrait, all of them in the landscape/tablet column;
Visualise spots and its contrast; the spot count, New source and Delete for the selected spot; Remove's progress or
failure, or a hint) with Cancel and Done. The mode carries a `PhotoLayer` (`ToolBehaviour.Mode.photo`): a tap adds a
circle spot of the size (Heal and Clone get the automatic source first), a drag paints a stroke spot (pen pressure sets
the dab size; a short stroke is a tap), a drag of the selected spot's source moves it, a drag of a spot moves it, a tap
on a spot selects it. Every spot is drawn (its shape; the selected one in the accent colour with its dashed source and a
line), the brush under the finger or a hovering pen; once a pen is seen fingers only pan and zoom; a second finger
cancels a stroke. Positions go through `MaskView`, so the overlay follows crop, turns, lens corrections and zoom. Every
spot, release, kind change, new source and deletion is one history step (undo per spot); Cancel returns to the spots of
the mode's start (one step), Done keeps them. Keys: `H` opens the mode, `[` / `]` resize, `V` toggles Visualise spots.

## Tests

JVM: `HealEngineTest` (clone exactness, heal quality vs the clean image, heal beats clone across a light change,
strokes, layering, incremental = fresh, dirty rectangle, opacity, edges), `MembraneTest`, `RemoveTest` (stripes are
continued, deterministic, crop geometry, a half-size render uses the original's fill, a model's pixels, tiles = whole),
`RedEyeAndSourceTest`, `HealCodecTest`, `HealDiffTest`, `HealEditsTest`. GPU (`HealRenderTest`): parity, dust removal,
partial updates, a fill that arrives later, Visualise spots, tiled export. Instrumented (`HealFlowTest`): tap, paint,
drag the source, switch kind, delete and undo, reopen; Cancel; Remove an object; red eye on the public-domain portrait
(red pupils painted at the eyes BlazeFace finds). Goldens `phone_heal*`, `landscape_heal`, `tablet_heal`,
`tablet_portrait_heal`, `foldable_heal`.

## Consequences

- `PIPELINE_VERSION` stays 10.
- `RecipePaste.DEFAULT_GROUPS` no longer contains `HEALS`.
- `TiledRenderer.render(…, heals = RemoveSource)`; `TileSource` reads are serialised on the source (the Remove worker
  reads the original too).
- Open: Remove's model (M5/R) plugs into `RemoveFills` by method name; a disk cache for fills (a fill is recomputed
  after the process ends, or on another device from the same original); pet eyes (green/yellow shine) for red eye.

## Amendment M5/R: model methods (ADR 0024)

Date: 2026-11-14. `RemoveFills` gains `register(name, method)` (the app registers `model:big-lama@1` at start, before
any render; a registration drops fills an earlier render made under the patch method for that name) and `models()`;
a method may throw `RemoveUnavailableException` (an inpainting model without enough memory), and the spot is then
filled by the patch method with `RemoveFill.fellBack` set, reported as `HealStatus.fellBack`. A fill without offsets
gets grain where the render is finer than the crop (`heal/Grain.kt`). The patch method and its results are unchanged.

## Amendment FIX/H: speed, live result, cancellation, thumbnails (0.7.1)

Date: 2026-11-14. The owner's test of 0.7.0 on 24 MP RAWs (Galaxy S24 Ultra and Tab S9 Ultra): dragging a spot
recomputed it every frame, a Remove after a drag froze the editor and kept running after the mode or the photo was
closed, the result was hidden by the spots' overlay, a big Remove was slow, thumbnails missed healing (and every
cropped or turned edit), and a few crashes.

**Edits on release.** The editor sends a spot's drag (its target, its source, its size slider) only when it ends
(`HealUiState.dragging` is the overlay's copy until then; a second finger cancels it). Feather, opacity and darken stay
live (they do not change a fill's key). Only the selected spot shows its painted area; the others are thin outlines,
and the eye button hides them all (`HealTags.OVERLAY_TOGGLE`).

**Never on the GL thread.** The preview's engine moved to `gl/HealWorker`, one thread per renderer: the GL thread
submits the newest spots (a burst of edits computes the last one) and keeps drawing with the healed texture it has;
results come back in order through `healInbox` and are uploaded (`applyHealResult`), then a frame is asked for. The
engine reads the proxy through `GlSourceReader`, now thread-safe: 64-row strips (each readback waits for the GPU, so
few of them) fetched by a hop to the GL thread (`glCall`), failing with `CancellationException` once the renderer
closes. Offscreen renders (tests, thumbnails, benchmarks) wait for the worker and serve its reads meanwhile; on-screen
frames never wait. `pickHealSource` runs on the worker. Results are unchanged bit for bit (same engine, same order).

**Cancellation.** `RemoveFills` keeps one job per key with the callers waiting for it; the worker takes the newest
job first. `RemoveSource.release(onReady, keys)` drops a caller's interest; a job nobody waits for is removed from the
queue, or, when running, cancelled through `RemoveInput.cancelled` (`PatchFill` polls it every 8 rows of each
PatchMatch pass; `InpaintService` before loading the model, before running it and after): nothing is cached and the
key is not marked failed. `HealEngine` releases the keys of spots that moved, changed or were deleted after every
update and all of them in `close()` (the renderer's close, `HealedTileSource.close`: the zoom's and an export's).

**Speed.** The patch method searches a crop whose factor also keeps the region's larger side at most
`RemoveSpec.PATCH_REGION` (96) pixels (offsets copy the photo's own pixels at any resolution, so it stays sharp; small
spots keep full resolution); model methods keep the 512 px crop. `RemoveSpec.VERSION` 2. `HealWarmUp` runs a small
synthetic heal and patch Remove on the worker when a renderer gets its heal source, so the first real spot does not
run interpreted code.

**Measured on the Galaxy Tab S9 Ultra** (Adreno 740; `PipelineBenchmark#healSpotAndSourceDrag -e only 24mp` and
`AiRemove24MpBenchmark`: the CC0 24 MP `sony-zv-e10.arw`, 2400 px proxy, the full decode as the original, a fresh
`RemoveFills`; time from the edit to the frame that shows it; the stroke is 300 px long at the default size; the drag
is 30 frames, then the release):

| | 0.7.0 | FIX/H |
| --- | --- | --- |
| New Remove stroke, Patch (first of the session) | 1474 ms | **682 ms** (warm-up done at open: 734 ms) |
| Drag-release of it, Patch, release only (the editor since FIX/H) | 431 ms | **291 ms** (fill 124 ms: crop read 46, patch 77) |
| Drag-release, Patch, every drag frame sent (the editor in 0.7.0) | **7311 ms** | 276 ms (superseded fills cancelled) |
| New Remove stroke, AI (model loaded, run, released) | 5118 ms | 4915 ms |
| Drag-release, AI, release only | 2751 ms | 2956 ms |
| Drag-release, AI, every drag frame sent | **75426 ms** | 2821 ms |
| New heal spot | 32 ms (on the GL thread) | 102 ms (on the worker; frames go on meanwhile) |

The heal spot's latency moved off the GL thread: the drawing thread no longer spends anything on healing, at the cost
of the hop and the worker's scheduling. An AI fill stays bounded by ADR 0024's policy (the model loaded for each fill
and released after it).

**Thumbnails and the viewer.** `GpuEditedThumbnails` rendered `renderOutput` into a bitmap of the input's size: any
crop, turn or Transform failed and the library showed the original; it now uses `renderOutputImage` (the geometry's
size). Healing: fills outlive the process in a `RemoveFillStore` (`:core:data` `HealFillCacheArea`, `cache/heal-fills`,
budgeted with the AI masks), keyed by `RemoveRequest.storeKey` (the file's fingerprint, the crop on the original, the
shape, the method) and written by the editor's fills once their crop comes from the original. A thumbnail asks with
the original's size (`RemoveContext.originalWidth/Height`): a stored fill (patch or AI) is used as is; a miss is a
patch fill made on the thumbnail itself, in its own read-only `RemoveFills`. The grid's first thumbnail does not wait
for fills (`renderQuick`); the complete one replaces it (`ThumbnailRepository.withAiMasks`, now also for Remove spots);
the viewer's developed page waits. Edited thumbnails cached before are made again (`EDITED_VERSION` 2).
`EditedThumbnailTest` compares a thumbnail of a recipe using every tool with the editor's render downscaled (tablet:
mean 0.94, p95 3 levels; the removed object gone).

**Crashes.** `NativeRawTileSource` shared its buffer position between threads (the zoom tiles and the Remove crops
read it at once) and could be read after the zoom closed it (the photo closed mid-fill: freed native memory); reads
and close are now synchronised and a read after close throws `CancellationException` (`BitmapTileSource` too). A fill
that runs out of memory fails its spot instead of the app; a heal worker failure is logged and the last healed image
stays. `HealCancelTest` closes the photo while a fill runs (the fill stops, no worker is left, the heap returns) and
stresses 24 MP Remove drags with Patch and AI, rotation and trim-memory: no crash on the tablet.

**Tests on hardware (item 7).** `MasksFlowTest` and `SnapshotsFlowTest` clicked controls that portrait devices had
scrolled out of the docked panel: `performScrollTo` scrolls only the closest container (the masks row), not the panel.
The UI was right; the tests now use `scrollIntoView` (every enclosing container). `PresetsFlowTest` (also failing on
the tablet, in 0.7.0 too): one edge-to-edge swipe of the full-width strength slider stops at 5 % (crossing the 100 %
snap zone costs more than the slider's padding); it swipes again until the step is undone.
