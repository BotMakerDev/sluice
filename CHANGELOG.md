# Changelog

All notable changes to this project are documented here, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), with [semantic versioning](https://semver.org/).

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
