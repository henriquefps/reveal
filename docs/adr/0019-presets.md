# ADR 0019: Presets

Status: accepted (M3/A). Date: 2026-11-12.

## Context

`docs/plan/product.md` "Presets" asks for presets with a live mini-preview of each on the current photo, a strength
slider (0-200 %) that scales every parameter the preset sets, user presets made from the current edit with a chooser
of which settings to include, groups, favourites, recently used, import/export as documented files, and batch apply.
`docs/plan/distribution.md` "Preset packs" adds that the file format must let packs be sold as content later: a
versioned, documented format with pack metadata (name, author, cover, licence, link), packs shown as groups, and a
"try on my photos" before keeping a pack. Smart presets (a correction computed from each photo, then the style) are
built by M3/B on top of this model. ADR 0003 defines the recipe a preset patches, ADR 0006 the database, ADR 0017/0018
sync (the `preset` record kind is reserved there).

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Model | `Preset(id, name, patch, group, author, version, createdAt, smart)` in `:core:pipeline/preset` (pure Kotlin). `id` is a version 4 UUID, its identity everywhere (files, database, sync, favourites). |
| Patch | `PresetPatch`: a **partial recipe**, a map from field path (`light.exposure`, `colour.mixer.hue.red`, `light.curve.rgb`) to the value in the recipe's own JSON encoding. Unset fields keep the photo's value. |
| Fields | `PresetFields`: derived from the default recipe's JSON (every leaf; arrays and nullable values are leaves), minus the per-frame/per-photo ones (see "Fields"). A field added to the recipe later is presettable without code: a number scales linearly, anything else switches. |
| Strength | 0-200 % from the photo's current value to the preset's and beyond, per field kind (see "Strength"). Exactly the photo at 0 and exactly the preset at 100 %. |
| History | Applying is one `HistoryAction.PRESET` step labelled with the preset's name; moving the strength slider replaces that step live (working recipe per frame) and commits on release (the step is replaced, not added). |
| Smart slot | `Preset.smart: SmartPart?` carries the file's `smart` object verbatim; `SmartCorrection` (bound in `DataModule`, `SmartCorrection.NONE` until M3/B binds its own) resolves it per photo before the style patch, which alone is scaled by strength; the editor (apply, strength, previews) and the batch worker use the same binding. |
| Files | `.revealpreset`: one JSON object. `.revealpack`: a zip with `manifest.json`, the presets, a licence text and a cover. Strict validation, size limits, no code. |
| Library | Room v7: `preset_packs`, `preset_groups`, `presets`, `preset_marks` (favourite, last used; keyed by uuid so built-ins have them too). |
| Sync | Each preset is one `preset` record (ADR 0017) with its group and pack inside; deletes are tombstones; trial packs do not sync. |

## Fields

A field is a leaf of the recipe JSON (ADR 0003) by its dotted path. **Not presettable** (ignored in files):
`v`, `masks`, `heals` (image coordinates of one photo), `geometry.crop`, `angle`, `rotate`, `flipH`, `flipV`,
`aspect`, `constrain` (one frame), `geometry.transform.upright` and `guides` (the result of analysing one photo) and
`optics.caRed` / `caBlue` (the automatic CA estimate, measured per photo). Everything else is: profile (look, amount,
treatment), white balance (both pairs and the mode), light, the tone curve, vibrance/saturation, mixer, grading, grey
mixer, effects, detail, lens corrections, CA removal switch, defringe and the transform sliders.

**Sections** (`PresetSection`) group the fields for the "Create preset" chooser: Profile, Treatment (B&W and its grey
mixer), White balance, Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Tone curve, Vibrance, Saturation,
Colour mixer, Colour grading, Texture, Clarity, Dehaze, Vignette, Grain, Sharpening, Noise reduction, Lens
corrections, Chromatic aberration, Defringe, Transform. Each field is in exactly one section. A chosen section sets
**all** its fields, also those at their default (a preset can reset clarity to 0). The chooser starts with the
sections the edit changed, minus the photo-specific ones (`PresetSection.PHOTO_SPECIFIC`: white balance, exposure,
lens, CA, defringe, transform): those describe one photo more than a style.

## Strength

