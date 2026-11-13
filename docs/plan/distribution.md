# Reveal: distribution constraints

The owner's business plan is private and kept outside this repository. What it asks of the code:

- The code stays public under AGPL-3.0-or-later; anyone can build it from source.
- The app is complete on its own: no in-app purchases, no trial or unlock, no feature behind a paywall.
- Bring your own server: sync works with any `reveal-sync` server the user runs; the app never assumes a particular
  server, and the server (with its setup UI) stays open in this repository.
- Preset packs are data, not code: a versioned, documented file format with pack metadata (name, author, cover,
  licence, link), import from a file or a link, packs as groups, and a "try on my photos" preview before keeping one
  (ADR 0019).
- Hand-off with Hoard works both ways, and Reveal works fully without Hoard (ADR 0011).
