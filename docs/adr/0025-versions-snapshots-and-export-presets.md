# ADR 0025: Versions, snapshots and export presets

Status: accepted (M5/V). Date: 2026-11-13.

## Context

`docs/plan/product.md` "History, versions, copy/paste" asks for **virtual copies** (several independent edits of one
photo, shown as separate items in the library) and **snapshots** (named, saved states); "Export and share" asks for
**export presets** (named combinations of the sheet's choices) with "Instagram 4:5 (1080×1350)", "Full" and
"Web 2048". ADR 0006 already reserved virtual copies in the data model (`versions`, position > 0) and gave every
version its own recipe, history and cursor; ADR 0018 already syncs versions by uuid and keeps sync conflict copies
apart with `versions.copy_of`; the grid already lists every version as a cell (conflict copies, versions another
device added). ADR 0009 is the export pipeline; its sheet remembered only the last options.

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Virtual copy | A `versions` row of the same photo, `position` = last + 1, `copy_of` null, `name` "Copy N" (localised when made, then the user's text). Its own recipe, history, cursor, snapshots, thumbnail and exports. |
| Copy source | "Copy this edit": the new history starts with one `HistoryAction.VIRTUAL_COPY` step holding the source's committed recipe (none when it is unedited), so undo reaches the original. "Copy of the original": no step. |
| Culling | **Per photo**, shared by its copies (see "Culling"). |
| Delete | A copy can be deleted, never a photo's last version; deleting the first version moves the next one to position 0. A version the server knows becomes a tombstone. |
| Grid | One cell per version (unchanged); a copy's badge shows its name; "Edited" (badge, filter, counts) is per cell. Selection is by version. |
| Viewer, filmstrip | Page and step through versions; the viewer's Versions sheet lists, shows, creates, renames and deletes copies. |
| Snapshot | A row of the new `snapshots` table (uuid, version, name, recipe JSON, time). Applying is one `HistoryAction.SNAPSHOT` step named after it. |
| Snapshot sync | Inside the version's record: `version.data.snapshots`. No new record kind. |
| Export preset | A row of the new `export_presets` table (uuid, name, `ExportOptions` JSON, position); built-ins are code. |
| Export preset sync | One `setting` record per preset, id `export-preset.<uuid>`. No server change. |
| Database | v12, an auto-migration (two new tables); sync triggers v4. |

## Virtual copies

**Making one.** `VersionRepository.createCopy(versionId, CopySource, nameFor)` inserts the row after the photo's last
version and names it with the first `nameFor(n)` no version of the photo uses (`n` starts at the number of non-conflict
versions, so the first copy is "Copy 1"). The name is stored, not computed: it is user data that syncs and can be
renamed, and a number derived from positions would change when another copy is deleted. From the library selection
(`createCopies`, one copy per selected cell, one transaction), the viewer's Versions sheet (the version on screen: its
edit or the original) and the editor's More ⋯ (the committed edit; the editor flushes its history writer first and then
moves to the copy, like a filmstrip step).

