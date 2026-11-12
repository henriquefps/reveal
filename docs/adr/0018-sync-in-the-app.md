# ADR 0018: Sync in the app

Status: accepted (MS/2). Date: 2026-11-11.

## Context

ADR 0017 defines the `reveal-sync` protocol and server. `docs/plan/sync.md` ("App side") asks the app for: pairing and a
Sync screen, a background job that pushes and pulls edits and library metadata with an offline queue, conflict copies
that are never dropped, the server's NAS photos in the library, smart previews so a device without the original can
still edit, an originals backup for photos only a device has, and the cloud state on library cells. `distribution.md`
adds: the server is the user's own (no host in the app), no feature locks.

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Module | `:core:sync` (Android library, Hilt): protocol client, engine, uploads, smart previews, the server-files provider, WorkManager jobs. `:feature:settings` (Sync screen) and `:feature:library` (Server source, delete copies) depend on it; `:core:data` holds the tables, the triggers and the `ServerTree`. |
| HTTP | OkHttp 5 (the platform `HttpURLConnection` has no `PATCH`). No host is compiled in: the base URL comes from the pairing link; a reverse proxy's path prefix is kept. |
| Device key | AES-GCM with a key that never leaves the Android Keystore; the ciphertext in no-backup preferences (`allowBackup` is off). Never logged. |
| Change tracking | SQLite triggers on `photos`, `photo_files`, `versions`, `recipes`, `history_cursor` mark a `sync_records` row dirty; silenced while a pull is applied (`meta` key `sync.applying`). Every write path is covered without touching it. |
| Push | Dirty records, photos first, at most 500 per request and under 7 MiB of JSON; `baseRev` = the record's last known server rev. An unedited version nobody has seen is never pushed; a photo waits for its fingerprint. |
| Pull | `GET /v1/changes` from the stored cursor; each page applied in transactions of 50 records with a coroutine yield between them (a 10,000-record first pull never holds the database long; the library stays responsive), the cursor saved after each page. `invalid_cursor` restarts from the empty cursor. |
| Conflicts | A record with unpushed local work is not overwritten: the server's state waits in `sync_inbox` and applies after our push if it is still the newest. A `copyOf` version becomes a local version with `versions.copy_of` / `copy_device`: a second cell with a "Conflict" badge until M5's versions UI. |
| Identity | A pulled photo is found by uuid, then by alias, then by its main file's fingerprint. A local photo never pushed adopts the server's uuid; one already pushed under another uuid keeps it and the server's id becomes an alias (`sync_aliases`). A local version nobody edited adopts an incoming version at the same position. |
| Server photos | One `sources.kind = SERVER` source holds photos only the server has (NAS files, other devices' photos). Their document id is `<fingerprint>/<name>`; `ServerFilesProvider` serves them by range, so thumbnails, viewer, editor and export need no change. "All photos" excludes them; the library has a "Server" entry. |
| Smart previews | `SmartPreviewCodec` (`:core:pipeline/preview`): the editor's own `PipelineInput` at 2560 px (version 2 since 0.5.2: log-encoded luma and half-resolution chroma quantised to the photo's own noise, deflate) plus a 1280 px display JPEG. The editor opens it directly; made by devices only for device-only photos whose original cannot go up (amendment FIX/P). |
| Originals | Device-only photos (lookup says missing) of folders with the backup on, and edited photos of any folder (amendment FIX/P): full SHA-256, chunked resumable upload, Wi-Fi only by default. The server's fingerprint check guarantees the bytes. |
| Jobs | `SyncWorker` (any network, exponential backoff), `BlobWorker` (network, battery not low, storage not low; originals only unmetered unless allowed), a 15-minute periodic sync, a sync 3 s after local changes settle, and one every 30 s while the app is in the foreground. |
| Server change | `GET /v1/devices` (new, ADR 0017 "Other endpoints") names the device of a conflict copy. |

## Data model (database v6)

