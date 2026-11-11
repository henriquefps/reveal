# ADR 0011: Hand-off contract (Hoard and other apps)

Status: accepted (M1, hand-off). Date: 2026-11-11. Builds on ADR 0006 (library data model, XMP) and Hoard's ADR 0006
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

### The "Opened from other apps" source

A hand-off becomes rows of the library (ADR 0006 tables) under one special source (`sources.kind = HANDOFF`, shown in
the sources list as "Opened from other apps", never as a folder). Its `DocumentTree` (`HandoffTree`) uses the content URI
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
  (`<stem>.xmp`, or `<file name>.xmp`, with a **write grant**): a URI of a document Reveal cannot create beside the photo
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
fun editInReveal(context: Context, files: List<Uri>, title: String?, openFirst: Uri? = null): Boolean {
    val intent = Intent("dev.hfps.reveal.action.DEVELOP")
        .setType("image/*")
        .putExtra("dev.hfps.reveal.extra.TITLE", title)             // optional
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

## Alternatives considered

- **Copy the files into Reveal's storage:** doubles RAW storage and breaks sidecar write-back; rejected (originals are
  never copied).
- **A result intent** (`startActivityForResult`) with the new ratings: sidecars are the shared, portable truth between
  the apps (Hoard ADR 0009), and a result is lost when either process dies.
- **Creating `<stem>.xmp` for any content URI:** not possible without a folder grant; a sender that wants write-back
  passes the sidecar item (above).
- **Pairing across intents:** a pair arrives in one intent from every sender considered; stem collisions across batches
  (`IMG_0001` from two cameras) would join unrelated photos.

## Consequences

- Hoard's side is one function (the example) and a `<queries>` element; no Reveal library is needed.
- DB version 3 (auto-migration: `sources.kind`, `photos.handoff_at`, `photos.handoff_title`); version 4 adds
  `photo_files.fingerprint` (ADR 0006 "Identity").
- Receiving reads up to 2 MiB per new file to fingerprint it (4 at a time); a URI already known with the same size and
  mtime reuses its stored fingerprint.
- Hand-offs are library photos like any other: they appear in "All photos" and "Recent", can be rated, edited and
  removed, and keep their edit history across hand-offs of the same photo.
- The instrumented test cannot send from another uid; cross-app grants are the platform's, verified by Hoard's side.
