// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.cli;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.update.SelfUpdate;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code sluice} command: a verb, then its flags. Exit codes: 0 done or previewed clean, 1 refused, 2 stopped
 * partway (resumable), 3 usage.
 */
@Command(name = "sluice", mixinStandardHelpOptions = true, versionProvider = Main.ManifestVersion.class,
        subcommands = {Commands.Preview.class, Commands.Cut.class, Commands.Why.class, Commands.Resume.class,
            Commands.Status.class, Commands.SyncPins.class, GraphCommand.class, UpdateCommand.class,
            CompletionCommand.class},
        description = "Release a Maven project split over the git submodules of one umbrella: each module tagged in "
                + "dependency order, its dependents' pins moved, every module back on its next snapshot.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {"0:done, or previewed clean", "1:refused", "2:stopped partway (sluice resume goes on)",
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

    Main(Engine engine) {
        this.engine = engine;
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        SelfUpdate.finishPending(Main.class);
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
            return failed.getCommand() instanceof Commands.Writing writing && writing.writes() ? STOPPED : REFUSED;
        });
        int code = line.execute(Aliases.expand(Arrays.asList(args), options(line, args)).toArray(String[]::new));
        out.flush();
        err.flush();
        return code;
    }

    /** The option names sluice has itself, before the verb and on the verb named: an alias never shadows one. */
    private static Set<String> options(CommandLine line, String[] args) {
        Stream<CommandLine> verb = Arrays.stream(args).filter(line.getSubcommands()::containsKey).limit(1)
                .map(line.getSubcommands()::get);
        return Stream.concat(Stream.of(line), verb)
                .flatMap(command -> command.getCommandSpec().options().stream())
                .map(OptionSpec::names).flatMap(Arrays::stream).collect(Collectors.toSet());
    }

    private static int usage(ParameterException e, PrintWriter err) {
        err.println(e.getMessage());
        e.getCommandLine().usage(err);
        err.flush();
        return USAGE;
    }

    /** The {@code Main} a verb runs under. */
    static Main of(CommandSpec verb) {
        return (Main) verb.root().userObject();
    }

    Engine engine() {
        return engine;
    }

    /** The umbrella {@code --umbrella} before the verb names, as an absolute path. */
    Path umbrella() {
        return umbrella.toAbsolutePath().normalize();
    }

    @Override
    public Integer call() {
        throw new ParameterException(spec.commandLine(),
                "say what to do: preview, release, why, resume, status, sync-pins, graph or update");
    }

    /**
     * Runs {@code work}, which answers the exit code, with Ctrl-C asking it to stop between steps: the hook waits a
     * while for it to say so, so the run ends resumable rather than killed mid-step, then ends the program with
     * the run's own code (2 when it stopped), not the signal's.
     */
    static int stoppable(PrintWriter out, Function<AtomicBoolean, Integer> work) {
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

    static int code(Release.Status status) {
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
