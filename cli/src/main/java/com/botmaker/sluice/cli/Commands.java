// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.cli;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.decide.Dependents;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.Release.Options;
import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.execute.Release.Saved;
import com.botmaker.sluice.execute.ReleaseState;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** sluice's verbs: each does what it names, and {@code preview} and {@code sync-pins --dry-run} write nothing. */
final class Commands {

    private Commands() {
    }

    /** A verb that may write: a failure under it may have stopped a release partway, never "refused". */
    interface Writing {

        boolean writes();
    }

    /** {@code --umbrella}, on every verb; before the verb ({@code sluice --umbrella DIR preview}) works too. */
    static final class Umbrella {

        @Spec(Spec.Target.MIXEE)
        private CommandSpec spec;

        @Option(names = "--umbrella", paramLabel = "DIR", description = "The umbrella repository (default: here).")
        private Path umbrella;

        /** The umbrella as an absolute path, so every module's directory under it is one too. */
        Path root() {
            return umbrella != null ? umbrella.toAbsolutePath().normalize() : Main.of(spec).umbrella();
        }
    }

    /** What a release takes: which modules, at what level, and how it treats gates, dependents and publishes. */
    static final class Selection {

        @Spec(Spec.Target.MIXEE)
        private CommandSpec spec;

        @Option(names = "--all", paramLabel = "LEVEL",
                description = "Release every changed module: patch, minor, major.")
        private String all;

        @Option(names = "--module", paramLabel = "NAME[=VERSION|LEVEL]",
                description = "Release one module, by its path, alias or artifactId. Repeatable. A module's alias "
                        + "in sluice.yml is a flag too: --sdk 1.10.0.")
        private List<String> modules = new ArrayList<>();

        @Option(names = "--with-dependents", description = "Also release, at a patch, every dependent a release "
                + "owes, though sluice.yml says owed: refuse (without it, owed: release does so already).")
        private boolean withDependents;

        @Option(names = "--no-dependents", description = "Release only what is named: a dependent a release owes "
                + "keeps its released pins, its branch's pin follows.")
        private boolean noDependents;

        @Option(names = "--force", description = "Go on past a refused gate (never past one that could not run).")
        private boolean force;

        @Option(names = "--no-wait", description = "Do not wait for a publish a later module resolves from.")
        private boolean noWait;

        Requests requests() {
            if (all == null && modules.isEmpty()) {
                throw new ParameterException(spec.commandLine(), "say what to release: --all LEVEL or --module NAME");
            }
            return Requests.parse(modules, level());
        }

        Options options(boolean execute) {
            if (withDependents && noDependents) {
                throw new ParameterException(spec.commandLine(), "--with-dependents and --no-dependents: say one");
            }
            Optional<Dependents> dependents = withDependents ? Optional.of(Dependents.ADD)
                    : noDependents ? Optional.of(Dependents.LEAVE) : Optional.empty();
            return new Options(execute, force, dependents, !noWait);
        }

        private Optional<Level> level() {
            if (all == null) {
                return Optional.empty();
            }
            return Optional.of(Level.of(all).orElseThrow(() -> new ParameterException(spec.commandLine(),
                    "--all " + all + ": write " + Arrays.stream(Level.values()).map(Level::spelling)
                            .collect(Collectors.joining(", ")))));
        }
    }

    /** preview, release and why: one decide, gates, then a cut or not. */
    abstract static class Run implements Callable<Integer>, Writing {

        @Spec
        CommandSpec spec;

        @Mixin
        Umbrella umbrella;

        @Mixin
        Selection selection;

        abstract boolean execute();

        boolean why() {
            return false;
        }

        @Override
        public boolean writes() {
            return execute();
        }

