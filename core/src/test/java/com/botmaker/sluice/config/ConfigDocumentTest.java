package com.botmaker.sluice.config;

import com.botmaker.sluice.config.Config.Place;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDocumentTest {

    /** Comments everywhere a person puts them: above a key, after a value, above a list item, between sections. */
    private static final String COMMENTED = """
            # The umbrella's release.
            target: jitpack   # every module
            branch: main
            tag: "v{version}"

            modules:
              # The app, tagged first.
              app:
                alias: a
                place: {after: lib}
              lib:

            gates:
              # Lints first.
              - name: lint
                run: [make, lint]
              # Then the docs.
              - name: docs
                modules: [app, lib,
                          other]
                run: make docs
            """;

    @Test
    void unEditedSavesByteIdentical(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("sluice.yml"), COMMENTED);
        ConfigDocument.load(tmp).save(tmp);
        assertEquals(COMMENTED, Files.readString(tmp.resolve("sluice.yml")));
    }

    @Test
    void settingAScalarChangesOnlyIt() {
        String edited = doc().set(List.of("modules", "app", "alias"), "app2").text();
        assertEquals(COMMENTED.replace("alias: a\n", "alias: app2\n"), edited);
        String branch = doc().set(List.of("target"), "none").text();
        assertEquals(COMMENTED.replace("target: jitpack   #", "target: none   #"), branch);
    }

    @Test
    void textThatWouldReadAsAnotherTypeIsQuoted() {
        ConfigDocument edited = doc().set(List.of("branch"), "1.10");
        assertEquals(Optional.of("1.10"), edited.get(List.of("branch")));
        assertEquals("1.10", edited.config().branch());
    }

    @Test
    void aMissingKeyIsAddedUnderItsMap() {
        String edited = doc().set(List.of("modules", "app", "kind"), "tag-only").text();
        assertEquals(COMMENTED.replace("place: {after: lib}\n", "place: {after: lib}\n    kind: tag-only\n"),
                edited);
    }

    @Test
    void aKeyIsAddedInsideAFlowMap() {
        ConfigDocument edited = doc().set(List.of("modules", "app", "place"), Map.of("after", "other"));
        assertEquals(Optional.of(new Place.After("other")), edited.config().module("app").place());
        assertTrue(edited.text().contains("place: {after: other}\n"), edited.text());
    }

    @Test
    void anEmptyValueGetsAMap() {
        ConfigDocument edited = doc().set(List.of("modules", "lib", "alias"), "l");
        assertEquals(Optional.of("l"), edited.config().module("lib").alias());
        assertTrue(edited.text().contains("  lib:\n    alias: l\n"), edited.text());
    }

    @Test
    void aMissingSectionIsAddedAtTheEnd() {
        ConfigDocument edited = doc().set(List.of("after-release", "lib", "x.version"), "v");
        assertEquals(Map.of("x.version", "v"), edited.config().afterRelease().get("lib"));
        assertTrue(edited.text().startsWith(COMMENTED), edited.text());
    }

    @Test
    void anItemIsAppendedInBlockForm() {
        ConfigDocument edited = doc().set(List.of("gates", 2), Map.of("name", "test", "run", List.of("make", "test")));
        assertEquals(List.of("lint", "docs", "test"),
                edited.config().gates().stream().map(Config.CommandConfig::name).toList());
        assertTrue(edited.text().startsWith(COMMENTED), edited.text());
        assertTrue(edited.text().contains("\n  - "), edited.text());
    }

    @Test
    void anItemOfAFlowListIsAddedInIt() {
        ConfigDocument edited = doc().set(List.of("gates", 1, "modules", 3), "more");
        assertEquals(List.of("app", "lib", "other", "more"), edited.config().gates().get(1).modules());
    }

    @Test
    void removingAnEntryTakesItsCommentAndLeavesTheRest() {
        String edited = doc().remove(List.of("gates", 0)).text();
        assertEquals(COMMENTED.replace("  # Lints first.\n  - name: lint\n    run: [make, lint]\n", ""), edited);
        String module = doc().remove(List.of("modules", "app")).text();
        assertEquals(COMMENTED.replace("  # The app, tagged first.\n  app:\n    alias: a\n    place: {after: lib}\n",
                ""), module);
    }

    @Test
    void removingTheLastEntryLeavesItEmpty() {
        ConfigDocument edited = doc().remove(List.of("modules", "app", "place"))
                .remove(List.of("modules", "app", "alias"));
        assertEquals(Config.ModuleConfig.NONE, edited.config().module("app"));
        assertTrue(edited.problems().isEmpty(), edited.problems().toString());
    }

    @Test
    void removingAListItemsFirstKeyKeepsTheItem() {
        String text = "gates:\n  - name: a\n    run: x\n    modules: [b]\n";
        assertEquals("gates:\n  - run: x\n    modules: [b]\n",
                ConfigDocument.of(text, "sluice.yml").remove(List.of("gates", 0, "name")).text());
    }

    @Test
    void anItemThatStartsWithADashIsCutWithItsComment() {
        String text = "run:\n  - mvn\n  # quiet\n  - -q\n  - install\n";
        assertEquals("run:\n  - mvn\n  - install\n", ConfigDocument.of(text, "x.yml").remove(List.of("run", 1)).text());
    }

    @Test
    void aDocumentOfCommentsKeepsThem() {
        assertEquals("# only a comment\nbranch: trunk\n",
                ConfigDocument.of("# only a comment\n", "sluice.yml").set(List.of("branch"), "trunk").text());
        assertEquals("# keep\n", ConfigDocument.of("a: 1\n# keep\n", "x.yml").remove(List.of("a")).text());
    }

    @Test
    void removingFromAFlowList() {
        ConfigDocument edited = doc().remove(List.of("gates", 1, "modules", 1));
        assertEquals(List.of("app", "other"), edited.config().gates().get(1).modules());
        ConfigDocument last = doc().remove(List.of("gates", 1, "modules", 2));
        assertEquals(List.of("app", "lib"), last.config().gates().get(1).modules());
    }

    @Test
    void editsKeepEveryComment() {
        ConfigDocument edited = doc().set(List.of("modules", "app", "alias"), "b")
                .set(List.of("gates", 1, "run"), List.of("make", "docs"))
                .set(List.of("branch"), "trunk");
        COMMENTED.lines().filter(line -> line.contains("#")).forEach(comment -> assertTrue(
                edited.text().contains(comment.substring(comment.indexOf('#'))), comment + "\n" + edited.text()));
        assertEquals("trunk", edited.config().branch());
    }

    @Test
    void anEmptyDocumentGetsWhatIsSet() {
        ConfigDocument edited = ConfigDocument.of("", "sluice.yml").set(List.of("modules", "a", "alias"), "x");
        assertEquals(Optional.of("x"), edited.config().module("a").alias());
    }

    @Test
    void getReadsAPath() {
        assertEquals(Optional.of("lint"), doc().get(List.of("gates", 0, "name")));
        assertEquals(Optional.empty(), doc().get(List.of("gates", 7)));
    }

    private static ConfigDocument doc() {
        return ConfigDocument.of(COMMENTED, "sluice.yml");
    }
}
