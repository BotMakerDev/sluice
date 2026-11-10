package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.testing.PlainUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphTest {

    @TempDir
    Path tmp;

    @Test
    void pomlessSubmoduleIgnoredUnlessDeclared() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("apk", null)
                .module("site", null);

        Graph plain = Graph.build(umbrella.layout(), Config.defaults());
        assertEquals(List.of("a"), plain.modules().stream().map(Module::name).toList());

        Graph declared = Graph.build(umbrella.layout(),
                ConfigLoader.parse("modules:\n  apk:\n    kind: tag-only\n", "t"));
        assertEquals(List.of("a", "apk"), declared.modules().stream().map(Module::name).toList());
        assertEquals(Module.Kind.TAG_ONLY, declared.module("apk").orElseThrow().kind());

        Refusal refusal = assertThrows(Refusal.class, () -> Graph.build(umbrella.layout(),
                ConfigLoader.parse("modules:\n  site:\n    alias: web\n", "t")));
        assertTrue(refusal.getMessage().contains("tag-only"), refusal.getMessage());
    }

    @Test
    void ignoredSubmoduleLeftOutThoughItHasAPom() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("tool", pom("tool", "2.0.0-SNAPSHOT", ""));

        Graph graph = Graph.build(umbrella.layout(), ConfigLoader.parse("modules:\n  tool:\n    kind: ignored\n", "t"));

        assertEquals(List.of("a"), graph.modules().stream().map(Module::name).toList());
    }

    @Test
    void textPinIsAnEdge() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("b", pom("b", "1.0.0-SNAPSHOT", ""))
                .file("b/src/Fallback.java", "String V = \"1.0.0\";\n");
        Config config = ConfigLoader.parse("""
                text-pins:
                  - in: b/src/Fallback.java
                    pins: a
                    match: 'V = "([^"]+)"'
                """, "t");

        Graph graph = Graph.build(umbrella.layout(), config);

        assertEquals(1, graph.edges().size());
        Edge edge = graph.edges().getFirst();
        assertEquals("a", edge.upstream().name());
        assertEquals("b", edge.downstream().name());
        assertEquals("text pin in b/src/Fallback.java", edge.why());
        Pin.Text pin = assertInstanceOf(Pin.Text.class, edge.pins().getFirst());
        assertEquals(Spelling.VERSION, pin.spelling());
    }

    @Test
    void extraEdgeCarriesWhy() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("a", pom("a", "1.0.0-SNAPSHOT", ""))
                .module("b", pom("b", "1.0.0-SNAPSHOT", ""));
        Config config = ConfigLoader.parse("""
                edges:
                  - from: a
                    to: b
                    why: b calls a in-process
                """, "t");

        Graph graph = Graph.build(umbrella.layout(), config);

        Edge edge = graph.edges().getFirst();
        assertEquals("b calls a in-process", edge.why());
        assertEquals(List.of(), edge.pins());
        assertEquals(List.of(edge), graph.dependents(graph.module("a").orElseThrow()));
    }
}
