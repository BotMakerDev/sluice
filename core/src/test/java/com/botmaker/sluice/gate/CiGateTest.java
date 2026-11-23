// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.gate;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.github.GitHub.Run;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiGateTest {

    private static final Run RUNNING = new Run("ci", "in_progress", "", "https://example.invalid/3", "cccccccccc");
    private static final Run FAILED = new Run("ci", "completed", "failure", "https://example.invalid/2", "bbbbbbbbbb");
    private static final Run PASSED = new Run("ci", "completed", "success", "https://example.invalid/1", "aaaaaaaaaa");

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella;

    @BeforeEach
    void create() {
        umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.1.0-SNAPSHOT", ""));
    }

    private GateVerdict verdict(Optional<List<Run>> runs) {
        GitHub github = (repo, branch) -> {
            assertEquals("owner/a on main", repo + " on " + branch);
            return runs;
        };
        return only(check(new CiGate(Optional.of(github), module -> Optional.of(new Repo("owner", module.name()))),
                umbrella, Config.defaults(), "a"));
    }

    @Test
    void failureRefused() {
        GateVerdict verdict = verdict(Optional.of(List.of(FAILED, PASSED)));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.refusal().contains("ci: failure at bbbbbbb  https://example.invalid/2"), verdict.refusal());
    }

    @Test
    void runningLeavesTheNewestFinishedToAnswer() {
        GateVerdict green = verdict(Optional.of(List.of(RUNNING, passedAtHead(), FAILED)));
        GateVerdict red = verdict(Optional.of(List.of(RUNNING, FAILED, PASSED)));

        assertEquals(Status.OK, green.status(), green.refusal());
        assertTrue(green.line().contains("a newer run is still going"), green.line());
        assertEquals(Status.REFUSED, red.status());
    }

    /** A green run of the commit {@code a} is on. */
    private Run passedAtHead() {
        String head = FixtureUmbrella.git(umbrella.dir("a"), "rev-parse", "HEAD").lines().getFirst();
        return new Run("ci", "completed", "success", "https://example.invalid/5", head);
    }

    @Test
    void greenRunOfAnOlderCommitSkipped() {
        GateVerdict verdict = verdict(Optional.of(List.of(PASSED)));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("no finished run built its HEAD"), verdict.line());
    }

    @Test
    void cancelledRunLeavesTheOneBeforeItToAnswer() {
        Run cancelled = new Run("ci", "completed", "cancelled", "https://example.invalid/6", "eeeeeeeeee");

        assertEquals(Status.OK, verdict(Optional.of(List.of(cancelled, passedAtHead()))).status());
        assertEquals(Status.REFUSED, verdict(Optional.of(List.of(cancelled, FAILED))).status());
    }

    @Test
    void anotherWorkflowsFailureIsNotHiddenByANewerGreenOne() {
        Run pages = new Run("pages", "completed", "success", "https://example.invalid/4", "dddddddddd");

        assertEquals(Status.REFUSED, verdict(Optional.of(List.of(pages, FAILED))).status());
    }

    @Test
    void aRemovedWorkflowsFailureIsIgnored() {
        Run gone = new Run("build", "completed", "failure", "https://example.invalid/7", "ffffffffff",
                ".github/workflows/build.yml", "");
        Run pages = new Run("pages", "completed", "failure", "https://example.invalid/8", "ffffffffff",
                "dynamic/pages/pages-build-deployment", "");

        assertEquals(Status.OK, verdict(Optional.of(List.of(passedAtHead(), gone))).status(),
                "a workflow no longer in the module says nothing of its branch now");
        assertEquals(Status.REFUSED, verdict(Optional.of(List.of(passedAtHead(), pages))).status(),
                "GitHub's own workflows have no file, and still count");
    }

    /** A dependent's CI builds its upstream's branch: an upstream green since it failed is named. */
    @Test
    void anUpstreamGreenSinceTheFailureIsNamed() {
        FixtureUmbrella two = FixtureUmbrella.create(tmp.resolve("two"))
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("b", pom("b", "0.1.0-SNAPSHOT", "  <properties><a.version>1.0.0-SNAPSHOT</a.version>"
                        + "</properties>\n" + dep("a", "${a.version}")));
        Run failed = new Run("ci", "completed", "failure", "https://example.invalid/9", "bbbbbbbbbb", "",
                "2026-11-10T03:20:00Z");
        Run greenBefore = new Run("ci", "completed", "success", "https://example.invalid/10", "aaaaaaaaaa", "",
                "2026-11-10T03:00:00Z");
        Run greenAfter = new Run("ci", "completed", "success", "https://example.invalid/11", "9999999999", "",
                "2026-11-10T04:00:00Z");

        GateVerdict named = only(check(new CiGate(Optional.of((repo, branch) -> Optional.of(
                repo.name().equals("a") ? List.of(greenAfter) : List.of(failed))),
                module -> Optional.of(new Repo("owner", module.name()))), two, Config.defaults(), "b"));
        GateVerdict notNamed = only(check(new CiGate(Optional.of((repo, branch) -> Optional.of(
                repo.name().equals("a") ? List.of(greenBefore) : List.of(failed))),
                module -> Optional.of(new Repo("owner", module.name()))), two, Config.defaults(), "b"));

        assertEquals(Status.REFUSED, named.status());
        assertTrue(named.refusal().contains("a went green after it, at 9999999: if it failed in a's build, rerun it"),
                named.refusal());
        assertFalse(notNamed.refusal().contains("went green"), notNamed.refusal());
    }

    @Test
    void nothingFinishedOrNoAnswerSkipped() {
        assertEquals(Status.SKIPPED, verdict(Optional.of(List.of(RUNNING))).status());
        assertEquals(Status.SKIPPED, verdict(Optional.of(List.of())).status());
        assertEquals(Status.SKIPPED, verdict(Optional.empty()).status());
    }

    /** A module with a workflow and one commit its origin lacks; its {@code HEAD}. */
    private String aheadWithAWorkflow() {
        umbrella.commit("a", ".github/workflows/ci.yml", "on: push\n").commit("a", "src/A.java", "class A {}\n");
        return Git.line(umbrella.dir("a"), "rev-parse", "HEAD").orElseThrow();
    }

    private static GateContext executing(GateContext preview) {
        return new GateContext(preview.umbrella(), preview.graph(), preview.plan(), preview.config(),
                new Runner(false, line -> { }), false);
    }

    @Test
    void previewSaysItWouldPushAndWait() {
        aheadWithAWorkflow();
        GitHub github = (repo, branch) -> Optional.of(List.of(PASSED));

        GateVerdict verdict = only(check(new CiGate(Optional.of(github), module -> Optional.of(new Repo("o", "a"))),
                umbrella, Config.defaults(), "a"));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("a is 2 commit(s) ahead of origin/main: a run pushes it and waits up to "
                + "20 min for CI on its HEAD"), verdict.line());
        assertNotEquals(Git.line(umbrella.dir("a"), "rev-parse", "HEAD"),
                Git.line(umbrella.dir("a"), "rev-parse", "refs/remotes/origin/main"), "a preview pushes nothing");
    }

    @Test
    void runPushesThenWaitsForCiOnHead() {
        String head = aheadWithAWorkflow();
        List<List<Run>> answers = new ArrayList<>(List.of(List.of(PASSED),
                List.of(new Run("ci", "in_progress", "", "u", head), PASSED),
                List.of(new Run("ci", "completed", "success", "u", head), PASSED)));
        GitHub github = (repo, branch) -> Optional.of(answers.size() > 1 ? answers.removeFirst() : answers.getFirst());
        int[] slept = {0};
        CiGate gate = new CiGate(Optional.of(github), module -> Optional.of(new Repo("o", "a")),
                Optional.of(duration -> ++slept[0] > 0));

        List<GateVerdict> verdicts = gate.check(executing(GateFixture.context(umbrella, Config.defaults(), false,
                "a")));

        assertEquals(Status.OK, only(verdicts).status(), only(verdicts).line());
        assertEquals(2, slept[0]);
        assertEquals(Optional.of(head), Git.line(umbrella.dir("a"), "rev-parse", "refs/remotes/origin/main"),
                "pushed before waiting");
    }

    @Test
    void waitThatRunsOutLeavesCiUnchecked() {
        aheadWithAWorkflow();
        GitHub github = (repo, branch) -> Optional.of(List.of(PASSED));
        int[] slept = {0};
        CiGate gate = new CiGate(Optional.of(github), module -> Optional.of(new Repo("o", "a")),
                Optional.of(duration -> ++slept[0] > 0));
        Config oneMinute = ConfigLoader.parse("ci-wait: 1\n", "t");

        GateVerdict verdict = only(gate.check(executing(GateFixture.context(umbrella, oneMinute, false, "a"))));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("no finished run built its HEAD"), verdict.line());
        assertEquals(1, slept[0], "one minute is two asks, thirty seconds apart");
    }

    @Test
    void noTokenSkipped() {
        GateVerdict verdict = only(check(new CiGate(Optional.empty()), umbrella, Config.defaults(), "a"));

        assertEquals(Status.SKIPPED, verdict.status());
        assertTrue(verdict.line().contains("GITHUB_TOKEN"), verdict.line());
    }

    @Test
    void remoteThatIsNotOnGitHubSkipped() {
        GitHub github = (repo, branch) -> Optional.of(List.of(FAILED));

        assertEquals(Status.SKIPPED,
                only(check(new CiGate(Optional.of(github)), umbrella, Config.defaults(), "a")).status());
    }
}
