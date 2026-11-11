# ADR 0006: Library data model, indexing, XMP and thumbnails

Status: accepted (M1, library). Date: 2026-11-11. Amended 2026-11-11: file identity is a content fingerprint
("Identity", DB version 4).

## Context

M1 replaces the M0 start screen with a library (`docs/plan/product.md` "Library", `ux.md` "Library and other
screens"): folders added through the Storage Access Framework (phone, SD card, USB drive), indexed fast, shown as a
grid with RAW+JPEG pairs collapsed and ratings/flags read from XMP (Hoard's culling), a full-screen viewer and "Edit".
The data layer also has to hold recipes and history for the editor's undo/redo, and leave seams for virtual copies
(M5) and the recipe-in-sidecar (M5).

## Schema (Room, `RevealDatabase` version 2)

Version 1 (M0) had only `meta`. Version 2 adds tables only, so it is an **auto-migration** (`AutoMigration(1, 2)`),
checked by `RevealDatabaseMigrationTest` (Robolectric + `MigrationTestHelper` over the exported schemas, which are
test assets of both the JVM and the device tests).

| Table | Row | Key columns |
| --- | --- | --- |
| `sources` | an added folder: SAF tree URI (unique), root document id, display name, `volume_id` (`primary`, an SD/USB uuid, or null for other providers), `volume_name`, `state` (`AVAILABLE` / `UNPLUGGED` / `PERMISSION_LOST`), `writable`, `added_at`, `indexed_at` | |
| `photos` | a **shot**: RAW, rendering, or RAW+JPEG pair (one row) | unique `(source_id, folder, stem_key)`; `sort_time`, `capture_time` (EXIF, camera clock as UTC ms incl. sub-seconds), `capture_offset`, `width`/`height`/`orientation`, `format` (`RAW_PAIR`, `RAW`, `JPEG`, `HEIF`, `PNG`, `WEBP`), `has_raw`, `rating` 0..5, `label`, `flag` (`NONE`/`PICK`/`REJECT`), `has_edit`, `metadata_state`, `xmp_document_id` + `xmp_modified`, `last_opened_at` (Recent), `hidden` ("remove from library") |
| `photo_files` | one file of a photo, at a place (source + relative path) and with a **content fingerprint** (see "Identity") | unique `(source_id, relative_path)`; `document_id`, `role` (`RAW`/`RENDERED`/`OTHER`), `size`, `modified`, `fingerprint` (indexed, null until computed) |
| `versions` | an independent edit of a photo; M1 creates exactly one per photo (position 0, `name` null) with the photo, M5 adds virtual copies | unique `(photo_id, position)` |
| `recipes` | the committed recipe of a version: ADR 0003 JSON (`v` inside), `is_default`, `updated_at`. No row = the default recipe | PK `version_id` |
| `history` | one committed step of a version (see "History") | unique `(version_id, position)` |
| `history_cursor` | where undo/redo stands per version | PK `version_id` |
| `cache_entries` | index of disk caches (thumbnails now; proxies/previews later): key, kind, file name, bytes, `last_access` | indexed `last_access` |

Every child cascades on delete (source → photos → files/versions → recipes/history/cursor/cache rows). Enums are
stored by name. `photos.has_edit` and `sort_time` are denormalised for the grid's filters and order (indexed
`(source_id, sort_time)`, `sort_time`, `last_opened_at`).

**Why a photo and its files are two tables:** a pair has two files with two sizes and mtimes; the photo is the unit
the user rates, edits and sees, the file is the unit the walk compares. A photo's key is its folder and lower-case
stem, the pairing rule of Hoard ADR 0006 (`ShotPairing`, ported in `:core:image`).

## Identity (DB version 4)

A file is recognised by **what it contains**, not by its name or where it is: `photo_files.fingerprint` =
`v1:` + hex SHA-256 over the file size (8 bytes, big-endian), the first 1 MiB and the last 1 MiB (the whole file
after its size when it is at most 2 MiB). `FileFingerprint` (`:core:image/source`) computes it from any
`RandomAccessSource` with at most 2 MiB of positional reads, never the whole of a 25-60 MB RAW. It is unique in
practice: a camera file's head carries the EXIF capture time with sub-seconds, the body serial number and the
shutter count, and its tail the end of the image data. **Known limit:** two files of the same size that differ only
more than 1 MiB from both ends share a fingerprint (cameras do not produce them; a tool rewriting pixels in place
without touching the header could); the `v1:` prefix lets a later scheme live beside stored values. Version 4 adds
the nullable, indexed column (`AutoMigration(3, 4)`; existing rows stay null until filled).

- **Hand-offs** (ADR 0011) find their photo by fingerprint: the photo that already holds the main file's content
  (else another file of the shot) is reused with its edits and history, in any source. The same photo opened from
  the gallery, a file manager or an SMB app (other URI, even another name) is one photo; two different files named
  `DSC00001.JPG` (a counter reset, two cards) are two. A file replaced under the same URI has another fingerprint:
  it leaves its old photo and becomes a new one, never inheriting the old recipe (the old photo, if it had no other
  file, is removed: its picture no longer exists). A match in an added folder (available, not removed from the
  library) opens that folder photo as it is. A row without a fingerprint (filed before version 4, or unreadable)
  still matches by URI + size + mtime, the previous rule. A known URI with the same size and mtime reuses its stored
  fingerprint instead of reading the file again.
- **Folders** keep pairing and diffing by path (the walk compares size and mtime, see "Indexing"); their
  fingerprints are filled **in the background after each walk** (`LibraryIndexer.fillFingerprints`, 2 files at a
  time, cancellable, its own job so the library is "indexed" without waiting for it; a changed file's fingerprint is
  cleared and computed again; a write checks size and mtime so it never stores a stale value). Matching library
  photos across folders by fingerprint (a folder moved or renamed keeping its edits, duplicates) is **not done yet**;
  it is the prerequisite for sync (M5+), where the fingerprint is the photo's key on the server.

**"Remove from library"** sets `hidden`: the row stays (so a rescan does not bring the photo back) and the file is
never touched. Removing a folder deletes its rows (edits included, after a confirmation) and releases the grant.

## History (for the editor's undo/redo)

The history engine is pure Kotlin (`EditHistory` in `:core:pipeline/recipe`, ADR 0003 "History engine"); this
section is its persistence. `EditRepository` (in `:core:data`):

- `load(versionId)` rebuilds the editor's `EditHistory` (every step's snapshot, its label and the cursor) when a
  photo is opened; the editor then works on it in memory.
