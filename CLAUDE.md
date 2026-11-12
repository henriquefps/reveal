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
  Release installs on the phone are the orchestrator's. The owner's tablet (Galaxy Tab S9 Ultra, `RX2X9009BXP`, S Pen,
  user 0 only) may be connected too: same rules, always `adb -s <serial>`.
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
Ultra: some layouts only break at that size); `EMULATOR_LCD=2560x1600@274 EMULATOR_DEVICE=pixel_tablet` a Galaxy Tab S9:

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

Room `RevealDatabase` v7 (sources, photos, photo_files, versions, recipes, history, history_cursor, cache_entries; sync tables v6; preset tables v7, ADR 0019);
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

The full suite excludes the measurement classes:
`-e notClass dev.hfps.reveal.editor.EditorMeasurementsTest,dev.hfps.reveal.library.LibraryMeasurementsTest,dev.hfps.reveal.editor.FilmstripMeasurementsTest,dev.hfps.reveal.sync.SyncPullMeasurementsTest,dev.hfps.reveal.editor.PresetsMeasurementsTest`.
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
adb -s emulator-<port> shell am instrument -w -e notClass dev.hfps.reveal.editor.EditorMeasurementsTest,dev.hfps.reveal.library.LibraryMeasurementsTest,dev.hfps.reveal.editor.FilmstripMeasurementsTest,dev.hfps.reveal.editor.PresetsMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
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

`EditorContent` picks `EditorLayoutKind` (portrait / landscape phone / tablet >= 840 x 480 dp, see "Tablet" below).
The GL surface fills the screen behind the chrome (`PhotoSurface`); the chrome leaves an empty `PhotoSlot` whose bounds become the renderer's
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

## Tablet, filmstrip, pen and keyboard (`:feature:editor`, `:app`, M2/E, ADR 0016)

Tablet layout: `TabletLayout.kt` (full-width top bar; photo + `layout/Filmstrip.kt` in the left column; panel column
with a drag-to-resize left edge, `EditorLayoutKind.panelRange`; rail). Portrait tablets and almost square foldables use
`DockedTabletLayout` (`EditorLayoutKind.TABLET_DOCKED`, chosen by `prefersDock`: whichever shows a 3:2 photo larger;
panel docked at the bottom with a drag-to-resize top edge, `dockRange`; tool strip as the bottom row; ADR 0016
"Docked panel"). The phone layouts must not change (their goldens stay byte-identical). Panel width / docked height
and filmstrip visibility: `EditorLayoutPreferences` (`:core:data`). Rotation/fold recreate the activity: chrome state
(`rememberEditorChrome`: tool, mode, side panel, sub-panel stack via `SubPanels.byKey`, panel width and height) and
zoom (`PhotoController.Saver`) are saved state; the ViewModel and renderer survive.
`RevealBottomSheet` is a centred dialog from `DialogMinWidth` (840 dp); the viewer's info is a side panel there.

Filmstrip: `EditorRoute(versionId, strip, tool)`; `strip` = the grid's `PhotoQuery` (`PhotoListCodec`), passed by the
viewer (and "Opened from other apps" for a multi-photo hand-off). `FilmstripViewModel` observes the list; moving (tap,
left/right arrows, a horizontal swipe on the photo at fit) navigates to a new `EditorRoute` with
`popUpTo<EditorRoute> { inclusive = true }`: a new ViewModel and renderer per photo (never swap the photo inside one).

Pen: palm rejection in `MainActivity.dispatchTouchEvent` (`input/PalmRejection.kt`, pure `PalmRejectionPolicy`);
hover via `canvas/StylusInput.kt` (`Modifier.penHover`, `HoverTooltipLayer`; stylus and mouse only) in the curve
editor, crop handles, Guided Upright guides and the targeted adjustment (`EditorViewModel.targetedHover`). Smaller grab
radii for the pen (`PointerType.Stylus`). Once a pen is seen (`PhotoController.penSeen`) the targeted adjustment
answers the pen and the finger pans/zooms. Keyboard: `layout/EditorKeys.kt` + `rememberEditorKeys` (root
`onPreviewKeyEvent`). Goldens: `tablet_*` (Tab S9 1495x934 dp, mdpi), `tablet_portrait_*`, `foldable_*` (852x883 dp)
in `screenshots/editor` and `screenshots/library`.

