package com.botmaker.sluice.execute;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PinNotesTest {

    private static final List<String> LINES = List.of("- Built against a 1.0.1 (was 1.0.0).");

    @Test
    void addedAfterTheNotesOfItsOwnChangedSection() {
        String text = "# Changelog\n\n## [Unreleased]\n\n### Changed\n\n- Mine.\n\n### Fixed\n\n- A bug.\n\n"
                + "## [1.0.0]\n\n- Old.\n";

        assertEquals("# Changelog\n\n## [Unreleased]\n\n### Changed\n\n- Mine.\n"
                + "- Built against a 1.0.1 (was 1.0.0).\n\n### Fixed\n\n- A bug.\n\n## [1.0.0]\n\n- Old.\n",
                PinNotes.with(text, LINES));
    }

    @Test
    void aChangedSectionAddedAfterTheOthers() {
        String text = "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- A bug.\n\n## [1.0.0]\n";

        assertEquals("# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- A bug.\n\n### Changed\n\n"
                + "- Built against a 1.0.1 (was 1.0.0).\n\n## [1.0.0]\n", PinNotes.with(text, LINES));
    }

    @Test
    void anUnreleasedSectionAddedAboveTheNewestRelease() {
        assertEquals("# Changelog\n\n## [Unreleased]\n\n### Changed\n\n- Built against a 1.0.1 (was 1.0.0).\n\n"
                + "## [1.0.0]\n", PinNotes.with("# Changelog\n\n## [1.0.0]\n", LINES));
        assertEquals("# Changelog\n\n## [Unreleased]\n\n### Changed\n\n- Built against a 1.0.1 (was 1.0.0).\n",
                PinNotes.with("# Changelog\n", LINES));
    }

    /** The next patch of a release whose target failed moves the same pin: its own notes say so too. */
    @Test
    void aLineOfAnOlderReleaseIsWrittenAgain() {
        String text = "# Changelog\n\n## [0.1.1] — 2026-11-10\n\n### Changed\n\n- Built against a 1.0.1 (was 1.0.0).\n";

        assertEquals("# Changelog\n\n## [Unreleased]\n\n### Changed\n\n- Built against a 1.0.1 (was 1.0.0).\n\n"
                + "## [0.1.1] — 2026-11-10\n\n### Changed\n\n- Built against a 1.0.1 (was 1.0.0).\n",
                PinNotes.with(text, LINES));
    }

    /** A resumed release writes the line once. */
    @Test
    void aLineThereAlreadyStaysOne() {
        String once = PinNotes.with("# Changelog\n", LINES);

        assertEquals(once, PinNotes.with(once, LINES));
    }
}
