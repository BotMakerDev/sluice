package com.botmaker.sluice.gate;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinsAtMainGateTest {

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella(String pinned) {
        return FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", ""))
                .module("b", pom("b", "0.1.0-SNAPSHOT", "  <properties><a.version>" + pinned
                        + "</a.version></properties>\n" + dep("a", "${a.version}")));
    }

    @Test
    void stalePinRefusedNamingPom() {
        GateVerdict verdict = only(check(new PinsAtMainGate(), umbrella("1.0.0"), Config.defaults(), "b"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("b pins a at 1.0.0 in b/pom.xml"), verdict.line());
        assertTrue(verdict.line().contains("a is at 1.0.1-SNAPSHOT on main"), verdict.line());
        assertTrue(verdict.refusal().contains("--sync-pins"), verdict.refusal());
    }

    @Test
    void pinAtItsUpstreamsVersionIsOk() {
        assertEquals(Status.OK,
                only(check(new PinsAtMainGate(), umbrella("1.0.1-SNAPSHOT"), Config.defaults(), "b")).status());
    }
}
