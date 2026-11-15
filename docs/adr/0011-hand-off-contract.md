# ADR 0011: Hand-off contract (Hoard and other apps)

Status: accepted (M1, hand-off). Date: 2026-11-11. Amended 2026-11-11 (M2/S): culling values per item ("Culling"),
hidden sidecar items. Amended 2026-11-12 (FIX/S, 0.5.1): "One photo across sources", "Unreadable hand-offs",
"Gallery access". Builds on ADR 0006 (library data model, XMP) and Hoard's ADR 0006
(RAW+JPEG pairing), 0008 and 0009 (sidecars, pick/reject).

## Context

`docs/plan/product.md` ("Hand-off with Hoard", "Inputs"): Hoard, the culling app, must be able to send one or many
photos to Reveal ("Edit in Reveal"), RAW+JPEG pairs kept together; any other app can open or share a photo to Reveal
with the standard intents. Reveal never copies originals: it reads the content URIs it is given, and writes only XMP
sidecars.

## Decision

### Action and extras

| Name | Kind | Meaning |
| --- | --- | --- |
| `dev.hfps.reveal.action.DEVELOP` | action | Explicit hand-off. Reveal's `MainActivity` handles it (category `DEFAULT`, so `startActivity` with an implicit or package-targeted intent resolves). |
| `ClipData` | payload | The photos: one `ClipData.Item` per content URI, RAW and rendering both (plus sidecars, below). Order is kept. |
| `dev.hfps.reveal.extra.TITLE` | `String`, optional | Names the batch; shown as the group header in "Opened from other apps" (else the arrival date and time). Trimmed, at most 120 chars. |
| `dev.hfps.reveal.extra.INITIAL_URI` | `Uri`, optional | One of the URIs sent: open it in the editor at once, with the batch in the library behind it. Ignored if it is not among the URIs. |
| `dev.hfps.reveal.extra.CULLING` | `Bundle`, optional | The sender's rating / flag / colour label per item, see "Culling". |
| `dev.hfps.reveal.extra.CULLING_VERSION` | `Int`, optional | Layout version of `CULLING`; absent = 1. Current: **2** (W3/H, adds per-field dates and authors; see "Culling" and "Amendment W3/H"). |
| `dev.hfps.reveal.extra.SERVER_REF_VERSION`, `SERVER_ID`, `SERVER_ITEMS`, `INITIAL_ITEM` | optional | Server references: photos the sender's paired hoard-server holds, see "Amendment W3/H: server references". |

Intent flags: `FLAG_GRANT_READ_URI_PERMISSION` (required), `FLAG_GRANT_WRITE_URI_PERMISSION` (only for URIs the sender
lets Reveal write: sidecars), `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` (optional, see "Grants"). The ClipData's grants
are what carry the permissions: Android applies the flags to every item of the ClipData, so the sender need not call
`grantUriPermission` itself. Type: set `image/*` (or leave it unset: Reveal also has a type-less filter for the action).

Also accepted, with the same parsing (below): `ACTION_VIEW`, `ACTION_EDIT` (the intent data), `ACTION_SEND`
(`EXTRA_STREAM`, else the ClipData) and `ACTION_SEND_MULTIPLE` (`EXTRA_STREAM` list, else the ClipData), for `image/*`.
`DEVELOP` and `SEND_MULTIPLE` need a result of nothing: Reveal does not call `setResult` with data; edits live in its
own database and, when it has a write grant, in the sidecar.

### MIME types

The manifest filters list `image/*` (which matches every `image/...` type), plus the exact RAW types so apps that
resolve by exact type find Reveal: `image/x-sony-arw`, `image/x-sony-sr2`, `image/x-sony-srf`, `image/x-adobe-dng`,
`image/dng`, `image/x-raw`, `image/x-canon-cr2`, `image/x-canon-cr3`, `image/x-nikon-nef`, `image/x-nikon-nrw`,
`image/x-fuji-raf`, `image/x-olympus-orf`, `image/x-panasonic-rw2`, `image/x-pentax-pef`, `image/x-samsung-srw`. Only
`DEVELOP` also accepts `application/octet-stream` (providers that do not know RAW report it), and `DEVELOP` has a second
filter without a type. `VIEW`/`EDIT` match `content:` URIs. **What a file is** is decided by its display name
(`OpenableColumns.DISPLAY_NAME`, the extension, as in `ImageFormats`) and its content, never by the intent's type, so a
sender that cannot report the right MIME still works.

### Parsing

URIs are collected, in order, from: the intent data, every `ClipData` item, `EXTRA_STREAM` (one `Uri` or a list).
Duplicates are dropped and only `content:` and `file:` URIs count. This one rule covers every action (the framework
itself copies `EXTRA_STREAM` into the ClipData of `SEND`, hence the de-duplication). Items named `*.xmp` are sidecars; items
whose name is not an image are ignored. A single unrecognised item (an `ACTION_VIEW` of `x.dat`) still opens the
editor by URI, which says what is wrong (the M0 behaviour).

### Pairing

Same rule as `ShotPairing` (Hoard ADR 0006): files that share a **lower-case stem** inside the same parent are one
photo (`DSC07668.ARW` + `dsc07668.jpg`); several renderings keep the JPEG over HEIF over PNG/WebP. "Same parent" for
content URIs: the directory of a SAF document id (`primary:DCIM/Camera/X.ARW` -> `primary:DCIM/Camera`), else the URI without
its last segment (FileProvider paths, one MediaStore collection). So a sender must give both URIs of a pair **in the same
intent**; the same stem in two different folders stays two photos. The photo opens from its RAW; the rendering is the
instant preview.

### Culling (rating, pick/reject, colour label)

Hoard keeps culling decisions in its own database for folders it cannot write (read-only SMB shares), so they cannot
travel in a sidecar. It sends them with the hand-off, **per item**, in one `Bundle` extra:

