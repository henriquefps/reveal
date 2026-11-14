## FIX/H (0.7.1): healing speed, live result, cancellation, thumbnails, crashes, phone test-order failures

ADR: `docs/adr/0023-healing.md`, amendment "FIX/H". CLAUDE.md "Healing" section, CHANGELOG `[Unreleased]` (Fixed).
**All device results below are from the owner's Galaxy Tab S9 Ultra** (Adreno 740, pt-BR), CC0 24 MP
`sony-zv-e10.arw` (6024 x 4024) and synthetic images only.

### What and why (the owner's seven items)

1. **Drags compute once, on release.** Dragging a spot's target or source, and the Size slider, move only the
   outline (`HealUiState.dragging`); the recipe changes once on release (one history step), with the existing Remove
   progress while it computes. A second finger cancels the drag. Feather / opacity / darken stay live (they never
   change a fill).
2. **No freeze, no work after leaving.** Healing left the GL thread: `gl/HealWorker` (one thread per renderer) owns
   the preview's engine and computes the newest spots; results are uploaded in order on the GL thread
   (`applyHealResult`). `GlSourceReader` is thread-safe (64-row strips, GL hop, fails once the renderer closes).
   Remove fills are jobs: newest first, `RemoveSource.release(onReady, keys)` drops interest, a job nobody waits for is
   dropped or cancelled (`RemoveInput.cancelled`, polled by `PatchFill`; `InpaintService` checks before load, before
   run, after). `HealEngine` releases fills of spots that moved/changed/were deleted and `close()`s all of them
   (renderer close, zoom and export `HealedTileSource`). Nothing survives the editor (`HealCancelTest`).
3. **Live result in the mode.** Only the selected spot shows its painted area, others a thin outline; an eye button
   (`heal_overlay_toggle`, strings in both languages) hides the outlines.
4. **Remove speed.** Patch searches a crop whose factor keeps the hole region ≤ 96 px (`RemoveSpec.PATCH_REGION`;
   offsets still copy the photo's own pixels at full resolution, so it stays sharp; small spots full resolution;
   `RemoveSpec.VERSION` 2); `HealWarmUp` JIT-compiles the heal path when the photo opens. Determinism and
   preview = zoom = export unchanged (same engine; tests below).
