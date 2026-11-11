# Reveal: product spec

Reveal is a non-destructive RAW photo editor for Android, built for one real user first: the owner,
who shoots a **Sony ZV-E10** (24 MP APS-C, `.ARW` RAW + JPEG), culls in **Hoard** (his Android file
manager, github.com/henriquefps/hoard) and wants to stop paying for Photoshop/Lightroom mobile. The
bar is "I edit my photos here instead of Lightroom Mobile": every feature below must be complete,
correct and pleasant, not a demo.

Reveal is Hoard's second stage: **Hoard chooses, Reveal develops.** Hoard finds, culls, rates and
copies; Reveal edits the keepers and exports them.

## Principles

1. **Non-destructive, always.** Originals are never modified. An edit is a recipe (parameters), saved
   as the user goes; export renders the recipe. Every edit can be undone, reset or copied.
2. **Quality first.** Colour-managed, linear-light, 16-bit/float pipeline from the RAW data; no
   banding, no clipped highlights that were recoverable, no colour shifts between preview and export.
   Preview and export run the same pipeline (export at full resolution).
3. **Instant feedback.** A slider drag updates the photo every frame. The user never waits for a
   spinner to see the effect of a control.
4. **Help, don't take over.** Auto and smart presets propose a starting point, always shown as normal
   slider values the user can change. Nothing is applied without the user seeing it.
5. **Offline and private.** Everything runs on the device: decoding, AI masks, analysis. No account,
   no upload, no telemetry.
6. **Interoperable.** Ratings, labels and crops travel through XMP sidecars that Hoard, Lightroom and
   darktable read; exports are standard JPEG/HEIF/TIFF with correct metadata and colour profile.

## Inputs

- **Formats:** Sony ARW (first-class, tested on the owner's files), any RAW LibRaw supports (other
  cameras, DNG including phone DNGs), JPEG, HEIF/HEIC, PNG, TIFF. JPEG/HEIF edit with the same tools
  (no demosaic/WB-from-RAW, of course).
- **RAW+JPEG pairs:** shown as one photo (the RAW is edited; the camera JPEG is used as the instant
  first preview), same as Hoard's pairing rules (ADR 0006/0008 in Hoard).
- **Sources:** folders on the phone, USB drives and SD cards (Storage Access Framework tree grants,
  persisted), and files opened from other apps (`ACTION_EDIT`, `ACTION_VIEW`, `ACTION_SEND`,
  `ACTION_SEND_MULTIPLE`, and the Hoard hand-off below). SMB is Hoard's job: Hoard copies to the
  phone first; Reveal does not speak SMB.

## Library

- **Sources list:** "Opened from other apps" (recent hand-offs), added folders (SAF trees, with the
  volume name and an unplugged state), and "Recent". Adding a folder never copies anything.
- **Grid:** fast thumbnails (embedded previews, cached), pairs collapsed with a `RAW+JPG` badge,
  sorted by capture time (EXIF with sub-seconds) or name; "edited" badge (with a small rendered thumb
  of the edit, not the original), star rating and pick/reject flag read from XMP (written by Hoard's
  culling) and editable here.
- **Filters:** all / edited / not edited / picked / rated ≥ N / RAW / JPEG; sort; search by file name.
- **Multi-select** (long-press then tap, drag-select across rows): copy/paste edits, apply preset,
  reset, export, rate/flag, remove from library (never deletes files).
- **Viewer:** full-screen swipe between photos with pinch zoom to 100 %, info sheet (EXIF: camera,
  lens, shutter, aperture, ISO, focal length, date, dimensions, file names and sizes), "Edit".

## Editor (the core)

All tools work on RAW and on JPEG/HEIF. Every slider: double-tap resets it, tap the value to type
it, long-press the panel title to reset the whole panel. All values are stored in the recipe and
shown in the history.

### Auto and profiles
- **Auto:** one tap sets exposure, contrast, highlights, shadows, whites, blacks, vibrance (and
  white balance when "Auto WB" is chosen) from the image analysis; the result is plain slider values.
- **Profiles** (the base look applied before the adjustments): Standard (default), Neutral/Flat,
  Vivid, Portrait (gentle skin), Landscape, Monochrome, plus "Camera JPEG match" calibrated to the
  ZV-E10's own JPEG rendering (documented calibration on the owner's RAW+JPEG pairs). Profile amount
  0-200 %.

### Light
- Exposure (±5 EV), Contrast, Highlights, Shadows, Whites, Blacks (Lightroom-style semantics:
  highlights/shadows are local, edge-aware, no halos).
- **Tone curve:** parametric (highlights, lights, darks, shadows with movable splits) and point curve
  for RGB and for R, G, B separately; presets Linear / Medium contrast / Strong contrast; histogram
  behind the curve.
- Highlight recovery uses the RAW headroom (reconstruct clipped channels; never pink/magenta
  highlights).

