package com.botmaker.sluice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every side effect of a release, echoed as the command a person would type. A dry run echoes and runs nothing,
 * which is what makes the preview the default: what it prints is what {@code --execute} would do.
 *
 * <p>{@code stop} is asked between commands, never during one: a {@code git push} cut in half is worse than one
 * more tag.
 */
public record Runner(boolean dryRun, Consumer<String> out, BooleanSupplier stop) {

    /** A runner nobody can stop but Ctrl-C. */
    public Runner(boolean dryRun, Consumer<String> out) {
        this(dryRun, out, () -> false);
    }

    /** Whether the operator has asked the release to stop. */
    public boolean stopping() {
        return stop.getAsBoolean();
    }

    /** Runs {@code git -C <dir> <args…>}, echoed; {@code dir} absolute, since git already runs in it. */
    public Proc.Result git(Path dir, String... args) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toAbsolutePath().normalize().toString()));
        argv.addAll(List.of(args));
        return run(dir, argv, Map.of());
    }

    /** Runs a command in {@code dir}, echoed. A dry run answers a successful empty result: nothing ran. */
    public Proc.Result run(Path dir, List<String> argv, Map<String, String> env) {
        out.accept("    $ " + argv.stream().map(Runner::quoted).collect(Collectors.joining(" ")));
        return dryRun ? new Proc.Result(0, "") : Proc.run(dir, argv, env);
    }

    /**
     * One argument as a shell would need it written. The echo is read as a script by whoever checks a preview, so
     * {@code commit -m release: a v1.0.0} must still read as one message.
     */
    static String quoted(String argument) {
        return argument.matches("[A-Za-z0-9_@%+=:,./\\\\-]+")
                ? argument
                : "'" + argument.replace("'", "'\\''") + "'";
    }

    /** Writes a whole file, echoed by name: what is interesting about it is said by the line above. */
    public void write(Path file, String content) {
        out.accept("    $ write " + quoted(file.toString()));
        if (dryRun) {
            return;
        }
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be written (" + e.getMessage() + ")");
        }
    }

    /**
     * Rewrites the first match of {@code find} in a file somebody else owns, echoed with the pattern.
     *
     * <p><b>A pattern that matches nothing is a refusal, not a no-op</b>: a release that believes it moved a pin
     * it did not move is the failure worth being loud about.
     *
     * @param replacement a {@link Matcher#replaceFirst} replacement, so {@code $1} names a group
     */
    public void replace(Path file, Pattern find, String replacement) {
        out.accept("    $ replace " + quoted(find.pattern()) + " with " + quoted(replacement) + " in "
                + quoted(file.toString()));
        if (dryRun) {
            return;
        }
        String before = read(file);
        Matcher match = find.matcher(before);
        if (!match.find()) {
            throw new Refusal(file + ": nothing matches " + find.pattern());
        }
        store(file, match.reset().replaceFirst(replacement));
    }

    /**
     * Rewrites the first group of {@code find}'s first match in a file, and nothing around it: a text pin, whose
     * pattern finds the version by its context. A pattern that matches nothing refuses, as {@link #replace} does.
     */
    public void replaceGroup(Path file, Pattern find, String value) {
        out.accept("    $ replace group 1 of " + quoted(find.pattern()) + " with " + quoted(value) + " in "
                + quoted(file.toString()));
        if (dryRun) {
            return;
        }
        String before = read(file);
        Matcher match = find.matcher(before);
        if (!match.find() || match.groupCount() < 1 || match.start(1) < 0) {
            throw new Refusal(file + ": nothing matches " + find.pattern());
        }
        store(file, before.substring(0, match.start(1)) + value + before.substring(match.end(1)));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
    }

    private static void store(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be written (" + e.getMessage() + ")");
        }
    }

    /** One line of narration. */
    public void say(String line) {
        out.accept(line);
    }
}
