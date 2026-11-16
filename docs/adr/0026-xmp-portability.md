# ADR 0026: XMP portability

Status: accepted (M5/X). Date: 2026-11-14. Amends ADR 0006 "XMP" (the `reveal:` seam is now used; sidecars carry the
edit, not only culling) and ADR 0011 (hand-off sidecars). Builds on ADR 0003 (the recipe JSON), ADR 0018 (conflict
copies and their chooser) and Hoard's ADR 0011 (culling sidecars, the same merge writer). No database change (the
state lives in the sidecar, in `recipes.updated_at` and in one `meta` key); `PIPELINE_VERSION` and `RECIPE_VERSION`
are unchanged.

Code: `:core:data` `xmp/XmpDevelop.kt` (the `reveal:` block, `SidecarRecipe`), `xmp/CameraRaw.kt` (the `crs:`
table), `xmp/CameraRawGeometry.kt` (crop, angle, orientation), `xmp/XmpPreferences.kt` (Settings),
`library/SidecarSync.kt` (writes), `library/EditSidecars.kt` (the debounced writer), `library/SidecarEdits.kt`
(reading back), `index/LibraryIndexer.kt` (calls it), `HistoryAction.XMP_IMPORT` in `:core:pipeline`.

## Context

The product's principle 6 ("Interoperable") and the plan's architecture ask that a photo's sidecar is a portable copy
of its edit: another Reveal without sync (a second device on the same SD card, a folder Hoard copied), Lightroom and
darktable should see what they can of it, and Lightroom's basic edits should come into Reveal. Until now the sidecar
carried only rating, label and pick (ADR 0006) and reserved the `reveal:` namespace.

## Decision

### What is written

The master version's edit (position 0; virtual copies stay in the database: a sidecar is one edit, and every other app
reads one), merged into the existing sidecar with the ADR 0006 writer (unknown namespaces, other apps' `crs:` fields,
darktable's history, `xpacket`, comments and element order kept; attribute layout normalised):

| Where | What |
| --- | --- |
| `reveal:Schema` | layout version, `1`; a sidecar with a higher one is never rewritten (only the culling fields) |
| `reveal:RecipeVersion` | the recipe's `v` (ADR 0003); higher than this app's = newer app, left alone |
| `reveal:Recipe` (element) | the ADR 0003 JSON **without members equal to the default recipe's** (`{"v":1,"light":{"exposure":0.5}}`); arrays (masks, curve points) are kept whole so polymorphic `type`s stay. Read through `RecipeCodec` (migrations; newer refused) |
| `reveal:RecipeDeflate` | instead of `reveal:Recipe` above 16 K characters (long brush strokes): Base64 of deflated JSON; read back at most 4 MiB inflated |
| `reveal:Edited` | when the recipe last changed (`recipes.updated_at`), ISO 8601 UTC |
| `reveal:Device` | the writing device's model (`Build.MODEL`, never the user's device name: sidecars travel) |
| `reveal:Ancestors` | digests of the history steps before the current one, oldest first, at most 32 |
| `crs:*` | the Camera Raw overlap below, `crs:ProcessVersion="11.0"`, `crs:HasSettings="True"` |
| `tiff:Orientation` | the picture as seen: the photo's EXIF orientation, then Reveal's quarter turns and flips |

The **digest** of a recipe is the SHA-256 of its compact JSON, first 16 hex characters: stable when a later app adds
fields with defaults, the same for a recipe decoded from the full or the compact form.

When: while Settings → "Write edits to XMP sidecars" is on (default on). Every change of a master version's recipe
(editor commits, undo, paste, presets, a sync pull, an import) moves `recipes.updated_at`; `EditSidecars` observes its
maximum, waits 2 s after the last change (a slider burst is one write) and writes, on the data scope (never the main
thread), every master version changed since `meta` `xmp.editsWritten`. Edits made while the setting was off are
written when it is turned on; the first run after this release writes the edits made before it. Ratings and flags are
written as before (ADR 0006), and they carry the edit too while the setting is on.

Where: available, writable folders (ADR 0006). Hand-off photos only through a sidecar their sender granted (ADR 0011;
Reveal cannot create documents there); Server photos never. An unedited photo (never edited, or reset) gets no new
sidecar; one that already has a `reveal:` block gets it updated to the reset state. A malformed sidecar is never
overwritten (ADR 0006).

Turning the setting off leaves existing sidecars as they are; their `reveal:` block is then older than the photo and
is recognised as such when read (below).

### Camera Raw: the overlap and its limits

