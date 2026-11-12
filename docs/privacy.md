# Privacy notes

What Reveal does with your photos and the network. Reveal has no account, no analytics, no ads, no crash reporting
service and no server of its own: it talks to the network only in the two cases below, both started by you.

## Photos

- Originals are never modified: edits are recipes in the app's database (and, when you allow it, ratings in XMP
  sidecars next to the photos).
- Every analysis runs on the device, offline: Auto, face and subject detection (an open model run in Reveal's own
  code), lens profiles, previews. Nothing about a photo leaves the device for it.

## Network use

| When | To where | What is sent | What comes back |
| --- | --- | --- | --- |
| Sync (only after you pair a server, docs/adr/0017, 0018) | Your own reveal-sync server, at the address in its pairing QR code | Your edits, ratings, presets, smart previews and, if you turn on the backup, originals; authenticated by the device key from pairing | The other devices' changes; NAS photos you open |
| Import presets from a link (docs/adr/0019 "Import from a link") | The `https` host of the link you pasted or shared, after you confirm it by name | One `GET` of that URL with a generic `User-Agent: Reveal`; no cookies, no identity, nothing about you or your photos | One `.revealpreset` / `.revealpack` (at most 32 MiB), checked like a file you open |

There are no background fetches besides sync: a link is downloaded once, when you tap Download, and never refreshed.