**Its first step.** A copy of an edit starts with one `VIRTUAL_COPY` step (labelled with the groups it holds) rather
than a copy of the source's whole history: the copy branches from where the source *is*, its own undo goes back to the
original, and a long history is not duplicated. Snapshots are not copied (they are the source's saved states).

**What a copy has of its own.** Recipe, history, cursor, snapshots (all keyed by `version_id`), the grid thumbnail
(`ThumbnailRepository.thumbnail(photoId, versionId, editTime)` already keys by version), the viewer's developed image
(`PhotoImages.display/developed(..., versionId)`), exports (`ExportRequest.Versions` already lists versions) and the
editor (`EditorRoute(versionId)`). Copy/paste, "Previous", presets and resets act on versions already.

**Culling.** Lightroom rates each virtual copy. Reveal keeps ratings, flags and labels **per photo**: they live in the
XMP sidecar of the photo's files (ADR 0006 "XMP"), which Hoard reads and writes, and a sidecar has one rating per file.
Per-copy culling would either not reach the sidecar or make the copies overwrite each other there, and Hoard's culling
(the owner's workflow: cull in Hoard, edit in Reveal) would stop agreeing with Reveal. So a copy's cell shows the
photo's stars and flag, rating a copy rates the photo (the selection maps cells to their photos), and the Versions
sheet says so. Revisit only together with a sidecar format for versions (M5/X writes the recipe to the sidecar).

**Deleting.** `VersionRepository.delete` refuses a photo's last version (a photo always has one; "Remove from library"
removes photos). The cascade drops the recipe, history, cursor and snapshots. When position 0 goes, the next version
moves to 0 (the grid shows a photo's first cell by the photo id), as sync's own remote delete does. The sync record: a
version that reached the server (rev > 0) becomes a dirty tombstone; one that never did is forgotten. There is no delete
trigger on `versions`, on purpose: removing a folder or forgetting a hand-off cascades versions that must not become
tombstones on the server (ADR 0018 "Deleting from the server").

**Grid and selection.** `PhotoQuerySql` already returns one row per version, ordered by the photo then `position`; it
now also returns `v.name` (`PhotoRow.versionName`, the badge). "Edited" was the photo's `has_edit`; with copies an
unedited copy of an edited photo is not edited, so the Edits filter, the edited dot and the filter counts go by the
cell's own recipe (`EDITED_SQL` = a non-default recipe joined to the version), and the counts are counted over cells
(`CELLS_FROM`), the same rows the grid lists. `photos.has_edit` stays (hand-off merges, the forget dialog, the viewer's
fallback). The selection holds **version ids**: a copy is selected, exported, pasted onto and reset on its own; rating,
flag, "Remove" and the unavailable-photo actions map the selected cells to their photos (`selectedPhotoIds`).

**Viewer and filmstrip.** The viewer pages through the grid's cells (`ViewerPage(photoId, versionId)`, keyed by the
version), opens on the cell that was tapped (`ViewerRoute.versionId`), shows a copy's name in its title and opens the
editor on the page's version. Its Versions sheet (top bar) lists the photo's versions; a version that is not in the
viewer's snapshot of the grid (a new copy) is added after its photo's last page and shown. The editor's filmstrip
already listed versions (`gridKey`, `versionId`).

**Conflict copies stay distinct.** A conflict copy has `copy_of` set (ADR 0018); a user copy never does. The grid's
"Conflict" badge, the conflict chooser, "Delete conflict copies" and the viewer's "Resolve conflict" only see
`copy_of`; a user copy shows its name and opens the viewer. `VersionInfo.isCopy` = position > 0 and not a conflict copy.

## Snapshots

`snapshots` (`id`, `uuid` unique, `version_id` → `versions` cascade, `name`, `recipe_json`, `created_at`, indexed by
version). A snapshot belongs to one version (a copy has its own) and stores the full recipe (ADR 0003 JSON) as it was
saved; applying decodes it through `RecipeCodec` (its migrators), so an old snapshot applies on a newer app.

- **Create**: from the History panel ("Save") or More ⋯ ("Save snapshot…"), named in a dialog that starts with
  "Snapshot N". The editor saves its *committed* recipe (the history writer may not have flushed it yet).
- **Apply**: one `HistoryAction.SNAPSHOT` step labelled "Snapshot: <name>" with the groups it changes (none when the
  edit is already the snapshot), so undo returns to the edit before it. The editor commits it through its own history;
  `VersionRepository.applySnapshot` does the same outside the editor.
- **Rename / delete**: from each snapshot's menu in the History panel. The list is newest first.

**Synced.** Snapshots are small (a recipe) and are part of "the edit" the owner expects on every device, so they
travel **inside their version's record**: `version.data.snapshots = [{id, name, recipe, createdAt}]`. Triggers on
`snapshots` (insert, update, delete) mark the version's record dirty; a pull replaces the version's snapshots with the
record's (unchanged when equal). A snapshot uuid another local version already holds (the same snapshot inside a
conflict copy) gets a new local uuid. Records without the field (0.6.x) apply as "snapshots unchanged" (`null`), but a
0.6.x device that edits the version pushes it without them: last write wins for the whole version, as for its history.
A version with snapshots is pushed even when its recipe is unedited. "Keep this one" in the conflict chooser moves the
kept side's snapshots to the main version. A separate record kind was rejected: the server validates kinds (ADR 0017),
and a snapshot without its version means nothing.