- `apply(ops)` writes the editor's changes in order, in **one transaction per batch**: `HistoryOp.Commit` (a step
  after the cursor, redo steps above it dropped, the recipe stored as the version's recipe, cursor moved, `has_edit`
  updated) and `HistoryOp.Move` (undo, redo, a jump in the history list; -1 = the base, the default recipe).
  `commit` / `undo` / `redo` / `goTo` / `history` remain as single-operation forms.
- **`HistoryWriter`** (a singleton on the app-wide data scope) queues the editor's ops and writes them off the main
  thread; ops that arrive while a write runs are coalesced into the next transaction (a burst of commits is one write),
  and the queue outlives the screen, so leaving the editor right after a commit still saves it. A kill loses at most
  the last few milliseconds of queued steps, never half a step. The editor flushes the writer before `load`, so
  reopening a photo at once reads its newest step.
- Each row stores the **full recipe after the step** (`encoding = "snapshot"`) plus its label as JSON: the field
  changes (`{"field":"EXPOSURE","value":"+0.70"}`) and, for a named step, a first element
  `{"action":"PASTE","groups":["LIGHT","COLOUR"]}`. Unknown fields or actions from a newer app are skipped; a step
  whose recipe cannot be decoded (a newer schema) ends the loaded history there. Recipes are a few KB, so
  snapshots keep undo O(1) and robust against migrations (each snapshot goes through `RecipeCodec`'s migrators).
  When masks with brush strokes make recipes large, the `encoding` column allows keyframes + diffs without a schema
  change. History is unlimited.
- **Copy/paste:** `copy(recipe)` stores the whole recipe as the clipboard in `meta` (`edits.clipboard`, survives
  restarts; `clipboard()` is a Flow the editor and the library observe); the paste chooser picks the groups and
  remembers them (`edits.pasteGroups`, default everything but geometry). `paste(versionIds, clipboard, groups, action)`
  pastes onto many versions in one transaction, **one history step per changed version** (`PASTE`; the library's
  "Reset edits" is the same path with the default recipe and `RESET_ALL`), so each photo can undo its own paste.
- **"Previous":** `previous(excludeVersionId)` = the recipe with the newest `updated_at` among non-default recipes
  of other versions (one query on `recipes`). No schema change: the M1 data model already had every table.

Photos opened from other apps are filed in the library (ADR 0011), so they get a version and a persisted history as
well; an `EditorRoute(uri)` that has no version keeps its history in memory for the session only.

## Indexing (`LibraryIndexer`)

1. **Walk**: breadth-first `DocumentsContract` child queries (one per folder, all rows read in one pass), skipping
   names that start with `.`; images by extension (`ImageFormats`), `.xmp` sidecars by name. Cancellable between
   folders. A failing sub-folder is skipped; a failing root fails the run **without deleting anything**.
2. **Save the difference** in transactions of 500 shots: per folder `ShotPairing.pair`, then for each shot: new →
   photo + version + files; known → files whose size/mtime changed (or added/removed members) mark the photo
   `PENDING`. Files no longer listed are deleted, then photos without files. All comparisons are hash lookups over
   the source's rows loaded once.
3. **Sidecars** that are new or whose mtime/document changed are read and applied (rating, label, flag).
4. **Headers** of `PENDING` photos, 4 at a time, written in batches of 64: `ImageInspector` on the RAW (gaps filled
   from the rendering): capture time with sub-seconds and offset, size, orientation; a JPEG's embedded XMP packet
   and the TIFF/XMP rating. Embedded values only fill a photo nobody rated (no sidecar, no stars, flag or label).

A source is indexed when added, on every library start (`refresh`, incremental: an unchanged folder costs one
listing), on "Rescan" and when a volume comes back. Sort time = capture time, else the file mtime converted to the
same "local wall clock as UTC" convention, so date headers group both the same way (formatted in UTC).

**Volumes:** `StorageVolumes` maps a tree of `com.android.externalstorage.documents` to its volume (`primary`,
`1234-ABCD`) and reads mount state from `StorageManager`; media broadcasts (mounted, unmounted, eject, removed) make
the library re-probe. A source is `UNPLUGGED` when its volume is not mounted or its root no longer lists,
`PERMISSION_LOST` on a `SecurityException`. Unplugged sources keep their index and cached thumbnails; the grid shows
a `warning` banner, the sources list a warning icon, and the viewer says the folder is not available.

## XMP (`XmpReader`, `XmpWriter`)

The fields Reveal shares with Hoard (its ADR 0009, section 9), Lightroom, darktable and digiKam:

| Field | XMP | Notes |
| --- | --- | --- |
| stars 0..5 | `xmp:Rating` | as attribute or element; fractional values truncate, out of range clamps |
| reject | `xmp:Rating="-1"` | Lightroom, darktable and Hoard (`PhotoMetadata.rating`) agree |
| pick / reject / none | `digiKam:PickLabel` 3 / 1 / 0 | the only pick flag in common use; always written so a pick (or an explicit "none") survives |
| pick (read only) | `xmp:CreatorTool` starting with `Hoard` and no `digiKam:PickLabel` | Hoard writes sidecars only for the shots "Copy picks" copies |
| label | `xmp:Label` | free text; Lightroom shows colour names as colours |

Sidecar names: `<stem>.xmp` (Lightroom, Hoard; one per pair, named after the RAW), else `<file name>.xmp`
(darktable). Writing **merges** into the existing sidecar through the DOM: only `xmp:Rating`, `xmp:Label`,
`digiKam:PickLabel` and `xmp:MetadataDate` change; Lightroom's `crs:` settings, darktable's history, `<?xpacket?>`
and comments stay; an existing prefix for the XMP namespace (`xap:`) is reused. A new sidecar gets the standard
`x:xmpmeta/rdf:RDF/rdf:Description` shape with `xmp:CreatorTool="Reveal <version>"`. A sidecar that is not
well-formed is **never overwritten** (the change stays in the database). Parsing refuses DTDs/external entities.
Serialising uses our own small writer (the platform `Transformer` differs between the JVM and Android), with Hoard's
attribute escaping (`& < > " '`, tab/newline/CR as references, XML-invalid characters dropped).

Writes happen when the source is available and **writable** (the persisted grant includes write and the root
accepts new documents); otherwise, or if the provider refuses, the change is kept in the database and the UI says
"Saved in Reveal only". After writing, the sidecar's new mtime is stored, so the next walk does not read Reveal's own
write back as a change. A sidecar changed by another app (newer mtime) wins over the database on the next index.

**Seam for M5:** `XmpNs.REVEAL` (`https://reveal.hfps.dev/ns/1.0/`) is reserved for the recipe; the writer leaves
`reveal:` properties untouched and `XmpWriter.update(..., extensions)` takes `XmpExtension`s that will write them.

## Thumbnails (`ThumbnailRepository`)

Fast path: the RAW's embedded preview (`Inspection.previewFor(512)`, two small reads), else the camera/phone JPEG
decoded with `inSampleSize` straight from its descriptor; upright, long edge 512 px. Memory LRU (1/8 of the heap,
at most 96 MB) and JPEG q85 files in `cacheDir/thumbs`, indexed in `cache_entries` with an LRU cap of 256 MB
(trimmed every 50 writes; orphan files swept). The key is the file identity (source, path, size, mtime), so a
changed file gets a new thumbnail and stale ones age out. 3 decodes at a time.

**Edited badge:** when a version has a non-default recipe, the cell shows a primary dot and the thumbnail is a
render of the edit: `GpuEditedThumbnails` decodes a 512 px proxy (`ProxyBuilder`), maps it like the editor
(`PipelineInputMapper`), uploads it to one shared `GpuDevelopRenderer` and reads back `renderOutput(recipe)` (sRGB
RGBA8). No new renderer API was needed. Its key adds the recipe time and `PIPELINE_VERSION`. The renderer closes
after 5 s idle.

## Viewer

Pages are the grid's list at the moment it opened (same scope, filter, order, search), so rating a photo out of a
filter does not move the page. Display: `FirstImageLoader` at the window's long edge (embedded preview, or the camera
JPEG); an edited photo shows a develop render of its recipe instead. Pinch and double-tap to 100 % of the full
size; past the display image's resolution, `RegionSource` (`BitmapRegionDecoder` on the camera rendering's
descriptor, orientation-aware) decodes the visible region at the right sample size after the gesture settles. A RAW
without a rendering gets a develop render at 3200 px (half-size LibRaw decode) as its detail: true 100 % of a lone
RAW needs the tiled renderer (M2). Info sheet: EXIF read from the files on demand.

