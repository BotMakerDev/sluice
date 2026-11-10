# Roadmap

Open work only. The plan for v0.1.0 is `docs/superpowers/plans/2026-11-10-sluice.md` in the BotMaker umbrella.

- A child pom of a multi-module repository: its version and parent moved by a release still read as a change.
- Deciding fetches each module's tags one after the other; fetch them together.
- `builds-at-pins` skips a module that pins an upstream released by the same run: that release is published only
  once the run tags it.
- `builds-at-pins` downloads every plugin and dependency into its empty local repository on each run.
- A JitPack coordinate of a multi-module repository (`com.github.owner.repo:module`): the wait asks for the root
  artifact's address only.
- The JitPack plugin check reads the module's own pom only: a plugin a `<parent>` or its `pluginManagement` pins
  is not checked, and a requirement a plugin states only in its `plugin.xml` (`requiredMavenVersion`) is not read.
- The Maven a JitPack build runs is one value for the umbrella (or a module's own `target`); reading it from each
  module's `jitpack.yml` would keep it from drifting.
- A Maven repository that needs credentials to answer: the target reads it as not answering, so the wait times
  out.
- Tab completion for bash and zsh, generated from the command line and installed by the rpm and the deb.
- A signed rpm, and dnf and apt repositories to install from.
- v2: the extension SPI published as a stable API; layouts beyond an umbrella of submodules.
