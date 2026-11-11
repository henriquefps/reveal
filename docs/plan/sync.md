# Reveal: sync and cloud library (proposal, not scheduled yet)

Status: proposal from the owner (2026-11-11), placed in `milestones.md` as **MS**. Nothing here is built
yet, except the content fingerprint (see "Prepare now").

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
3. **Originals (optional backup, large):** RAW/JPEG files uploaded once, content-addressed by full
   SHA-256, so duplicates cost nothing. Per folder or per album, Wi-Fi only by default.
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
like the other two servers. Deployed on `cachyos-fps` with Docker Compose and a Cloudflare Tunnel.

### App side

A WorkManager sync job (offline queue, backoff, Wi-Fi/charging constraints for blobs), a Settings
screen (server, key, what to sync, storage used), a "cloud" state on library cells (local, preview
only, original in the cloud), and the tablet layout from M2 using the same library.

## Prepare now (cheap, avoids a migration later)

- Content fingerprint on every file (in progress: the hand-off identity fix of M1).
- Stable UUIDs for photos, versions and presets in addition to Room's numeric ids, before M3
  presets ship.
- Recipes stay self-contained JSON (ADR 0003), which is already the sync payload.

## Open questions for the owner

- Placement: right after M2 (recommended: the tablet layout lands in M2 and sync makes it useful) or
  after 1.0.
- Originals backup on by default, or opt-in per folder?
- Storage on the server: `cachyos-fps`'s own disk, or a folder on the NAS mounted there?
