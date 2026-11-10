package com.botmaker.sluice;

import java.util.Arrays;
import java.util.Optional;

/** How a text names a released version: bare ({@code 1.9.1}) or as its tag ({@code v1.9.1}). */
public enum Spelling {
    VERSION("version"),
    TAG("tag");

    private final String id;

    Spelling(String id) {
        this.id = id;
    }

    /** The word the configuration uses. */
    public String id() {
        return id;
    }

    /** {@code version} as this spelling writes it, the tag made from {@code tagPattern}. */
    public String spell(Version version, String tagPattern) {
        return this == TAG ? version.tag(tagPattern) : version.toString();
    }

    public static Optional<Spelling> fromId(String id) {
        return Arrays.stream(values()).filter(s -> s.id.equals(id)).findFirst();
    }
}
