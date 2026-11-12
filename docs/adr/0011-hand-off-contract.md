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
| `dev.hfps.reveal.extra.CULLING_VERSION` | `Int`, optional | Layout version of `CULLING`; absent = 1. Current: **1**. |

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
dev.hfps.reveal.extra.CULLING_VERSION : Int    = 1                   (optional; absent = 1)
dev.hfps.reveal.extra.CULLING         : Bundle {
    "<item uri, exactly uri.toString() as put in the ClipData>" : Bundle {
        "rating" : Int     0..5                                  (optional)
        "flag"   : String  "pick" | "reject" | "none"            (optional, case-insensitive)
        "label"  : String  colour label, xmp:Label ("Red", ...)  (optional; "" or blank clears it; max 64 chars)
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
  capped at 1.5 GiB (least recently used first); a forgotten or merged hand-off loses both.
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
