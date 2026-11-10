package com.botmaker.sluice.execute;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.execute.Release.Status;
import com.botmaker.sluice.execute.ReleaseLogs.LoggedRelease;
import com.botmaker.sluice.execute.ReleaseState.Entry;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.execute.ReleaseFixture.EXECUTE;
import static com.botmaker.sluice.execute.ReleaseFixture.lines;
import static com.botmaker.sluice.execute.ReleaseFixture.pinned;
import static com.botmaker.sluice.execute.ReleaseFixture.run;
import static com.botmaker.sluice.execute.ReleaseFixture.served;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseLogsTest {

    @TempDir
    Path tmp;

    @Test
    void noReleasesIsNothing() {
        assertEquals(List.of(), ReleaseLogs.list(tmp));
    }

    @Test
    void listsEachRunOnceTheNewestFirst() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(StateFile.DIR));
        ReleaseState done = new ReleaseState(ReleaseState.Status.DONE,
                List.of(new Entry("a", Version.parse("1.0.1").orElseThrow(), Stage.SNAPSHOT, "")));
        StateFile.write(dir.resolve("2026-11-10-1200.json"), done);
        Files.writeString(dir.resolve("2026-11-10-1200.md"), "# Release a 1.0.1\n\nStatus: DONE\n");
        Files.writeString(dir.resolve("2026-11-10-1200-2.md"), "# Release b 0.1.1\n\nStatus: REFUSED\n");
        Files.writeString(dir.resolve("2026-11-09-0900.json"), "not json at all: [");
        Files.writeString(dir.resolve("notes.md"), "a person's own notes\n");

        List<LoggedRelease> runs = ReleaseLogs.list(tmp);

        assertEquals(List.of("2026-11-10-1200-2", "2026-11-10-1200", "2026-11-09-0900"),
                runs.stream().map(LoggedRelease::name).toList());
        assertEquals(Optional.of(Status.REFUSED), runs.get(0).status());
        assertTrue(runs.get(0).stateFile().isEmpty() && runs.get(0).state().isEmpty());
        assertEquals(Optional.of(done), runs.get(1).state());
        assertEquals(Optional.of(Status.DONE), runs.get(1).status());
        assertTrue(runs.get(2).state().isEmpty() && runs.get(2).unreadable().isPresent(), runs.get(2).toString());
    }

    @Test
    void readsARunByEitherFile() {
        FixtureUmbrella umbrella = pinned(tmp);
        Release.Outcome outcome = run(umbrella, EXECUTE, served(), lines(), "a", "b");

        LoggedRelease byLog = ReleaseLogs.read(outcome.log().orElseThrow());
        LoggedRelease byState = ReleaseLogs.read(outcome.state().orElseThrow());

        assertEquals(byLog, byState);
        assertEquals(Optional.of(Status.DONE), byLog.status());
        assertEquals(List.of("a", "b"), byLog.state().orElseThrow().modules().stream().map(Entry::module).toList());
        assertEquals(List.of(byLog), ReleaseLogs.list(umbrella.root()));
    }

    @Test
    void anotherFileIsRefused() {
        assertThrows(Refusal.class, () -> ReleaseLogs.read(tmp.resolve("notes.md")));
    }
}
