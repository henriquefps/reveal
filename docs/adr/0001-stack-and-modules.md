# ADR 0001: Stack and modules

Status: accepted (M0). Date: 2026-11-10.

## Stack

Kotlin 2.4 with the Compose compiler plugin, Jetpack Compose (BOM 2026.09.00) with Material 3 as the
component base, single activity, Navigation Compose 2.10 with type-safe (`@Serializable`) routes,
coroutines/Flow. Gradle 9.9.1 (wrapper), AGP 9.5.1 (built-in Kotlin: `org.jetbrains.kotlin.android` is
not applied), KSP 2.3, Gradle Kotlin DSL and a version catalog. JDK 21 runs Gradle; sources compile to
Java 17 bytecode. minSdk 29, compileSdk and targetSdk 37 (Android 17, stable).

## Modules and dependency direction

```
:app ──> :feature:{library,editor,export,settings}
:app ──> :core:data :core:pipeline :core:ml :core:image :core:designsystem   (DI wiring, theme)

:feature:*        ──> :core:designsystem   (every feature screen builds on the hfps theme; ADR 0005)
:feature:library  ──> :core:image  :core:data
:feature:editor   ──> :core:image  :core:pipeline  :core:ml  :core:data
:feature:export   ──> :core:image  :core:pipeline  :core:data
:feature:settings ──> :core:data
:core:data        ──> :core:pipeline :core:image   (Recipe JSON, edited thumbnails; pairing and headers, ADR 0006)
:core:pipeline    ──> :core:image
:core:ml          ──> :core:image
:core:image       ──> (nothing)
:core:designsystem ──> (nothing)   package dev.hfps.reveal.designsystem: theme, icons, shell components
```

Features never depend on each other; they meet in `:app` navigation. `:core:image` is package
`dev.hfps.image` (it may later be shared with Hoard); everything else is `dev.hfps.reveal.*`.

**`:core:pipeline` is one Android library, not a pure-JVM module plus an Android one.** The CPU reference
and the recipe model are plain Kotlin and are tested with ordinary JVM unit tests (an Android library's
`testDebugUnitTest` runs on the JVM, no emulator), while the GL renderer needs `android.opengl`. One module
avoids a second artifact and cross-module visibility rules. The purity is enforced instead: detekt's
`ForbiddenImport` rejects `android.*`/`androidx.*` in `**/pipeline/cpu/**` and `**/pipeline/recipe/**`.
The `reveal.jvm.library` convention plugin exists for any module that must be Android-free (for example
if the recipe moves out later).

## Convention plugins (`build-logic/`)

`reveal.android.application`, `reveal.android.library`, `reveal.android.feature` (library + Compose +
Hilt + serialization + navigation), `reveal.compose`, `reveal.hilt`, `reveal.jvm.library`,
`reveal.kotlin.options` (warnings are errors), `reveal.test` (JUnit 4 + Truth), `reveal.screenshot`
(Roborazzi), `reveal.quality` (ktlint + detekt + the per-module `qa` task). A module's build file is a few
lines: plugins, namespace, dependencies.

## Dependency injection: Hilt

Hilt over a hand-rolled container. Reasons: `@HiltViewModel` plus `hilt-navigation-compose` is the
shortest path for the per-screen ViewModels of four feature modules; WorkManager export workers
(`@HiltWorker`) and the Room database share one graph; compile-time validation catches wiring mistakes
that a hand-rolled container would surface at runtime on a phone. Cost: KSP in every module, accepted
(build cache keeps it cheap). Hilt modules live next to what they provide (`DataModule` in `:core:data`).

## Test stack

- JVM: **JUnit 4 + Truth** in every module (+ `kotlinx-coroutines-test`). JUnit 4 because Robolectric,
  Roborazzi, Compose test and the instrumented runner are JUnit 4 based; one framework everywhere.
  Robolectric (SDK 35) is used where Android classes are needed on the JVM (Room DAO tests, screenshots).
- Instrumented (`AndroidJUnitRunner`) in `:app` (launch + Hilt + start screen) and `:core:pipeline`
  (OpenGL ES 3.0+ present). Espresso 3.7.0 is pinned: older versions crash on API 36.1.
- **Screenshot tests: Roborazzi** (not Compose Preview Screenshot Testing). It runs on the JVM with
  Robolectric native graphics (no emulator), is stable, and supports plain Compose rules, so goldens can
  be produced in CI. Goldens are committed under `app/src/test/screenshots/`.
  Record: `./gradlew :app:recordRoborazziDebug`. Verify: `./gradlew :app:verifyRoborazziDebug`
  (`roborazzi.test.verify=true` in `gradle.properties` makes every `testDebugUnitTest`, and thus `qa`, verify).

## Quality gates

`./gradlew qa` runs, per module: unit tests (incl. screenshot verification), Android lint (warnings are
errors, no baseline), ktlint (`android_studio` code style plus the Compose ktlint rules, `.editorconfig`)
and detekt (`config/detekt/detekt.yml`, Compose rules enabled). CI runs `qa` and the debug build.

## Versions policy

All versions live in `gradle/libs.versions.toml`; build-logic reads the same catalog. Stable releases
only, bumped deliberately in their own change with `qa` green (lint's `GradleDependency` and
`NewerVersionAvailable` checks are off for that reason). Chosen versions are the latest stable that
resolve together on 2026-11-10; detekt stays on 1.23.8 (2.0 is still alpha).

## Versioning and signing

`reveal.versionName` in `gradle.properties` is the single source (0.1.0). `versionCode = major * 1_000_000
+ minor * 10_000 + patch * 100 + build` (build 0..99 for a re-upload), so 0.1.0 is 10000.
Application id `dev.hfps.reveal`; the debug build adds `.debug` and a `-debug` version suffix.

Release signing reads the environment, never the repo: `REVEAL_KEYSTORE` (path of the `.jks` that
`skv run` materialises), `REVEAL_KEYSTORE_PASSWORD`, `REVEAL_KEY_ALIAS`, `REVEAL_KEY_PASSWORD` (skv group
`reveal`). Without them the release is built unsigned with a warning (CI, contributors). Release builds
use R8 and resource shrinking.
