# Reveal: notes for agents

Non-destructive RAW photo editor for Android (Kotlin + Jetpack Compose, GPU pipeline, LibRaw).
Start with `docs/plan/README.md` (the brief), then `docs/plan/` and `docs/adr/`.

## Rules (until the code exists, the plan is the source of truth)

- UI follows `docs/plan/ux.md`: the hfps identity (`hfps-kit` skill) translated to Compose; the editor is
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
python3 scripts/theme/convert_theme.py ~/.agents/skills/hfps-kit/brand/theme.css   # RevealTokens.kt + WCAG pairs (fails on regression)
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

Room `RevealDatabase` v11 (sources, photos, photo_files, versions, recipes, history, history_cursor, cache_entries; sync tables v6; preset tables v7, ADR 0019; `photo_files.unreadable_at` v8, ADR 0011; `photo_cloud.preview_format` v9; `photo_files.on_server` + `sync_deletes` v10, ADR 0018 FIX/V; `versions.edit_device/edited_at` + data fixes v11, FIX/W; `snapshots` + `export_presets` v12, ADR 0025);
schema change = new version + migration (or `AutoMigration`) + exported `core/data/schemas/*.json`, tested by
`RevealDatabaseMigrationTest`. `LibraryRepository` (sources, SAF grants, volumes, indexing, ratings/flags with XMP
write-back), `LibraryIndexer` (walk → diff → sidecars → headers → sidecar edits), `XmpReader`/`XmpWriter` (merge, never
overwrite a malformed sidecar; `reveal:` = the edit, see "XMP sidecars"), `ThumbnailRepository` (embedded preview / JPEG, edited thumb
via `GpuEditedThumbnails`), `PhotoImages` (viewer display + region decoder), `EditRepository` (recipes, undo/redo
history for the editor). The editor route is `EditorRoute(versionId = …)` from the library or `EditorRoute(uri = …)`
from other apps.