```sh
EMULATOR_LCD=2560x1600@274 EMULATOR_DEVICE=pixel_tablet scripts/emulator.sh create reveal-tablet   # Galaxy Tab S9 screen
scripts/emulator.sh start reveal-tablet <port>
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.editor.TabletEditorTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.editor.FilmstripMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> logcat -d -s REVEAL_FILMSTRIP   # 1,200 handed-over photos, frame times while flinging the strip
```

`TabletEditorTest` skips below 840 dp; the full suite also excludes `FilmstripMeasurementsTest` (a measurement). Large
screens ignore `requestedOrientation`: rotate with `UiAutomation.setRotation` in tests.

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
reads them where the index says so (`OUTPUT_FRAG`, `sampleTile`). 1:1 views (tiles at 100 %, proxy at scale 1) are pixel-aligned by
`zoom/PixelAlignment` in `drawOutput`: never `floor` a screen pixel centre that may sit on a pixel edge (ADR 0012
"Pixel alignment at 1:1"; Adreno 740 duplicated rows there). Pure maths in `zoom/ZoomGrid.kt` (`ZoomView`).
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

Drag speed (M2/P, ADR 0012 "M2/P"): on the Adreno a 2400 x 1600 pass costs ~2 ms whatever it computes, so the preview
runs as few full-resolution passes per drag as possible. The luminance edge stop follows the noise (`DetailParams.
EDGE_SIGMA`), never the sliders: the preview keeps the smoothing chain in a packed RGBA16F texture (`Targets.chain`,
`LUM_V_CHAIN_FRAG`) and a luminance drag is one `SHRINK_FRAG` pass; the colour binning and the blurs (RG16F, `q16`)
are kept too. Views at 50 % or less (`halfViewOnly`) make the final pass write the working texture's level 1 directly
(`FLAG_HALF`, 2 x 2 means; level 0 is rendered on demand by `ensureStageLevel0`), the output reads it with
`OutputSampling.MODE_BOX` (no per-frame mips). The tiled renderer (zoom, export) is unchanged. `DevelopParams` other
than Detail/Effects are memoised across a drag (`UploadedImage.params`, `DevelopParams.withLocal`).

```sh
# REVEAL_BENCH: every tool on, each Detail/Effects slider; pipelined ms per frame (the display rate), GPU per frame, and
# the serialised latency; -e profile true = per-pass GPU timer queries (-e timers false: glFinish after each pass)
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.pipeline.PipelineBenchmark#everyToolOnSliderDrags dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
# on screen (needs files/samples/sony-zv-e10.arw): frame intervals while a finger drags one slider, every tool on
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.editor.EditorMeasurementsTest -e all true -e gpu false -e drag nr_luminance dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.pipeline.PreviewPassesTest dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
```

A phone's GPU clock follows its load: benchmarks warm up for 2 s of continuous rendering (`-e warmup <ms>`), and a
measurement serialised with `glFinish` per frame runs at a low clock (it reads up to 2x the pipelined frame time).

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

## Presets (`:core:pipeline/preset`, `:core:data/preset`, `:feature:editor/presets`, M3/A, ADR 0019)

Model (pure Kotlin, `:core:pipeline/preset`): `Preset` (uuid, name, `PresetPatch`, group, author, version, created,
`SmartPart?`), `PresetPatch` = partial recipe as field path → recipe-JSON value (`fromSections`, `fromJson`,
`toJson`, `apply(before, strength 0..2, ApplyContext)`), `PresetFields` (registry derived from the default recipe's
JSON; `EXCLUDED` paths; `FieldKind` per path), `PresetSection` (chooser sections, `changedIn`, `suggestedFor`),
`PresetStrength` (per-kind scaling; exact at 0 and 1). A new recipe field is presettable automatically: give it a
`FieldKind` in `PresetFields.kinds` and a control range in `controls` if it is not linear. Files: `PresetFiles`
(`.revealpreset`), `PresetPacks` (`.revealpack` zip, limits in `PresetLimits`, errors `PresetError`). Smart presets
(M3/B): `SmartPart` is carried verbatim, `SmartCorrection` (Hilt binding in `DataModule`, `SmartCorrection.NONE` by default) resolves it before the style, in the editor and the batch.