```
dev.hfps.reveal.extra.CULLING_VERSION : Int    = 2                   (optional; absent = 1)
dev.hfps.reveal.extra.CULLING         : Bundle {
    "<item uri, exactly uri.toString() as put in the ClipData>" : Bundle {
        "rating" : Int     0..5                                  (optional)
        "flag"   : String  "pick" | "reject" | "none"            (optional, case-insensitive)
        "label"  : String  colour label, xmp:Label ("Red", ...)  (optional; "" or blank clears it; max 64 chars)
        // version 2, per field, optional:
        "ratingModifiedAt" / "flagModifiedAt" / "labelModifiedAt" : Long    Unix ms of the change (R0.8)
        "ratingDevice" / "flagDevice" / "labelDevice"            : String  server deviceId of the author; only when
                                                                           the sender is paired
    }
    ...
}
```

- **Keyed by URI string, not by position**: Reveal de-duplicates and merges the data, `ClipData` and `EXTRA_STREAM`
  (see "Parsing"), so an item's index is not stable across actions; a URI is. The same layout serves one item and
  many (one key per item), with any action (`DEVELOP` normally; `SEND`/`SEND_MULTIPLE` too).
- An entry may sit on any file of a shot (the RAW, the JPEG or both); per field, the main file's (the RAW's) value is
  used, else the other file's. Keys that are not among the URIs sent, values of the wrong type, a rating outside
  0..5 and an unknown flag are ignored **field by field**; a field left out means "no decision sent: keep".
- **Versioning:** later versions only **add** keys; Reveal reads any `CULLING_VERSION >= 1` with the keys it knows and
  ignores the rest, so an old Reveal accepts a newer Hoard and a newer Reveal an old Hoard (no `CULLING` = the
  behaviour before this extra). An incompatible change would use a new extra name. A version below 1 is ignored.

**Precedence.** For each shot that has values: if a sidecar of the shot was sent too and its mtime is **later than
the photo's previous hand-off** (`photos.handoff_at` before this one; someone rated it after the sender last said what
it holds), the **sidecar wins**. Otherwise the **values win**; the fields they leave out keep the sidecar's value (or,
without one, the photo's). On a first hand-off there is no previous one, so the values win. Without values, the
sidecar applies as before, and with neither a re-sent photo keeps what it has (a rating set in Reveal stays). Values
also win over ratings embedded in the file (JPEG XMP), which only fill an unrated photo.

**Nothing is written back** on arrival: the values set the photo's rating/flag/label in Reveal's database only; the
sender's files and sidecar stay untouched (the sender holds the decision already; a read-only source cannot take it).
A later change made in Reveal goes to the sidecar item under the rules of "What Reveal writes back". A hand-off that
opens a photo of an **added folder** applies the values to that photo under the same precedence (its sidecar wins only
when it was written after the photo's previous hand-off), and then writes them to the folder's sidecar like any rating
change; see "One photo across sources".

### The "Opened from other apps" source

A hand-off becomes rows of the library (ADR 0006 tables) under one special source (`sources.kind = HANDOFF`, shown in
the sources list as "Opened from other apps", never as a folder). The list "Opened from other apps" shows every photo
with a hand-off time (`photos.handoff_at`): the hand-off source's, and folder photos a hand-off opened or merged into
(below), so a batch stays whole. Its `DocumentTree` (`HandoffTree`) uses the content URI
as the document id, so thumbnails, the viewer, the editor, ratings and edit history work as for any folder. The grid
lists **most recent hand-off first** (`photos.handoff_at`, with a header per batch: the title or the arrival time),
files in a batch by name.

**Which photo a hand-off is** (ADR 0006 "Identity"): files are recognised by their **content fingerprint**, never by
name. Sending the same file again (same content, through any URI or app, under any name) moves its photo to the top,
keeps its edits and history, and replaces its file set with the URIs just sent. A different file with a name already
seen (`DSC00001.JPG` from a second card) is a new photo with an empty history, and so is a file replaced under the
same URI. A photo of an added folder handed over by another app opens as that folder photo, with its edits. Pairing
by stem applies only to the files of one request; a pair completed later (the RAW first, RAW+JPEG afterwards) joins
the earlier photo because one of its files has the same content. Edits and ratings of a hand-off photo are never
lost to another file; a photo whose only file was replaced under its URI is removed, since its picture is gone.

What happens when it arrives:

| Intent | Result |
| --- | --- |
| one photo (a RAW+JPEG pair counts as one) | the editor opens on it; **back returns to the calling app** (`finish()`) |
| several, no `INITIAL_URI` | the library opens on "Opened from other apps" showing the batch |
| several with `INITIAL_URI` | the editor opens on that photo; back shows the batch in the library |
| nothing readable | a toast ("Reveal could not open those photos...") and the library |

Retention: the newest **400** hand-off photos are kept; older ones **without edits** are forgotten (rows and persisted
grants; a photo with an edit stays). "Remove from library" hides one as for a folder photo.

### Grants

Reveal calls `takePersistableUriPermission` (read + write, falling back to read) on every `content:` URI when the sender
set `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` and the provider allows it; a refusal is silent. Without a persisted grant
the access lasts until Reveal's process ends or the device restarts; after that the photo stays in the library with its
edits but its thumbnail and image cannot load until the sender hands it over again. A sender that wants hand-offs to
survive restarts should offer a persistable grant (SAF/DocumentsProvider URIs and `MediaStore` do; plain `FileProvider`
does not). Releasing happens with the retention rule above.

### What Reveal writes back

- **Ratings, pick/reject and labels** go to the XMP sidecar exactly as in ADR 0006 (`xmp:Rating`, `digiKam:PickLabel`,
  `xmp:Label`; merged, never overwriting a malformed file), **only when the hand-off included the sidecar as an item**
  (`<stem>.xmp`, or `<file name>.xmp`, or their hidden forms `.<stem>.xmp` / `.<file name>.xmp`, with a **write
  grant**; a normal name wins when both are sent): a URI of a document Reveal cannot create beside the photo
  is the only thing a plain content URI offers, so Reveal never creates sidecars for hand-offs. A sender that wants
  write-back creates (or has) an empty or existing `<stem>.xmp`, includes it in the ClipData, and adds
  `FLAG_GRANT_WRITE_URI_PERMISSION`. The sidecar's rating, label and flag are read on arrival (Hoard's picks arrive as picks).
  Without a sidecar item, or when the provider refuses, the change stays in Reveal's database and the UI says "Saved in
  Reveal only".
