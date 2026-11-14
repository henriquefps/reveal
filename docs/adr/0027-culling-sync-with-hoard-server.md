# ADR 0027: Culling sync with hoard-server, device identity, edited previews, XMP culling fixes

Status: accepted (R0.8). Date: 2026-11-14. Implements Hoard ADR 0016 "Client rules" 1–5 and "Wire format" (culling
records, per-field last writer wins) and Hoard ADR 0017 "Client calls" (one device identity for Hoard and Reveal) on
Reveal's side. Amends ADR 0018 (culling no longer travels inside `photo` records while the server has culling
records), ADR 0006 "XMP" and ADR 0026 (which culling properties a sidecar write touches, how a sidecar is imported),
and ADR 0011 "Culling" (hand-off values are pushed only when they differ from the server's). Database v13. Protocol of
record: Hoard's `docs/adr/0013-server-protocol.md` ("Culling", "Physical devices"); pinned server
`hoard-server:0.9.0` (`scripts/sync/local-server.sh`). Also implements Hoard ADR 0020 "Client rules (Reveal)"
(edited previews for the server's gallery, section "Edited previews").

Code: `:core:data` `db/CullingEntities.kt`, `db/CullingDao.kt`, `db/SyncTriggers.kt` (v5), `library/SidecarImport.kt`,
`library/SidecarSync.kt`, `xmp/Xmp.kt` (`SidecarCulling`, `CullingChanges`, `XmpWriter.update(write = …)`),
`handoff/HandoffRepository.kt`; `:core:sync` `engine/CullingSync.kt`, `engine/SyncEngine.kt`, `account/ClientDevice.kt`,
`api/SyncApi.kt`, `SyncRepository.kt` (`ThisDevice`, `claim`, `pairCandidates`); `:feature:settings`
`sync/ThisDeviceScreen.kt`.

## Context

Until 0.7 Reveal synced rating, flag and label inside its `photo` records: last writer wins **per record**, so a
rating on one device and a pick on another at the same moment lost one of the two, and Hoard, which culls the same
shoots, saw none of it. hoard-server 0.7.0 holds one culling record per shot (item) with three independent fields and
a per-field conflict rule (Hoard ADR 0016), and bridges released Reveals' photo records into it until they declare the
capability. It also groups the app installations of one phone (Hoard ADR 0017).

Reviewing Hoard ADR 0016 also showed four XMP problems on Reveal's side: (a) an edit-only sidecar write (ADR 0026)
rewrote `xmp:Rating`, `digiKam:PickLabel` and `xmp:Label` from the database, so a value pulled from the server (or
changed in Lightroom since) was written over; (b) the reader turned `xmp:Rating="-1"` into 0 stars + reject, losing
the stars; (c) every change of a sidecar re-applied all three fields, so Lightroom changing a label re-asserted the
file's old rating over a newer one from another device; (d) a hand-off from Hoard carries Hoard's cached server values,
which Reveal would push back as new decisions.

## Decision

### Feature gate

Everything below runs only when `GET /v1/server` lists `culling` (and the device identity calls when it lists
`device-identity`). Against an older server Reveal behaves exactly as 0.7: culling inside `photo` records, no
`/v1/device` call, no culling feed (`SyncStore.cullingInRecords = true`; the local queue still fills and simply waits).

### The local model (database v13)

| Table | What |
| --- | --- |
| `culling_records` | A mirror of every culling record the feed sends (not only those of local photos): per field the server's value, `rev` and `modifiedAt`, and `replaced_by`. |
| `culling_files` | The record's member fingerprints (`fingerprints` of the feed), plus the main file a push named it by (the result's `itemId`). |
| `culling_pending` | The queue of local decisions, one row per photo and field: when it was made, `state` (`pending` / `invalid`), `invalid_rev`, and `blind`. The value is read from the photo when it is pushed, so several decisions collapse to the last. |
| `sidecar_culling` | What each photo's sidecar held for each field the last time Reveal read or wrote it (null = absent). |

Mirroring every record keeps mapping local and cheap: a photo maps to a record when **any** of its files' fingerprints
is one of the record's member files (Hoard ADR 0016 "Mapping a record" 1), at any time, including for a photo added
long after its record went by in the feed. This is why Reveal needs no `POST /v1/items/lookup` fallback (rule 4 of the
mapping): a record not in the mirror is a shot the server holds no culling for. Records with `fingerprints: []` and no
`replacedBy` (a phone-only shot) map through the derived id: a local main file whose
`uuid8(SHA-256("hoard-item\0" + f))` is the record's id (`CullingSync.derivedItemId`, checked against the Go
function); a push by fingerprint also records the item the server named, so this device's own phone-only shots map
directly. A replaced record never maps (its files are cleared); its fields are on the live item's record.

