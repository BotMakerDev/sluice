package com.botmaker.sluice.execute;

import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.execute.Release.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.execute.ReleaseFixture.EXECUTE;
import static com.botmaker.sluice.execute.ReleaseFixture.PREVIEW;
import static com.botmaker.sluice.execute.ReleaseFixture.config;
import static com.botmaker.sluice.execute.ReleaseFixture.lines;
import static com.botmaker.sluice.execute.ReleaseFixture.pinned;
import static com.botmaker.sluice.execute.ReleaseFixture.read;
import static com.botmaker.sluice.execute.ReleaseFixture.run;
import static com.botmaker.sluice.execute.ReleaseFixture.served;
import static com.botmaker.sluice.execute.ReleaseFixture.show;
import static com.botmaker.sluice.execute.ReleaseFixture.tags;
import static com.botmaker.sluice.testing.GateFixture.probe;
import static com.botmaker.sluice.testing.GateFixture.yamlList;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseTest {

    @TempDir
    Path tmp;

    @Test
    void previewWritesNothing() {
        FixtureUmbrella umbrella = pinned(tmp);
        String pomBefore = read(umbrella.dir("b").resolve("pom.xml"));
        List<String> lines = lines();

        Outcome outcome = run(umbrella, PREVIEW, served(), lines, "a", "b");

        assertEquals(Status.PREVIEWED, outcome.status(), String.join("\n", lines));
        assertTrue(outcome.log().isEmpty() && outcome.state().isEmpty());
        assertFalse(Files.exists(umbrella.root().resolve(StateFile.DIR)));
        assertEquals(List.of("v1.0.0"), tags(umbrella, "a"));
        assertEquals(pomBefore, read(umbrella.dir("b").resolve("pom.xml")));
        String said = String.join("\n", lines);
        assertTrue(said.contains("versions-maven-plugin:2.18.0:set -DnewVersion=1.0.1"), said);
        assertTrue(said.contains("tag -a v0.1.1"), said);
        assertTrue(said.contains("would wait for fixture to serve a v1.0.1"), said);
    }

    @Test
    void executeTagsInOrderMovesPinsAndSnapshots() {
        FixtureUmbrella umbrella = pinned(tmp);
        List<String> lines = lines();
        String umbrellaHead = FixtureUmbrella.git(umbrella.root(), "rev-parse", "HEAD").out().strip();

        Outcome outcome = run(umbrella, EXECUTE, served(), lines, "a", "b");

        assertEquals(Status.DONE, outcome.status(), String.join("\n", lines));
        assertTrue(tags(umbrella, "a").contains("v1.0.1"));
        assertTrue(tags(umbrella, "b").contains("v0.1.1"));
        assertTrue(show(umbrella, "a", "v1.0.1", "pom.xml").contains("<version>1.0.1</version>"));
        String bReleased = show(umbrella, "b", "v0.1.1", "pom.xml");
        assertTrue(bReleased.contains("<version>0.1.1</version>"), bReleased);
        assertTrue(bReleased.contains("<a.version>v1.0.1</a.version>"), bReleased);
        assertTrue(read(umbrella.dir("a").resolve("pom.xml")).contains("<version>1.0.2-SNAPSHOT</version>"));
        String bMain = read(umbrella.dir("b").resolve("pom.xml"));
        assertTrue(bMain.contains("<version>0.1.2-SNAPSHOT</version>"), bMain);
        assertTrue(bMain.contains("<a.version>1.0.2-SNAPSHOT</a.version>"), bMain);
        // Each branch is pushed with its tag.
        assertTrue(FixtureUmbrella.git(umbrella.dir("b"), "ls-remote", "--tags", "origin").out().contains("v0.1.1"));
        assertEquals("", FixtureUmbrella.git(umbrella.dir("b"), "rev-list", "origin/main..main").out().strip());
        // One umbrella commit, holding the pointers, the log and the state.
        List<String> commits = FixtureUmbrella.git(umbrella.root(), "log", "--format=%s", umbrellaHead + "..HEAD")
                .lines();
        assertEquals(List.of("release: a v1.0.1, b v0.1.1"), commits);
        String files = FixtureUmbrella.git(umbrella.root(), "show", "--name-only", "--format=", "HEAD").out();
        assertTrue(files.contains("a\n") && files.contains("b\n") && files.contains("releases/2026-11-10-1200.md")
                && files.contains("releases/2026-11-10-1200.json"), files);
        ReleaseState state = StateFile.read(outcome.state().orElseThrow());
        assertEquals(ReleaseState.Status.DONE, state.status());
        assertTrue(state.modules().stream().allMatch(e -> e.stage() == Stage.SNAPSHOT));
    }

    @Test
    void textPinMovedInReleaseCommitOnly() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n").push("a")
                .module("b", pom("b", "0.1.1-SNAPSHOT", "")).tag("b", "v0.1.0")
                .commit("b", "src/V.java", "String V = \"1.0.0\";\n").push("b");
        config(umbrella, """
                text-pins:
                  - in: b/src/V.java
                    pins: a
                    match: 'V = "([^"]+)"'
                """);
        List<String> lines = lines();

        assertEquals(Status.DONE, run(umbrella, EXECUTE, served(), lines, "a", "b").status(),
                String.join("\n", lines));

        assertEquals("String V = \"1.0.1\";\n", show(umbrella, "b", "v0.1.1", "src/V.java"));
        assertEquals("String V = \"1.0.1\";\n", read(umbrella.dir("b").resolve("src/V.java")));
        assertEquals(2, FixtureUmbrella.git(umbrella.dir("b"), "log", "--format=%s", "v0.1.0..main",
                "--", "src/V.java").lines().size(), "moved by the release commit, not again by the snapshot");
    }

    @Test
    void afterReleasePropertySet() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "  <properties><baseline>1.0.0</baseline></properties>\n"))
                .tag("a", "v1.0.0").commit("a", "src/A.java", "class A {}\n").push("a");
        config(umbrella, """
                after-release:
                  a: {baseline: "{version}"}
                """);
        List<String> lines = lines();

        assertEquals(Status.DONE, run(umbrella, EXECUTE, served(), lines, "a").status(), String.join("\n", lines));

        assertTrue(show(umbrella, "a", "v1.0.1", "pom.xml").contains("<baseline>1.0.0</baseline>"));
        assertTrue(read(umbrella.dir("a").resolve("pom.xml")).contains("<baseline>1.0.1</baseline>"));
    }

    @Test
    void changelogStamped() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n")
                .commit("a", "CHANGELOG.md", "# Changelog\n\n## [Unreleased]\n\n- Something.\n\n## [1.0.0]\n")
                .push("a");
        config(umbrella, "changelog: true\n");
        List<String> lines = lines();

        assertEquals(Status.DONE, run(umbrella, EXECUTE, served(), lines, "a").status(), String.join("\n", lines));

        String stamped = show(umbrella, "a", "v1.0.1", "CHANGELOG.md");
        assertTrue(stamped.contains("## [1.0.1] — 2026-11-10\n\n- Something."), stamped);
        assertFalse(stamped.contains("[Unreleased]"), stamped);
        assertTrue(stamped.contains("## [1.0.0]"), stamped);
    }

    @Test
    void stepsRunAtTheirPoints() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n").push("a");
        Path records = tmp.resolve("records");
        config(umbrella, "steps:\n"
                + "  - {at: before-release-commit, run: " + yamlList(probe(records.resolve("before"), 0)) + "}\n"
                + "  - {at: after-tag, run: " + yamlList(probe(records.resolve("tag"), 0)) + "}\n"
                + "  - {at: after-snapshot, modules: [a], run: " + yamlList(probe(records.resolve("snap"), 0))
                + "}\n");
        List<String> lines = lines();
        records.toFile().mkdirs();

        assertEquals(Status.DONE, run(umbrella, EXECUTE, served(), lines, "a").status(), String.join("\n", lines));

        for (String point : List.of("before", "tag", "snap")) {
            String record = read(records.resolve(point));
            assertTrue(record.contains("RT_VERSION=1.0.1") && record.contains("RT_TAG=v1.0.1"), point + ": " + record);
        }
    }

    @Test
    void stepFailureStopsTheRelease() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n").push("a");
        config(umbrella, "steps:\n  - {at: after-tag, run: " + yamlList(probe(tmp.resolve("r"), 3)) + "}\n");
        List<String> lines = lines();

        Outcome outcome = run(umbrella, EXECUTE, served(), lines, "a");

        assertEquals(Status.STOPPED, outcome.status(), String.join("\n", lines));
        ReleaseState.Entry a = StateFile.read(outcome.state().orElseThrow()).modules().getFirst();
        assertEquals(Stage.TAGGED, a.stage());
        assertTrue(a.detail().contains("the after-tag step failed (exit 3)"), a.detail());
    }

    @Test
    void gateRefusalTagsNothing() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Files.writeString(umbrella.dir("b").resolve("dirty.txt"), "not committed\n");
        List<String> lines = lines();

        Outcome outcome = run(umbrella, EXECUTE, served(), lines, "a", "b");

        assertEquals(Status.REFUSED, outcome.status(), String.join("\n", lines));
        assertEquals(List.of("v1.0.0"), tags(umbrella, "a"));
        assertTrue(read(outcome.log().orElseThrow()).contains("REFUSED"));
        assertTrue(outcome.state().isEmpty());
    }
}
