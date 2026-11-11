# Reveal: notes for agents

Non-destructive RAW photo editor for Android (Kotlin + Jetpack Compose, GPU pipeline, LibRaw).
Start with `docs/plan/README.md` (the brief), then `docs/plan/` and `docs/adr/`.

## Rules (until the code exists, the plan is the source of truth)

- UI follows `docs/plan/ux.md`: the `hfps-app-ui` identity translated to Compose; the editor is
  always dark with a zero-chroma photo surround.
- No hard-coded UI strings: `values/strings.xml` and `values-pt-rBR/strings.xml` together.
- Every pipeline operator has a CPU reference (JVM-tested) and the GPU result matches it.
- Originals are never modified; edits are recipes (`docs/plan/architecture.md`).
- Credentials only through `skv` (group `reveal`). Never commit keystores or secrets.
- Private photos: copies only, never committed, never in screenshots, aggregates only.
- Workers use emulators. Only when the emulator itself keeps crashing may a worker run its debug/test APKs on the
  owner's phone (`RXCX109VTTM`, on the Mac's USB, adb over SSH); if it is not connected, ask the orchestrator.
  Release installs on the phone are the orchestrator's.
  On the phone always install with `adb install --user 0` (it has a Dual App profile, user 95: a plain
  `adb install` adds a second icon there), and uninstall `dev.hfps.reveal.debug` after a test run.
- Code, identifiers, comments and ADRs in English.
- Design system (`:core:designsystem`, ADR 0005): wrap screens in `RevealTheme` (the editor in `EditorTheme`), build
  them with `RevealScaffold`/`RevealTopBar`/`Eyebrow`/`RevealButtons`/`RevealIconButton`/`RevealChip`/`RevealListRow`.
  Colours only via `RevealTheme.colors` (no raw `Color(0x..)`, no `Color.White` in screens), text only via
  `RevealTheme.typography` (nothing under 12sp, uppercase only through `Eyebrow`), icons only Phosphor
  (`PhosphorRegular`/`PhosphorFill`, never `androidx.compose.material.icons`). New icon: add it to
  `scripts/icons/icons.txt` and regenerate.

## Commands

Setup once per worktree: `local.properties` (git-ignored) with `sdk.dir=/Users/henriquefps/Library/Android/sdk`, and
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (no system JDK).

```sh
./gradlew :app:assembleDebug                      # debug APK, id dev.hfps.reveal.debug
skv run reveal -- ./gradlew :app:assembleRelease  # signed release (REVEAL_KEYSTORE* from skv); unsigned without it
./gradlew qa                                      # unit tests + screenshot verify + lint + ktlint + detekt, all modules
./gradlew :<module>:qa                            # same, one module (e.g. :core:pipeline:qa)
./gradlew ktlintFormat                            # auto-fix style
./gradlew testDebugUnitTest                       # JVM unit tests only
./gradlew :app:recordRoborazziDebug               # re-record screenshot goldens (app/src/test/screenshots)
./gradlew :app:verifyRoborazziDebug               # compare against goldens
ANDROID_SERIAL=emulator-<port> ./gradlew :app:connectedDebugAndroidTest :core:pipeline:connectedDebugAndroidTest
```

Generated design-system files (regenerate, never hand-edit; both scripts have `--check`):

```sh
python3 scripts/theme/convert_theme.py ~/.agents/skills/hfps-app-ui/assets/theme.css   # RevealTokens.kt + WCAG pairs (fails on regression)
python3 scripts/icons/generate_icons.py                                                 # PhosphorRegular.kt / PhosphorFill.kt (fetches the pinned npm release)
```

Emulator (own AVD, own port 5560-5590, headless, one per worker, delete when done; never the phone). The script picks
the host's image (arm64 on the Mac, x86_64 on Linux); `EMULATOR_LCD=1080x2340@450` makes it phone-like (the owner's S24
Ultra: some layouts only break at that size):

```sh
scripts/emulator.sh create reveal-<workstream>
scripts/emulator.sh start  reveal-<workstream> <port>   # waits for boot
adb -s emulator-<port> exec-out screencap -p > shot.png
scripts/emulator.sh stop <port> && scripts/emulator.sh delete reveal-<workstream>
```