| Table / column | Meaning |
| --- | --- |
| `sync_records(kind, record_id, rev, dirty, modified_at, deleted)` | per synced record: the server rev it stands on (the next push's `baseRev`) and whether it changed locally since (`modified_at` = the push's `modifiedAt`). A tombstone keeps its row. |
| `photo_cloud(photo_id, original_on_server, original_source, preview_sha, preview_width/height, preview_fingerprint, original_sha, checked_at)` | what the server holds for a photo and what this device already did for it. |
| `sync_uploads` | one preview or original upload: the server's upload id and offset after each chunk, state `pending` / `done` / `failed`. |
| `sync_aliases(remote_uuid, photo_id)` | a server photo id that names a local photo under another uuid. |
| `sync_inbox(kind, record_id, rev, json)` | pulled records not placed yet (a version before its photo, a record with local work). |
| `versions.copy_of`, `versions.copy_device` | a conflict copy and the device whose change lost. |
| `sources.backup` | the per-folder switch of the originals backup (default on). |

Auto-migration 5 → 6 (`RevealDatabaseMigrationTest`). The triggers are not Room schema: `RevealDatabase.configure`
adds a callback that creates them on every open (versioned names, idempotent). SQLite 3.22 (API 29) has no UPSERT, and
inside a trigger the outer statement's conflict clause (Room writes `INSERT OR ABORT`) overrides the trigger's own, so
the mark is a guarded `INSERT ... SELECT ... WHERE NOT EXISTS` plus an `UPDATE`.

## Payloads

`photo.data`: `fingerprint` (the RAW of a pair, else the rendering), `files: [{fingerprint, role, name, size}]`,
`name`, `format`, `rating`, `flag`, `label`, `captureTime`, `width`, `height`, `hidden`, `preview: {sha256, width,
height}`. `version.data`: `photoId`, `position`, `name`, `recipe` (ADR 0003 JSON), `history: [{recipe, label}]` (the
stored snapshot and its `labels_json`), `cursor`. A version over 90 % of the server's 2 MiB record limit drops its
oldest history steps (undo stops at the oldest kept one). Settings: `setting/export.authorship` `{author, copyright}`;
a setting is pushed when its value differs from the last one pushed or applied.

Applying a pulled version replaces the version's history, cursor and recipe (`recipes.updated_at` = now, so edited
thumbnails refresh) and announces it on `RemoteEdits`: an open editor on that version reloads its history unless a
slider is held. Local deletes of photos (removing a folder, forgetting hand-offs) are never pushed: other devices keep
their edits. A pulled photo tombstone removes only a Server-source photo. Synced ratings are not written to XMP
sidecars (the sidecar is written when the rating is set on the device that has the folder).

## Pairing