One home with combinable filters (FIX/L, ADR 0006 "Filters"): the start destination is one grid of every photo
(`LibraryScope.All` = this device's photos + the Server photos that open; `Recent` is its own view). Everything else is
`PhotoFilters` (sealed groups: `SourceFilter.Folder/FromApps/ServerOnly`, `CullingFilter`, `EditFilter`, `TypeFilter`,
`SyncFilter` while paired): `PhotoQuerySql.build` ORs within a group and ANDs across, bound args, indexed plans
(EXPLAIN in `PhotoQuerySqlTest`). A new filter = a choice in `PhotoFilters`, its clause in `PhotoQuerySql`, its token in
`PhotoListCodec` (format 2; format 1 and the old `library.lastSelection` still decode), its option in
`feature/library/LibraryFilters.kt` (`filterGroups`), its count in `PhotoDao.observeFilterCounts`, strings in both
languages. The last filters live in `meta` `library.filters` (`LibraryRepository.lastFilters`); a multi-photo hand-off
opens on `PhotoFilters.FROM_APPS`. UI: `LibraryContent` (top bar: `SyncBadge` status -> Sync screen, Recent, Folders,
Settings, ⋯), `FilterBar` + `FilterSheet` (`LibraryFilters.kt`), `ManageFoldersSheet` (`ManageFolders.kt`: show a
folder = `LibraryViewModel.showFolder`, backup mode, remove). The viewer and the filmstrip get the grid's query
(`PhotoListCodec.encode(state.gridQuery)`). Pending uploads: `CloudState.UPLOADING`. Instrumented steps that work in
any language: `library/LibraryFilterSteps` (`showOnly`, `openFolder`, `activeTokens`).

Instrumented library tests use the debug-only `SyntheticDocumentsProvider` (`app/src/debug`, authority
`dev.hfps.reveal.debug.synthetic`, files under `files/saf-test`, own process) and `DebugAccess` (Hilt entry point):

```sh
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.library.LibraryFlowTest,dev.hfps.reveal.library.LibraryFilterFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.library.LibraryMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner   # 10,000 files, filter query times
./gradlew :core:data:testDebugUnitTest --tests '*PhotoQuerySqlTest*' --tests '*PhotoListCodecTest*'   # every group, combinations, EXPLAIN
adb -s emulator-<port> logcat -d -s REVEAL_LIBRARY RevealLibrary
```

The full suite excludes the measurement classes:
`-e notClass dev.hfps.reveal.editor.EditorMeasurementsTest,dev.hfps.reveal.library.LibraryMeasurementsTest,dev.hfps.reveal.editor.FilmstripMeasurementsTest,dev.hfps.reveal.sync.SyncPullMeasurementsTest,dev.hfps.reveal.editor.PresetsMeasurementsTest,dev.hfps.reveal.library.XmpMeasurementsTest`.
Frame measurements must not use a Compose test rule (it holds frames while it synchronizes): launch with
`ActivityScenario` and inject touches with `input swipe`.

## Versions, snapshots and export presets (`:core:data`, `:feature:library`, `:feature:editor`, `:feature:export`, M5/V, ADR 0025)

A virtual copy is a `versions` row (position > 0, `copy_of` null, `name` "Copy N"); conflict copies keep `copy_of`
(ADR 0018) and `VersionInfo.isCopy` tells them apart. `edit/VersionRepository` (`createCopy(versionId, CopySource
.CURRENT|ORIGINAL, nameFor)`: a copy of an edit starts with one `HistoryAction.VIRTUAL_COPY` step; `createCopies`,
`rename`, `delete` (never the last version; position 0 goes to the next; a server-known version becomes a tombstone,
there is no delete trigger on purpose), snapshots: `createSnapshot`, `renameSnapshot`, `deleteSnapshot`,
`snapshotRecipe`, `applySnapshot` = one `HistoryAction.SNAPSHOT` step named after it). Grid cells are versions
everywhere: `PhotoRow.versionName/edited` (edited = the cell's own non-default recipe, `PhotoQuerySql.EDITED_SQL`;
counts over `CELLS_FROM`), the library's `selected` holds **version ids** (`selectedVersions` for edits/exports,
`selectedPhotoIds` for rating/flag/remove, `selectedCopies`), the viewer pages `ViewerPage(photoId, versionId)`
(`ViewerRoute.versionId`, `PhotoImages.display/developed(..., versionId)`) and has a Versions sheet
(`viewer/VersionsSheet.kt`, `VersionsTags`); the editor's More ⋯ has "Save snapshot…" and "Create virtual copy"
(`EditorUiState.versions`, `EditorTags.MENU_*`), the History panel starts with `panels/SnapshotsSection.kt`. Culling
stays per photo (XMP sidecars). Names in dialogs: `RevealTextDialog` (`:core:designsystem`).
Export presets: `:feature:export/model/ExportPresets.kt` (`BuiltInExportPreset` instagram/web/full, `ExportPreset.User`,
`ExportPresets.resolve` = this device's fallbacks), stored by `:core:data` `export/ExportPresetRepository`
(`export_presets`, options = `ExportOptions` JSON); the sheet's Presets row (`ExportTags.preset(key)`, `PRESET_*`).
Sync: snapshots ride in `version.data.snapshots`; export presets are `setting` records `export-preset.<uuid>`
(`SyncTriggers.EXPORT_PRESET_PREFIX`, `engine/ExportPresetSync`); triggers v4.

```sh
./gradlew :core:data:testDebugUnitTest --tests '*VersionRepositoryTest*' --tests '*ExportPresetRepositoryTest*' --tests '*SyncTriggersTest*' --tests '*RevealDatabaseMigrationTest*'
./gradlew :core:sync:testDebugUnitTest --tests '*VersionsSyncTest*'     # copies, snapshots, export presets on two devices
./gradlew :feature:export:testDebugUnitTest --tests '*ExportPresetsTest*' && ./gradlew :feature:library:testDebugUnitTest --tests '*LibraryStateTest*'
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.library.VersionsFlowTest,dev.hfps.reveal.export.ExportPresetsFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.editor.SnapshotsFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner   # editor GL: the phone
## XMP sidecars (`:core:data/xmp`, `library/`, M5/X, ADR 0026)

The sidecar carries the master version's edit next to the culling fields (ADR 0006): `XmpDevelop` (the `reveal:`
block: `Schema`, `RecipeVersion`, `Edited`, `Device`, `Ancestors` (digests), `Recipe` = compact ADR 0003 JSON or
`RecipeDeflate`; `SidecarRecipe.encode/digest`), `CameraRaw` (the `crs:` table: one `Field` per Camera Raw property,
group, Lightroom default, get/set; a new mappable recipe field = one entry there + a case in `CameraRawTest`),
`CameraRawGeometry` (crop corners in the stored image, `CropAngle`, `tiff:Orientation`). Writing: `SidecarSync.write`
(culling always; the edit while `XmpPreferences.writeEdits`), triggered for edits by `EditSidecars` (observes
`MAX(recipes.updated_at)`, 2 s debounce, `meta` `xmp.editsWritten`; started by `RevealApplication`). Reading:
`LibraryIndexer` parses each changed sidecar once, then after the headers `SidecarEdits.reconcile` (import step
`HistoryAction.XMP_IMPORT`, sidecar behind -> `IndexReport.sidecarsBehind` rewritten by `LibraryRepository`, diverged ->
conflict copy; Lightroom's defaults never imported; `XmpPreferences.readEdits`). Never write darktable history, never
create a sidecar for an unedited photo, never overwrite a newer app's `reveal:` block or a malformed sidecar.
Fixtures: `core/data/src/test/resources/xmp/` (hand-written Lightroom Classic/mobile, darktable, Hoard).

```sh
./gradlew :core:data:testDebugUnitTest --tests '*CameraRawTest*' --tests '*XmpDevelopTest*' --tests '*SidecarEditsTest*' --tests '*XmpTest*' --tests '*SidecarSyncTest*'
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.library.XmpSidecarFlowTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-<port> shell am instrument -w -e class dev.hfps.reveal.library.XmpMeasurementsTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner   # 1,000 photos
adb -s emulator-<port> logcat -d -s REVEAL_XMP RevealSidecars
```

## Hand-off from other apps (`:core:data/handoff`, ADR 0011)

`HandoffIntents.parse` (action `dev.hfps.reveal.action.DEVELOP`, `VIEW`, `EDIT`, `SEND`, `SEND_MULTIPLE`; URIs from data +
ClipData + `EXTRA_STREAM`) -> `HandoffRepository.receive` (pairs by stem and parent, files the photos under the single
`sources.kind = HANDOFF` source, `HandoffTree` keys documents by URI) -> `HandoffOutcome` (`MainActivity.show`). DB v3.
Identity (ADR 0006 "Identity", DB v4): a file is its content fingerprint (`FileFingerprint` in `:core:image/source`,
`photo_files.fingerprint`); hand-offs find their photo by fingerprint (any source, URI+size+mtime only for rows without
one), never by name; folders fill fingerprints in the background after a walk (`LibraryIndexer.fillFingerprints`).
The filter "Opened from other apps" (`SourceFilter.FromApps`) alone lists newest hand-off first, header per batch. Sidecars are only written
when the sender included a writable `.xmp` item. Culling values (ADR 0011 "Culling"): `HandoffIntents.EXTRA_CULLING`
(`Bundle` keyed by item URI string -> `Bundle` `rating`/`flag`/`label`, `CULLING_VERSION` 1, `cullingExtra()` builds it)
-> `HandoffRequest.culling` -> `HandoffRepository.applyRatings` (values win unless the sidecar is newer than the photo's
previous hand-off; never written back). Sidecar names (normal before hidden `.<stem>.xmp`) live in `xmp/SidecarNames`.
DB v5: `photos.uuid` / `versions.uuid` (stable sync ids, manual `MIGRATION_4_5`; open the database with
`addMigrations(*RevealDatabase.MIGRATIONS)`). Instrumented: `dev.hfps.reveal.handoff.HandoffFlowTest` (same command
shape as the library tests).

One photo across sources (ADR 0011, 0.5.1): a hand-off and a folder file of the same fingerprint are the folder's photo.
Folder first: `HandoffRepository.store` opens it (`foreign`), sets `handoff_at`, applies the culling values
(`applyFolderCulling`) and writes the folder sidecar (`SidecarSync`). Hand-off first: `LibraryRepository.mergeHandoffs`
after every fingerprint pass -> `handoff/HandoffMerge` (culling `resolve`, versions moved, sync uuid adopted or
tombstone + `sync_aliases`, local previews deleted). "Opened from other apps" = `photos.handoff_at IS NOT NULL` (folder
photos included). Unreadable hand-offs: `HandoffPreviewer` makes `LocalPreviews` (`noBackupFilesDir/handoff-previews`,
by fingerprint: `.jpg` display always, `.rvsp` smart preview for non-persistable grants) after `receive`; smart
previews wait while an editor is open (`editorOpened/Closed`, heap), the editor writes its photo's from its own input
(`LibraryRepository.keepSmartPreview`, after full resolution);
`photo_files.unreadable_at` is set by any refused read (`storage/isAccessError`; `ImageInput.fromUri` turns a provider's
`IllegalArgumentException` into `FileNotFoundException`) and by `LibraryRepository.checkHandoffs` (refresh);
thumbnails, `PhotoImages`, `editTarget` (file URI of the `.rvsp`) and `exportTarget` (`OriginalReach.PREVIEW`) fall
back to them; grid `CloudState.LOCAL_PREVIEW`. Gallery access (optional): `handoff/GalleryAccess` (state, asked once),
`GalleryAccessSheet` in the library route, Settings → Gallery access; permissions in `core/data` manifest. The debug
provider refuses ids listed by `SyntheticDocumentsProvider.refuse` (an ended grant), `forget` (a provider's
`IllegalArgumentException`) and `refuseAfterOpen` (a grant good for one read).

Fallbacks (ADR 0011 "Fallbacks", 0.5.2): `handoff/HandoffFallbacks` is the one place that says what an unreadable
hand-off is read from: the server's original (`photo_cloud.original_on_server`, `ServerTree.ORIGINAL` by range), else a
smart preview (local, else `ServerTree.SMART`), else nothing (`EditTarget.unreadable`: the editor's error names the
sender, `HandoffApps.sender`, launcher `<queries>` in the `core/data` manifest). `EditTarget.fallback` = the preview the
editor opens when the server copy fails; `ExportTarget.previewUri` the same for the export worker. Editor state:
`EditorUiState.previewOnly` (note `EditorTags.PREVIEW_ONLY`), `EditorPhase.Failed.handoff`. Viewer info:
`LibraryRepository.originalPlace`. "Unreadable" = `storage/isAccessError` everywhere (opener, thumbnails, viewer, sync,
export). Goldens `phone_preview_only`, `phone_error_handoff*`.

Unavailable photos (ADR 0011 "Unavailable photos", 0.5.2): "unavailable" = unreadable file AND no server original AND
no server/local preview, defined once: `PhotoQuerySql.UNAVAILABLE_SQL` (`PhotoRow.unavailable`,
`PhotoFilter.UNAVAILABLE`), its Kotlin mirror `PhotoAvailability`, and `LibraryRepository.withLocalPreviews` (a local
preview is a file: it clears the flag and drops the row from the filter). Also `unavailableCount(scope)` (the filter
chip), `recheckAccess(photoIds?, force)` (batch, once per 30 s unless forced), `unavailableDetail`, `editedCount`,
`forget` (hand-off photos deleted with edits and local previews; folder/Server photos only hidden), `isUnavailable`
(viewer). UI: `UnavailablePlaceholder` (`:core:designsystem`) in the grid cell, the filmstrip cell and the viewer page;
`feature/library/UnavailableSheet.kt` (`UnavailableInfo`/`UnavailableHelper`, the sheet, `ForgetDialog`); a grid tap on
an unavailable photo opens the sheet, never the viewer. Goldens `phone_unavailable*`, `tablet_unavailable*`.

```sh
./gradlew :core:data:testDebugUnitTest --tests '*UnavailablePhotosTest*' --tests '*HandoffMergeTest*' --tests '*UnreadableHandoffsTest*' --tests '*GalleryAccessTest*' --tests '*AccessErrorsTest*'
./gradlew :core:sync:testDebugUnitTest --tests '*HandoffSyncTest*'       # merged hand-offs on two devices, parked originals
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.handoff.UnavailablePhotoTest,dev.hfps.reveal.handoff.UnreadableHandoffTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
# fallbacks; the backed-up case needs a local reveal-sync (scripts/sync/local-server.sh, adb reverse), skipped without:
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.handoff.HandoffFallbackTest -e syncLink "'$(scripts/sync/local-server.sh pair <work dir>)'" dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.pipeline.SmartPreviewMakerTest dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner   # every format; CC0 RAWs in files/samples
```

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

Import from a link (M3/F, ADR 0019 "Import from a link"): `PresetLinks.parse` (first http(s) token; https only),
`PresetDownloader` (OkHttp; size limit, timeouts, https-only redirects; `testBaseClient` is the instrumented tests'
TLS hook, never set in the app), `PresetIntents.presetLink` (`SEND text/plain`, `VIEW https`), `PresetLinkViewModel` +
`PresetLinkSheet` (states Entering → Confirming → Downloading → Importing; hosted by `EditorScreen` and
`MainActivity`), then `PresetFilesIo.import(file)`. Any new network use goes into `docs/privacy.md`. Batch and
as-shot WB: `ApplyContext.of(input)` / `ApplyContext.of(PipelineInputMapper.asShot(header))`; the worker reads each
RAW's header (`ThumbnailRepository.applyContext`) when `PresetPatch.usesAsShot`.

```sh
./gradlew :core:data:testDebugUnitTest --tests '*PresetLinksTest*' --tests '*PresetDownloaderTest*'
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.editor.PresetLinkFlowTest,dev.hfps.reveal.library.PresetBatchWhiteBalanceTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
#   the batch test needs two CC0 RAWs of different as-shot WB in files/samples (sony-zv-e10.arw, nikon-d5600.nef, ...)
```

Release-build measurements (`bench` build type: R8 like release, id `dev.hfps.reveal.bench`, debug-signed,
profileable, `src/bench/BenchActivity` driven by adb; never the release app). A minified app cannot host instrumented
tests, so the script drives it (results `REVEAL_BENCH`, frame times from `dumpsys gfxinfo`):

```sh
./gradlew :app:assembleBench
scripts/bench/release-bench.sh <serial> app/build/outputs/apk/bench/app-bench.apk core/image/build/samples/sony-zv-e10.arw \
  core/image/build/samples/nikon-d5600.nef app/src/androidTest/assets/faces/studio-trio.jpg   # ADB=<adb path>, KEEP=1, RUNS=5
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
owns; the options sheet's "Smart corrections…" = `PresetRepository.setSmart` with `SmartRules.withKinds`). Built-ins: `preset/BuiltInPresets.kt` (patches as JSON, ids from `idOf(key)`: never rename a key) and
`BuiltInPresetNames` (`preset_<key>` / `preset_group_<group>` strings, both languages), served to the library by
`:core:data` `AppBuiltInPresets`. Smart correction is per photo: the editor's `SmartCorrector` from the open photo's
features (`EditorViewModel.photoSmart`), the batch's per photo (`PresetBatchWorker.applySmart`,
`ThumbnailRepository.analysisInput`); the injected `SmartCorrection` (`DataModule`) is only the `NONE` fallback.
Instrumented: `dev.hfps.reveal.editor.SmartPresetsFlowTest` (public-domain portrait in `app/src/androidTest/assets/faces`).

