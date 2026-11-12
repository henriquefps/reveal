# Changelog

All notable changes to Reveal are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.4.0] - 2026-11-12

MS: sync with your own server. Pair phone and tablet with the reveal-sync server by QR code; edits, ratings and
versions follow you between devices (a conflict keeps both edits), NAS photos appear under Server, and photos that
only live on a device are backed up.

### Added
- Presets (the first tool in the editor): a grid of live previews of every preset on the photo you are editing,
  groups, Favourites and Recent. Tap to apply (one undoable step; tapping another replaces it while you browse), then
  set its strength from 0 to 200 %: every setting the preset touches scales from your photo's value. "Create preset"
  saves the current edit with the settings you choose into a group; long-press a preset to favourite, rename,
  duplicate, move, reorder, export or delete it.
- Preset files: export a preset (`.revealpreset`) or a group as a pack (`.revealpack`), import either from the panel,
  by opening the file or by sharing it to Reveal. A pack shows its author, licence and link and stays on trial, so you
  can try it on your own photos before keeping it. The format is documented (ADR 0019) and strictly checked.
- Apply a preset to the library selection (strength included): runs in the background with progress and Cancel,
  one undoable step on each photo.
- Presets sync between your devices with your sync server (with their groups, packs and favourites).
- Auto sees faces and the subject: it exposes for the people in the photo (only when their faces are badly lit:
  darker and lighter skin keep their own brightness), protects bright windows without darkening faces, and its white
  balance ignores skin and keeps faces from turning green, magenta or blue. Faces are found on the device, offline,
  by an open model (MediaPipe BlazeFace, Apache-2.0) run in Reveal's own code; nothing leaves the phone. On the
  owner's test shoots Auto's brightness is now closer to the camera's (median error 4.8 → 3.4 L\*, faces 7.5 → 4.8).
- Smart presets: a correction computed from each photo (exposure from the subject and the faces, highlight and shadow
  limits, white balance with the skin guard, optional straightening) and then a style. The results are ordinary
  slider values, and a smart preset applied to many photos adapts to each one. Built-in: Natural portrait, Bright
  outdoor, Moody indoor, Clean auto.
- 36 built-in presets in nine groups (Smart, Natural, Portrait, Landscape, Black & white, Film, Matte, Warm, Cool).
- Sync with your own `reveal-sync` server (Settings → Sync): pair by scanning the server's QR code or pasting its
  link (the device key stays in the Android Keystore). Edits with their history, virtual copies, ratings, flags,
  labels and the export author follow you to every paired device, in the background and within seconds while the app
  is open; edits made elsewhere show up in the open editor. When two devices change the same photo, the other edit is
  kept as a "Conflict" copy next to it in the library (Delete conflict copies in the selection menu).
- The library's Server entry shows the photos only your server has (its NAS folders and other devices' photos);
  they open in the viewer and the editor straight from the server, read in pieces and cached. Cells show where an
  original is: also on the server, only on the server, or only as a smart preview.
- Smart previews: a developable copy (about 2560 px) of photos only this device has, so other devices can edit them;
  the editor opens it when the original is out of reach. Originals backup: photos only this device has are uploaded
  once (Wi-Fi only by default, per folder), resuming where they stopped; photos the server already has never are.
- Exporting a photo whose original is on your sync server downloads it first (with progress and Cancel); a photo
  whose original is only on another device waits instead of failing, and its export finishes by itself once that
  device backs it up. The server's NAS photos sort by capture time like your own.
- `reveal-sync`: `GET /v1/devices` names the paired devices (for "Conflict copy from <device>").

## [0.3.0] - 2026-11-12

M2: the full global toolset (tone curve, colour mixer, grading, B&W, effects, Detail at 100 %, optics,
transform and Upright), every drag at the display rate on the phone, and a real tablet editor. The sync server
ships in the repository; the app side of sync comes in 0.4.0.

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
- Every Detail and Effects slider now keeps up with the 120 Hz display while you drag it, even with every tool on
  (noise reduction, sharpening, texture, clarity, dehaze, vignette, grain, tone curve, colour mixer and grading):
  the photo on screen is drawn from fewer, cheaper passes, and only the photo's area of the screen is redrawn.
- Luminance noise reduction keeps edges and fine lines better at high amounts; photos edited with it look marginally
  different (pipeline version 10).
- Tablets in portrait and foldables' almost square inner screens: the photo uses the whole width and the panel is
  docked at the bottom above the tool row; drag its top edge to make it taller or shorter (remembered). Landscape
  tablets keep the panel beside the photo.
- Pipeline version 9: lens corrections, defringe and the Transform (photos without them look exactly as before).
- Detail sliders respond faster while dragging: sharpening on its own now keeps up with the 120 Hz display, and
  luminance noise reduction drags cost about a quarter less. Colour noise reduction removes slightly more colour noise.
  Photos edited with Detail look marginally different (pipeline version 8).

### Fixed
- At 100 % on some tablets (Galaxy Tab S9 Ultra), a pixel row or column could be shown twice or skipped every dozen
  rows after a double-tap; 1:1 views now land exactly on the photo's pixels on every GPU.
- On lower-density screens (tablets), tapping the far end of a slider's track no longer opens the number entry meant
  for the value next to it.

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
