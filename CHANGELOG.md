# Changelog

All notable changes to this project are documented here, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), with [semantic versioning](https://semver.org/).

## [Unreleased]

### Added

- Versions, levels and the process runner; a preview runner that echoes every command and runs none.
- The umbrella layout: the project's repositories read from `.gitmodules`, in its order.
- The graph: each pom read as written, the pins that move an upstream's version (a property, wherever up the
  parents it is declared; a literal; a `<parent>`), text pins and extra edges from `sluice.yml`, and the tag
  order (upstreams first, ties by `.gitmodules` order, `place: first | last | {after}`, a cycle refused by name).
- Deciding: each module's newest tag, what changed since it (a back-to-snapshot commit and moved pins are no
  change; only ignored files is its own answer; an upstream released since is a change), requests by name, level
  or version, and the dependents a release owes, listed or added with `withDependents`.
- `sluice.yml`, read strictly: an unknown key or a bad value is refused, naming where it is.
