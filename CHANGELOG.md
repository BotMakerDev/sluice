# Changelog

All notable changes to this project are documented here, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), with [semantic versioning](https://semver.org/).

## [Unreleased]

### Added

- `desktop` (`sluice-desktop`): a JavaFX window over the library, its shaded jar
  `desktop/target/sluice-desktop-<version>-all.jar`. A start screen opens an umbrella, a folder with a
  `sluice.yml`, and keeps the recent ones; then four tabs:
  - **Modules**: each module's version on the branch, newest tag, CI, its target's build of that tag, and its pins.
  - **Release**: the command's flags and a request per module; Preview decides and gates, and prints what
    `sluice` prints; Execute… is armed only by a clean preview of the same flags and asks for a typed word. The run
    is the library's, in the window's process: a board draws each module's steps as they come, Stop stops it
    between steps, and Resume… goes on with a run that did not finish.
  - **Releases**: the runs under `releases/`, each drawn from its state beside its log.
  - **Changelog**: each module's `CHANGELOG.md` to edit, with the commits since its newest tag; Save, or Save and
    commit the file alone.
  The GitHub token is read as the command reads it (`GITHUB_TOKEN`, else `gh auth token`) and never shown.
- What a program around sluice needs, in `sluice-core`:
  - `ConfigLoader.check(text, label)` gives every problem of a `sluice.yml` at once (`ConfigProblem`: key path,
    message, line), YAML syntax errors included; `parse` still refuses the first one, word for word.
  - `ConfigDocument` edits a `sluice.yml` by key path (`set`, `remove`) and keeps the file as it was written:
    comments, quoting, flow or block style. An edit changes only the lines it touches; a document saved unedited is
    byte for byte the file it read.
  - `Release.preview(umbrella, requests, options)` decides and runs the gates, and tags nothing: its `Preview`
    holds the plan and the verdicts.
  - `ReleaseLogs.list(umbrella)` lists the runs under `releases/`, the newest first, each with its log, state and
    how it ended; `ReleaseLogs.read(file)` reads one run by either of its files.
  - `ModuleStatus` asks of one module what the gates ask: its newest tag, its CI on the release branch, its publish
    target's build of a release, and its pom pins against its upstreams' versions.

### Changed

- sluice is two Maven modules at one version: `core` (`sluice-core`, the library, which prints nothing) and `cli`
  (`sluice-cli`, the command and its `-all` jar). A program embeds
  `com.github.BotMakerDev.sluice:sluice-core:<tag>` from JitPack. The packages and their names are unchanged.

## [0.2.0] — 2026-11-10

### Added

- A pin in a plugin's `<artifactItem>` (what `maven-dependency-plugin` copies) is an edge like any other; its
  version moves only through a `${property}`, so a literal one is refused by name.
- `kind: ignored` in `sluice.yml` leaves a submodule out of the graph though it has a pom: the release tool's own
  submodule, say.
- `changelog: {pins: true}`: the release commit writes, under the version's `### Changed`, one line per upstream it
  now builds against (`- Built against a 1.0.1 (was 1.0.0).`), after the module's own notes. A module released
  with no change of its own (only for its upstreams) needs no notes then: those lines are its notes.
- A red `ci` names each upstream whose CI went green after the failed run: a dependent's CI builds its
  upstreams' branches, and a rerun may pass.
- One-command install on Fedora/RHEL and Debian/Ubuntu (x86_64):
  `curl -fsSL https://botmakerdev.github.io/sluice/install.sh | sudo bash`. Each tag publishes signed dnf and apt
  repositories on this repository's GitHub Pages, so later versions arrive with the system's updates; the rpm is
  signed too. On Debian and Ubuntu, whose own `sluice` is an unrelated pipe rate limiter, an apt pin makes this
  one win.

### Changed

- Untracked files are no concern of a release: `clean` no longer refuses them, and the release and snapshot
  commits stage the tracked files and what their own steps created, each named in the output, never a file that
  was untracked when that commit's work began (an editor's, a build's, another step's).

### Fixed

- An umbrella named by a relative path (the default, here) is read once: each module's git ran with its directory
  given twice, and every module came out untagged, detached and unreadable. `Runner.git` names its directory
  absolutely too, for a library caller that passes a relative one.
- A module never tagged and asked for by a level releases its branch's version (`0.1.0-SNAPSHOT` releases
  `0.1.0`), or that level above `0.0.0` when it is higher (`major` still releases `1.0.0`).
- `ci` ignores the runs of a workflow whose file is no longer in the module: its old red run says nothing of the
  branch now. GitHub's own workflows, which have no file, still count.
