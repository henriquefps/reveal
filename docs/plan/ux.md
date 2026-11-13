# Reveal: layout, visual style and interaction

Reveal is native (Kotlin + Jetpack Compose), but it must look like a sibling of the owner's web and
Capacitor apps (Hoard, Finances, the Designer), which follow the **`hfps-kit`** skill
(`~/.agents/skills/hfps-kit/react/GUIDE.md`, tokens in `brand/theme.css`; Compose: `android/GUIDE.md`). Read that skill before any
UI work and translate it to Compose with the rules below. Install Hoard 1.7.x from
`~/Documents/my-apps/hoard-release/` on an emulator to see the family look on Android.

## The hfps identity in Compose

| hfps-app-ui (web) | Reveal (Compose) |
| --- | --- |
| OKLCH tokens in `theme.css` (`--background`, `--card`, `--primary` …, light and dark) | A `RevealTheme` with the same tokens converted to sRGB (and Display P3 where it matters) as a `ColorScheme` + an extra `RevealColors` for `warning`, `muted-foreground`, borders. Convert with a script from `theme.css`, check the WCAG pairs the skill lists. |
| Brand blue `oklch(0.5 0.14 255)` light / `0.72` dark | `primary`; used for the active tool, slider fills, toggles, primary buttons, focus rings. Never for large areas. |
| Inter | Inter bundled (variable font, OFL), whole app; tabular figures for values. |
| Phosphor icons | Phosphor on Compose (a maintained Compose port, or ImageVectors generated from the Phosphor SVGs by a script; "regular" weight, "fill" for active tool states). No Material icons mixed in. |
| Type scale (12 eyebrow / 14 UI / 15-16 body / 18 titles / 20-24 numbers, nothing below 12) | Same sizes in sp; the uppercase eyebrow style only for small section labels ("LIGHT", "TONE CURVE"); sentence case everywhere else. |
| Radius 0.75rem; cards `rounded-xl`, controls `rounded-md` | Cards 16-17dp, controls 10dp, chips full. |
| Shell: fixed 56dp header on `--background`, ONE card filling the rest with a margin, content scrolls inside it | Library and settings screens use exactly this: top bar 56dp on `background`, a single `card` surface with 8dp side/bottom margin (respecting system bars insets) and `rounded-xl` + hairline border, lists/grids scroll inside the card. |
| Status colours: primary / warning / destructive / muted | Same; clipping warnings, failed exports, unplugged volumes use them. |
| Drawer on phones for forms (Vaul) | `ModalBottomSheet` with a drag handle for forms and choosers on phones; dialogs/side sheets on tablets. |
| Motion: short, purposeful (150-250 ms), interruptible; gestures follow the finger | Same rules (see the skill's `references/gestures.md` and `design-eng.md`): sheets and panels track the finger and fling with velocity; nothing animates that the user did not cause. |

## The editor is a darkroom

The editor screen is **always dark** (independent of the theme setting) and its photo surround is a
**neutral grey with zero chroma** (e.g. `#1c1c1e` / `#2a2a2c`, never the bluish hfps neutrals),
because tinted surroundings bias colour judgement. hfps shows up in the chrome: the blue accent,
Inter, Phosphor, radii, the raised panel card, the type scale. Panels sit on a dark neutral card
slightly lighter than the surround, with the hfps hairline border and radius.

## Phone, portrait (the main layout)

```
┌──────────────────────────────────────┐
│ ←   DSC01234.ARW    ↶  ↷  ◐  ⋯  [Export] │  top bar 56dp: back, file name (13sp mono, ellipsised),
├──────────────────────────────────────┤      undo, redo, before/after, more, primary Export
│                                      │
│              PHOTO                   │  canvas: fit, neutral surround, pinch/double-tap zoom;
│      (histogram, top-left, toggle)   │  hold = original; tap = hide UI
│                                      │
├──────────────────────────────────────┤
│ ╭──────────────────────────────────╮ │  panel card (bottom sheet with 3 snaps:
│ │ LIGHT                     Auto  ⟲│ │   collapsed = tool strip only,
│ │ Exposure            ─────●── +0.70│ │   half = panel (default),
│ │ Contrast            ───●──── −12 │ │   expanded = tall panel for curves/HSL/masks)
│ │ Highlights          ●─────── −64 │ │
│ │ …                                │ │
│ ╰──────────────────────────────────╯ │
│ Presets Auto Crop Light Colour Effects│  tool strip: horizontally scrolling icon + label,
│ Detail Optics Masks Heal             │  active = primary colour + filled icon
└──────────────────────────────────────┘
```

- The photo always stays visible and as large as possible: the canvas resizes to the space above the
  panel, with a smooth transition when the panel snaps.
- **While a slider is dragged, everything else in the panel fades out** and the panel shrinks to that
  one slider, so the photo gets the room; it comes back on release.
- The panel title row: eyebrow name, a panel-level Auto where meaningful, reset (⟲). Sub-sections
  (e.g. Tone curve inside Light, Colour mixer inside Colour) are rows that open a sub-panel with a
  back arrow, not long scrolls.
- Crop, Masks and Heal are **modes**: entering one swaps the tool strip for that mode's own controls
  (aspect chips + straighten dial for crop; mask list + "+" for masks; heal/clone + size for heal)
  and a "Done" check; the canvas shows the mode's overlays.

## Landscape and tablets

- **Landscape phone:** photo on the left, panel as a right column (360dp), tool rail vertical on the
  far right; top bar collapses to icons.
- **Tablet / foldable unfolded (≥ 840dp):** in landscape, photo centre, panel right (320-560dp,
  resizable, 400dp by default) and the tool rail; in portrait and on a foldable's almost square inner
  screen, the photo takes the whole width and the panel is docked at the bottom (full width, its top
  edge resizes its height, 360dp by default) above the tool strip as the bottom row. Which one: the
  arrangement that shows a 3:2 photo larger (ADR 0016 "Docked panel"). A filmstrip of the current
  folder sits under the photo (hide-able) to move between photos without leaving the editor; library
  uses a two-pane layout (sources list + grid). Stylus (S Pen) works for brushes
  with pressure for size/flow; the finger pans/zooms while the pen paints (no tool switch).

## Controls

- **Sliders** are the heart of the app; get them perfect:
  - full-width row: label left (14sp), value right (tabular, 14sp, `+0.70`, `−12`, `5600 K`), the track
    below or between; bipolar controls fill from the centre, unipolar from the left; neutral = centre
    tick.
  - **relative drag** anywhere on the row (the thumb never jumps to the finger), fine control when the
    finger moves vertically away from the row (the further, the finer), velocity-independent.
  - haptic tick when crossing the neutral value and at the ends; a short snap zone at neutral.
  - double-tap = reset; tap the value = numeric input with ± stepper; long-press the label = show the
    effect isolated where that helps (sharpen mask, noise detail).
  - white balance and HSL sliders have **gradient tracks** showing the colour direction.
- **Curves:** large square editor with the histogram behind it, channel chips (RGB, R, G, B),
  parametric/point switch, add point by tap, drag to move, drag off the edge to remove, fine drag.
- **Colour wheels:** wheel with a draggable puck for hue/saturation, luminance slider below; switch
  between the 3-way view and one large wheel.
- **Masks** (as built in M4/B, docs/adr/0022 "M4/B (editor)"): a panel, not a mode (the tool strip
  stays). At its top the masks as a row of coverage thumbnails and "+" (an empty panel lists the
  choices: Subject, Sky, Background, People and its parts; linear, radial, brush; colour and
  luminance range). The selected mask: name (tap to rename), eye, overlay toggle, ⋯ (rename,
  duplicate, invert, move, delete); Amount; its parts (type, how each combines, invert, remove;
  Add / Subtract / Intersect another); the selected part's controls; the local sliders grouped
  Light, Colour, Effects, Detail; "Reset this mask's adjustments". On the photo: gradient handles
  (pin, lines, radii, rotation knob, feather), the brush cursor, the eyedropper and subject taps,
  all following crop, rotation and zoom. Brush: size/feather/flow/density sliders, Paint/Erase,
  Auto mask, pen pressure → flow or size; the S Pen's eraser end and side button erase, and once a
  pen is used the finger pans/zooms. The overlay (tint or black and white) shows while drawing or
  dragging, or always with the toggle / `O`. Brush size by `[` `]` (pinch stays zoom).
- **Presets:** horizontal groups of large thumbnails rendered with the current photo, strength slider
  below the selected one, long-press for edit/rename/delete/favourite.
- **Before/after:** hold the ◐ button or the photo; long-press ◐ for split / side-by-side modes.

## Library and other screens

- hfps shell (fixed top bar + one card). Grid of square thumbnails with 2dp gaps inside the card,
  3 columns on phones portrait, adaptive above (min 120dp cell), date section headers (eyebrow
  style), badges in the corners (`RAW+JPG`, edited dot, stars/flag, cloud state).
- **One library home** (FIX/L, like Lightroom and Google Photos): the start screen is one grid of every photo,
  newest first with date headers: this device's folders and hand-offs and the Server photos that open, one cell per
  photo; photos not on this device keep a small cloud badge, uploads still waiting a pending one. Everything else is a
  filter, not a place.
- **Top bar:** the title "Library", then a compact sync status (an icon on phones, with the count while uploading;
  icon and text on tablets: "Synced", "Uploading 12…", "Offline", "Not paired"; tap → the Sync screen), Recent,
  Folders ("Manage folders": add, remove with the delete-from-server question, backup mode per folder, a folder → the
  home filtered to it), Settings and ⋯ (search and sort on phones; add folder, open a photo, design system, About).
  Tablets show search and sort in the bar. Recent is its own view (back returns to the home).
- **Combinable filters:** a filter bar above the grid: the Filters button (with how many choices are on), a removable
  chip (×) per active choice, "Clear all". The Filters sheet (a centred dialog on tablets) has the groups Source (each
  folder, Opened from other apps, Only on the server), Culling (Picked, Starred, Rejected), Edits, Type (RAW, JPEG)
  and, while paired, Sync (Synced, Not synced), each choice with its count. OR within a group, AND across groups. The
  last combination is remembered. Unreadable photos keep their in-place "Unavailable" placeholder (no filter).
- Empty states with a single clear action ("Add a folder", "Open from Hoard").
- Settings: grouped list inside the card, same as Hoard's settings screen.
- Export: a bottom sheet on phones (format, size, quality, metadata, destination, export preset) with
  a live estimated file size; progress in the sheet and a notification for batches.

## Accessibility and polish

- Every icon button has a content description and a tooltip on long-press; touch targets ≥ 48dp.
- Respect font scaling up to 200 % (panels scroll, values do not clip), TalkBack reads slider name,
  value and range and supports adjust actions.
- Edge-to-edge with correct insets (status bar, gesture nav, display cutouts), predictive back.
- Light/dark in the library follows the system unless overridden; the editor is always dark.
- Screenshots of every screen at 390×844-equivalent and on a tablet size, in both themes where they
  apply, go into the PRs; the orchestrator reviews them against this document and the skill.
