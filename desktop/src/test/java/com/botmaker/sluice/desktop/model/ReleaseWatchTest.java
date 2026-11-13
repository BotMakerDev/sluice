package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.decide.Decider;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.desktop.Fixtures;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Lane;
import com.botmaker.sluice.desktop.model.ReleaseProgress.NodeState;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Phase;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Step;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.Stage;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Module;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseWatchTest {

    private static final Instant T0 = Instant.parse("2026-11-13T10:00:00Z");

    @TempDir
    Path tmp;

    private Umbrella umbrella;
    private Plan plan;
    private Module a;
    private Module b;

    @BeforeEach
    void decide() {
        umbrella = Fixtures.open(Fixtures.pinned(tmp));
        plan = Decider.decide(umbrella.graph(), umbrella.config(), Requests.parse(List.of("a", "b"), Optional.empty()),
                false);
        a = umbrella.module("a").orElseThrow();
        b = umbrella.module("b").orElseThrow();
    }

    private ReleaseWatch watch() {
        return new ReleaseWatch(umbrella.config().tagPattern(), T0);
    }

    @Test
    void gatingUntilTheFirstModuleMoves() {
        ReleaseWatch watch = watch();
        watch.accept(new Event.Decided(plan), T0.plusSeconds(1));
        watch.accept(new Event.Verdict(new GateVerdict("clean", GateVerdict.Status.OK, "clean", "")),
                T0.plusSeconds(2));

        ReleaseProgress progress = watch.progress(T0.plusSeconds(3));

        assertEquals(Phase.GATING, progress.phase());
        assertEquals(List.of("a", "b"), progress.lanes().stream().map(Lane::module).toList());
        assertEquals("v1.0.1", progress.lanes().getFirst().tag());
        assertEquals(1, progress.verdicts().size());
    }

    @Test
    void theModuleAtWorkRunsItsNextStepAndTheRestWait() {
        ReleaseWatch watch = watch();
        watch.accept(new Event.Decided(plan), T0);
        watch.accept(new Event.Reached(a, Stage.RELEASE_COMMITTED), T0.plusSeconds(10));
        watch.accept(new Event.Reached(a, Stage.TAGGED), T0.plusSeconds(20));

        ReleaseProgress progress = watch.progress(T0.plusSeconds(30));

        assertEquals(Phase.RELEASING, progress.phase());
        Lane first = progress.lanes().get(0);
        assertEquals(NodeState.OK, first.state(Step.TAG));
        assertEquals(NodeState.RUNNING, first.state(Step.PUSH));
        assertEquals(NodeState.PENDING, first.state(Step.PUBLISH));
        assertEquals(Optional.of(Duration.ofSeconds(30)), first.elapsed());
        Lane second = progress.lanes().get(1);
        assertEquals(NodeState.PENDING, second.state(Step.COMMIT));
        assertTrue(second.elapsed().isEmpty());
        assertTrue(watch.running());
    }

    @Test
    void aStopMarksTheStepItStoppedAtWithTheRunsWords() {
        ReleaseWatch watch = watch();
        watch.accept(new Event.Decided(plan), T0);
        watch.accept(new Event.Reached(a, Stage.SNAPSHOT), T0.plusSeconds(5));
        watch.accept(new Event.Reached(b, Stage.RELEASE_COMMITTED), T0.plusSeconds(6));
        watch.accept(new Event.Line("stopped at b: stopped by the operator"), T0.plusSeconds(7));
        watch.ended(Release.Status.STOPPED, T0.plusSeconds(8));

        ReleaseProgress progress = watch.progress(T0.plusSeconds(100));

        assertEquals(Phase.STOPPED, progress.phase());
        assertFalse(watch.running());
        Lane second = progress.lanes().get(1);
        assertEquals(NodeState.FAILED, second.state(Step.TAG));
        assertEquals(List.of("b: stopped by the operator"), second.errors());
        assertEquals(Duration.ofSeconds(8), progress.elapsed());
        assertEquals("broken", progress.health());
    }

    @Test
    void aTargetThatFailedFailsThePublishStepAndSkipsTheRest() {
        ReleaseWatch watch = watch();
        watch.accept(new Event.Decided(plan), T0);
        watch.accept(new Event.Reached(a, Stage.FAILED), T0.plusSeconds(5));
        watch.ended(Release.Status.STOPPED, T0.plusSeconds(6));

        Lane first = watch.progress(T0.plusSeconds(6)).lanes().getFirst();

        assertEquals(NodeState.OK, first.state(Step.PUSH));
        assertEquals(NodeState.FAILED, first.state(Step.PUBLISH));
        assertEquals(NodeState.SKIPPED, first.state(Step.SNAPSHOT));
        assertTrue(first.tagged());
    }

    @Test
    void aDoneRunCountsItsTiles() {
        ReleaseWatch watch = watch();
        watch.accept(new Event.Decided(plan), T0);
        watch.accept(new Event.Reached(a, Stage.SNAPSHOT), T0.plusSeconds(5));
        watch.accept(new Event.Reached(b, Stage.PUBLISHED), T0.plusSeconds(6));
        watch.accept(new Event.Reached(b, Stage.SNAPSHOT), T0.plusSeconds(7));
        watch.ended(Release.Status.DONE, T0.plusSeconds(8));

        ReleaseProgress progress = watch.progress(T0.plusSeconds(8));
        ReleaseProgress.Tiles tiles = progress.tiles();

        assertEquals(Phase.DONE, progress.phase());
        assertEquals(2, tiles.total());
        assertEquals(2, tiles.tagged());
        assertEquals(2, tiles.published());
        assertEquals(2, tiles.done());
        assertEquals("ok", progress.health());
        assertEquals(List.of(), progress.lanes(ReleaseProgress.Filter.SNAPSHOT));
    }

    @Test
    void aClockPastTheHourSaysTheHour() {
        assertEquals("0:05", ReleaseProgress.clock(Duration.ofSeconds(5)));
        assertEquals("1:02:05", ReleaseProgress.clock(Duration.ofSeconds(3725)));
    }
}