```sh
./gradlew :core:pipeline:testDebugUnitTest --tests '*faces*' --tests '*AutoFacesTest*' --tests '*preset*'
# owner's calibration (copies, aggregates only; RAW dumps + JPGs in one folder; REVEAL_AUTO_FIT=faces refits the face constants):
REVEAL_CALIBRATION_DIR=<work dir> ./gradlew --no-configuration-cache :core:pipeline:testDebugUnitTest --tests '*AutoCalibrationTest*' --rerun -i
# preset contact sheets (+ REVEAL_AUTO_COMPARE=1: camera JPEG | M1 Auto | M3 Auto); owner's photos -> private review folder only:
REVEAL_PRESET_DUMPS=<dumps and/or JPEGs> REVEAL_PRESET_OUT=<dir> ./gradlew --no-configuration-cache :core:pipeline:testDebugUnitTest --tests '*PresetRenderSheetTest*' --rerun -i
```

Env-driven opt-in tests need `--no-configuration-cache` (the cached configuration keeps the old environment).

## On-device mask models (`:core:ml`, M4/S spike + M4/C, ADR 0021)

`:core:ml` ships in every build (M4/C): the app and the editor use it, `:core:data` provides `MaskService` (Hilt) and
registers its disk cache with `CacheBudget` (`storage/MaskCacheArea`, `StorageKind.MASKS`, `cache/masks`). Models:
`core/ml/models.json` (URL or `derive`, SHA-256, bytes, licence; `bundled` = every build, `lab` = debug builds only)
made at build time by `scripts/ml/fetch_models.py --variant release|debug` (stdlib; cache `~/.cache/reveal-ml` by
checksum; `--check` re-downloads and re-derives everything; `--all --out DIR` every candidate; `--paths` cached files)
through `fetchMlModelsRelease/Debug` into `core/ml/build/generated/ml-assets/<set>/ml/` (never commit a model). A
`derive` entry is made from a pinned one: `convert` runs a script's `convert(bytes)` (`convert_deeplab_sky.py`: the
SPACE_TO_BATCH/BATCH_TO_SPACE triples -> dilated convolutions, 151 logits -> one sky logit; flatbuffer I/O in
`tflite_flatbuffer.py`, stdlib, deterministic), `extract` takes a zip member (the Face Landmarker `.task`). A new
model = a `models.json` entry + a `ModelSpec` / `LandmarkModelSpec` in `MaskModels` (`bundled`, `lab`, `pinned`);
`MaskModelsTest` keeps both in step. Ids are `<id>@<version>` for recipes and caches: never reuse an id for another
file. R8: `core/ml/consumer-rules.pro` keeps LiteRT's JNI-reached classes.

