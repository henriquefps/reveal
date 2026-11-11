# ADR 0009: Export

Status: accepted (M1, Export). Date: 2026-11-11. Builds on ADR 0002 (tiles seam, output transforms, dither,
sampling), 0004 (full decode, memory), 0006 (versions and recipes), 0007 (Light operators' grid), 0008 (profiles).

Export renders the photo with the same shaders as the preview at full resolution, in tiles, encodes it to JPEG or
HEIF with its metadata, and writes it to the gallery, a SAF folder or the share sheet, always in a background
WorkManager job. Code: `:core:pipeline` `gl/TiledRenderer`, `gl/ExportShaders`, `export/` (sizes, tile plan),
`cpu/LanczosResampleOp`, `input/TileSource` (geometry: `GeometryMapping`/`GeometryOp` of slot 13); `:core:image` `format/ExifSnapshot`; `:core:data` `export/ExportPreferences`,
`LibraryRepository.exportTarget`; `:feature:export` (engine, encoders, metadata, destinations, worker, sheet);
`:feature:settings` (author and copyright).

## Full-resolution rendering in tiles

**Own GL context.** `TiledRenderer` has its own `HandlerThread` and EGL context (never the editor's): an export in
the worker runs while the editor renders, and everything is released on `close()`.

**Input.** A `TileSource` is "a linear image read in pieces", upright, in the `PipelineInput` encoding:
`NativeRawTileSource` over the full-size `NativeLinearImage` (LibRaw's own buffer, ADR 0004; the orientation is applied
while reading), `BitmapTileSource` over a standard image decoded once as an `ARGB_8888` bitmap in its own colour space
(native memory) and linearised per tile with the decoder's table, `PipelineInputTileSource` for in-memory images
(tests). Regions are uploaded into an RGBA16F texture in 64-row bands (2 MB staging whatever the tile size), the
same float -> half conversion as the preview's upload.

**The shaders are the preview's.** The only change to them is a `uOrigin` uniform (0 for the preview, so preview
output is bit-identical to before): the scene and develop stages read their tile textures at the local position and
the Light grids at the image position; the two grid row passes read their source at `image position - uOrigin`.
`LightPasses` gained `reconstructRows` / `reconstructFinish` and `guideRows` / `guideFinish`; `reconstruct()` and
`guide()` are those two steps over the whole image, as before.

**Grid pre-passes, once for the whole image (consistency across tiles).** The Light operators' only full-resolution
neighbourhood is one grid cell (ADR 0007), so:

1. *Highlight reconstruction*: for every pre-pass tile (cell-aligned squares of at most the tile size covering the
   **whole** image, not just the crop, as the preview's grid does), upload the source and draw its chroma row
   segments into the image-wide rows target; then the cell means, push and pull run once. Whether reconstruction
   runs at all is decided from the full-resolution pixels while uploading (`HighlightReconstructionOp.needed`).
2. *Local tone guide*: for every pre-pass tile, upload, run the scene stage (with the finished chroma grid), draw
   the guide row segments; then the cell moments, box means and coefficients run once.

Cell-aligned tiles keep every row segment of a cell inside one tile, and the sums run in the same order as a
whole-image render: the grids are **bit-identical** to an untiled render's. A tile never computes its own grid
(which would differ at every tile edge).

**Main pass per output tile:** upload the tile's input region, scene stage, develop stage, the **geometry operator**
(slot 13, ADR 0002 "Geometry": crop, straighten, quarter turns, flips; `GeometryOp` line for line in
`ExportShaders.GEOMETRY_FRAG`: an exact texel copy without straightening, Catmull-Rom with it, black in empty
corners) when the recipe has geometry, then (when the size changes) the two Lanczos passes, then the export output pass (working space -> output matrix, clamp, sRGB OETF,
8-bit with the dither of the **output** pixel position `uOrigin + p`, the same hash as `OutputTransformOp` and the
on-screen pass). Read back in 128-row bands into a `TileSink`. A `high = true` render writes the encoded value
unquantised (RGBA32F) for 10/16-bit encoders (PNG/TIFF 16-bit in M5; tested against the 8-bit output).

**Overlap.** The Light group needs none (ADR 0007). After the grids, two operators read neighbourhoods, and the plan
(`ExportPlan`, three spaces: image -> geometry output -> final output) chains their reach backwards: an output tile's
**geometry box** is what its Lanczos taps reach (plus one pixel on each side: a GPU whose `ceil`/`floor` lands one tap
further reads inside, that tap's weight being 0); its **input region** is what the geometry operator reads for that
box (`GeometryMapping.sourceRegion`, Catmull-Rom margin included; null in an empty corner, then the tile is black).
Tiles are sized so the input region fits the tile (the box edge divided by sqrt 2 when straightened). The geometry and
Lanczos passes reuse the two RGBA32F targets, no extra texture. JVM-tested.

**Tile size and memory.** Default 2048: three RGBA16F `tile²` textures (source, scene, working, 32 MB each), two
RGBA32F resampling targets and the output target, about 150 MB of GPU memory, plus the image-wide rows target
(`gridWidth x height` RGBA32F, 16 MB at 24 MP). `TiledRenderer.create` halves the tile (down to 256) while the driver
reports `GL_OUT_OF_MEMORY`; the plan is built with the size actually allocated.

**Tested** (`TiledRendererTest`, emulator): tiles of 256 stitch **bit-identically** to one 1024 tile, sRGB and Display
P3, on an 1100x740 RAW-like image with clipped highlights and every Light slider (both pre-passes run); the untiled
export render equals the preview renderer's `renderOutput` (**0 values differ**); the resampled tiled export equals
the CPU reference (`CpuDevelop` + `LanczosResampleOp` + `OutputTransformOp`) within **1 level** (mean 0.05); the
high-depth output quantises to the 8-bit output; cancellation stops between tiles. With geometry (crop + quarter turn +
flip; crop + 7.3 deg; -31.7 deg + quarter turn, unconstrained with empty corners): tiles bit-identical to one tile;
against the preview renderer (which runs the same operator in its output pass) **0 levels** without straightening and
at most **1 level on 10-24 values** with it; against `CpuDevelop.render` at most 2 levels.

## Resampling (size)

`LanczosResampleOp`: separable Lanczos-3 on linear light in the working space, before the output transform (slot 13
of ADR 0002, where the geometry operators will share the same resampling), kernel stretched by the scale (low-pass at
the output Nyquist frequency, no aliasing), taps clamped to the image (a crop reads real pixels beyond its border),
normalised weights. CPU reference with golden tests (flat stays flat, a ramp keeps its slope, a 0.4 cycle/pixel sine
disappears at 4x); GLSL line for line. Lanczos rings by a few percent at hard edges; it is clamped only by the output
transform, like every operator. Exports never upscale.

Sizes (`ExportSize`): original, long edge, megapixels, percentage, and the presets "Full", "Web 2048" (long edge
2048) and "Instagram 4:5" (fit inside 1080x1350). A photo that is not 4:5 is **not** cropped: it fits inside the box
and the sheet says so (the crop tool owns cropping). `geometry.crop` of the recipe is respected (rounded to whole
pixels) through the geometry operator, so the export is the picture the editor shows (cropped, straightened, turned,
flipped); sizes apply to that picture.

## Preview = export

`ExportFlowTest.arwToJpegInPicturesMatchesThePreview` (public ZV-E10 ARW, a Light edit): the editor's proxy (2400 px
from the half-size decode, `ProxyBuilder`) through the preview renderer, against the export at the proxy size (the
export's own Lanczos, JPEG quality 100), in 8-bit sRGB levels:

| Comparison | Mean | p99 | Tolerance (test) |
| --- | --- | --- | --- |
| Per pixel | 2.88 | 14 | mean <= 3.5, p99 <= 16 |
| 8x8 block means (tone and colour) | 0.55 | 4 | mean <= 1.0, p99 <= 6 |
| Full-size export decoded at 1/2 and scaled bilinearly (informative) | 2.99 | 16 | - |

The tone and colour agree to about half a level. The per-pixel residual is fine detail: the editor's proxy is a
2x2-binned half-size decode (no demosaic) averaged down, the export a full AHD demosaic downscaled with Lanczos, so
edges and noise differ. The pipeline itself is not the source: on the same input the export render equals the
preview renderer exactly (above). If the proxy ever switches to a full demosaic, tighten the per-pixel tolerance.

## JPEG encoder: the platform's (`Bitmap.compress`)

Options evaluated: `Bitmap.compress` (Skia, libjpeg-turbo inside the platform), libjpeg-turbo vendored through the
NDK, `ImageWriter`/`HardwareBuffer` paths (the HEIC/JPEG encoders of the camera stack, not available for arbitrary
images on every device).

Measured on the exported files (exiftool on the Mac): `Bitmap.compress` on a bitmap whose `ColorSpace` is sRGB or
Display P3 **embeds the matching ICC profile** ("sRGB", "Display P3 Gamut with sRGB Transfer"), contrary to the
assumption that it lacks ICC control; quality 1-100 maps to libjpeg quality; chroma is 4:2:0. A 24 MP JPEG encodes in
about 0.2 s on the emulator. EXIF and XMP are spliced in afterwards by streaming the encoder's output once
(`JpegSegments`: SOI, EXIF APP1, XMP APP1, the encoder's segments without its JFIF APP0 and APP1, the scan unchanged).

Chosen: the platform encoder. Vendoring libjpeg-turbo would add 4:4:4 chroma at high quality and streaming encode
(no full-size output bitmap: about 96 MB at 24 MP, 244 MB at 61 MP), at the cost of a second native library, its
SIMD build and its licence/maintenance. Revisit if 4:2:0 shows on saturated edges in the owner's photos or if the
output bitmap becomes the memory limit; the encoder is behind `ImageEncoder`, so it is a drop-in change.

## HEIF

`androidx.heifwriter` 1.1.0 `HeifWriter` (bitmap input; the HEIC tile codec when the device has one, else the HEVC
video encoder in 512 px grid tiles), EXIF in its `Exif` item (`addExifData`). **8-bit sRGB in M1**:

- MediaCodec signals colour through `KEY_COLOR_STANDARD` (BT.601, BT.709, BT.2020 only): there is no Display P3, and
  the muxer writes no ICC, so a P3 HEIF would be mislabelled. The sheet offers P3 for JPEG only.
- The only 10-bit path in the platform and in androidx (`AvifWriter`'s high bit depth, HeifWriter's encoder base) is
  BT.2020 PQ, i.e. HDR. 10-bit HEIF therefore belongs with the HDR output transform (Rec.2020 PQ, ADR 0002 slot 14,
  later), not with SDR P3.
- `EncoderSupport.detect()` reports whether an HEVC/HEIC encoder exists (HEIF is hidden otherwise, with a note), the
  HEIC codec, and HEVC Main10 support (kept for that later HDR work). The sheet states "HEIF is saved as 8-bit sRGB".

XMP is not written into HEIF (the writer cannot); the rating goes to EXIF `Rating` (0x4746) in both formats.

## Metadata: a small Kotlin EXIF writer

Options: exiv2 via the NDK (complete, but a large C++ dependency with its own licence, for writing ~30 tags),
`androidx.exifinterface` (writes JPEG and reads HEIF, but cannot write HEIF and rewrites whole files), a small
Kotlin writer. Chosen: **Kotlin** (`ExifWriter`, `XmpPacket`, `JpegSegments`), JVM-tested and checked with exiftool
(`-validate`: no errors, one minor "non-standard tag" warning for `Rating`, which Windows and Lightroom read):

- `ExifSnapshot` (`:core:image`) copies a whitelist of the original's fields (IFD0 camera/author, EXIF exposure, lens
  and capture time, the whole GPS IFD) from JPEG APP1 and TIFF-based RAWs, values normalised to big-endian; CR3/RAF
  fall back to the inspected `PhotoMetadata`; a RAW's paired JPEG fills gaps. Maker notes, thumbnails, offsets into
  the original, serial numbers and the original's orientation and size are never copied.
- Levels: **All** (camera, exposure, lens, location unless "Remove location", author, copyright, rating, software),
  **Copyright only** (author, copyright, software), **None** (nothing). "Keep capture time" (DateTime, DateTimeOriginal,
  Digitized, offsets, sub-seconds) is independent of the level.
- Always written when EXIF is written: orientation 1 (the pixels are upright), pixel dimensions, `ColorSpace` (1 for
  sRGB, uncalibrated for P3, the EXIF convention with an ICC profile), `ExifVersion` 0232, `ComponentsConfiguration`,
  `YCbCrPositioning`. EXIF is ASCII: `©` becomes `(C)`; XMP `dc:rights` / `dc:creator` keep UTF-8.
- Author and copyright come from Settings (`ExportPreferences`); when empty, level All keeps the original's.
- The ICC profile is colour, not metadata: always embedded (JPEG).

## Destinations and file names

- **Gallery**: MediaStore `Pictures/Reveal`, `IS_PENDING = 1` while writing (scoped storage, no permission).
- **Folder**: a SAF tree picked once, its grant persisted and remembered (`ExportPreferences`); a new document per
  export. A removed SD card or revoked grant fails that item with "the destination is unavailable" (tested with a
  missing folder of the debug provider).
- **Share**: the file goes to `cache/share/<slot>/` and is offered through a `FileProvider`
  (`${applicationId}.exports`); the files are deleted when the share sheet returns, and anything left (the app was
  killed meanwhile) at the next start (`RevealApplication`). Tested: the share URI reads through the provider and the
  cache is empty afterwards. An app that keeps reading after the share sheet returned would lose the file: the usual
  receivers (messaging, Instagram, Drive) copy it on receipt.
- Files are encoded into `cache/export/` first and copied to the destination (a half-written destination only exists
  during the copy, and is deleted on failure or resume).
- Names (`ExportNaming`): original name, capture date (`20260807-143012`), or original name + running number
  (`DSC01234-0007`, the number persisted so names do not repeat), plus a suffix (default `-edit`). The destination
  de-duplicates (MediaStore and SAF append " (1)").

## Background batch (WorkManager)

Every export, one photo or many, is a job run by `ExportWorker` (`CoroutineWorker`, foreground with a `dataSync`
notification: progress, Cancel; a summary notification at the end), in a unique APPEND chain so jobs run one at a
time. The job (items: library version ids, or a URI with the editor's recipe JSON; options; first sequence number)
and each item's state live in `files/export-jobs/` (`ExportJobStore`, atomic writes): WorkManager input is capped at
10 KB, and its output is merged into the next chained work's input, so the job id is read from the work's own tag.

Idempotent per photo: an item is `PENDING`, `DONE` (URI, name) or `FAILED` (reason); the destination URI is recorded
before the first byte (`partialUri`). After the process is killed WorkManager runs the work again: done items are
skipped and a partial file is deleted before the item is exported again. Failures are per item with a clear reason:
source gone, unsupported file or camera, destination unavailable, low storage (pre-checked: twice a 2 bytes/pixel
upper bound plus 32 MB, in the cache and for the gallery on shared storage), out of memory, no GPU.

Tested on the emulator: a batch of five cancelled after two (state `CANCELLED`, 2 done, the rest pending, no partial
file); a batch of five whose process was killed after the first item: the second run started at
`[DONE, PENDING x4]` and finished five distinct files.

Where the recipe comes from: library items read the committed recipe through `EditRepository.recipe` (history is
not touched); the editor passes the recipe on screen.

## The sheet

`:feature:export` `ExportSheetHost`: a bottom sheet on phones, a dialog on tablets (window >= 600dp); format, colour
(JPEG), quality, size (with the output size and the 4:5 note), metadata, destination, file name (with an example)
and the **live estimate**: a 768 px proxy developed once on the CPU (`SizeEstimator`), encoded with the sheet's
format, quality and colour on every change (debounced, a few hundred ms), bytes per pixel times the export's pixels,
times 0.6 when the export is more than twice the proxy's pixels (fine detail compresses better at full resolution;
measured on the ZV-E10 sample, 9.1 MB actual). For a selection the estimate is the first photo's, "per photo".
Progress and the result (with each failure's reason) replace the options; the job goes on when the sheet closes.
Wired: Export in the editor's top bar, Export in the library's selection menu; Settings (library overflow) holds the
author and copyright. Options are remembered between exports.

## Measurements (emulator, host GPU Apple M4 Pro via the translator; re-measure on the phone)

| Export | Decode | Render (tiles) | Encode + write | File | Peak native |
| --- | --- | --- | --- | --- | --- |
| ZV-E10 ARW 24 MP -> JPEG q95 full, Light edit | 532 ms | 298 ms (6 + 6 tiles; reconstruction 74, guide 74, main 125, uploads 205) | 198 ms | 9.1 MB | 348 MB |
| same -> HEIF q90 full | 582 ms | 255-323 ms | 1.5-1.9 s | 13.2 MB | - |
| same -> JPEG P3, Web 2048 | 624 ms | 280-446 ms | 20 ms | 0.44 MB | - |
| Sony A7R IV ARW 61 MP (9568x6376) -> JPEG q92 full | 2.8 s | 2.6 s (20 + 20 tiles) | 0.47 s | 9.4 MB | 830 MB |

Peak native memory is LibRaw's full decode (about 240 MB at 24 MP, 600 MB at 61 MP while demosaicing) plus the output
bitmap (96 / 244 MB); the source is closed as soon as the render ends, before encoding. No OOM at 61 MP on the
emulator (192 MB Java heap limit: nothing large is on the Java heap). A 6 GB phone has the room; the remaining risk
is the low-memory killer while the app is in the background with a 60 MP export, which the per-item resume covers.
The numbers on a busy host (other builds running) were 2-4x higher; the phone decides.

## Limits and follow-ups

- HEIF is 8-bit sRGB (above); 10-bit with the HDR output later. AVIF, PNG, 16-bit TIFF, original copy and export
  presets are M5: add an `ExportFormat` entry and its `ImageEncoder`.
- Output sharpening (screen/matte/glossy) is not in M1 (it belongs after the resampling, slot 13).
- JPEG chroma is 4:2:0 (platform encoder).
- The estimate for a selection uses the first photo.
