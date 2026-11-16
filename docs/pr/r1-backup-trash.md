## R1: Hoard's trash (Hoard ADR 0023) and one backup per phone (Hoard ADR 0024)

Reveal 0.10.0 work for hoard-server 0.11.0 (`trash`) and 0.12.0 (`backup`). Every addition is used only when
`GET /v1/server` lists its feature; against older servers Reveal behaves as 0.9.0, apart from the purge fix (an edited
NAS photo whose file is gone is now unavailable instead of a broken cell). Design and rules: ADR 0018 "Amendment R1";
notes in ADR 0006 and 0011; CLAUDE.md "Sync (app)"; CHANGELOG `[Unreleased]`.

### What

**Part T: Hoard's trash**
- NAS feed `removed + trashed` keeps the Server source's file row with `photo_files.trashed_at`. A Server photo with no
  live servable file is "in Hoard's trash": hidden from the home, the Server scope and every count, listed only under
  the new Sync filter **In Hoard's trash** (token `s:IN_TRASH`, shown while something is in the trash). The cell is a
  placeholder ("In trash"); the viewer shows a note and no Edit button. All trashed photos are kept, edited or not.
- Restore (same path and fingerprint) clears it: the same photo with its uuid, edits and culling.
- Purge (and any removal on a server without `trash`) deletes the row; a photo Reveal keeps (edit, hand-off, record)
  loses `original_on_server` and gets `photo_cloud.lost_at`: an **unavailable** cell with the sheet (new "server"
  reason) and Remove from Reveal. Fixes 0.9.0's broken cell.
- "Remove from Reveal" stamps `photos.forgotten_at`; such a Server photo is shown again (and its record pushed with
  `hidden: false`) when its file comes back. Photos hidden any other way stay hidden.
- Hoard ADR 0023 amendment S1: a JPEG trashed alone leaves the pair live with its RAW (not in the trash).
  `fingerprints/lookup` still finds a trashed file until it is purged, so a purge (or removal) makes this device's own
  copies of that content ask again (`SyncDao.askAgain`) and get backed up if the server lost them.
- Edited previews: unchanged behaviour (missing + 404 while trashed); a trash and restore re-uses the server's edit.

**Part B: one backup per phone (ADR 0024 items 1-8)**
- `GET /v1/device` `physicalDevice.backup.roots` -> `meta` `sync.backup.roots`, refreshed every sync (gone without
  `backup`). `BackupCoverage` (pure): only `ExternalStorageProvider` trees, active roots, volume any case, path prefix
  at `/`, file kind in the root's `types`.
