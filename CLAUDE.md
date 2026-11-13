# CLAUDE.md — sluice

A release tool for a Maven project split over the git submodules of one umbrella. Spec:
`docs/superpowers/specs/2026-11-10-sluice-design.md` in the BotMaker umbrella; plan
`docs/superpowers/plans/2026-11-10-sluice.md` there.

- **Generic.** No main source names BotMaker (`NoBotMakerTest`); BotMaker is one user, through `sluice.yml`.
- **Modules, one version:** `core/` (`sluice-core`: every package but `cli`) and `cli/` (`sluice-cli`: the command,
  its shaded `cli/target/sluice-cli-<version>-all.jar`) and `desktop/` (`sluice-desktop`: the JavaFX window, on
  core only; `DesktopSourcesTest`). `core` attaches its test fixtures as a `test-jar`.
- **The window decides nothing.** Every answer is a core call the command also makes; a release runs in-process
  through `Release.run`/`resume` and its `Event`s.
- **A library first.** `com.botmaker.sluice` prints nothing. Only `cli` writes to a terminal.
- **Every side effect goes through `Runner`.** A preview echoes it and runs none of it.
- **Shell to `git` and `mvn`**, never embed them. Versions move only through `versions-maven-plugin`.
- **A gate that cannot run is `SKIPPED`, never `OK`**; `--force` never lifts a `SKIPPED`.
- **Linux and Windows.** A command is an argument list, never a shell line, unless the config gives a string.
- Tests build their own umbrella with `FixtureUmbrella`; none reads a real checkout.
