package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.config.ConfigDocument;
import com.botmaker.sluice.desktop.Fixtures;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileTest {

    @TempDir
    Path tmp;

    @Test
    void theDiffShowsTheChangedLinesWithTheirNeighbours() {
        String before = "a\nb\nc\nd\ne\nf\ng\n";
        String after = "a\nb\nc\nd\nE\nf\ng\nh\n";

        assertEquals("  ⋯\n  c\n  d\n- e\n+ E\n  f\n  g\n+ h\n", ConfigFile.diff(before, after));
        assertEquals("", ConfigFile.diff(before, before));
    }

    @Test
    void saveThenCommitCommitsSluiceYmlAlone() throws Exception {
        FixtureUmbrella fixture = Fixtures.pinned(tmp);
        Files.writeString(fixture.root().resolve("other.txt"), "not this\n");
        ConfigDocument edited = ConfigValues.put(ConfigDocument.load(fixture.root()), List.of("branch"), "release");

        edited.save(fixture.root());
        ConfigFile.commit(fixture.root(), "sluice.yml: release branch");

        String shown = FixtureUmbrella.git(fixture.root(), "show", "--stat", "--format=%s", "HEAD").out();
        assertTrue(shown.startsWith("sluice.yml: release branch"), shown);
        assertTrue(shown.contains("sluice.yml"), shown);
        assertFalse(shown.contains("other.txt"), shown);
        assertEquals(Optional.of("release"), ConfigDocument.load(fixture.root()).get(List.of("branch")));
    }
}
