package com.botmaker.sluice.layout;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UmbrellaLayoutTest {

    private static final String POM = "<project><groupId>g</groupId><artifactId>%s</artifactId>"
            + "<version>1.0.0-SNAPSHOT</version></project>";

    @Test
    void readsSubmodulesInGitmodulesOrder(@TempDir Path tmp) {
        FixtureUmbrella fixture = FixtureUmbrella.create(tmp)
                .module("b", POM.formatted("b"))
                .module("a", POM.formatted("a"))
                .module("docs", null);

        Layout layout = UmbrellaLayout.of(fixture.root());

        assertEquals(List.of("b", "a", "docs"),
                layout.modules().stream().map(ModuleDir::path).toList());
        assertTrue(Files.isRegularFile(layout.modules().getFirst().dir().resolve("pom.xml")));
        assertTrue(layout.modules().getFirst().remote().orElseThrow().endsWith("b.git"));
    }

    @Test
    void umbrellaWithoutGitmodulesRefused(@TempDir Path tmp) {
        Refusal refusal = assertThrows(Refusal.class, () -> UmbrellaLayout.of(tmp));
        assertTrue(refusal.getMessage().contains(".gitmodules"));
    }
}
