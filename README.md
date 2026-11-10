# sluice

Releases a Maven project split over the git submodules of one umbrella repository.

Each module is tagged in dependency order. The pins its dependents hold on it move to the released version, and
every module goes back to its next `-SNAPSHOT` afterwards. Gates run before anything is tagged. A run that stops
partway resumes where it stopped.

```text
sluice --all patch              # preview: what would be released, and every gate's verdict
sluice --module core=2.0.0 --execute
sluice --resume --execute       # after a stop
sluice graph                    # the modules, their pins and the tag order
```

A module's alias in `sluice.yml` is a flag too: `--sdk 1.10.0` is `--module sdk=1.10.0`. Exit codes: 0 done or
previewed clean, 1 refused, 2 stopped partway (`--resume` goes on), 3 usage.

A preview is the default. Nothing is tagged, pushed or written without `--execute`.

Configuration is an optional `sluice.yml` at the umbrella's root. Without one, sluice reads the graph from the
poms, tags `v{version}` on `main`, and publishes nowhere.

## Install

Fedora/RHEL or Debian/Ubuntu, x86_64:

```text
curl -fsSL https://botmakerdev.github.io/sluice/install.sh | sudo bash
```

It registers the signed dnf or apt repository at <https://botmakerdev.github.io/sluice/> (whose page gives the
same steps by hand) and installs from it; later versions come with the system's updates. The package carries its
own Java runtime and needs git and Maven. Debian and Ubuntu ship an unrelated `sluice`; the installer pins this
repository so its package wins, and replaces that one if it is installed.

Windows: the MSI, or `sluice-windows.zip` with its scoop manifest. Anywhere with Java 25: `java -jar
sluice-all.jar`. All are on the [Releases](https://github.com/BotMakerDev/sluice/releases) page.

## Licence

Apache-2.0.
