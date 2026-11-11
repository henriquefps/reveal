# Reveal: AI editing assistant (proposal, after 1.0)

Status: owner's idea (2026-11-11), **noted only, not scheduled and not a priority** ("Later" in `milestones.md`).
If it is ever built: after 1.0, on top of MS (sync server), M3 (smart preset analysis) and M4 (masks).

## Idea

The owner writes a prompt ("warmer, brighter faces, moody sky, like a film photo") and gets back an **edit**, not a new
image: a recipe (ADR 0003) with values for Reveal's own controls. Unlike generative editors (Gemini, etc.) it never
invents pixels or changes faces, every value is visible on the sliders, and the result is one undoable history step
the owner can keep tweaking.

## How it works

1. The app sends the sync server: a small developed preview (about 1024 px, sRGB JPEG, with the current recipe),
   EXIF summary, the current recipe, the prompt, and optionally the owner's past edits of similar photos (his style).
2. The server asks a vision-capable model (Claude through the Anthropic API, key in `skv`; or a local model when it is
   good enough) to answer **only with a recipe patch** that follows a JSON schema generated from `Recipe.kt` (field
   names, ranges, steps), plus a one-line explanation.
3. **Look, then refine:** the server renders the patch with Reveal's CPU reference (pure Kotlin, runs on the JVM on the
   server), shows the model the result and lets it adjust (2-3 rounds), so it judges what it did instead of guessing.
4. The app applies the patch as one history step ("AI: <prompt>"), with before/after; the owner can undo or adjust.
   Masks (M4) are requested by name ("subject", "sky", "background") and generated on the device, so local edits work
   too. Batch: one prompt over a selection, each photo gets its own patch.

## Rules

- Output is validated against the schema and clamped to ranges; nothing but recipe fields is accepted.
- Privacy: the owner's family photos go to the model only as the small preview, only from his own server, only when
  he asks; a local-model mode for photos he never wants to leave the house. Stated in the UI.
- Cost visible per request; no background use.

## Prepare before it

- Keep every control in the recipe schema with documented ranges (already ADR 0003); generate a JSON schema from it
  in M3 (presets need the same).
- Make the CPU reference runnable outside Android (it already is: pure Kotlin) as a small server-side renderer.
