# ADR 0003: Recipe format

Status: accepted (M0, W5). Date: 2026-11-10. Amended for M1 (schema contract, 2026-11-10): see "Fields (M1)".

## Model

An immutable data-class tree in `dev.hfps.reveal.pipeline.recipe`, pure Kotlin (detekt forbids `android.*`
there), serialised with kotlinx.serialization JSON:

```
Recipe(v=1, profile, light, colour, effects, detail, optics, geometry, masks[], heals[])
```

- **Defaults mean "no change"**: `Recipe()` renders the original; `Recipe.isDefault()` says so.
- **Groups without operators yet** (`effects`, `detail`, `optics`) are empty `data object`s; `masks` and `heals` are
  empty lists of `Mask(id)` / `Heal(id)`. They reserve the JSON names; adding fields later is additive. The fields
  of `profile`, `light`, `colour` and `geometry` are in "Fields (M1)".
- Every field has an explicit `@SerialName`; Kotlin renames never change stored JSON.
- Validation lives in `init` blocks (finite numbers); ranges are enforced by the UI and clamped by operators
  where unsafe (temperature is clamped to 2000-15000 K by the white-balance model).

## JSON and migration policy

`RecipeCodec` writes every field (`encodeDefaults`) so a stored recipe is self-describing, with `v` first.
Reading:

- the `v` field selects the chain of migrators `vN -> vN+1 -> ... -> current`; each is a pure function on the
  `JsonObject` and the codec stamps the new `v`;
- **unknown fields at the current version are ignored**: they come from a newer app that made an additive change
  (a new optional field) without bumping `v`;
- **a `v` above the app's own throws `UnsupportedRecipeVersionException`**: an older app must never open and
  re-save, and so silently drop, a newer app's edits;
- missing or non-integer `v`, bad JSON, or a missing migrator throws `InvalidRecipeException`.

Rules for changing the schema: *additive* (new optional field with a default that means "no change"): no version
bump, no migrator. *Breaking* (rename, move, change of meaning or unit, removal): bump `RECIPE_VERSION`, add the
migrator `RECIPE_MIGRATORS[old]`, add a test with a stored v-old fixture; the codec test also asserts the
production table covers every version below the current one. The machinery is tested with a synthetic 1 -> 3 chain
since the real schema is still v1. M1 is entirely additive (see below): **no bump, no migrator**, M0 recipes decode
unchanged and a pre-M1 app that opens an M1 recipe ignores the new fields (and would drop them on re-save; acceptable
because recipes never leave the device yet). Changing a *formula* is not a schema change; it bumps `PIPELINE_VERSION`
(ADR 0002).

## Fields (M1)

All additive, defaults = no change, still `v=1`, `PIPELINE_VERSION` unchanged (the fields have no operators yet).
Ranges are *not* enforced in `init` (only finiteness, plus structural limits of geometry); `RecipeRanges` is the
single source of truth for sliders and for operator clamping. "Bipolar" controls fill from the centre; neutral is
the value that means no change.