Signed release check: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` (SHA-256 should
start `0d0c62a3...`). Only the orchestrator installs it on the phone. A connected test run uninstalls the debug app.

Layout: `build-logic/` convention plugins, `gradle/libs.versions.toml` all versions, `config/detekt/detekt.yml`,
`docs/adr/0001-stack-and-modules.md` (modules, DI, tests, versions). A module build file is plugins + namespace +
dependencies. Add a Room schema change with a migration and the exported file in `core/data/schemas`.

## Develop pipeline (`:core:pipeline`, ADR 0002 and 0003)

Packages: `recipe/` (model, codec, diff; pure Kotlin), `cpu/` (CPU reference; pure Kotlin), `gl/` (GLES renderer on its
own thread), `synthetic/` (generated test images), `zoom/` (the editor's tile grid maths). `PipelineInput` is the
pipeline's input type.

**Adding an operator** (one pattern, every time):
1. Recipe: add the field(s) to the group in `recipe/Recipe.kt` (default = no change; additive, so no `RECIPE_VERSION`
   bump) and, if the user sees it in history, a `HistoryField` + `RecipeDiff` branch + `history_*` strings in both languages.
2. CPU reference: an `object ...Op` in `cpu/Operators.kt` over `FloatArray` with the formula in KDoc; put its constants in
   `DevelopParams` (computed in Double, rounded once). Call it from `CpuDevelop` at its slot in the order of ADR 0002.
3. Shader: the same maths, line for line, in `gl/Shaders.kt` (fuse into stage 1 when it is per-pixel; its parameters go in
   the std140 `Develop` block and `GpuDevelopRenderer.developBlock`); local operators are extra passes.
4. Golden JVM test in `cpu/` on small synthetic images (`SyntheticImages`: gradients, colour checker, out-of-range).
5. Extend `GpuParityTest` recipes so the operator is exercised; keep within the tolerance of ADR 0002.
6. If any pixel output changes: bump `PIPELINE_VERSION` and the golden CRC in `CpuDevelopTest`.

Stages (ADR 0007): `SCENE_FRAG` (WB, highlight reconstruction, matrix, exposure) -> scene texture -> Light low-res passes
(`gl/LightPasses.kt`, on the `LowResGrid`, RGBA32F, skipped when neutral) -> `DEVELOP_FRAG` (highlights/shadows,
profile, contrast/whites/blacks, vibrance/saturation) -> working texture -> output. A neighbourhood operator gathers at most one grid cell at full
res and does the rest on the grid (no tile overlap); upsample with explicit fp32 weights, never hardware bilinear.

Colour (ADR 0008): profiles in `cpu/ProfileLooks.kt` (one 16-knot `ToneCurve` per look, `Filmic` for designed looks),
Camera JPEG match constants in `cpu/CameraMatchZvE10.kt`, vibrance/saturation and `HueTreatment` in
`cpu/ColourOperators.kt` (OkLab, `cpu/OkLab.kt`), GLSL in `gl/ColourShaders.kt` (own `Look` block, binding 3). White
balance API for the editor: `WhiteBalanceModel.preset/presetCct/neutralFromSample/asShot`, `AutoWhiteBalance.analyse`
(keep the small `WbAnalysis`, not the proxy) then `.whiteBalance`. Profiles apply to scene-referred input only.

Camera JPEG match calibration (owner's RAW+JPEG pairs, opt-in, copies deleted by the script, aggregates only). The shoot
folders are the owner's private ZV-E10 RAW+JPEG folders; ask the orchestrator for the paths:

```sh
scripts/calibration/calibrate.sh <work dir outside the repo> <private shoot folder 1> <private shoot folder 2>
#   prints CALIB aggregates + CALIB-CONST for CameraMatchZvE10.kt
scripts/calibration/rawdump.sh core/image/build/samples/sony-zv-e10.arw build/calibration/zv.rdump   # public sample
REVEAL_RENDER_DUMP=$PWD/build/calibration/zv.rdump REVEAL_RENDER_OUT=$PWD/build/calibration/zv \
  ./gradlew :core:pipeline:testDebugUnitTest --tests '*SampleRenderSheetTest*' --rerun   # profile + WB contact sheets
