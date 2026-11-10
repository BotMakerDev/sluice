package com.botmaker.sluice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only git: what a decision or a gate asks. Nothing here changes a working tree, a branch or a remote;
 * whatever does goes through {@link Runner#git}, so a preview shows it and runs none of it. The one thing a
 * preview does to a repository is fetch its remote's tags and release branch, without which it would decide from
 * stale ones.
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

    /** The file at {@code path} (from the repository's root, {@code /}-separated) as {@code ref} has it. */
    public static Optional<String> show(Path dir, String ref, String path) {
        Proc.Result result = run(dir, "show", ref + ":" + path);
        return result.ok() ? Optional.of(result.out()) : Optional.empty();
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