| Field (JSON) | Kotlin | Type, unit | Range | Default | Step (fine) |
|---|---|---|---|---|---|
| `profile.base` | `Profile.base` | enum `standard` `neutral` `vivid` `portrait` `landscape` `monochrome` `camera_match` | | `standard` | |
| `profile.amount` | `amountPercent` | percent | 0..200 | 100 | 1 |
| `light.exposure` | `exposureEv` | EV | -5..+5 | 0 | 0.01 |
| `light.contrast` `highlights` `shadows` `whites` `blacks` | same | unitless, bipolar | -100..+100 | 0 | 1 |
| `colour.whiteBalance.mode` | `mode` | enum `as_shot` `auto` `daylight` `cloudy` `shade` `tungsten` `fluorescent` `flash` `custom` | | `as_shot` | |
| `colour.whiteBalance.temperature` | `temperatureK` | kelvin, RAW only, `null` = as shot | 2000..15000 | `null` (slider start 5500) | 50 (10) |
| `colour.whiteBalance.tint` | `tint` | RAW only, `null` = as shot, + = magenta | -150..+150 | `null` (start 0) | 1 |
| `colour.whiteBalance.relTemperature` | `relTemperature` | display-referred only, + = warmer | -100..+100 | 0 | 1 |
| `colour.whiteBalance.relTint` | `relTint` | display-referred only, + = magenta | -100..+100 | 0 | 1 |
| `colour.vibrance` `saturation` | same | bipolar | -100..+100 | 0 | 1 |
| `geometry.crop` | `crop` | `{left,top,right,bottom}` normalised, y down, or `null` = full | 0..1, positive size | `null` | |
| `geometry.angle` | `angle` | degrees, + = clockwise | -45..+45 | 0 | 0.1 |
| `geometry.rotate` | `rotate` | quarter turns clockwise | 0..3 | 0 | |
| `geometry.flipH` `flipV` | | boolean | | false | |
| `geometry.aspect` | `Aspect(preset, w, h, locked)` | `free` `original` `1_1` `4_5` `5_4` `3_2` `2_3` `16_9` `9_16` `custom`; `w:h` used by `custom` | w,h > 0 | `free`, 1:1, unlocked | |
| `geometry.constrain` | `constrain` | boolean: straightening and the crop handles keep the crop inside the image (an editing option stored with the edit, like `aspect`; no pixel change by itself) | | `true` | |

Decisions:

- **`profile.base` keeps its JSON name** (no rename, so no migration); the enum only gains values. `amount` scales the
  look's departure from `standard`; `standard` ignores it.
- **White balance, one recipe for RAW and display-referred inputs: two field pairs, not one reused pair.**
  `temperature`/`tint` are absolute (kelvin, RAW); `relTemperature`/`relTint` are relative (-100..+100, JPEG/PNG).
  Reusing one pair would make a stored 5600 mean kelvin or +5600 % depending on the file it is pasted onto. The
  operator reads the pair that matches the `PipelineInput` and ignores the other; the UI shows the same one.
  `mode` records which preset wrote the values (Auto and the presets write temperature/tint, so rendering never
  depends on `mode` except `as_shot` = no override). Copy/paste between a RAW and a JPEG therefore carries both
  pairs harmlessly.
- **Highlight recovery is not a field**: it is always-on reconstruction, not a user control.
- **Geometry**: `crop` is a normalised rectangle in the *oriented, un-rotated* image (EXIF orientation applied;
  `angle`, `rotate` and the flips not). The editor shows the full image while cropping and the crop follows the
  transform. `aspect` (and its lock) is stored in the recipe so undo and copy restore the frame constraint.
  **Masks and heals stay in image coordinates** (the oriented, un-cropped, un-rotated space), so they follow every
  geometry change without being rewritten. The operator's mapping (crop centre, clockwise-as-seen straightening about it,
  quarter turns then flips of the final picture) is in ADR 0002, "Geometry (slot 13)". `constrain` (M1/G, additive,
  default `true`) is not in the history diff.
- **`RecipeRanges`** (`recipe/RecipeRanges.kt`) holds one `ControlRange` per scalar control (id, min, max, default,
  step, fine step, bipolar, neutral, value kind) and `ValueFormat` (`+0.70`, `−12` with U+2212, `5600 K`, `100 %`,
  `1.5°`; locale-independent). The temperature descriptor's default (5500 K) is only where the slider starts when
  the recipe holds `null`.
