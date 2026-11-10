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

A preview is the default. Nothing is tagged, pushed or written without `--execute`.

Configuration is an optional `sluice.yml` at the umbrella's root. Without one, sluice reads the graph from the
poms, tags `v{version}` on `main`, and publishes nowhere.

Status: in development; v0.1.0 is not released yet.

## Licence

Apache-2.0.
