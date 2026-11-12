# Privacy notes

What Reveal does with your photos and the network. Reveal has no account, no analytics, no ads, no crash reporting
service and no server of its own: it talks to the network only in the two cases below, both started by you.

## Photos

- Originals are never modified: edits are recipes in the app's database (and, when you allow it, ratings in XMP
  sidecars next to the photos).
- Every analysis runs on the device, offline: Auto, face and subject detection (an open model run in Reveal's own
  code), lens profiles, previews. Nothing about a photo leaves the device for it.
- A photo another app shares with Reveal is read where it is. While Reveal can read it, it keeps a small local copy
  in its own storage, never backed up and never shared: a display image (1600 px) for every such photo and, when the
  other app's access ends with Reveal's activity (the Samsung Gallery's share does), a smart preview (about
  2560 px) to keep editing it (docs/adr/0011 "Unreadable hand-offs"). Forgotten hand-offs lose theirs; at most
  1.5 GB of smart previews are kept.

## Permissions

| Permission | When it is asked | What it allows |
| --- | --- | --- |
| Camera | Only on the Sync screen, to read a pairing QR code | Nothing is stored or sent |
| Photos and videos (`READ_MEDIA_IMAGES`, with "selected photos only" on Android 14+; `READ_EXTERNAL_STORAGE` up to Android 12) | Optional. Offered once, in a sheet, when a photo the gallery shared can no longer be read; or from Settings → Gallery access. Declining is remembered | Reveal reads the gallery photos you shared with it again any time (full thumbnail, full-size export, original backup to your own sync server). Read only: Reveal never changes, moves or deletes a gallery photo |
| Notifications | When an export or a batch runs in the background | Progress and results |

## Network use

| When | To where | What is sent | What comes back |
| --- | --- | --- | --- |
| Sync (only after you pair a server, docs/adr/0017, 0018) | Your own reveal-sync server, at the address in its pairing QR code | Your edits, ratings, presets, smart previews and, if you turn on the backup, originals; authenticated by the device key from pairing | The other devices' changes; NAS photos you open |
| Import presets from a link (docs/adr/0019 "Import from a link") | The `https` host of the link you pasted or shared, after you confirm it by name | One `GET` of that URL with a generic `User-Agent: Reveal`; no cookies, no identity, nothing about you or your photos | One `.revealpreset` / `.revealpack` (at most 32 MiB), checked like a file you open |

There are no background fetches besides sync: a link is downloaded once, when you tap Download, and never refreshed.
