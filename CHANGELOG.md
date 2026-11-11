# Changelog

All notable changes to Reveal are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Library (start screen): add folders from the phone, an SD card or a USB drive (nothing is copied); they are
  indexed in the background and kept up to date; unplugged drives keep their thumbnails and show a warning.
- Grid with RAW+JPEG pairs as one photo, date headers, badges (RAW+JPG, edited, stars, pick/reject), filters
  (edited, not edited, picked, rated, RAW, JPEG), sort, search by file name, multi-select with drag, rate, pick,
  reject and remove from library. Tablets show folders and photos side by side.
- Ratings, labels and pick/reject are read from XMP sidecars (Hoard, Lightroom, darktable) and written back to them
  when the folder allows it.
- Full-screen viewer: swipe between photos, pinch or double-tap to 100 %, photo info, rate and flag, Edit.
- Edit history: unlimited undo and redo that survive closing the app, and a history list (More ⋯ → History) with
  named steps such as "Exposure +0.70" or "Paste edits"; tap a step to go back to it.
- Copy and paste edits, in the editor and on a selection in the library, with a chooser of what to paste (light,
  colour, profile, geometry, …); "Previous" applies the last edited photo's settings; reset all, reset a panel,
  reset to as opened, and "Reset edits" for a library selection. Every one of them can be undone.

## [0.1.1] - 2026-11-10

### Fixed
- Colours at sharp edges could be off by a few levels on some phones (Adreno GPUs): the photo is now sampled
  exactly at 100 % and with a fixed level of detail when zoomed out, so an unedited JPEG matches its original.

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