Release set: Selfie Multiclass (people + parts), MagicTouch (subject), `deeplab-v3-ade20k-sky` (sky), `face-landmarker`
(eyes, irises, lips, teeth, brows). `MaskService.mask(MaskRequest(photoKey = fingerprint, image = upright
recipe-independent render ≤ 1024 px, kind, inputVersion, model?, prompts, autoSeeds, refine, cues?))` ->
`MaskOutput(mask at the image size, raw model output, model key, fromCache, timings)`: `MaskService.modelFor(kind)` is
what a new recipe records; background = 1 - subject; subject seeds = `PhotoCues.seeds()` (faces + body, else saliency
peaks) + taps; face parts = `face/FaceLandmarker` (BlazeFace crop, two passes) + `face/FaceParts` polygons
(`SoftPolygon`, MediaPipe mesh indices). Cache keys: photo + model + kind + input version/size + seeds (+ refine for
the refined entry; the raw entry is shared by refinements and by all kinds of one Multiclass run); 8-bit, deflated,
hashed names (`service/MaskDiskCache`). One segmentation model loaded at a time (+ the landmark model); `release()`
frees them. Refinement: `refine/RefineDefaults` (per kind, size-independent: coefficient grid ~512 px), CPU
`GuidedRefiner` (reference; the service refines at ≤ 1024 px on the CPU), GPU `refine/GpuGuidedRefiner` (all passes,
`GuidedFilterShader`; needs a current GLES 3 context with RGBA32F targets). Pure Kotlin, JVM-tested: `tensor/`,
`face/`, `service/` (fake engines), `refine/GuidedRefiner`. Runtime: `runtime/LiteRtEngine` (CompiledModel;
`Backend.GPU` registers the CPU too; CPU fallback with `CPU_THREADS` XNNPACK threads). Debug mask lab: More ⋯ → Mask
lab (`feature/editor/src/debug/.../masklab`, `tools/DebugTools` with a `src/release` no-op; strings in
`src/debug/res`; models from both sets + Face parts), input = `GpuDevelopRenderer.renderOutputImage(recipe)`.