`PresetPatch.apply(before, s, context)`, `s` in [0, 2]; `b` = the photo's value (the recipe the preset is applied
to), `p` = the preset's. Per `FieldKind`:

| Kind | Fields | Rule |
| --- | --- | --- |
| `LINEAR` | additive sliders: exposure (EV), the tone sliders, vibrance/saturation, relative WB, profile amount, HSL and grey bands, grading luminance/blending/balance, parametric curve and splits, effects, detail amounts, optics amounts, transform sliders | `b + s (p − b)`, clamped to the control's `RecipeRanges` range |
| `RATIO` | multiplicative: sharpening radius, transform scale | `b · (p / b)^s` (log space; linear if either is ≤ 0), clamped |
| `KELVIN` | absolute temperature | linear in mired: `1e6 / (1e6/b + s (1e6/p − 1e6/b))`, clamped to 2000-15000 K |
| `TINT` | absolute tint | linear, clamped |
| `GRADE_HUE` + `GRADE_SATURATION` | a grading wheel | both set: the tint as a 2D vector `(sat·cos h, sat·sin h)` blends linearly (a tint fades through grey instead of rotating round the wheel); hue alone: the shortest arc; saturation alone: linear |
| `CURVE` | point curves (RGB, R, G, B) | both curves (monotone splines; straight line = identity) are sampled at the union of their points' x (16 evenly spaced x if that exceeds the 16-point limit), y blends linearly, clamped to [0, 1]; a straight result is stored empty |
| `SWITCH` | look, treatment, WB mode, lens corrections and CA switches, any enum/boolean | the preset's value from **50 %**, the photo's below |