### Colour
- **White balance:** As shot, Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Flash, Custom;
  Temperature in kelvin and Tint for RAW (relative −100..+100 for JPEG); **eyedropper** (tap a
  neutral area on the photo, with a loupe).
- Vibrance, Saturation.
- **Colour mixer (HSL):** 8 bands (red, orange, yellow, green, aqua, blue, purple, magenta) × hue,
  saturation, luminance, plus a "targeted adjustment" mode: drag on the photo to change the band
  under the finger.
- **Colour grading:** shadows / midtones / highlights / global wheels (hue, saturation, luminance),
  blending and balance.
- **Black & white:** a B&W treatment with its own 8-band grey mixer (luminance per source colour) and
  Auto mix.

### Effects
- Texture, Clarity, Dehaze (all ±100, edge-aware, no halos).
- Vignette (post-crop: amount, midpoint, roundness, feather, highlights protection).
- Grain (amount, size, roughness; scaled to output size so it looks the same in preview and export).

### Detail
- Sharpening (amount, radius, detail, masking with an "show mask" overlay while dragging, Alt-style).
- Noise reduction: luminance (amount, detail, contrast) and colour (amount, detail, smoothness).
  Quality must be judged at 100 % on high-ISO ZV-E10 files (ISO 3200-12800). An AI denoise is a later
  milestone (see milestones), not a replacement for these.
- Both previewed correctly at 100 % (and approximated sensibly when zoomed out).

### Optics
- **Lens corrections:** distortion, vignetting and lateral chromatic aberration, automatic from the
  lens in the EXIF (Sony embeds correction parameters in the ARW for E-mount lenses; lensfun as a
  fallback), with manual amount sliders; "remove chromatic aberration" (purple/green fringe
  defringe).

### Geometry (crop and transform)
- **Crop:** free, original, 1:1, 4:5, 5:4, 3:2, 2:3, 16:9, 9:16, custom; lock/unlock; swap
  orientation; handles on corners and edges, drag the photo under the crop frame, pinch to zoom inside
  the crop; rule-of-thirds grid while dragging, finer grid while straightening.
- **Straighten:** angle dial (±45° with 0.1° precision, haptic tick at 0), **auto-straighten** (detect
  the horizon/verticals), and a "draw a line along the horizon" tool.
- Rotate 90° left/right, flip horizontal/vertical.
- **Transform/perspective:** vertical, horizontal, rotate, aspect, scale, offset; **Upright** auto
  modes (level, vertical, full, guided with 2-4 drawn guides); constrain crop to avoid empty corners.
- Crop and transform show the full uncropped image while editing; the rest of the editor shows the
  cropped result.

### Masks (local adjustments)
- **AI masks** computed on the device: **Subject** (people, animals, objects in focus), **Sky**,
  **Background** (inverse of subject), **People** with parts when feasible (face skin, body skin,
  hair, eyes, lips: a later milestone if the models are not good enough), all offline.
- **Linear gradient**, **radial gradient** (with feather, invert), **brush** (size, feather, flow,
  density, auto-mask edge detection, erase).
- **Range masks** to refine any mask: luminance range and colour range (eyedropper).
- Combine components per mask: add, subtract, intersect; invert.
- Each mask has its own set of local adjustments: exposure, contrast, highlights, shadows, whites,
  blacks, temperature, tint, saturation, texture, clarity, dehaze, sharpness, noise, hue shift, and a
  colour tint.
- Mask overlay (colour and opacity configurable) while editing a mask; masks list with names,
  show/hide, duplicate, delete, rename. Masks follow crop/rotate/transform (they are defined in image
  coordinates).

### Healing
- **Spot heal / clone** with brush size and feather: tap or paint over a blemish/dust spot, source is
  chosen automatically (patch search) and can be dragged; heal blends, clone copies. **Visualise
  spots** (high-pass view) to find sensor dust.
- **Remove** (bigger objects): patch-based content-aware fill first; an on-device inpainting model is
  a later milestone if it beats the patch method on the owner's photos.
- Red-eye (people and pets): small, later.

### Views and comparison
- Histogram (RGB + luminance), live while editing; **clipping warnings** (tap the histogram corners:
  shadow clipping in blue, highlight clipping in red on the photo).
- **Before/after:** press and hold on the photo (or a button) to see the original; split view
  (draggable divider, left/right and top/bottom) and side-by-side.
- Zoom: pinch, double-tap fit ↔ 100 %, a loupe for the eyedropper/healing, pan with one finger when
  zoomed (two fingers while a brush tool is active).
- Hide all UI with a tap on the photo (immersive view); show the EXIF line optionally.

### History, versions, copy/paste
- **Undo/redo** (unlimited within a session, persisted across restarts), **history list** (named steps
  like "Exposure +0.70", tap a step to go back to it), **snapshots** (named, saved states).