```sh
python3 scripts/ml/fetch_models.py --check                    # pinned files still match upstream, derived ones re-made
./gradlew :core:ml:testDebugUnitTest
uv run scripts/ml/verify_sky.py $(python3 scripts/ml/fetch_models.py --paths | awk '$1=="deeplab-v3-ade20k"||$1=="deeplab-v3-ade20k-sky"{print $2}') \
  core/ml/src/androidTest/assets/fixtures/*.jpg core/pipeline/src/test/resources/faces/*.jpg   # host: IoU vs original
./gradlew :core:ml:assembleDebugAndroidTest && adb -s <serial> install --user 0 -r -t core/ml/build/outputs/apk/androidTest/debug/ml-debug-androidTest.apk
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.ml.MaskModelsDeviceTest,dev.hfps.reveal.ml.GuidedFilterGpuTest,dev.hfps.reveal.ml.SkyConversionTest,dev.hfps.reveal.ml.FaceLandmarksDeviceTest,dev.hfps.reveal.ml.MaskServiceDeviceTest dev.hfps.reveal.ml.test/androidx.test.runner.AndroidJUnitRunner
#   emulators: add -e backend cpu (LiteRT's GPU accelerator does not run there; GPU parity is skipped)
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.ml.MaskBenchmark dev.hfps.reveal.ml.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s REVEAL_ML        # parity, bench (per model and backend), refine, sky-conversion, landmarks, service
# GPU op placement of a model: LiteRT logs the partition when it compiles (look for "delegate" / "partition")
adb -s <serial> logcat -d | grep -i -E "litert|delegate|partition" | tail -40
# unbundled candidates: push files into the test app's files/models (run-as dev.hfps.reveal.ml.test) to benchmark them
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.editor.MaskLabTest [-e shots true] dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The ADR's COCO numbers and the private review sheets come from host scripts kept outside the repo (LiteRT/ONNX on the
host, COCO val2017 panoptic subsets); review sheets of the owner's photos go only to the owner's private review
folder, never to the repo or a PR.

## Masks (`:core:pipeline/mask`, M4/A, ADR 0022)

Recipe (`recipe/Masks.kt`): `Mask` (id, name, `enabled` = the eye, `amount` %, `LocalAdjustments`, `components`
combined in order with `MaskOp` ADD = max / SUBTRACT / INTERSECT, each with `invert`); `MaskComponent` = `Linear`,
`Radial`, `Brush` (`BrushStroke`: points in `mask/StrokeCodec`'s base64 varint form; `BrushStroke.raw` while drawing,
`BrushStroke.of` simplifies when the stroke ends), `ColourRange`, `LuminanceRange`, `Ai` (ADR 0021) and `Unknown` (a newer
app's type, kept verbatim). Positions are fractions of W/H, lengths of the long edge, image coordinates before crop.
`LocalAdjustment` enumerates the local sliders with their ranges (`LocalAdjustments[adj]`, `.with(adj, v)`). History:
`HistoryField.MASK_*` (value = mask name). Masks are excluded from presets and from the default paste groups.

Engine: `MaskParams.from(recipe)` (rendered masks: enabled, amount > 0, an adjustment, a component; at most 32; their
adjustment vectors `V_*`, the global values `G_*`, `NEED_*` bits) lives in `DevelopParams.masks`; `MaskPlan.of(masks, W,
H)` packs the shapes for one image size (`PackedComponent`, `Dabs` per brush, AI layers). CPU reference:
`MaskRaster` (components, combination, brush raster, `MaskMath.store` = float16), `cpu/LocalAdjustOps.kt`
(`LocalField` sums, `LocalWhiteBalance` table, `LocalTotals`, `LocalColourOp`, `LocalDevelop`), the masked branches of
`CpuDevelop.renderLinear(…, masks: MaskContext)` and `DetailOp`. GPU: `gl/MaskShaders.kt` (the small `Masks` block,
the adjustment rows in `MaskDevelop` (vector rows 0-2, `localDevelopSum`) and `MaskDetail` (rows 3-4,
`localDetailSum`): keep both blocks small, SwiftShader counts uniform blocks against the default-uniform limit),
`gl/MaskPasses.kt` (brush layers re-rasterised per 64 px bin, coverage RGBA16F, 4 masks per layer, layers as cells of one
2D texture (`LayerGrid`: never a 2D array, the emulator caps those at 2048), cached in the preview), `Shaders.DEVELOP_FRAG`
`developMasked`, Detail's `FLAG_LOCAL` / per-pixel thresholds. Every program that includes `MaskShaders.MASKS` must call
`attachMasks`. A new local adjustment = a `LocalAdjustment` + field, a `V_` slot in the right rows (develop 0-11, Detail
12-19; and `G_` if it adds to a global), the CPU total and operator, the GLSL line, a case in `LocalAdjustOpsTest` (full
mask = global slider) and in `MaskFixtures`.

Brush options (M4/B fields, engine M4/P): `BrushStroke.density` (`cov += max(0, D - cov) a`, erase `cov *= 1 - a D`),
`pressure` (`StrokePressure` both / flow / size: what `k(p)` scales in `Dabs.of`), `autoMask` (each dab weighted by
the OkLab distance of the pre-local pixel to the pre-local pixel under its centre, `Dabs.AUTO_NEAR/AUTO_FAR`; reference
colours `MaskRaster.dabColours` / `MaskShaders.DAB_COLOURS_FRAG`, filled by a pre-pass over every tile in
`TiledRenderer`). A dab is 8 floats (`Dabs.DAB_FLOATS`).

Speed (M4/P, ADR 0022 "Speed"): the plan is keyed on ids + components (dabs cached per stroke list); a stroke in
progress or a mask's slider drag is a **partial stage-1 update** (`GpuDevelopRenderer.partialRect`: the changed
coverage rectangle, or `MaskBounds` of the masks whose vectors changed; develop pass scissored, `DetailPasses.run(dirty,
reach, finalFull)`, `EffectsPasses.update`), bit-identical to a fresh render (`MaskRenderTest
#partialUpdatesEqualAFreshRender`: keep it passing when you add a pass; a pass whose target is shared between levels
must be widened or run whole). Range masks: a global change redraws only the masks that read the pre-local image, and in the preview their
coverage pass computes the pre-local colour itself (`MaskShaders.FUSED_COVERAGE_FRAG` + `Shaders.developGlobal`, the
same code as the develop pass's unmasked path; `MaskPasses.attachFused`); an auto-mask brush keeps the pre texture.

AI masks: the engine asks `mask/AiMaskSource` (M4/C implements it in `:core:ml`); `GpuDevelopRenderer.setMaskSource`,
`TiledRenderer.render(…, masks = MaskContext(source, key))` (an export waits for its masks), the zoom tiles re-develop
when one arrives. Overlay for M4/B: `GpuDevelopRenderer.setMaskOverlay(MaskOverlay(id, COLOUR | GREYSCALE, colour,
opacity))`; `readCoverage(recipe, id)` and `maskStats` for tests.

Shared AI masks (M4/F, ADR 0022 "M4/F"): the editor, the export and the thumbnails make an AI mask once and read each
other's from the masks cache. Key = `MaskInputs.photoKey(image)` (the file's `FileFingerprint`) + the model input's size
(`MaskInputs.size`): open sessions with `AiMasks.session(photoKey, size) { render }` so the cache is read before the
input is rendered (`MaskService.cached(MaskLookup)`, no pixels); without a file, `session(null) { render }` keys by
`MaskInputs.contentKey`. `MaskInputs.smallInput(image, edge)` = proxy area-averaged to the model input size;
`Session.ready(components)` suspends until the parts are made. Thumbnails: `ThumbnailRepository.thumbnail` is the
quick one (AI parts absent), `withAiMasks` renders again with them (cells call it through `ThumbnailSource.upgrade` /
`FilmstripThumbs.upgrade`); the presets' previews get the editor's session by `PresetThumbnails.useMasks`. Test and
export timings (phone, `files/samples/sony-zv-e10.arw`; logs `REVEAL_MASKS_REUSE`):

