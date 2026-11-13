package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigDocument;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.desktop.model.ConfigValues.Command;
import com.botmaker.sluice.desktop.model.ConfigValues.Target;
import com.botmaker.sluice.desktop.model.ConfigValues.TargetKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValuesTest {

    private static final String COMMENTED = """
            # The umbrella's release.
            branch: main   # every module's
            tag: "v{version}"

            modules:
              # the first one
              core:
                alias: c
              app:
                kind: tag-only

            gates:
              - name: lint
                run: [tools/lint.sh, --strict]
            """;

    private static ConfigDocument doc() {
        return ConfigDocument.of(COMMENTED, ConfigLoader.FILE);
    }

    @Test
    void aFormEditRoundTripsThroughTheYamlAndEveryCommentStays() {
        ConfigDocument edited = ConfigValues.put(doc(), List.of("branch"), "release");

        assertEquals(COMMENTED.replace("branch: main", "branch: release"), edited.text());
        Config config = edited.config();
        assertEquals("release", config.branch());
        assertEquals(Optional.of("c"), config.module("core").alias());
    }

    @Test
    void aModuleKeyAddedLandsUnderItsModuleAndIsReadBack() {
        ConfigDocument edited = ConfigValues.put(doc(), List.of("modules", "app", "alias"), "the-app");

        assertTrue(edited.text().contains("# the first one"), edited.text());
        assertEquals(Optional.of("the-app"), edited.config().module("app").alias());
        assertEquals(Config.ModuleConfig.Kind.TAG_ONLY, edited.config().module("app").kind());
    }

    @Test
    void aClearedFieldTakesItsKeyAwayAndTheMapsItLeavesEmpty() {
        ConfigDocument noAlias = ConfigValues.put(doc(), List.of("modules", "core", "alias"), "");
        assertFalse(noAlias.text().contains("core:"), noAlias.text());
        assertTrue(noAlias.text().contains("app:"), noAlias.text());

        ConfigDocument noModules = ConfigValues.put(noAlias, List.of("modules", "app", "kind"), null);
        assertFalse(noModules.text().contains("modules"), noModules.text());
        assertTrue(noModules.problems().isEmpty(), noModules.problems().toString());
    }

    @Test
    void anEntryJustAddedStaysWhenItsLastFieldIsCleared() {
        ConfigDocument added = doc().set(List.of("gates", 1), Map.of("name", "x"));
        ConfigDocument cleared = ConfigValues.put(added, List.of("gates", 1, "name"), "");

        assertEquals(2, ((List<?>) cleared.get(List.of("gates")).orElseThrow()).size(), cleared.text());
    }

    @Test
    void clearingTheChangelogsLastOptionLeavesItOn() {
        ConfigDocument withExtractor = ConfigDocument.of("changelog:\n  extractor: [tools/x.sh]\n", ConfigLoader.FILE);

        ConfigDocument cleared = ConfigValues.put(withExtractor, List.of("changelog", "extractor"), null);

        assertTrue(cleared.config().changelog().enabled(), cleared.text());
    }

    @Test
    void removingTheLastEntryOfAListTakesTheListAway() {
        ConfigDocument none = ConfigValues.prune(doc().remove(List.of("gates", 0)), List.of("gates"));

        assertFalse(none.text().contains("gates"), none.text());
        assertTrue(none.problems().isEmpty(), none.problems().toString());
    }

    @Test
    void aTargetReadsAndWritesEveryShapeTheLoaderTakes() {
        for (Target target : List.of(new Target(TargetKind.NONE, ""), new Target(TargetKind.JITPACK, ""),
                new Target(TargetKind.JITPACK, "3.9.9"), new Target(TargetKind.MAVEN, "https://repo.example/m2"))) {
            ConfigDocument edited = ConfigValues.put(doc(), List.of("target"), target.yaml());
            assertTrue(edited.problems().isEmpty(), target + ": " + edited.problems());
            assertEquals(Optional.of(target), ConfigValues.target(edited.get(List.of("target"))), edited.text());
        }
        assertEquals(Optional.of(new Target(TargetKind.UNSET, "")), ConfigValues.target(Optional.empty()));
        assertEquals(Optional.empty(), ConfigValues.target(Optional.of("elsewhere")));
    }

    @Test
    void aPlaceIsAWordOrAfterAModule() {
        assertEquals(Map.of("after", "core"), ConfigValues.placeYaml("after core"));
        assertEquals("first", ConfigValues.placeYaml("first"));
        assertEquals("after core", ConfigValues.place(Optional.of(Map.of("after", "core"))));
    }

    @Test
    void aCommandIsOneValueWhenBothSystemsRunTheSameAndArgumentsStayArguments() {
        Command read = ConfigValues.command(doc().get(List.of("gates", 0, "run")));
        assertEquals(new Command("[tools/lint.sh, --strict]", "[tools/lint.sh, --strict]"), read);
        assertEquals(List.of("tools/lint.sh", "--strict"), read.yaml());

        assertEquals("make check", new Command("make check", "make check").yaml());
        assertEquals(Map.of("linux", "make check", "windows", List.of("check.cmd", "a b")),
                new Command("make check", "[check.cmd, 'a b']").yaml());
        assertEquals(null, new Command(" ", "").yaml());
    }

    @Test
    void aListFieldIsCommaSeparated() {
        assertEquals(List.of("a", "b"), ConfigValues.list(" a, ,b "));
        assertEquals("a, b", ConfigValues.listText(Optional.of(List.of("a", "b"))));
        assertEquals(3, ConfigValues.integer(" 3 "));
        assertEquals("three", ConfigValues.integer("three"));
    }
}
