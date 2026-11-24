# ADR 0018: Sync in the app

Status: accepted (MS/2). Date: 2026-11-11.

## Context

ADR 0017 defines the `reveal-sync` protocol and server (since SRV/M the server is Hoard's `hoard-server` and the protocol of record is Hoard's ADR 0013; same `/v1`). `docs/plan/sync.md` ("App side") asks the app for: pairing and a
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
- Instrumented, `:app` `SyncFlowTest` against the real server (`hoard-server`, the pinned image) run locally (see CLAUDE.md "Sync (app)"): pair,
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

## Amendment 2026-11-12 (FIX/W, 0.5.4): orientation, lens default, conflict chooser, hand-off backup, storage

Owner reports on 0.5.3 (phone and tablet paired; live sync works).

- **Stretched Server photos.** `photo.data` carried `width` / `height` (the stored, sensor size) but no orientation, so
  another device filed a portrait shot with `orientation = 1` and `metadata_state = READ` (a capture time was there):
  the header was never read and the viewer, which takes the full size from the photo when the camera JPEG is not on
  the server, stretched the upright image over a landscape size. Now `photo.data.orientation` (the main file's EXIF
  1..8, sent only once the header is read) is filed with Server photos and updated by later records; a record without
  it (0.5.3 and older) leaves the photo `PENDING`, so its header is read by range like a NAS photo's. Database v11
  sets every Server photo `PENDING` once (only `metadata_state`, which no trigger watches), and the first sync after
  updating pushes this device's rotated photos again after its pull (`SyncEngine.KEY_ORIENTATION`), so the other
  devices learn it. The viewer also reconciles: `DisplayImage.of` / `UprightSize.match` turns a full size whose
  orientation disagrees with the decoded upright image when the swap matches it (3 % tolerance).
- **Conflicts nobody made.** Cause (reproduced in `SyncPolishTest`): opening a photo commits the lens profile default
  (ADR 0015 "New photos"), a history step; the version then counted as edited, so it was pushed under its own id and an
  incoming edit of the same photo could no longer adopt it (`isUntouched` wanted no history): a second version on both
  devices, or, when the step landed on a version both knew, a conflict copy. Decision: **the automatic default never
  makes a version an edit for sync.** A version whose committed recipe and every history step are unedited
  (`Recipe.isUnedited`: default or exactly the new-photo lens default) and that is not a copy is never pushed (any rev)
  and is adopted by an incoming version like an untouched one. The editor commits the default with
  `HistoryOp.Commit(onlyIfEmpty = true)`: it lands only on a still empty stored history, so an edit sync applied while
  the editor was opening wins (the open editor reloads it through `RemoteEdits`). An undo past a real edit keeps that
  step, so it never reads as automatic. Consequence: undoing the lens step alone on a never-edited photo is not synced
  (each device applies its own default anyway).
- **Conflict chooser.** Tapping a conflict copy (or "Resolve conflict" in the viewer of a photo that has one) opens
  `ConflictSheet`: the main version and the copy side by side, each with its own edited thumbnail, the device and time
  of its last change, and "Keep this one". `versions.edit_device` / `edited_at` (database v11; "" = this device, a
  device name for an applied record, null = unknown for older rows) record who changed a version last
  (`EditRepository.store` for local edits, `SyncStore.applyVersion` for pulled ones). `SyncStore.keep`: keeping the
  main version deletes the copy (its tombstone, as "Delete conflict copies"); keeping the copy copies its recipe,
  history and cursor onto the main version as this device's change, then deletes the copy, so every device ends with
  one version holding the kept edit.
- **Hand-off backup.** "Photos opened from other apps" acted like a folder with "Everything" and was never asked.
  Settings -> Sync now lists it with the folders: "Only photos you edit" (default) or "Everything"
  (`HandoffBackup`: `meta` `sync.handoffBackup` plus the hand-off source's `sources.backup`, set when the source is
  made). Database v11 moves existing installs to "Only photos you edit"; originals already uploaded stay (deleting from
  the server is manual).
- **Storage** (Settings -> Storage, `AppStorage`, `CacheBudget` in `:core:data/storage`): usage by kind (thumbnails,
  server files = range blocks + server previews, photos from other apps = hand-off local previews, other cached files
  and previews being uploaded, library and edits = the database), "Clear cache" and a maximum cache size (500 MB to
  10 GB, default 2 GB). Thumbnails and the two server caches are `CacheArea`s: each caps itself at the maximum on its
  own writes and asks for a global least-recently-used trim across all of them (at most every 30 s). "Clear cache"
  empties the areas and other cached files older than 10 minutes (an export in progress stays), and deletes a hand-off
  preview only when its photo is gone or the server holds the original: a preview that is a photo's only readable
  copy is kept and the screen says how much. Never the database, never an edit. The screen explains that Reveal never
  copies or moves photos. No "free up space by deleting originals" (owner's decision).

Tests: `SyncPolishTest` (JVM, two devices, `FakeSyncServer`): orientation filed, a record without it read again and
fixed by a later record, the one-off re-push; edit on one device + open on the other = one version, no copy; both
only opened = nothing pushed, later edit adopted; the default dropped on a non-empty history; the chooser's sides, keeping
either side on both devices. `AppStorageTest` (LRU across areas, usage, what clear keeps, `UprightSize`),
`EditRepositoryTest`, `HandoffRepositoryTest`, `RevealDatabaseMigrationTest` (10 -> 11). Goldens
`sync/phone_conflict_chooser_*`, `sync/tablet_conflict_chooser_dark`, `sync/*_storage_*`, `sync/phone_paired_*`,
`export/phone_settings_light`. Instrumented: `dev.hfps.reveal.sync.ConflictChooserTest`.

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

## Amendment FIX/L (0.5.5): one library home

The library no longer has a Server entry or scope: the home lists this device's photos and the Server photos that
open (`SERVER_OPENABLE_SQL`), one cell per photo, and "Only on the server" is a choice of the Source filter (ADR 0006
"Filters"). While paired, the Sync filter splits the photos by whether the server holds the original of any file of
the shot (`photo_cloud.original_on_server` or a file's `photo_files.on_server`; Server photos count as synced) and the
grid shows a pending badge (`CloudState.UPLOADING`, `PhotoQuerySql.UPLOADING_SQL`) on a photo whose original or smart
preview upload still waits in `sync_uploads`. "Delete from server" applies to the selected Server photos of any
filtered grid. The library's top bar carries a compact sync status (`SyncBadge.of(paired, SyncStatus)`: not paired,
synced, syncing, uploading N, N waiting, offline, problem) that opens the Sync screen; "Manage folders" sets a
folder's backup mode (FIX/V's choice) and removes it with FIX/V's delete-from-server question.


## Amendment R0.8: culling through the server's culling records

