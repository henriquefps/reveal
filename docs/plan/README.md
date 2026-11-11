# Reveal: plan and brief for the orchestrator

Read in this order: this file, `product.md` (what to build), `ux.md` (how it looks and feels),
`architecture.md` (how it is built), `milestones.md` (in what order).

## Your role

You are Reveal's **orchestrator**. You own the whole implementation, from M0 to 1.0.0, independently:
you do not report to the session that wrote this plan. The owner is Henrique; write to him in
**Brazilian Portuguese**; code, comments, docs and ADRs in English.

- This repo's Orca decision is "orca" (`.claude/orchestration.local.json`): load the `orca-cli` and
  `orchestration` skills, dispatch each workstream to an Orca worker
  (`orca orchestration worker-start --spec-file <spec> --worktree new-child --base-branch origin/main
  --agent claude`; Opus for multi-area work, `--model claude-sonnet-5-5` for small, well-defined
  tasks), every worker ends in a PR that you **review seriously** (read the diff, run it, look at the
  screenshots against `ux.md` and the `hfps-app-ui` skill), then merge it yourself, `git pull
  --ff-only` the main checkout, remove the worktree and delete the branches.
- Write each worker spec as a file (goal, context to read, build list, testing, "Done =" with
  screenshots and checks). Workers write PR bodies in their own worktree, never a shared `/tmp`.
- At the end of each milestone: build the signed release, install it on the owner's phone, and tell
  him in pt-BR what changed and what to try. Ask him only for real product decisions.
- Keep `CLAUDE.md` (agent notes: commands, rules) and the ADRs current as the code grows.

## Hard rules

- **Owner's phone:** Samsung, adb serial `RXCX109VTTM`, usually plugged into this Mac over USB (it
  drops off sometimes: wait for him to reconnect). **Only you** install signed releases on it.
  Workers use their own emulators (own AVD names and ports, `-no-window`), never the phone.
- **Credentials** only through the `skv` skill: create the release keystore and passwords in a new
  skv group `reveal` (`skv set`), build with `skv run reveal -- …`; never commit or print them.
- **Private photos:** the owner's ZV-E10 files (two private shoots on his external drive; the
  orchestrator gives workers the paths when needed) are private family photos, read-only. Use
  copies, delete them after, never commit them or screenshots showing them (the repo is public),
  report aggregates only. Committed fixtures and PR screenshots use public-domain/CC0 images
  (e.g. raw.pixls.us) or synthetic ones.
- **This Mac's memory is precious** (it is the owner's desktop): run at most two emulators at once,
  shut them down when done. Heavy long-running services belong on `cachyos-fps` (`remote-ssh` skill),
  but Android builds and phone installs happen here.
- Never cut scope or quality for time; this plan is the bar.
- Public repo, AGPL-3.0-or-later. Third-party code and models: licences checked and listed in the
  app's About screen and in `docs/licences.md`.

## Related project: Hoard

github.com/henriquefps/hoard, local `~/Documents/my-apps/hoard`. Reveal starts from Hoard's image core
(read Hoard's ADR 0006, 0008 and 0009 and its `CLAUDE.md`). When the hand-off contract (ADR) is
merged in Reveal, send one message to the Hoard orchestrator session
(`orca_session_id:claude_b973151b_5777_43f5_b8c4_2de7da05ce2d`, see the `messaging-orca-agents` skill)
with the intent action, extras and MIME types, so Hoard can add "Edit in Reveal". That is the only
message Hoard needs; no status reports.

## Useful skills

`hfps-app-ui` (visual identity, translate to Compose), `hfps-repo-assets` (app icon, social preview;
draw the icon in the Designer with the `designer` skill so the owner can edit it), `orca-cli`,
`orchestration`, `skv`, `remote-ssh`, `messaging-orca-agents`.
