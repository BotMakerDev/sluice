package com.botmaker.sluice.gate;

import com.botmaker.sluice.Changelog;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.gate.GateVerdict.Status;
import com.botmaker.sluice.testing.FixtureUmbrella;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.testing.GateFixture.check;
import static com.botmaker.sluice.testing.GateFixture.only;
import static com.botmaker.sluice.testing.GateFixture.probe;
import static com.botmaker.sluice.testing.GateFixture.yamlList;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ChangelogGateTest {

    private static final Config ON = ConfigLoader.parse("changelog: true\n", "t");

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella;

    @BeforeEach
    void create() {
        umbrella = FixtureUmbrella.create(tmp).module("a", pom("a", "0.1.0-SNAPSHOT", ""));
    }

    @Test
    void offUnlessConfigured() {
        assertEquals(List.of(), check(new ChangelogGate(), umbrella, Config.defaults(), "a"));
    }

    @Test
    void missingSectionRefused() {
        umbrella.commit("a", "CHANGELOG.md", "# Changelog\n\n## [0.0.0] — 2026-01-01\n\n- Old.\n");

        GateVerdict verdict = only(check(new ChangelogGate(), umbrella, ON, "a"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.line().contains("no notes for 0.1.0"), verdict.line());
        assertTrue(verdict.refusal().contains("## [Unreleased]"), verdict.refusal());
    }

    @Test
    void emptyUnreleasedSectionRefused() {
        umbrella.commit("a", "CHANGELOG.md", "# Changelog\n\n## [Unreleased]\n\n## [0.0.0]\n\n- Old.\n");

        assertEquals(Status.REFUSED, only(check(new ChangelogGate(), umbrella, ON, "a")).status());
    }

    @Test
    void headingsWithNothingUnderThemRefused() {
        umbrella.commit("a", "CHANGELOG.md", "# Changelog\n\n## [Unreleased]\n\n### Added\n\n### Changed\n");

        assertEquals(Status.REFUSED, only(check(new ChangelogGate(), umbrella, ON, "a")).status());
    }

    @Test
    void shellExtractorGetsTheVersionAsItsFirstArgument() {
        assumeFalse(Proc.isWindows(), "sh");
        Config config = ConfigLoader.parse("changelog:\n  extractor: 'test \"$1\" = 0.1.0 && echo notes'\n", "t");

        GateVerdict verdict = only(check(new ChangelogGate(), umbrella, config, "a"));

        assertEquals(Status.OK, verdict.status(), verdict.refusal());
    }

    @Test
    void notesUnderUnreleasedAreOk() {
        umbrella.commit("a", "CHANGELOG.md", "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- New.\n");

        assertEquals(Status.OK, only(check(new ChangelogGate(), umbrella, ON, "a")).status());
    }

    @Test
    void extractorCommandUsedWhenConfigured() throws IOException {
        Path record = tmp.resolve("record.txt");
        Config config = ConfigLoader.parse("changelog:\n  extractor: " + yamlList(probe(record, 0)) + "\n", "t");

        GateVerdict verdict = only(check(new ChangelogGate(), umbrella, config, "a"));

        assertEquals(Status.OK, verdict.status(), verdict.refusal());
        assertTrue(Files.readAllLines(record).contains("arg=0.1.0"), Files.readString(record));
    }

    @Test
    void extractorThatFailsRefuses() {
        Config config = ConfigLoader.parse("changelog:\n  extractor: "
                + yamlList(probe(tmp.resolve("record.txt"), 1)) + "\n", "t");

        GateVerdict verdict = only(check(new ChangelogGate(), umbrella, config, "a"));

        assertEquals(Status.REFUSED, verdict.status());
        assertTrue(verdict.refusal().contains("probe ran"), verdict.refusal());
    }

    @Test
    void sectionStopsAtTheNextHeading() {
        String text = "# Changelog\n\n## [Unreleased]\n\n- New.\n\n## [1.0.0] — 2026-01-01\n\n- Old.\n";

        assertEquals(Optional.of("- New."), Changelog.section(text, Changelog.UNRELEASED));
        assertEquals(Optional.of("- Old."), Changelog.section(text, "1.0.0"));
        assertEquals(Optional.empty(), Changelog.section(text, "2.0.0"));
    }
}
