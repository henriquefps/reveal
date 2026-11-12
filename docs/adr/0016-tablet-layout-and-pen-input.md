# ADR 0016: Tablet layout, filmstrip navigation, pen and keyboard input

Status: accepted (M2/E). Date: 2026-11-11. Amends ADR 0005 (`RevealBottomSheet` on wide windows) and the editor
layout of M1/E (`EditorLayoutKind.TABLET`).

## Decision in one paragraph

From 840 dp wide (and 480 dp tall) the editor uses its own tablet layout: a full-width top bar; below it the photo with
a filmstrip under it, a side panel whose left edge drags to resize it (remembered), and the tool rail. The filmstrip
shows the grid the editor was opened from; moving to another photo **replaces the editor's back-stack entry** (a new
`EditorViewModel` and renderer per photo), it does not swap the photo inside one ViewModel. Rotation and fold/unfold
still recreate the activity; the ViewModel and renderer survive, and the chrome and zoom are saved state. The pen is
precise input: palm rejection happens once, in `MainActivity.dispatchTouchEvent`, and hover feedback uses Compose's
hover events. Keyboard shortcuts are a preview key handler on the editor's root.

## Layout

- `EditorLayoutKind.of(width, height)`: TABLET from 840 x 480 dp (Material's "expanded" width), so a Galaxy Tab in
  either orientation, a Pixel Fold-class inner screen (852 dp) and a Galaxy Z Fold's inner screen in landscape are
  tablets; a Z Fold's inner screen in portrait (~794 dp) keeps the phone layouts. The phone layouts (portrait,
  landscape) are untouched: their goldens are byte-identical.
- `TabletLayout.kt`: the top bar spans the window (back, file name, filmstrip toggle, undo, redo, ◐, ⋯, Export);
  the photo slot and the filmstrip share the left column; the panel column defaults to 400 dp and drags between
  320 dp and `min(560 dp, window − rail − 400 dp)` so the photo keeps at least 400 dp. A mode (Crop) takes the panel
  column and the rail for its controls; the filmstrip hides while a mode is open (moving would cancel it).
- The panel width and the filmstrip's visibility are user choices, not per-photo state: `EditorLayoutPreferences`
  (`:core:data`, SharedPreferences like `ExportPreferences`).
- `RevealBottomSheet` opens as a centred dialog (max 560 dp) from the same 840 dp (paste chooser, numeric input, crop
  chooser, the export sheet, which had its own 600 dp rule before; one threshold for the whole app now). The library
  viewer's info is a side panel beside the photo there, following the page.

## Filmstrip: a new editor entry per photo

The route carries the photo list: `EditorRoute(versionId, strip, tool)`, where `strip` is the grid's `PhotoQuery`
(scope, filter, sort, search) encoded by `PhotoListCodec`. The viewer passes the grid's query; a hand-off of several
photos passes "Opened from other apps". `FilmstripViewModel` observes `LibraryRepository.photos(query)` (live: stars,
flags and edited thumbnails follow) and reads thumbnails from `ThumbnailRepository` (memory cache first). The strip
is a keyed `LazyRow`, so a folder of thousands composes only the visible cells.

A tap (or the arrow keys, or a horizontal swipe on the photo at fit) navigates to `EditorRoute(next, strip, tool)`
with `popUpTo<EditorRoute> { inclusive = true }`. Why a new entry instead of swapping the photo in one ViewModel:

- The ViewModel *is* one photo's session: decode cancel token, renderer with its proxy textures and full-resolution
  tiles, Auto/optics analyses, history. Swapping would mean resetting a dozen fields in the right order; a new entry
  gets it right by construction and frees the previous photo's GPU and heap memory.
- "The current edit is saved first, like back": it is literally the same path. Committed steps are already queued on
  the `HistoryWriter` (it outlives the screen and the next editor flushes it before reading); an uncommitted mode
  (Crop) is cancelled exactly like back.
- Back from any photo still returns to the viewer/grid (the replaced entries do not pile up).

The cost is a new GL renderer per move (tens of milliseconds, overlapped with the decode, which dominates); the
embedded preview shows first as on any open. The open tool rides along in the route so the panel stays the same.

## Configuration changes

The activity keeps recreating on rotation and fold (no `configChanges`): Compose's saved state handles the window
size classes cleanly. What survives and how:

- `EditorViewModel` (working + committed recipe, history, renderer with its textures): unchanged, so no step is lost
  and the GL work is not redone; only the `SurfaceView` is recreated and reattached.
- `rememberEditorChrome`: active tool and mode, immersive, the portrait sheet's snap, the side panel's open state,
  the **sub-panel stack** (saved as keys, rebuilt through `SubPanels.byKey`) and the dragged panel width.
- `PhotoController.Saver`: the zoom (absolute scale, so 100 % stays 100 %, and the image point at the centre), applied
  once the photo, its content size and the new frame are known; a fitted view stays fitted.

## Pen input

- **Palm rejection** (`app/.../input/PalmRejection.kt`): `MainActivity.dispatchTouchEvent` runs every touch event
  through a small state machine (`PalmRejectionPolicy`, JVM-tested): while a stylus/eraser pointer is down only its
  pointer is delivered (rebuilt `MotionEvent`s); a pen landing on fingers cancels their gesture first; fingers left
  when the pen lifts stay ignored until they lift. At the activity rather than in Compose because several gesture
  detectors deliberately read unconsumed *and* consumed downs (`requireUnconsumed = false`), so consuming fingers in an
  initial pass would not be enough; and one place covers every screen.
- **Precision**: a pen grabs curve points within 12 dp (finger 24 dp) and crop handles within 20 dp (finger 32 dp), so
  it can pick one of two close points or an edge next to a corner.
- **Hover** (`canvas/StylusInput.kt`): Compose hover events (`Enter`/`Move`/`Exit`, pointer type stylus or mouse;
  fingers never hover, so phones never see any of it). The curve editor rings the point the pen would take (or where a
  tap would add one) with an "In · Out" tooltip; the crop overlay highlights the handle with the crop's size in
  pixels; Guided Upright shows the loupe under the hovering tip and the hovered guide's tilt; the targeted adjustment
  shows the band under the tip and its value (`EditorViewModel.targetedHover`).
- **Pen adjusts, finger navigates**: once a pen has touched or hovered the photo (`PhotoController.penSeen`), the
  targeted adjustment answers only the pen and a finger falls through to pan and zoom (no tool switch). Without a pen
  nothing changes.

## Keyboard

`EditorKeys` (pure, JVM-tested) maps Ctrl+Z / Ctrl+Shift+Z (or Ctrl+Y) to undo/redo, backslash to the ◐ tap (last
compare view on/off), left/right arrows and the D-pad to the previous/next filmstrip photo, Z to fit ↔ 100 %. The
editor's root takes focus when it appears and handles them in `onPreviewKeyEvent`; arrows without a neighbour fall
through to focus navigation.

## Measurements

`FilmstripMeasurementsTest` (1,200 JPEGs, flings through the strip with `input swipe`, FrameMetrics): see the PR and
CLAUDE.md for the command; numbers from the tablet emulator are in the PR body.
