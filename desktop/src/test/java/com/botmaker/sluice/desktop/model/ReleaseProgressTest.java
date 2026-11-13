package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.desktop.model.ReleaseProgress.NodeState;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Phase;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Step;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.ReleaseLogs.LoggedRelease;
import com.botmaker.sluice.execute.ReleaseState;
import com.botmaker.sluice.execute.Stage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseProgressTest {

    private static LoggedRelease logged(Optional<ReleaseState> state, Optional<Release.Status> status) {
        return new LoggedRelease("2026-11-13-1042", Optional.empty(), Optional.empty(), state, status,
                Optional.empty());
    }

    private static ReleaseState.Entry entry(String module, String version, Stage stage, String detail) {
        return new ReleaseState.Entry(module, Version.parse(version).orElseThrow(), stage, detail);
    }

    @Test
    void aRunItsStateSaysIsRunningDiedWhereItWas() {
        ReleaseState state = new ReleaseState(ReleaseState.Status.RUNNING, List.of(
                entry("a", "1.0.1", Stage.SNAPSHOT, ""),
                entry("b", "0.1.1", Stage.TAGGED, "")));

        ReleaseProgress progress = ReleaseProgress.past(logged(Optional.of(state), Optional.empty()), "v{version}");

        assertEquals(Phase.DIED, progress.phase());
        assertEquals("v0.1.1", progress.lanes().get(1).tag());
        assertEquals(NodeState.OK, progress.lanes().get(1).state(Step.TAG));
        assertEquals(NodeState.FAILED, progress.lanes().get(1).state(Step.PUSH));
        assertEquals(NodeState.OK, progress.lanes().get(0).state(Step.SNAPSHOT));
    }

    @Test
    void aStateEntrysDetailIsItsError() {
        ReleaseState state = new ReleaseState(ReleaseState.Status.STOPPED, List.of(
                entry("a", "1.0.1", Stage.FAILED, "the target failed to build v1.0.1")));

        ReleaseProgress progress = ReleaseProgress.past(logged(Optional.of(state), Optional.empty()), "v{version}");

        assertEquals(Phase.STOPPED, progress.phase());
        assertEquals(List.of("the target failed to build v1.0.1"), progress.lanes().getFirst().errors());
        assertEquals(NodeState.FAILED, progress.lanes().getFirst().state(Step.PUBLISH));
    }

    @Test
    void aLogWithoutAStateIsARefusal() {
        ReleaseProgress progress = ReleaseProgress.past(logged(Optional.empty(), Optional.of(Release.Status.REFUSED)),
                "v{version}");

        assertEquals(Phase.REFUSED, progress.phase());
        assertEquals(List.of(), progress.lanes());
        assertEquals("broken", progress.health());
    }

    @Test
    void theFileNameIsWhenItStarted() {
        assertEquals(LocalDateTime.of(2026, 11, 13, 10, 42).atZone(ZoneId.systemDefault()).toInstant(),
                ReleaseProgress.started("2026-11-13-1042"));
    }
}
