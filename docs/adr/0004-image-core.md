# ADR 0004: `:core:image` from Hoard's image core, with linear RAW decode

Status: accepted (M0). Date: 2026-11-10.

## Context

Reveal needs RAW files decoded to **linear camera RGB** with every piece of colour metadata the pipeline needs
(ADR 0002), JPEG/HEIF/PNG/WebP decoded and linearised the same way, quick previews, and proxies, all read through
Storage Access Framework descriptors without copying originals. Hoard (same owner) already has a tested image core
(ADR 0006, 0008, 0009 there) with LibRaw vendored. We copy it (no dependency) and extend it.

## Provenance (port from Hoard)

Source: github.com/henriquefps/hoard, commit `85b543a32ba73de39f29b7cbfa01f3a1fb94af55`,
`android/app/src/main/java/dev/hfps/hoard/image/` and `android/app/src/main/cpp/`. Packages renamed to
`dev.hfps.image.*` (module `:core:image`, namespace `dev.hfps.image`).

| Ported | From | Notes |
| --- | --- | --- |
| `source/` | `source/RandomAccessSource.kt`, `DescriptorSource.kt` | SAF/`ParcelFileDescriptor` source kept as is |
| `format/`, `tiff/` | same | classification, `ShotPairing`, `ImageInspector`, `PhotoMetadata` (sub-seconds, lens), TIFF/JPEG walkers, embedded-preview extraction |
| `decode/` | `Decoders.kt`, `Geometry.kt` | orientation maths, `BitmapDecoding`, `ZoomImage` |
| `cache/` | `Caches.kt`, `PriorityScheduler.kt` | generic disk/LRU caches and priority scheduler; not wired yet |
| `sharpness/`, `analysis/` | `Sharpness.kt`, `ShotAnalysis.kt` | kept for the smart presets; `Faces` is only the interface |
| `third_party/LibRaw`, `CMakeLists.txt` | same | LibRaw 0.22.3 unmodified (sha256 in `VENDORED.md`); CMake renamed to `revealimage` |
| tests | `Synthetic.kt`, `ImageInspectorTest`, `ImageCoreLogicTest`, `ShotAnalysisTest` | plan/prefetch tests removed with `ImagePlans` |

Dropped: `SmbFileSource` (Reveal does not speak SMB), `ImageCore.kt` / `ImagePlans.kt` / `CullModel.kt` (culling
facade of Hoard), the ML Kit `FaceDetection.kt` implementation (no ML Kit dependency here; `:core:ml` provides one),
`hoard_image_jni.cpp` and `LibRawDecoder.kt` (8-bit sRGB Bitmap/JPEG output; replaced by the linear path below),
Hoard-specific harness/calibration tests. Edits to ported files: package rename, a file header that relaxes detekt
style rules (they are kept close to the original), `Thread.threadId()` (API 36) replaced by `nanoTime`, obsolete
`SDK_INT >= 26` checks removed (minSdk 29), one dangling KDoc turned into a comment, scheduler thread name.
Licences: `docs/licences.md`.

## Design

### Input: descriptors, never copies

`ImageInput(name, ParcelFileDescriptor)`. Header parsing and previews go through `DescriptorSource` (positional reads
on a `dup`) behind `BlockCachedSource`. LibRaw reads through our own `LibRaw_abstract_datastream` over the descriptor
(`FdStream`: `pread` + a 256 KiB block cache; big reads bypass it; size from `fstat`, `lseek` as fallback), used with
`open_datastream`. Why not `open_file("/proc/self/fd/N")` or `open_buffer`: `/proc` paths depend on the provider's
descriptor being a regular file and LibRaw seeks and rereads freely; `open_buffer` would copy 25 MB. Measured on a
descriptor served by a `ProxyFileDescriptorCallback` (what a DocumentsProvider hands out for non-local storage): the
decode works and is bit-identical to the direct one (harness `decodesFromAProxyDescriptorLikeASafDocument`, 189
callback reads for a half-size decode of a 24 MB ARW). Pipes (non-seekable descriptors) are not supported.

### Linear RAW decode (`raw/`, `src/main/cpp/reveal_image_jni.cpp`)

LibRaw parameters: `use_camera_wb=0`, `use_auto_wb=0`, `user_mul = 1,1,1,1` (overrides every WB source),
`no_auto_bright=1`, `gamm = 1/1`, `output_bps=16`, `output_color=0` (raw camera RGB: `convert_to_rgb` skips the
colour conversion), `highlight=0`, `adjust_maximum_thr=0` (the stored white level is the scale), `user_flip=0`
(orientation is reported, never applied), `med_passes=0`. The processed `imgdata.image` (4 x uint16, black subtracted,
scaled so `maximum - black` maps to 65535, demosaiced) is packed **in place** to RGB16 and handed out.

`LinearImage(width, height, data: ShortArray)`: interleaved RGB, linear, 0..65535, stored orientation. For full-size
images it would be 144 MB: on the emulator (192 MB heap growth limit) it throws `OutOfMemoryError`, and **a Java
`ByteBuffer.allocateDirect` is no escape on Android** (it is a non-movable heap array and counts against the same
limit; the same OOM). So `NativeLinearImage` wraps LibRaw's own buffer with `NewDirectByteBuffer` (zero copy, native
memory, closed explicitly; usable as-is for `glTexImage2D`). Heap `LinearImage` is for proxies. Both are plain Kotlin.