The Sync screen (Settings → Sync) reads the server's QR code with CameraX and ZXing core (both Apache-2.0, no Play
services), or takes the pasted `revealsync://pair?...` link. `PairingLink.parse` accepts version 1, an `http(s)` URL
and a token; the `server` id, when present, must match the one `POST /v1/pair` answers. A new pairing forgets every
sync row first (a device paired twice starts from nothing and pulls before it pushes, so shared photos adopt the
server's ids). Unpairing deletes the key, the cursors, the sync tables, the Server source and the cached server files;
local photos and edits stay.

## Server photos

`ServerFilesProvider` (`<applicationId>.sync`, not exported):

- `original/<fingerprint>/<name>`: the original, through `StorageManager.openProxyFileDescriptor` (seekable, so LibRaw's
  `pread` and `BitmapFactory` work unchanged) backed by `RangeCache`: 256 KiB blocks under `cache/sync-originals`. A
  miss fetches one block; while a file is read forward (a RAW decode, an export download) each next miss doubles the
  read-ahead up to 16 blocks per `Range` request, so a header read costs one block and a whole file a few requests.
  LRU by file to 1 GiB. Sizes come from the library, so cached
  blocks read offline. The cache is a directory LRU, not `cache_entries` rows: that table is the thumbnail cache's index
  and its trim would count and delete these blocks.
- `display/<fingerprint>/<name>`: the same, or, when the server cannot serve the original, the display JPEG inside the
  photo's smart preview (grid and viewer of a preview-only photo).
- `smart/<fingerprint>/<name>`: the smart preview, downloaded whole into `cache/sync-previews` (LRU 512 MiB).

`ServerTree.uri` is the strict original (the editor, the export); `ServerTree.openInput` is `display`. The NAS feed
files each NAS file under the Server source (folder = the NAS folder's name and directory, paired by stem); a NAS file
whose fingerprint a local photo has only marks that photo `original_on_server`.

**Capture times.** The server keeps no EXIF, so a NAS photo starts with the file's time and `metadata_state =
PENDING` (so does another device's photo whose record has no `captureTime`). After every metadata sync,
`LibraryIndexer.readServerMetadata` reads up to 300 of those headers through the provider (one 256 KiB block each, the
library's own header reader: capture time with sub-seconds, offset, size, orientation, embedded rating) and sorts them
like local photos. It writes with the triggers quiet: a capture time read from a file is not a change to push.

## Smart previews

The format (`RVSP`) is in `SmartPreviewCodec`'s KDoc. Version 1 (0.5.0-0.5.1): luma (green) at 0.0003-stop steps
over 20 stops, chroma (`log2 r - log2 g`, `log2 b - log2 g`) at half resolution and 0.004-stop steps. Version 2
(0.5.2): the same planes with steps that follow the photo's own noise (amendment FIX/P below); values below black
clamp to 0 in both. `SmartPreviewCodecTest`: green within 0.1 %, mean colour error under 2 % at the scene's ring edges,
a developed preview within 1.5 8-bit levels (mean) of the developed original, version 1 files still open, a clean
gradient keeps fine monotone steps. Which photos get one: amendment FIX/P (only where the original cannot go up).

`LibraryRepository.editTarget` opens the smart preview when the original is out of reach (a Server photo the server
cannot serve, an unplugged folder); `PhotoOpener` recognises the magic and builds the editor's input from it (no full
resolution behind it, so zoom stops at the preview).

## Export

`LibraryRepository.exportTarget` says where the original can be read (`OriginalReach`): on the device, on the server
(a Server photo the server serves, or an unplugged folder's photo whose backup the server holds: the target's URI is
then the server original), or only on another device. `ExportWorker` (`OriginalPolicy`, JVM-tested):

- **Server:** the original is downloaded first, read once from start to end through the provider into the range cache,
  with its own notification ("Downloading the original from your sync server", progress, Cancel); the export then
  decodes the cached copy. A cancel leaves the item pending.
- **Only on another device:** the item is `WAITING`, not failed. The run finishes its other items, the notification
  and the export sheet say the photos wait for their originals and that the export finishes by itself, and the job
  runs again every 30 minutes with a network (its own unique work, so other exports never queue behind it). Each run
  first tries the original again (the provider asks the server), and the daily fingerprint lookups include Server
  photos, so another device's backup unblocks it. After 7 days of waiting the item fails with "the original is only on
  another device and never reached your sync server".

## Originals backup

After a fingerprint lookup (`POST /v1/fingerprints/lookup`, 1000 at a time, a missing answer asked again after a day)
a device-only photo in a folder with the backup on is queued; the worker computes its full SHA-256 from the document,
`POST /v1/uploads` (an existing blob is answered `exists` and only registered), then sends chunks of the server's
`chunkSize`, saving the offset after each one. A broken chunk resumes from the server's `Upload-Offset` (`HEAD`); a
process killed mid-way resumes in the next run from the stored upload id. A checksum mismatch marks the upload failed
(the file changed while it was read); the next lookup decides again. Nothing is ever deleted from the device.

## Cloud states on cells

| State | Badge | When |
| --- | --- | --- |
| local | none | the original is on this device only (or sync is off) |
| backed up | cloud with a check | on this device, and the server has it (NAS or backup) |
| in the cloud | cloud | only the server has it; it opens by range |
| preview only | picture | only another device has the original; this one has its smart preview |

Conflict copies are separate cells (keyed by version) with a "Conflict" badge and "Conflict copy from <device>" for
TalkBack; "Delete conflict copies" in the selection menu deletes them here and pushes their tombstones.

## Tests

- JVM, `:core:sync` against `FakeSyncServer` (a MockWebServer dispatcher implementing ADR 0017): two simulated devices
  (two in-memory databases) — an edit travels both ways and the photo adopts the server's id; concurrent edits keep the
  loser as a conflict copy on both devices; deleting the copy removes it on the other; a photo only one device has
  appears in the other's Server source with its edit and rating; NAS files become server photos paired by stem and mark
  local copies; lookups; settings; a reset server's cursor; unprinted photos and unedited versions; 1,000 edited photos
  in one run. Uploads: chunks, an interrupted chunk, a run killed mid-way resuming from `HEAD` in a new uploader, an
  existing blob not sent again. Pairing links, pairing, unpairing, error codes.
- JVM, `:core:data`: the 5 → 6 migration, every trigger, the quiet flag. `:core:pipeline`: `SmartPreviewCodecTest`.
- Instrumented, `:app` `SyncFlowTest` against the real `reveal-sync` run locally (see CLAUDE.md "Sync (app)"): pair,
  the NAS RAW's capture time read by range, its export (downloaded first) and a preview-only photo's export waiting;
  `TwoDevicePassTest` (two real devices); `SyncPullMeasurementsTest` (library open and scroll frames during a
  10,000-record first pull, against a baseline).
- JVM: `OriginalPolicyTest`, `RangeCacheTest` (one block for a header, a growing read-ahead for a whole file).

## Amendment 2026-11-12 (FIX/S, 0.5.1): hand-offs and retries

- **Unreadable originals** (ADR 0011 "Unreadable hand-offs"): opening an original that Reveal can no longer read
  (`SecurityException`, a missing document, or a provider's `IllegalArgumentException`, which `ImageInput.fromUri`
  reports as missing) marks the file `unreadable_at` and drops its queued upload: it is **parked**, not retried every
  run (0.5.0 retried ~10 such originals every few seconds). `backupCandidates` skips unreadable files until a read
  clears the mark (a re-share, gallery access); `previewCandidates` includes them, and the hand-off's local smart
  preview is uploaded as it is (`SmartPreviewMaker.adopt`).
- **No tight loop**: a blob run asks for the next one at once only when it moved something forward (made, sent, or
  set aside a preview) and work is left; otherwise the next metadata sync or the periodic job tries again. A preview
  that cannot be made is set aside per file (as before) and logged once with its class, the place in Reveal's code and,
  for programming errors only, the message (`BlobUploader.describe`; never a URI).
- **Smart previews of any file**: `SmartPreviewMaker` (moved to `:core:pipeline/preview`) gives an `IOException` for
  what cannot be developed (a camera without a colour matrix); `SmartPreviewCodec` never fails on a non-finite sample
  (NaN encodes as black) or lens term (the lens is left out); the decoders never ask for a non-parametric colour space
  (`StandardDecoder.decodableSpace`). Verified on JPEG-only (1 x 1, 3 x 1001, 2561 x 1707, rotated), PNG, WebP, HEIC,
  Display P3, BT.2020 HLG and the six CC0 RAWs (`SmartPreviewMakerTest`).
- **Merged hand-offs**: a photo tombstone is pushed for a hand-off merged into a folder photo when the server knew
  both; pulling it keeps a photo another server id names (an alias: `sync_aliases`) under that id instead of deleting
  it. `versions.photo_id` changes are tracked (trigger version 3).

## Amendment 2026-11-12 (FIX/P, 0.5.2): fewer and smaller smart previews

The owner's server held 153 previews of about 9 MB each (1.42 GB), more than the JPEG originals: 0.5.x made one for
every edited, rated or picked device-only photo, and for every photo of a folder with the backup off.

- **Edited photos back up their original** (like Lightroom): `backupCandidates` takes every device-only photo of a
  folder with the backup on **and every edited photo** (`photos.has_edit`: a version with a non-default recipe) of
  any folder or hand-off, with the same Wi-Fi rule, uploader and lookup. A folder with the backup off sends nothing
  else: ratings, flags, labels and recipes travel as records. Removing an edit never deletes an uploaded original.
  Settings → Sync says so ("Off: only photos you edit are backed up").
- **A smart preview only where the original cannot go up**: the file is unreadable (a hand-off whose grant ended, ADR
  0011; then it is the only copy) or the photo is edited while the originals backup is off (`previewCandidates`,
  `originalsOff`). No stop-gap preview while an edited photo's original waits for Wi-Fi: that would upload a preview
  and drop it again minutes later; the other device sees the photo (metadata, edit) without pixels until the original
  lands. A rated or picked photo only another device has shows the "only on another device" badge
  (`CloudState.ELSEWHERE`), not "smart preview only".
- **Previews that no longer qualify are dropped** (an edit reset, the original backed up): before each metadata push
  `BlobUploader.markDrops` clears the photo's preview (the record is pushed without it) and queues a `drop_preview`
  row in `sync_uploads`; the blob run asks `DELETE /v1/blobs/{sha256}` (ADR 0017 "Dropping a smart preview"), which
  the server refuses while any live photo record names it. A preview replaced by a new one (format 2, or a changed
  file) is dropped the same way. A server before 0.5.2 (`405`) keeps the drops queued. A pulled photo record without a
  preview now clears the device's pointer (0.5.x kept the old one), except for a preview this device made: then the
  record is pushed again with it.
- **Old previews are made again lazily**: `photo_cloud.preview_format` (database v9, null = made by 0.5.x or by
  another device) marks the format this device uploaded; on an unmetered network each blob run makes up to 3
  qualifying format 1 previews again (`outdatedPreviews`), never re-scheduling itself for them.
- **Format 2** (`SmartPreviewCodec` KDoc, `PreviewPlanes.kt`): decoding reads 1 and 2. The bytes of format 1 were
  almost all the photo's own sensor noise coded exactly (16-bit log luma at 0.0003-stop steps). Format 2 measures that
  noise per 1/2-stop level (`PreviewNoise`: lower quartile of each pixel's difference to its 4-neighbour mean,
  relative, never rising with brightness) and quantises in log2 with steps of one noise sigma, clamped to
  0.0004-0.02 stops for green and 0.004-0.015 stops for chroma (1/8-stop level tables in the header), then LOCO-I
  median prediction, split low/high residual bytes and deflate level 9. The display JPEG is 1280 px (was 1600).

### Measurements (FIX/P)

`SmartPreviewSizesTest` (`REVEAL_PREVIEW_SIZES`, sizes without the display JPEG, CPU develop of both previews, CIEDE2000 on the float output before 8-bit
rounding; "strong" = Auto + an S tone curve + sharpening 80 + noise reduction + texture/clarity/dehaze, "lift" = Auto
+2 EV and shadows +80; "block" = the worst 4 x 4-pixel mean, what is visible at 1:1 without single noisy pixels):

| Sample | v1 | Steps (max green/chroma stops) | v2 | of v1 | strong p99 / max / block | lift p99 / max / block |
| --- | --- | --- | --- | --- | --- | --- |
| Sony ZV-E10 (ISO 100) 2560x1710 | 8.95 MB | **A 0.02/0.015 (default)** | 3.73 MB | 42 % | 0.80 / 2.52 / 1.11 | 0.96 / 1.88 / 1.15 |
| Sony ZV-E10 (ISO 100) 2560x1710 | 8.95 MB | B 0.03/0.03 | 3.39 MB | 38 % | 1.17 / 3.11 / 1.60 | 1.58 / 2.95 / 1.68 |
| Sony ZV-E10 (ISO 100) 2560x1710 | 8.95 MB | C 0.05/0.06 | 3.21 MB | 36 % | 1.28 / 3.38 / 1.75 | 2.00 / 4.21 / 2.51 |
| Nikon D5600 2560x1709 | 10.16 MB | **A 0.02/0.015 (default)** | 4.79 MB | 47 % | 0.59 / 2.55 / 0.95 | 0.69 / 1.78 / 0.93 |
| Nikon D5600 2560x1709 | 10.16 MB | B 0.03/0.03 | 4.40 MB | 43 % | 0.97 / 4.33 / 1.64 | 1.18 / 2.64 / 1.31 |
| Nikon D5600 2560x1709 | 10.16 MB | C 0.05/0.06 | 3.85 MB | 38 % | 1.82 / 7.99 / 3.22 | 2.24 / 4.98 / 2.69 |
| Sony A7C ISO 12800 2560x1710 | 9.38 MB | **A 0.02/0.015 (default)** | 4.13 MB | 44 % | 0.59 / 1.64 / 0.73 | 0.77 / 1.81 / 0.85 |
| Sony A7C ISO 12800 2560x1710 | 9.38 MB | B 0.03/0.03 | 3.66 MB | 39 % | 0.87 / 2.40 / 1.04 | 1.31 / 2.70 / 1.80 |
| Sony A7C ISO 12800 2560x1710 | 9.38 MB | C 0.05/0.06 | 3.23 MB | 34 % | 1.41 / 4.12 / 1.74 | 2.37 / 4.93 / 2.70 |
| Sony A7C ISO 2000 2560x1710 | 8.92 MB | **A 0.02/0.015 (default)** | 3.66 MB | 41 % | 0.72 / 1.83 / 0.62 | 0.78 / 1.87 / 0.88 |
| Sony A7C ISO 2000 2560x1710 | 8.92 MB | B 0.03/0.03 | 3.22 MB | 36 % | 1.02 / 2.44 / 1.16 | 1.34 / 2.76 / 1.65 |
| Sony A7C ISO 2000 2560x1710 | 8.92 MB | C 0.05/0.06 | 3.03 MB | 34 % | 1.03 / 2.43 / 1.08 | 2.14 / 4.58 / 2.53 |
| Canon EOS R6 2560x1709 | 8.79 MB | **A 0.02/0.015 (default)** | 3.43 MB | 39 % | 1.07 / 2.88 / 1.59 | 0.86 / 2.02 / 1.03 |
| Canon EOS R6 2560x1709 | 8.79 MB | B 0.03/0.03 | 3.32 MB | 38 % | 1.20 / 3.70 / 1.60 | 1.15 / 2.87 / 1.47 |
| Canon EOS R6 2560x1709 | 8.79 MB | C 0.05/0.06 | 3.31 MB | 38 % | 1.20 / 3.70 / 1.60 | 1.19 / 3.12 / 1.57 |
| Pixel 4a DNG 2016x1512 | 7.29 MB | **A 0.02/0.015 (default)** | 3.69 MB | 51 % | 0.91 / 2.75 / 1.14 | 0.76 / 1.63 / 0.75 |
| Pixel 4a DNG 2016x1512 | 7.29 MB | B 0.03/0.03 | 3.43 MB | 47 % | 1.55 / 4.38 / 1.86 | 1.31 / 2.67 / 1.42 |
| Pixel 4a DNG 2016x1512 | 7.29 MB | C 0.05/0.06 | 3.07 MB | 42 % | 2.92 / 8.10 / 3.20 | 2.46 / 4.93 / 2.70 |
| JPEG 768 px (public domain) 768x604 | 0.77 MB | **A 0.02/0.015 (default)** | 0.71 MB | 92 % | 0.14 / 0.66 / 0.08 | 0.12 / 0.22 / 0.11 |

The display JPEG adds 104-411 KB at 1280 px (155-579 KB at 1600 px). Default A: 3.4-4.8 MB of pixels (39-51 % of format 1),
about 3.6-5.1 MB with the JPEG; developed p99 0.6-1.07, the worst 4 x 4 block 0.6-1.6, single noisy pixels up to ~2.9 where
the photo's own grain is several units (the differences there are grain-like, not banding or shifts).

On a device (x86_64 emulator, the real `SmartPreviewMaker`: ProxyBuilder decode, LibRaw; `SmartPreviewMakerTest
#publicSampleRaws`, log `REVEAL_PREVIEWS`), whole files with the display JPEG: ZV-E10 3.8 MB, A7C ISO 2000 3.8 MB,
A7C ISO 12800 4.3 MB, Canon R6 3.6 MB, Nikon D5600 5.1 MB, Pixel 4a DNG (2560 x 1920) 5.7 MB; JPEGs 86-307 KB.

Where the bytes go: in format 1 a 24 MP RAW at 2560 x 1710 is ~6.6 MB of green and ~2.4 MB of chroma; predictors
(MED, averages, gradient) change the entropy by under 5 % and a context-adaptive coder would gain about 5 % over
deflate: the residual is the noise. A plain bit cut (11-bit green, 8-bit chroma) still leaves ~3.5 MB. Only a step
tied to the noise brings it down, and the developed difference then grows with the step (above), so the steps were
chosen at the edge of "p99 below 1": 1.5-3 MB would need steps about twice as coarse (p99 1.5-2.5, max block 2-3).
Decision (owner, 2026-11-12): keep A, quality first (~45 % of format 1). The 1.5-3 MB target came from the old "a
preview for every rated, picked or unbacked photo" rule; with edited photos backing up their original, previews are
rare (unreadable files, or the originals backup off), so their size matters far less than their fidelity.

## Amendment 2026-11-12 (FIX/V, 0.5.3): the server view shows what can be opened

The owner's 0.5.2 run (fresh server and apps): a camera folder of 437 RAW + JPEG shots added on the phone (backup on),
removed, added again, backup turned off. The server ended with 876 live photo records (two sets) and 45 originals; the
tablet's Server scope listed all of them, mostly as "Unavailable" squares, and the few with the cloud badge did not
open (`404` on `GET /v1/originals/{fingerprint}`); the phone showed no Server entry at all.

Causes found:

- **The 404**: `ServerFiles.original` trusted the photo's `photo_cloud.original_on_server` (a lookup of the *main*
  file) for every file of the shot and skipped asking the server. The backup sends only the main file (the RAW), so
  the viewer and grid reading the camera JPEG of a pair asked the server for a file it never had. Server photos were
  also marked unreadable by the thumbnail loader when the server could not serve them, hence the placeholders.
- **The duplicates**: removing a folder deleted its photos locally while their records stayed on the server; the
  device's own records are behind its pull cursor (it skips its own revisions), so they never came back, and adding
  the folder again pushed new ids for the same files.

Decisions (owner):

- **Per-file presence** (database v10): `photo_files.on_server`, set by the lookups (a Server photo's lookup asks about
  every file of its shot, `SyncDao.serverFilesOf`; the main file still decides `photo_cloud.original_on_server` for
  this device's photos, "any file" for Server photos) and by the NAS feed. `ServerFiles.original` trusts only that;
  anything not known is asked first (`HEAD`). The open sites (`LibraryRepository.detail`, `PhotoImages`,
  `ThumbnailRepository`) use `ServerTree.reachable`: a Server photo opens from the files the server holds (a shot whose
  JPEG alone is on a NAS opens from the JPEG). A failed read of a Server photo never marks it unreadable.
- **The Server scope lists only what opens**: `PhotoQuerySql.SERVER_OPENABLE_SQL` (original of any file, or a smart
  preview), also the Server entry's count. Records that only describe a photo stay in the database (ratings, edits;
  they merge when the photo shows up) and a pulled record of such a photo asks the lookup again at once; the device
  whose backup lands (`SyncStore.originalLanded`) pushes the record again so the others ask. The "Unavailable" filter
  chip is gone; the placeholder stays only for this device's own unreadable photos (`UNAVAILABLE_SQL` excludes Server
  photos).
