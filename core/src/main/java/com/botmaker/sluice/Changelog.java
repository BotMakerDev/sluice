package com.botmaker.sluice;

import java.util.Optional;
import java.util.stream.Collectors;

/** A module's {@code CHANGELOG.md} in the Keep a Changelog shape: one {@code ## [name]} heading per release. */
public final class Changelog {

    public static final String FILE = "CHANGELOG.md";

    /** The section a release stamps with its version. */
    public static final String UNRELEASED = "Unreleased";

    private Changelog() {
    }

    /** Whether a section says anything: a line that is neither blank nor a heading of its own. */
    public static boolean hasNotes(String section) {
        return section.lines().anyMatch(line -> !line.isBlank() && !line.startsWith("#"));
    }

    /**
     * What {@code text} says under {@code ## [name]}, up to the next heading of its level; empty when it has no
     * such heading. Whatever follows the name on the heading's line (a date) is not read.
     */
    public static Optional<String> section(String text, String name) {
        String heading = "## [" + name + "]";
        boolean[] in = {false};
        boolean[] found = {false};
        String body = text.lines().filter(line -> {
            if (line.startsWith("## ")) {
                in[0] = !found[0] && line.startsWith(heading);
                found[0] |= in[0];
                return false;
            }
            return in[0];
        }).collect(Collectors.joining("\n"));
        return found[0] ? Optional.of(body.strip()) : Optional.empty();
    }
}
