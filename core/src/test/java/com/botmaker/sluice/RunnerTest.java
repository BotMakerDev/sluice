package com.botmaker.sluice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerTest {

    @Test
    void dryRunEchoesAndRunsNothing(@TempDir Path tmp) throws Exception {
        List<String> lines = new ArrayList<>();
        Runner runner = new Runner(true, lines::add);
        Path file = tmp.resolve("x.txt");

        Proc.Result result = runner.run(tmp, List.of("git", "commit", "-m", "release: a v1.0.0"), Map.of());
        runner.write(file, "content");

        assertTrue(result.ok());
        assertFalse(Files.exists(file));
        assertEquals("    $ git commit -m 'release: a v1.0.0'", lines.getFirst());
    }

    @Test
    void replaceOfNothingIsRefused(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("Constants.java");
        Files.writeString(file, "VERSION = \"1.0.0\";");
        Runner runner = new Runner(false, line -> { });

        runner.replace(file, Pattern.compile("VERSION = \"([0-9.]+)\""), "VERSION = \"1.0.1\"");
        assertEquals("VERSION = \"1.0.1\";", Files.readString(file));

        assertThrows(Refusal.class, () -> runner.replace(file, Pattern.compile("GONE = \"x\""), "y"));
    }

    @Test
    void missingProgramIsNotStarted(@TempDir Path tmp) {
        Proc.Result result = Proc.run(tmp, List.of("no-such-program-sluice-test"));
        assertEquals(Proc.NOT_STARTED, result.exit());
    }
}
