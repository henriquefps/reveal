# Reveal: sync and cloud library (proposal, not scheduled yet)

Status: **MS (0.4.0), after M2** (owner, 2026-11-11). Built: the content fingerprint and stable ids (see "Prepare
now") and, in MS/1, the **server** (`sync-server/`, `reveal-sync`): the protocol is
[ADR 0017](../adr/0017-sync-protocol.md) (HTTP API `/v1`, pairing, change feed, conflicts, chunked uploads, NAS
indexing; OpenAPI in `sync-server/api/openapi.yaml`), self-hosting in `sync-server/README.md`; in MS/2 the **app
side** ([ADR 0018](../adr/0018-sync-in-the-app.md): `:core:sync`, Settings → Sync with QR pairing, the sync jobs,
conflict copies in the library, the Server source with NAS photos read by range, smart previews and the originals
backup).

## What the owner wants

- Keep working on the same photos and edits on any of his devices (phone, tablet), like Lightroom's
  cloud library.
- A backup of his photos and edits on his own server, not a third-party cloud.
- The same model as his other apps' sync servers (Markdown Previewer `markdown-preview-sync`, Designer
  `designer-sync`): one small Go program, data in one folder he owns, running on `cachyos-fps`,
  reached through Cloudflare Tunnel (or Tailscale) away from home.

## How Reveal holds photos today

Reveal never imports or copies photos. The library keeps **references**: added folders (SAF trees)
and files opened from other apps (content URIs). Edits (recipes, history, ratings) live in the app's
Room database on that device. So a second device today sees neither the photos nor the edits. Photos
opened through a file manager from the SMB share are references to that app's URIs and may stop
working when its permission ends.

## Design

### Identity: the content fingerprint

A photo is recognised by its content, not its name or URI: `v1:` + SHA-256 over the file size and
its first and last 1 MiB (cheap, unique in practice because the header holds capture time, serial and
shutter count). The same photo opened from the gallery, a file manager, the SMB share or another
device has the same fingerprint, so it finds its edits. A full-file SHA-256, computed in the
background, is the content address for uploads.

### What syncs

1. **Edits and library metadata (always, small):** recipes with their history, versions, ratings,
   flags, labels, presets, settings that should follow the owner (export presets, author/copyright).
   Keyed by fingerprint and stable UUIDs. The fingerprint does not depend on the device.
2. **Smart previews (optional, medium):** a compact developable proxy per photo, about 2560 px,
   so a device without the original can still edit at screen size. Export at full size then needs the
   original (download on demand) or is queued until it is reachable.
3. **Originals backup (large), only for photos the server cannot already reach:** photos that live
   only on a device (imported from the camera or an SD card, the phone's DCIM) are uploaded once,
   content-addressed by full SHA-256, Wi-Fi only by default, on by default for those. A photo whose
   fingerprint the server already has (it is in an indexed NAS folder, or another device uploaded it)
   is never uploaded again: editing a NAS photo syncs only its edits. A per-folder switch turns the
   backup off (decided with the owner, 2026-11-11).
4. **The SMB share:** `cachyos-fps` is on the same network as the NAS, so the server can index NAS
   folders directly and serve them, with no upload from the phone. The tablet then sees the NAS shoots
   and their edits even away from home, through the tunnel.

### Conflicts

As in Designer: last-write-wins per photo version, and the loser is kept, as a **virtual copy** in
Reveal's terms (M5 versions), never silently dropped. History steps are append-only per device, so
a later merge of non-overlapping groups (one device crops, the other grades) is possible but not in
v1.

### Security

Private family photos, so stricter than Designer: an API key per device is **always** required (no
LAN-only anonymous mode), constant-time compared, revocable per device; HTTPS only through the
tunnel; optionally Cloudflare Access service tokens in front. Keys stored on the phone in the Android
Keystore, server secrets in `skv`. Files at mode 0600; optional encryption at rest of originals.

### Server

`reveal-sync` in Go in this repo (`sync-server/`): metadata in SQLite, blobs (previews, originals)
in a content-addressed folder, chunked resumable uploads, a list endpoint with changes since a
cursor, tombstones kept forever, health endpoint with counts only. Docker image + static binaries,
like the other two servers. Built in MS/1 (ADR 0017). The owner's own deployment (Docker Compose and a
Cloudflare Tunnel) is a separate, later step; nothing in the server or the app assumes it.

### Setup UI and deployment

The server serves a small web setup page (protected by an admin password set on first run, and only
on the LAN/Tailscale unless the owner enables it through the tunnel):
- **Devices:** pair a new device with a QR code (the app scans it and receives its own key), see last
  sync per device, revoke a device.
- **Storage:** choose the backup folder among the paths mounted into the container, see space used
  and free.
- **NAS folders:** choose which mounted NAS folders the server indexes and serves (read-only).
- Status: photos, previews, originals, pending uploads, errors.

Docker Compose mounts the volumes: `/data` (database and settings), the backup root (for example a
folder on the NAS or on `cachyos-fps`'s disk) and the NAS shoots read-only. The UI only picks among
those mounts, so the container never sees anything else.

### App side

A WorkManager sync job (offline queue, backoff, Wi-Fi/charging constraints for blobs), a Settings
screen (server, key, what to sync, storage used), a "cloud" state on library cells (local, preview
only, original in the cloud), and the tablet layout from M2 using the same library.

## Prepare now (cheap, avoids a migration later)

- Content fingerprint on every file (in progress: the hand-off identity fix of M1).
- Stable UUIDs for photos, versions and presets in addition to Room's numeric ids, before M3
  presets ship.
- Recipes stay self-contained JSON (ADR 0003), which is already the sync payload.

## Decided in MS/1 (ADR 0017)

- Pairing by a one-time QR token (10 minutes) exchanged for a per-device key; the admin password is set on first run
  with a setup code from the server's log, so nobody else on the network can claim a fresh server.
- Conflict copies are new `version` records with `copyOf`; photo metadata, presets and settings are last-write-wins.
- The server lists its NAS files to devices (`/v1/nas/files`) and serves them by fingerprint, so a device away from
  home sees the NAS shoots.
- Smart previews are made by devices (the server has no RAW decoder).

## Decided (2026-11-11)

- Placement: after M2 (orchestrator's choice, owner left it open).
- Originals backup: only for photos the server cannot already reach (rule above).
- Storage: chosen in the setup UI among Docker-mounted paths.
