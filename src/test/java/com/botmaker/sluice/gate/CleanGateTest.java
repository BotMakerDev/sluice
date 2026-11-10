package com.botmaker.sluice.gate;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.GateFixture.said;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanGateTest {

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella;

    @BeforeEach
    void create() {
        umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.1.0-SNAPSHOT", ""));
    }

    private List<GateVerdict> verdicts() {
        return check(new CleanGate(), umbrella, Config.defaults(), "a");
    }

    @Test
    void cleanModuleIsOk() {
        assertEquals(Status.OK, only(verdicts()).status(), said(verdicts()));
    }

    @Test
    void dirtyTreeRefused() throws IOException {
        Files.writeString(umbrella.dir("a").resolve("pom.xml"), "<!-- not committed -->\n", StandardOpenOption.APPEND);

        GateVerdict verdict = only(verdicts());

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.refusal().contains("pom.xml"), verdict.refusal());
    }

    /** The release commits only what it changes: an editor's or a build's file reaches no commit of it. */
    @Test
    void untrackedFileIsNoConcern() throws IOException {
        Files.writeString(umbrella.dir("a").resolve(".project"), "an editor's\n");

        assertEquals(Status.OK, only(verdicts()).status(), said(verdicts()));
    }

    @Test
    void detachedHeadRefused() {
        FixtureUmbrella.git(umbrella.dir("a"), "checkout", "-q", "--detach");

        GateVerdict verdict = only(verdicts());

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("a is on a detached HEAD"), verdict.line());
        assertTrue(verdict.refusal().contains("git -C a switch main"), verdict.refusal());
    }

    @Test
    void behindRemoteRefused() {
        umbrella.commit("a", "src/A.java", "class A {}\n").push("a");
        FixtureUmbrella.git(umbrella.dir("a"), "reset", "-q", "--hard", "HEAD~1");

        GateVerdict verdict = only(verdicts());

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("1 commit(s) behind origin/main"), verdict.line());
        assertTrue(verdict.refusal().contains("git -C a pull --ff-only"), verdict.refusal());
    }

    @Test
    void aheadOfItsRemoteIsOk() {
        umbrella.commit("a", "src/A.java", "class A {}\n");

        assertEquals(Status.OK, only(verdicts()).status(), said(verdicts()));
    }
}