5. **Thumbnails / viewer show every edit.** `GpuEditedThumbnails` rendered into a bitmap of the *input's* size: any
   crop/turn/Transform failed and the grid showed the original. Now `renderOutputImage` (geometry's size). Healing:
   fills persist in a `RemoveFillStore` (`:core:data` `HealFillCacheArea`, `cache/heal-fills`, budgeted with the AI
   masks) keyed by file fingerprint + crop on the original; thumbnails use the editor's fill (patch or AI), else a patch
   fill at thumbnail size, in the background (`renderQuick` for the grid, complete one via `withAiMasks`); the viewer
   waits. Old edited thumbnails are remade (`EDITED_VERSION` 2). Audit: masks (all kinds incl. AI via the existing
   session), geometry/transform/lens (input metadata), Detail/Effects (scaled), heals — `EditedThumbnailTest`.
6. **Crashes.** `NativeRawTileSource` shared its buffer position across threads (zoom tiles + Remove crops at once) and
   could be read after the zoom closed it (photo closed mid-fill → freed native memory). Reads/close are synchronised
   and a read after close throws `CancellationException` (also `BitmapTileSource`). OOM in a fill fails the spot, not
   the app; a heal worker failure is logged.
7. **Phone/tablet-only test failures** were test issues: on portrait devices the panel is docked and was scrolled down
   to a slider, and `performScrollTo` only scrolls the closest container (the masks row), so "+" / the exposure value
   were clicked outside the panel. New `scrollIntoView(tag)` scrolls every enclosing container (Masks, Snapshots, Heal
   flow tests). Reproduced and fixed on the tablet. The full suite on the tablet also showed `PresetsFlowTest`
   failing (in 0.7.0 too): one edge-to-edge swipe of the full-width strength slider stops at 5 % (the 100 % snap zone
   costs more than the slider's padding); the test swipes again until the step is undone.

### Measurements (tablet, `PipelineBenchmark#healSpotAndSourceDrag -e only 24mp`, `AiRemove24MpBenchmark`)

Same benchmark file built against 0.7.0 and this branch; time from the edit to the frame showing it; 300 px stroke at
the default size; 30-frame drag then release.

| | 0.7.0 | FIX/H |
| --- | --- | --- |
| New Remove stroke, Patch | 1474 ms | **682 ms** |
| Drag-release, Patch, release only | 431 ms | **291 ms** |
| Drag-release, Patch, every frame sent (0.7.0's editor) | **7311 ms** | 276 ms |
| New Remove stroke, AI | 5118 ms | 4915 ms |
| Drag-release, AI, release only | 2751 ms | 2956 ms |
| Drag-release, AI, every frame sent | **75426 ms** | 2821 ms |
| New heal spot | 32 ms (GL thread) | 102 ms (worker; frames continue) |

### How tested

- `./gradlew qa` — green (unit tests, Roborazzi verify, lint, ktlint, detekt).
- New JVM tests: `RemoveJobsTest` (queued/running cancellation, newest first, shared waiters, patch cancel, engine
  release, store round trip, coarse patch crops, thumbnail-scale fills), `HealDragTest` (outline-only drags, release =
  one step, cancel), `HealFillCacheAreaTest`.
- Tablet: `HealCancelTest` (close mid-fill: fill cancelled, fills idle, no `reveal-heal-*` thread, heap back; 24 MP
  stress: 60 Hz Patch/AI drags, rotation, trim-memory, close mid-fill ×3 — no crash), `EditedThumbnailTest`
  (thumbnail vs editor render: mean 0.94, p95 3 levels; object removed), `HealFlowTest`, `MasksFlowTest`,
  `SnapshotsFlowTest` after `HealFlowTest`, `HealRenderTest`, `InpaintRemoveRenderTest`, full app suite twice.

Results (tablet, this branch):

- Full app suite (CLAUDE.md `notClass` list), **twice in a row without `pm clear`: 100/100 and 100/100**, crash buffer
  empty (before the test fixes: `MasksFlowTest` and `PresetsFlowTest` failed on the tablet, also with 0.7.0).
- `:core:pipeline` `HealRenderTest`, `TiledRendererTest`, `ZoomTilesTest`: 23/23 (heal parity 0.26 of ADR 0002's
  tolerance as before, partial updates bit-identical, 256 vs 1024 px tiles identical, preview within 1 level).
- `:core:ml` `InpaintRemoveRenderTest` (AI export = preview, tiles identical, object gone), `AiRemove24MpBenchmark`.
- `HealCancelTest`: the slow fill sees its cancellation when the photo closes, fills idle, no heal thread left,
  heap back within 16 MB; stress ×3 (Patch/AI drags at 60 Hz, rotation, trim-memory, close mid-fill): no crash,
  fills cancelled 30–46 per round. `EditedThumbnailTest`: thumbnail vs editor mean 0.94 / p95 3 levels, red pixels of
  the removed object 1158 → 0.

### What the orchestrator should run on the phone

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:pipeline:assembleDebugAndroidTest :core:ml:assembleDebugAndroidTest
adb -s RXCX109VTTM install --user 0 -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s RXCX109VTTM install --user 0 -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# files/samples/sony-zv-e10.arw into dev.hfps.reveal.debug (CLAUDE.md "Editor open flow"), then:
adb -s RXCX109VTTM shell am instrument -w -e class dev.hfps.reveal.editor.HealFlowTest,dev.hfps.reveal.editor.HealCancelTest,dev.hfps.reveal.library.EditedThumbnailTest,dev.hfps.reveal.editor.MasksFlowTest,dev.hfps.reveal.editor.SnapshotsFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
# the full suite, twice (CLAUDE.md "Editor open flow" notClass list)
adb -s RXCX109VTTM install --user 0 -r -t core/pipeline/build/outputs/apk/androidTest/debug/pipeline-debug-androidTest.apk
adb -s RXCX109VTTM shell am instrument -w -e class dev.hfps.reveal.pipeline.HealRenderTest dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s RXCX109VTTM shell am instrument -w -e class dev.hfps.reveal.pipeline.PipelineBenchmark#healSpotAndSourceDrag -e only 24mp dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner   # sample in the pipeline test app's files/samples
adb -s RXCX109VTTM install --user 0 -r -t core/ml/build/outputs/apk/androidTest/debug/ml-debug-androidTest.apk
adb -s RXCX109VTTM shell am instrument -w -e class dev.hfps.reveal.ml.AiRemove24MpBenchmark,dev.hfps.reveal.ml.InpaintRemoveRenderTest dev.hfps.reveal.ml.test/androidx.test.runner.AndroidJUnitRunner
adb -s RXCX109VTTM logcat -d -s REVEAL_BENCH REVEAL_ML REVEAL_HEAL_TEST REVEAL_THUMB_TEST
```

### Open

- An AI fill still costs a model load per fill (ADR 0024's memory policy: released after each fill); keeping the
  model loaded while the Heal mode is open would make later AI fills ≈ 0.3 s at +616 MB — an owner decision.
- The first Remove of a session is still ~2x a later one (JIT); a baseline profile for the release build would help.
