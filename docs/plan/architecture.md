# Reveal: architecture (starting point)

These are the starting decisions. Each one gets an ADR in `docs/adr/` when it is implemented; a
spike may overturn one, but only with measurements written in the ADR.

## Stack

- **Kotlin + Jetpack Compose**, single-activity, Material 3 as the component base re-themed to hfps
  (see `ux.md`). Coroutines/Flow; a small hand-rolled DI or Hilt (choose one in ADR 0001).
- **minSdk 29** (Android 10: scoped storage era, modern codecs, Vulkan/GLES 3.2 widely present; the
  owner's phone is a recent Samsung), target/compile = latest stable. Gradle KTS + version catalog.
- Modules (Gradle):
  - `:app` - UI, navigation, DI wiring.
  - `:core:image` - decoding (LibRaw via JNI, JPEG/HEIF via platform), metadata (EXIF/XMP/maker
    notes), thumbnails/previews, caches. **Start from Hoard's image core** (`dev.hfps.hoard.image` and
    `android/app/src/main/cpp` with vendored LibRaw 0.22.3 in github.com/henriquefps/hoard; read its
    ADR 0006), copied in with history noted in the ADR, renamed `dev.hfps.image`, so a later step can
    make Hoard depend on the same module. Same licence (AGPL-3.0-or-later), same owner.
  - `:core:pipeline` - the develop pipeline: recipe model, CPU reference implementation (pure Kotlin,
    JVM-tested), GPU implementation, renderer for preview/export.
  - `:core:ml` - on-device models (masks, analysis), behind interfaces so models can be swapped.
  - `:core:data` - Room database, repositories, XMP sidecar reader/writer.
  - `:feature:library`, `:feature:editor`, `:feature:export`, `:feature:settings`.

## The develop pipeline

1. **Decode.** RAW: LibRaw unpack + demosaic (AHD/DCB/AMaZE-class quality; pick by measurement on
   ZV-E10 files) to **linear camera RGB, 16-bit**, no WB/curve/gamma applied by LibRaw (raw
   multipliers handled by the pipeline). Keep black/white levels, camera matrices (`cam_xyz`), as-shot
   multipliers, and the embedded lens correction data. JPEG/HEIF: decode, linearise from its colour
   space (sRGB/P3) into the working space.
   - The camera JPEG or embedded preview is shown immediately while the RAW develops.
2. **Proxies.** A screen-size proxy (long edge ≈ display long edge, e.g. 2400-3000 px) and a
   half-resolution level are kept as float16 textures for interactive editing; **full resolution is
   rendered in tiles** for 100 % zoom regions and export. Proxies cached on disk per photo (keyed by
   file identity + decoder version).
3. **Working space:** linear-light, wide gamut (linear Rec.2020 or ProPhoto, float16 on the GPU,
   float32 on the CPU reference). Order of operations (documented in an ADR, fixed):
   raw WB → camera matrix → lens corrections (geometry later, on coordinates) → exposure →
   highlight reconstruction → profile (base look) → white balance fine/tint → tone (contrast,
   highlights/shadows via edge-aware local tone mapping, whites/blacks, curves) → colour (vibrance,
   saturation, HSL, colour grading, B&W) → local adjustments (masks) → detail (NR, sharpening) →
   effects (texture/clarity/dehaze, vignette, grain) → geometry (crop/rotate/transform as one
   resampling) → output transform (sRGB / Display P3 / Rec.2020 PQ for HDR export later) + dithering.
4. **GPU:** OpenGL ES 3.2 with float16 render targets as the default (mature, Compose-embeddable via
   `TextureView`/`SurfaceView`), each stage a fragment shader, fused where possible; local operators
   (bilateral grid / guided filter / Laplacian pyramids) as multi-pass. Vulkan only if a measured
   bottleneck needs it (ADR). Render on a dedicated GL thread; Compose never blocks on it.
5. **CPU reference:** every operator also exists as plain Kotlin over float arrays, used by unit tests
   (golden values on small synthetic images) and to check GPU parity on the emulator and the phone.
6. **Export:** the same shaders at full resolution in tiles (with overlap for local operators), then
   encode (JPEG via libjpeg-turbo or the platform encoder with ICC profile; HEIF/AVIF via platform;
   TIFF 16-bit own writer), metadata written with an EXIF/XMP library (exiv2 or a Kotlin writer,
   ADR). Export runs in a WorkManager worker with its own GL context.

## The recipe (edit model)