        @Override
        public Integer call() {
            Requests requests = selection.requests();
            Options options = selection.options(execute());
            PrintWriter out = spec.commandLine().getOut();
            Console console = new Console(out, why());
            Engine engine = Main.of(spec).engine();
            return Main.stoppable(out, stop -> {
                Outcome outcome = engine.run(umbrella.root(), requests, options, stop::get, console);
                console.ended(outcome);
                return Main.code(outcome.status());
            });
        }
    }

    @Command(name = "preview", mixinStandardHelpOptions = true, sortOptions = false,
            description = "Say what a release would cut, and run its gates; nothing is written.")
    static final class Preview extends Run {

        @Override
        boolean execute() {
            return false;
        }
    }

    @Command(name = "release", mixinStandardHelpOptions = true, sortOptions = false,
            description = "Release: each module tagged in dependency order, its dependents' pins moved, every "
                    + "module back on its next snapshot. preview, with the same flags, says what it will do.")
    static final class Cut extends Run {

        @Override
        boolean execute() {
            return true;
        }
    }

    @Command(name = "why", mixinStandardHelpOptions = true, sortOptions = false,
            description = "A preview that says why each module is released or left, and which dependents a "
                    + "release owes but was not asked.")
    static final class Why extends Run {

        @Override
        boolean execute() {
            return false;
        }

        @Override
        boolean why() {
            return true;
        }
    }

    @Command(name = "resume", mixinStandardHelpOptions = true, sortOptions = false,
            description = "Go on with the newest run that stopped partway.")
    static final class Resume implements Callable<Integer>, Writing {

        @Spec
        private CommandSpec spec;

        @Mixin
        private Umbrella umbrella;

        @Option(names = "--force", description = "Go on past a refused gate (never past one that could not run).")
        private boolean force;

        @Option(names = "--no-wait", description = "Do not wait for a publish a later module resolves from.")
        private boolean noWait;

        @Option(names = "--dry-run", description = "Say what the resume would do; write nothing.")
        private boolean dryRun;

        @Override
        public boolean writes() {
            return !dryRun;
        }

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            Console console = new Console(out, false);
            Engine engine = Main.of(spec).engine();
            Options options = new Options(!dryRun, force, Optional.empty(), !noWait);
            return Main.stoppable(out, stop -> {
                Outcome outcome = engine.resume(umbrella.root(), options, stop::get, console);
                console.ended(outcome);
                return Main.code(outcome.status());
            });
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Print a run's state, module by module.")
    static final class Status implements Callable<Integer> {

        @Spec
        private CommandSpec spec;

        @Mixin
        private Umbrella umbrella;

        @Parameters(arity = "0..1", paramLabel = "FILE",
                description = "A run's state file (default: the newest under releases/).")
        private Path file;

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            Saved saved = Release.state(umbrella.root(), stateFile());
            out.println(saved.file());
            new Console(out, false).state(saved.state());
            return saved.state().status() == ReleaseState.Status.DONE ? Main.OK : Main.STOPPED;
        }

        /** {@code FILE} as typed, else under the umbrella; empty for the newest. */
        private Optional<Path> stateFile() {
            if (file == null) {
                return Optional.empty();
            }
            return Optional.of(Files.exists(file) || file.isAbsolute() ? file : umbrella.root().resolve(file));
        }
    }

    @Command(name = "sync-pins", mixinStandardHelpOptions = true,
            description = "Move every pin to its upstream's version on the branch, one commit per module.")
    static final class SyncPins implements Callable<Integer>, Writing {

        @Spec
        private CommandSpec spec;

        @Mixin
        private Umbrella umbrella;

        @Option(names = "--dry-run", description = "Say what would move; write nothing.")
        private boolean dryRun;

        @Override
        public boolean writes() {
            return !dryRun;
        }

        @Override
        public Integer call() {
            // Not stoppable: each module's sync is one commit, quick, and nothing resumes it.
            Engine engine = Main.of(spec).engine();
            return Main.code(engine.syncPins(umbrella.root(), !dryRun,
                    new Console(spec.commandLine().getOut(), false)));
        }
    }
}