With a server that lists `culling` (hoard-server 0.7.0+), rating, flag and label no longer travel inside `photo`
records for this device: they sync per field through the culling records, and pulled records' culling fields are
ignored (they are still written into this device's records for older Reveals). See docs/adr/0027.

## Amendment W3/C (0.9.0): a large library on the phone

Context: the owner's library grew to about 21,000 photos the server indexes from the NAS (0.8.0, Galaxy S24 Ultra).
Opening Reveal sometimes "crashed": system_server logged dozens of `#openFile/#open/#close might be slow` per second,
the storage service (vold, StorageManagerService) blocked 75 s and the watchdog restarted system_server; Hoard's
FileProvider logged `HoardHandoff: open <name>` for hand-off files. Re-opening a Server photo after editing it took as
long as the first time. Reveal 0.9.0 ships with Hoard 2.0.1 (its `busy:` answer, Hoard ADR 0010).

### What caused the burst

Reproduced with a local hoard-server (pinned 0.9.0) whose synthetic NAS holds 21,000 JPEGs, 300 of them also handed
over through the debug provider, on the phone (0.8.0 debug build, 60 s on the first screen after a cold start):

- **Every grid thumbnail of a Server photo and every Server header was an `openProxyFileDescriptor`.** `ServerTree`
  opened `content://<app>.sync/display/…` through the ContentResolver; `ServerFilesProvider` answered with a proxy
  descriptor over the range cache, and each proxy is an **appfuse mount through vold** (`/mnt/appfuse/<uid>_<n>`,
  mounted and unmounted per open). The first sync of every start (and one every 30 s in the foreground) also read 300
  Server photos' headers this way (`readServerHeaders`, 4 at a time). Measured: 276 appfuse mounts in 60 s (peak 14/s,
  in 42 of the 60 seconds), 1,452 system_server watchdog pauses `#openFile/#open/#close might be slow` (peak 57/s) and
  685 range GETs of originals, with nobody touching the phone. Hoard's own SMB hand-offs go through the same appfuse
  path on its side (Hoard ADR 0010).
- **Hand-offs were opened in bulk on the app's own schedule.** `LibraryViewModel` re-checked every file marked
  unreadable on every new grid query (`recheckAccess`, rate-limited to 30 s), `refresh()` at start and
  `onResumed`/`onGalleryAccessResult` opened **every** hand-off document (`checkHandoffs`, one after the other), and
  the grid's thumbnails of hand-offs not yet cached opened their URIs: each of those is a `HoardHandoff: open` on an
  SMB-backed file.

### Start-up I/O (FIX/C)

- **Nothing reads files before the first frame.** `AppStart` (`:core:data/storage`): `MainActivity` reports its first
  frame; the folders' walk, the access check, the sync jobs (`SyncController`) and the thumbnail prefetch wait for it
  plus a short settle (2 s; 15 s without an activity).
- **One app-wide throttle for other apps' files.** `FileOpens` (`:core:data/storage`): at most 4 documents of other
  apps' providers open at once, **never more** (Hoard's own limit is 6, contract 11). Everything that opens a
  sender's document takes a slot until the input is closed: `HandoffTree` (thumbnails, checks, previews, uploads), the
  editor's opener and the export (`FileOpens.openDocument`). A caller waits for a slot as long as it takes
  (interruptible: a cancelled caller frees nothing it never held; there is no bypass after a timeout). A busy
  answer's backoff **releases the slot** while it waits. Every open counted, `REVEAL_IO` logs opens per second while
  any happen.
- **No hold-and-wait, slots for reads only, waits that end with their caller** (fix in 0.11.0; the wait-forever cap
  shipped in 0.9.0 and 0.10.0 could lock up: four callers each holding one slot and waiting for a second, the RAW and
  JPEG of a pair, waited forever, and every later open with them). The rules, in `FileOpens`' KDoc: never wait for a
  slot while holding one (a pair is read one file after the other, each closed before the next opens:
  `FirstImageLoader.loadEach`, the thumbnail loader, `PreviewMaking.display` takes openers, the export reads the
  pair's EXIF before the main file); hold a slot for the read only (`EditedThumbnails.decode` opens, decodes and
  closes, then `render` does the GPU work, the lock queue, AI masks and fills with no file open; sync's chunked upload
  of an original is the one long holder, one at a time, nothing inside it waits for another slot); never wait for a
  slot inside a database transaction. The queue is first come, first served; a waiter keeps its place while it polls
  (50 ms) for its coroutine's cancellation (`FileOpens.io(tag)` / `waits(tag)`: a `ThreadContextElement` carrying the
  job) or ends on an interrupt, taking and freeing nothing. A wait over 2 minutes logs `REVEAL_IO` with each holder's
  tag, document and age (again every 2 minutes) and keeps waiting: never a bypass. An input returned across a context switch goes
  through `openedIn` (a `withContext` cancelled while its block ran throws at its exit and would drop the input and its
  slot), and reads are wrapped in `readCatching`, which never swallows a cancelled wait.
- **A busy sender is waited out, never marked unreadable** (Hoard ADR 0010): a `FileNotFoundException` whose message
  starts exactly with `busy:` (`PROVIDER_BUSY_PREFIX`) is retried with 250 ms doubling to 4 s, giving up after about
  30 s as a transient failure (`ProviderBusyException`); `isAccessError` is false for it, so `unreadable_at` is never
  set by it. Any other `FileNotFoundException` keeps its meaning. `HandoffTree`, the editor's opener and the export use
  it.
