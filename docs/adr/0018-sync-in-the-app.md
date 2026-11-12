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
| Smart previews | `SmartPreviewCodec` (`:core:pipeline/preview`): the editor's own `PipelineInput` at 2560 px (log-encoded 16-bit luma, half-resolution chroma, deflate) plus a 1600 px display JPEG. The editor opens it directly; made by devices for their device-only photos. |
| Originals | Device-only photos (lookup says missing) of folders with the backup on: full SHA-256, chunked resumable upload, Wi-Fi only by default. The server's fingerprint check guarantees the bytes. |
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

The format (`RVSP`, version 1) is in `SmartPreviewCodec`'s KDoc. Luma (green) keeps 0.0003-stop steps over 20 stops;
chroma (`log2 r - log2 g`, `log2 b - log2 g`) is half resolution at 0.004-stop steps; values below black clamp to 0.
`SmartPreviewCodecTest`: green within 0.1 %, mean colour error under 2 % at the scene's ring edges, a developed preview
within 1.5 8-bit levels (mean) of the developed original. A device makes previews only for its device-only photos that
are edited, rated or picked, or whose originals are not being backed up (the option or the folder's switch is off):
that keeps preview storage to the photos people work on, and a backed-up original serves the others.

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

## Consequences

- The grid now has one cell per version (a photo with a conflict copy has two); selection stays per photo.
- A conflict that arrives while a slider is held is not shown until the next sync after that commit: the server then
  turns it into a copy.
- Previews and originals are never garbage collected on the server (ADR 0017); the app never deletes a backup.
- Encryption of originals at rest is still the backup volume's job.
- The x86_64 emulator's host GL stack crashes on the editor's GPU tests (`RenderThread` SIGSEGV in
  `qemu-system-x86_64-headless` with `swiftshader_indirect`; with `-gpu host` the CPU threads hang and it segfaults
  later in the full suite). The same tests pass on the phone; GPU-heavy suites run on a device.
