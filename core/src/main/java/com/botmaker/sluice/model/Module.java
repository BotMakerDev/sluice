package com.botmaker.sluice.model;

import com.botmaker.sluice.layout.ModuleDir;

import java.util.Optional;

/**
 * One repository the release tags.
 *
 * @param name  its path in the umbrella
 * @param alias the shorter name the configuration gives it
 * @param pom   its root pom; empty for a {@link Kind#TAG_ONLY} module
 */
public record Module(String name, ModuleDir dir, Kind kind, Optional<String> alias, Optional<PomFile> pom) {

    /** What a release does with a module. */
    public enum Kind {
        /** A Maven project: its version moves, its dependents pin it, it publishes. */
        MAVEN("maven"),
        /** A repository only tagged: nothing pins it, nothing publishes it. */
        TAG_ONLY("tag-only");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public Optional<Coordinate> coordinate() {
        return pom.map(PomFile::coordinate);
    }

    /** Its pom's own version as written. */
    public Optional<String> version() {
        return pom.flatMap(PomFile::version);
    }

    /** Whether a person typing {@code word} means this module: its path, its alias or its artifactId. */
    public boolean answersTo(String word) {
        return name.equals(word) || alias.filter(word::equals).isPresent()
                || coordinate().filter(c -> c.artifactId().equals(word)).isPresent();
    }

    @Override
    public String toString() {
        return name;
    }
}
