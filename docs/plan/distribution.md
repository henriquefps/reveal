# Reveal: distribution and business model (owner's direction, not scheduled)

Status: direction decided by the owner (2026-11-11). Nothing to build before 1.0.0; it only constrains choices now.

## Decided

- **Open source stays.** The code remains public under AGPL-3.0-or-later.
- **A paid app on Google Play, one-time price** (no in-app purchases, no subscription inside the app). People pay for
  the convenience of a signed, updated build; anyone may still build it from source.
- **Bring your own server.** Sync (`sync.md`) works with any `reveal-sync` server the user runs; the app never assumes
  the owner's server. The server stays in this repo, open, with its setup UI.

## Later, only if the app becomes exceptional

- A hosted sync service run by the owner, by subscription, for people who do not want to run a server.
- The same subscription could include the AI editing assistant (`ai-assistant.md`), served from the owner's server.
- AGPL protects both: a third party offering a modified server as a service must publish its source.

## What this means now

- Server URL and keys are user-entered (pairing by QR from any server); no hard-coded hosts.
- No feature locks or licence checks in the app: the Play build and a source build are the same app.
- Keep the right to relicense future versions: all code is the owner's; outside contributions need a contributor
  agreement before they are merged.
- Before selling: check the name "Reveal" (it may be taken on Play / as a trademark), Play listing, privacy policy,
  merchant account. Part of the 1.0.0 release work.
