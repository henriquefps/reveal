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
