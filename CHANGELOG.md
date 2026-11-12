# Changelog

All notable changes to Reveal are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `reveal-sync`, the self-hosted sync server (`sync-server/`, ADR 0017): pairs devices by QR code (one revocable key
  per device), syncs edits, ratings and virtual copies with a change feed that never loses an edit in a conflict (the
  other edit becomes a virtual copy), backs up originals and smart previews with resumable uploads, and indexes NAS
  folders read-only so photos the server can reach are never uploaded and can be opened away from home. A setup page
  (English and Portuguese) manages devices, the backup folder and NAS folders. Docker image and static Linux
  binaries; the app side of sync comes next.
- Tone curve (Light → Tone curve): point curve for RGB and for red, green and blue separately (tap to add a point,
  drag to move, drag off to remove, double-tap to reset; Linear, Medium and Strong contrast presets) and a parametric
  curve (highlights, lights, darks, shadows with movable splits), with the photo's histogram behind the curve.
- Colour mixer (Colour → Colour mixer): hue, saturation and luminance for red, orange, yellow, green, aqua, blue,
  purple and magenta, with smooth transitions between colours, and a targeted adjustment: drag up or down on the
  photo to change the colour under your finger.
- Colour grading (Colour → Colour grading): shadows, midtones, highlights and global colour wheels with luminance,
  blending and balance; 3-way view or one large wheel.
- Black & white: a Colour / Black & white switch in the Colour panel, with a grey mix per source colour, Auto mix
  and the targeted adjustment; colour grading tones black & white photos.
- Effects panel: Texture (medium detail such as skin and foliage, negative to smooth it), Clarity (midtone local
  contrast) and Dehaze (removes or adds atmospheric haze), all edge-aware with no halos; a vignette after crop (amount,
  midpoint, roundness, feather, highlights) that follows the crop; grain (amount, size, roughness) that keeps its size
  relative to the picture and looks the same in the editor, at 100 % and in exports of any size.
- Noise reduction now knows how noisy the photo should be (ISO and camera from the RAW), so an amount means the same
  at ISO 200 and ISO 12800.

- Optics panel: lens corrections from the profile the camera writes into Sony RAW files (distortion, vignetting and
  chromatic aberration), or from the lensfun database for other cameras and for JPEGs, with distortion and vignetting
  amounts; the panel names the profile it found. New RAW photos with a profile open with the corrections on (one
  "Lens profile applied" step you can undo); photos edited before keep their look.
- Remove chromatic aberration: from the lens profile, or estimated from the photo when there is none.
- Defringe: purple and green amounts with adjustable hue ranges, for colour fringes along high-contrast edges.
- Transform (Crop → Transform): vertical and horizontal perspective, rotate, aspect, scale and offset, with the crop
  kept free of empty corners; Upright (Auto, Level, Vertical, Full) straightens buildings and horizons from the lines
  in the photo, and Guided Upright follows 2 to 4 lines you draw on the photo (with a magnifier). Upright sets the
  sliders, so you can fine-tune them after.
- Auto straighten: the Auto chip next to the angle dial levels the photo from its own lines.
- Tablets and unfolded foldables (840 dp and wider) get their own editor: the photo in the middle with a filmstrip of
  the folder (or library view) underneath, the panel on the right (drag its edge to make it wider or narrower; the
  width is remembered) and the tool rail. Tap a thumbnail, use the arrow keys or swipe the photo to move to the next
  or previous photo without leaving the editor; each photo keeps its edit. The filmstrip can be hidden from the top
  bar.
- Rotating or folding/unfolding keeps the photo, the zoom, the open panel and sub-panel and every step.
- S Pen and stylus: the pen is precise (smaller grab areas on curve points and crop handles), and while it hovers it
  highlights the point or handle under it with its value (curve in/out, crop size, guide angle, the colour under the
  targeted adjustment). Finger touches are ignored while the pen is down (palm rejection); with a pen, the targeted
  adjustment follows the pen and the finger pans and zooms.
- Keyboard shortcuts: Ctrl+Z undo, Ctrl+Shift+Z redo, `\` before/after, ← → previous/next photo, Z zoom to 100 %.
- Library on tablets: the selection bar shows copy, paste, reset and export directly; the viewer shows the photo info
  in a side panel next to the photo.

### Changed
- On tablets, sheets (export, paste, numeric input) open as centred dialogs.
- Pipeline version 9: lens corrections, defringe and the Transform (photos without them look exactly as before).
- Detail sliders respond faster while dragging: sharpening on its own now keeps up with the 120 Hz display, and
  luminance noise reduction drags cost about a quarter less. Colour noise reduction removes slightly more colour noise.
  Photos edited with Detail look marginally different (pipeline version 8).

## [0.2.1] - 2026-11-11

### Added
- Export: a Share button on the result shares the photos just saved (gallery or folder) without exporting again.
- Hand-off from Hoard: ratings, pick/reject and colour labels travel with the photos (`dev.hfps.reveal.extra.CULLING`,
  ADR 0011), so culling done on read-only folders shows up in Reveal; a sidecar edited since the last hand-off wins.
- Hidden XMP sidecars (`.<name>.xmp`) are read; the normal name wins when both exist, and Reveal keeps writing the
  normal name.
- Stable ids (UUIDs) on photos and versions, ready for sync (database version 5).
- Full-resolution zoom: zooming in past 50 % now shows the photo's real pixels instead of a magnified preview. The
  original is decoded in the background after the photo opens; the visible part is developed in tiles with the same
  maths as the export (the 100 % view is the exported pixels) and fades in within a fraction of a second. While a
  slider moves the fast preview shows, and the sharp tiles come back when you let go.
- Detail panel: sharpening (amount, radius, detail, masking, with a mask overlay while you drag Masking) and noise
  reduction for luminance (amount, detail, contrast) and colour (amount, detail, smoothness). Judged at 100 %; the
  zoomed-out view shows a matching approximation, and exports apply it at full resolution.

## [0.2.0] - 2026-11-11

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
- Export from the editor (Export in the top bar) and from a library selection: JPEG (sRGB or Display P3, with the
  colour profile embedded) or HEIF, quality, size (full, Web 2048, Instagram 4:5, long edge, megapixels,
  percentage), metadata (all, copyright only or none, remove location, keep capture time), to the gallery
  (Pictures/Reveal), a folder you choose, or Share (no copy stays behind), with file name templates and a live
  size estimate. Exports render at full resolution with the same pipeline as the preview and run in the background
  with a notification: you can close the app, cancel, and an interrupted export resumes where it stopped.
- Settings: author and copyright written into exported photos.
- Auto: one tap in the tool strip sets exposure, contrast, highlights, shadows, whites, blacks and vibrance (and the
  white balance when it is on Auto) from an analysis of the photo; the Light panel's Auto sets the Light sliders
  only. The result is ordinary slider values, one undoable step, and the same every time you tap it.

### Fixed
- A photo opened from another app could open with the edits and history of a different photo that had the same file
  name (cameras repeat `DSC00001.JPG` after a counter reset; two memory cards). Photos are now recognised by their
  content: the same photo keeps its edits even when it comes through another app or under another name, and a
  different file never inherits another one's edits.
- Cancelling an export while it was rendering could crash the app.
- The photo info sheet sometimes needed two presses of back (or two back gestures) to close on phones with a shorter
  screen or a larger font; one back now closes every sheet.

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
