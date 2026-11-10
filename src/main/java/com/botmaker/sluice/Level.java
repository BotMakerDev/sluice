package com.botmaker.sluice;

import java.util.Optional;

/** How far a release moves a version: {@code patch}, {@code minor} or {@code major}. */
public enum Level {

    PATCH, MINOR, MAJOR;

    /** The level a module gets when it is requested without one. */
    public static final Level DEFAULT = PATCH;

    /** The word on the command line: {@code patch}, {@code minor}, {@code major}. */
    public String spelling() {
        return name().toLowerCase();
    }

    /** The level a word spells, or empty. */
    public static Optional<Level> of(String word) {
        for (Level level : values()) {
            if (level.spelling().equals(word)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
