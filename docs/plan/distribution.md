# Reveal: distribution and business model (owner's direction, not scheduled)

Status: direction decided by the owner (2026-11-11). Nothing to build before 1.0.0; it only constrains choices now.

## Decided

- **Open source stays.** The code remains public under AGPL-3.0-or-later.
- **A paid app on Google Play, one-time price** (no in-app purchases, no subscription inside the app). People pay for
  the convenience of a signed, updated build; anyone may still build it from source.
- **Bring your own server.** Sync (`sync.md`) works with any `reveal-sync` server the user runs; the app never assumes
  the owner's server. The server stays in this repo, open, with its setup UI.
- **No "free then pay" model** (free download with a time-limited trial and an unlock purchase): it reads as
  misleading and draws bad reviews. The price is visible in the store. Ways to try before buying stay honest: Play's
  refund window, a listing with real before/after screenshots and a short video, promo codes for reviewers, and the
  free build from source (GitHub releases / F-Droid).

## Hoard as the free companion

Hoard (the owner's file manager with photo culling) stays **free** and is the way in: cull in Hoard, develop in
Reveal (hand-off contract, ADR 0011). Both under the same Play developer account, each listing mentions the other.
In Hoard, "Develop in Reveal" stays visible when Reveal is not installed and opens Reveal's store page (no ads, no
nagging). Reveal works fully without Hoard.

## Preset packs (idea, needs an audience first)

Packs of presets made by the owner or by photographers/influencers (revenue share), sold as content, not as app
features: the app stays complete without them. Packs are data, not code, so they can be sold under their own licence
while the app stays AGPL. Sold outside the app first (a file or link that the app imports); in-app purchase later if it
is worth it. It only works with people using the app, so it comes after the launch and an audience.

What M3 must make possible now (cheap): a versioned, documented preset file format with pack metadata (name, author,
cover, licence, link), import from a file or a link, packs as groups, and a "try on my photos" preview before keeping a
pack.

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
