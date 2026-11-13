package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Refusal;

import java.nio.file.Path;

/** The one commit the window makes: a file it saved, alone. */
public final class GitCommit {

    private GitCommit() {
    }

    /**
     * Commits {@code file} alone in the repository at {@code dir}, with {@code message}; nothing else staged goes
     * with it.
     *
     * @throws Refusal when git refuses, with its words
     */
    public static void alone(Path dir, String file, String message) {
        must(Git.run(dir, "add", "--", file), "git add");
        must(Git.run(dir, "commit", "-q", "-m", message, "--", file), "git commit");
    }

    private static void must(Proc.Result result, String what) {
        if (!result.ok()) {
            throw new Refusal(what + " failed: " + result.out().strip());
        }
    }
}