## UI (`:feature:library`)

hfps shell; sources pane (All photos, Recent, Opened from other apps placeholder, folders with volume, count and
state, Add folder) and grid, side by side from 840 dp, one at a time below (predictive back from the grid to the
sources). The library reopens the folder it showed last (`meta`). Grid: 3 columns on a portrait phone, else
`width / 120 dp`; 2 dp gaps; date headers; badges (`RAW+JPG`/`RAW`, edited dot, stars, pick/reject; rejected cells
dimmed; badge text capped at 130 % font scale, the cell's TalkBack description carries everything). Filters: all /
edited / not edited / picked / rated ≥ N / RAW (has a RAW) / JPEG (no RAW); sort newest, oldest, name; search by
file name (`LIKE` with escaped wildcards). Multi-select: long-press, tap to toggle, drag across rows with auto-scroll;
rate, pick, reject, clear flag, remove from library, select all; preset and export are disabled
placeholders. "Open a photo…" (single document, M0's flow) stays in the menu; the editor route takes a version id
from the library (it opens the RAW of a pair) or a URI from other apps.

## Measurements

Emulator `reveal-library` (API 36.1 arm64 on the Mac mini, host GPU, 1080x2400, 60 Hz), debug build,
`LibraryMeasurementsTest`: one folder of **10,000 files** (3,000 RAW+JPEG pairs of synthetic TIFF "ARW"s with
embedded previews + 4,000 JPEGs, 640x427 previews, 300 sidecars; 7,000 photos, 50 MB) behind the debug
`SyntheticDocumentsProvider` running in its own process (every call crosses a binder). Indexing and first frame: four to seven runs; fling: two runs
(ranges):

| Step | Result |
| --- | --- |
| First index, total | 4.2-6.2 s (listing 0.32-0.37 s, saving 1.0-1.3 s, 300 sidecars 0.21-0.52 s, 7,000 headers 2.6-4.1 s) |
| Unchanged re-index | 0.37-0.45 s (one listing + a diff of 10,000 rows) |
| First rows / first grid frame after the grid is asked for (7,000 photos) | 50-370 ms / 130-540 ms (the higher values include the activity launch) |
| Fling through the grid with thumbnails loading (20 flings, ~1,040 frames) | frames past their deadline 1.0-1.1 %; main thread per frame p50 2.3-2.6 ms, p90 4.1-5.0 ms, p99 7.6-9.6 ms; total frame time p50 17-20 ms, p99 27-34 ms (the emulator's GPU/swap dominates) |

The UI never waits for indexing: rows stream in (Room flow, coalesced to 4 updates/s while indexing) and the walk,
the database writes and the header reads run on IO threads. Numbers on the owner's phone are for the orchestrator to
add (release build, real SD card).

## Consequences

- One shared database owns the working copy of ratings and edits; sidecars are the portable copy.
- In an added folder, a file renamed or moved outside Reveal is still a new photo for now (its edits stay with the
  old, removed row): folders match by path until cross-folder matching by fingerprint lands. A photo handed over by
  another app keeps its edits when renamed or moved (it is matched by content), and a different file with a known
  name never inherits another one's edits.
- Editing a lone RAW at 100 % in the viewer waits for M2's tiles; pairs and JPEGs already zoom to real pixels.
- Hoard reads `xmp:Rating` only; to round-trip picks it can read/write `digiKam:PickLabel` (noted for the hand-off).