- **The recipe** (M5) goes to the same sidecar, in the `reveal:` namespace reserved in ADR 0006, under the same rule.
- Originals are never modified.

### Detecting Reveal

Sender manifest (package visibility, Android 11+):

```xml
<queries>
    <intent>
        <action android:name="dev.hfps.reveal.action.DEVELOP" />
        <data android:mimeType="image/*" />
    </intent>
</queries>
```

```kotlin
val develop = Intent("dev.hfps.reveal.action.DEVELOP").setType("image/*")
val installed = packageManager.queryIntentActivities(develop, 0).isNotEmpty()
```

Package ids: `dev.hfps.reveal` (release), `dev.hfps.reveal.debug` (debug builds).

### Example: "Edit in Reveal" in Hoard

```kotlin
/** [shots]: for each shot the RAW and/or JPEG content URIs and, if it has one, its writable `.xmp`. */
fun editInReveal(
    context: Context,
    files: List<Uri>,
    title: String?,
    openFirst: Uri? = null,
    culling: Map<Uri, Triple<Int?, String?, String?>> = emptyMap(),  // uri -> (rating, "pick"/"reject"/"none", label)
): Boolean {
    val values = Bundle().apply {
        for ((uri, v) in culling) putBundle(uri.toString(), Bundle().apply {
            v.first?.let { putInt("rating", it) }
            v.second?.let { putString("flag", it) }
            v.third?.let { putString("label", it) }
        })
    }
    val intent = Intent("dev.hfps.reveal.action.DEVELOP")
        .setType("image/*")
        .putExtra("dev.hfps.reveal.extra.TITLE", title)             // optional
        .putExtra("dev.hfps.reveal.extra.CULLING_VERSION", 1)       // optional
        .apply { if (!values.isEmpty) putExtra("dev.hfps.reveal.extra.CULLING", values) }
        .apply { if (openFirst != null) putExtra("dev.hfps.reveal.extra.INITIAL_URI", openFirst) }
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or       // only matters for the .xmp items
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,   // keep the grants across restarts
        )
    intent.clipData = ClipData.newRawUri("Reveal", files.first()).also { clip ->
        files.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    }
    if (intent.resolveActivity(context.packageManager) == null) return false   // Reveal is not installed
    context.startActivity(intent)
    return true
}
```

Use `setPackage("dev.hfps.reveal")` to target Reveal explicitly. Errors: not installed (`resolveActivity` is null or
`ActivityNotFoundException`), an empty or all-non-image ClipData (Reveal shows a message), a URI the sender did not
grant (`SecurityException` inside Reveal: that photo shows without an image; the rest still open). Reveal sends no
result: Hoard re-reads sidecars (`xmp:Rating`) when it comes back to the foreground, as for any other XMP editor.

### One photo across sources (amended 2026-11-12)

ADR 0006 "Identity": a file is its content fingerprint. The same file handed over by an app (Hoard's picks, a gallery
share) and present in an added folder (the SD card, the camera folder) is **one photo, the folder's**: the folder is
where the file lives and stays readable; the hand-off photo goes away.

- **Folder first, then a hand-off**: the hand-off opens the folder photo (as before), which takes the hand-off time and
  title (it shows in the batch) and the sender's culling values (above).
- **Hand-off first, then the folder**: after every fingerprint pass of a folder (`LibraryIndexer.fillFingerprints`,
  then `LibraryRepository.mergeHandoffs` → `HandoffMerge.mergeInto`), each hand-off photo with a file of that folder
  (same fingerprint; the folder available, the photo not removed from the library) merges into the folder photo that
  holds its main file. The pass compares all of the folder's prints, so rows printed before 0.5.1 merge on the first
  walk after the update.
  - **Culling**, field by field (`HandoffMerge.resolve`): the folder photo's value when the hand-off has none; the
    hand-off's when the folder photo has none; otherwise the hand-off's only when it was handed over after the
    folder's sidecar was written (or the folder photo has no sidecar). Then the folder photo's sidecar is written
    (`SidecarSync`: merged, a malformed one never overwritten).
  - **Edits**: the hand-off photo's touched versions (an edit, a history, a conflict copy, or known to the server) move
    to the folder photo with their ids, recipes and histories. A folder photo nobody touched gives its empty version
    way; otherwise the moved versions follow its own (both edits stay, as versions; nothing is lost).
  - **Sync identity** (ADR 0018 "Identity"): a hand-off the server knows gives its uuid to a folder photo the server
    does not know (pushed again with the folder's files); when the server knows both, the hand-off's uuid is pushed as
    a photo **tombstone** and kept here as an alias of the folder photo. A device that pulls the tombstone keeps a
    photo another server id still names (an alias) under that id; otherwise only a Server-source copy goes. The moved
    versions are pushed with their new photo (`versions.photo_id` is tracked by the triggers, version 3).
  - Its local previews ("Unreadable hand-offs") are deleted: the folder has the file.
- Two folders with the same file stay two photos (two copies, ADR 0006), and a Server-source photo is never merged into
  a folder; a hand-off joins the folder photo of the first folder whose pass finds it.
- Retention: folder photos beyond the newest 400 hand-offs only leave "Opened from other apps".

### Unreadable hand-offs (amended 2026-11-12)

A sender may grant access only while Reveal's activity lives (the Samsung Gallery's `SEND` of
`content://media/external/images/media/<id>`; after it the provider answers `SecurityException ... has no access`, some
providers an `IllegalArgumentException`, which `ImageInput.fromUri` reports as a missing document). Edits survive in the
database, but the picture would be gone. So, **always**, while the grant is alive:

