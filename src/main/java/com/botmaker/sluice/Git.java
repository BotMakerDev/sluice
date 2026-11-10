package com.botmaker.sluice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only git: what a decision or a gate asks. Nothing here changes a repository; whatever does goes through
 * {@link Runner#git}, so a preview shows it and runs none of it.
 */
public final class Git {

    private Git() {
    }

    /** Runs {@code git -C <dir> <args…>}. */
    public static Proc.Result run(Path dir, String... args) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        return Proc.run(dir, argv);
    }

    /** The one line a command answered, or empty when it failed or answered nothing. */
    public static Optional<String> line(Path dir, String... args) {
        Proc.Result result = run(dir, args);
        return result.ok() ? result.lines().stream().findFirst() : Optional.empty();
    }

    /** The checked-out branch, or empty on a detached {@code HEAD}. */
    public static Optional<String> branch(Path dir) {
        return line(dir, "symbolic-ref", "--short", "-q", "HEAD");
    }

    /** Whether the working tree has anything uncommitted, untracked files included. */
    public static boolean dirty(Path dir) {
        Proc.Result status = run(dir, "status", "--porcelain");
        return !status.ok() || !status.out().isBlank();
    }
}