```

Parity and benchmark (emulator or phone, GLES 3.0+ with `EXT_color_buffer_float`; start the emulator with
`EMULATOR_GPU=host` for meaningful frame times, the default SwiftShader is ~50x slower):

```sh
ANDROID_SERIAL=emulator-<port> ./gradlew :core:pipeline:connectedDebugAndroidTest
adb -s emulator-<port> logcat -d -s REVEAL_PARITY REVEAL_BENCH     # measured errors and frame times
```

Slot 12 (Effects, ADR 0014) and the post-crop vignette and grain: see "Effects and Detail speed" below.

Debug canvas: `adb -s emulator-<port> shell am start -n dev.hfps.reveal.debug/dev.hfps.reveal.debug.PipelineDebugActivity`
(debug build only: `app/src/debug`, hosting `EditorDebugScreen` on a synthetic image).

Geometry (slot 13, ADR 0002 "Geometry"; the lens/Transform warp: ADR 0015 below): `geometry/GeometryMapping` (output
<-> image, used by `cpu/GeometryOp`, the output pass and the export tiles via `sourceRegion`; build it with
`GeometryMapping.of(recipe, lens, w, h)` so the warp is included), `geometry/CropEditing` (crop mode gesture maths in the editing
plane). Crop/turn/flip copy pixels; straightening is Catmull-Rom. Renderer: `setGeometryEditing(true)` shows the editing
plane; `renderOutput` returns the operator's output size (`CpuDevelop.mapping(input, recipe)`).

## Colour tools (`:core:pipeline`, `:feature:editor`, M2/B, ADR 0013)

Slot 8 ends with the tone curve, slot 9 with the colour mixer *or* black & white, then colour grading (all skipped at
their defaults). Recipe: `light.curve` (`Curves`: parametric + `rgb/red/green/blue` point lists, empty = straight),
`colour.mixer` (`ColourMixer`: hue/saturation/luminance `BandValues`), `colour.grading` (`ColourGrading`: 4
`GradeWheel` + blending/balance), `colour.greyMixer`, `profile.treatment` (`Treatment.BLACK_WHITE` skips
vibrance/saturation and the mixer). CPU: `cpu/Curves.kt` (`MonotoneSpline` Fritsch-Butland, `ParametricCurveShape`,
`CurveLut`: encoded `table` + operator `linear` sampled in sqrt(v), baked in `DevelopParams`; `CurveOp`), `ColourStageOp` (one OkLab round trip for slot 9 when a tool is on), `cpu/ColourTools.kt` (`HueBands` smoothstep band
interpolation, `MixerParams`/`ColourMixerOp`, `BlackWhiteOp`, `GradingParams`/`ColourGradingOp`). GLSL:
`gl/ColourToolShaders.kt` (`Tools` block, binding 5, plus the curve table as a 512x1 RGBA32F texture on unit 3: never
put big arrays in a uniform block, SwiftShader counts them against the default-uniform limit; both renderers own a
`ColourToolShaders.Gl`). Controls:
`ControlId.CURVE_*`, `HUE_/SAT_/LUM_/GREY_<band>`, `GRADE_<region>_<HUE|SAT|LUM>`, `GRADE_BLENDING/BALANCE`, read and
written by `ColourToolControls` (history fields share the control's name; labels are templates in
`HistoryLabels.colourTool`). Targeted adjustment and Auto mix: `auto/ColourSampling.kt` (`SourceColours` = Auto's
sample rendered without mixer/grading/B&W, `TargetedAdjust`, `AutoBlackWhite`). Editor: `panels/ToneCurvePanel.kt` +
`CurveEditor.kt` (pure maths in `edit/CurveEditing.kt`), `panels/MixerPanels.kt`, `panels/GradingPanel.kt`
(`edit/WheelMath.kt`); a `SubPanel(expand = true, reset = …)` opens the tall panel and resets only its tool; content
colours from `OkLabColors` (`:core:designsystem`). Tests: `CurvesTest`, `ColourToolsTest`, `ColourSamplingTest`, the
`GpuParityTest` tool recipes (tolerance x the curve's steepest slope), `ColourToolsInteractionTest` (instrumented),
goldens `phone_tone_curve*`, `phone_colour_mixer*`, `phone_grading*`, `phone_bw*`. Benchmarks and sheets:

```sh
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.editor.ColourToolsInteractionTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
ANDROID_SERIAL=emulator-<port> ./gradlew :core:pipeline:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.hfps.reveal.pipeline.PipelineBenchmark   # REVEAL_BENCH curve/hsl drags
REVEAL_COLOUR_SHEET_DIR=$PWD/build/calibration REVEAL_RENDER_OUT=$PWD/docs/screenshots/colour-tools/colour-tools \
  ./gradlew :core:pipeline:testDebugUnitTest --tests '*ColourToolsRenderSheetTest*' --rerun   # CC0 before/after sheets
