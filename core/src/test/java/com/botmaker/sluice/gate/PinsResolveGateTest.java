package com.botmaker.sluice.gate;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.spi.PublishTarget.Served;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture.FixedTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinsResolveGateTest {

    private static final String PINS_A = "  <properties><a.version>1.0.1-SNAPSHOT</a.version></properties>\n"
            + dep("a", "${a.version}");

    @TempDir
    Path tmp;

    /** {@code b} pins {@code a}; {@code a} is released at 1.0.0 when {@code released}. */
    private FixtureUmbrella umbrella(boolean released) {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "1.0.1-SNAPSHOT", ""));
        if (released) {
            umbrella.tag("a", "v1.0.0");
        }
        return umbrella.module("b", pom("b", "0.1.0-SNAPSHOT", PINS_A));
    }

    private static PinsResolveGate gate(Served served) {
        return new PinsResolveGate(module -> new FixedTarget(Spelling.TAG, Optional.empty(), served));
    }

    @Test
    void pinNotServedRefused() {
        GateVerdict verdict = only(check(gate(Served.NO), umbrella(true), Config.defaults(), "b"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("b would pin a v1.0.0, which fixture does not serve"), verdict.line());
        assertTrue(verdict.refusal().contains("--module a"), verdict.refusal());
    }

    @Test
    void pinServedIsOk() {
        assertEquals(Status.OK, only(check(gate(Served.YES), umbrella(true), Config.defaults(), "b")).status());
    }

    @Test
    void targetNotAnsweringSkipped() {
        assertEquals(Status.SKIPPED,
                only(check(gate(Served.UNREACHABLE), umbrella(true), Config.defaults(), "b")).status());
    }

    @Test
    void upstreamNeverReleasedRefused() {
        GateVerdict verdict = only(check(gate(Served.YES), umbrella(false), Config.defaults(), "b"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("a, which has no release to pin"), verdict.line());
    }

    @Test
    void upstreamInThisReleaseIsNotAsked() {
        assertEquals(List.of(), check(gate(Served.NO), umbrella(false), Config.defaults(), "a", "b"));
    }
}