- `HandoffPreviewer` (after `receive`, one shot at a time, the photo opened first) keeps in app storage
  (`noBackupFilesDir/handoff-previews`, `LocalPreviews`, keyed by the main file's fingerprint, so a re-share under
  another URI finds them): the **display image** (1600 px JPEG) for every hand-off, and a **smart preview**
  (`SmartPreviewCodec`, the 2560 px one sync makes, ADR 0018) when the grant is not persistable. Smart previews are
  capped at 1.5 GiB (least recently used first); a forgotten or merged hand-off loses both. Memory: a smart preview
  decodes a 2560 px proxy (about 100 MB of heap), like the editor's open, and the two together ran the phone's 256 MB
  heap out (the editor's full-resolution zoom failed). So display images come first, smart previews wait while an
  editor is open, and the photo the editor opens gets its smart preview from the editor's own input once its full
  resolution is loaded (`LibraryRepository.keepSmartPreview`; no second decode).
- A refused read marks the file (`photo_files.unreadable_at`, DB version 8; a successful read or a re-share clears it).
  `LibraryRepository.checkHandoffs` checks every hand-off document at start (and after a gallery permission change);
  thumbnails, the viewer, the editor, the export and sync mark it when they meet it.
- The grid shows such a photo with a "preview only" badge (`CloudState.LOCAL_PREVIEW`, the smart-preview icon). Its
  thumbnail and the viewer use the display image (an edited one renders the smart preview), the editor opens the smart
  preview (`LibraryRepository.editTarget`; no full-resolution zoom behind it, as for the Server source).
- **Export** (`OriginalReach.PREVIEW`): the original is tried again first; still unreadable, the item is exported from
  the smart preview at its size, the sheet says so before starting ("N photos will be exported at preview size …
  share them to Reveal again for full size") and the notification after.
- **Sync** (ADR 0018): an unreadable original is not backed up (parked, `backupCandidates` skips it until it reads
  again) and its smart preview is uploaded instead (the local one as it is). Re-sharing the photo refreshes its URI
  (new file rows, readable) and keeps the previews.

### Fallbacks (amended 2026-11-12, 0.5.2)

0.5.1 opened an unreadable hand-off only from a local smart preview (made for temporary grants, or by the editor) and
never looked at the sync server: hand-offs from Hoard's SMB stream provider (`Permission Denial: opening provider ...`
once the grant ended) failed in the editor whether the grid showed them as backed up (CloudCheck) or as preview only
(Image, with a display image but no smart preview).
Now one resolver, `HandoffFallbacks` (`:core:data/handoff`), says what an unreadable hand-off is read from, in order,
and the editor, the viewer, the grid, the info and the export all ask it:

1. **The server's original** (`photo_cloud.original_on_server`: a backup or a NAS file of the same fingerprint), read by
   range through `ServerFilesProvider` (`ServerTree.ORIGINAL`), like a Server photo: full quality, full-resolution
   zoom, no prompt. Export: `OriginalReach.SERVER` (downloaded first), and `ExportTarget.previewUri` for when the server
   cannot be reached. The grid shows the photo as `CloudState.CLOUD` (the original is only on the server now).
2. **A smart preview**: the local one (`LocalPreviews`), else the server's (`photo_cloud.preview_sha`,
   `ServerTree.SMART`). Preview only: editing works, the editor shows a one-line "preview only" note (also for Server
   photos whose original is on another device, `PreviewOnly.ELSEWHERE`), export is `OriginalReach.PREVIEW` (at
   2560 px, the sheet and the notification say so). The editor opens it also when (1) fails while opening (the
   server is offline: `EditTarget.fallback`); the export worker likewise when the download fails.
3. **Nothing**: `EditTarget.unreadable` = the document; the editor's error says that the sending app only lent the
   photo, names it when its provider belongs to an app with a launcher entry (`HandoffApps.sender`: the URI's
   authority -> `resolveContentProvider`, visible through the manifest's `<queries>` for launcher apps) and offers to
   open it ("Share it to Reveal again from Hoard"); for a MediaStore URI without full gallery access it offers gallery
   access (then opens the photo again).

Thumbnails: the local display image, else the server's `ServerTree.DISPLAY` (the original's embedded preview by range,
or the server's smart preview JPEG); edited thumbnails render the smart preview first (local, server), the original
last. The viewer's info has an "Original" line (`LibraryRepository.originalPlace`: on this device, backed up, only on
the server, on another device, or one of the three cases above, each with what it means), and the grid's badge has a
content description (`cloudDescription`). The export sheet's preview-size count leaves out photos the server has.

A library with no folder but with hand-offs starts on "Opened from other apps" (`LibraryViewModel.startSelection`; the
last selection still wins) and its sources list is not the empty state (`LibraryUiState.fromAppsPhotos`).

"Unreadable" is one helper, `isAccessError` (`:core:data/storage/AccessErrors.kt`), used by the opener, thumbnails,
the viewer, sync and export: `SecurityException`, `FileNotFoundException`, an `IllegalArgumentException` /
`IllegalStateException` / `UnsupportedOperationException` thrown by a provider (rethrown across Binder by
`DatabaseUtils.readExceptionFromParcel`, or raised in `ContentResolver`), and any message saying `Permission Denial`
or `no access to`, also when wrapped.

### Unavailable photos (amended 2026-11-12, 0.5.2)

A photo Reveal cannot show at all must never be an empty square. **Unavailable** is defined once: a file of the photo
is unreadable (`photo_files.unreadable_at`) AND the sync server has no original (`photo_cloud.original_on_server`)
AND no smart preview (`photo_cloud.preview_sha`) AND there is no local preview (`LocalPreviews`). The SQL half is
`PhotoQuerySql.UNAVAILABLE_SQL` (the `unavailable` column of every row and `PhotoFilter.UNAVAILABLE`); the local preview
is a file, so `LibraryRepository` clears `PhotoRow.unavailable` for photos that have one (and drops them from the
filter) with `PhotoAvailability`, the Kotlin mirror (`UnavailablePhotosTest` keeps the two in step).

- **Grid cell, filmstrip, viewer**: `UnavailablePlaceholder` (`:core:designsystem`): muted surface, Phosphor
  `ImageBroken`, the file name on one line, a small "Unavailable" eyebrow; rating, flag, edited dot and selection stay.
  The filmstrip's thumbnail is icon-only. The viewer centres the placeholder with an explanation and a "What can I do?"
  button. A photo with any preview keeps showing it ("preview only").
