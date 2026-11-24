// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.execute;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.execute.Release.Status;
import com.botmaker.sluice.execute.Release.Wiring;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.testing.GateFixture.FixedTarget;
import com.botmaker.sluice.execute.ReleaseFixture.Assets;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.Run;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.execute.ReleaseFixture.EXECUTE;
import static com.botmaker.sluice.execute.ReleaseFixture.byMaven;
import static com.botmaker.sluice.execute.ReleaseFixture.PREVIEW;
import static com.botmaker.sluice.execute.ReleaseFixture.config;
import static com.botmaker.sluice.execute.ReleaseFixture.lines;
import static com.botmaker.sluice.execute.ReleaseFixture.pinned;
import static com.botmaker.sluice.execute.ReleaseFixture.read;
import static com.botmaker.sluice.execute.ReleaseFixture.resume;
import static com.botmaker.sluice.execute.ReleaseFixture.run;
import static com.botmaker.sluice.execute.ReleaseFixture.served;
import static com.botmaker.sluice.execute.ReleaseFixture.show;
import static com.botmaker.sluice.execute.ReleaseFixture.tags;
import static com.botmaker.sluice.execute.ReleaseFixture.wiring;
import static com.botmaker.sluice.testing.GateFixture.probe;
import static com.botmaker.sluice.testing.GateFixture.yamlList;
import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @Tag("slow")
    void executeTagsInOrderMovesPinsAndSnapshots() {
        FixtureUmbrella umbrella = pinned(tmp);
        List<String> lines = lines();
        String umbrellaHead = FixtureUmbrella.git(umbrella.root(), "rev-parse", "HEAD").out().strip();

        // The one release whose versions move by the plugin itself: the rest use PlainGoals.
        Outcome outcome = run(umbrella, EXECUTE, byMaven(wiring(served())), lines, "a", "b");

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
    @Tag("slow")
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
    @Tag("slow")
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
    void resumeRunsNoAfterTagStepTwice() throws Exception {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n").push("a");
        Path first = tmp.resolve("first");
        String steps = "steps:\n  - {at: after-tag, run: " + yamlList(probe(first, 0)) + "}\n"
                + "  - {at: after-tag, run: %s}\n";
        config(umbrella, steps.formatted(yamlList(probe(tmp.resolve("second"), 3))));
        List<String> lines = lines();

        Outcome stopped = run(umbrella, EXECUTE, served(), lines, "a");

        assertEquals(Status.STOPPED, stopped.status(), String.join("\n", lines));
        assertEquals(1, StateFile.read(stopped.state().orElseThrow()).modules().getFirst().steps());
        Files.delete(first);
        config(umbrella, steps.formatted(yamlList(probe(tmp.resolve("second"), 0))));

        assertEquals(Status.DONE, resume(umbrella, served(), lines).status(), String.join("\n", lines));
        assertFalse(Files.exists(first), "the first step ran before the stop, and not again");
        assertTrue(Files.exists(tmp.resolve("second")));
    }

    @Test
    void aModuleNotServedYetHoldsBackOnlyItsDependents() {
        FixtureUmbrella umbrella = pinned(tmp)
                .module("c", pom("c", "3.0.1-SNAPSHOT", "")).tag("c", "v3.0.0")
                .commit("c", "src/C.java", "class C {}\n").push("c");
        PublishTarget notYet = new FixedTarget(Spelling.TAG, Optional.empty(), PublishTarget.Served.NO);
        Wiring wiring = new Wiring(config -> module -> module.name().equals("a") ? notYet : served(),
                Optional::empty, stop -> duration -> true, ReleaseFixture::plain, ReleaseFixture.CLOCK, Assets.NONE,
                targets -> (module, pins, graph) -> Optional.empty(), module -> Optional.empty());
        List<String> lines = lines();

        Outcome stopped = run(umbrella, EXECUTE, wiring, lines, "a", "b", "c");

        assertEquals(Status.STOPPED, stopped.status(), String.join("\n", lines));
        assertEquals(List.of("v1.0.0", "v1.0.1"), tags(umbrella, "a"));
        assertEquals(List.of("v0.1.0"), tags(umbrella, "b"), "b needs a, which is not served yet");
        assertEquals(List.of("v3.0.0", "v3.0.1"), tags(umbrella, "c"), "c needs nothing of a");
        assertTrue(lines.contains("b: waits for a, not published yet"), String.join("\n", lines));

        assertEquals(Status.DONE, resume(umbrella, served(), lines).status(), String.join("\n", lines));
        assertEquals(List.of("v0.1.0", "v0.1.1"), tags(umbrella, "b"));
    }

    @Test
    void aModuleWhoseCiStillRunsHoldsBackOnlyItsDependents() {
        FixtureUmbrella umbrella = pinned(tmp)
                .commit("a", ".github/workflows/ci.yml", "on: push\n").push("a")
                .module("c", pom("c", "3.0.1-SNAPSHOT", "")).tag("c", "v3.0.0")
                .commit("c", "src/C.java", "class C {}\n").push("c");
        String head = FixtureUmbrella.git(umbrella.dir("a"), "rev-parse", "HEAD").out().strip();
        int[] asked = {0};
        GitHub github = (repo, branch) -> Optional.of(!repo.name().equals("a") ? List.of()
                : ++asked[0] < 3 ? List.of(new Run("ci", "in_progress", "", "u", head))
                : List.of(new Run("ci", "completed", "success", "u", head)));
        List<String> lines = lines();

        Outcome done = run(umbrella, EXECUTE, ReleaseFixture.withGitHub(wiring(served()), github), lines,
                "a", "b", "c");

        assertEquals(Status.DONE, done.status(), String.join("\n", lines));
        assertTrue(lines.contains("a: CI on its HEAD is still running: releasing what does not need it first"),
                String.join("\n", lines));
        int c = indexOf(lines, "c tag -a v3.0.1");
        int a = indexOf(lines, "a tag -a v1.0.1");
        int b = indexOf(lines, "b tag -a v0.1.1");
        assertTrue(c >= 0 && c < a && a < b, "c, which needs nothing of a, first:\n" + String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("OK a: CI on main is green")),
                String.join("\n", lines));
    }

    @Test
    @Tag("slow")
    void ciThatTurnsRedStopsTheRunAndResumeJudgesItAgain() {
        FixtureUmbrella umbrella = pinned(tmp)
                .commit("a", ".github/workflows/ci.yml", "on: push\n").push("a");
        String head = FixtureUmbrella.git(umbrella.dir("a"), "rev-parse", "HEAD").out().strip();
        int[] asked = {0};
        String[] conclusion = {"failure"};
        GitHub github = (repo, branch) -> Optional.of(!repo.name().equals("a") ? List.of()
                : ++asked[0] < 3 ? List.of(new Run("ci", "in_progress", "", "u", head))
                : List.of(new Run("ci", "completed", conclusion[0], "u", head)));
        Wiring wiring = ReleaseFixture.withGitHub(wiring(served()), github);
        List<String> lines = lines();

        Outcome stopped = run(umbrella, EXECUTE, wiring, lines, "a", "b");

        assertEquals(Status.STOPPED, stopped.status(), String.join("\n", lines));
        assertEquals(List.of("v1.0.0"), tags(umbrella, "a"));
        List<String> refused = lines();
        assertEquals(Status.REFUSED, ReleaseFixture.resume(umbrella, wiring, refused).status(),
                "a resume judges CI again, still red:\n" + String.join("\n", refused));
        assertEquals(List.of("v1.0.0"), tags(umbrella, "a"));

        conclusion[0] = "success";
        List<String> resumed = lines();
        assertEquals(Status.DONE, ReleaseFixture.resume(umbrella, wiring, resumed).status(),
                String.join("\n", resumed));
        assertTrue(tags(umbrella, "a").contains("v1.0.1") && tags(umbrella, "b").contains("v0.1.1"));
    }

    private static int indexOf(List<String> lines, String part) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void previewGivesThePlanAndTheGatesOnly() {
        FixtureUmbrella umbrella = pinned(tmp);
        String pomBefore = read(umbrella.dir("b").resolve("pom.xml"));

        Release.Preview preview = Release.preview(umbrella.root(), Requests.parse(List.of("a", "b"),
                Optional.empty()), PREVIEW, wiring(served()));

        assertEquals(List.of("a", "b"), preview.plan().releasing().keySet().stream().map(m -> m.name()).toList());
        assertFalse(preview.verdicts().isEmpty());
        assertFalse(preview.refused(), preview.verdicts().toString());
        assertFalse(Files.exists(umbrella.root().resolve(StateFile.DIR)));
        assertEquals(List.of("v1.0.0"), tags(umbrella, "a"));
        assertEquals(pomBefore, read(umbrella.dir("b").resolve("pom.xml")));
    }

    @Test
    void previewSaysARefusalTheRunWouldMeet() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Files.writeString(umbrella.dir("b").resolve("src/B.java"), "class B { int notCommitted; }\n");

        Release.Preview preview = Release.preview(umbrella.root(), Requests.parse(List.of("a", "b"),
                Optional.empty()), PREVIEW, wiring(served()));

        assertTrue(preview.refused(), preview.verdicts().toString());
        assertTrue(preview.verdicts().stream().anyMatch(v -> v.stops() && v.gate().equals("clean")),
                preview.verdicts().toString());
    }

    @Test
    void previewOfNothingGatesNothing() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "1.0.1-SNAPSHOT", ""))
                .tag("a", "v1.0.0").push("a");

        Release.Preview preview = Release.preview(umbrella.root(), Requests.parse(List.of(), Optional.of(
                Level.MINOR)), PREVIEW, wiring(served()));

        assertTrue(preview.plan().releasing().isEmpty(), preview.plan().toString());
        assertEquals(List.of(), preview.verdicts());
    }

    @Test
    void gateRefusalTagsNothing() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Files.writeString(umbrella.dir("b").resolve("src/B.java"), "class B { int notCommitted; }\n");
        List<String> lines = lines();

        Outcome outcome = run(umbrella, EXECUTE, served(), lines, "a", "b");

        assertEquals(Status.REFUSED, outcome.status(), String.join("\n", lines));
        assertEquals(List.of("v1.0.0"), tags(umbrella, "a"));
        assertTrue(read(outcome.log().orElseThrow()).contains("REFUSED"));
        assertTrue(outcome.state().isEmpty());
    }

    /** An editor's file is in no commit of the release, and stays where it was. */
    @Test
    @Tag("slow")
    void untrackedFileStaysOutOfTheRelease() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Files.writeString(umbrella.dir("b").resolve(".project"), "an editor's\n");
        List<String> lines = lines();

        assertEquals(Status.DONE, run(umbrella, EXECUTE, served(), lines, "a", "b").status(),
                String.join("\n", lines));

        assertFalse(FixtureUmbrella.git(umbrella.dir("b"), "ls-tree", "-r", "--name-only", "HEAD").lines()
                .contains(".project"));
        assertEquals("?? .project", FixtureUmbrella.git(umbrella.dir("b"), "status", "--porcelain").out().strip());
    }

    @Test
    @Tag("slow")
    void runSyncsAStalePinOfACleanModuleBeforeItGates() {
        FixtureUmbrella umbrella = pinned(tmp);
        umbrella.commit("b", "pom.xml", read(umbrella.dir("b").resolve("pom.xml"))
                .replace("<a.version>1.0.1-SNAPSHOT</a.version>", "<a.version>0.9.0</a.version>")).push("b");
        List<String> lines = lines();

        Outcome outcome = run(umbrella, EXECUTE, served(), lines, "a", "b");

        assertEquals(Status.DONE, outcome.status(), String.join("\n", lines));
        String log = FixtureUmbrella.git(umbrella.dir("b"), "log", "--format=%s", "origin/main").out();
        assertTrue(log.contains("pins synced to their upstreams' main"), log);
        assertTrue(show(umbrella, "b", "v0.1.1", "pom.xml").contains("<a.version>v1.0.1</a.version>"));
    }

    @Test
    @Tag("slow")
    void runFastForwardsAModuleBehindItsOrigin() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        Path other = tmp.resolve("other");
        FixtureUmbrella.git(tmp, "clone", "-q", tmp.resolve("remotes").resolve("a.git").toString(), other.toString());
        FixtureUmbrella.git(other, "config", "user.email", "t@example.invalid");
        FixtureUmbrella.git(other, "config", "user.name", "t");
        Files.writeString(other.resolve("Elsewhere.java"), "class Elsewhere {}\n");
        FixtureUmbrella.git(other, "add", "-A");
        FixtureUmbrella.git(other, "commit", "-q", "-m", "pushed from elsewhere");
        FixtureUmbrella.git(other, "push", "-q", "origin", "main");
        List<String> lines = lines();

        Outcome outcome = run(umbrella, EXECUTE, served(), lines, "a", "b");

        assertEquals(Status.DONE, outcome.status(), String.join("\n", lines));
        assertTrue(lines.contains("a: behind origin/main, fast-forwarding it"), String.join("\n", lines));
        assertEquals("class Elsewhere {}\n", show(umbrella, "a", "v1.0.1", "Elsewhere.java"));
    }

    @Test
    void noWaitBuildsNothingAtPinsItsUpstreamMayNotServeYet() {
        FixtureUmbrella umbrella = pinned(tmp);
        List<String> built = new ArrayList<>();
        List<String> lines = lines();

        Outcome outcome = run(umbrella, new Release.Options(true, false, Optional.empty(), false),
                wiring(served(), Assets.NONE, (module, pins, graph) -> {
                    built.add(module.name());
                    return Optional.empty();
                }), lines, "a", "b");

        assertEquals(Status.DONE, outcome.status(), String.join("\n", lines));
        assertEquals(List.of(), built);
        assertTrue(lines.contains("    b: not built at its pins (a was not waited on)"), String.join("\n", lines));
    }

    @Test
    void aFastForwardThatChangesWhatWouldBeCutRefusesBeforeAnyTag() throws Exception {
        FixtureUmbrella umbrella = pinned(tmp);
        // Elsewhere, b stops depending on a: once pulled, a release of a owes b nothing.
        Path other = tmp.resolve("other");
        FixtureUmbrella.git(tmp, "clone", "-q", tmp.resolve("remotes").resolve("b.git").toString(), other.toString());
        FixtureUmbrella.git(other, "config", "user.email", "t@example.invalid");
        FixtureUmbrella.git(other, "config", "user.name", "t");
        Files.writeString(other.resolve("pom.xml"), pom("b", "0.1.1-SNAPSHOT", ""));
        FixtureUmbrella.git(other, "commit", "-q", "-am", "drop a");
        FixtureUmbrella.git(other, "push", "-q", "origin", "main");
        List<String> lines = lines();

        Refusal refused = assertThrows(Refusal.class, () -> run(umbrella, EXECUTE, served(), lines, "a"),
                String.join("\n", lines));

        assertTrue(refused.getMessage().contains("preview again"), refused.getMessage());
        assertEquals(List.of("v1.0.0"), tags(umbrella, "a"));
    }

    @Test
    @Tag("slow")
    void aModuleThatDoesNotBuildAtItsPinsStopsBeforeItsReleaseCommit() {
        FixtureUmbrella umbrella = pinned(tmp);
        List<String> built = new ArrayList<>();
        GateVerdict broken = new GateVerdict("builds-at-pins", GateVerdict.Status.REFUSED,
                "b does not build at the pins its tag would carry", "b does not build at the pins its tag would carry"
                + "\n     [ERROR] cannot find symbol");
        Release.AtPins failing = (module, pins, graph) -> {
            built.add(module + " " + pins.values());
            return Optional.of(broken);
        };
        List<String> lines = lines();

        Outcome stopped = run(umbrella, EXECUTE, wiring(served(), Assets.NONE, failing), lines, "a", "b");

        assertEquals(Status.STOPPED, stopped.status(), String.join("\n", lines));
        assertEquals(List.of("b [v1.0.1]"), built);
        assertEquals(List.of("v1.0.0", "v1.0.1"), tags(umbrella, "a"));
        assertEquals(List.of("v0.1.0"), tags(umbrella, "b"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[ERROR] cannot find symbol")),
                String.join("\n", lines));

        Outcome resumed = resume(umbrella, wiring(served(), Assets.NONE, (module, pins, graph) -> {
            built.add(module + " again");
            return Optional.empty();
        }), lines);

        assertEquals(Status.DONE, resumed.status(), String.join("\n", lines));
        assertEquals(List.of("b [v1.0.1]", "b again"), built, String.join("\n", lines));
        assertEquals(List.of("v0.1.0", "v0.1.1"), tags(umbrella, "b"));
    }

    /** {@code b} changed nothing of its own: the release writes its notes, the pin it moves. */
    @Test
    @Tag("slow")
    void releaseOwedToItsUpstreamWritesItsNotes() {
        String bReleased = "  <properties><a.version>v1.0.0</a.version></properties>\n" + dep("a", "${a.version}");
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n")
                .commit("a", "CHANGELOG.md", "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- A thing.\n")
                .push("a")
                .module("b", pom("b", "0.1.0", bReleased)).tag("b", "v0.1.0")
                .commit("b", "pom.xml", pom("b", "0.1.1-SNAPSHOT", bReleased.replace("v1.0.0", "1.0.1-SNAPSHOT")))
                .commit("b", "CHANGELOG.md", "# Changelog\n\n## [0.1.0] — 2026-01-01\n\n- First.\n")
                .push("b");
        config(umbrella, "changelog: {pins: true}\n");
        List<String> lines = lines();

        assertEquals(Status.DONE, run(umbrella, EXECUTE, served(), lines, "a", "b").status(),
                String.join("\n", lines));

        String b = show(umbrella, "b", "v0.1.1", "CHANGELOG.md");
        assertEquals("# Changelog\n\n## [0.1.1] — 2026-11-10\n\n### Changed\n\n- Built against a 1.0.1 (was 1.0.0).\n\n"
                + "## [0.1.0] — 2026-01-01\n\n- First.\n", b);
        String a = show(umbrella, "a", "v1.0.1", "CHANGELOG.md");
        assertEquals("# Changelog\n\n## [1.0.1] — 2026-11-10\n\n### Fixed\n\n- A thing.\n", a,
                "a pins nothing: its notes are its own");
    }
}
