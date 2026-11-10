package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.testing.PlainUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagOrderTest {

    @TempDir
    Path tmp;

    /** {@code names} as Maven modules in layout order, ordered by {@code yaml}. */
    private List<String> order(String yaml, String... names) {
        PlainUmbrella umbrella = new PlainUmbrella(tmp);
        for (String name : names) {
            umbrella.module(name, pom(name, "1.0.0-SNAPSHOT", ""));
        }
        Config config = ConfigLoader.parse(yaml, "t");
        return TagOrder.of(Graph.build(umbrella.layout(), config), config).stream().map(Module::name).toList();
    }

    private static String edge(String from, String to) {
        return "  - {from: " + from + ", to: " + to + ", why: test}\n";
    }

    @Test
    void topologicalTiesByGitmodulesOrder() {
        assertEquals(List.of("y", "z", "x", "w"), order("edges:\n" + edge("z", "x"), "x", "y", "z", "w"));
    }

    @Test
    void placeFirstAndLast() {
        String yaml = "edges:\n" + edge("a", "b")
                + "modules:\n  apk1: {place: last}\n  apk2: {place: first}\n";
        assertEquals(List.of("apk2", "a", "b", "apk1"), order(yaml, "a", "b", "apk1", "apk2"));
    }

    @Test
    void placeAfter() {
        assertEquals(List.of("a", "srv", "b", "c"),
                order("modules:\n  srv: {place: {after: a}}\n", "a", "b", "c", "srv"));
    }

    @Test
    void cycleRefusedNamingIt() {
        Refusal refusal = assertThrows(Refusal.class,
                () -> order("edges:\n" + edge("a", "b") + edge("b", "a"), "a", "b"));
        assertTrue(refusal.getMessage().contains("a -> b -> a"), refusal.getMessage());
    }
}