`RawColorInfo` (plain Kotlin, KDoc'd), derived from LibRaw state read after `open` (and the levels after `unpack`):

| Field | Source / definition |
| --- | --- |
| `cameraToXyz` | `XYZ_from_sRGB * rgb_cam * diag(pre_mul)` over the first three channels. LibRaw builds `rgb_cam`/`pre_mul` from the camera's XYZ-to-camera matrix `C` (Adobe `ColorMatrix2`, D65: `cam_xyz`) by row-normalising `C * sRGB_from_XYZ`; the product above is exactly `inverse(C)`, **un-normalised** (verified in `ColorMathTest` by reproducing `cam_xyz_coeff`, and on device: the daylight white maps to D65). Used instead of `color.cam_xyz`, which LibRaw only fills on its table path, not for DNG. `null` when LibRaw has no matrix; 4-colour sensors are not supported |
| `asShotWhiteBalance` | `cam_mul` R,G,B normalised to G = 1; `null` when the file has none |
| `daylightWhiteBalance` | `pre_mul`, normalised to G = 1 |
| `levels.black` | `black + cblack[c]`, per channel (R, G, B, G2) after unpack |
| `levels.white` | LibRaw `maximum` (table or file), the value that becomes 65535 |
| `levels.saturation` / `saturationLinear16` | `linear_max[c]` (else white) in raw units / in the output encoding: where each channel clips, for highlight reconstruction (M1). Data above `white` are clipped to 65535 |
| `levels.dataMaximumAboveBlack`, `bitsPerSample`, `isFinal` | largest sample after black subtraction; bit depth; `false` for the header-only `readInfo` (many cameras only set real levels while unpacking) |
| `baselineExposureEv` | DNG `BaselineExposure` (null otherwise) |
| `orientation` | LibRaw `flip` (dcraw bits) mapped to EXIF 1..8 |
| `sensorWidth/Height`, `activeArea`, `defaultCrop` | raw frame, the area `LinearImage` contains at full size, and the camera's recommended crop relative to it (ARW `DefaultCrop`, DNG) |
| `cfa`, `cfaPattern`, `isDng`, `iso`, make/model (+ LibRaw-normalised), `libRawVersion` | as named |
| `lensCorrection` | Sony only, see below |

**Sony lens correction.** LibRaw 0.22.3 does not expose it, so `SonyLensCorrectionReader` parses it: IFD0 `0xC634` ->
SR2 private IFD -> `0x7200/0x7201/0x7221` (offset, length, key) -> sub-IFD encrypted with dcraw's `sony_decrypt`
keystream (symmetric; the cipher starts at position 127, verified on a real ZV-E10 ARW) -> a bare IFD with absolute
offsets holding `SSHORT` blocks `0x797D` (vignetting, 17), `0x7980` (chromatic aberration, 33) and `0x7982` (distortion,
17): knot count first, then the knots, zero padded. Exposed exactly as stored; interpreting them is M2.

Options (`RawDecodeOptions`): `halfSize` (2x2 binning, ~4x faster) and `demosaic`. Cancellation and progress: Hoard's
listener pattern (`RawProgressListener`, native callback returns non-zero to cancel; `CancelToken`). One decode at a
time (a semaphore). The native library is `librevealimage.so` (arm64-v8a, x86_64, 16 KB aligned); on other ABIs
`RawDecoder.isAvailable` is false.

### Standard formats (`standard/`)

`StandardDecoder` (BitmapFactory / BitmapRegionDecoder on the descriptor): decoded as `ARGB_8888` **in the file's own
colour space** (the source space is passed back as `inPreferredColorSpace`, so no gamut conversion happens), then
linearised through a 256-entry table built from the space's EOTF (`ColorSpace.Rgb.eotf`, so sRGB, Display P3, Adobe RGB
and ICC-based spaces all work and 8-bit sources lose nothing) into the same 16-bit linear encoding. `StandardColorInfo`
carries the primaries' RGB-to-XYZ matrix adapted to D65 (`ColorMath`: Bradford, from the space's chromaticities).
Region and power-of-two subsample decode are supported. Limits: 10-bit/HDR sources decode at 8 bits; EXIF orientation is
not applied by the platform and is reported only where the container exposes it (JPEG via `ImageInspector`).

### Previews and proxies

`FirstImageLoader.load(raw, rendered, targetLongEdge)`: the embedded RAW preview when it covers 75% of the target, else
the paired camera JPEG (`ShotPairing`), as an upright display bitmap. `ProxyBuilder.decodeProxy(input, longEdge)`:
RAW -> half-size decode when it still covers the request, else a full decode (in native memory), then an exact
area-average downscale in linear light (a native implementation, ~8x faster than Kotlin over a `ShortBuffer`, checked
against the Kotlin reference within 1 code); other formats -> platform subsampling + the same resample. It returns
`ProxyStats` (source, sizes, decode/resample/total ms).

