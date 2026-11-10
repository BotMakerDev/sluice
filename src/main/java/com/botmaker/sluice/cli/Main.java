package com.botmaker.sluice.cli;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.Release.Options;
import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.execute.Release.Saved;
import com.botmaker.sluice.execute.ReleaseState;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The {@code sluice} command: a release, its preview, a resume, a pin sync, a state, or the graph. Exit codes: 0
 * done or previewed clean, 1 refused, 2 stopped partway (resumable), 3 usage.
 */
@Command(name = "sluice", mixinStandardHelpOptions = true, versionProvider = Main.ManifestVersion.class,
        subcommands = GraphCommand.class, sortOptions = false,
        description = {
            "Release a Maven project split over the git submodules of one umbrella: each module tagged in "
                    + "dependency order, its dependents' pins moved, every module back on its next snapshot.",
            "Previews unless --execute is given. A module's alias in sluice.yml is a flag too: --sdk 1.10.0."},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:done, or previewed clean", "1:refused", "2:stopped partway (--resume goes on)",
            "3:usage"})
public final class Main implements Callable<Integer> {

    static final int OK = 0;
    static final int REFUSED = 1;
    static final int STOPPED = 2;
    static final int USAGE = 3;

    /** How long Ctrl-C waits for the step under way to finish, so the state file says where the run stopped. */
    private static final long STOP_GRACE_SECONDS = 120;

    private final Engine engine;

    @Spec
    private CommandSpec spec;

    @Option(names = "--umbrella", paramLabel = "DIR", description = "The umbrella repository (default: here).")
    private Path umbrella = Path.of("");

    @Option(names = "--all", paramLabel = "LEVEL", description = "Release every changed module: patch, minor, major.")
    private String all;

    @Option(names = "--module", paramLabel = "NAME[=VERSION|LEVEL]",
            description = "Release one module, by its path, alias or artifactId. Repeatable.")
    private List<String> modules = new ArrayList<>();

    @Option(names = "--with-dependents", description = "Also release, at a patch, every dependent a release owes.")
    private boolean withDependents;

    @Option(names = "--force", description = "Go on past a refused gate (never past one that could not run).")
    private boolean force;

    @Option(names = "--execute", description = "Release, rather than preview.")
    private boolean execute;

    @Option(names = "--no-wait", description = "Do not wait for a publish a later module resolves from.")
    private boolean noWait;

    @Option(names = "--why", description = "Say why each module is released or left.")
    private boolean why;

    @Option(names = "--resume", description = "Go on with the newest run that stopped partway.")
    private boolean resume;

    @Option(names = "--sync-pins", description = "Move every pin to its upstream's version on the branch.")
    private boolean syncPins;

    @Option(names = "--status", arity = "0..1", paramLabel = "FILE", fallbackValue = "",
            description = "Print a run's state: FILE, or the newest under releases/.")
    private String status;

    Main(Engine engine) {
        this.engine = engine;
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        System.exit(run(args, out, err, Engine.REAL));
    }

    /** Runs the command line {@code args} against {@code engine}; answers the exit code. */
    static int run(String[] args, PrintWriter out, PrintWriter err, Engine engine) {
        CommandLine line = new CommandLine(new Main(engine));
        line.setOut(out);
        line.setErr(err);
        line.setParameterExceptionHandler((e, ignored) -> usage(e, err));
        line.setExecutionExceptionHandler((e, failed, parsed) -> {
            if (e instanceof Refusal refusal) {
                err.println("refused: " + refusal.getMessage());
                err.flush();
                return REFUSED;
            }
            if (e instanceof ParameterException parameter) {
                return usage(parameter, err);
            }
            // A bug, or the system failing under it: said, and never read as "refused" once a release ran,
            // since it may have stopped partway and the state file says where.
            err.println("sluice failed: " + e);
            e.printStackTrace(err);
            err.flush();
            return failed.getCommand() instanceof Main main && main.execute ? STOPPED : REFUSED;
        });
        Set<String> own = line.getCommandSpec().optionsMap().keySet();
        int code = line.execute(Aliases.expand(Arrays.asList(args), own).toArray(String[]::new));
        out.flush();
        err.flush();
        return code;
    }

