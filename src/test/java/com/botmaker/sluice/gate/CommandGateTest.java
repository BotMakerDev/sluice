package com.botmaker.sluice.gate;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.GateFixture.probe;
import static com.botmaker.sluice.testing.GateFixture.yamlList;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandGateTest {

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella;
    private Path record;

    @BeforeEach
    void create() {
        umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "0.1.0-SNAPSHOT", ""))
                .module("plugin-b", pom("plugin-b", "0.1.0-SNAPSHOT", ""));
        record = tmp.resolve("record.txt");
    }

    /** The verdicts of a gate {@code check} running the probe in {@code a}, {@code extra} added to its entry. */
    private List<GateVerdict> run(String extra, int exit, String... arguments) {
        Config config = ConfigLoader.parse("gates:\n  - name: check\n    modules: [a]\n" + extra
                + "    run: " + yamlList(probe(record, exit, arguments)) + "\n", "t");
        return check(new CommandGate(config.gates().getFirst()), umbrella, config, "a", "plugin-b=1.2.0");
    }

    @Test
    void exitZeroOk() {
        GateVerdict verdict = only(run("", 0));

        assertEquals(Status.OK, verdict.status(), verdict.refusal());
        assertEquals("check", verdict.gate());
    }

    @Test
    void skipExitCodeSkipped() {
        assertEquals(Status.SKIPPED, only(run("    skip-exit: 3\n", 3)).status());
    }

    @Test
    void otherExitRefusedQuotingOutput() {
        GateVerdict verdict = only(run("    skip-exit: 3\n", 1));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("a: failed (exit 1)"), verdict.line());
        assertTrue(verdict.refusal().contains("probe ran"), verdict.refusal());
    }

    @Test
    void argumentListRunsWithoutShell() throws IOException {
        String untouched = "a b; echo $HOME && * 'q' \"d\"";

        assertEquals(Status.OK, only(run("", 0, untouched, "")).status());

        assertEquals(List.of("arg=" + untouched, "arg="), Files.readAllLines(record).subList(0, 2));
    }

    @Test
    void envCarriesReleaseFacts() throws IOException {
        run("", 0);

        List<String> facts = Files.readAllLines(record);
        assertTrue(facts.containsAll(List.of("RT_MODULE=a", "RT_VERSION=0.0.1", "RT_NEXT_SNAPSHOT=0.0.2-SNAPSHOT",
                "RT_TAG=v0.0.1", "RT_DRY_RUN=true", "RT_VERSION_A=0.0.1", "RT_VERSION_PLUGIN_B=1.2.0",
                "RT_UMBRELLA=" + umbrella.root().toAbsolutePath().normalize())), facts.toString());
    }

    @Test
    void runsInTheModulesDirectory() {
        Config config = ConfigLoader.parse("""
                gates:
                  - name: has-pom
                    run: [git, ls-files, --error-unmatch, pom.xml]
                """, "t");

        List<GateVerdict> verdicts = check(new CommandGate(config.gates().getFirst()), umbrella, config, "a",
                "plugin-b");

        assertEquals(List.of(Status.OK, Status.OK), verdicts.stream().map(GateVerdict::status).toList());
    }
}
