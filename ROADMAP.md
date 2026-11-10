# Roadmap

Open work only. The plan for v0.1.0 is `docs/superpowers/plans/2026-11-10-sluice.md` in the BotMaker umbrella.

- A child pom of a multi-module repository: its version and parent moved by a release still read as a change.
- Deciding fetches each module's tags one after the other; fetch them together.
- `builds-at-pins` skips a module that pins an upstream released by the same run, or pins one by a literal or a
  `<parent>`: build those too, moving the pins in the throwaway tree with the release's own mover.
- `builds-at-pins` downloads every plugin and dependency into its empty local repository on each run.
- The `lock` and `target` gates; publish targets, the release itself with state, lock and resume.
- The command line; rpm, deb and Windows packages.
- v2: the extension SPI published as a stable API; layouts beyond an umbrella of submodules.