    private static int usage(ParameterException e, PrintWriter err) {
        err.println(e.getMessage());
        e.getCommandLine().usage(err);
        err.flush();
        return USAGE;
    }

    Path umbrella() {
        return umbrella;
    }

    @Override
    public Integer call() {
        boolean releasing = all != null || !modules.isEmpty();
        long modes = List.of(releasing, resume, syncPins, status != null).stream().filter(b -> b).count();
        if (modes != 1) {
            throw new ParameterException(spec.commandLine(), modes == 0
                    ? "say what to do: --all LEVEL, --module NAME, --resume, --sync-pins, --status or graph"
                    : "--all/--module, --resume, --sync-pins and --status go one at a time");
        }
        String mode = status != null ? "--status" : syncPins ? "--sync-pins" : resume ? "--resume" : "";
        unused(mode, "--execute", execute && status != null);
        unused(mode, "--force", force && (status != null || syncPins));
        unused(mode, "--with-dependents", withDependents && !mode.isEmpty());
        unused(mode, "--no-wait", noWait && (status != null || syncPins));
        unused(mode, "--why", why && (status != null || syncPins));
        PrintWriter out = spec.commandLine().getOut();
        Console console = new Console(out, why);
        Options options = new Options(execute, force, withDependents, !noWait);
        if (status != null) {
            Saved saved = Release.state(umbrella, stateFile());
            out.println(saved.file());
            console.state(saved.state());
            return saved.state().status() == ReleaseState.Status.DONE ? OK : STOPPED;
        }
        if (syncPins) {
            // Not stoppable: each module's sync is one commit, quick, and nothing resumes it.
            return code(engine.syncPins(umbrella, execute, console));
        }
        return stoppable(out, stop -> {
            Outcome outcome = resume
                    ? engine.resume(umbrella, options, stop::get, console)
                    : engine.run(umbrella, Requests.parse(modules, level()), options, stop::get, console);
            console.ended(outcome);
            return code(outcome.status());
        });
    }

    /** A flag the chosen mode has no use for is a usage error, never silently dropped. */
    private void unused(String mode, String flag, boolean given) {
        if (given) {
            throw new ParameterException(spec.commandLine(), flag + " does not apply to " + mode);
        }
    }

    /** {@code --status FILE} as typed, else under the umbrella; empty for the newest. */
    private Optional<Path> stateFile() {
        if (status.isEmpty()) {
            return Optional.empty();
        }
        Path typed = Path.of(status);
        return Optional.of(Files.exists(typed) || typed.isAbsolute() ? typed : umbrella.resolve(typed));
    }

    private Optional<Level> level() {
        if (all == null) {
            return Optional.empty();
        }
        return Optional.of(Level.of(all).orElseThrow(() -> new ParameterException(spec.commandLine(),
                "--all " + all + ": write " + Arrays.stream(Level.values()).map(Level::spelling)
                        .collect(Collectors.joining(", ")))));
    }

    /**
     * Runs {@code work}, which answers the exit code, with Ctrl-C asking it to stop between steps: the hook waits a
     * while for it to say so, so the run ends resumable rather than killed mid-step, then ends the program with
     * the run's own code (2 when it stopped), not the signal's.
     */
    private static int stoppable(PrintWriter out, Function<AtomicBoolean, Integer> work) {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger code = new AtomicInteger(STOPPED);
        CountDownLatch finished = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            stop.set(true);
            try {
                finished.await(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.flush();
            Runtime.getRuntime().halt(code.get());
        }, "sluice-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            int ended = work.apply(stop);
            code.set(ended);
            return ended;
        } finally {
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // Ctrl-C came: the hook is running and now returns.
            }
        }
    }

    private static int code(Release.Status status) {
        return switch (status) {
            case DONE, PREVIEWED -> OK;
            case REFUSED -> REFUSED;
            case STOPPED -> STOPPED;
        };
    }

    /** {@code --version}: the jar's manifest, written by the build. */
    static final class ManifestVersion implements IVersionProvider {

        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[]{"sluice " + (version == null ? "(dev)" : version)};
        }
    }
}