```sh
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.library.AiMaskReuseTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
```

```sh
./gradlew :core:pipeline:testDebugUnitTest --tests '*mask*' --tests '*LocalAdjustOpsTest*' --tests '*MasksCodecTest*' --tests '*RecipeDiffTest*'   # + BrushOptionsTest
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.pipeline.MaskRenderTest,dev.hfps.reveal.pipeline.GpuParityTest#masksMatchCpuReference dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.pipeline.TiledRendererTest#maskedTilesStitchAndMatchThePreviewAndTheCpu,dev.hfps.reveal.pipeline.ZoomTilesTest#maskedTilesMatchTheCpuAndTheExport dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.pipeline.PipelineBenchmark#maskSliderDragAndBrushAppend dev.hfps.reveal.pipeline.test/androidx.test.runner.AndroidJUnitRunner
#   -e profile true (per-pass GPU timers), -e only <text> (drags whose name contains it; "stroke" = the strokes only)
adb -s <serial> logcat -d -s REVEAL_MASKS REVEAL_PARITY REVEAL_EXPORT REVEAL_ZOOM REVEAL_BENCH
```

Editor (M4/B, ADR 0022 "M4/B (editor)"): the Masks tool is a `ToolBehaviour.Panel` with a `PhotoLayer` (gestures that
see each pointer before the canvas + an overlay; `PhotoSlot(layer = chrome.panelLayer)`). `feature/editor/masks/`:
`MaskEdits` (pure recipe edits; invert = De Morgan), `MaskView` (image fraction <-> slot through `GeometryMapping`,
the warp inverted by Newton), `MaskHandles` (linear/radial drags in the isotropic plane), `MaskEditor` (the view
model's half: every `MaskActions` verb, strokes `raw` per move and `of` + one commit on lift, overlay, thumbnails via
`readCoverage`), `MasksPanel`, `MaskSheets` (chooser, ⋯), `MaskPhotoLayer` (handles, brush with pen pressure/eraser/
side button, eyedropper, subject taps, luminance map), `MaskLabels` (`MaskTags` for tests). State: `EditorUiState.masks`
(`MasksUiState`), actions `EditorActions.masks`. AI: `:core:data` `mask/AiMasks` (Hilt; `Session` = the renderer's
`AiMaskSource`, states per `AiMasks.key`) and `MaskInputs` (model input ≤ 1024 px, `VERSION`, content key);
`ExportEngine(aiMasks = …)` renders AI parts in exports. Recipe fields of M4/B: `BrushStroke.density/autoMask/pressure`,
`MaskComponent.Ai.autoSeeds` (engine side of the brush fields: M4/P). Test hooks: `EditorMetrics.masks`,
`applyRecipe`, `proxySize`.

