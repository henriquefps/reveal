## M5/H: healing — spot heal, clone, Remove (patch-based), red eye, Visualise spots

ADR: `docs/adr/0023-healing.md` (amends ADR 0002 / 0003). CLAUDE.md "Healing" section, CHANGELOG `[Unreleased]`.

### What and why

- **Slot 0: healing repairs the input** (camera RGB) before raw white balance, so every operator sees the repaired
  pixels, no global slider ever re-runs a repair, and sensor dust is removed where it physically is. Highlight
  reconstruction's chroma grid still reads the unrepaired input (a spot never changes far-away clips).
- **One CPU engine** (`heal/HealEngine`) makes one patch per spot, in order (each reads what the earlier spots left).
  The preview uploads the patches into a healed copy of its input texture; the zoom tiles and the export read the
  full-resolution original through `HealedTileSource` (same engine at that resolution). Preview, zoom and export run the
  same code; GPU parity is the existing pipeline's.
- **Heal** = log-domain Poisson blend (cascadic multigrid membrane of `ln T − ln S` on the ring), **Clone** = copy with
  feather, **Red eye** = redness-weighted pupil, desaturated and darkened, **Remove** = deterministic PatchMatch image
  completion (multi-scale, seeded) whose nearest-neighbour field copies the photo's own pixels at any resolution (sharp
  in 24 MP exports), blended into the edge with the membrane.
- **Contract for M5/R** (`heal/Remove.kt`): `RemoveInput` (context crop of the original, display-encoded, + hole mask +
  seed) → `RemoveOutput` (filled crop, optional offsets). `RemoveFills` runs methods by name (`patch` now;
  `model:<id>@<version>` registers there), one worker, cache by key; crops are defined on the full-resolution original
  (`RemoveSpec`) so the editor and the export ask for the same fill. Unknown method → falls back to `patch`.
- **Recipe**: `heals[]` (additive; the old `{"id": …}` placeholder renders nothing), history `HEAL_ADD/DELETE/SOURCE/EDIT`
  in both languages, "Healing" paste group (now out of the default paste choice, like masks), excluded from presets.
- **Editor**: Heal is a **mode** with a photo layer: tap = spot of the brush size (automatic source by patch search on
  the proxy), paint = stroke spot (S Pen pressure → size; hover shows the brush), drag the selected spot's source or the
  spot, kind chips (Heal / Clone / Remove / Red eye), Size / Feather / Opacity / Darken, Visualise spots (+ contrast),
  New source, Delete, spot count, Remove progress/failure. One history step per spot; Cancel restores the spots of the
  mode's start. Keys `H`, `[`, `]`, `V`. Works at fit and zoomed, follows crop/turns/lens/transform (`MaskView`).
- **Partial updates**: a spot change redraws the healed rectangle in the scene pass, the guide's rows for the cells it
  touches (`LightPasses.guidePartial`), and develop/Detail/Effects on the rectangle grown by the guide's reach, through
  the masks' partial path — bit-identical to a fresh render (tested).
- `PIPELINE_VERSION` unchanged (no output changes without spots; golden CRCs untouched).

### How it was tested (results)

- `./gradlew qa` — green (unit tests, Roborazzi verify, lint, ktlint, detekt; all modules).
- JVM: `HealEngineTest`, `MembraneTest`, `RemoveTest`, `RedEyeAndSourceTest`, `HealCodecTest`, `HealDiffTest`,
  `HealEditsTest` (+ `RecipeGroupsTest`, `RecipePasteTest` updated).
