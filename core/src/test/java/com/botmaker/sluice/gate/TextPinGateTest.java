package com.botmaker.sluice.gate;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextPinGateTest {

    private static final Config CONFIG = ConfigLoader.parse("""
            text-pins:
              - in: b/src/Fallback.java
                pins: a
                match: 'V = "([^"]+)"'
            """, "t");

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella(String written) {
        return FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .module("b", pom("b", "0.1.0-SNAPSHOT", ""))
                .commit("b", "src/Fallback.java", "String V = \"" + written + "\";\n");
    }

    @Test
    void textNamingNoTagRefused() {
        GateVerdict verdict = only(check(new TextPinGate(), umbrella("1.4.0"), CONFIG, "b"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("b/src/Fallback.java names a 1.4.0"), verdict.line());
    }

    @Test
    void textNamingAReleaseIsOk() {
        assertEquals(Status.OK, only(check(new TextPinGate(), umbrella("1.0.0"), CONFIG, "b")).status());
    }

    @Test
    void upstreamReleasedWithItIsNotChecked() {
        FixtureUmbrella umbrella = umbrella("1.4.0").commit("a", "src/A.java", "class A {}\n");

        assertEquals(List.of(), check(new TextPinGate(), umbrella, CONFIG, "a", "b"));
    }
}
