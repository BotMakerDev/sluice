package com.botmaker.sluice.gate;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.github.GitHub.Run;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                ".github/workflows/build.yml");
        Run pages = new Run("pages", "completed", "failure", "https://example.invalid/8", "ffffffffff",
                "dynamic/pages/pages-build-deployment");

        assertEquals(Status.OK, verdict(Optional.of(List.of(passedAtHead(), gone))).status(),
                "a workflow no longer in the module says nothing of its branch now");
        assertEquals(Status.REFUSED, verdict(Optional.of(List.of(passedAtHead(), pages))).status(),
                "GitHub's own workflows have no file, and still count");
    }

    @Test
    void nothingFinishedOrNoAnswerSkipped() {
        assertEquals(Status.SKIPPED, verdict(Optional.of(List.of(RUNNING))).status());
        assertEquals(Status.SKIPPED, verdict(Optional.of(List.of())).status());
        assertEquals(Status.SKIPPED, verdict(Optional.empty()).status());
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
