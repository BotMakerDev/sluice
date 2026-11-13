package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.config.ConfigDocument;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.desktop.Fixtures;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphViewTest {

    @TempDir
    Path tmp;

    @Test
    void theTagOrderPutsAnUpstreamFirstWithItsEdges() {
        FixtureUmbrella fixture = Fixtures.pinned(tmp);

        GraphView view = GraphView.of(fixture.root(), ConfigDocument.load(fixture.root()));

        assertTrue(view.refusal().isEmpty(), view.refusal().toString());
        assertEquals(List.of("a", "b"), view.rows().stream().map(GraphView.Row::module).toList());
        assertEquals(List.of("b"), view.rows().getFirst().downstreams());
        assertEquals(List.of("a"), view.rows().get(1).upstreams());
        assertTrue(view.rows().get(1).added().isEmpty(), "a pom's pin is not sluice.yml's");
    }

    @Test
    void anEdgeTheFileAddsIsMarkedAndOrdersTheTags() {
        FixtureUmbrella fixture = Fixtures.pinned(tmp).module("c", pom("c", "0.1.0-SNAPSHOT", ""));
        ConfigDocument doc = ConfigDocument.of("edges:\n  - {from: c, to: a, why: the docs build}\n",
                ConfigLoader.FILE);

        GraphView view = GraphView.of(fixture.root(), doc);

        assertTrue(view.refusal().isEmpty(), view.refusal().toString());
        assertEquals(List.of("c", "a", "b"), view.rows().stream().map(GraphView.Row::module).toList());
        assertEquals(List.of("c (the docs build)"), view.rows().get(1).added());
    }

    @Test
    void anOrderAgainstTheGraphIsRefused() {
        FixtureUmbrella fixture = Fixtures.pinned(tmp);
        ConfigDocument doc = ConfigDocument.of("modules:\n  b: {place: first}\n", ConfigLoader.FILE);

        GraphView view = GraphView.of(fixture.root(), doc);

        assertTrue(view.refusal().orElse("").contains("placed before"), view.toString());
    }

    @Test
    void aNameThatIsNoModuleIsRefusedAndAFileWithProblemsWaits() {
        FixtureUmbrella fixture = Fixtures.pinned(tmp);

        GraphView unknown = GraphView.of(fixture.root(),
                ConfigDocument.of("modules:\n  nowhere: {alias: n}\n", ConfigLoader.FILE));
        assertTrue(unknown.refusal().isPresent(), unknown.toString());

        GraphView waiting = GraphView.of(fixture.root(), ConfigDocument.of("branch: [\n", ConfigLoader.FILE));
        assertTrue(waiting.waiting());
        assertTrue(waiting.rows().isEmpty());
    }
}