- **Copy/paste groups** (`RecipeGroup`): `profile`, `light`, `colour`, `effects`, `detail`, `optics`, `geometry`,
  `masks`, `heals`. Each recipe field is in exactly one (the recipe's top-level properties); each `HistoryField` names
  its group. `RecipeGroup.copy(source, target)` pastes a group, `reset(target)` clears it.

## History labels (diff API)

`RecipeDiff.between(before, after)` returns `HistoryStep(field, value)` list in pipeline order, for example
`(EXPOSURE, "+0.70")`, `(TEMPERATURE, "5600 K")`, `(TINT, null)` (`null` = back to as-shot). Enum-valued fields
(look, white-balance mode, aspect) carry the lower-case key (`camera_match`) which `HistoryLabels` maps to a localised
name (`history_value_*`); crop carries `"crop"` (or `null` when removed) and the flips carry `null`. No UI text lives in
the model: `HistoryField` picks a string resource (`history_exposure` = "Exposure %1$s", pt-BR "Exposição %1$s",
resolved by `HistoryLabels.format(context, step)`), and values are formatted locale-independently with an explicit
sign and U+2212 as the minus (same as the sliders' value labels). Changes below 1e-4 are not steps.

## Committed steps vs per-frame updates

- **Per frame** (slider drag): the editor holds the working `Recipe` as UI state and pushes each new value to the
  renderer (`GpuDevelopRenderer.setRecipe`), which coalesces to the latest. Nothing is written to history, to Room
  or to disk, and no diff is computed.
- **Committed** (slider release, preset applied, reset): the editor computes `RecipeDiff.between(committed,
  working)`, and if it is non-empty appends one history step (the diff's labels, one step per commit, not one per
  field if the gesture changed several), stores the new recipe JSON in Room and moves `committed` forward. Undo/redo
  swap `committed` and push it to the renderer.
- A drag that ends where it started is empty diff and no step.

## History engine (M1, history workstream)

`EditHistory` (`recipe/EditHistory.kt`, pure Kotlin, immutable) is the undo/redo model of one version: `entries`
(each a `HistoryEntry` = the full recipe after the step + its `StepLabel`), `cursor` (-1 = `base`, the default
recipe) and `current`. Semantics, all JVM-tested (`EditHistoryTest`):

- `commit(recipe, action?, groups)` appends a step after the cursor, labelled with `RecipeDiff.between(current,
  recipe)`; **a commit after an undo drops the steps above the cursor** (a new branch, no tree). An empty diff is no
  step, even for a named action ("Reset all" on an unedited photo adds nothing); the editor then snaps its working
  recipe back to `current`.
- `undo` / `redo` move the cursor by one; `goTo(position)` jumps to any step (the history list) and keeps the later
  steps for redo. Unlimited.
- **What is one step:** slider release, numeric entry, double-tap reset of a slider, a chip (look, WB mode), the
  eyedropper, a panel reset, Reset all, Reset to as opened, a paste, "Previous", and (for the workers that add them) a
  mode's Done, Auto and a preset. Never a drag frame.
- **Named steps:** `StepLabel(changes, action, groups)` with `HistoryAction` `RESET_ALL`, `RESET_GROUP` (+ the group),
  `AS_OPENED`, `PASTE` (+ the pasted groups), `PREVIOUS`, and `AUTO` / `PRESET` reserved for their workstreams. The
  list shows the action's name ("Paste edits", "Reset Light") with the changes underneath; an unnamed step shows its
  only change ("Exposure +0.70") or the first one "and N more" (`HistoryLabels.format`, plural resource).
- **Resets:** reset panel = `RecipeGroup.reset` of the panel's group; Reset all = `Recipe.DEFAULT`; **Reset to as
  opened** = the default recipe plus the user's default settings (default profile, open with Auto) once Settings
  stores them; until then it equals Reset all (`EditorViewModel.asOpened`, the seam for the settings worker).

Copy/paste (`RecipePaste`): copy takes the **whole** recipe; the **paste chooser** picks the groups (all, or per
`RecipeGroup`; each row lists what the copied edit holds for that group). `paste(source, target, groups)` replaces
each chosen group of the target as a whole (a field the source left at default resets it on the target) and leaves
the other groups untouched. The default choice is every group but geometry (a crop rarely fits another frame), and
the last choice is remembered. **"Previous"** applies the last edited other photo's recipe with the default groups
(no chooser: one tap). White balance carries both pairs, so a paste between RAW and JPEG is harmless (see above).
