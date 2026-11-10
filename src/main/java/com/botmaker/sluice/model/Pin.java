package com.botmaker.sluice.model;

import com.botmaker.sluice.Spelling;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** How a downstream spells an upstream's version, and so how a release moves it. */
public sealed interface Pin {

    /** The file the release edits to move it. */
    Path file();

    /** A {@code <properties>} entry, moved by {@code versions:set-property}, in the pom that declares it. */
    record Property(Path pom, String name) implements Pin {

        @Override
        public Path file() {
            return pom;
        }
    }

    /** A literal {@code <version>} of {@code dep}, moved by {@code versions:use-dep-version}. */
    record Literal(Path pom, Coordinate dep) implements Pin {

        @Override
        public Path file() {
            return pom;
        }
    }

    /** The {@code <parent>} version, moved by {@code versions:update-parent}. */
    record Parent(Path pom) implements Pin {

        @Override
        public Path file() {
            return pom;
        }
    }

    /** {@code match}'s one group in {@code file}, a released version written as {@code spelling} says. */
    record Text(Path file, Pattern match, Spelling spelling) implements Pin {
    }
}