```

## Library (`:core:data`, `:feature:library`, M1, ADR 0006)

Room `RevealDatabase` v5 (sources, photos, photo_files, versions, recipes, history, history_cursor, cache_entries);
schema change = new version + migration (or `AutoMigration`) + exported `core/data/schemas/*.json`, tested by
`RevealDatabaseMigrationTest`. `LibraryRepository` (sources, SAF grants, volumes, indexing, ratings/flags with XMP
write-back), `LibraryIndexer` (walk → diff → sidecars → headers), `XmpReader`/`XmpWriter` (merge, never overwrite a
malformed sidecar; `reveal:` namespace reserved for M5), `ThumbnailRepository` (embedded preview / JPEG, edited thumb
via `GpuEditedThumbnails`), `PhotoImages` (viewer display + region decoder), `EditRepository` (recipes, undo/redo
history for the editor). The editor route is `EditorRoute(versionId = …)` from the library or `EditorRoute(uri = …)`
from other apps. The library is the start destination and reopens the last folder (`meta`).

Instrumented library tests use the debug-only `SyntheticDocumentsProvider` (`app/src/debug`, authority
`dev.hfps.reveal.debug.synthetic`, files under `files/saf-test`, own process) and `DebugAccess` (Hilt entry point):

```sh
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.library.LibraryFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.library.LibraryMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner   # 10,000 files
adb -s emulator-<port> logcat -d -s REVEAL_LIBRARY RevealLibrary
```

The full suite excludes both measurement classes:
`-e notClass dev.hfps.reveal.editor.EditorMeasurementsTest,dev.hfps.reveal.library.LibraryMeasurementsTest`.
Frame measurements must not use a Compose test rule (it holds frames while it synchronizes): launch with
`ActivityScenario` and inject touches with `input swipe`.

## Hand-off from other apps (`:core:data/handoff`, ADR 0011)

`HandoffIntents.parse` (action `dev.hfps.reveal.action.DEVELOP`, `VIEW`, `EDIT`, `SEND`, `SEND_MULTIPLE`; URIs from data +
ClipData + `EXTRA_STREAM`) -> `HandoffRepository.receive` (pairs by stem and parent, files the photos under the single
`sources.kind = HANDOFF` source, `HandoffTree` keys documents by URI) -> `HandoffOutcome` (`MainActivity.show`). DB v3.
Identity (ADR 0006 "Identity", DB v4): a file is its content fingerprint (`FileFingerprint` in `:core:image/source`,
`photo_files.fingerprint`); hand-offs find their photo by fingerprint (any source, URI+size+mtime only for rows without
one), never by name; folders fill fingerprints in the background after a walk (`LibraryIndexer.fillFingerprints`).
Library scope `FromApps` = "Opened from other apps" (newest hand-off first, header per batch). Sidecars are only written
when the sender included a writable `.xmp` item. Culling values (ADR 0011 "Culling"): `HandoffIntents.EXTRA_CULLING`
(`Bundle` keyed by item URI string -> `Bundle` `rating`/`flag`/`label`, `CULLING_VERSION` 1, `cullingExtra()` builds it)
-> `HandoffRequest.culling` -> `HandoffRepository.applyRatings` (values win unless the sidecar is newer than the photo's
previous hand-off; never written back). Sidecar names (normal before hidden `.<stem>.xmp`) live in `xmp/SidecarNames`.
DB v5: `photos.uuid` / `versions.uuid` (stable sync ids, manual `MIGRATION_4_5`; open the database with
`addMigrations(*RevealDatabase.MIGRATIONS)`). Instrumented: `dev.hfps.reveal.handoff.HandoffFlowTest` (same command
shape as the library tests).

## Editor open flow (`:feature:editor`, M0)

`EditorRoute(uri)` → `EditorViewModel`: `PhotoOpener` (preview via `FirstImageLoader`, proxy via `ProxyBuilder`,
`PipelineInputMapper` in `:core:pipeline/input`) → `GpuDevelopRenderer.upload` (the CPU copy is dropped) →
`EditorScreen` (canvas, preview overlay that fades out on the first GPU frame, debug develop panel, `OpenError` states).
`MainActivity` routes `ACTION_VIEW`/`EDIT`/`SEND` (`OpenIntents`) to the editor; back returns to the calling app.
`EditorMetrics` holds the timings/memory of the last open (`adb logcat -s RevealEditor`) and, for tests only, the
renderer. Highlights clip at the input's `saturation` (ADR 0002 slot 1, `PIPELINE_VERSION` 2).

Editor tests and measurements (install both APKs yourself: `connectedDebugAndroidTest` uninstalls the app and its
`files/samples`):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-<port> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-<port> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-<port> push core/image/build/samples/sony-zv-e10.arw /data/local/tmp/
adb -s emulator-<port> shell "chmod a+r /data/local/tmp/sony-zv-e10.arw && run-as dev.hfps.reveal.debug sh -c 'mkdir -p files/samples && cp /data/local/tmp/sony-zv-e10.arw files/samples/' && rm /data/local/tmp/sony-zv-e10.arw"
adb -s emulator-<port> shell am instrument -w -e notClass dev.hfps.reveal.editor.EditorMeasurementsTest,dev.hfps.reveal.library.LibraryMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.editor.EditorMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> logcat -d -s REVEAL_MEASURE REVEAL_EDITOR_TEST RevealEditor
```

The tests put photos into MediaStore Downloads (real content URIs) and remove them. Owner's photos (opt-in, copies,
aggregates only): copy them into `files/private` the same way, run the measurement with `-e private true` (logs
`file#N`), then `run-as dev.hfps.reveal.debug rm -rf files/private` and delete the copies on the Mac.

Release check without the keystore: `./gradlew :app:assembleRelease`, then sign a local copy with the debug key only to
install it on an emulator (`zipalign -p 4` + `apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android`);
open a RAW through it to prove R8 kept the JNI (`core/image/consumer-rules.pro`) and the serializable routes.

## Editor layout (`:feature:editor`, M1/E)

`EditorContent` picks `EditorLayoutKind` (portrait / landscape phone / tablet >= 840dp). The GL surface fills the
screen behind the chrome (`PhotoSurface`); the chrome leaves an empty `PhotoSlot` whose bounds become the renderer's
frame (`PhotoController`), so panel snaps and the slider-drag shrink move the photo without resizing the surface.
Tools live in `tools/EditorTools.kt` (`EditorTool` + `ToolBehaviour.Panel / Mode / Action / Unavailable`): add or
enable a tool there, never in the layout. A `Mode` (Crop: `crop/`) gets a `ModeHost` (state, actions, photo controller,
Done/Cancel) and edits the working recipe only; Done commits one step, Cancel/back restores the committed geometry. Panels get a `PanelHost` (state, actions, `SliderFocus`, `PanelNavigator`
for sub-panels); bind a slider with `ControlSlider(host, ControlId.X, R.string.label)` (`RecipeControls` maps ids to
recipe fields, `RecipeRanges` gives range/step/format). The ViewModel owns the working/committed recipe and pushes
it, the compare mode, clipping and histogram flags to the renderer. History (ADR 0003 "History engine", ADR 0006):
`EditState.history` is a pure `EditHistory`; `commit()` / `editAndCommit(action, groups)` add one step and queue a
`HistoryOp` on the `HistoryWriter` (Room, coalesced, outlives the screen); undo/redo/`goTo` move the cursor. A new
committing control calls `onCommit` once on release, never per frame. The history list is `EditorTools.history`
(opened from More ⋯, not in the strip); copy/paste/Previous/resets are in More ⋯ and in the library's selection menu.
Goldens: `app/src/test/screenshots/editor/` (`EditorScreenshotTest`, `SliderScreenshotTest`; the photo is the
pipeline's synthetic scene developed on the CPU). Instrumented: `EditorInteractionTest` (synthetic JPEG, any
emulator), `EditorHistoryTest` (undo/redo/jump/branch, reopen restores from Room), `:core:pipeline`
`ReadbackApisTest` (clipping flag, source sample). Edits persist per photo and a hand-off is the same photo exactly
when its content is the same: `TestPhotos.insert` appends a unique trailer to every image (so each run
is a new photo; pass `unique = false` to reuse content on purpose) and test names stay unique (`TestPhotos.uniqueName`).
The full suite must pass twice in a row without `pm clear` (the phone keeps app data).

## Export (`:feature:export`, `gl/TiledRenderer`, ADR 0009)

`ExportSheetHost(request)` (editor top bar, library selection) -> `ExportWorker.enqueue` (every export is a WorkManager
job; job + item states in `files/export-jobs/`, resumable per photo) -> `ExportEngine.export`: full decode
(`NativeRawTileSource` / `BitmapTileSource`) -> `TiledRenderer` (own GL context; grid pre-passes over cell-aligned tiles,
then per output tile: develop the input region, geometry operator (slot 13), Lanczos; the preview's shaders with
`uOrigin`; `ExportPlan` chains image -> geometry output -> output) -> `BitmapSink` -> `ImageEncoders`
(JPEG = `Bitmap.compress` + `JpegSegments` for EXIF/XMP, HEIF = HeifWriter 8-bit sRGB) -> `Destinations` (MediaStore
`Pictures/Reveal`, SAF folder, share cache swept at start). Metadata: `ExifSnapshot` (`:core:image`) -> `ExifWriter`.
Sizes and tiles: `ExportSize`, `ExportPlan` (`:core:pipeline/export`). Author/copyright: Settings (`ExportPreferences`).

```sh
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.pipeline.TiledRendererTest dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.export.ExportFlowTest,dev.hfps.reveal.export.ExportSheetTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
#   -e keep true leaves the files in Pictures/Reveal and files/saf-test/export (exiftool on the Mac);
#   -e large <file in files/samples> exports a ~60 MP RAW (memory); logs: adb logcat -d -s REVEAL_EXPORT RevealExport
# kill/restart: -e class '...ExportFlowTest#startBatchForKill' -e kill start, then am start the app, then
#               -e class '...ExportFlowTest#killedBatchFinished' -e kill check
```

## Detail and full-resolution zoom (`:core:pipeline`, `:feature:editor`, M2/Z, ADR 0012)

Detail is slot 11: `cpu/DetailOp.kt` (`DetailParams.from(detail, pixelScale)`: lengths are full-resolution pixels,
`pixelScale` = full pixels per rendered pixel, the proxy's downscale or 1), GLSL in `gl/DetailShaders.kt`, passes in
`gl/DetailPasses.kt` (the caller owns the textures). The develop stage writes OkLab when Detail runs (`uLabOut`). Every
value a pass stores in RGBA16F goes through `q16` / `Half.round` (explicit float16 rounding, same on CPU and GPU):
keep that for any new stored intermediate, a driver's own rounding biased the cascade by half a level. A neighbourhood
operator needs an apron in tiles: export plans take `reach` (`ExportPlan.of(..., reach)`, `DevelopParams.reach`: Detail and Effects' texture).
The panel is `panels/DetailPanel.kt`; the Masking drag shows the mask (`GpuDevelopRenderer.setDetailMask`).

Zoom: `EditorViewModel.loadFullResolution` decodes the original after the proxy (`PhotoOpener.openFullResolution`:
`NativeRawTileSource`, `BitmapTileSource` up to 40 MP, `RegionTileSource` above) and hands it to
`GpuDevelopRenderer.setFullResolution(source, k)`. `gl/ZoomTiles.kt` keeps the atlas (8 x 6 slots of 512 px + 8 px
border, levels 0-1), the index texture and the scheduling (settled edit + 150 ms, centre first, grids prefetched);
the tiles are developed by a `TiledRenderer` on a shared EGL context (`prepareZoom`, `developTile`); the output pass
reads them where the index says so (`OUTPUT_FRAG`, `sampleTile`). Pure maths in `zoom/ZoomGrid.kt` (`ZoomView`).
Tests/APIs: `renderer.awaitZoom(...)`, `renderView(view, w, h, settings)`, `renderShown(settings)`, `zoomStats`.

```sh
./gradlew :core:pipeline:testDebugUnitTest --tests '*DetailOpTest*' --tests '*ZoomGridTest*'
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.pipeline.ZoomTilesTest,dev.hfps.reveal.pipeline.TiledRendererTest dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.pipeline.PipelineBenchmark dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner   # Detail drag cost
# editor: tile at 100 % vs CPU (synthetic JPEG), and the zoom measurement on files/samples/sony-zv-e10.arw
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.editor.EditorZoomTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> logcat -d -s REVEAL_BENCH REVEAL_ZOOM REVEAL_EDITOR_TEST RevealZoom
# 100 % crops for docs/screenshots/detail (CC0 high-ISO RAWs from scripts/fetch-samples.sh, full-size dump):
scripts/calibration/rawdump.sh core/image/build/samples/sony-a7c-iso12800.arw build/calibration/a7c.rdump full
REVEAL_DETAIL_DUMP=$PWD/build/calibration/a7c.rdump REVEAL_DETAIL_OUT=$PWD/build/calibration/a7c REVEAL_DETAIL_CROP=4000,1650,480,480 \
  ./gradlew :core:pipeline:testDebugUnitTest --tests '*DetailRenderSheetTest*' --rerun
```

## Effects and Detail speed (`:core:pipeline`, `:feature:editor`, M2/C, ADR 0014 and ADR 0012 "M2/C")

Slot 12 shares Detail's final pass (`DetailShaders.FINAL_FRAG`, CPU `DetailOp.render`): the develop stage writes OkLab
when `DevelopParams.localActive`; texture is the second channel of the blur passes (cross-bilateral), clarity and dehaze
read the Effects grid (`cpu/EffectsOp.kt` `EffectsGrid`, GPU `gl/EffectsPasses.kt`, built from the develop output
before Detail, never from the amounts). Vignette and grain (`PostParams`, `PostCropOp`, GLSL `EffectsShaders.POST`) are
post-crop: in the output passes (`OUTPUT_FRAG` with `post0..3`, the export's `uPost*` at the export's own size), on the
geometry operator's output. Apron: `DevelopParams.reach` / `DevelopParams.reach(recipe)` (Detail + texture) for
`ExportPlan.of(..., reach)` and zoom tiles; the tiled renderer runs an Effects pre-pass (scene + develop of every
pre-pass tile). Detail: separable luminance levels (RG16F), colour on a half-resolution copy (`DetailPasses.HalfRegion`),
per-slider caching in `DetailPasses` (`invalidate()` when the develop output changes), uniform locations cached. Noise
model: `cpu/NoiseModel.kt` (`PipelineInput.noise` = ISO + model from `PipelineInputMapper.noise`). The panel is
`panels/EffectsPanel.kt`. Positive dehaze has a 4x parity tolerance (`GpuParityTest.dehazeGain`).

```sh
./gradlew :core:pipeline:testDebugUnitTest --tests '*EffectsOpTest*' --tests '*DetailOpTest*' --tests '*NoiseModelTest*'
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.pipeline.PipelineBenchmark#detailAndEffectsSliderDrags dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> logcat -d -s REVEAL_BENCH    # one line per Detail / Effects slider
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.editor.EditorEffectsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
# CC0 before/after sheets (docs/screenshots/effects) and the noise model check (rawdump now records the ISO):
scripts/calibration/rawdump.sh core/image/build/samples/nikon-d5600.nef build/calibration/nikon-d5600.rdump
REVEAL_EFFECTS_DUMPS=$PWD/build/calibration/nikon-d5600.rdump REVEAL_EFFECTS_OUT=$PWD/build/calibration/fx \
  ./gradlew :core:pipeline:testDebugUnitTest --tests '*EffectsRenderSheetTest*' --rerun
REVEAL_NOISE_DUMPS=<full-size rdumps, comma separated> ./gradlew :core:pipeline:testDebugUnitTest --tests '*NoiseCalibrationTest*' --rerun -i
```

## Optics, Transform and Upright (`:core:pipeline`, `:feature:editor`, M2/D, ADR 0015)

Lens profiles: `optics/LensProfile.kt` (`SonyLensProfile` from the ARW's knots, `LensfunProfile`), radius normalised to
the half diagonal; `input/LensResolver` (embedded first, then `optics/LensfunDatabase.shipped()`), attached as
`PipelineInput.lens` (`PipelineInputMapper.map(..., metadata)`, `TileSource.withLens`). Recipe: `optics` (switch,
amounts, `removeCa` + the automatic estimate `caRed/caBlue`, defringe) and `geometry.transform` (sliders, `upright`,
`guides`); controls in `recipe/OpticsControls.kt`. Operators: `VignetteOp` (slot 3, `SCENE_FRAG` `uVignette`),
`DefringeOp` (slot 10b, `gl/DefringePasses.kt`, OkLab in, OkLab or RGB out), and the slot 13 **warp**
(`geometry/Warp.kt`: `Perspective` homography + `optics/LensWarp` per-channel radial table, auto-scaled; GLSL in
`gl/WarpShaders.kt`, shared by the output pass, the export geometry pass and the zoom tiles). The lens tables are
`optics/LensTables` (256 entries, explicit interpolation, 256 x 1 RGBA32F textures on unit 3). The crop is in the
**plane** (corrected + transformed image): `CropEditing.transform` refits a constrained crop, `isValid` checks the
corners through the homography. New photos get the profile as one `HistoryAction.LENS_PROFILE` step
(`EditorViewModel.applyLensDefault`, `Recipe.isUnedited`). Analysis: `geometry/LineDetector` (simplified LSD),
`geometry/Upright` (solve + straightenAngle), `optics/CaEstimator`; the editor's `edit/OpticsAnalysis` runs them on
the proxy at open. UI: `panels/OpticsPanel.kt`; the Crop mode's Transform tab `crop/TransformControls.kt`, guides in
`crop/CropOverlay.kt` + `crop/Guides.kt` (stored in the corrected image, normalised). lensfun asset: regenerate with
`python3 scripts/lensfun/convert_lensfun.py` (`--check`), CC BY-SA 3.0 (docs/licences.md).

```sh
./gradlew :core:pipeline:testDebugUnitTest --tests '*LensCorrectionTest*' --tests '*WarpTest*' --tests '*UprightTest*' \
  --tests '*OpticsOpsTest*' --tests '*LensfunDatabaseTest*'   # the last reads the CC0 samples when fetched
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.pipeline.GpuParityTest#opticsMatchCpuReference,dev.hfps.reveal.pipeline.TiledRendererTest#opticsAndTransformExportLikeThePreview dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.pipeline.PipelineBenchmark#transformDragFrameTime dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.editor.TransformModeTest,dev.hfps.reveal.export.ExportFlowTest#transformedPhotoExportMatchesThePreview dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
# CC0 before/after sheets (docs/screenshots/optics): rdumps of the public samples, then
REVEAL_OPTICS_OUT=$PWD/docs/screenshots/optics ./gradlew :core:pipeline:testDebugUnitTest --tests '*OpticsRenderSheetTest*' --rerun
```

## Auto (`:core:pipeline/auto`, ADR 0010)

`AutoAnalyser.sample(proxy)` (512 px, point-sampled, kept by `EditorViewModel` from the open) -> `AutoAnalyser.analyse
(sample, recipe)` (Light/colour sliders neutral, so Auto is idempotent) -> `AutoRules.compute(analysis, AutoParams())`
(pure, every constant in `AutoParams`) -> `AutoRules.applyTo(recipe, result, AutoScope.ALL | LIGHT)`; `AutoRules.run`
does all of it plus Auto WB (`AutoRules.whiteBalance`, also behind the Auto WB chip). The editor commits it as one
`HistoryAction.AUTO` step. Tests: `AutoRulesTest` (JVM fixtures), `dev.hfps.reveal.editor.EditorAutoTest`
(instrumented, CC0 ZV-E10 in `files/samples` + synthetic JPEG). Opt-in, never committed:

```sh
REVEAL_CALIBRATION_DIR=<work dir with rdump+JPG copies> ./gradlew :core:pipeline:testDebugUnitTest --tests '*AutoCalibrationTest*' --rerun -i   # + REVEAL_AUTO_FIT=1
REVEAL_AUTO_SHEET_DIR=$PWD/build/calibration REVEAL_RENDER_OUT=$PWD/docs/screenshots/auto/auto \
  ./gradlew :core:pipeline:testDebugUnitTest --tests '*AutoRenderSheetTest*' --rerun   # CC0 before/after sheets
```

## `:core:image` harness (ADR 0004)

```sh
scripts/fetch-samples.sh                                      # public CC0 RAWs (raw.pixls.us) into core/image/build/samples, checksummed
scripts/emulator.sh create reveal-image && scripts/emulator.sh start reveal-image 5561
./gradlew :core:image:assembleDebugAndroidTest
adb -s emulator-5561 install -r -t core/image/build/outputs/apk/androidTest/debug/image-debug-androidTest.apk
scripts/harness-push.sh emulator-5561 core/image/build/samples/*     # into the test app's files/samples (emulators only)
adb -s emulator-5561 shell am instrument -w dev.hfps.image.test/androidx.test.runner.AndroidJUnitRunner   # all harness tests
#   one class: -e class dev.hfps.image.MeasurementsHarnessTest ; results: adb logcat -d -s RevealHarness:I
```

Opt-in tests on the owner's photos (COPIES only, aggregates only, delete the copies afterwards, never commit or
screenshot): JVM `REVEAL_ARW_DIR=<dir of copies> ./gradlew :core:image:testDebugUnitTest -i` (prints `REALSHOOT ...`);
device: push the copies to `files/private` with `run-as`, run with `-e dir /data/user/0/dev.hfps.image.test/files/private
-e anonymize true` (logs `file#N`), then `run-as dev.hfps.image.test rm -rf files/private`.