- **Tap** (grid) or the viewer's button opens `UnavailableSheet`: why (`UnavailableReason`: the sending app's grant
  ended, the file is gone, the folder is not mounted), "Open <app>" to share it again (`HandoffApps`, 0.5.2), "Allow
  access to photos" for a MediaStore URI, "Try again", and "Remove from Reveal". Removing forgets a hand-off photo
  (rows, edits and local previews: the sender can share it again); a folder or Server photo is only hidden (deleting its
  rows would sync to the other devices). It asks first when any photo has edits: they go too.
- **Filter "Unavailable"** (pt-BR "Indisponíveis"), right after "All", only while the scope has such photos
  (`LibraryRepository.unavailableCount`); it works with selection, so "Select all" then "Try again" or "Remove from
  Reveal" cleans up in one go. When the last one is gone the filter resets to "All".
- **Re-check**: opening a scope calls `LibraryRepository.recheckAccess()` (every file marked unreadable, whatever its
  source, batch, background, at most once per 30 s); a file that reads again clears its mark. "Try again" does it now
  for the chosen photos and reports how many are readable.

### Gallery access (amended 2026-11-12)

Optional, offered in context, never as a system dialog out of the blue: when a hand-off whose document is a MediaStore
URI (`content://media/...`) cannot be read, Reveal has no gallery access and the offer was never answered, the library
shows a sheet (`GalleryAccessSheet`): allow access to photos ("selected photos only" is fine), or "Not now"
(remembered). Settings → Gallery access shows the state (allowed / selected photos only / off) and asks again, or opens
the app's system settings when the system no longer shows the dialog. Permissions: Android 14+ `READ_MEDIA_IMAGES`
with `READ_MEDIA_VISUAL_USER_SELECTED`, Android 13 `READ_MEDIA_IMAGES`, below `READ_EXTERNAL_STORAGE`
(`maxSdkVersion` 32). With access, MediaStore URIs read again: `checkHandoffs` runs after the answer and when the app
comes back, clearing the marks (full thumbnail, full-size export, original backup). Listed in `docs/privacy.md`.

## Alternatives considered

- **Copy the files into Reveal's storage:** doubles RAW storage and breaks sidecar write-back; rejected (originals are
  never copied).
- **A result intent** (`startActivityForResult`) with the new ratings: sidecars are the shared, portable truth between
  the apps (Hoard ADR 0009), and a result is lost when either process dies.
- **Creating `<stem>.xmp` for any content URI:** not possible without a folder grant; a sender that wants write-back
  passes the sidecar item (above).
- **Copying every hand-off's original into app storage** (to survive temporary grants): doubles RAW storage, as above.
  A 2560 px smart preview is enough to edit, export at preview size and sync, and the gallery permission restores
  the original for MediaStore URIs.
- **Keeping the hand-off and folder photos apart, linked**: two rows for one file is what made picks invisible in the
  folder's "Picked" filter; one photo is the identity rule.
- **Pairing across intents:** a pair arrives in one intent from every sender considered; stem collisions across batches
  (`IMG_0001` from two cameras) would join unrelated photos.

## Consequences

- Hoard's side is one function (the example) and a `<queries>` element; no Reveal library is needed.
- Culling values cost nothing to a sender that has none (no extra) and need no schema change: the precedence uses
  `photos.handoff_at` and the sidecar's mtime.
- DB version 3 (auto-migration: `sources.kind`, `photos.handoff_at`, `photos.handoff_title`); version 4 adds
  `photo_files.fingerprint` (ADR 0006 "Identity").
- Receiving reads up to 2 MiB per new file to fingerprint it (4 at a time); a URI already known with the same size and
  mtime reuses its stored fingerprint.
- Hand-offs are library photos like any other: they appear in "All photos" and "Recent", can be rated, edited and
  removed, and keep their edit history across hand-offs of the same photo.
- The instrumented test cannot send from another uid; cross-app grants are the platform's, verified by Hoard's side.

## Amendment R0.8 (docs/adr/0027)

Culling values a sender passes that the paired server already holds for the shot are not pushed back to it; each
`EXTRA_CULLING` item may carry `ratingModifiedAt`, `flagModifiedAt`, `labelModifiedAt` (Unix ms) dating the change.

## Amendment W3/H: server references (2026-11-15)

### Context

URI hand-offs of photos the paired hoard-server already holds made Reveal read through Hoard's provider (SMB via
appfuse) for thumbnails, access checks and edits; a burst of such opens at Reveal's start hung vold and restarted
system_server (2026-11-15). Rule: **content URIs only for photos the server does not have**; a photo the server holds
is handed over as a **server reference** and Reveal reads it through its own pairing.

### Extras (same action `dev.hfps.reveal.action.DEVELOP`)

| Name | Kind | Meaning |
| --- | --- | --- |
| `dev.hfps.reveal.extra.SERVER_REF_VERSION` | `Int` | Layout version of the extras below. Current: **1**. Absent = the intent has no server references. |
| `dev.hfps.reveal.extra.SERVER_ID` | `String` | The `serverId` (UUID, Hoard ADR 0013 pairing / `GET /v1/server`) of the server the sender is paired with. Required with `SERVER_ITEMS`. |
| `dev.hfps.reveal.extra.SERVER_ITEMS` | `ArrayList<Bundle>` | One bundle per shot (an item, Hoard ADR 0014), order kept. At most **500** per intent (~300-450 B per bundle; the Binder limit is ~1 MB); a bigger batch is several intents with the same `TITLE`. |
| `dev.hfps.reveal.extra.INITIAL_ITEM` | `String`, optional | An `itemId` among `SERVER_ITEMS` to open in the editor at once. `INITIAL_URI` keeps its meaning for URI items; if both are set, `INITIAL_ITEM` wins. |
| `dev.hfps.reveal.extra.TITLE` | unchanged | Names the batch (URI items and server items of one intent form one batch). |

Item bundle:

```
"itemId"        : String   server item id (Hoard ADR 0014)                       required
"fingerprint"   : String   v1: fingerprint of the main file (the RAW)             optional (cross-check / fallback)
"fingerprints"  : String[] v1: fingerprints of every member file (RAW, JPEG, ...) optional
"name"          : String   main file name, for display before the record arrives  optional (<= 255)
```

