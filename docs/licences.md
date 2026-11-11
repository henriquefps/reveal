# Third-party licences

Reveal is AGPL-3.0-or-later. Every third-party component that ships in the app or builds it is listed here
with its licence; add a row in the same change that adds the dependency. The in-app About screen is
generated from this list.

## Shipped in the app

| Component | Version source | Licence |
| --- | --- | --- |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | `gradle/libs.versions.toml` | Apache-2.0 |
| AndroidX (core, activity, lifecycle, navigation, Room, Compose, Material 3) | `gradle/libs.versions.toml` | Apache-2.0 |
| Dagger / Hilt | `gradle/libs.versions.toml` | Apache-2.0 |
| Inter 4.1 variable font (`core/designsystem/src/main/res/font/inter_variable.ttf`; licence text in `core/designsystem/fonts/Inter-OFL-LICENSE.txt`) | rsms/inter release v4.1 | SIL OFL 1.1 |
| Phosphor Icons 2.1.1 (path data generated into `core/designsystem/.../icons/Phosphor*.kt`) | `scripts/icons/generate_icons.py` (`VERSION`) | MIT |

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