### Local decisions are queued by triggers

Sync triggers v5 add six culling triggers on `photos`: an update that really changes `rating`, `flag` or `label`, and
an insert with a non-default value, upsert that field's `culling_pending` row (`modified_at` = now, `pending`, not
blind). Like the record triggers they are quiet while `meta` `sync.applying` is set, so a value applied from the server
is never queued back (no echo). Every write path is covered without code of its own: the library's rating and flag
actions, the viewer, sidecar imports, hand-offs, merges.

Paths that know better date the change afterwards (`CullingDao.date`), which also marks it **blind**:

- a sidecar import: the sidecar's mtime (Hoard ADR 0016 "Client rules" 4);
- an embedded rating (JPEG XMP, TIFF Rating) filling an unrated photo: the file's mtime;
- a hand-off value with a sender time (below).

A blind change is pushed with `baseRev` 0, so the server decides by time alone (its rule 5), exactly as for its own
sidecar import; a decision the user made in Reveal carries the field's revision as this device last saw it (rule 4:
it wins whatever the clocks say when nobody changed the field since).

### One culling sync (`CullingSync.run`, after the record push and pull of a run)

1. **Seed**, once per server (`meta` `sync.culling.seeded`): every local, non-default value not queued yet (rated
   before this version or before pairing) is queued blind, dated `COALESCE(xmp_modified, added_at)`: older than any
   decision the server may have, so a newer one there wins and a field the server never had is filled. Server-source
   photos are not seeded (their values are other devices' records, already bridged).
2. **First pull** when the culling cursor is new, so the queue goes up with the right base revisions.
3. **Push** `POST /v1/culling` in batches of ≤ 500, by the photo's main fingerprint (`photo.data.fingerprint`), with
   the decision's own `modifiedAt` (kept as made when pushed later, offline). Labels are checked like the server
   checks them (trimmed, ≤ 64 characters, no control character): one that fails stays local as `invalid`, never sent.
   Results: `applied`, `unchanged`, `conflict` (either side) clear the row (unless the user changed the field again
   meanwhile: the row's time moved) and update the mirror when this value is the server's now (a clipped time is
   stored as the server's clock at most); `invalid` keeps the value local (`state = invalid`, `invalid_rev` = the
   field's rev) and the rest of the batch goes on. A refused row is pushed again only after the user changes the field
   again (the trigger re-arms it).
4. **Pull** `GET /v1/items/changes?include=culling` (own cursor `sync.culling.cursor`; `invalid_cursor` restarts from
   the beginning), mirroring each record; a field this device pushed at a newer revision keeps that state.
5. **Derived ids** for live records without known files (above).
6. **Apply**: per field, every photo whose value differs from its record's takes it, unless a change of that field is
   pending (it was pushed; the next pull brings the winner) or refused at the record's current revision (a label only
   Reveal can hold stays until another app writes the field). Applied with the triggers quiet: no queued change, no
   dirty `photo` record, no sidecar write. A refused row is dropped when the server's value replaces it.

Rating and flag are independent fields everywhere: a reject keeps its stars on the server, on every device and in the
grid.

### Photo records

Reveal keeps writing `rating`, `flag` and `label` into its `photo` records (devices on an older Reveal read them) but,
while the server has culling records, ignores those three fields of pulled records (`SyncDao.applyPhotoHidden` applies
only `hidden`). A value pulled from the culling feed does not mark the photo record dirty: older Reveals see this
device's own decisions (in its records) but not Hoard's until they update. Pushing a record for every pulled culling
value would make every culling-aware device re-push every change (no loop, since the bridge ignores aware devices, but
N extra records per decision).

### Device identity (Hoard ADR 0017)

- `app` is the app id with its build variant, from the package name (`ClientDevice.appId`: `dev.hfps.reveal` →
  `reveal`, `.debug` → `reveal-debug`, `.bench` → `reveal-bench`); `manufacturer` / `model` are `Build.MANUFACTURER` /
  `Build.MODEL` (≤ 64).
- Pairing sends them; the answer's `physicalDeviceId` is kept (`meta` `sync.device.physical`) and its candidates with
  `match: "model"` become `SyncRepository.pairCandidates`: the Sync screen asks the one question ("Is this the same
  phone as Hoard on Pixel 8?" → **Same phone** = `POST /v1/device/claim`, **Another device** = nothing; several model
  matches = the same question as a list, plus **None**). No model match: no question.
- `PATCH /v1/device` `{app, manufacturer, model, features: ["culling"]}` runs at the start of a sync, **before any
  photo record is pushed** (Hoard ADR 0016 rule 5), once per server and again whenever the body changes (`meta`
  `sync.device.reported` = server id + body). A refusal that is not transient is logged and retried next run; the
  first `POST /v1/culling` marks the device culling-aware anyway.
- **Settings → Sync → This device** (`ThisDeviceScreen`): how the server knows this installation (name, app, model,
  whether culling syncs per field), the other apps grouped on this phone, **Not the same phone** (claim `null`), and the
  phones it could join (each with **Same phone**; model matches say "same model"). `409 same_app` ("that phone already
  has this app paired") and `404` are explained. An older server: the screen says it does not group apps by phone.

### XMP fixes (amend ADR 0006 "XMP" and ADR 0026)

- (a) **Writes touch only what the user changed.** `SidecarSync.write(…, fields)`: a rating tap writes the rating, a
  flag tap the flag (the two share `xmp:Rating` + `digiKam:PickLabel`, so either writes that pair from the photo's
  current values), a label its `xmp:Label`; an edit-only write (`EditSidecars`) writes none of them, and a sidecar it
  creates has no culling properties. Values pulled from the server are applied to the database at pull time and never
  written to a sidecar by themselves (Hoard ADR 0016 rule 4); the pair a user's tap writes reads the photo's current
  values, pulled ones included.
- (b) **A reject keeps the stars.** `XmpReader.readCulling` reads field by field (`SidecarCulling`): `rating` only from
  a non-negative `xmp:Rating`; `flag` from `digiKam:PickLabel` when present, else a negative rating (reject), else the
  Hoard "Copy picks" rule; `label` from `xmp:Label`. `-1` sets the flag and leaves the stars as they are. (The old
  `XmpReader.read` stays for embedded packets.)
- (c) **An import re-asserts only what changed in the file.** `SidecarImport.apply` (folder walk and hand-off) compares
  with `sidecar_culling` (what the same file held last time; the first read of a file: every field it holds); a label
  present before and absent now (Lightroom removes it) clears the label; what it applies is dated by the file's mtime
  and pushed blind. After every write Reveal stores what the file now holds. Migration 12 → 13 fills `sidecar_culling`
  from the photos' values for every photo with an applied sidecar (a reject's rating unknown), so upgrading does not
  re-assert everything at the next change of a file.
- (d) **Hand-offs.** The values a sender passes (ADR 0011 "Culling") are queued like any change, then each one equal to
  the server's value for the shot (the mirror) is dropped: Hoard sends its cached server values, and they are not
  decisions to push back. `EXTRA_CULLING` items may carry `ratingModifiedAt`, `flagModifiedAt`, `labelModifiedAt` (Unix
  ms, any `CULLING_VERSION`; Hoard's planned version 2): they date the queued change (blind) instead of the hand-off's
  time.

### Edited previews (Hoard ADR 0020)

With `edits` in `/v1/server` `features` (hoard-server 0.9.0 with previews on), every **edited** version (a non-default
committed recipe; the main version and virtual copies, never a sync conflict copy) gets its rendered look on the
server, so the web gallery and Hoard show the photo as finished in Reveal.

- **When.** In the blob job (`SyncRepository.syncBlobs`), under the originals' network rule (unmetered, unless
  "Originals over Wi-Fi only" is off) and Settings → Sync → "Edited photos for the gallery" (on by default), at most 5
  renders per run (the job runs again while there is more). A version goes up once its edit **settled**: its recipe
  unchanged for `EditPreviews.SETTLE_MS` (60 s) and its record pushed; not on every slider step.
- **Which item.** `POST /v1/items/lookup` with the photos' main fingerprints (batches of ≤ 1000) names each photo's
  item and the edits it already holds; a photo the server does not index (phone-only, not backed up) is skipped
  until it is (an edited photo's original is backed up by default, ADR 0018 FIX/P).
- **Render.** `ThumbnailRepository.renderEdit`: the same develop as the thumbnails and the viewer (GPU, AI masks from
  the masks' cache or made, Remove fills), proxy at the server's `maxEdge` (2560), the geometry's output (sRGB,
  upright, a crop is smaller than 2560: never upscaled), JPEG quality 90 (80, 70 only when over `maxBytes`).
- **Digest.** `r<PIPELINE_VERSION>.<EditPreviews.RENDER_VERSION>:` + SHA-256 of the recipe re-encoded by the codec:
  equal for the same edit on every device, new when the recipe or Reveal's rendering changes. An edit whose digest
  the server already holds (uploaded by this or another device) is never rendered again; a change of label (the
  version's name; `""` for the main version), primary (the main version when edited, else the first edited copy:
  what Reveal's grid shows) or `editedAt` (the version record's `modifiedAt`) is a header-only PUT.
- **Deletes.** A version reset to unedited, or deleted here (a tombstone), is deleted on the server
  (`DELETE /v1/items/{itemId}/edits/{versionId}`); a version gone without a tombstone (its folder removed) only
  leaves this device's state.
- **Answers.** `409 older` (another device's later edit), `422 invalid_image` / `too_many_edits` /
  `not_applicable`: remembered (`edit_uploads.refused` = digest + time) and not tried again until the edit changes;
  `image_required` on a header-only PUT: sent again with the image; `404`: looked up again next pass; transient
  errors: the job retries.
- **State.** `edit_uploads` (v13): per version uuid the item, digest and metadata the server holds. Each pass
  recomputes the work from it and the database, so nothing is lost when a run is cut short. Unpairing clears it.
- Known limit: a version whose item later changes id (a lone JPEG whose RAW appeared: the server moves the edit) is
  deleted on the old id only when it is next reset; the server's move keeps the gallery right meanwhile.

## Consequences

- Rating, pick and label made at the same time on different devices or apps all survive; Hoard's decisions reach
  Reveal's grid within one foreground pull (every 30 s while the app is open; measured on the emulator, below) and
  Reveal's within the 3 s change debounce plus one sync.
- The server's culling feed is mirrored in full: one row per culled shot plus its member fingerprints (a few hundred
  bytes per shot; 100,000 culled shots ≈ 30 MB). Records are never deleted while paired; unpairing clears the mirror
  and the cursor but keeps the queue (decisions made unpaired go up at the next pairing).
- Sidecars may lag the server (a value from another device reaches a file only with the next decision made on a device
  that has the folder), exactly as Hoard ADR 0016 accepts.
- An older Reveal on another device keeps working through the bridge; it does not see Hoard's values until updated.
- `XmpReader.read` (all fields at once) remains for embedded packets; sidecars always go through `readCulling`.

## Tests

- JVM, `:core:sync` against `FakeSyncServer` + `FakeCulling` (Hoard ADR 0016's conflict rule, clock guard, items feed
  with member files and `replacedBy`, the bridge, `/v1/device` and claims): `CullingSyncTest` (declare before photo
  records, per-field merge with Hoard, quiet apply and no echo, record fields ignored, offline times, clock clip,
  member-fingerprint mapping, `replacedBy`, derived ids, refused labels, server refusals, reject keeps stars, seeding,
  old server), `DeviceIdentityTest` (app id, pairing, the one question, This device, `same_app`, leaving, old server,
  one report).
- JVM against the real server (`RealServerCullingTest`, `REVEAL_SYNC_LINKS`, three pairing links of
  `scripts/sync/local-server.sh` on loopback): per-field travel between two Reveals and "Hoard", no echo, the
  capability and model reported, per-change `invalid` results and the clock clip, refused labels.
- JVM, `:core:data`: `SyncTriggersTest` (culling triggers), `XmpTest` (field-by-field read, changes since last read,
  selective write), `SidecarSyncTest` (rating-only and edit-only writes, changed-fields import dated by mtime, cleared
  label, reject keeps stars), `HandoffIntentsTest` / `HandoffRepositoryTest` (sender times, values the server already
  holds), `RevealDatabaseMigrationTest` (12 → 13).
- Edited previews: `EditPreviewsTest` (fake: upload once, settle, virtual copies, header-only rename, primary,
  reset and delete, conflict copies excluded, `409 older` not retried, another device's upload not re-rendered,
  unindexed photo and older server, digest); `RealServerEditsTest` (`REVEAL_EDITS_LINK`: backup -> item, upload, lookup
  lists it, header-only PUT, `image_required`, reset deletes it); instrumented `EditedPreviewFlowTest` (`-e syncLink`,
  GPU: a real device): +2 EV in Reveal -> the server's `preview?edit=primary` is the brighter edit.
- Instrumented: `dev.hfps.reveal.sync.CullingFlowTest` (`-e syncLinks`): pair from the Sync screen after "Hoard",
  answer "Same phone", see Hoard under This device, a rating + pick Hoard sends appear in the grid without a manual
  sync, a reject made in Reveal reaches the server with the stars kept. Goldens `phone_this_device*`,
  `phone_same_phone_dark`, `tablet_this_device_dark`.

## Alternatives considered

- **Map records only through `POST /v1/items/lookup`**: a request per new photo (or batch) and a second source of
  truth; the mirror answers locally and is what Hoard does with one cursor.
- **Re-push the photo record for every pulled culling value** (so older Reveals see Hoard's values at once): N extra
  record revisions per decision on N culling-aware devices; rejected, older Reveals update.
- **Write pulled values to sidecars**: Hoard ADR 0016 rule 4 forbids it (a third writer of the owner's files racing
  Lightroom); a later option, safe because the server's import would be a no-op.
- **Push sidecar imports with the cached revision**: would make an old file win over a newer value whatever its date;
  blind pushes let the time decide, as the server's own import does.