- A versioned, immutable data class tree (`Recipe(v=1, profile, light, colour, effects, detail,
  optics, geometry, masks[], heals[])`) with defaults = "no change"; serialised as JSON (kotlinx
  serialization), stored per photo version in Room; diffs drive the history list ("Exposure +0.70").
- History: each committed change (slider release, not every frame) is a step; undo/redo stacks
  persisted.
- **Determinism:** the same recipe + same file + same pipeline version = the same pixels. A
  `PIPELINE_VERSION` is stored with each render cache; changing a formula bumps it.
- Masks store their definition (model id + parameters for AI masks, the cached bitmap as a cache only,
  vector strokes for brushes, gradient geometry) in image coordinates.
- **XMP sidecar** (portable copy): `xmp:Rating`, `xmp:Label`, `crs:Crop*`/orientation where
  semantics match, and the full recipe in a `reveal:` namespace (documented schema). Written next to
  the original when the folder is writable (SAF), never inside the original file.

## Analysis and smart presets

- Reuse the analysis ideas from Hoard's ADR 0009 (histogram, clipping, sharpness, faces with ML Kit
  bundled face detection), documented platform-neutrally; add scene/subject brightness from the
  subject mask and a neutral/grey-world + gamut-based white balance estimate with a skin-tone guard.
- Smart preset = `rules(analysis) -> partial Recipe` (documented formulas and constants) merged with a
  style `Recipe` scaled by strength. Pure functions, JVM-tested on fixtures of analysis values, and
  calibrated on the owner's photos (aggregates only in the ADR).

## On-device ML

- Candidates to evaluate in a spike (ADR with quality on the owner's photos, size, speed, licence):
  MediaPipe Image Segmenter / interactive segmenter, ML Kit Subject Segmentation (needs Google Play
  services: acceptable only if the bundled options are worse), sky segmentation models
  (ADE20K-class DeepLab/SegFormer on LiteRT), portrait matting models for hair-accurate edges; run on
  GPU/NNAPI delegates with a CPU fallback. Models are downloaded at build time into assets (no runtime
  download); APK size impact stated.
- Edge refinement (guided filter on the full-res image) so masks follow hair and fine edges.
- Spike result (M4/S): ADR 0021 (LiteRT; Selfie Multiclass for people and parts, DeepLab ADE20K for sky, MagicTouch
  for the subject; guided filter refinement; masks referenced by model id@version, never stored as pixels).

## Storage and files

- Room DB: sources (SAF tree URIs, volume ids), photos (identity = source + relative path + size +
  mtime, pairing), versions, recipes, history, presets, export presets, caches index.
- Files are read through SAF/`ContentResolver` and file descriptors (`ParcelFileDescriptor`) into the
  native decoder; no copying of originals. Caches in the app cache dir with an LRU cap (setting).
- Exports to a SAF folder, to MediaStore (`Pictures/Reveal`), or to the share sheet via a
  FileProvider of a temp export (cleaned up).

## Hand-off contract (with Hoard)

- `dev.hfps.reveal.action.DEVELOP` (explicit), plus `ACTION_EDIT`, `ACTION_VIEW`, `ACTION_SEND`,
  `ACTION_SEND_MULTIPLE` for `image/*` and RAW MIME types (`image/x-sony-arw`, `image/x-adobe-dng`,
  …). `ClipData` of content URIs with read (and write, for sidecars) grants; RAW+JPEG pairs grouped
  by stem. Documented in an ADR with an example `Intent`, then the Hoard project is told so it can add
  "Edit in Reveal".

## Testing and measurement

- JVM unit tests: recipe model, history, every operator's CPU reference (golden images), smart preset
  rules, XMP reader/writer (round-trip, escaping, Lightroom/darktable samples), file identity/pairing.
- Instrumented tests on an emulator: GPU parity vs CPU reference, decode of sample files, export
  round-trip (decode the exported JPEG and compare with the preview), Room migrations.
- Screenshot tests for key screens (Compose Preview screenshot testing or Roborazzi).
- Performance on the owner's phone (release builds only): time to first image, time to editable
  proxy, frame time while dragging (target ≤ 16 ms at the screen proxy), 100 % tile time, export time
  for a 24 MP ARW, peak memory. Recorded in the relevant ADR.
- Real photos: the owner's ZV-E10 RAW+JPEG pairs (two private shoots, paths given by the
  orchestrator, never written in the repo) are private and read-only: use copies,
  never commit them or screenshots that show them, report aggregates only. Public test RAWs
  (raw.pixls.us, CC0) for committed fixtures.