```sh
./gradlew :feature:editor:testDebugUnitTest --tests '*masks*' && ./gradlew :core:data:testDebugUnitTest --tests '*AiMasksTest*'
# GPU + models: the tablet (x86_64 emulators wedge on the editor's GL); the AI cases run the real models
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.editor.MasksFlowTest,dev.hfps.reveal.editor.MaskPenTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s RevealAiMasks
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
`blobs/BlobUploader` (chunked, resumable from `HEAD`; unreadable originals are parked, not retried; a hand-off's local
smart preview is adopted; failures logged once with `describe`; `SmartPreviewMaker` lives in `:core:pipeline/preview`), `files/` (`ServerFilesProvider`
`<applicationId>.sync` + `RangeCache`: Server-source files by range), `work/SyncWork.kt` (`SyncWorker`, `BlobWorker`,
`SyncScheduler`, `SyncController` started by `RevealApplication`), `SyncRepository` (pair/unpair/status). Database v6:
`sync_records` (dirty marks by `SyncTriggers`, quiet while `meta` `sync.applying` is set; create databases with
`RevealDatabase.configure`), `photo_cloud`, `sync_uploads`, `sync_aliases`, `sync_inbox`, `versions.copy_of`,
`sources.backup`. Server photos: `sources.kind = SERVER`, document id `<fingerprint>/<name>`, `data/sync/ServerTree`.
Smart previews: `:core:pipeline` `preview/SmartPreviewCodec` (`PhotoOpener` opens them; writes format 2, reads 1
and 2: `preview/PreviewPlanes.kt`, noise-relative steps from `PreviewNoise`, tables in the header). What goes up (ADR
0018 amendment FIX/P): originals of backup-on folders **and of every edited photo** (`SyncDao.backupCandidates`); a
preview only when the original cannot go up (`previewCandidates(originalsOff, …)`: unreadable, or edited with the
originals backup off); previews no longer needed are dropped (`BlobUploader.markDrops` before the push, `drops` =
`DELETE /v1/blobs/{sha}` in the blob run, `sync_uploads.kind = drop_preview`); format 1 ones are remade 3 per run on
unmetered networks (`photo_cloud.preview_format`, DB v9). Grid: `CloudState.ELSEWHERE` = only another device has it
and no preview. Live editor reload:
`data/sync/RemoteEdits`. UI: `feature/settings/sync/` (Sync screen, `PairingScanner` CameraX + ZXing), library
`SpecialSource.SERVER`, `CloudState` badges, conflict cells (`LibraryTags.copy`). A new synced field: add it to the
payload, `photoData`/`versionChange` and the apply path, and (if it lives in a new column) to the triggers.

Server view (FIX/V, 0.5.3, ADR 0018 amendment, DB v10): what the server holds is per file (`photo_files.on_server`,
from the lookups, which ask about every file of a Server photo, and the NAS feed); `ServerFiles.original` trusts only
that. A Server photo opens from `ServerTree.reachable(files)`; the Server scope and its count list only photos that open
(`PhotoQuerySql.SERVER_OPENABLE_SQL`); a failed read of a Server photo never marks it unreadable. `engine/ServerLibrary`:
`removeFolder(source, deleteFromServer)` (before `LibraryRepository.removeSource`; keep = move to the Server source
with its uuid), `deleteFromServer(photoIds)`, the one-off `cleanUp` (meta `sync.cleanup`, re-pull from the start).
Deletes of originals queue in `sync_deletes` and go after the push (`BlobUploader.deleteOriginals`,
`DELETE /v1/originals/{fingerprint}`); a photo tombstone releases its preview on the server. Re-adding a folder merges
Server photos into it (`HandoffMerge.mergeServerPhotosInto`, after every fingerprint pass). New folders back up only
edited photos unless "Everything" is chosen (`addSource(uri, backup)`, `AddFolderBackupSheet`).

FIX/W (0.5.4, ADR 0018 amendment, DB v11): `photo.data.orientation` (Server photos without it read their header;
`DisplayImage.of`/`UprightSize` in the viewer); the lens profile default is never an edit for sync (`SyncStore
.isAutomatic`, `HistoryOp.Commit(onlyIfEmpty)`); conflict chooser `feature/library/ConflictSheet.kt` +
`SyncStore.conflict/keep` (`versions.edit_device/edited_at`); `HandoffBackup` (Settings -> Sync); Settings -> Storage
(`:core:data/storage` `AppStorage`, `CacheBudget` + `CacheArea`: thumbnails and `ServerFiles.areas`;
`feature/settings/storage/StorageScreen.kt`). A new disk cache: make it a `CacheArea`, register it with the budget,
cap it at `budget::cap`, and add its directory to `AppStorage.AREA_DIRS`.

```sh
./gradlew :core:sync:testDebugUnitTest --tests '*SyncPolishTest*'
./gradlew :core:data:testDebugUnitTest --tests '*AppStorageTest*'
./gradlew :core:sync:testDebugUnitTest --tests '*ServerViewTest*'   # two devices: listing, 404 cause, removal, re-add, clean-up
cd sync-server && go test -race ./internal/server/ -run 'Deleting|Tombstone'
adb -s <serial> shell am instrument -w -e class dev.hfps.reveal.sync.ServerViewFlowTest -e syncLinks "'$L1,$L2'" dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
```

```sh
./gradlew :core:sync:testDebugUnitTest            # FakeSyncServer: two devices, conflicts, NAS, uploads, 1,000 photos
./gradlew :core:sync:testDebugUnitTest --tests '*SmartPreviewRulesTest*'   # which photos send what, drops, format 1 remakes
# preview format sizes and developed CIEDE2000 vs format 1 (host rdumps of the CC0 RAWs and/or JPEGs; ~1 min each):
REVEAL_PREVIEW_SIZES=$PWD/build/calibration/sony-zv-e10.rdump,<more rdumps or .jpg> ./gradlew --no-configuration-cache \
  :core:pipeline:testDebugUnitTest --tests '*SmartPreviewSizesTest*' --rerun -i     # prints PREVIEW lines
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
