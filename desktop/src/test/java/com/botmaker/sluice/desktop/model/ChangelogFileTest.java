package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.desktop.Fixtures;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogFileTest {

    @TempDir
    Path tmp;

    @Test
    void aModuleWithoutAChangelogSaysSo() {
        Umbrella umbrella = Fixtures.open(Fixtures.pinned(tmp));

        ChangelogFile file = ChangelogFile.read(umbrella.module("a").orElseThrow(), umbrella.config().tagPattern());

        assertEquals("no changelog", file.state());
        assertEquals("1.0.0", file.newest().orElseThrow().toString());
        assertEquals(1, file.commits().size(), file.commits().toString());
    }

    @Test
    void notesUnderUnreleasedAreNotesAndTheNewestSectionIsShown() {
        FixtureUmbrella fixture = Fixtures.pinned(tmp).commit("a", "CHANGELOG.md", """
                # Changelog

                ## [Unreleased]

                - A new thing.

                ## [1.0.0]

                - The first.
                """);
        Umbrella umbrella = Fixtures.open(fixture);

        ChangelogFile file = ChangelogFile.read(umbrella.module("a").orElseThrow(), umbrella.config().tagPattern());

        assertTrue(file.hasNotes());
        assertEquals("notes", file.state());
        assertTrue(file.lastSection().orElseThrow().contains("The first."), file.lastSection().toString());
    }

    @Test
    void saveAndCommitCommitsTheFileAlone() throws Exception {
        FixtureUmbrella fixture = Fixtures.pinned(tmp);
        Umbrella umbrella = Fixtures.open(fixture);
        Files.writeString(fixture.dir("a").resolve("other.txt"), "not this\n");
        ChangelogFile file = ChangelogFile.read(umbrella.module("a").orElseThrow(), umbrella.config().tagPattern());

        file.save("## [Unreleased]\n\n- Written here.\n");
        file.commit("a: changelog");

        String shown = FixtureUmbrella.git(fixture.dir("a"), "show", "--stat", "--format=%s", "HEAD").out();
        assertTrue(shown.startsWith("a: changelog"), shown);
        assertTrue(shown.contains("CHANGELOG.md"), shown);
        assertFalse(shown.contains("other.txt"), shown);
        assertTrue(ChangelogFile.read(umbrella.module("a").orElseThrow(), umbrella.config().tagPattern()).hasNotes());
    }
}