Reveal's controls were modelled on Lightroom's (process version 2012), so most fields are the same slider with the
same range and are written 1:1. The **pixels differ** (Reveal's operators, profiles and colour space are its own), so
every value is approximate: the same intent, not the same rendering. Values use Lightroom's text forms (`"+0.70"`,
`"-40"`, `"+1.0"`) and are compared by value, never by text.

| Reveal | Camera Raw | Notes |
| --- | --- | --- |
| exposure (EV) | `Exposure2012` | ±5, two decimals |
| contrast, highlights, shadows, whites, blacks | `Contrast2012`, `Highlights2012`, `Shadows2012`, `Whites2012`, `Blacks2012` | ±100 |
| texture, clarity, dehaze | `Texture`, `Clarity2012`, `Dehaze` | ±100 |
| vibrance, saturation | `Vibrance`, `Saturation` | ±100 |
| WB mode | `WhiteBalance` | As Shot, Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Flash, Custom |
| WB (RAW) | `Temperature` K, `Tint` | absolute; As Shot writes neither (Reveal does not know the camera's kelvin) |
| WB (JPEG etc.) | `Temperature`, `Tint` | Lightroom's relative -100..+100, same as Reveal's `relTemperature/relTint` |
| sharpening amount, radius, detail, masking | `Sharpness`, `SharpenRadius`, `SharpenDetail`, `SharpenEdgeMasking` | Lightroom's RAW default is 40, Reveal's 0 |
| luminance NR, detail, contrast | `LuminanceSmoothing`, `LuminanceNoiseReductionDetail`, `LuminanceNoiseReductionContrast` | |
| colour NR, detail, smoothness | `ColorNoiseReduction`, `ColorNoiseReductionDetail`, `ColorNoiseReductionSmoothness` | Lightroom's RAW default is 25, Reveal's 0 |
| post-crop vignette | `PostCropVignetteAmount/Midpoint/Roundness/Feather/HighlightContrast`, `PostCropVignetteStyle=1` | Reveal's vignette is "highlight priority"-like |
| grain amount, size, roughness | `GrainAmount`, `GrainSize`, `GrainFrequency` | |
| lens corrections, distortion %, vignetting % | `LensProfileEnable`, `LensProfileDistortionScale`, `LensProfileVignettingScale` | each app uses its own profile |
| remove CA | `AutoLateralCA` | |
| defringe | `DefringePurpleAmount/HueLo/HueHi`, `DefringeGreenAmount/HueLo/HueHi` | same ranges (0..20, 0..100) |
| transform | `PerspectiveVertical/Horizontal/Rotate/Scale/Aspect/X/Y`, `PerspectiveUpright` 0..5 | Upright is re-solved by each app |
| parametric curve | `ParametricShadows/Darks/Lights/Highlights`, `Parametric*Split` | Reveal applies its curve in its own space |
| point curves | `ToneCurvePV2012`, `…Red`, `…Green`, `…Blue` (`rdf:Seq` of `"x, y"` 0..255) | straight = `0, 0` `255, 255` |
| colour mixer | `HueAdjustment*`, `SaturationAdjustment*`, `LuminanceAdjustment*` (8 bands) | |
| black & white | `ConvertToGrayscale`, `GrayMixer*` | |
| colour grading | `SplitToningShadowHue/Saturation`, `SplitToningHighlightHue/Saturation`, `ColorGrade{Shadow,Midtone,Highlight,Global}…`, `ColorGradeBlending`, `SplitToningBalance` | Lightroom keeps shadow/highlight hue and saturation in its split-toning fields |

Not mapped (Reveal-only or no clean equivalent): profiles and their amount (`CameraProfile` is left as it was), masks
and local adjustments, heals, the guided Upright's guide lines, highlight reconstruction. They travel in `reveal:` only.

**Crop and orientation** (`CameraRawGeometry`): Camera Raw stores the two opposite corners of the crop as points of
the stored (un-oriented, un-straightened) image, 0..1, plus `CropAngle`; the rectangle is axis-aligned once the image
turns by the angle about the crop's centre (darktable's `lightroom.c` reads it so). Reveal's crop is centre + size in
the oriented image with the angle about the centre. The conversion undoes the EXIF orientation (a dihedral matrix on
centred coordinates; a mirror flips the angle's sign) and turns the half-diagonal by the angle, in pixels: a
straightened crop needs the photo's size and is neither written nor read without it. Assumed and tested only against
documented behaviour: the sign of `CropAngle` (positive = clockwise as seen, Reveal's sign). A crop over a transform
is approximate (each app has its own transform plane). `tiff:Orientation` composes the photo's EXIF orientation with
Reveal's quarter turns (`F * Q^rotate`, `GeometryMapping`); read back, any of the eight is a turn plus at most a
horizontal flip.

**darktable** (its `src/develop/lightroom.c`, read for this ADR): the "Lightroom" import of its history stack reads a
`<stem>.xmp` sidecar's crop, `CropAngle`, `tiff:Orientation`, `Exposure2012`, `Blacks2012`, `Clarity2012`, the
post-crop vignette, `GrainAmount/GrainFrequency`, the parametric and `ToneCurvePV2012` curves, the HSL bands, split
toning, `xmp:Rating` and `xmp:Label`, and refuses a file whose `stEvt:softwareAgent` exists and is neither Lightroom nor
Camera Raw (Reveal writes none, so it is accepted). It ignores the other 2012 sliders, white balance, vibrance and
saturation. Reveal never writes darktable's own history; darktable's own sidecar is `<file name>.xmp`, which Reveal
reads for ratings and leaves untouched. **Lightroom** reads every field above from `<stem>.xmp` next to a RAW (Lightroom
ignores a sidecar next to a JPEG: those carry the edit for Reveal and darktable only).

### Reading (`SidecarEdits`)

A sidecar is read when it is new or its mtime changed (ADR 0006: Reveal's own writes are not read back), after the
headers of the walk (the crop needs the orientation and size), while Settings → "Apply edits from XMP sidecars" is on
(default on). The rules are culling's and sync's: the newer edit wins and nothing is lost.

1. **Incoming recipe.** With a `reveal:` block: its recipe, plus every `crs:` group another app changed since (a field
   differing from what Reveal writes for that recipe: Lightroom edited a Reveal sidecar); a group (an amount with its
   shaping sliders, the white balance, the vignette…) comes in whole. Without one (Lightroom, Lightroom mobile): the
   photo's recipe plus the `crs:` groups that differ both from what Reveal would write and from **Lightroom's own
   defaults** (Lightroom writes every field when it saves metadata; rating a photo there must not import "sharpening
   40" or "colour noise 25"), the crop, and the orientation if it differs from the photo's.
2. **Same recipe:** nothing (Hoard rating a Reveal sidecar keeps the edit).
3. **Fast-forward** (the photo never edited, its recipe is in the sidecar's ancestors, or it is the sidecar's base;
   a Camera Raw-only sidecar always): the incoming recipe becomes one history step "Imported from XMP"
   (`HistoryAction.XMP_IMPORT`; undo restores the previous edit). An open editor reloads it (`RemoteEdits`); sync
   pushes it like any edit. **Decided: applied, not offered**: it is one undoable, labelled step on the photo, the
   sidecar is the user's own file, and an offer would need a review queue for every photo of a walk.
4. **Sidecar behind** (the sidecar's recipe is in this photo's history): the photo keeps its edit and the sidecar is
   rewritten (`IndexReport.sidecarsBehind`); a Camera Raw change on top still comes in.
5. **Diverged:** the newer side (`reveal:Edited` against `recipes.updated_at`) becomes the master version, the other
   a conflict copy (`versions.copy_of`, ADR 0018 FIX/W) named after the sidecar's `reveal:Device` (else the sidecar's
   file name) or "this device", which the conflict chooser resolves like a sync conflict.

A newer app's block (schema or recipe version) is left alone: no import, no overwrite. Malformed parts (bad JSON, a
recipe that fails validation, bad Base64, an inflation over 4 MiB, an unparseable date, wrong digests) read as absent;
a sidecar that is not XML is never read nor written (DTDs and external entities refused, ADR 0006).

### Settings

Settings → "XMP sidecars": "Write edits to XMP sidecars" and "Apply edits from XMP sidecars", both on by default,
English and pt-BR (`XmpPreferences`, SharedPreferences `xmp`). Culling always travels.

## Measurements

`XmpMeasurementsTest` (instrumented, the debug SAF provider in its own process, 1,000 RAW+JPEG pairs; `REVEAL_XMP`),
x86_64 emulator (phone screen, SwiftShader; the home Linux machine), 2026-11-14:

| Step | Total | Per photo |
| --- | --- | --- |
| an edit on every photo -> 1,000 new sidecars | 18.2 s | 18.2 ms |
| a second edit merged into the 1,000 sidecars | 13.8 s | 13.8 ms |
| a new library indexing the folder: 1,000 sidecars read, 1,000 edits imported | 17.2 s (sidecar phase) | 17.2 ms |

Sidecar size: 4.5 KB (crop + exposure + contrast + vibrance edit). The cost is the provider's round trips (stat,
read, create, write, stat across processes) more than the XML; it is paid in the background, 2 s after the last
change, so an edit in the editor costs one ~15 ms write off the main thread, and a 1,000-photo batch preset about
15-20 s of background writes. Not measured on the phone (no GPU involved; the orchestrator can run the command in
CLAUDE.md "XMP sidecars" there).

## Consequences

- A folder edited in Reveal opens with its edits in another Reveal without sync, in Lightroom (basic values) and in
  darktable's Lightroom import; Lightroom's basic edits come into Reveal as a step.
- Sidecars grow by about 3-5 KB for a typical edit (the Camera Raw fields plus the compact recipe).
- Every edit costs one sidecar write per photo, 2 s after the last change, off the main thread.
- The overlap is approximate by nature; the ADR's table is the contract, not pixel parity.

## Tests

`CameraRawTest` (every mapped field round-trips, Lightroom's text forms, relative JPEG white balance, as-shot, clamping
and malformed values, crop under all eight orientations with straightening, orientation composition),
`XmpDevelopTest` (Reveal → XMP → Reveal identical incl. masks, compact JSON and digests, deflate, merging into
Lightroom Classic / darktable / Hoard samples keeps their fields, newer blocks untouched, hostile blocks: bad JSON,
invalid recipe, bad Base64, a 64 MiB deflate bomb, DOCTYPE), `SidecarEditsTest` (indexer + history + writer on files:
Lightroom Classic and mobile imports, Lightroom's defaults ignored, write then no read-back, the settings, reset,
fast-forward, sidecar behind, both conflict directions, Lightroom editing a Reveal sidecar, Hoard rating it, newer app,
malformed sidecar, a second library reading the first's sidecars), `XmpSidecarFlowTest` (instrumented over SAF).
Fixtures in `core/data/src/test/resources/xmp/` are hand-written in each app's layout (no real or owner files).

## Amendment R0.8 (docs/adr/0027)

An edit-only write no longer touches `xmp:Rating`, `digiKam:PickLabel` or `xmp:Label`, and a sidecar it creates has
none of them; a sidecar import applies only the culling fields that changed in the file since it was last read.

## Amendment W4: automatic writes removed, Export XMP (0.9.0)

Reveal no longer writes sidecars on its own, anywhere: not for an edit (`EditSidecars` is gone, and with it `meta`
`xmp.editsWritten`), not for a rating, flag or label change (library, viewer, editor, hand-off culling, merges), not to
catch a sidecar up (`IndexReport.sidecarsBehind` is informational), not into a hand-off's writable `.xmp` item (those
are read for import only). Culling and edits travel between apps through hoard-server (ADR 0027, edited previews).
Reading and importing sidecars is unchanged.

- Settings: the "Write edits to XMP sidecars" switch is gone and its stored `writeEdits` value is removed on first
  run; "Apply edits from XMP sidecars" stays, with a note that sidecars are written only by Export XMP.
- Grants: `addSource` and hand-offs take a persistable READ grant only. Grants that already include write are kept
  (they cannot be taken again once released) and serve Export XMP's "Next to the photos". New folders therefore have
  `sources.writable = false` unless the provider needs no grant.
- **Export XMP** (`XmpExport`, `XmpExportWorker`, `XmpExportSheet`) is the only caller of `SidecarSync.write` /
  `writeInto`. Entry points: the library selection menu, a folder's ⋯ in Manage folders (the whole folder) and the top
  bar ⋯ ("Export XMP for these N photos": the current filter's results). A sheet asks where:
  - **Next to the photos** (`<stem>.xmp`, merged into the existing sidecar): offered when every photo is a folder file
    of an available source Reveal holds a write grant for. When exactly one folder lacks the grant the sheet offers to
    pick that folder with `ACTION_OPEN_DOCUMENT_TREE` for a one-off write (`XmpDestination.NextToPhotos.oneOff`).
    The picked tree must be, or contain, that folder (`XmpGrants.covers`: same provider, the source's root document at
    or below the picked one, else `DocumentsContract.isChildDocument`); otherwise nothing starts and the user is told.
    Server-only photos and hand-offs never get "next to".
  - **Choose a folder…**: flat `<stem>.xmp` files in a folder the user picks, for any photo. The photo's own sidecar
    (read only) is the merge base (a malformed one is left alone and the chosen folder gets a fresh sidecar); a taken
    name is de-duplicated by the provider, as in the image export, and the file already there is never touched.
  Content and merge rules are what the automatic writer had: the culling fields and the master version's edit
  (`reveal:` block + Camera Raw overlap), only fields Reveal knows, never a newer app's `reveal:` block, never a
  malformed sidecar overwritten. It runs as a WorkManager job (notification with Cancel, job file
  `files/xmp-jobs/<id>.json`); the result names `SidecarOutcome(written, databaseOnly = unwritable, malformed)`.
- Grants of picked folders (`XmpGrants`): both pickers take a persisted READ|WRITE grant when the job is queued, so a
  job resumed after a kill or a reboot still writes; the grant is released when the job ends (done, cancelled by the
  user, failed) unless another job, a library folder (which keeps its READ, and its WRITE if it had one), the image
  export's remembered folder or an export preset still uses that tree.
- Resuming: the job's log (`<id>.log`, append-only) records each photo (`d <photo> <w|u|m>`) and, for a chosen folder,
  the document created for it (`c <photo> <document>`, written before the document's bytes). A run stopped by the
  system (`stopReason` other than `STOP_REASON_CANCELLED_BY_APP`) keeps the job and WorkManager runs it again: it skips
  the photos done and overwrites the documents it created instead of making `(1)` copies; next to the photos the photo
  records its new sidecar (`photos.xmp_document_id`) before writing it, and an empty document (a write interrupted
  after the create) is no merge base. Only the user's Cancel ends a job early. `PresetBatchWorker` keeps its job on a
  system stop the same way.
- Queue (fix round 2): each job is its own unique work (`xmp-export-<job>`, tags `xmp-export` + `xmp-job:<job>`,
  `ExistingWorkPolicy.KEEP`), never a chain: with `APPEND_OR_REPLACE` a Cancel or failure of the running job cancelled
  or failed the queued ones without running them, so their grants and job files were never released. Jobs run one at a
  time through a process-wide lock in the worker. Enqueue (grants taken, job file, work), a job's end and the sweep
  share one lock. The sweep (`XmpExportWorker.sweep`, at app start and at each job's end) deletes the files of jobs
  with no unfinished work (cancelled or failed while queued, a kill between the files and the work) and releases their
  trees unless a live job uses them.
- A sidecar document created by a write that then fails or is cancelled (next to the photos or into a chosen folder)
  is deleted again (`DocumentTree.delete`) and the photo's sidecar record restored; only a kill leaves the empty
  document, which the next run fills.
- `sources.writable` is true only when a WRITE grant really is persisted for the tree, or for the debug build's
  synthetic provider (named explicitly, `LibraryRepository.grantFreeAuthorities`).
- Tests: `XmpExportTest` (no write through the library's rating/flag, an edit, a hand-off's label, folder-first and
  merge paths, recorded at the tree; next to, chosen folder, a taken name, malformed kept / fresh copy, reruns without
  copies, server-only and hand-off refused for "next to", the one-off tree, preference removal), `XmpGrantsTest`,
  `XmpExportJobsTest`, `XmpExportQueueTest` (cancel keeps the queue, orphan sweep), `XmpExportPickerTest` (rotation while picking), `ReadOnlyGrantsTest`, `LibraryIndexerTest`
  (first read), `XmpSidecarFlowTest` (instrumented).

### First read of a sidecar (shared with Hoard app 2.2.1 and hoard-server 0.10.1)

Since nothing writes sidecars automatically, another app may create one later for a photo Reveal already rated
(darktable writes `xmp:Rating="1"` by default). The rule, the same in Reveal, Hoard's import and hoard-server's NAS
import:

- First read: the sidecar was never read at that path, or its record was dropped because the file disappeared. On a
  first read the reader fills only the fields it holds NO value for.
- A field "holds a value" when it is non-default: rating != 0, flag != none, label non-empty, no matter who set it. An
  explicit 0 stars counts as NO value.
- Fields that hold a value are kept, whatever the sidecar's mtime.
- The reader records what it read: path, size, mtime and values. Later reads of the same sidecar import only the
  fields that differ from the last read (newest by mtime, LWW).
- Reveal's own Export XMP records what it wrote as read (`sidecar_culling`), so a file it wrote is never a first read.
- A sidecar missing on a scan forgets its record.

In Reveal: `SidecarImport.apply` (`SidecarImport.held`), the record is `photos.xmp_document_id` + `xmp_modified` (the
walk re-reads a sidecar whose mtime or document changed; the size is not stored) and `sidecar_culling`; the walk
deletes the `sidecar_culling` row of a sidecar that is gone. Tests: `LibraryIndexerTest` (0 stars + sidecar 3 -> 3,
rating 5 + sidecar 1 -> 5, flag none + reject -> reject, export then rescan -> no import, removed then re-created ->
first read again).

