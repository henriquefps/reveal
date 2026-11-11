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
- Workers use emulators; only the orchestrator installs on the owner's phone (`RXCX109VTTM`).
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

Emulator (own AVD, own port 5560-5590, headless, one per worker, delete when done; never the phone):

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
own thread), `synthetic/` (generated test images). `PipelineInput` is the pipeline's input type.

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

Debug canvas: `adb -s emulator-<port> shell am start -n dev.hfps.reveal.debug/dev.hfps.reveal.debug.PipelineDebugActivity`
(debug build only: `app/src/debug`, hosting `EditorDebugScreen` on a synthetic image).

Geometry (slot 13, ADR 0002 "Geometry"): `geometry/GeometryMapping` (output <-> image, used by `cpu/GeometryOp`, the
output pass and the export tiles via `sourceRegion`), `geometry/CropEditing` (crop mode gesture maths in the editing
plane). Crop/turn/flip copy pixels; straightening is Catmull-Rom. Renderer: `setGeometryEditing(true)` shows the editing
plane; `renderOutput` returns the operator's output size (`CpuDevelop.mapping(input, recipe)`).

## Library (`:core:data`, `:feature:library`, M1, ADR 0006)

Room `RevealDatabase` v2 (sources, photos, photo_files, versions, recipes, history, history_cursor, cache_entries);
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
Library scope `FromApps` = "Opened from other apps" (newest hand-off first, header per batch). Sidecars are only written
when the sender included a writable `.xmp` item. Instrumented: `dev.hfps.reveal.handoff.HandoffFlowTest` (same command
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
`ReadbackApisTest` (clipping flag, source sample). Edits persist per photo and a hand-off file with the same name is
the same photo: give instrumented test photos unique names.

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
