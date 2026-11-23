// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.cli;

import com.botmaker.sluice.execute.LocalEngine;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineTest {

    @TempDir
    Path tmp;

    /** One run of the command line: its exit code and what it printed. */
    private record Ran(int code, String out, String err) {

        @Override
        public String toString() {
            return "exit " + code + "\n" + out + "\n" + err;
        }
    }

    private static Ran sluice(LocalEngine engine, FixtureUmbrella umbrella, String... args) {
        List<String> all = new ArrayList<>(List.of("--umbrella", umbrella.root().toString()));
        all.addAll(List.of(args));
        return sluice(engine, all.toArray(String[]::new));
    }

    private static Ran sluice(LocalEngine engine, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Main.run(args, new PrintWriter(out), new PrintWriter(err), engine);
        return new Ran(code, out.toString(), err.toString());
    }

    @Test
    void exitCodes() {
        FixtureUmbrella umbrella = LocalEngine.chain(tmp);
        LocalEngine engine = new LocalEngine();

        Ran preview = sluice(engine, umbrella, "preview", "--all", "patch");
        assertEquals(Main.OK, preview.code(), preview.toString());
        assertTrue(preview.out().contains("previewed"), preview.toString());

        Ran owing = sluice(engine, umbrella, "preview", "--module", "a");
        assertEquals(Main.OK, owing.code(), owing.toString());
        assertTrue(owing.out().contains("b 0.1.0 -> 0.1.1") && owing.out().contains("c 0.2.0 -> 0.2.1"),
                owing.toString());

        Ran both = sluice(engine, umbrella, "preview", "--module", "a", "--with-dependents", "--no-dependents");
        assertEquals(Main.USAGE, both.code(), both.toString());

        Ran unknown = sluice(engine, umbrella, "preview", "--module", "nope");
        assertEquals(Main.REFUSED, unknown.code(), unknown.toString());
        assertTrue(unknown.err().contains("\"nope\" is no module"), unknown.toString());

        engine.failing().add("b:v0.1.1");
        Ran stopped = sluice(engine, umbrella, "release", "--module", "a", "--module", "b", "--module", "c");
        assertEquals(Main.STOPPED, stopped.code(), stopped.toString());
        assertTrue(stopped.out().contains("sluice resume"), stopped.toString());

        Ran status = sluice(engine, umbrella, "status");
        assertEquals(Main.STOPPED, status.code(), status.toString());
        assertTrue(status.out().contains("b 0.1.1: failed (its build failed)"), status.toString());
        String file = status.out().lines().findFirst().orElseThrow();
        Ran named = sluice(engine, umbrella, "status", "releases/" + Path.of(file).getFileName());
        assertEquals(status, named, "a relative FILE is found under the umbrella");

        engine.failing().clear();
        Ran dry = sluice(engine, umbrella, "resume", "--dry-run");
        assertEquals(Main.OK, dry.code(), dry.toString());
        assertEquals(status.out(), sluice(engine, umbrella, "status").out(), "a resume's dry run writes nothing");
        Ran resumed = sluice(engine, "resume", "--umbrella", umbrella.root().toString());
        assertEquals(Main.OK, resumed.code(), resumed.toString());

        assertEquals(Main.USAGE, sluice(engine, umbrella, "preview", "--bogus").code());
        assertEquals(Main.USAGE, sluice(engine, umbrella).code(), "no verb");
        assertEquals(Main.USAGE, sluice(engine, umbrella, "preview").code(), "nothing to release");
        assertEquals(Main.USAGE, sluice(engine, umbrella, "preview", "--all", "huge").code());
        for (String gone : List.of("--execute", "--resume", "--status", "--sync-pins", "--why")) {
            assertEquals(Main.USAGE, sluice(engine, umbrella, "preview", "--all", "patch", gone).code(), gone);
        }
        assertEquals(Main.USAGE, sluice(engine, umbrella, "sync-pins", "--force").code(), "not a sync's flag");
        assertEquals(Main.USAGE, sluice(engine, umbrella, "resume", "--all", "patch").code(), "a resume picks none");
        assertEquals(Main.OK, sluice(engine, umbrella, "sync-pins", "--dry-run").code());
        assertEquals(Main.OK, sluice(engine, "--help").code());
        assertEquals(Main.OK, sluice(engine, "release", "--help").code());
    }

    @Test
    void whySaysEveryReason() {
        FixtureUmbrella umbrella = LocalEngine.chain(tmp);
        LocalEngine engine = new LocalEngine();

        Ran preview = sluice(engine, umbrella, "preview", "--module", "a", "--no-dependents");
        Ran why = sluice(engine, umbrella, "why", "--module", "a", "--no-dependents");

        assertEquals(Main.OK, why.code(), why.toString());
        assertEquals(Main.OK, preview.code(), preview.toString());
        assertTrue(why.out().contains("a 1.0.0 -> 1.0.1  ("), why.toString());
        assertFalse(preview.out().contains("a 1.0.0 -> 1.0.1  ("), preview.toString());
    }

    @Test
    void aliasFlagEqualsModuleFlag() {
        FixtureUmbrella umbrella = LocalEngine.chain(tmp);
        LocalEngine.config(umbrella, "modules:\n  b: {alias: bee}\n");
        LocalEngine engine = new LocalEngine();

        Ran byModule = sluice(engine, umbrella, "why", "--module", "a", "--module", "b=minor", "--module", "c");
        Ran byAlias = sluice(engine, umbrella, "why", "--module", "a", "--bee", "minor", "--module", "c");

        assertEquals(Main.OK, byModule.code(), byModule.toString());
        assertEquals(byModule, byAlias);
        assertTrue(byAlias.out().contains("b 0.1.0 -> 0.2.0"), byAlias.toString());
    }

    @Test
    void aliasesExpandBeforeParsing() {
        FixtureUmbrella umbrella = LocalEngine.chain(tmp);
        LocalEngine.config(umbrella, "modules:\n  b: {alias: bee}\n  c: {alias: force}\n");
        String root = umbrella.root().toString();

        assertEquals(List.of("release", "--umbrella", root, "--module", "bee=1.2.0", "--force", "--module", "bee"),
                Aliases.expand(List.of("release", "--umbrella", root, "--bee", "1.2.0", "--force", "--bee"),
                        Set.of("--umbrella", "--force")));
        assertEquals(List.of("--umbrella=" + root, "--module", "bee=major"),
                Aliases.expand(List.of("--umbrella=" + root, "--bee=major"), Set.of("--umbrella")));
    }

    @Test
    void graphPrintsOrderAndPins() {
        FixtureUmbrella umbrella = LocalEngine.chain(tmp);

        Ran graph = sluice(new LocalEngine(), "graph", "--umbrella", umbrella.root().toString());

        assertEquals(Main.OK, graph.code(), graph.toString());
        String out = graph.out();
        assertTrue(out.indexOf("1. a") < out.indexOf("2. b") && out.indexOf("2. b") < out.indexOf("3. c"), out);
        assertTrue(out.contains("a -> b") && out.contains("property a.version in b/pom.xml"), out);
        assertEquals(out, sluice(new LocalEngine(), umbrella, "graph").out(), "--umbrella before graph");
    }
}