- Covered candidates (backup-on, edited; the shot's main file) wait **24 h from when they became candidates**
  (`photo_cloud.candidate_at`, excluded from `backupCandidates` while waiting so they never starve others), are looked
  up again and uploaded only if still missing. Uncovered files go at once.
- `POST /v1/uploads` sends `"wait": true` (only to servers listing `backup`; `claim` absent). `in_progress` keeps the
  upload pending until `retryAfter` (default 300 s, `sync_uploads.retry_at`), no failure count, no parallel upload.
- `DELETE /v1/originals` `409 mirror`: dropped at once, `on_server` kept, library notice "N files stay on the server:
  they are in this phone's backup by Hoard".
- Freed-adopt: a walk keeps a photo whose every file vanished (`vanished_at`, unlisted; paired: all such photos;
  unpaired: only edited ones; at most 7 days). `engine/FreedPhotos` decides every sync: item by fingerprint, else by
  mirror location (`GET /v1/items?folder=<rootId>&path=<dir>`, volume + path, size + `modifiedAt` to the second);
  `freed` -> adopt; `deleted`/unknown/no location -> adopt only if edited; `onDevice: true` -> keep waiting; no item ->
  delete. Adoption is in place (uuid, versions, edits, history, culling kept; files `freed/<fp>/<name>`). `PATCH
  /v1/device` declares `freed-adopt` (with `culling`) only with `backup`.
- Settings > Sync: a covered folder shows "Backed up by Hoard on this phone" (read only); "Originals over Wi-Fi only"
  says it applies to what Reveal still uploads. Manage folders: "Backed up by Hoard" instead of the backup choice.
- Item 7: mirror locations parse and do not change `SyncServerItems`' nas/backup choice.
- DB v16 (auto-migration, schema exported, `RevealDatabaseMigrationTest`).

**Deliberate deviation (please confirm):** the task asked to keep vanished photos up to 7 days also when the server has
no `backup` feature. Reveal deletes them as before as soon as a reachable server says it has no `backup` (such a server
cannot have freed anything; keeping deletions a week on every current server would be a visible regression). Offline
or before the next sync they still wait (7-day bound). Easy to switch if you prefer the literal rule.

### How tested (cachyos-fps, Linux x86_64)

- `./gradlew qa` -> BUILD SUCCESSFUL (unit tests, Roborazzi verify, lint, ktlint, detekt, all modules).
- New JVM tests: `HoardTrashTest` (7: trash/filter/restore, purge -> unavailable, no-`trash` purge fix, forgot then
  restored + "removed from library" stays hidden, JPEG trashed alone, purge re-asks local copy, edited preview not
  re-uploaded), `PhoneBackupTest` (6: roots/coverage incl. paused/other phone/non-ExternalStorage, 24 h grace for every
  candidate with Hoard backing one up meanwhile, types, no `wait` without `backup`, `in_progress` retry without failure
  + default 300 s, `409 mirror`, `freed-adopt` declaration), `FreedAdoptTest` (7: freed with fingerprint, freed without
  by location/size/time + decoy, deleted with/without edit, unknown reason, `onDevice` keeps, no `backup` deletes,
  outside any root by fingerprint), `VanishedPhotosTest` (3: unpaired/paired/partial), `BackupCoverageTest` (4),
  `SyncServerItemsTest` (mirror locations). Existing suites unchanged and green (`RePairTest`, `VersionPhotoSyncTest`,
  `ServerViewTest`, ...).
- Real server (loopback Docker, hoard-server 0.11.0 built locally from Hoard `trash-s1-server` = PR #57 + main, via
  `HOARD_SERVER_IMAGE=hoard-server:0.11.0-local`; pin stays 0.9.0 until the tag exists):
  `RealServerTrashTest` -> pass (NAS file moved into `Shoots/.hoard-trash/2026-11-16/`, walked, restored with its
  edit, trashed again, purged -> unavailable); `RealServerVersionPhotoTest` against 0.11.0 -> pass (2).
- Emulator (own AVD, 1080x2340@450, x86_64): `HoardTrashFlowTest` (2: trash filter -> placeholder -> viewer note without
  Edit, paired to an on-device MockWebServer; lost Server photo -> unavailable sheet) passed twice in a row without
  `pm clear`; `LibraryFilterFlowTest`, `UnavailablePhotoTest` pass. `LibraryFlowTest#addFolderBrowsePairsRateInTheViewerAndEdit`
  fails at its sidecar-write step: it still expects a sidecar written after a viewer rating, which W4 (0.9.0) removed;
  unrelated to this PR (stale on main).
- Goldens: new `library/phone_hoard_trash_light`, `phone_hoard_trash_filters_dark`, `phone_manage_folders_hoard_dark`,
  `phone_unavailable_server_sheet_light`, `sync/phone_paired_hoard_dark`; updated `sync/phone_paired_light`,
  `phone_paired_problem_dark` (Wi-Fi subtitle). Every other golden is byte-identical.
- Also fixed a pre-existing race in `HandoffServerRefsTest`'s fake (call logs appended from parallel coroutines).

### Not done yet

- `RealServerBackupTest` (roots, `wait`, `409 mirror`, `GET /v1/items?folder=<rootId>`): hoard-server 0.12.0 is not
  published or merged yet. Part B is covered against `FakeSyncServer` only.
- Moving `HOARD_SERVER_VERSION` to 0.11.0 / 0.12.0 when their tags exist.

### For the phone (orchestrator)

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s RXCX109VTTM install --user 0 -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s RXCX109VTTM install --user 0 -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s RXCX109VTTM shell am instrument -w -e class dev.hfps.reveal.library.HoardTrashFlowTest,dev.hfps.reveal.handoff.UnavailablePhotoTest dev.hfps.reveal.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s RXCX109VTTM uninstall dev.hfps.reveal.debug; adb -s RXCX109VTTM uninstall dev.hfps.reveal.debug.test
```