Library (`:core:data/preset`, DB v7): `PresetRepository` (`library()` flow of `PresetLibrary` = built-in groups from
`BuiltInPresetSource` (bound in `DataModule`, `NONE` until M3/B) + user/pack groups; create, rename, move, reorder,
duplicate, delete, groups, favourites/recents in `preset_marks` by uuid, `importPreset`, `importPack` (on trial),
`keepPack`/`removePack`, `export`, `exportGroup`), `PresetFilesIo` (SAF read/write, zip sniffing), `PresetIntents`
(`MainActivity`: VIEW/SEND of `.revealpreset`/`.revealpack`). Batch history: `EditRepository.applyEach`. Sync:
`:core:sync` `engine/PresetSync.kt` (`PresetData` payload; trial packs skipped; triggers `preset_*` in
`SyncTriggers` v2).

Editor: the Presets tool (`ToolBehaviour.Panel(ownsScroll = true)`): `presets/PresetsPanel.kt` (chips, `LazyVerticalGrid`
of `PresetCellModel`, strength slider), `PresetSheets.kt` (create, options, ⋯ menu, pack banner/about),
`PresetSession.kt` (`PresetSession.isCurrent`, `PresetSteps.commit`, `PreviewInput` 360 px box downscale),
`PresetThumbnails.kt` (own offscreen `GpuDevelopRenderer`, newest request first, LRU 40 MB). ViewModel:
`applyPreset` (replaces the session's step while current), `setPresetStrength` (per frame) /
`commitPresetStrength` (replaces the step; 0 undoes it). Library: selection menu → `presets/ApplyPresetSheet.kt` →
`PresetBatchWorker` (WorkManager, job file `files/preset-jobs`, 25 photos per transaction, notification + Cancel;
the `SmartCorrection` binding). Goldens: `phone_presets*`, `landscape_presets`, `tablet_presets`,
`tablet_portrait_presets` (placeholder presets in `EditorScreenshotTest.TestPresets`, tests only).

```sh
./gradlew :core:pipeline:testDebugUnitTest --tests '*preset*'          # patch, strength per kind, files, hostile packs
./gradlew :core:data:testDebugUnitTest --tests '*PresetRepositoryTest*' --tests '*RevealDatabaseMigrationTest*'
./gradlew :core:sync:testDebugUnitTest --tests '*SyncEngineTest*'      # presets between two devices, trial packs
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.editor.PresetsFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
# preview grid frame times (60 presets; files/samples/sony-zv-e10.arw if present); -e baseline true scrolls Light
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.editor.PresetsMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s REVEAL_PRESETS RevealPresets
```

Frame measurements: use `getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)` for `input` commands (a
connected UiAutomation makes Compose diff its semantics tree every frame). With an accessibility service on (the
owner's phone has one), a lazy grid's scroll still pays that diff every frame.

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

## Faces, subject and smart presets (`:core:pipeline`, M3/B, ADR 0010 "Features", ADR 0020)

`auto/PhotoFeatures` = what the rules know about a photo independently of its recipe: Auto's `sample`, the `WbAnalysis`,
`faces` (`faces/FaceDetector`: MediaPipe BlazeFace full-range, Apache-2.0, run by `faces/NetworkGraph` in pure Kotlin,
deterministic, JVM-testable; input `faces/FaceInput` = 576 px sRGB render; whole frame + 192 px tiles), `saliency`
(`auto/SaliencyMap`), `lines` (`geometry/ImageLines`, also behind `OpticsAnalysis`) and `lens`. The editor computes it in the
background at open (`EditorViewModel.prepareAuto`, `EditorMetrics.faces`, log `features: N faces, T ms`); Auto
(`AutoRules.run(features, …)`) waits for it. Faces steer exposure (`AutoParams.faceTarget/faceTolerance/faceWeight/
faceAreaFull/maxFaceShift`) and Auto WB (`AutoWhiteBalance.estimate(…, faces)`: face blocks out, skin hue guard 22-52°).
The model asset is generated: `python3 scripts/faces/convert_blazeface.py` (`--check`); never hand-edit it.

Smart presets: `preset/SmartPresets.kt` (`SmartRules` = the preset file's `smart` object, `SmartCorrector(features)`
implements ADR 0019's `SmartCorrection`, memoised for strength drags; `Preset.smart(rules)` drops the style fields a rule
owns). Built-ins: `preset/BuiltInPresets.kt` (patches as JSON, ids from `idOf(key)`: never rename a key) and
`BuiltInPresetNames` (`preset_<key>` / `preset_group_<group>` strings, both languages), served to the library by
`:core:data` `AppBuiltInPresets`. The batch (`PresetBatchWorker.applySmart`) analyses each photo
(`ThumbnailRepository.analysisInput`) before applying.

```sh
./gradlew :core:pipeline:testDebugUnitTest --tests '*faces*' --tests '*AutoFacesTest*' --tests '*preset*'
# owner's calibration (copies, aggregates only; RAW dumps + JPGs in one folder; REVEAL_AUTO_FIT=faces refits the face constants):
REVEAL_CALIBRATION_DIR=<work dir> ./gradlew --no-configuration-cache :core:pipeline:testDebugUnitTest --tests '*AutoCalibrationTest*' --rerun -i
# preset contact sheets (+ REVEAL_AUTO_COMPARE=1: camera JPEG | M1 Auto | M3 Auto); owner's photos -> private review folder only:
REVEAL_PRESET_DUMPS=<dumps and/or JPEGs> REVEAL_PRESET_OUT=<dir> ./gradlew --no-configuration-cache :core:pipeline:testDebugUnitTest --tests '*PresetRenderSheetTest*' --rerun -i
```

Env-driven opt-in tests need `--no-configuration-cache` (the cached configuration keeps the old environment).

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

## Sync server (`sync-server/`, MS/1, ADR 0017)

Go module `github.com/henriquefps/reveal/sync-server`, binary `reveal-sync`; it is not part of the Gradle build.
Protocol = ADR 0017 (change it there first) + `sync-server/api/openapi.yaml` (`TestOpenAPIListsEveryRoute` keeps the
routes and the YAML in step). Layout: `internal/server` (`api.go` = `/v1`, `admin.go` = `/admin` + setup page in
`internal/server/web`, plain HTML/CSS/JS with strings in `assets/i18n.js`, English + pt-BR together), `internal/store`
(SQLite via `modernc.org/sqlite`; schema change = a new numbered `migrations/NNNN_*.sql`, never edit a shipped one),
`internal/blobstore` (`<backup>/reveal-sync/blobs/aa/bb/<sha256>`, `os.Root`), `internal/nas` (indexer),
`internal/mounts` (every path from a request or the database goes through `Roots.Resolve`), `internal/fingerprint`.
The `v1:` fingerprint has golden vectors shared with the app: `sync-server/testdata/fingerprint-vectors.txt`, checked
by the Go test and `:core:image` `FileFingerprintVectorsTest` (regenerate only if the generator changes:
`REVEAL_WRITE_VECTORS=1 go test ./internal/fingerprint/`). Logs: route patterns and device ids only, never keys,
tokens, file names or photo paths (`TestLogsHoldNoSecretsOrNames`).

```sh
cd sync-server
go test -race ./...                      # unit + end-to-end (builds and runs the binary)
go vet ./... && golangci-lint run ./...  # CI: .github/workflows/sync-server.yml
go run . --data-dir /tmp/rs --mounts /tmp/rs-mnt --log-format text   # first run logs the setupCode; /admin/ from the LAN
```

Local Docker test on cachyos-fps (no buildx there: legacy builder args), on a free loopback port, removed afterwards;
never deploy it on the owner's machines or open a tunnel from a worker:

```sh
docker build -t reveal-sync:test --build-arg BUILDPLATFORM=linux/amd64 --build-arg TARGETOS=linux --build-arg TARGETARCH=amd64 .
# compose with an override: image reveal-sync:test, ports ["127.0.0.1:<free port>:3071"]; then
docker compose -p reveal-test down -v && docker rmi reveal-sync:test
```

Release: tag `sync-server-v<x.y.z>` (`.github/workflows/sync-server-release.yml`: linux amd64/arm64 archives +
`ghcr.io/henriquefps/reveal-sync`). App tags stay `v*`.

## Sync (app) (`:core:sync`, MS/2, ADR 0018)

`:core:sync`: `api/SyncApi` (OkHttp client of ADR 0017; no host compiled in, cleartext allowed by
`res/xml/network_security_config.xml` for LAN/Tailscale servers), `account/` (`PairingLink`, `SyncAccountStore`, the
key in `KeystoreSecretStore`), `engine/SyncStore` (push snapshot, pull apply, NAS feed, conflict copies, aliases,
inbox; `Payloads.kt` = the record JSON), `engine/SyncEngine` (one run: push → pull → inbox → NAS → lookups),
`blobs/` (`SmartPreviewMaker`, `BlobUploader`: chunked, resumable from `HEAD`), `files/` (`ServerFilesProvider`
`<applicationId>.sync` + `RangeCache`: Server-source files by range), `work/SyncWork.kt` (`SyncWorker`, `BlobWorker`,
`SyncScheduler`, `SyncController` started by `RevealApplication`), `SyncRepository` (pair/unpair/status). Database v6:
`sync_records` (dirty marks by `SyncTriggers`, quiet while `meta` `sync.applying` is set; create databases with
`RevealDatabase.configure`), `photo_cloud`, `sync_uploads`, `sync_aliases`, `sync_inbox`, `versions.copy_of`,
`sources.backup`. Server photos: `sources.kind = SERVER`, document id `<fingerprint>/<name>`, `data/sync/ServerTree`.
Smart previews: `:core:pipeline` `preview/SmartPreviewCodec` (`PhotoOpener` opens them). Live editor reload:
`data/sync/RemoteEdits`. UI: `feature/settings/sync/` (Sync screen, `PairingScanner` CameraX + ZXing), library
`SpecialSource.SERVER`, `CloudState` badges, conflict cells (`LibraryTags.copy`). A new synced field: add it to the
payload, `photoData`/`versionChange` and the apply path, and (if it lives in a new column) to the triggers.

```sh
./gradlew :core:sync:testDebugUnitTest            # FakeSyncServer: two devices, conflicts, NAS, uploads, 1,000 photos
# against the real server (loopback only; the emulator through adb reverse; stop it afterwards):
scripts/sync/local-server.sh start <work dir> 3471 [NAS files: CC0 samples or synthetic]
adb -s emulator-<port> reverse tcp:3471 tcp:3471
L1=$(scripts/sync/local-server.sh pair <work dir>); L2=$(scripts/sync/local-server.sh pair <work dir>)
./gradlew :core:sync:assembleDebugAndroidTest && adb -s emulator-<port> install -r -t core/sync/build/outputs/apk/androidTest/debug/sync-debug-androidTest.apk
adb -s emulator-<port> shell am instrument -w -e syncLinks "'$L1,$L2'" [-e nasSeed <seed> -e nasSize <bytes>] dev.hfps.reveal.sync.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.sync.SyncFlowTest -e syncLink "'$(scripts/sync/local-server.sh pair <work dir>)'" -e nasName DSC00002.ARW dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> logcat -d -s REVEAL_SYNC RevealSync RevealSyncFiles
scripts/sync/local-server.sh stop <work dir>
```

Measurement (skipped without links; exclude it from the full suite like the other measurement classes):
`-e class dev.hfps.reveal.sync.SyncPullMeasurementsTest -e syncLinks "'$L1,$L2'"` logs `REVEAL_SYNC` library open and
scroll frames before and during a 10,000-record first pull. Pulls apply 50 records per transaction (`SyncStore.APPLY_CHUNK`);
keep it small. Exports of Server photos download the original first; preview-only ones wait (`OriginalPolicy`).
Emulator: the x86_64 host GL stack segfaults on the editor's GPU tests (SwiftShader and `-gpu host`); run GPU-heavy
suites on the phone.

The `nasSeed`/`nasSize` file is the fingerprint-vector generator's bytes (`((i * 2654435761 + seed * 97) >> 13) & 0xff`).
Both instrumented classes skip without their arguments, so the full suite stays green without a server. For the
phone (on the Mac), keep the server on cachyos-fps loopback and forward it: `ssh -R 3471:127.0.0.1:3471` to the Mac,
then `adb -s RXCX109VTTM reverse tcp:3471 tcp:3471` there.
