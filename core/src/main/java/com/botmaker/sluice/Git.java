package com.botmaker.sluice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only git: what a decision or a gate asks. Nothing here changes a working tree, a branch or a remote;
 * whatever does goes through {@link Runner#git}, so a preview shows it and runs none of it. The one thing a
 * preview does to a repository is fetch its remote's tags and release branch, without which it would decide from
 * stale ones.
 */
public final class Git {

    private Git() {
    }

    /**
     * Runs {@code git -C <dir> <args…>}. The {@code -C} is absolute: git runs in {@code dir} already, so a relative
     * one would be read from inside it.
     */
    public static Proc.Result run(Path dir, String... args) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toAbsolutePath().normalize().toString()));
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

    /**
     * Whether a tracked file has changes not committed, staged or not. Untracked files are not counted: a release
     * commits only what it changed, so they can reach no commit of its. A {@code status} that fails counts as
     * changed.
     */
    public static boolean changed(Path dir) {
        Proc.Result status = run(dir, "status", "--porcelain", "--untracked-files=no");
        return !status.ok() || !status.out().isBlank();
    }

    /**
     * The files under {@code dir} git does not track and does not ignore, relative to it, {@code /}-separated: what
     * a {@code git add} run there names them by.
     */
    public static Set<String> untracked(Path dir) {
        Proc.Result files = run(dir, "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard");
        return files.ok() ? new LinkedHashSet<>(files.lines()) : Set.of();
    }

    /** Whether the index holds anything a commit would record. */
    public static boolean staged(Path dir) {
        return !run(dir, "diff", "--cached", "--quiet").ok();
    }
}
