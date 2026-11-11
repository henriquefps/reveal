# ADR 0005: Design system (hfps identity in Compose)

Status: accepted (M0). Date: 2026-11-10.

## Decision

`:core:designsystem` (package `dev.hfps.reveal.designsystem`) holds the whole hfps look: tokens, typography, shapes,
Phosphor icons and the shell components. Features depend on it, never on Material icons or raw colours. It is a
leaf module (no project dependencies), so `:app` and every `:feature:*` can use it.

## Tokens: generated from `theme.css`

`scripts/theme/convert_theme.py <theme.css>` reads the `:root` (light) and `.dark` blocks, converts OKLCH to sRGB
(OKLab matrices, 8-bit rounding) and writes `RevealTokens.kt` (header "generated, do not edit"). Display P3
variants (`primaryP3`, `ringP3`, plus any token outside sRGB, today `warning` in light) use
`Color(r, g, b, a, ColorSpaces.DisplayP3)`; `RevealTheme` picks them when `Configuration.isScreenWideColorGamut`.
`--check` fails if the committed file is stale. The script also fails when a WCAG pair regresses (pairs below).

`RevealTheme(darkTheme)` maps the tokens to a Material 3 `ColorScheme` (surface = card, background = background,
outline = input, outlineVariant = border, error = destructive) and provides `RevealColors` (adds `warning`,
`mutedForeground`, `border`, `hairline`, `ring`, ...), `RevealTypography` and `RevealShapes`.
`ThemeMode { System, Light, Dark }` lives in the module; `:app` keeps it in `ThemeModeStore` (in memory; settings
swaps in DataStore later).

### WCAG contrast (measured by the script, sRGB after 8-bit rounding)

| Pair | Light | Dark | Min |
| --- | --- | --- | --- |
| foreground on background | 17.74 | 18.90 | 4.5 |
| muted-foreground on background / card / muted | 4.98 / 5.50 / 4.98 | 8.43 / 7.30 / 6.17 | 4.5 |
| destructive on background / card / muted | 5.14 / 5.68 / 5.14 | 6.78 / 5.88 / 4.97 | 4.5 |
| primary on background / card | 5.47 / 6.05 | 7.90 / 6.84 | 4.5 |
| primary-foreground on primary | 5.83 | 7.90 | 4.5 |
| warning on background / card | 4.55 / 5.03 | 11.38 / 9.86 | 3.0 |

## The editor darkroom (`EditorTheme`)

Always dark, independent of `ThemeMode`. Every neutral has zero chroma (R = G = B, asserted by
`EditorNeutralsTest`); only the accent (blue, from the dark tokens) and status colours have hue. Note: the plan's
example `#1c1c1e` has a blue offset, so it is not zero chroma and is not used.

| Role | Value |
| --- | --- |
| Photo surround (`background`) | `#1C1C1C` |
| Panel card (`card`) | `#262626` |
| Raised fill (`secondary`, `muted`, `accent`) | `#333333` |
| Text | `#F2F2F2` |
| Secondary text | `#A6A6A6` (>= 4.5:1 on all three surfaces, tested) |
| Hairline / input border | white 10 % / 15 % |
| Accent, destructive, warning | dark-token blue `#65A7FA`, red, yellow |

## Typography

Inter 4.1 variable font (SIL OFL; licence file in `core/designsystem/fonts`, row in `docs/licences.md`) bundled as
`res/font/inter_variable.ttf` (880 KB), weights through `FontVariation.weight`. Scale in sp: eyebrow 12 medium +0.08em,
UI 14, body 15, body-large 16, title 18 semibold, numbers 20/24 semibold, values 14; all numeric styles use
`fontFeatureSettings = "tnum"`; mono is the system monospace at 13/12. Every Material 3 style is mapped onto the
scale so stock components never fall under 12sp (M3's default `labelSmall` is 11sp). Uppercase happens only in the
`Eyebrow` composable.

## Shapes

Cards 16 dp (0.75rem base, rounded-xl), controls 10 dp, chips fully round, sheets 16 dp top corners.

## Icons: generator, not a Compose port

Evaluated: the community Compose ports of Phosphor (for example `phosphor-icon` by adamglin, Compose Multiplatform).
They are MIT and cover all weights, but ship thousands of generated icons per weight (a large AAR that relies on R8
to prune, and every Kotlin/Compose bump depends on a single maintainer rebuilding it), and we use about 30 icons
in two weights. Decision: `scripts/icons/generate_icons.py` (Python 3, no dependencies) downloads the pinned
`@phosphor-icons/core` 2.1.1 (MIT) from the npm registry at generation time (never at build time) and writes
`PhosphorRegular.kt` and `PhosphorFill.kt` with only the icons listed in `scripts/icons/icons.txt`. Each icon is an
`ImageVector` over a 256-unit viewport, built lazily from the SVG path data. Add an icon: append its name to
`icons.txt`, run the script, commit. `--check` verifies the committed files. No Material icons anywhere (not even
`Icons.Default.*`).

## Shell components

`RevealScaffold` (top bar on `background` + ONE card, 8 dp side/bottom margins, 16 dp radius, hairline border,
edge-to-edge insets via `safeDrawing`), `RevealTopBar` (56 dp, no bottom border), `RevealIconButton` (48 dp target,
content description + long-press tooltip), `PrimaryButton`/`SecondaryButton`/`GhostButton` (44 dp min, 10 dp radius),
`RevealChip`, `RevealListRow` (56 dp min), `Eyebrow`, `RevealBottomSheet` (Material modal sheet with drag handle and
hfps radius). Motion: `RevealMotion` (150 / 200 / 250 ms, ease-out `tween`s, so every change is interruptible).

## Verification

Roborazzi goldens for the gallery (`app/src/test/screenshots/gallery`): phone 390x844 dp and tablet 1280x800 dp,
light / dark / editor, phone at 200 % font scale. The gallery is the start destination until the library (M1).
