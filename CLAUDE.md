# Reveal: notes for agents

Non-destructive RAW photo editor for Android (Kotlin + Jetpack Compose, GPU pipeline, LibRaw).
Start with `docs/plan/README.md` (the brief), then `docs/plan/` and `docs/adr/`.

## Rules (until the code exists, the plan is the source of truth)

- UI follows `docs/plan/ux.md`: the `hfps-app-ui` identity translated to Compose; the editor is
  always dark with a zero-chroma photo surround.
- No hard-coded UI strings: `values/strings.xml` and `values-pt-rBR/strings.xml` together.
- Every pipeline operator has a CPU reference (JVM-tested) and the GPU result matches it.
- Originals are never modified; edits are recipes (`docs/plan/architecture.md`).
- Credentials only through `skv` (group `reveal`). Never commit keystores or secrets.
- Private photos: copies only, never committed, never in screenshots, aggregates only.
- Workers use emulators; only the orchestrator installs on the owner's phone (`RXCX109VTTM`).
- Code, identifiers, comments and ADRs in English.

## Commands

Added by M0 (Gradle tasks, emulator setup, release build under `skv run reveal`).
