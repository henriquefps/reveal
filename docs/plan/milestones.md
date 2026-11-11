# Reveal: milestones

Each milestone ends in a signed release installed on the owner's phone, with release notes in
`CHANGELOG.md`, the README screenshots updated, and the docs (ADRs, CLAUDE.md) current. A milestone
is "done" only when every item in it meets the quality bar in `product.md`. Workstreams inside a
milestone run in parallel where they do not touch the same files.

## M0 - Foundations (0.1.0)
- Android project (modules from `architecture.md`), hfps theme in Compose (tokens converted from
  `hfps-app-ui`'s `theme.css`, Inter, Phosphor), app icon (via the `hfps-repo-assets` skill and the
  Designer), signing (new keystore in the skv group `reveal`, never in the repo), `bun`-free Gradle
  scripts documented in CLAUDE.md, lint/ktlint/detekt, unit and instrumented test setup.
- `:core:image` from Hoard's image core; RAW decode to linear 16-bit; JPEG/HEIF decode.
- GPU pipeline skeleton: proxy upload, exposure + WB + camera matrix + a base tone curve + output
  transform, shown in a zoomable canvas; CPU reference + parity test.
- ADRs: 0001 stack and modules, 0002 pipeline order and working space, 0003 recipe format.

## M1 - A real editor for everyday edits (0.2.0)
- Library (sources via SAF, grid, pairs, ratings from XMP, viewer), open from other apps + the Hoard
  hand-off contract (ADR; tell the Hoard project), editor layout from `ux.md` (phone portrait and
  landscape), sliders done right.
- Light (all sliders + highlight recovery), Colour (WB with eyedropper, vibrance, saturation),
  profiles, Auto, histogram + clipping, before/after, history + undo/redo + persistence,
  copy/paste edits, crop/straighten/rotate/flip (no perspective yet).
- Export: JPEG/HEIF, size, quality, metadata, destinations, share, background batch.
- This is the first version the owner should be able to use for real photos.

## M2 - Full global toolset (0.3.0)
- Tone curve (parametric + point, per channel), colour mixer with targeted adjustment, colour
  grading, B&W with mixer.
- Effects (texture, clarity, dehaze, vignette, grain), Detail (sharpening with masking, luminance and
  colour NR) correct at 100 % with tiled full-resolution rendering.
- Optics (lens profiles from ARW data + lensfun fallback, CA, defringe), transform/perspective +
  Upright, auto-straighten.
- Tablet/foldable layout with the filmstrip; S Pen basics.

## M3 - Presets and smart presets (0.4.0)
- Built-in presets designed on real photos, live previews, strength, user presets and groups,
  import/export, favourites, batch apply.
- Smart presets (analysis rules + style), calibrated on the owner's photos, documented
  platform-neutrally in an ADR. Auto upgraded to use the same analysis.

## M4 - Masks (0.5.0)
- AI subject / background / sky (and people parts if the spike finds good enough models), linear and
  radial gradients, brush with auto-mask and pen pressure, luminance/colour range, add/subtract/
  intersect/invert, all local adjustments, overlay, mask list management. ML spike ADR first.

## M5 - Healing and finishing (1.0.0)
- Spot heal/clone with dust visualisation, content-aware remove (patch-based), red-eye.
- Versions/virtual copies, snapshots, export presets, XMP sidecar portability (round-trip with Hoard
  and darktable/Lightroom for the overlapping fields), settings complete, accessibility pass,
  worst-case pass, performance pass on the phone. 1.0.0 = the owner can cancel his subscription.

## MS - Sync and cloud library (proposal; placement to be decided by the owner)
- Self-hosted Go sync server (`reveal-sync`, on `cachyos-fps` behind Cloudflare Tunnel), edits and
  library metadata keyed by a content fingerprint, optional smart previews and originals backup,
  NAS folders served by the server, phone + tablet on the same library. Design: `sync.md`.

## Later (only if the owner asks)
AI denoise, on-device inpainting model for larger removals, HDR merge, panorama, Lightroom preset
import, HDR (Ultra HDR / gain map) export, a Hoard-side shared `:core:image` dependency.