- **On the owner's phone** (S24 Ultra, Adreno 750 — results are from the phone, not an emulator):
  - `:core:pipeline` connected suite without the benchmark: **54/54 OK** (GpuParityTest, MaskRenderTest,
    TiledRendererTest, ZoomTilesTest, HealRenderTest, …).
  - `HealRenderTest`: parity worst error 0.26 of ADR 0002's tolerance, 8-bit ≤ 1 level; partial updates == fresh render
    (max diff 0.0); tiled export 256 px tiles == 1024 px tiles, 1 level from the preview; a late Remove fill renders;
    Visualise spots shows the dust.
  - `HealFlowTest`: 4/4, twice in a row without `pm clear`, on the rebased build (tap/paint/source drag/kind/delete/undo/reopen; Cancel; Remove an object: redness 0.97 → −0.14;
    red eye on the public-domain portrait: 0.69 → 0.13).
  - Full app suite (measurements excluded), before the rebase: 85/88; the 3 failures are not healing: 2 ×
    `MasksFlowTest` (masks "+" chooser timeout, see below) and `HandoffFallbackTest#aHandOffReadOnlyOnce…` (stale test,
    fixed on main by M4/F, which this branch is now rebased on).

### Measurements (phone, `PipelineBenchmark#healSpotAndSourceDrag`, 2400 x 1600 proxy shown 1080 x 720)

| | pipelined ms / frame | GPU p50 / p95 |
| --- | --- | --- |
| Drag a spot's source (30 spots, highlights/shadows + Detail + Effects on) | **5.37** ✔ | 3.31 / 8.08 |
| Drag a spot's source (30 spots, nothing else) | **2.49** ✔ | 1.69 / 1.74 |
| A new spot every frame on top of 30 (every stage on) | **6.04** ✔ | 4.32 / 9.98 |
| Global exposure with 30 spots / without (reference) | 17.4 / 14.9 | 13.7 / 13.5 (p50) |

Remove (patch, phone CPU, worker thread): 120 px region 0.7–1.0 s cold, 360 px region (factor 3) 0.34 s.

### Screenshots (public-domain / synthetic only)

- Goldens: `app/src/test/screenshots/editor/{phone_heal,phone_heal_remove,phone_heal_red_eye,phone_heal_visualise,
  landscape_heal,tablet_heal,tablet_portrait_heal,foldable_heal}.png`; `library/phone_paste_chooser_light.png` changed
  (Healing no longer ticked by default). No other golden changed.
- Before/after (`HealRenderSheetTest`, NASA public-domain fixtures): `docs/screenshots/healing/healing-dust.jpg`
  (synthetic dust on the sky, auto-source taps), `healing-remove.jpg` (a rock removed), `healing-red-eye.jpg` (red
  pupil painted at the detected eye, fixed).

### For the orchestrator to run on the phone

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:pipeline:assembleDebugAndroidTest
adb -s RXCX109VTTM install --user 0 -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s RXCX109VTTM install --user 0 -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s RXCX109VTTM install --user 0 -r -t core/pipeline/build/outputs/apk/androidTest/debug/pipeline-debug-androidTest.apk
adb -s RXCX109VTTM shell am instrument -w -e notClass dev.hfps.reveal.pipeline.PipelineBenchmark dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s RXCX109VTTM shell am instrument -w -e class dev.hfps.reveal.pipeline.PipelineBenchmark#healSpotAndSourceDrag dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s RXCX109VTTM shell am instrument -w -e class dev.hfps.reveal.editor.HealFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s RXCX109VTTM logcat -d -s REVEAL_HEAL REVEAL_HEAL_TEST REVEAL_BENCH
```

### Open issues

- `MasksFlowTest` (2 tests: `everyHandMadeTypeIsCreatedEditedUndoneAndKept`, `theSubjectHairAndEyesOfAPortrait`) time
  out waiting for the masks "+" chooser on the phone. **Pre-existing**: the same 2 failures with the base commit's
  build (85e6c4c, no healing code) on the same phone, and still on this branch rebased on main (0277edb).
- Not done here: a disk cache for Remove fills (recomputed after a process restart or on another device), pet-eye
  (green/yellow) correction, the model method (M5/R).
- The global exposure drag with Detail/Effects on misses 8.3 ms with or without spots (ADR 0022 "Out of reach").