## Export presets

`ExportPreset` (`:feature:export/model`): `BuiltIn(kind)` or `User(uuid, name, options)`, keyed `builtin:<id>` or by
uuid. Built-ins (`BuiltInExportPreset`, stable ids):

| Preset | Options |
| --- | --- |
| Instagram 4:5 (`instagram`) | JPEG 90, sRGB, fit 1080 × 1350, copyright only, no location, Share |
| Web 2048 (`web`) | JPEG 85, sRGB, long edge 2048, copyright only, no location, Pictures/Reveal |
| Full quality (`full`) | JPEG 100, sRGB, full size, all metadata, Pictures/Reveal |

User presets are rows of `export_presets` (`uuid`, `name`, `options_json` = `ExportOptions.encode()`, `position`,
times), through `ExportPresetRepository` (`:core:data/export`; the JSON is opaque there). `ExportOptions` decodes with
defaults for unknown or missing fields, so a preset saved by a newer app still opens.

**The sheet.** A Presets row of chips above Format: one tap sets every option (single and batch exports share the
sheet). The chip whose options are exactly the sheet's is selected; a picked user preset stays selected while it is
being adjusted and then offers "Update preset", plus "Rename" and "Delete"; "Save as preset" stores the current
options under a name. Picking a preset makes it usable on this device (`ExportPresets.resolve`): a folder whose grant
is not here (a preset from another device, or a forgotten folder) falls back to Pictures/Reveal, HEIF without an
encoder to JPEG. The last options are still remembered between exports as before.

**Synced** as one `setting` record per preset, id `export-preset.<uuid>` (fits ADR 0017's `[a-z0-9][a-z0-9._-]{0,63}`),
`data = {name, options, position}`; a deleted preset is a tombstone. Triggers on `export_presets` mark the record
(`SyncTriggers.EXPORT_PRESET_PREFIX`); `ExportPresetSync` (`:core:sync`) pushes and applies them; last write wins per
preset like any setting; pairing marks them all (`markAllExportPresets`). The fixed settings of `SyncStore.settings`
(the authorship) keep their own value-compare path. A whole-list setting was rejected (two devices adding a preset each
would lose one), and so was a new record kind (a server change for a small list).

**Sheet options still missing** (product.md "Export and share"), not trivial, left for a later workstream: output
sharpening (screen / matte / glossy × low / standard / high: a new pipeline operator with a CPU reference and GPU
parity), PNG and 16-bit TIFF (encoders and a 16-bit readback), AVIF and 10-bit P3 HEIF (ADR 0009 "HEIF"), "original
file copy", a megapixel/percentage preset row beyond the chips, and per-preset file-name templates beyond the three
templates and the suffix the sheet has. The preset format carries whatever `ExportOptions` gains.

## Database (version 12)

Auto-migration 11 → 12: `snapshots` and `export_presets` (tables only), exported schema `12.json`, checked by
`RevealDatabaseMigrationTest`. Sync triggers version 4 add `snapshot_insert/update/delete` (→ the version) and
`export_preset_insert/update/delete` (→ the setting record). M5/X (XMP) takes v13 if it needs a column.

## Consequences

- A photo's cells are its versions everywhere: grid, counts, selection, viewer, filmstrip, export. Code that needs the
  photo maps a cell to `PhotoRow.id`; code that needs the edit uses `versionId`.
- Ratings stay one per photo (sidecar-compatible with Hoard); per-copy culling needs a sidecar format for versions.
- Snapshots and export presets reach the other devices without a server change; snapshots ride on version records
  (bigger records: a recipe each, well within the 2 MiB limit; the oldest history steps go first when a record grows).
- Export presets store the whole `ExportOptions`, so options added later are presettable without a schema change.