**No culling in server items**: both apps read it from the server (ADR 0027 / Hoard ADR 0016); `CULLING` is only for
URI items. No `ClipData` item, URI or grant is sent for a server item. One intent may mix URI items (photos the server
does not have, unchanged contract) and server items.

### Culling for URI items: `CULLING_VERSION` 2

Per item (same URI-keyed `Bundle` as version 1), each field may carry its own date and author: `ratingModifiedAt`,
`flagModifiedAt`, `labelModifiedAt` (Long, Unix ms) and `ratingDevice`, `flagDevice`, `labelDevice` (String, the
server `deviceId` of the device that made the change; sent only when the sender is paired, else absent). Reveal applies
each field by **per-field last-writer-wins** as in Hoard ADR 0016 / ADR 0027: the newer `modifiedAt` wins; at equal
times the greater `deviceId` by byte-wise (ordinal) string comparison wins (Go string order, never a locale collator;
Hoard ADR 0016 step 5 / ADR 0013). The same tiebreak is used wherever Reveal compares culling changes. The comparison
is against what Reveal holds for that field (its own change time, or the sidecar's mtime for a sidecar-read value). A
field without a date falls back to version 1's precedence ("values win unless the sidecar is newer than the previous
hand-off"). Version 2 only adds keys, so an old Reveal reads it as version 1.

### Sender rule (Hoard)

A shot the sender's paired server holds (and can serve the original of) goes as a server item whenever Reveal >= 0.9.0
(`versionCode >= 90000`) is installed: Gallery items, and SMB files Hoard maps to server items. Hoard may read
`GET /v1/device` `physicalDevice.devices[]` only to decide how it presents things; if that is false or unknown (not
claimed, another server, server unreachable) it still sends server items and Reveal shows its pair or "paired with
another server" sheet. **Hoard never falls back to URIs for photos the server holds.** URIs only for photos the server
does not hold, or for Reveal < 0.9.0. Hoard sends `fingerprint` when it has it and `fingerprints` for pairs, and
flushes its culling pushes for those items before sending (best effort); Reveal pulls culling on receipt.

**Reveal never calls back into Hoard for server items** (no provider, no grants, no URI).

### Reveal behaviour

- **Same server** (Reveal paired, its `serverId` == `SERVER_ID`): each item resolves to a photo by fingerprint (any
  member) across every source (ADR 0006 "Identity"); else Reveal creates it as a Server photo (`sources.kind =
  SERVER`) from `GET /v1/items/{itemId}` (a tombstone with `replacedBy` is followed once). The photo gets `handoff_at`
  and joins the batch in "Opened from other apps"; `INITIAL_ITEM` opens in the editor, reading the original by range
  (`ServerFiles`), the smart preview first if the server has one. Nothing is read through the sender.
- **Culling of server items** comes from the server only (Reveal pulls the item's culling record before the batch
  shows). If `itemId` and `fingerprint` disagree (the item's members do not include it), Reveal trusts `itemId` and
  logs the mismatch; if `itemId` is unknown and a fingerprint is given, Reveal falls back to a fingerprint lookup
  (`POST /v1/items/lookup`).
- **Not paired**: a sheet "These photos are on your Hoard server. Pair Reveal with it to open them" with Pair (the
  scanner or a `revealsync://` link) and Cancel; after pairing with that server the batch is resolved as above.
- **Paired with another server**: the same sheet saying Reveal is paired with another server (its name); no automatic
  re-pairing.
- **Unknown or deleted item** (404, tombstone without replacement): skipped and listed in the outcome ("N photos could
  not be found on the server"); the rest of the batch opens.
- **Server unreachable**: the photos are created or located and open from what Reveal has (local cache, smart
  preview); otherwise the usual "unavailable" placeholder and a retry once the server answers.
- **Sidecars**: none are sent or written for server items (culling rides the server, edits sync as records and edited
  previews, Hoard ADR 0020).

### Batches over 500

A bigger batch is several `DEVELOP` intents with the same `TITLE`, sent by `Activity.startActivities(intents)`; the
first carries `INITIAL_ITEM` (or `INITIAL_URI`) and any URI items. Each intent carries **`BATCH_INDEX`**
(`dev.hfps.reveal.extra.BATCH_INDEX`, `Int`, 0 for the first, the one lowest in the stack) and **`BATCH_COUNT`** (`dev.hfps.reveal.extra.BATCH_COUNT`, `Int`, the number of intents, on every intent of the batch). Both are set only when the sender uses `startActivities` with more than one intent (**agreed** with Hoard). `MainActivity` is
`standard`, so every intent gets its own record (the last intent's is created first, the others as each one above
finishes). Reveal ingests them as **one** batch: a process-wide `HandoffBatches` keeps one record in `meta` (the
sender package + `TITLE`, a shared `handoff_at`, the last touch, the notices so far) and every intent whose key matches
within 10 s of the last one joins it, so the photos share one stamp and one header in "Opened from other apps". An
intent with `BATCH_INDEX` > 0 ingests, reports its notices to the batch and `finish()`es with no UI and no animation,
so the cascade unwinds to the first intent, which shows the batch: the editor when it carries an `INITIAL_*` (library
behind it, as for any multi-photo hand-off), else "Opened from other apps".

`BATCH_COUNT` tells Reveal when the batch is complete (that many intents joined: `Join.complete`); the 10 s window stays the fallback for an incomplete batch (a member that arrives after the notices were shown reports on its own). Whether an activity is silent is decided **only** by `BATCH_INDEX`, never by the task or by the absence of an
`INITIAL_*`: Hoard starts Reveal with `startActivity(intent)` and no `FLAG_ACTIVITY_NEW_TASK`, so Reveal's activity
lands in the sender's task and is never its root, and an ordinary multi-select hand-off (one intent, `TITLE`, several
photos, no `INITIAL_URI`) is indistinguishable from a trailing chunk by its content. A sender that does not send
`BATCH_INDEX` gets one visible activity per intent (nothing is lost; the cascade is just not collapsed). A single
intent is never a batch member. A server file with no `name` takes the sender's `name` (main file), else a name made
of its fingerprint and format. A batch without `TITLE` and without `BATCH_INDEX` is not merged.

The notices of **every** member (not-found counts, server issues: not paired, another server, unreachable), including
the silent ones, are collected in the batch record and shown **once** by the intent that shows the batch: the not-found
counts are summed ("N photos could not be found") and one issue sheet is shown (not paired before another server
before unreachable, its count summed). A member that finishes after that notice was shown reports on its own. Batches
kept for a pairing (`handoff.pendingServerRefs`) and photos waiting for the server (`handoff.retryServerRefs`) are lists
of groups, one per server and `TITLE`: the members of a cascade add their items to the group instead of replacing it,
a hand-off that resolves fully removes only its own items from the waiting list, and a retry keeps the batch's
`handoff_at`. Resuming a pending batch takes it atomically (two live activities resolve it once).

Resolution notes (review of W3/H): the sender's fingerprint alone never picks a local photo (a stale one would open the
wrong photo): the item's members (known from sync, else read from the server) decide, and only when the server cannot
answer does the sender's fingerprint stand in. A server answer that is not the server's JSON (a captive portal, a
proxy) counts as "unreachable", never as a crash. The culling pull a hand-off asks for while a sync run holds the lock
follows that run.

### Existing libraries (W3/C)

Hand-off files received earlier as URIs whose fingerprint the server holds are read from the server copy (and moved to
the Server source, keeping uuid, edits and culling); Reveal stops opening their Hoard URIs. URI hand-offs of photos the
server does not have keep working, with Hoard's `busy:` retry (Hoard ADR 0010) and throttled checks.

### Review

Item limit 500; the tiebreak is the ordinal `deviceId`; Hoard learns Reveal's pairing from the server's device grouping
(Hoard ADR 0017), with no provider (different signing keys); no fingerprint-only references for now; `CULLING_VERSION` 2.

## Amendment W3/C (0.9.0, docs/adr/0018 "W3/C")

- **Access checks are a paced background pass**, never a burst: `checkHandoffs`/`recheckAccess` no longer run at start
  or per grid query. `LibraryRepository.scheduleAccessCheck` runs one pass after the first frame: files the server
  holds are skipped (shown and edited from the server's copy), files checked in the last 6 h are skipped
  (`photo_files.checked_at`, DB v14), documents with a read grant this process holds (persisted, or the sender's
  temporary one, `checkUriPermission`) count as readable without an open, and every open waits for one of
  `FileOpens`' 4 app-wide slots, ≥ 100 ms apart. A gallery permission change reopens the unreadable ones at once.
  "Try again" and the viewer check the photos the user looks at.
- **Busy senders** (Hoard ADR 0010, Hoard 2.0.1): a `FileNotFoundException` whose message starts exactly with `busy:`
  (`PROVIDER_BUSY_PREFIX`) is retried, 250 ms doubling to 4 s, for about 30 s, then a transient failure: never
  `unreadable_at`. Any other `FileNotFoundException` keeps its meaning.
- **A hand-off the server holds becomes a Server photo** (`SyncStore.adoptHandoffs`): it keeps its identity, edits,
  culling and `handoff_at` ("Opened from other apps" lists Server photos with a hand-off time), and a later share of
  the same file opens it (a Server photo is reusable like a folder photo). Its sidecar link goes (no sidecar for server
  items, "Amendment W3/H"). Only the files the server holds move, and the photo never becomes deletable by the
  server's records or the NAS feed (docs/adr/0018 "Hand-offs the server holds become Server photos").

## Amendment W4: local copies (0.9.0)

**Sender rule (Hoard 2.2.0).** A server-held photo still comes as a server reference (W3/H, unchanged). A photo already
on the phone comes as its `content://media/...` URI. Every other photo (SMB not on the server, USB/SD/SAF) is copied
by Hoard into MediaStore and handed over as a `content://media/...` URI in the usual DEVELOP intent (ClipData, `TITLE`,
`INITIAL_URI`, `CULLING` v2 keyed by those URIs, `BATCH_INDEX`/`BATCH_COUNT` when split): RAW and JPEG into
`Pictures/Hoard/<batch>/` (`MediaStore.Images`), and the formats Images refuses (CR3) into `Download/Hoard/<batch>/`
(`MediaStore.Downloads`, `application/octet-stream`, the real name kept), never sidecars. Only
`FLAG_GRANT_READ_URI_PERMISSION`: no persistable grant, no write grant, no sidecar items. Hoard's SMB streaming links
(`content://dev.hfps.hoard.stream/...`) are the **legacy** path: not used for new hand-offs, but the links already in
existing libraries keep working (`busy:` handling stays).

**Sender contract: a shot's members stay in one chunk** (agreed with Hoard's coordinator): when Hoard splits a batch
over several intents, a RAW and its JPEG (and any other member of the shot) go in the same intent. Reveal keeps a
safety net for a sender that does not (item 5).

**Reveal.**
1. *Gallery access on receipt.* When **Hoard** (the referrer package `dev.hfps.hoard*`, `HandoffApps.isHoard`; the
   debug build's `HandoffHostActivity` plays it in tests) hands over documents of the `MediaStore.Images` collection
   (`GalleryAccess.isMediaImages`: `content://media/...` outside `/downloads/`, which is copied instead, item 3) and
   access is not FULL, Reveal offers the permission once ("To open photos from Hoard, Reveal needs access to all your
   photos"): `GalleryAccess.noteHoardMedia` on receipt, `shouldOfferForMedia(hoardMedia, state, mediaAsked)`. A batch
   shows it in the library (`LibraryViewModel.galleryOffer` = `GalleryOffer.HOARD`); a single photo shows it over the
   editor (`MainActivity.EditorGalleryOffer`, the same design-system sheet, non-blocking). The batch opens meanwhile
   from the temporary grant. PARTIAL gets a variant that says only the selected photos stay openable. Its answer is
   its own (`mediaAsked`, separate from the older gallery-share offer's `asked`, whose text is unchanged:
   `GalleryOffer.SHARED`); Settings → Gallery access changes it.
2. *Media URIs are stable references* under FULL (and for the selected photos under PARTIAL): re-openable without
   grants. The access check (`recheckAccess`, `checkHandoffs`, the background pass) simply opens them through
   `FileOpens`: readable stays readable, only a real refusal (`isAccessError`) marks a file unreadable, `busy:` never
   does. `takePersistableUriPermission` is never tried on them (`persist` skips authority `media`).
3. *Downloads originals are kept locally.* `READ_MEDIA_IMAGES` does not cover `MediaStore.Downloads`, so a hand-off
   file at `content://media/<volume>/downloads/<id>` (detected by URI, confirmed by its name and magic bytes, never by
   MIME) is copied, while the intent's grant is valid, into `noBackupFilesDir/handoff-originals/<fingerprint>.<ext>`
   (`LocalOriginals`, through the tree's `FileOpens` slot, `busy:` retried) and the file row is repointed at the copy
   (`document_id` = the `file:` URI; `relative_path` keeps the sender's URI, so the same URI handed over again finds
   the row and its fingerprint without reading). It is read from there from then on, never marked unreadable when the
   grant ends, and uploaded from there; identity stays by fingerprint. Only files filed under the hand-off source are
   copied (a shot that is a folder's or a Server photo keeps its own files, `StoredShot.foreign`). A copy already kept
   (same fingerprint and size) is filed again without opening the URI, so a failed re-copy never orphans it, and
   forgetting / the retention / a merge delete copies **by fingerprint** whatever the rows point at, unless a row
   still files under the copy (`deleteCopies`). Copies are refused when they would leave less than 200 MiB
   (`StorageManager.getAllocatableBytes`; reported as "not enough space", the photo keeps its URI and falls back to
   its preview); `*.tmp` files of an interrupted copy are swept before the first copy. An item without an extension is
   classified by its first bytes (and named after them) before the name filter, so an extensionless Downloads file is
   not dropped. It is *not* a cache: the budget never evicts it (a copy may be the only
   readable one), it has its own row in Settings → Storage (`StorageKind.HANDOFF_ORIGINALS`, not part of "Clear
   cache"), and only forgetting the photo, the retention, or merging it into a folder photo that has the file deletes it.
4. *Identity* is the v1 fingerprint as before: a Hoard copy, the NAS/server original and an earlier hand-off of the
   same content are one photo (`HandoffMerge`, folder-first and hand-off-first paths).
5. *Pairing* across collections: a CR3 in Downloads and its JPEG in Images arrive in one intent with different parents.
   The parent key of a MediaStore document (`HandoffRepository.parentKey(uri, relativePath)`) is its `RELATIVE_PATH`
   without the top-level folder (`Pictures/`, `DCIM/`, `Download/`), read by `HandoffTree.stat`: Hoard's
   `Pictures/Hoard/<batch>/` and `Download/Hoard/<batch>/` pair by stem, a camera's `DCIM/Camera/IMG_0001.JPG` never
   pairs with a Hoard `IMG_0001.CR3`. Without its path (not queryable) a MediaStore URI pairs only within its
   collection. Safety net for split batches: a single file whose shot an earlier chunk of the same batch (same
   `handoff_at` stamp) filed under the same key and stem joins that photo when the photo lacks its role
   (`PhotoDao.batchShot`, `joinEarlierChunk`).
6. *Adoption (W3/C) leaves local copies alone:* `SyncDao.adoptableHandoffs` skips a main file that is a readable
   `content://media/` file (an unreadable one is adopted as before) and never adopts a kept Downloads original
   (`file:` under `handoff-originals`): adoption would drop the row and orphan the copy.
7. No write grants are asked on these URIs and no sidecar is written for them (ADR 0026 "Amendment W4").
8. *Chunks.* Media-URI items split at 200 per intent use the same `BATCH_INDEX`/`BATCH_COUNT` scheme as server items;
   `HandoffIntents`, `HandoffBatches` and `MainActivity` treat URI-only, server-only and mixed chunks alike: one
   shared stamp and title (one entry in "Opened from other apps"), notices shown once, culling applied per chunk, the
   initial photo opens from index 0 only. The hand-off retention (400 photos) never cuts the newest batch.
9. *The grant's holder waits for what needs it.* Each intent is received in the app's scope (`HandoffIngests`, keyed
   per intent and saved in the activity's state), so a recreated activity (rotation) re-attaches instead of dropping
   a batch half-way; the activity follows it. `HandoffRepository.receiveStaged` returns the outcome once the photo to
   open first is filed (and its Downloads original kept): index 0 opens the editor or the library then. The rest
   (the other copies, 2 at a time through `FileOpens`, and the display previews) completes `Received.settled` in the
   background. A silent member (index > 0) keeps its activity, and so the intent's grant, until `settled`: the display
   previews of its own shots whose documents only that grant covers are written (`HandoffPreviewer.schedule` returns
   their job; display images never wait behind smart previews), then it finishes. Under FULL access Images documents
   need no waiting. A smart preview of a grant-only document is made only while its holder lives
   (`HandoffIngests.Ingest.holderGone` when it finishes); otherwise it comes from the display preview or the server later.

Tests: `HandoffMediaTest` (JVM: offer state machine and its own answer, Hoard-only Images trigger, no persistable
grant, cross-collection pairing by relative path (DCIM never pairs with Hoard), a RAW in a later chunk joining its
JPEG, kept original + revoked grant + restart + forget, no copy for a folder's photo, a kept copy reused when the URI
cannot be opened, forget by fingerprint, free-space refusal, `.tmp` sweep, an extensionless Downloads item, a silent
chunk whose grant is revoked after it settled (display previews made, no smart preview attempted), access check,
identity incl. a Server photo, URI chunks, retention), `HandoffServerRefsTest` (URI/server/mixed chunks),
`HandoffAdoptionTest` (kept originals never adopted), `ReadOnlyGrantsTest`; instrumented `HandoffMediaTest` (real
MediaStore Images/Downloads, read grant only through `HandoffHostActivity`, 600 URIs in 3 chunks of 200, the editor's
offer once, a batch that survives `recreate()` mid-ingest).

