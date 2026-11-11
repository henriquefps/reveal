# Third-party licences

Reveal is AGPL-3.0-or-later. Every third-party component that ships in the app or builds it is listed here
with its licence; add a row in the same change that adds the dependency, and mirror it in the in-app About screen
(`R.array.about_licences` in `app/src/main/res/values/strings.xml`, `component|licence` lines).

## Shipped in the app

| Component | Version source | Licence |
| --- | --- | --- |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | `gradle/libs.versions.toml` | Apache-2.0 |
| Jetpack Compose (runtime, UI, foundation, animation, Material 3) | Compose BOM in `gradle/libs.versions.toml` | Apache-2.0 |
| AndroidX core, activity, lifecycle, navigation, navigationevent, savedstate, fragment, startup, window, emoji2, profileinstaller, tracing, collection, annotation, arch-core, concurrent, customview, autofill, graphics, interpolator, loader, viewpager, versionedparcelable (transitive) | `gradle/libs.versions.toml` and the release runtime classpath | Apache-2.0 |
| AndroidX Room and SQLite | `gradle/libs.versions.toml` | Apache-2.0 |
| AndroidX WorkManager (`work-runtime-ktx`) and HeifWriter | `gradle/libs.versions.toml` | Apache-2.0 |
| Dagger / Hilt, AndroidX Hilt | `gradle/libs.versions.toml` | Apache-2.0 |
| javax.inject, jakarta.inject (via Dagger) | transitive | Apache-2.0 |
| Guava ListenableFuture (via AndroidX) | transitive | Apache-2.0 |
| JSR-305 annotations (`com.google.code.findbugs:jsr305`, via Dagger) | transitive | BSD-3-Clause |
| JetBrains annotations, JSpecify (via Kotlin / AndroidX) | transitive | Apache-2.0 |
| LibRaw 0.22.3 (vendored in `core/image/src/main/cpp/third_party/LibRaw`, statically linked into `librevealimage.so`) | tag and sha256 in its `VENDORED.md` | LGPL-2.1 (chosen from LGPL-2.1 / CDDL-1.0) |
| dcraw (inside LibRaw, non-restricted parts), DCB demosaic and FBDD (J. Gozdz), X3F library (R. Karlsson), Adobe DNG SDK 1.4 pieces | via LibRaw, see its `COPYRIGHT` | dcraw: public-domain-style; DCB/FBDD, X3F: BSD-style; DNG SDK: MIT |
| Hoard image core (`dev.hfps.image`, ported, same owner) | ADR 0004 | AGPL-3.0-or-later |
| Inter 4.1 variable font (`core/designsystem/src/main/res/font/inter_variable.ttf`; licence text in `core/designsystem/fonts/Inter-OFL-LICENSE.txt`) | rsms/inter release v4.1 | SIL OFL 1.1 |
| Phosphor Icons 2.1.1 (path data generated into `core/designsystem/.../icons/Phosphor*.kt`) | `scripts/icons/generate_icons.py` (`VERSION`) | MIT |

The shipped list was checked against `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` for 0.1.0
(every group on that classpath is covered by a row above).

## Build, test and analysis tooling (not shipped)

| Component | Licence |
| --- | --- |
| Android Gradle Plugin, Kotlin Gradle plugin, KSP | Apache-2.0 |
| JUnit 4 | EPL-1.0 |
| Truth | Apache-2.0 |
| Robolectric | MIT |
| Roborazzi | Apache-2.0 |
| ktlint, ktlint-gradle | MIT |
| detekt | Apache-2.0 |
| Compose Rules (nlopez) | Apache-2.0 |
| AndroidX Test, Espresso | Apache-2.0 |

## LibRaw and the AGPL

LibRaw is offered under LGPL-2.1 or CDDL-1.0; Reveal uses it under **LGPL-2.1**, unmodified, built from the
vendored sources by our own CMake file. LGPL-2.1 section 6 lets a combined work be distributed under terms of the
distributor's choice provided users may modify it and reverse engineer it for debugging; AGPL-3.0-or-later, with the
complete source of the app and of the library in this public repository, satisfies that and lets anyone rebuild
`librevealimage.so` with a modified LibRaw. The licence texts ship in the source tree (`LICENSE.LGPL`, `LICENSE.CDDL`,
`COPYRIGHT`) and the About screen lists LibRaw with its licence. Only LibRaw's core is used: the GPL demosaic and
GPL2/GPL3 "demosaic packs" are not vendored. (Same position as Hoard; not legal advice: re-check if the licence of the
app ever changes.)

## Test data

The RAW samples used by the device harness come from raw.pixls.us (all samples there are released by their authors into
the public domain / CC0), are downloaded by `scripts/fetch-samples.sh` with checksums into git-ignored `build/` and are
never committed. The JPEG/HEIF/PNG fixtures in `core/image/src/androidTest/assets/fixtures` are synthetic ramps.
