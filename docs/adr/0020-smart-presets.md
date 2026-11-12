# ADR 0020: Smart presets and the built-in presets

Status: accepted (M3/B). Date: 2026-11-12. Builds on ADR 0010 (Auto: the analysis and the rules, amended in M3 with
faces and the subject map) and ADR 0019 (presets: model, patch, strength, files, the smart slot). Platform-neutral:
the rules are numbers and formulas; the Kotlin code is one implementation of them.

## Context

`docs/plan/product.md` "Presets": smart presets are the owner's main ask. A smart preset is two parts: a
**correction** computed from each photo (exposure from the histogram and the subject/face brightness, highlight
protection and shadow lift from clipping, white balance from the scene with a skin-tone guard, optional
auto-straighten) and a **style** (the look: profile, curves, HSL, grading, effects) applied on top with the preset
strength. The rules must be simple, documented, deterministic and adjustable ("target subject brightness", "max
shadow lift"); the result shows as normal slider values; a smart preset applied to many photos adapts to each one.
ADR 0019 reserved the preset file's `smart` object and the `SmartCorrection` hook for this.

## Decisions in one place

| Topic | Decision |
| --- | --- |
| Correction | Auto's rules (ADR 0010) with per-preset targets, run on the photo's features. No second rule set. |
| Rules file | The `smart` object of a `.revealpreset` (schema below), `smartVersion` 1. A rule that is absent does not run. |
| Look | The correction is analysed through the **style's** profile (and white balance when the correction leaves it), so a B&W or film look is exposed through its own curve. |
| Order | `before` → correction (unscaled) → style patch at the strength (ADR 0019, `Preset.apply`). |
| Ownership | A running rule owns its fields: making a preset smart drops those fields from its style ("Make smart"). |
| Output | Plain slider values (exposure, the tone sliders, vibrance, white balance, straighten angle): the editor shows them as usual; one history step (`PRESET`). |
| Batch | Each photo gets its own `SmartCorrector(PhotoFeatures.of(proxy))`; the style is the same for all. |
| Built-ins | 36 presets in 9 groups (Smart, Natural, Portrait, Landscape, B&W, Film, Matte, Warm, Cool), ids from SHA-256 of their key, names in string resources. |

## The photo's features

`PhotoFeatures` (`:core:pipeline/auto`): what the rules know about a photo independently of its recipe, computed
once from the upright proxy (the editor at open, in the background; a batch per photo):

| Feature | What (ADR 0010) |
| --- | --- |
| `sample` | Auto's 512 px point-sampled copy |
| `wb` | the Auto white balance analysis (192 px blocks) |
| `faces` | BlazeFace full-range faces (box, score, six keypoints), normalised coordinates |
| `saliency` | the subject map (64 px) for photos without faces |
| `lines` | the straight lines (`ImageLines`, as Upright uses them) when straighten may run |
| `lens` | the lens profile (lines are corrected through it) |

Everything else is a pure function of these and the recipe, so a smart preset applied twice gives the same recipe.

## The rules file

```json
"smart": {
  "smartVersion": 1,
  "exposure": {"subjectBrightness": 0.17, "faceBrightness": 0.12},
  "tone": {"maxHighlights": 60, "maxShadows": 40},
  "whiteBalance": {"mode": "auto", "skinGuard": true},
  "vibrance": {"max": 20},
  "straighten": {"maxAngle": 5}
}
```

| Member | Runs | Parameters (clamped) | Sets |
| --- | --- | --- | --- |
| `exposure` | ADR 0010 rule 1 with faces | `subjectBrightness` = `AutoParams.targetGrey` (display Y the key lands on, 0.03-0.6); `faceBrightness` = `AutoParams.faceTarget` (display Y of the faces' skin, 0.03-0.6) | `light.exposure` |
| `tone` | rules 2-6 | `maxHighlights` (0-100, the largest pull), `maxShadows` (0-100, the largest lift: "max shadow lift") | contrast, highlights, shadows, whites, blacks |
| `whiteBalance` | Auto WB | `mode` `auto` (ADR 0008 estimate with the face rules when `skinGuard`) or `as_shot` | the white balance (mode Auto, or as shot) |
| `vibrance` | rule 7 | `max` (0-100) | vibrance |
| `straighten` | below | `maxAngle` (0-15°) | `geometry.angle` (crop refitted when constrained) |

Unknown members are ignored (a newer app's additions); a `smartVersion` above 1 makes the whole object unknown and
the preset applies its style only. Absent parameters take Auto's values.

## Applying (per photo)

```
rules    = SmartRules.fromJson(preset.smart)
recipe   = before
if wb:     recipe.wb = mode == auto ? AutoRules.whiteBalance(features.wb, skinGuard ? faces : []) : as shot
look     = recipe with the profile of style.apply(recipe, 1) (and its WB when wb does not run)
analysis = AutoAnalyser.analyse(features.sample, look, faces, saliency)          // ADR 0010
r        = AutoRules.compute(analysis, AutoParams(targetGrey = subjectBrightness, faceTarget = faceBrightness,
                                                  maxHighlights, maxShadows, maxVibrance = vibrance.max))
recipe  += r's exposure / tone / vibrance, for the rules that run
if straighten: recipe.angle = straighten(lines) when the lines agree (below)
result   = style.apply(recipe, strength)                                         // ADR 0019
```

The analysis ignores the Light and colour sliders (ADR 0010), so the correction does not depend on the values it
sets or on the photo's previous edit: the same photo gets the same correction whatever was there before.

**Straighten.** `Upright.straightenEstimate` on the photo's lines (lens-corrected, through the current transform):
the weighted median deviation of the near-horizontal and near-vertical lines from their axis, refined by the mean of
the lines within 2° of it. It runs only on a clear consensus: the agreeing lines carry at least **50 %** of the
axis-aligned line length and at least **0.5** of it in normalised units (about two long edges), the angle is at least
0.1° and at most `maxAngle`. Otherwise the photo is left as it is (organic scenes, deliberate tilts).

**Strength** scales the style only: the correction is a measurement of the photo, not a taste, so 50 % of a "Bright
outdoor" is the same exposure with half its look.

**Ownership.** When a preset is made smart (`Preset.smart(rules)`), the style's fields in the sections the rules set
are dropped (`SmartRules.sections`: Exposure; Contrast, Highlights, Shadows, Whites, Blacks; White balance;
Vibrance). A file that still has them is applied as written (the style then overrides the correction at its
strength): the format stays simple and an author can do it on purpose.

## Making any preset smart

The presets panel's "Make smart" on a user preset opens a chooser of the corrections (Exposure with its two
brightness targets, Tone with the two limits, White balance, Vibrance, Straighten) and stores `preset.smart(rules)`
as a new version of the preset (the same id). "Auto as a preset" is `SmartRules.AUTO`.

## Built-in presets

Pure data in `:core:pipeline/preset/BuiltInPresets.kt`: key, group, the patch as the preset file's nested JSON, the
smart rules. Ids are version 4 UUIDs from SHA-256 of `reveal-builtin-<key>` (stable across releases, so favourites
and recents survive); names and groups are string resources `preset_<key>` / `preset_group_<group>` in both
languages; the English name in the model is a fallback only.

**Design rules** (checked by `BuiltInPresetsTest` where they are mechanical):

- A style never sets exposure, white balance, lens corrections, CA, defringe or transform (they describe one photo).
- Tone by contrast, the parametric curve and gentle point curves; the faded/matte looks lift black to 0.07-0.10 and
  roll white to 0.92-0.96; colour by the mixer and grading (OkLab hues: warm 55-75°, teal 185-200°, blue 210-250°).
- Portrait looks keep skin natural: the orange band moves at most ±8 luminance and -6 saturation, hue at most -4; the
  measured skin hue shift on the faces of the test photos stays under 4° (`PRESET-CHECK`).
- No clipping disasters: the new-clipping fraction is printed per preset on the test photos and reviewed on the
  sheets.

| Group | Presets |
| --- | --- |
| Smart | Natural portrait, Bright outdoor, Moody indoor, Clean auto |
| Natural | Clean, Soft, Crisp, Bright and airy |
| Portrait | Soft skin, Warm glow, Studio, Moody portrait, Golden |
| Landscape | Vivid, Clear sky, Lush green, Golden hour, Misty |
| B&W | Classic, High contrast, Soft matte, Dramatic sky, Selenium, Sepia |
| Film | Warm film, Cool film, Faded, Cinematic, Vintage |
| Matte | Soft matte, Warm matte, Cool matte |
| Warm | Sunlit, Cosy |
| Cool | Crisp blue, Nordic |

The smart built-ins:

| Preset | Correction | Style |
| --- | --- | --- |
| Natural portrait | exposure (faces +7 %: the owner's "bright, natural"), tone (max shadow lift 35), Auto WB with skin guard, vibrance ≤ 12 | Portrait look, orange luminance +5, texture -10 |
| Bright outdoor | exposure (subject and faces +12 %), tone (highlight pull up to 80), Auto WB, vibrance ≤ 25, straighten ≤ 5° | Landscape look at 80 %, blue saturation +8 / luminance -8, clarity +8, dehaze +5 |
| Moody indoor | exposure (subject -20 %, faces -8 %), tone (shadow lift ≤ 15), Auto WB | darks -6, saturation -8, cool shadows / warm highlights, vignette -18 |
| Clean auto | every rule (Auto as a preset) and straighten | sharpening 25 |

**Review.** `PresetRenderSheetTest` (opt-in) renders each preset on up to 12 photos (before = the photo with Auto,
the usual starting point; smart presets from the unedited photo) and an overview of all presets on four photos.
Sheets of the public-domain/CC0 photos are in `docs/screenshots/presets/`; sheets of the owner's photos only go to
his private review folder.

## Consequences

- Smart presets reuse Auto's documented, calibrated rules: improving Auto improves every smart preset.
- A preset file with a smart part is still a plain file: a reader that ignores `smart` gets the style.
- The face detector runs once per photo (open or batch, about 0.2-0.5 s on a phone, in the background).
- The built-in list is code: changing a built-in is a release; their ids never change.