### Mapping onto `PipelineInput` (ADR 0002; done in the integration step)

| `PipelineInput` | from |
| --- | --- |
| `kind` | RAW: `SCENE_REFERRED`; JPEG/HEIF/PNG/WebP: `DISPLAY_REFERRED` |
| `linearRgb` | `sample / 65535f` (RAW: black subtracted, white level = 1.0, no WB/matrix/gamma; standard: linear in the file's space) |
| `cameraToXyz` | `RawColorInfo.cameraToXyz` (null -> unsupported camera) / `StandardColorInfo.toXyz` (`ColorInfo.toXyz` for both) |
| `asShotMultipliers` | `asShotWhiteBalance` (R, G, B) for RAW, else null; fall back to `daylightWhiteBalance` when the file has none |
| not in `PipelineInput` yet | `baselineExposureEv` (DNG, apply as exposure), `levels.saturationLinear16` (highlight reconstruction), `orientation`, `activeArea`/`defaultCrop`, `lensCorrection` |

## Measurements

Emulator `reveal-image` (API 36.1, arm64 virtualised on the Mac mini, same class of CPU as Hoard's measurements), warm
medians of 3 runs, 24 MP ZV-E10 ARW (6024x4024 active). "Owner" = four private ZV-E10 ARWs run on copies, aggregates
only (copies deleted). To be re-measured on the phone by the orchestrator.

| Step | Public ZV-E10 sample | Owner ZV-E10 (4 files) |
| --- | --- | --- |
| Header read (`readInfo`) | < 1 ms (128 KiB, one read) | < 1 ms |
| Embedded preview 1616x1080 / 1920x1080 -> bitmap | 8 ms, 430 KB read of 25 MB | 9-14 ms, 368-420 KB |
| Half-size linear decode (3012x2012) | 183 ms | 120-130 ms |
| Full linear decode, AHD | 652 ms (unpack 166, process 458) | 526-537 ms |
| Proxy 1600 px | 523 ms before native resample; now ~125 ms | 121-126 ms (decode 111-116, resample 9) |
| Proxy 3000 px (half decode + resample) | 141 ms | 136-142 ms |
| Peak native memory, full AHD | 239 MB | 239 MB |

Demosaic algorithms, full size, median of 3 (rmse = error of the 2x2-averaged result against the half-size binned
decode, 16-bit units; detail = mean |Laplacian| of green; fringing = high-pass of R-G; lower fringing is better, the
metrics are coarse):

| Algorithm | ZV-E10 time | Peak native | rmse | detail | fringing |
| --- | --- | --- | --- | --- | --- |
| PPG | 252-324 ms | 233 MB | 85-134 | 259-473 | 25-45 |
| **AHD** | 520-595 ms | 239 MB | 88-139 | 262-479 | 20-38 |
| DCB | 1.3-1.4 s | 787 MB | 80-126 | 204-371 | 9-16 |
| DHT | 2.3-2.4 s | 534 MB | 78-125 | 312-572 | 53-101 |
| AAHD | 2.5-2.7 s | 1137 MB | 96-143 | 277-510 | 27-48 |

**Decision: AHD is the default** (`Demosaic.DEFAULT`): the best balance of time, memory and artefacts; PPG is the
option for interactive previews of full-size data (2x faster, slightly more fringing). DCB has the least colour fringing
but costs 2.4x the time and 3.3x the memory; DHT keeps the most detail but fringes most; AAHD's 1.1 GB peak is unsafe on
a phone. Re-evaluate with AMaZE-class demosaicing if a GPL-free implementation becomes available.

Java heap: a full-size `ShortArray` (145 MB) fails on the emulator's 192 MB heap limit, hence `NativeLinearImage`.

Other formats on the emulator: sRGB JPEG 256x192 decodes in a few ms, HEIC in ~180 ms (first call); the grey ramp
matches the sRGB curve within JPEG error, and the Display P3 JPEG reports the P3 primaries.

Public samples decoded (all pass the harness): Sony ZV-E10 ARW, Google Pixel 4a DNG, Nikon D5600 NEF, Canon EOS R6 CR3.

## Testing

JVM: the ported suites plus `ColorMathTest`, `RawInfoMapperTest`, `LinearImageTest` (resample, native image, linearise),
`SonyLensCorrectionReaderTest` (encrypted synthetic ARW). Device harness (`core/image/src/androidTest`): `RawHarnessTest`
(info, levels, matrices, full/half decode, SAF-like descriptor), `StandardHarnessTest`, `ProxyHarnessTest`,
`DemosaicHarnessTest`, `MeasurementsHarnessTest`; commands in `CLAUDE.md`. Samples are fetched by
`scripts/fetch-samples.sh` (checksums) and never committed.

## Consequences

- One decode at a time and ~240 MB of native memory per full-size image alive: callers close `NativeLinearImage` promptly.
- The camera matrix comes from LibRaw's tables (DCP-derived for non-DNG); cameras LibRaw does not know have no matrix.
- Lens-correction data are extracted, not applied (M2). Hoard can adopt this module later; the package is shared on purpose.