- **Versions / virtual copies:** several independent edits of one photo (e.g. colour and B&W), shown
  as separate items in the library.
- **Copy/paste edits** with a chooser (all, or per group: light, colour, effects, detail, optics,
  geometry, masks, healing), paste onto one or many photos; "Previous" (apply the last photo's edit).
- Reset all / reset panel / reset to "as opened from camera".

### Presets
- **Built-in presets** grouped by style (natural, portrait, landscape, B&W, film-like, matte, warm,
  cool, …), designed and checked on real ZV-E10 photos, with a live mini-preview of each preset on the
  current photo (rendered thumbnails, not the same sample image for all).
- **Preset strength** slider (0-200 %) that scales every parameter the preset sets.
- **User presets** from the current edit with a group chooser (which settings to include), organised
  in groups, renamed, reordered, deleted; import/export as files (own format, documented; Lightroom
  `.xmp` preset import for the overlapping basics is a later nice-to-have).
- **Smart presets (the owner's main ask):** a preset made of two parts:
  1. *Correction* rules computed from the photo first: target exposure from the histogram and the
     subject/face brightness, shadow lift and highlight protection from clipping, white balance from
     the scene (neutral estimation, skin-tone guard when faces are present), optional auto-straighten.
  2. *Style*: the user's colour look (profile, curves, HSL, colour grading, effects), applied on top
     with the preset strength.
  The rules are simple, documented, deterministic and adjustable (e.g. "target subject brightness",
  "max shadow lift"), and the resulting slider values are shown as normal edits. A smart preset can
  be applied to many photos at once (batch) and adapts to each photo.
- Favourite presets, recently used.

### Export and share
- **Formats:** JPEG (quality, 8-bit, sRGB or Display P3), HEIF/AVIF where the device supports it
  (10-bit P3), PNG and 16-bit TIFF for further editing, original file copy.
- **Size:** original, long edge in px, megapixels, percentage; presets "Instagram 4:5 (1080×1350)",
  "Full", "Web 2048"; sharpening for output (screen / matte / glossy, low/standard/high).
- **Metadata:** all / copyright only / none, remove location (GPS) independently, keep capture time;
  copyright and author fields from settings; file name templates (original name, date, sequence,
  suffix like `-edit`).
- **Destination:** a folder chosen through SAF (remembered), the system "Pictures/Reveal" album via
  MediaStore, or **Share** to any app (Instagram, WhatsApp, Drive…) without leaving files behind.
- **Batch export** of a selection runs in the background (WorkManager, notification with progress,
  cancel, survives the app closing), exactly the same rendering as the editor.
- **Export presets** (named combinations of the above).

### Hand-off with Hoard
- Reveal accepts photos from Hoard: one or many content URIs (with RAW+JPEG pairs kept together) via
  an explicit intent action documented in `docs/adr/` (e.g. `dev.hfps.reveal.action.DEVELOP` with
  `ClipData`, plus standard `ACTION_EDIT`/`ACTION_SEND_MULTIPLE` for other apps). Hoard's side (an
  "Edit in Reveal" action in its culling screen) is built by the Hoard project after this contract is
  published.
- Ratings, flags and labels: read and written through standard XMP sidecars (`xmp:Rating`,
  `xmp:Label`, Hoard's pick/reject convention documented in Hoard's ADR 0009), so both apps agree.
- The edit recipe is also written to an XMP sidecar next to the original when the folder is writable
  (own namespace, plus the standard crop and orientation fields), so moving the files with Hoard keeps
  the edits. The app database is the working copy; the sidecar is the portable copy.

### Settings
- Theme for the library (System, Light, Dark; the editor is always dark), default profile, default
  "open with Auto", camera JPEG preview first on/off, histogram on/off, mask overlay colour, haptics,
  export defaults, copyright/author, cache size and "clear cache", about/licences (LibRaw, models).

## Out of scope (now)
Cloud sync and accounts; video; SMB access (Hoard's job); HDR merge, panorama stitching and focus
stacking (possible later milestones, not now); generative AI that invents content beyond removal;
social features; ads; tracking.

## Quality bar (acceptance for any feature)
- Works on the owner's ZV-E10 RAW and JPEG files and on JPEGs from his phone camera.
- Preview equals export (pixel diff of a downscaled export vs. the preview within a small tolerance,
  measured in tests).
- Every control has a tested CPU reference of its formula; the GPU result matches it within tolerance.
- No jank: slider → updated photo at 60 fps on the owner's phone, measured.
- i18n en + pt-BR from day one (the owner reads Portuguese); no hard-coded strings.
- Light/dark (library), phone portrait and landscape, tablets and foldables (Galaxy Tab), TalkBack
  labels on every control, font scaling up to 200 % without broken layouts.
- A worst-case pass: 10,000-photo folder, a 60 MP DNG, a corrupt file, a photo with 200 masks/steps, a
  removed SD card mid-export, low storage, the app killed mid-export.
