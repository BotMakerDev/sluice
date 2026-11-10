package com.botmaker.sluice.execute;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.execute.Release.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.botmaker.sluice.execute.ReleaseFixture.EXECUTE;
import static com.botmaker.sluice.execute.ReleaseFixture.lines;
import static com.botmaker.sluice.execute.ReleaseFixture.pinned;
import static com.botmaker.sluice.execute.ReleaseFixture.run;
import static com.botmaker.sluice.execute.ReleaseFixture.served;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockTest {

    @TempDir
    Path tmp;

    private Path lockFile(FixtureUmbrella umbrella) {
        return umbrella.root().resolve(".git").resolve(Lock.FILE);
    }

    @Test
    void secondRunRefused() {
        FixtureUmbrella umbrella = pinned(tmp);
        try (Lock held = Lock.take(umbrella.root(), line -> { })) {
            Refusal refusal = assertThrows(Refusal.class, () -> run(umbrella, EXECUTE, served(), lines(), "a"));
            assertTrue(refusal.getMessage().contains("another release holds the lock: pid "
                    + ProcessHandle.current().pid()), refusal.getMessage());
        }
        assertEquals(List.of("v1.0.0"), ReleaseFixture.tags(umbrella, "a"));
    }

    @Test
    void deadHolderIsTakenOver() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version").redirectErrorStream(true).start();
        gone.getInputStream().readAllBytes();
        gone.waitFor();
        Files.writeString(lockFile(umbrella), "pid=" + gone.pid() + "\nhost=" + Lock.host() + "\nsince=then\n");
        List<String> said = new ArrayList<>();

        try (Lock taken = Lock.take(umbrella.root(), said::add)) {
            assertTrue(Files.readString(lockFile(umbrella)).contains("pid=" + ProcessHandle.current().pid()));
        }
        assertEquals(List.of("taking over the lock of pid " + gone.pid() + " on " + Lock.host()
                + " since then, which no longer runs"), said);
        assertFalse(Files.exists(lockFile(umbrella)));
    }

    @Test
    void holderOnAnotherHostRefused() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Files.writeString(lockFile(umbrella), "pid=1\nhost=elsewhere.invalid\nsince=then\n");

        Refusal refusal = assertThrows(Refusal.class, () -> Lock.take(umbrella.root(), line -> { }));

        assertTrue(refusal.getMessage().contains("on elsewhere.invalid"), refusal.getMessage());
        assertTrue(Files.exists(lockFile(umbrella)));
    }

    @Test
    void lockReleasedOnRefusal() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Files.writeString(umbrella.dir("a").resolve("dirty.txt"), "not committed\n");

        assertEquals(Status.REFUSED, run(umbrella, EXECUTE, served(), lines(), "a").status());

        assertFalse(Files.exists(lockFile(umbrella)));
    }
}
