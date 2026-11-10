package com.botmaker.sluice.gate;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.context;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwedGateTest {

    private static final String PINS_A = "  <properties><a.version>1.0.1-SNAPSHOT</a.version></properties>\n"
            + dep("a", "${a.version}");

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella;

    /** {@code a} changed since its release; {@code b}, which pins it, did not. */
    @BeforeEach
    void create() {
        umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .module("b", pom("b", "1.0.1-SNAPSHOT", PINS_A)).tag("b", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n");
    }

    @Test
    void owedUnrequestedRefusedWithReason() {
        GateVerdict verdict = only(check(new OwedGate(), umbrella, Config.defaults(), "a"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("b is owed a release: a releases"), verdict.line());
        assertTrue(verdict.refusal().contains("--module b"), verdict.refusal());
        assertTrue(verdict.refusal().contains("--with-dependents"), verdict.refusal());
    }

    @Test
    void forceTurnsItForced() {
        List<GateVerdict> verdicts = Gates.run(context(umbrella, Config.defaults(), true, "a"),
                List.of(new OwedGate()));

        assertEquals(Status.FORCED, only(verdicts).status());
        assertFalse(Gates.refused(verdicts));
    }

    @Test
    void requestedDependentIsOk() {
        assertEquals(Status.OK, only(check(new OwedGate(), umbrella, Config.defaults(), "a", "b")).status());
    }
}
