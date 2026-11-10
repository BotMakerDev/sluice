package com.botmaker.sluice.execute;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.execute.Release.Status;
import com.botmaker.sluice.execute.ReleaseFixture.Flaky;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.execute.ReleaseFixture.EXECUTE;
import static com.botmaker.sluice.execute.ReleaseFixture.chain;
import static com.botmaker.sluice.execute.ReleaseFixture.config;
import static com.botmaker.sluice.execute.ReleaseFixture.lines;
import static com.botmaker.sluice.execute.ReleaseFixture.read;
import static com.botmaker.sluice.execute.ReleaseFixture.resume;
import static com.botmaker.sluice.execute.ReleaseFixture.run;
import static com.botmaker.sluice.execute.ReleaseFixture.show;
import static com.botmaker.sluice.execute.ReleaseFixture.tags;
import static com.botmaker.sluice.testing.GateFixture.probe;
import static com.botmaker.sluice.testing.GateFixture.yamlList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeTest {

    @TempDir
    Path tmp;

    @Test
    void stopsAfterFailedPublishThenResumesRest() {
        FixtureUmbrella umbrella = chain(tmp);
        Flaky target = new Flaky();
        target.failing.add("b:v0.1.1");
        List<String> lines = lines();

        Outcome stopped = run(umbrella, EXECUTE, target, lines, "a", "b", "c");

        assertEquals(Status.STOPPED, stopped.status(), String.join("\n", lines));
        List<ReleaseState.Entry> entries = StateFile.read(stopped.state().orElseThrow()).modules();
        assertEquals(List.of(Stage.SNAPSHOT, Stage.FAILED, Stage.DECIDED),
                entries.stream().map(ReleaseState.Entry::stage).toList());
        assertEquals("its build failed", entries.get(1).detail());
        assertFalse(tags(umbrella, "c").contains("v0.2.1"));

        target.failing.clear();
        List<String> resumed = lines();
        Outcome done = resume(umbrella, target, resumed);

        assertEquals(Status.DONE, done.status(), String.join("\n", resumed));
        assertTrue(done.state().orElseThrow().getFileName().toString().equals("2026-11-10-1200-2.json"),
                done.state().toString());
        assertTrue(tags(umbrella, "b").containsAll(List.of("v0.1.1", "v0.1.2")), tags(umbrella, "b").toString());
        assertEquals(List.of("v1.0.0", "v1.0.1"), tags(umbrella, "a"), "a is not released again");
        String cReleased = show(umbrella, "c", "v0.2.1", "pom.xml");
        assertTrue(cReleased.contains("<b.version>v0.1.2</b.version>"), cReleased);
        assertTrue(show(umbrella, "b", "v0.1.2", "pom.xml").contains("<a.version>v1.0.1</a.version>"));
        String bMain = read(umbrella.dir("b").resolve("pom.xml"));
        assertTrue(bMain.contains("<version>0.1.3-SNAPSHOT</version>")
                && bMain.contains("<a.version>1.0.2-SNAPSHOT</a.version>"), bMain);
        assertTrue(read(umbrella.dir("c").resolve("pom.xml")).contains("<b.version>0.1.3-SNAPSHOT</b.version>"));
    }

    @Test
    void resumesTheStepItStoppedInDespiteItsDirtyTree() {
        FixtureUmbrella umbrella = chain(tmp);
        Path records = tmp.resolve("records");
        records.toFile().mkdirs();
        config(umbrella, "steps:\n  - {at: before-release-commit, modules: [b], run: "
                + yamlList(probe(records.resolve("b"), 3)) + "}\n");
        List<String> lines = lines();

        Outcome stopped = run(umbrella, EXECUTE, new Flaky(), lines, "a", "b", "c");

        assertEquals(Status.STOPPED, stopped.status(), String.join("\n", lines));
        assertTrue(read(umbrella.dir("b").resolve("pom.xml")).contains("<version>0.1.1</version>"),
                "the stop leaves the moved pom uncommitted");
        config(umbrella, "steps:\n  - {at: before-release-commit, modules: [b], run: "
                + yamlList(probe(records.resolve("b"), 0)) + "}\n");
        List<String> resumed = lines();

        assertEquals(Status.DONE, resume(umbrella, new Flaky(), resumed).status(), String.join("\n", resumed));
        assertTrue(tags(umbrella, "b").contains("v0.1.1") && tags(umbrella, "c").contains("v0.2.1"));
        assertEquals("release: a v1.0.1, b v0.1.1, c v0.2.1", FixtureUmbrella.git(umbrella.root(), "log", "-1",
                "--format=%s").out().strip());
    }

    @Test
    void stateDetailWithControlCharactersReadsBack() {
        Path file = tmp.resolve("s.json");
        ReleaseState state = new ReleaseState(ReleaseState.Status.STOPPED, List.of(new ReleaseState.Entry("a",
                Version.parse("1.0.0").orElseThrow(), Stage.FAILED, "bad \u0085 \u009b \u0001 output")));

        StateFile.write(file, state);

        assertEquals(state, StateFile.read(file));
    }

    @Test
    void finishedRunHasNothingToResume() {
        FixtureUmbrella umbrella = chain(tmp);
        List<String> lines = lines();
        assertEquals(Status.DONE, run(umbrella, EXECUTE, new Flaky(), lines, "a", "b", "c").status(),
                String.join("\n", lines));

        List<String> resumed = lines();
        assertEquals(Status.DONE, resume(umbrella, new Flaky(), resumed).status());
        assertTrue(resumed.getFirst().startsWith("nothing to resume"), resumed.toString());
    }
}
