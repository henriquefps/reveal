# ADR 0003: Recipe format

Status: accepted (M0, W5). Date: 2026-11-10.

## Model

An immutable data-class tree in `dev.hfps.reveal.pipeline.recipe`, pure Kotlin (detekt forbids `android.*`
there), serialised with kotlinx.serialization JSON:

```
Recipe(v=1, profile, light, colour, effects, detail, optics, geometry, masks[], heals[])
```

- **Defaults mean "no change"**: `Recipe()` renders the original; `Recipe.isDefault()` says so.
- **Only the groups used now have fields**: `profile.base` (`standard`), `light.exposure` (EV),
  `colour.whiteBalance.{temperature, tint}` (`null` = as-shot). `effects`, `detail`, `optics`, `geometry` are
  empty `data object`s; `masks` and `heals` are empty lists of `Mask(id)` / `Heal(id)`. They reserve the JSON names
  and document the plan's intent in KDoc; adding fields later is additive.
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
since the real schema is still v1. Changing a *formula* is not a schema change; it bumps `PIPELINE_VERSION`
(ADR 0002).

## History labels (diff API)

`RecipeDiff.between(before, after)` returns `HistoryStep(field, value)` list in pipeline order, for example
`(EXPOSURE, "+0.70")`, `(TEMPERATURE, "5600 K")`, `(TINT, null)` (`null` = back to as-shot). No UI text lives in
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
