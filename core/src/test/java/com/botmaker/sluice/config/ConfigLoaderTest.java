package com.botmaker.sluice.config;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.config.Config.Command.Line;
import com.botmaker.sluice.config.Config.Place;
import com.botmaker.sluice.config.Config.StepPoint;
import com.botmaker.sluice.config.Config.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void checkFindsEveryProblemWithItsLine() {
        String text = """
                tagg: x
                branch: [main]
                modules:
                  a:
                    alais: x
                  b:
                    kind: wrong
                edges:
                  - from: a
                    to: b
                """;
        List<ConfigProblem> problems = ConfigLoader.check(text, "sluice.yml");
        assertEquals(List.of("tagg", "branch", "modules.a.alais", "modules.b.kind", "edges[0]"),
                problems.stream().map(ConfigProblem::path).toList(), problems.toString());
        assertEquals(List.of(1, 2, 5, 7, 9), problems.stream().map(ConfigProblem::line).toList());
        assertTrue(problems.getLast().message().contains("needs why"), problems.getLast().message());
        // parse refuses one of them, in the same words.
        assertEquals(problems.getFirst().message(),
                assertThrows(Refusal.class, () -> ConfigLoader.parse(text, "sluice.yml")).getMessage());
    }

    @Test
    void checkOfAGoodFileIsEmpty() {
        assertEquals(List.of(), ConfigLoader.check("branch: main\nmodules:\n  a:\n", "sluice.yml"));
    }

    @Test
    void checkSaysWhereTheYamlBreaks() {
        List<ConfigProblem> problems = ConfigLoader.check("branch: main\nmodules: [a\n", "sluice.yml");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("not valid YAML"), problems.toString());
        assertTrue(problems.getFirst().line() > 0, problems.toString());
    }

    @Test
    void absentFileGivesDefaults(@TempDir Path tmp) {
        Config config = ConfigLoader.load(tmp);

        assertEquals(new Target.None(), config.target());
        assertEquals("main", config.branch());
        assertEquals("v{version}", config.tagPattern());
        assertEquals(List.of("**/*.md", "docs/**"), config.relevanceIgnore());
        assertTrue(config.modules().isEmpty());
    }

    @Test
    void unknownKeyRefused() {
        Refusal top = assertThrows(Refusal.class, () -> ConfigLoader.parse("tagg: v{version}\n", "t"));
        assertTrue(top.getMessage().contains("tagg"), top.getMessage());

        Refusal nested = assertThrows(Refusal.class,
                () -> ConfigLoader.parse("modules:\n  a:\n    alais: x\n", "t"));
        assertTrue(nested.getMessage().contains("modules.a.alais"), nested.getMessage());
    }

    @Test
    void readsEveryKey() {
        Config config = ConfigLoader.parse("""
                target: jitpack
                branch: trunk
                tag: release-{version}
                relevance: {ignore: ["*.txt"]}
                modules:
                  app: {alias: x, kind: tag-only, place: {after: lib}, target: {maven: "https://repo.example"}}
                text-pins:
                  - {in: app/V.java, pins: lib, match: 'v(.+)', spelled: tag}
                edges:
                  - {from: lib, to: app, why: calls it}
                after-release:
                  app: {lib.version: x}
                changelog: true
                gates:
                  - {name: lint, modules: [app], run: [make, lint], skip-exit: 77}
                steps:
                  - {at: after-tag, run: {linux: "echo hi", windows: [cmd, /c, echo, hi]}}
                """, "t");

        assertEquals(new Target.Jitpack(), config.target());
        assertEquals("trunk", config.branch());
        assertEquals("release-{version}", config.tagPattern());
        assertEquals(List.of("*.txt"), config.relevanceIgnore());
        Config.ModuleConfig app = config.module("app");
        assertEquals(Optional.of("x"), app.alias());
        assertTrue(app.tagOnly());
        assertEquals(Optional.of(new Place.After("lib")), app.place());
        assertEquals(Optional.of(new Target.Maven("https://repo.example")), app.target());
        assertEquals(Spelling.TAG, config.textPins().getFirst().spelled());
        assertEquals("calls it", config.edges().getFirst().why());
        assertEquals(Map.of("app", Map.of("lib.version", "x")), config.afterRelease());
        assertTrue(config.changelog().enabled());
        assertEquals(Optional.of(77), config.gates().getFirst().skipExit());
        assertEquals(Optional.of(new Line.Args(List.of("make", "lint"))), config.gates().getFirst().run().linux());
        assertEquals(StepPoint.AFTER_TAG, config.steps().getFirst().at());
        assertEquals(Optional.of(new Line.Shell("echo hi")), config.steps().getFirst().run().linux());
    }

    @Test
    void jitpackNamesTheMavenItsBuildsRun() {
        assertEquals(new Target.Jitpack(Optional.of("3.9.9")),
                ConfigLoader.parse("target: {jitpack: {maven: '3.9.9'}}\n", "t").target());
        Refusal both = assertThrows(Refusal.class,
                () -> ConfigLoader.parse("target: {jitpack: {}, maven: x}\n", "t"));
        assertTrue(both.getMessage().contains("target must be"), both.getMessage());
        Refusal notAVersion = assertThrows(Refusal.class,
                () -> ConfigLoader.parse("target: {jitpack: {maven: latest}}\n", "t"));
        assertTrue(notAVersion.getMessage().contains("target.jitpack.maven must be a Maven version"),
                notAVersion.getMessage());
    }
}