- `builds-at-pins` asks for a pinned upstream's repository only when the module's build fetches it: a version that
  only its `<dependencyManagement>` names is fetched by the poms that inherit it, from their own repositories. A
  bom imported there (`<scope>import</scope>`) is fetched by the module's own build, and still needs it.
- A refused run says "refused: nothing was tagged" once.

## [0.1.0] - 2026-11-10

### Added

- Versions, levels and the process runner; a preview runner that echoes every command and runs none. On Windows a
  bare program name runs by its `.exe`, `.cmd` or `.bat`, and an argument keeps the quotes inside it.
- The umbrella layout: the project's repositories read from `.gitmodules`, in its order.
- The graph: each pom read as written, the pins that move an upstream's version (a property, wherever up the
  parents it is declared; a literal; a `<parent>`), text pins and extra edges from `sluice.yml`, and the tag
  order (upstreams first, ties by `.gitmodules` order, `place: first | last | {after}`, a cycle refused by name).
- Deciding: each module's newest tag, what changed since it (a back-to-snapshot commit and moved pins are no
  change; only ignored files is its own answer; an upstream released since is a change), requests by name, level
  or version, and the dependents a release owes, listed or added with `withDependents`.
- Gates, all run before the first tag, each answering ok, skipped, forced or refused: `clean` (on the release
  branch, committed, not behind its remote), `owed`, `pins-at-main`, `text-pins`, `changelog` (the built-in reader
  or a configured extractor), `ci` (each workflow's newest finished run on GitHub), `pins-resolve`,
  `builds-at-pins` (the pom declares each pinned upstream's repository, and the module compiles at its release
  pins in an empty local repository), and a project's own commands with the release's facts in `RT_` variables.
- Publish targets: `jitpack` (the pin is the tag's name; `{jitpack: {maven: <version>}}` names the Maven its
  builds install), `{maven: <url>}` (the bare version; Central needs no declared repository, a `file:` address is
  a directory), and `none`. The wait for a tag's publish, owed only to a module a later one in the run resolves
  from a pom; JitPack's wait stops at a build it reports failed and says when Maven Central's rate limit failed
  it. The `target` gate: on JitPack, every script `jitpack.yml` runs is committed, and every pinned Maven plugin
  runs on the build's Maven, read from the plugin's own pom.
- `sluice.yml`, read strictly: an unknown key or a bad value is refused, naming where it is.
- The release itself, a preview unless asked to execute. Each module, in tag order: the release commit (its
  version, each pin at its upstream's release, the `## [Unreleased]` changelog heading stamped, the
  `before-release-commit` steps), the tag, the push of branch and tag together, a GitHub release page with the
  version's notes (`github-releases`, on by default, only with a token), the `after-tag` steps, the wait for its
  target when a later module resolves it, then back to the next snapshot: pins to their upstreams' branch
  versions, `after-release` properties set, the `after-snapshot` steps, pushed, and each dependent the run does
  not release following to the snapshot in a commit of its own. One umbrella commit at the end holds the moved
  pointers, the run's log and its state.
- Pins move through `versions-maven-plugin` (`set`, `set-property`, `use-dep-version`), a `<parent>` and a text
  pin as text, and every move is read back.
- `releases/<YYYY-MM-DD-HHMM>.json`, the run's state after every step, beside its log; resume goes on from the
  newest, a module whose target failed to build its final tag released again at its next patch.
- One release at a time per umbrella: a lock in its git directory, taken over from a run of this host that no
  longer runs, refused otherwise.
- Sync pins: each pom pin moved to its upstream's version on the branch, one local commit per module.
- `builds-at-pins` builds a module pinning by a literal or a `<parent>` too, those pins moved in the clone.
- The command line: `sluice [--umbrella DIR] (--all LEVEL | --module NAME[=VERSION|LEVEL]…) [--with-dependents]
  [--force] [--execute] [--no-wait] [--why]`, `--resume`, `--sync-pins`, `--status [FILE]` and `sluice graph`
  (the tag order, each edge and its pins). A module's alias in `sluice.yml` is a flag of its own (`--sdk 1.10.0`).
  Exit codes: 0 done or previewed clean, 1 refused, 2 stopped partway, 3 usage. Ctrl-C stops a release between
  two steps, resumable.
- Packages: `sluice-<version>-all.jar`; on a tag, CI builds an rpm and a deb carrying their own Java runtime, a
  Windows MSI, and a zip with its scoop manifest, all attached to the tag's GitHub release.
