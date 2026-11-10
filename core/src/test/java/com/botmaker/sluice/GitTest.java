package com.botmaker.sluice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitTest {

    @TempDir
    Path tmp;

    @Test
    void relativeDirectoryIsReadOnce() {
        assertTrue(Proc.run(tmp, List.of("git", "init", "-q", "-b", "main")).ok());
        Path here = Path.of("").toAbsolutePath();
        // On Windows the checkout and the temporary directory can be on different drives: no relative path joins them.
        assumeTrue(here.getRoot().equals(tmp.toAbsolutePath().getRoot()), "the temporary directory is on another root");
        Path relative = here.relativize(tmp.toAbsolutePath());

        assertEquals(Optional.of("main"), Git.line(relative, "symbolic-ref", "--short", "-q", "HEAD"),
                "git runs in the directory already; its -C must not be read from inside it");
    }
}