- **The access check is one paced background pass** (`LibraryRepository.scheduleAccessCheck`, ADR 0011): after the
  app settled, the hand-off documents and the files marked unreadable are checked one at a time (≥ 100 ms apart, in a
  `FileOpens` slot). Skipped: files the server holds (they are shown and edited from the server's copy), files checked
  in the last 6 h (`photo_files.checked_at`, DB v14) unless a gallery permission changed (`again`), and documents this
  process holds an explicit read grant on (`readGrants`: persisted grants, or `checkUriPermission` for the sender's
  temporary one: no open; Hoard's URIs are persistable since Hoard 1.10.0). Never per grid query. The "Try again"
  action and the viewer still check the photos the user looks at, at once. Each pass logs one line: files, granted,
  opened, unreadable, busy, skipped (server, recent), ms.
- **Server headers come from the items** (Hoard ADR 0014): with `items`, `readServerHeaders` asks the item lookup
  (≤ 1,000 fingerprints per request, 5,000 photos per sync) for capture time, offset, size and orientation
  (`ServerHeaders`, `LibraryIndexer.applyServerHeaders`, untracked); no file is opened. Older servers keep the range
  reads.

### Hand-offs the server holds become Server photos

A hand-off photo whose main file the server holds (`photo_files.on_server`, from the lookups) is **adopted by the
Server source in place** (`SyncStore.adoptHandoffs`, after every sync, 100 per transaction, resumable): the same row
keeps its uuid, versions, edits, history, culling and `handoff_at` (it stays in "Opened from other apps": the filter
is `handoff_at IS NOT NULL` again, Server photos included); its files take the server's document ids
(`ServerTree.documentId`, relative path `handoff/<fingerprint>/<name>`), so the grid, the viewer, the editor and the
export read the server's copy and never open the sender's URI to show it. Quiet: nothing changes for other devices.
A later share of the same file opens that Server photo (`HandoffRepository`: a Server photo is reusable like a folder
photo). The sidecar link goes with the move: a Server photo's culling rides the server and no sidecar is written for
it (docs/adr/0011 "Amendment W3/H"), so the sender's URI is never opened again. The same call serves photos handed
over by server reference (W3/H).

Review fixes (PR #58): adoption is **per photo** (one transaction each, outside the sync's critical path: a failure
is logged and the photo marked in `meta` `sync.adoptFailed.<id>`, skipped for 6 h, never blocking the others or the
sync); only the files **the server holds** move (a RAW+JPEG hand-off with only the RAW backed up keeps its JPEG, and
every file without a fingerprint, as it was); the same fingerprint and name twice gets a unique path
(`handoff/<fp>/<fileId>/<name>`). Adoption never makes a hand-off deletable: a photo record deleted on the server
only forgets the server's copies of an adopted photo (it stays with its edits and culling, its files read as
unavailable until the user forgets it), the NAS feed leaves an adopted file row alone (a later rename or removal
cannot empty the photo), and the empty-photo sweep spares every photo with `handoff_at`. A hand-off of a file already
held by several Server photos opens the adopted or edited one first. A Server photo the item lookup missed stays
`PENDING` (`meta` `sync.headerMiss.<id>`, asked again after 30 min), never `FAILED`. Whole-original downloads
(`ServerFiles.keep`) run one at a time with at most 4 waiting; a bigger export reads the rest by range.

### Library thumbnails

For photos the server holds (the Server source and hand-offs), the grid shows the server's thumbnail (Hoard ADR 0015)
from **the library thumbnails** (`:core:sync/thumbs/LibraryThumbs`, `server_thumbs`, DB v14), never a range read of
the original and never a ContentResolver round trip per cell (`ThumbnailRepository` asks the `ServerThumbs` binding;
`ServerThumb.Image | Later | NotServed`: only "not served" falls back to reading the file):

- The grid's cells are batched (40 ms): one item lookup (fingerprint -> item, ≤ 1,000), one warm request
  (`POST /v1/items/previews`, ≤ 500, giving each ready thumb's `v`), then the immutable `…/preview?size=thumb&
  format=webp&v=` GETs, 4 at a time; a pending thumb is asked again twice, 2 s apart.
- Stored small: re-encoded at a long edge of 384 px, WebP quality 70 (cells are square and centre-cropped, so a 3:2
  photo keeps a 256 px short edge for the phone's 360 px cells). On 180 crops of the CC0 camera samples: the server's
  512 px WebP q70 thumb is 16.0 KB on average, the stored one 9.3 KB (256 px: 5.0 KB, 320 px: 7.0 KB); Hoard's bench
  photos run about 1.7 x larger (27 KB at 512 px), so about 16 KB, **about 330 MB for 21,000 photos**.
- Kept in `no_backup/library-thumbs` (the system never clears it), as its own `CacheArea`
  (`StorageKind.LIBRARY_THUMBNAILS`), evicted **last** (`CacheTier.LIBRARY`; the budget now evicts by tier, then by
  last use).
- **The whole library** is prefetched in the background (`LibraryThumbsWorker`): unmetered network and storage not low,
  plus charging for one request and the device idle for the other (and every 6 h while charging); newest shot first, 500
  per round, resumable (every result is a row: ready, pending asked again after 30 min, unavailable after 7 days),
  cancelled when a constraint ends, stopping at 90 % of the cache budget. Settings -> Storage: "Keep thumbnails of the
  whole library" (default on; off cancels the job) with the count and size, and "Clear library thumbnails".
- Edits: an edit made on another device (`versions.edit_device` set and not this device) shows the server's preview of
  that version (Hoard ADR 0020, `…/preview?edit=<version uuid>`) instead of downloading the original to render it;
  this device's edits are rendered here as before.

### Keeping what was just opened or edited

Why a Server photo's reopen after an edit was slow: in the lab its blocks were all still cached (24.9 MB of 24.9 MB),
yet the reopen took 3.5 s (the first open 6.3 s) with a 1.4 s preview stage both times: every read went through the
proxy descriptor (appfuse, a FUSE round trip per read, one block file opened per read). On the owner's phone the budget
can also evict it (not reproduced in the lab): the 512 px thumbnails of a 21,000-photo grid (about 50 KB each, about
1 GB) share the 2 GB cache with the originals' blocks, and the LRU drops an original opened before the last scroll.

- **Kept originals** (`ServerFiles.keep`, `cache/sync-kept`, `StorageKind.ORIGINALS_KEPT`): when the editor or the
  export opens a Server original, the rest is downloaded in the background (one at a time) and assembled into one file
  (`RangeCache.assemble`, the blocks then dropped); the provider serves a kept original as a plain file descriptor
  (no appfuse), and reads in progress switch to it.
- **Pinned**: the files of the 50 photos opened most recently in the last 7 days (`photos.last_opened_at`, set when the
  editor opens a photo) are `CacheTier.PINNED`, in the range cache and the kept originals: evicted after every ordinary
  cache and before the library thumbnails, LRU by last open; `RangeCache.trim` drops unpinned files first.
- Server photos' grid thumbnails no longer fill the shared thumbnail cache (they live in the library thumbnails).

Settings -> Storage lists every area (library thumbnails, thumbnails, originals kept, server files, AI masks and
fills, photos from other apps, other, library and edits) with its size, and the cache budget the owner sets (500 MB to
10 GB).

### Other apps' descriptors (Hoard integration contract 11)

Hoard 2.0.1 serves at most 6 hand-off reads at once; files over 8 MiB (every RAW) are appfuse proxy descriptors that
hold a slot until Reveal closes them. Audit of every path that opens a hand-off document:

| Path | Before | Now |
| --- | --- | --- |
| Editor proxy (`PhotoOpener.open`) | open, decode the preview and the proxy, close | unchanged; busy retried |
| Editor full resolution, RAW (`NativeRawTileSource`) | decoded once into native memory, closed | unchanged |
| Editor full resolution, standard image ≤ 40 MP (`BitmapTileSource`) | decoded once, closed | unchanged |
| Editor full resolution, standard image > 40 MP (`RegionTileSource`) | **descriptor held while the editor is open** | another app's document is copied to `cache/local-copies` (open, copy, close) and the copy is read |
| Viewer zoom regions (`PhotoImages.region`, `BitmapRegionDecoder`) | **descriptor held while the page is open** (and a `FileOpens` slot) | a hand-off's rendering is copied first (`LocalCopies`), the copy is deleted with the page |
| Export (`ExportEngine.openSource`: LibRaw / full decode) | open, decode, close before the tiles render | unchanged; busy retried |
| Grid thumbnails, viewer display, hand-off previews (`SmartPreviewMaker`), fingerprints, headers, sidecars, the access check | open, read, close | unchanged; through `HandoffTree` (≤ 4 at once, busy retried) |
| Server originals (`RangeCache`, provider proxies) | Reveal's own provider, not a sender's | unchanged (kept originals open as plain files) |

`HandoffDescriptorsTest` (debug provider counting its open descriptors and answering `busy:` beyond 6) opens a
handed-over RAW in the editor (preview, proxy, full resolution) and exports it: never more than 4 open at once, none
left open while the editor shows the photo or after the export.

### Measurements (W3/C)

Galaxy S24 Ultra, debug builds, local hoard-server 0.9.0 (loopback on the home Linux machine, `ssh -R` + `adb reverse`), 21,000
synthetic NAS JPEGs + 6 CC0 ZV-E10 RAW copies, 300 of the JPEGs also handed over through the debug provider.

| | 0.8.0 | 0.9.0 (W3/C) |
| --- | --- | --- |
| Cold start, 60 s on the first screen: appfuse mounts | 276 (peak 14/s) | 0 |
| … system_server watchdog "might be slow" pauses | 1,452 (peak 57/s) | 0 |
| … range GETs of originals | 685 | 0 |
| … `FileOpens` opens (`LargeLibraryFlowTest#startUpDoesNotBurst`) | — | 0 |
| Cold start, then 15 flings in 30 s: appfuse mounts / watchdog pauses | 243 / 1,226 | not measured again (the grid reads the library thumbnails, no original) |
| `am start -W` TotalTime / first grid frame | 690 ms / 1,054 ms | 705 ms / 1,136 ms (with the prefetch running) |
| Server headers per sync | 300 range reads | 5,000 photos per item lookup batch, 21,006 in two syncs, no file opened |
| Open a 24 MP Server RAW in the editor (first time) | 6,348 ms (preview 1,397 ms) | 4,376 ms (preview 336 ms) |
| Reopen it after an edit and 90 s idle | 3,528 ms (preview 1,407 ms; blocks all cached) | **1,349 ms** (preview 199 ms; kept whole: 24.9 MB) |
| Offline grid (server unreachable): newest 120 Server photos | — | 120 of 120 from the library thumbnails, 2.4 s in all (sequential) |
| Library thumbnails stored by the prefetch | — | 17,059 (the server had made 17,468 so far) in 78 MB; the rest pending, asked again in 30 min |
| Hand-off RAW opened (preview, proxy, full resolution) and exported: descriptors open at once / left open (`HandoffDescriptorsTest`) | — | 2 / 0 |

The synthetic JPEGs compress far better than photos (4.6 KB per stored thumbnail); see "Library thumbnails" for the
size on real camera images (about 16 KB, about 330 MB for 21,000).

## Amendment W5 (0.9.0): photo records for edited photos

Context: the owner edited a NAS photo (a Server photo made from the NAS feed) on the phone and the tablet never showed
the edit. Present since MS/2:

- `SyncStore.applyNas` makes a NAS file's photo quietly (no `photo` record) with a random uuid, so the same NAS file has
  a different id on every device and none reaches the server unless the photo's culling changes. Hand-off photos filed
  quietly (server references, ADR 0011 "Amendment W3/H") are the same.
- An edit marks only the version: `versionChange` named `photo.uuid` and never made sure the server knew that photo.
- `version.data` had no fingerprint and `applyVersion` (and the inbox retry) mapped a version's photo by uuid or alias
  only, so the other device parked it forever. Only photo records had the fingerprint fallback (`localPhoto`).
- In production the tablet later culled the photo (its own photo record went up), the phone pulled it and its photo
  took the tablet's id (`localPhoto` adoption), but the version on the server still named the phone's old id.

Decisions:

- **The photo goes up before its version.** When the push batch is read, a version change whose photo has no `photo`
  record the server accepted (`rev > 0`) brings that photo's change into the same batch, ahead of it (`photoFirst`;
  the record is made dirty, or created, even for a photo made quietly). Another device maps the photo by fingerprint
  (`localPhoto`: adopts the id when its own never went up, else an alias) and the version applies. An adopting photo
  keeps its old id as an alias, so records that named it (a version pushed before the adoption) still find it; the
  alias of an adoption is never a "survivor" of a photo tombstone (only ids with a live record are). A photo whose main
  file has no fingerprint yet cannot be described: its version is **held** (not dirty, its uuid listed in
  `meta` `sync.heldVersions` = `SyncStore.KEY_HELD_VERSIONS`; **its `rev` stays the server's**) and `releaseHeld`
  marks it dirty again (same revision, same edit time) once the photo is known to the server: its record accepted (a
  push), a pulled photo record placed on it, or a pulled version that found it by its main file (`applyVersion` ->
  `photoByFingerprint`, by the id it took or the alias it got). Keeping the revision matters for a version the server
  has already (one 0.8 pushed without its photo, re-marked by the repair): released as a create (`baseRev` 0) with the
  same `modifiedAt` and device, the server's state would win and keep ours as a conflict copy. A held version counts as
  unpushed work for the pull (its server record is parked, as for a dirty one). The first fix round overloaded `rev`
  with -1 (`SyncRecordEntity.HELD_REV`); `SyncDao.releaseHeldVersions` still releases such rows (rev 0). **The batch stays within the server's
  `maxPushChanges`** (500; hoard-server refuses a longer push as a whole with `422 invalid_change` and no index, which
  no retry can get past): settings, records and the photos added ahead of their versions all count; a version whose
  photo does not fit with it stays dirty and its photo (marked dirty) leads the next round. A photo record that is a tombstone
  (deleted from the server on purpose) is never brought back this way; one the server refuses (`invalid_change`) is
  not offered again by this process (the version then goes up alone, as before), and its held versions are released
  at once (`SyncStore.refused` -> `releaseHeld`: dirty, so they go up alone in the same run instead of waiting for a
  record that never comes). Every read of `sync.heldVersions` drops uuids whose version no longer exists (deleted, or
  its photo forgotten).
- **A version finds its photo by fingerprint** (additive, `/v1` unchanged): `version.data.photoFingerprint` = the
  photo's main-file fingerprint (older apps ignore it; records without it apply as before). `applyVersion`, and so the
  inbox retry: uuid → alias → the version's own local photo (a version already here) → the photo whose main file has
  that fingerprint (`SyncDao.photosWithMainFile`), by the same rule as a photo record (one helper,
  `photoByFingerprint`): the photo takes the record's photo id when its own never went up (no record, or rev 0),
  else that id becomes an alias, so later records map directly. Several local photos with that main file (the same file in two folders): the oldest one that is not hidden,
  logged (`RevealSync`).
- **One-off repair** (`SyncStore.repairVersionPhotos`, meta `sync.versionPhotos` = 1): in the first sync after the
  update, **after its pull** (every local revision is then the server's latest, so pushing a version again cannot make
  another device's newer edit a conflict copy), then a push. Versions a user edited (a recipe that is not the default,
  a name, a virtual copy, a conflict copy or snapshots, `SyncDao.editedVersions`):
  - whose photo has no accepted record: the photo and the version are marked (the push sends the photo first);
  - this device's own edits (`versions.edit_device` "" or null) already on the server, not dirty and not conflict
    copies, of a photo the server knows that was not made from another device's record (a Server photo whose
    `stem_key` is its uuid): marked again with their **original** `modifiedAt` and pushed with the photo's current id
    and fingerprint. Reveal never stored which photo id a version went up with, and an adoption leaves no trace, so
    every such version goes again (a few hundred records at most, once); the server compares the data bytes, so the
    new `photoId`/`photoFingerprint` make a new revision. On the other devices the newer revision applies and the
    parked older one is dropped by the inbox retry (`applied()`: a higher local revision). Only versions this device
    is known to have edited go at once: `edit_device` "" (0.5.4+), or null (older than 0.5.4) with a history of steps
    committed here (more than one step time: a pulled record writes its whole history at one time). The others (null,
    perhaps another device's edit pulled before 0.5.4) are listed (meta `sync.versionPhotos.checks`, `uuid@rev`) and
    `SyncStore.checkRepairs` marks them on the next run, after its pull, only if still at that revision and clean: a
    version another device changed meanwhile is left alone (its new record names the photo itself).
  A first sync with a server (pairing) marks the repair done without running it: everything just went up.
- **App version on the server:** hoard-server's `PATCH /v1/device` accepts `app`, `manufacturer`, `model` and
  `features` only (Hoard ADR 0013 "Physical devices", ADR 0017 "Client calls" 2; `server/internal/server/
  device_api.go`); the app version is only sent at pairing (`POST /v1/pair` `appVersion`). Nothing changes in Reveal:
  refreshing it needs a server change first (Hoard ADR 0012: additions are made in Hoard).

### W5 (f): pairing again with the same server

Context: the owner moved the server from a tunnel to Tailscale and scanned a new pairing QR on both devices. Each
`POST /v1/pair` made a new device on the same server; the old ones stayed registered (live), the new ones got a
physical device of their own and lost the grouping with Hoard on the same phone (Hoard ADR 0017). And `pair()`
forgot everything first (`forgetLocked`): the first sync then marked every photo and version at rev 0
(`startTracking`), parked the server's records behind them and pushed them as conflicts, so the server kept the
losing side of **every edited version as a conflict copy** (reproduced in `RePairTest`).

What the protocol allows (hoard-server `main`, Hoard ADR 0013/0017, `openapi.yaml`, `internal/server/api.go`): there
is **no self-revoke** under `/v1` (no `DELETE /v1/device`); revoking is admin-only (`/admin`, `Store.RevokeDevice`).
`candidates` never list a physical device that already has a live device of this app id, and a claim there is
`409 same_app`. So a phone whose old Reveal is still live cannot be rejoined by the new one, with or without a user
step, until the old device is revoked on the admin page. Nothing changes on the server.

Decisions (`SyncRepository.pair`):

- **Same server, key still valid: no new device.** Before calling `/v1/pair`, when this installation is paired and
  the link's `server=` is the account's server id (hoard-server prints it in every pairing code), `GET /v1/server` at
  the link's address with the current key. The key is never sent to an address the link does not prove to be the
  paired server: a link without `server=`, or naming another server, pairs normally (`/v1` has no unauthenticated way
  to ask an address which server it is: `/v1/health` names none and `/v1/server` needs a key); the same `serverId`
  and `deviceId` -> only the address changes (`SyncAccount.url`). Device id, key, physical device (Hoard's grouping),
  cursors and records stay; the pairing token is left unused. This is the owner's case from now on.
  Hardening (fix rounds 2 and 3): even with a matching `server=` the key goes **on its own** only to the host the
  account already uses (a port or scheme change there: `KeyReuse.AUTO`, `SyncRepository.quietReuse`, hosts compared
  case-insensitively; final fix: never from `https` down to an address that is not `keySafe`, which asks with the
  cleartext warning). Any other host asks first, whatever its scheme (a `server=` in a code is not a secret: anyone
  shown the QR could put it in a link to their own host), naming the host (`SyncRepository.keyReuseQuestion` ->
  `KeyReuseQuestion(host, protected)` -> `PairingState.ConfirmKeyReuse`, "Same server at <host>? Reveal will reuse
  this device's key"). `account/ServerAddresses.keySafe` (`https`, loopback, Tailscale `100.64.0.0/10` and `*.ts.net`,
  LAN `10/8`, `172.16/12`, `192.168/16`, `fd00::/8`, link-local `169.254/16`, `fe80::/10`; literal addresses only,
  never a DNS lookup) now only sets the wording: an address that is neither adds "the key would travel unencrypted".
  "Same server" = `pair(link, KeyReuse.CONFIRMED)`, "New device" = `KeyReuse.DECLINED`, a normal pairing (a new device
  keeping the sync state, as for a refused key). A paired Sync screen has "Pair with a new code" for this (goldens
  `phone_paired_*`, `phone_key_reuse_dark`).
- **Same server, key refused** (`401`: revoked or lost): pair a new device but **keep the library's sync state**
  (records, revisions, cursors, aliases, inbox, culling, Server source); only the device report and the stored
  physical id are forgotten (`SyncEngine.forgetDevice`), so the new device declares `culling` on its next sync. When
  the previous device was grouped and that physical device is among the pair answer's `candidates` (the old Reveal is
  no longer live there), the new device claims it at once: this installation's user already said "same phone". Else
  the usual model-match question is offered.
- **First sync after an unpair** (the state is gone on purpose): a pulled version whose local record is rev 0 and dirty
  (marked by `startTracking`) and whose edit equals the server's (recipe, name, position, snapshot ids) is not a
  conflict (`SyncStore.sameEdit`): the local record takes the server's revision and the record is **not applied**
  (this device's history and author stay; the record was this installation's own, under its previous device). It goes
  up again on top of that revision unless the server's record also names the photo by its current id and main-file
  fingerprint: the W5 repair skips first syncs, so a 0.8 record, or one naming the id the photo had before an
  adoption, would otherwise never be repaired.
- **Another server while paired (W5 (g), fix round 3): never forgotten silently.** "Pair with a new code" used to
  reach `forgetLocked()` for any code of another server, unpairing and wiping the sync state, the Server source and
  the edits of its NAS photos without a word. Now, while paired:
  - a code whose `server=` names another server is refused before anything is sent (`PairProblem.PAIRED_ELSEWHERE`,
    the token unused); the message offers "Switch server", which opens the usual Unpair confirmation and, confirmed,
    pairs with that code first and only then forgets the paired server (`SyncSettingsViewModel.switchServer` ->
    `SyncRepository.pairSwitching`, like `switchServer`); a failed pairing keeps the account and shows the error;
  - a code without `server=` (older hoard-server) can only be judged after `POST /v1/pair` (`/v1` has no
    unauthenticated way to name a server): when the answer's `serverId` is not the account's, nothing local changes;
    the new pairing (its key) waits **in memory only** (`SyncRepository.PendingSwitch`) and `ServerSwitchException`
    asks "This code is for another server" (`PairingState.ConfirmSwitch`, golden `phone_switch_server_dark`).
    "Switch" = `switchServer()`: Unpair (`accounts.clear()` + `forgetLocked()`), then the pairing just made is saved
    (its token is spent, so it is not redeemed again); refused when the account changed meanwhile. "Cancel" (or
    dismissing, or leaving the screen: `onCleared`) = `cancelSwitch()`: the key is dropped, the paired account and its
    state stay as they were. The other server has registered a device by then and `/v1` has no self-revoke: the
    dialog says so ("<server> already registered this device as <name>; remove it from that server's Devices page").
    Any later `pair()` or `unpair()` also drops a waiting switch.
  - Unpaired, any code pairs as before (everything an earlier server knew is forgotten).

The production state (both devices re-paired as new devices, old ones live in the Hoard groups): revoke the two old
Reveal devices on the admin page, then Settings -> Sync -> This device offers Hoard's phone again (claim). The conflict
copies the re-pair made are resolved with the conflict chooser (FIX/W).

Tests: `RePairTest` (a new address with a valid key keeps the device and its phone, no token used; a link without
`server=` or naming another server never receives the key (a second fake server records `Authorization`); unpair +
pair after an adoption re-pushes the version naming the current id and fingerprint; a revoked key -> a
new device that keeps the records, rejoins Hoard's phone, reports itself, and still gets the W5 repair; an old device
still live -> no forced claim; unpair + pair -> no conflict copy; a public http address asks, a declined reuse
never sends the key and keeps the sync state; fix round 3: another host asks whatever the scheme, the same host on
another port or scheme does not, AUTO never sends the key to another host; while paired a code naming another server
is refused unsent and "Switch server" (unpair, pair) works; final fix: `pairSwitching` with a refused token keeps
the account and the records, then switches; https -> public http on the same host asks and AUTO never sends the key; a code without `server=` for another server keeps
everything on Cancel and keeps syncing, and Switch forgets the state and keeps the new pairing),
`ServerAddressesTest` (the classification), `SyncApiTest` (a used token is refused once unpaired),
`VersionPhotoSyncTest` (a refused photo releases its held versions; deleted held versions leave the list).

Tests: `VersionPhotoSyncTest` (FakeSyncServer, two devices: an unculled NAS photo, a hand-off never culled against the
other device's folder photo, the exact production sequence, the repair of a photo the server never had, a version
before its photo record, the ambiguity, a held version, a held version the server already has (real `baseRev`, no conflict copy), a pulled
version releasing its photo's held versions, a tombstoned photo), `RealServerVersionPhotoTest` (the pinned
hoard-server: the NAS case and the production sequence; `REVEAL_VERSION_DIR`, `REVEAL_VERSION_LINKS`).

### W5 recovery: versions orphaned by an old re-pair

What happened in production (2026-11-15): on builds before W5 the phone (86f9c052) and the tablet (36f8f112) each
edited unculled NAS photos; each version went up alone, naming the author's random photo id, without a photo record
or a `photoFingerprint`; the other device pushed its own photo record for the same file. Both then paired again on
the old build, whose `forget()` ran `deleteServerSource()`: the edited Server photos and **their versions were
deleted locally** (cascade), and the first sync under the new ids (e75cbaf1, f004f152) pulled the four version records
and parked them (their photo id unknown everywhere, nothing in the record names the file). The admin revoked the old
ids. The W5 repair walks local versions, so it could never see them; nothing in `/v1` maps a version id to a photo,
**except Hoard's edited previews** (Hoard ADR 0020): `item_edits` held a row for each of the four.

Decisions (`SyncEngine.recoverParked`, `SyncStore.parkedOrphans` / `recover` / `proveRecovered`):

- **Every device recovers by itself.** A parked version whose photo cannot be found (no local version, no
  `photoFingerprint`, not a copy) is looked for in the edited previews: `POST /v1/items/lookup` answers each item with
  its `edits` and the `matches` of the fingerprints asked, so an edit named after the version gives its item, and the
  matched member gives a local photo. The fingerprint found is used exactly as a record's `photoFingerprint` would be
  (`applyVersion` -> `photoByFingerprint`: alias of the record's photo id, or adoption of it when the local photo was
  never on the server), and the inbox is retried. The edit appears on every device, with no push.
- **Cost.** `/v1` has no lookup by version id (`GET /v1/items/{id}/edits/{versionId}` needs the item; the items feed
  carries edits but only from a cursor, and Reveal's culling feed asks for `include=culling` only), so the lookup goes
  over the main-file fingerprints of the photos whose records the server may never have had (Server photos and
  hand-offs, newest shot first, `SyncDao.recoveryFingerprints`), 5 pages of 1,000 per run, resumable
  (`sync.recovery.search`), only while such versions exist and only for those due. A whole pass that does not find a
  version is logged once and the version is looked for again after a day, then 2, 4, ... up to 30 days
  (`sync.recovery.misses`, `SyncStore.searchDue`); it stays parked meanwhile.
- **Pushed again only with proof of authorship.** A recovered version is listed (`sync.recovery.proof`: uuid, the
  server revision, the record's author) and, on every sync, pushed again on that revision (naming the photo's current
  id and main-file fingerprint) only when its author is one of this installation's own device ids (`sync.ownDevices`,
  recorded from now on at every pairing, sync and device report, never forgotten) or a device of this installation's
  physical device (`GET /v1/device`: the physical device's id, which is its first device's id, and its devices). A
  name is never proof (revoked devices are not listed, and names repeat). Without proof nothing is pushed (the alias
  is enough locally); a version whose record changed meanwhile is dropped from the list. Since the authoring
  installation is the only one with proof, two devices never push the same version (no conflict copy).
- **The repair's authorship.** `repairVersionPhotos` also counts an `edit_device` that is the shortened id of one of
  this installation's own device ids.
- **Unpairing never deletes an edit.** `forget()` keeps every Server photo with an edited version (and the Server
  source while it has photos); the versions whose current state the server was not known to hold are remembered
  (`sync.keptEdits`) and marked new by the next pairing's `startTracking` (an equal edit on the server is settled by
  `sameEdit`); after a first sync's pull, edited versions the server has no record of go up as new
  (`markUnrecordedEdits`). Other deletions of edited photos are user actions (removing a folder, "Forget").

- **Kept photos belong to their server** (verification round of d2106de). `forget()` records each kept Server photo
  and kept edit with the server it came from (`sync.keptPhotos`, `sync.keptEdits`: `uuid@serverId`; `sync.tracking`
  at the time, `?` when unknown, e.g. kept by an earlier test build). `startTracking(serverId)` takes back only the
  entries of that server (its photos are ordinary Server photos again, its kept edits are marked new); the others stay
  listed and **nothing about them goes up** while paired elsewhere: their photo and version records are cleaned, not
  pushed (`photoChange`/`versionChange`), `markUnrecordedEdits` skips them, and their culling changes stay local
  (`CullingSync`). Pushing them would leak the old NAS's file names and edits and leave dead photos on other devices.
  They show as unavailable, as before; `SyncStore.removeKeptPhotos` (with `keptPhotoCount` for a confirmation) deletes
  them quietly.
- **A local edit on top of this installation's own record.** On a first sync, a version never pushed here (rev 0,
  dirty) whose server record differs and was authored by one of this installation's devices (`ownAuthors` = the
  `ownDevices` view read before the pull, plus `sync.ownDevices`) takes the server's revision and stays dirty
  (`rebaseOwn`): the push is a clean update, not a base-0 conflict copy. Another device's record is still a conflict.