- **Folder backup default "Only edited"**: a folder added while paired asks once (`AddFolderBackupSheet`: "Only photos
  you edit", preselected, or "Everything"); not paired, it is added with "Only edited". Existing folders keep theirs.
  `LibraryRepository.addSource(uri, backup)`; Settings → Sync names each folder's mode.
- **Removing a folder** (`ServerLibrary.removeFolder`, before the library forgets it; the dialog asks "Also delete from
  the server?", unchecked when the folder has edited photos):
  - yes: every photo's records (photo, aliases, versions) are tombstoned and every file's fingerprint is queued in
    `sync_deletes`; after the next push `BlobUploader.deleteOriginals` calls `DELETE /v1/originals/{fingerprint}` (ADR
    0017; `409` waits and is given up after a few tries, `405` waits for a server update); the preview goes with the
    tombstone on the server;
  - no: a photo with an original or preview on the server, or with an edit, moves to the Server source on this device
    (same uuid, versions, ratings and sync rows; files keyed `ServerTree.documentId`), so it stays visible (when it
    opens) and editable, and its record stays known here;
  - both: an unedited photo with nothing on the server has its records tombstoned.
- **Adding a folder again never duplicates**: after every fingerprint pass `HandoffMerge.mergeServerPhotosInto` merges a
  Server photo whose file the folder holds into the folder photo, with the hand-off rules (culling `resolve`, versions
  moved, the server's uuid adopted or tombstone + alias, the record's preview kept named when the id is adopted).
- **Delete from server** (Server scope selection, `ServerLibrary.deleteFromServer`): the same tombstones and deletes; a
  device that has the photo in a folder keeps the file, loses the edit (its versions' tombstones) and forgets what the
  server held (`SyncStore.forgetServerCopies`: no own preview named again, original asked about again).
- **One-off clean-up** (`SyncEngine`, meta `sync.cleanup` = 1 once done): the first sync after updating remembers the
  live photo records it holds a row for but no photo (`ServerLibrary.prepareCleanup`), pulls the feed from the start
  (`SyncStore.applyOne` places again a record whose photo or version is missing even at the same rev), then after the
  lookups: merges Server photos into folder photos, drops unedited duplicate Server photos of one main file, tombstones
  a remembered record placed again as another id of a local photo (versions pushed again under the kept id), and
  tombstones a remembered record whose photo has no edit and nothing on the server. Another device's aliases are never
  touched (two ids for one file are legitimate across devices). Counts only in the log (`clean-up: CleanupReport(...)`).

Tests: `ServerViewTest` (JVM, two devices, `FakeSyncServer`): metadata-only records unlisted; RAW + JPEG with only the
RAW backed up (and the provider never asks for the JPEG); JPEG-only on the server; RAW-only, JPEG-only and hand-off
photos listed once their original lands; folder removal keeping and deleting; a tombstone releasing a preview; adding
a folder again and on another device; delete from server; a server before 0.5.3; the clean-up and its idempotence. Go:
`TestDeletingAnOriginal`, `TestDeletingAnOriginalNeverTouchesNasFiles`, `TestATombstoneReleasesItsPreview`.
Instrumented: `ServerViewFlowTest` against the real server.

## Consequences

- The grid now has one cell per version (a photo with a conflict copy has two); selection stays per photo.
- A conflict that arrives while a slider is held is not shown until the next sync after that commit: the server then
  turns it into a copy.
- Originals are never garbage collected on the server (ADR 0017); the app never deletes a backup. Smart previews this
  device made are dropped when no longer needed (amendment FIX/P).
- Encryption of originals at rest is still the backup volume's job.
- The x86_64 emulator's host GL stack crashes on the editor's GPU tests (`RenderThread` SIGSEGV in
  `qemu-system-x86_64-headless` with `swiftshader_indirect`; with `-gpu host` the CPU threads hang and it segfaults
  later in the full suite). The same tests pass on the phone; GPU-heavy suites run on a device.
