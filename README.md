# sluice

Releases a Maven project split over the git submodules of one umbrella repository.

Each module is tagged in dependency order. The pins its dependents hold on it move to the released version, and
every module goes back to its next `-SNAPSHOT` afterwards. Gates run before anything is tagged. A run that stops
partway resumes where it stopped.

```text
sluice preview --all patch      # what would be released, and every gate's verdict
sluice release --module core=2.0.0
sluice why --module core        # a preview that says why each module is in or out
sluice resume [--dry-run]       # after a stop
sluice status [FILE]            # a run's state, module by module
sluice sync-pins [--dry-run]    # move every pin to its upstream's version on the branch
sluice graph                    # the modules, their pins and the tag order
sluice update [--check]         # this sluice to its latest release, the way it was installed
sluice completion               # the bash completion script; the rpm and the deb install it
```

A module's alias in `sluice.yml` is a flag too: `--sdk 1.10.0` is `--module sdk=1.10.0`. Every verb takes
`--umbrella DIR`; `sluice <verb> --help` lists the rest. Exit codes: 0 done or previewed clean, 1 refused, 2 stopped
partway (`sluice resume` goes on), 3 usage.

Only `release`, `resume` and `sync-pins` write; `preview`, `why`, `status`, `graph`, and `resume` or
`sync-pins` with `--dry-run`, write nothing.

A run repairs what it can before refusing, and a preview says what it would do:

- A module whose upstream is released is owed a release: it is added at its next patch (`--no-dependents` leaves
  it out, `owed: refuse` in `sluice.yml` refuses instead).
- A module behind its origin with no change of its own is fast-forwarded; a pin behind its upstream's `main` is
  moved and committed (the `ci` gate pushes it). If that changes what would be cut, the run refuses and asks for a
  new preview.
- A module ahead of its origin is pushed, and its CI is waited on (`ci-wait`, minutes) just before its release
  commit; meanwhile the run releases the modules that do not need it. A red run that failed in an upstream gone
  green since is rerun.
- A module whose upstream the run releases is built, its tests compiled, at its new pins just before its release
  commit.
- A module its target has not served in time holds back only the modules that need it; `sluice resume` goes on
  with them, and runs no `after-tag` step twice.

Configuration is an optional `sluice.yml` at the umbrella's root. Without one, sluice reads the graph from the
poms, tags `v{version}` on `main`, and publishes nowhere.

`sluice.schema.json` is the file's JSON Schema, for an editor's checks and completion. Name it on the file's first
line until your editor finds it by itself:

```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/BotMakerDev/sluice/main/sluice.schema.json
```

## Install

Fedora/RHEL or Debian/Ubuntu, x86_64:

```text
curl -fsSL https://botmakerdev.github.io/sluice/install.sh | sudo bash
```

It registers the signed dnf or apt repository at <https://botmakerdev.github.io/sluice/> (whose page gives the
same steps by hand) and installs from it; later versions come with the system's updates. The package carries its
own Java runtime and needs git and Maven. Debian and Ubuntu ship an unrelated `sluice`; the installer pins this
repository so its package wins, and replaces that one if it is installed.

Windows: `sluice.msi`, or `sluice-windows.zip` with its scoop manifest. Anywhere with Java 25: `java -jar
sluice-all.jar`. All are on the [Releases](https://github.com/BotMakerDev/sluice/releases) page.

`sluice update` updates any of them in its own way, each download checked against the release's
`checksums_sha256.txt`: a jar is replaced; the rpm or deb is upgraded from its signed repository by dnf or apt, or,
without that repository, the release's package installed (as root, by `sudo` on a terminal, `pkexec` off one or `sudo -n` without it); the
MSI's installer is run, an unzipped `sluice-windows.zip` unpacked over, and Scoop's install left to
`scoop update sluice`. On Windows the last step waits until every sluice of that install has ended, since a running
program's files are locked. sluice-desktop's *Check for updates* does the same.

Every install holds both the command, `sluice`, and the same releases in a window, `sluice-desktop` (sluice in the
desktop's menu or the Start menu).

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE): free for any noncommercial use; any commercial use needs a license from
LiQiyeDev. Versions up to 0.3.0 were released under Apache-2.0.
