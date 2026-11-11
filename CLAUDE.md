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

Parity and benchmark (emulator or phone, GLES 3.0+ with `EXT_color_buffer_float`):

```sh
ANDROID_SERIAL=emulator-<port> ./gradlew :core:pipeline:connectedDebugAndroidTest
adb -s emulator-<port> logcat -d -s REVEAL_PARITY REVEAL_BENCH     # measured errors and frame times
```

Debug canvas: `adb -s emulator-<port> shell am start -n dev.hfps.reveal.debug/dev.hfps.reveal.debug.PipelineDebugActivity`
(debug build only: `app/src/debug`, hosting `EditorDebugScreen`, to be replaced by the editor in M1).
