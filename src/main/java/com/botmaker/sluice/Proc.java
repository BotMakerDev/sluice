package com.botmaker.sluice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** One external command, run to its end, its stdout and stderr together. */
public final class Proc {

    /** What a command answered. */
    public record Result(int exit, String out) {

        public boolean ok() {
            return exit == 0;
        }

        /** The output's non-blank lines, stripped. */
        public List<String> lines() {
            return out.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        }
    }

    /** The exit code of a command that was not started, or was stopped, because its thread was interrupted. */
    public static final int INTERRUPTED = 130;

    /** The exit code of a command that could not be started: no such program, or no such directory. */
    public static final int NOT_STARTED = 127;

    private Proc() {
    }

    /** Runs {@code argv} in {@code dir}, with the caller's environment. */
    public static Result run(Path dir, List<String> argv) {
        return run(dir, argv, Map.of());
    }

    /**
     * Runs {@code argv} in {@code dir} with {@code env} added to the caller's environment.
     *
     * <p><b>An interrupt stops it</b>: the process and what it started are destroyed and the answer is
     * {@link #INTERRUPTED}, and a thread already interrupted starts nothing. The output is read on a thread of its
     * own so the wait itself can be interrupted, which is what lets a UI's Cancel stop a long Maven run.
     */
    public static Result run(Path dir, List<String> argv, Map<String, String> env) {
        if (Thread.currentThread().isInterrupted()) {
            return new Result(INTERRUPTED, "interrupted");
        }
        Process process = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ProcessBuilder builder = new ProcessBuilder(resolved(argv)).directory(dir.toFile()).redirectErrorStream(true);
            builder.environment().putAll(env);
            process = builder.start();
            InputStream stdout = process.getInputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (stdout) {
                    stdout.transferTo(out);
                } catch (IOException ignored) {
                    // Destroyed under the read: what was read is the output.
                }
            });
            int exit = process.waitFor();
            reader.join();
            return new Result(exit, out.toString(Charset.defaultCharset()));
        } catch (IOException e) {
            return new Result(NOT_STARTED, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return new Result(INTERRUPTED, "interrupted");
        }
    }

    /**
     * {@code argv} with its program found on {@code PATH} under Windows' extensions. {@code ProcessBuilder} finds
     * {@code git.exe} by itself but not {@code mvn.cmd}, which is how Maven installs on Windows.
     */
    private static List<String> resolved(List<String> argv) {
        if (argv.isEmpty() || !isWindows()) {
            return argv;
        }
        String program = argv.getFirst().contains(File.separator)
                ? argv.getFirst() : find(argv.getFirst()).map(Path::toString).orElse(argv.getFirst());
        String lower = program.toLowerCase(Locale.ROOT);
        boolean batch = lower.endsWith(".cmd") || lower.endsWith(".bat");
        return Stream.concat(Stream.of(program), argv.stream().skip(1).map(arg -> batch ? arg : windowsArg(arg)))
                .toList();
    }

    /**
     * One argument as a Windows program's C runtime splits its command line back into it. {@code ProcessBuilder}
     * wraps an argument holding a space in quotes but leaves the quotes inside it as they are, so {@code 'q' "d"}
     * would arrive as {@code 'q' d}. An argument holding a quote is written here already quoted, its quotes and the
     * backslashes before them escaped, which {@code ProcessBuilder} passes as it is. A {@code .cmd} or {@code .bat}
     * is read by {@code cmd.exe} under rules of its own, so its arguments are left alone.
     */
    static String windowsArg(String arg) {
        if (arg.indexOf('"') < 0) {
            return arg;
        }
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (char c : arg.toCharArray()) {
            if (c == '\\') {
                backslashes++;
                continue;
            }
            quoted.append("\\".repeat(c == '"' ? backslashes * 2 + 1 : backslashes)).append(c);
            backslashes = 0;
        }
        return quoted.append("\\".repeat(backslashes * 2)).append('"').toString();
    }

    /** Whether a program is on {@code PATH}, read from {@code PATH} itself, never by asking a shell. */
    public static boolean onPath(String command) {
        return find(command).isPresent();
    }

    private static Optional<Path> find(String command) {
        String path = System.getenv("PATH");
        if (path == null || command.isBlank() || command.contains(File.separator)) {
            return Optional.empty();
        }
        List<String> suffixes = isWindows() ? List.of("", ".exe", ".cmd", ".bat") : List.of("");
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            for (String suffix : suffixes) {
                Path candidate = Path.of(entry).resolve(command + suffix);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** Whether this is Windows, where a command's program and a shell are spelled differently. */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