`null` absolute white balance means *as shot*. When the photo's as-shot kelvin/tint are known (`ApplyContext`, the
editor has them from the input) a `null` side blends from that value, and a preset that says "as shot" lands back
on `null` at 100 % and above. Without them (the library's batch apply) the absolute pair switches at 50 %. At `s = 0`
the result is the photo, at `s = 1` exactly the preset's values (no arithmetic). Values past a range at 200 % clamp.
Tested per kind in `PresetStrengthTest`.

## Applying and history

The editor applies a preset as **one history step** (`HistoryAction.PRESET`, `StepLabel.name` = the preset's name,
`groups` = the recipe groups it touches; the label JSON gains `"name"`). The panel keeps the *applied preset*
session: the recipe before it, the preset and the strength. Moving the strength slider renders
`preset.apply(before, s)` per frame (nothing is written) and **on release replaces the session's step**: the history
cursor moves back one and the new recipe is committed as the same named step (`HistoryOp.Move` + `HistoryOp.Commit`,
which drops the replaced step; no new persistence op). Strength 0 undoes the step (the cursor goes back to the base; redo or dragging the strength up again brings it back). The session ends when the
history moves away from its step (another edit, undo, another preset). Tapping a second preset applies it on top of
the current edit (presets stack, as in other editors); the session's "before" is then the recipe with the first one.

## Files

### `.revealpreset`

UTF-8 JSON, at most 256 KiB, MIME `application/vnd.hfps.reveal.preset+json`:

```json
{
  "format": "reveal-preset",
  "formatVersion": 1,
  "recipeVersion": 1,
  "id": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "name": "Soft matte",
  "group": "Matte",
  "author": "Ana Example",
  "version": 2,
  "created": "2026-11-12T10:00:00Z",
  "patch": {
    "profile": {"base": "portrait", "amount": 90},
    "light": {"contrast": -15, "curve": {"rgb": [{"x": 0, "y": 0.06}, {"x": 0.5, "y": 0.52}, {"x": 1, "y": 0.96}]}},
    "effects": {"grainAmount": 20, "grainSize": 25, "grainRoughness": 50}
  },
  "smart": {"…": "defined by M3/B"}
}
```

| Member | Rule |
| --- | --- |
| `format` | `"reveal-preset"`, else `NOT_A_PRESET` |
| `formatVersion` | integer ≥ 1; above this app's (1) → `NEWER_VERSION` |
| `recipeVersion` | the ADR 0003 `RECIPE_VERSION` the patch is written in; above this app's → `NEWER_VERSION`. When the recipe schema has a breaking change, patches get their own migrators (none exist yet) |
| `id` | lower-case version 4 UUID |
| `name` | 1-80 characters, trimmed, no control characters |
| `group`, `author` | optional, 0-80 characters, no control characters |
| `version` | optional integer ≥ 1 (default 1): the author's revision |
| `created` | optional ISO-8601 instant |
| `patch` | a nested partial recipe: the recipe's JSON names (ADR 0003), only the members it sets. Numbers must be finite numbers (clamped to their range), booleans booleans, enums one of the recipe's serial names, point curves 0 or 2-16 `{x, y}` points in [0, 1] with increasing x, absolute temperature/tint a number or `null` (as shot). A wrong type → `INVALID_VALUE`. Unknown members, unknown enum values (a newer app's) and the not-presettable fields are **ignored** and reported, never applied. |
| `smart` | optional object, at most 64 KiB, kept verbatim (M3/B defines it) |

Unknown top-level members are ignored (additive changes by a newer app). Writing uses the same shape, pretty-printed,
every member present. `PresetFiles.encode` / `decode` (`PresetFilesTest`).

### `.revealpack`

A zip (MIME `application/vnd.hfps.reveal.pack+zip`) with `manifest.json` at its root:

```json
{
  "format": "reveal-pack",
  "formatVersion": 1,
  "id": "6a5b4c3d-2e1f-4a0b-8c9d-0e1f2a3b4c5d",
  "name": "Golden hour",
  "author": "Ana Example",
  "version": 3,
  "link": "https://example.com/golden-hour",
  "cover": "cover.jpg",
  "licence": {"name": "Personal use", "file": "LICENCE.txt"},
  "groups": [{"id": "1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d", "name": "Warm"}],
  "presets": [{"file": "presets/1.revealpreset", "group": "1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d"}]
}
```

`id`, `name`, `groups` (1-64, ids unique UUIDs), `presets` (1-1000, each file a valid `.revealpreset`, ids unique,
`group` one of `groups`), `licence.name` are required; `author`, `version`, `link` (an `https` URL, ≤ 512
characters), `cover` (JPEG or PNG by magic bytes, ≤ 4 MiB) and `licence.file` (UTF-8 text, ≤ 64 KiB) are optional. A
preset's own `group` member is replaced by its pack group's name, an empty `author` by the pack's.

**Hostile files.** The reader streams the zip once, never writes an entry to disk under its name, and refuses:
entry names that are empty, absolute, contain `\`, `:`, control characters, an empty, `.` or `..` segment, are over
255 characters, or repeat (`UNSAFE_ENTRY`); more than 1,100 entries, a file over 32 MiB as received, an entry over
4 MiB or more than 48 MiB in total **counted while inflating** (a zip bomb stops at the limit whatever its headers
say) (`TOO_LARGE`); anything that is not a zip, a missing manifest or referenced file (`MALFORMED`). Packs carry no
code, only JSON, text and an image; the cover is decoded by the platform's image decoder with a size bound before it
is shown. `PresetPacks.read` / `write`, `PresetFilesTest`.

### Import and export

Import: the presets panel's ⋯ (SAF, `.revealpreset`, `.revealpack` or a share of either), "Open with Reveal" on the
file and Share to Reveal (`MainActivity` routes both MIME types and the extensions to the import). A `.revealpreset`
lands in its `group` (a user group with that name, created if needed); an existing id is updated when the file's
`version` is higher, else kept. A pack is imported as a **trial**: its groups show in the panel with the pack's
name, author and licence and a banner "Keep pack / Remove", so the presets can be tried on the user's own photos
(live previews) before keeping it. A newer version of a kept pack replaces its presets. Export: a preset as
`.revealpreset`, a user group as `.revealpack` (through the share sheet or SAF).

## Library (database v7)

| Table | Row |
| --- | --- |
| `preset_packs` | `uuid`, name, author, version, licence name and text, link, `cover_file` (under `files/preset-covers`), `trial`, `imported_at` |
| `preset_groups` | `uuid`, name, `position`, `pack_id` (null = the user's group), `created_at` |
| `presets` | `uuid`, `group_id` (cascade), name, author, version, created/updated, `position`, `patch_json` (the nested partial recipe), `smart_json`, `origin` (`USER`, `IMPORTED`, `PACK`, `SYNC`) |
| `preset_marks` | `uuid` (a preset's, built-in or not) → `favourite`, `last_used_at` |

Auto-migration 6 → 7 (tables only). Built-in presets (M3/B) are not rows: a `BuiltInPresetSource` provides them,
and they have marks like the others. Operations: create from the current edit, rename, move to a group, reorder
(within a group, and the groups), duplicate, delete; groups: create, rename, reorder, delete (with their presets);
favourites; recents (last 12 applied).

## Sync

A preset is a `preset` record (ADR 0017) with `data` = the `.revealpreset` object plus `groupId`, `groupName`,
`groupPosition`, `position`, `favourite`, `origin` and, for a pack's presets, `pack` (`id`, `name`, `author`,
`version`, `licenceName`, `licenceText`, `link`; the cover is not synced). SQLite triggers (`SyncTriggers` v2) mark a
preset dirty when its row, its group's name/order or its pack's trial flag change, and a delete leaves a tombstone.
Presets of a pack on trial are not pushed until it is kept. Applying a pulled preset creates its group (and pack) by
uuid when missing; a tombstone deletes it; empty groups are removed. Conflicts are last-write-wins (ADR 0017: the
loser of a non-version record is dropped). Favourites of built-ins and recents stay on the device.

## Batch

The library's selection menu "Apply preset…" picks a preset and a strength and enqueues `PresetBatchWorker`
(WorkManager, like exports): one history step per photo (`PRESET`), in transactions of 25, with a progress
notification and Cancel; a cancel keeps the photos already done. Each photo can undo its own step. Smart presets'
correction is resolved per photo by M3/B's `SmartCorrection` inside the same worker.

## Previews

The panel renders each visible preset on the current photo with the GPU (`PresetThumbnails`, see "Presets panel" in
CLAUDE.md): one small offscreen renderer on its own thread, sharing nothing with the editor's surface, uploads a
256 px copy of the editor's proxy once per photo, renders `preset.apply(committed, 1)` per thumbnail in visibility
order (the newest request first), caches bitmaps by (photo, committed recipe, preset id, version) in memory, and
drops stale requests when the user scrolls. The editor's frame loop is never blocked by it.

## Measurements (owner's phone, debug build)

Galaxy S24 Ultra (`RXCX109VTTM`), debug APK, the CC0 ZV-E10 sample open, 60 presets in one group, the panel at its
half position, 20 `input swipe` scrolls of the grid (`PresetsMeasurementsTest`; the phone reported 60 Hz during the
runs and has an accessibility service enabled, so Compose builds and diffs its semantics tree every frame):

| | Frames | p50 | p90 | p99 | Past deadline | Compose (animation stage) p90 |
| --- | --- | --- | --- | --- | --- | --- |
| Presets grid, live previews | ~710 | 13.9-14.6 ms | 20-22 ms | 33-39 ms | 5-7 % | 9.7-10.4 ms |
| Presets grid, previews off | 708 | 13.8 ms | 20.3 ms | 28.8 ms | 6.4 % | 11.6 ms |
| Light panel, same swipes (baseline) | ~690 | 10.1 ms | 15.4 ms | 31.5 ms | 2.9 % | 2.8 ms |

Thumbnails: 54 rendered per run on the preview renderer's own thread, p50 6 ms, p90 7-11 ms each (360 px, every tool
the preset sets). Turning the previews off does not change the frame times: the extra cost against the Light panel is
Compose's accessibility pass (`getAllUncoveredSemanticsNodes`, about 4 ms per frame in this debug build, called every
frame because a lazy grid re-places its items while it scrolls; Perfetto trace). Each cell is therefore one semantics
node (`clearAndSetSemantics`). A release build (R8, no debug Compose) is expected to be well under these numbers; the
orchestrator measures it with the release install.

## Consequences

- A preset file is plain, documented JSON: third parties (and a later Lightroom `.xmp` importer) can write one.
- Adding a recipe field makes it presettable at once; giving it a non-linear kind is one line in `PresetFields`.
- Packs are data only; selling them later needs no app change beyond a purchase flow.
- Strength switches (look, treatment, modes) jump at 50 %; a smoother blend between two looks would need the
  pipeline to render two looks, which no editor does.