- **Recovery pass details.** Pages go by photo id (`recoveryFingerprints(limit, after)`: photos added during a pass
  come after it, nothing shifts; the state is `last photo id|uuids`). A lookup refused for good (not transient, not
  401) ends the pass like a miss (back-off). A proof entry is dropped once `GET /v1/device` was read and its author
  is neither this installation's nor a physical device it is offered to join (`candidates`): no claim offered in the
  app could prove it, so the view is not asked every sync forever. `forgetDevice` keeps the old physical device's id
  as one of this installation's (it is the old device's id when that device created it).
- **What proves a revoked old id (hoard-server).** Neither `GET /v1/device` nor `GET /v1/devices` lists revoked
  devices (`physicalDevice.devices` and `candidates` are live devices only), and a revoked device keeps its
  `physical_id`. A physical device's id is its first device's id, so claiming into the old group makes
  `physicalDevice.id` equal the old Reveal id when the old Reveal created that group; the group is offered as a
  candidate only while it holds a live device of another app on the same platform (Hoard). A group whose only device
  was the revoked Reveal is not offered (only the admin page's Move reaches it), and one Hoard created has Hoard's id:
  neither proves, and those versions stay applied locally, unpushed.

What the production devices do after the update: first and second sync, the four are applied locally on both
devices, nothing is pushed. After "Same phone/tablet?" (Settings -> Sync -> This device, claiming the old physical
device that holds Hoard), the next sync of the phone pushes again a09ff2ba and a615a71f, the tablet's c33fcf7f and
6aeb6d71, each on the server's revision, so the records name the current ids and fingerprints.

Hoard's rule (`internal/store/records.go`, `applyOne`): a push whose `baseRev` equals the record's revision is applied
whatever device sends it; revoking a device changes none of its records.

Tests: `VersionPhotoSyncTest` (the four-orphan replay in both stages, no preview found -> parked and looked for once a
day at most then found when the preview appears, unpairing keeps edited Server photos, a refused lookup backs off,
paging by photo id), `KeptServerPhotosTest` (another server never hears of kept photos, their own server gets them
again, removal, rebase onto an own record vs another device's conflict), `RealServerRecoveryTest`
(pinned hoard-server: one orphan per device, edited previews uploaded, revoke and claim through the admin API and
`/v1/device/claim`; `REVEAL_RECOVERY_DIR`).

## Amendment R1 (0.10.0): Hoard's trash and one backup per phone

Hoard 2.3+ moves rejects into a trash the server sees (Hoard ADR 0023, hoard-server 0.11.0, feature `trash`) and backs
up the phone itself into a per-phone mirror (Hoard ADR 0024, hoard-server 0.12.0, feature `backup`). Reveal follows
both. Every addition is used only when `GET /v1/server` lists its feature; against older servers Reveal behaves as
0.9.0, except for the purge fix below. Database v16 (auto-migration): `photo_files.trashed_at` / `vanished_at`,
`photos.forgotten_at`, `photo_cloud.lost_at` / `candidate_at`, `sync_uploads.retry_at`,
`photo_files.vanish_asked_at` / `vanish_retries`.

### The in-trash state (`trash`)

- The NAS feed's `removed: true, trashed: true` keeps the Server source's file row with `trashed_at` (Reveal chose to
  keep **every** trashed photo, edited or not: it costs one row and gives the trash a filter). A Server photo with a
  trashed file and no live file the server serves (`on_server` and not trashed) is **in Hoard's trash**
  (`PhotoQuerySql.IN_TRASH_SQL`): out of the home, the Server scope, every count (`LISTED_SQL`,
  `observeServerPhotoCount`), and listed only with the Sync filter's **In Hoard's trash** (`SyncFilter.IN_TRASH`,
  token `s:IN_TRASH`, its count `FilterCounts.inTrash`; the choice shows only while something is in the trash). Its
  cell is a placeholder ("In trash"); the viewer shows a note ("restore it in Hoard") and no Edit button; it is never
  opened full size or edited, although the server still serves the bytes until a purge. Trashed files are never
  "servable" (`ServerTree.servable`).
- **Restore** (the same path and fingerprint live again) clears `trashed_at`: the photo is back with its uuid,
  versions, edits and culling. A file that comes back as a new row (a server without `trash`, a file put back) still
  links by folder + stem, as before.
- **Purge** (the row again with `removed` and no `trashed`, and any removal on a server without `trash`) deletes the
  row; when the photo is left with nothing the server serves, `original_on_server` is cleared, and a photo Reveal
  keeps (an edit, a hand-off, a record: `deleteEmptyServerPhotos` leaves it) gets `lost_at`. A lost Server photo is
  **unavailable** (`UNAVAILABLE_SQL` has a Server branch; `UnavailableReason.SERVER`): the placeholder, the sheet and
  "Remove from Reveal", never a cell that fails to open (0.9.0's broken cell). A file of it that comes back clears it.
- **Forgot, then restored**: "Remove from Reveal" now stamps `photos.forgotten_at` on the photos it hides. When a file
  of such a Server photo is live on the server again, the photo is shown again and its record goes up (`hidden` false
  on every device). Photos hidden any other way (Remove from library, another device's record) stay hidden.
- Edited previews are unchanged: a trashed item is `missing` in the lookup and a `PUT` answers `404`, so nothing is
  sent or lost; after a restore the server's edit is the current one again and nothing is uploaded twice
  (`HoardTrashTest`).
- Hoard ADR 0023 amendment S1 (hoard-server 0.11.0): a JPEG trashed alone is a trashed item of its own while its RAW
  stays live; Reveal sees only that file's row trashed, so the photo keeps its live RAW, is not "in Hoard's trash" and
  opens from the RAW. `POST /v1/items/lookup` leaves trashed items out (`missing`) without `include=trash`, while
  `POST /v1/fingerprints/lookup` and `GET /v1/originals` still find a trashed file until it is purged: `on_server`,
  `original_on_server` and the 24-hour re-lookup may keep counting a trashed file as on the server, which is true
  until the purge. With `trash`, a purge of a NAS file therefore makes this device's own copies of that content that
  it believed on the server ask again (`SyncDao.askAgain`: `on_server` and the photo's lookup reset), so a phone copy
  whose only server copy was emptied from the trash is backed up instead of being believed on the server for good.
  Without `trash` the feed's removals leave local copies as 0.9.0 did (no mass re-lookup on a first pull). Reveal never sends
  `include=trash`. Real server: `RealServerTrashTest` (a NAS file moved into `.hoard-trash/<day>/` and back, then
  purged).

### Covered folders and the 24-hour grace (`backup`)

- Each sync run reads `GET /v1/device` `physicalDevice.backup.roots` (`volume`, `path`, `types`, `active`, `since`) and
  keeps them in `meta` `sync.backup.roots` (`BackupCoverage.META_ROOTS`; removed when the server does not list
  `backup`, on unpairing and on a new device). `BackupCoverage` (`:core:data`, pure) maps a folder source of
  `com.android.externalstorage.documents` (`primary:DCIM/Camera` = volume `primary`, path `DCIM/Camera`) to a location;
  any other provider is never covered. A folder is covered by an **active** root whose volume equals it (any case) and
  whose path is equal or a prefix at `/`; a file is covered when its kind is in the root's `types` (`image` = JPEG,
  HEIF, PNG, WebP, GIF and the RAWs MediaStore lists; `raw` = every RAW, CR3 included; `sidecar`; `video`; anything
  else is never covered).
- `BlobUploader.originals`: a candidate (`backupCandidates`: backup-on folders and edited photos, the shot's main
  file, which is the RAW of a pair) whose file is covered starts a **24-hour grace** (`photo_cloud.candidate_at`, set the
  first time it is seen as a candidate, never reused from `edited_at`); `backupCandidates(limit, waitedSince)` leaves
  it out until then, so waiting candidates never starve the others. After the grace it is looked up again
  (`POST /v1/fingerprints/lookup`): found = on the server (Hoard backed it up), missing = queued. Uncovered files go at
  once, as before.
- `POST /v1/uploads` carries `"wait": true` for originals when the server lists `backup` (`claim` stays absent: Reveal's
  uploads keep claiming). `status: "in_progress"` leaves the upload pending with `retry_at` = now + `retryAfter`
  (300 s when absent): never a failure, never a second upload in parallel (`SyncDao.dueOriginals`).
- Settings › Sync: a covered folder shows "Backed up by Hoard on this phone" (read only) instead of its switch, and
  "Originals over Wi-Fi only" says it applies to what Reveal still uploads. Manage folders: the folder's menu shows
  "Backed up by Hoard" instead of the backup choice (`SourceItem.coveredByHoard`).

### `409 mirror`

`DELETE /v1/originals/{fp}` answering `409` with `details.reason = "mirror"` (`DropResult.MIRROR`) ends the queued
delete at once, keeps `on_server` true (the file is in the phone's backup) and counts it in `meta` `sync.mirrorKept`;
the library shows "N files stay on the server: they are in this phone's backup by Hoard" once, where "Delete from
server" reports its result (`SyncRepository.mirrorKept`).

### Freed-adopt and the offline rule

Hoard's "Free up space" deletes phone files it verified on the server. A folder photo whose files vanished from a walk
must never lose its edits because of that.

- `LibraryIndexer.save`: a photo whose **every** file is gone is not deleted; its files get `vanished_at` and the photo
  waits, **unlisted** (`VANISHED_SQL` in `LISTED_SQL`, the grid query and the folder counts). Paired (`meta`
  `sync.tracking`), every such photo waits; unpaired, only one with an edit (the server cannot be asked, and an
  unedited deletion needs no answer). A file listed again clears the mark. After **7 days** (`VANISHED_WAIT_MS`) a walk
  applies today's rule (deleted). A photo that loses only some files (the JPEG of a pair) is handled as before.
- `engine/FreedPhotos` (every sync run, after the lookups): without `backup` the server cannot have freed anything and
  waiting photos are deleted at once. With it, each waiting photo's item is found by fingerprint (any file) when Reveal
  has one, else by its mirror location: `GET /v1/items?folder=<rootId>&path=<dir>` (one paged query per directory
  under any root of this phone, stopped and paused ones too), the `mirror` location whose `volume` (any case) and
  `path` are the file's, confirmed by the member's size and the location's `modifiedAt` to the second (MediaStore keeps
  seconds). Folders under no root fall back to `POST /v1/items/lookup` + `GET /v1/items/{id}`. Then:
  - `gone: "freed"` → **adopted**; `gone: "deleted"`, an unknown value or no mirror location → adopted only when the
    photo carries an edit (a non-default recipe, a named or copied version, snapshots), else deleted;
  - `onDevice: true` (Hoard still sees it: the walk missed it) → it keeps waiting (the walk's bound applies) and is
    not asked about again for 6 hours (`FreedPhotos.BACKOFF_MS`): no page walk per sync;
  - no item → deleted, as before, but **only when the server fully answered**: the item lookup named nothing for its
    files or its item was read, or (a photo known only by its path) its directory's folder query was read to the end,
    or it is under no root of this phone and has no fingerprint. A refused lookup or folder query, a listing cut at
    `MAX_PAGES`, the per-pass item-read cap (`MAX_ITEM_READS`, 500), a failed adoption or a backoff keep it for a
    later pass. Every kept photo backs off (`photo_files.vanish_asked_at` / `vanish_retries`: 1 hour doubling to 6),
    and the pass reads the oldest asked first, so photos without an answer never starve the read cap.
  - Before this phone's physical device id is known (its first device report), nothing unmatched is deleted: whose
    mirror location is whose cannot be judged yet.
  - Only **this phone's** mirror locations count (its root ids, or its physical device id from `meta`
    `sync.device.physical`): another phone's location at the same volume and path is never this file.
  - The pass acts on what it read: deleting or adopting re-checks, in its transaction, that the photo still has
    exactly the vanished files it started from (`SyncStore.stillVanished`); a photo a walk listed again meanwhile is
    left alone.
- Adoption (`SyncStore.adoptFreed`) is W3/C's in place: the same row keeps its uuid, versions, edits, history and
  culling; each file the item names takes the item's fingerprint, `freed/<fingerprint>/<name>` in the Server source
  and the server's document id, `on_server` set; the photo's other files go; the photo moves to the Server source under
  its phone folder (`DCIM/Camera`, made unique on a clash), sidecar link dropped, pending uploads cancelled, original
  marked (`nas` or `backup` by the item's locations). Quiet: nothing changes for other devices. The NAS feed never
  replaces an adopted file (`keepAdopted` knows both prefixes).
- `PATCH /v1/device` `features` adds `"freed-adopt"` (next to `culling`) only when the server lists `backup` and this
  installation knows its physical device (learned from the first report): from then on Hoard may free shots that
  Reveal's records name. Declaring it before the first pass is safe: nothing is deleted without a complete answer.
- Vanished files are no backup or preview candidates (`vanished_at IS NULL`), and a file listed again loses its
  `unreadable_at` with its `vanished_at`. The 24-hour grace applies only while the file is covered: each run ends the
  wait of candidates no longer covered (`SyncDao.inGrace` / `endGrace`), e.g. when Hoard's root goes inactive.

### Item 7 and 8

`ItemLocationRecord` keeps `source: "mirror"` locations whole (`rootId`, `volume`, `path`, `modifiedAt`, `onDevice`,
`gone`); `SyncServerItems` still chooses `nas`/`backup` from the other locations (`SyncServerItemsTest`).
`FakeSyncServer` gains `trash()` (NAS feed `trashed`, `trashNasFile` / `restoreNasFile` / `purgeNasFile`) and
`backup()` (roots in `GET /v1/device`, `inProgress` for `wait`, `mirrorFingerprints` for `409 mirror`,
`addMirrorItem` with `onDevice`/`gone`, `GET /v1/items?folder=<root>&path=`). Tests: `HoardTrashTest`,
`PhoneBackupTest`, `FreedAdoptTest`, `VanishedPhotosTest`, `BackupCoverageTest`; instrumented `HoardTrashFlowTest`.

**Without `backup`:** Reveal applies today's rule as soon as the paired server answered `GET /v1/server` **in this run**
without `backup` (never a stored features list): such a server cannot have freed anything, and keeping deletions for a
week on every current server would be a visible regression (accepted by the orchestrator). Offline, or before the next
sync, photos still wait (bounded by the walk's 7 days).

### Against the real server (R1b)

`RealServerBackupTest` (`:core:sync`, JVM, `REVEAL_BACKUP_DIR`) runs every rule above against hoard-server 0.13.2 with
two device keys on one phone: Reveal's (`TestDevice`) and a raw HTTP "Hoard" that claims Reveal's physical device and
drives `POST /v1/backup/roots`, `PATCH .../roots/{id}`, `POST /v1/backup/report` and uploads with `claim: false`. What
the real server showed, against R1's reading of Hoard ADR 0024 and `FakeSyncServer`:

- **Unchanged and confirmed**: roots in `physicalDevice.backup` with `types`, `active` and upper-case card volumes;
  entry and location paths relative to the volume; `GET /v1/items?folder=<rootId>&path=` with `path` relative to the
  root (as the fake does); `mirror` locations with `onDevice`/`gone` next to a `backup` location; `in_progress` with
  `retryAfter: 300` while another device's upload is live, `exists` once it completes, `created` once it is abandoned;
  `409 mirror` on `DELETE /v1/originals` after the photo's record is tombstoned; the reserved `SD card <UUID>` prefix
  (`422` for a root).
- **Items follow a report a moment later**: right after `removed` (`freed`), the folder query can still say
  `onDevice: true`. FreedPhotos treats that as "the walk missed it" and asks again in 6 hours; no change was needed
  (Hoard reports a free-up as soon as the system deleted the files, long before a walk notices).
- **`freed-adopt` was not kept** by the first 0.12.0 build (unknown features were dropped): fixed in hoard-server 0.12.0
  (schema 11), which stores it and lists it in `features` and `physicalDevice.devices[].features`. Reveal parses the
  latter (`DeviceEntry.features`, optional) only for the test.
- **A bad report entry is refused on its own** (`state: "refused"` with `reason`), not a `422` for the whole batch.
  Reveal never reports, so nothing changed here.
- **PNG, WebP and GIF** are mirrored (screenshots, stickers): Reveal now knows GIF (`ImageKind.GIF`, `PhotoFormat.GIF`,
  badge "GIF"; shown and edited as its first frame, never through `BitmapRegionDecoder`, which has no GIF), so a folder
  GIF is a photo, Hoard's `image` type covers it, and a mirror item of any of the three becomes a Server photo, adopted
  or handed over. Older apps read an unknown `format` from the file names (`SyncStore.formatOf`).
