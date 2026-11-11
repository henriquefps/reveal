# Changelog

All notable changes to Reveal are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-11-10

The first preview: open a photo and see it developed on the GPU.

### Added
- Open a RAW (Sony ARW, DNG, CR2/CR3, NEF, RAF, ORF, RW2, …) or a JPEG/HEIF/PNG/WebP from the system picker
  ("Open photo") or from another app (View, Edit, Share). Originals are read in place and never changed.
- The editor (always dark, neutral surround): the camera's embedded preview appears at once, then the RAW is
  decoded and developed by Reveal's colour-managed GPU pipeline and replaces it without a flash.
- Zoomable canvas: fit, pinch zoom, double-tap fit/100 %, pan with fling, hold to see the original.
- Develop panel (preview controls): exposure, white balance temperature and tint, live while dragging, and reset.
- Clipped highlights render neutral white instead of magenta.
- Friendly error screens for a damaged file, an unsupported format or camera, lost access to the file, an
  unsupported GPU or too little memory.
- About screen with the version and every third-party licence; English and Brazilian Portuguese.

### Foundations (developers)
- Android project scaffold: Gradle Kotlin DSL with convention plugins, modules `:app`, `:core:*`
  and `:feature:*`, Hilt, Room, Navigation Compose with type-safe routes.
- Release signing from the environment (`skv run reveal -- ...`), R8 and resource shrinking.
- Quality gates: Android lint, ktlint, detekt with Compose rules, aggregate `qa` task.
- Test setup: JUnit 4 + Truth, instrumented runner, Roborazzi screenshot tests.
- GitHub Actions CI, ADR 0001 (stack and modules).
- Design system (ADR 0005), RAW/image core from Hoard with linear LibRaw decode and proxies (ADR 0004), develop
  pipeline with CPU reference, GLES 3 renderer and parity tests (ADR 0002, 0003).
