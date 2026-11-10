package com.botmaker.sluice.decide;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.testing.PlainUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestsTest {

    @TempDir
    Path tmp;

    private Graph graph() {
        PlainUmbrella umbrella = new PlainUmbrella(tmp)
                .module("repo-sdk", pom("sdk-artifact", "1.0.0-SNAPSHOT", ""))
                .module("repo-studio", pom("studio-artifact", "1.0.0-SNAPSHOT", ""));
        Config config = ConfigLoader.parse("modules:\n  repo-sdk:\n    alias: sdk\n", "t");
        return Graph.build(umbrella.layout(), config);
    }

    @Test
    void aliasDirectoryAndArtifactIdAllResolve() {
        Graph graph = graph();
        Module sdk = graph.module("repo-sdk").orElseThrow();

        for (String word : List.of("sdk", "repo-sdk", "sdk-artifact")) {
            Map<Module, Request> resolved = Requests.parse(List.of(word), Optional.empty()).resolve(graph);
            assertEquals(List.of(sdk), List.copyOf(resolved.keySet()), word);
        }
    }

    @Test
    void readsLevelAndVersion() {
        Requests requests = Requests.parse(List.of("sdk=1.10.0", "repo-studio=minor", "x"), Optional.of(Level.MAJOR));

        assertEquals(Optional.of(Level.MAJOR), requests.all());
        assertEquals(new Request("sdk", Optional.of(new Version(1, 10, 0)), Optional.empty()),
                requests.named().get(0));
        assertEquals(new Request("repo-studio", Optional.empty(), Optional.of(Level.MINOR)), requests.named().get(1));
        assertEquals(Level.PATCH, requests.named().get(2).bump());
    }

    @Test
    void unknownNameRefusedWithClosestMatch() {
        Refusal refusal = assertThrows(Refusal.class,
                () -> Requests.parse(List.of("repo-studoi"), Optional.empty()).resolve(graph()));

        assertTrue(refusal.getMessage().contains("did you mean \"repo-studio\"?"), refusal.getMessage());
    }

    @Test
    void sameModuleTwiceRefused() {
        Refusal refusal = assertThrows(Refusal.class,
                () -> Requests.parse(List.of("sdk", "repo-sdk=minor"), Optional.empty()).resolve(graph()));

        assertTrue(refusal.getMessage().contains("requested twice"), refusal.getMessage());
    }

    @Test
    void badValueRefused() {
        Refusal refusal = assertThrows(Refusal.class, () -> Requests.parse(List.of("sdk=1.x"), Optional.empty()));

        assertTrue(refusal.getMessage().contains("neither a version"), refusal.getMessage());
    }
}
