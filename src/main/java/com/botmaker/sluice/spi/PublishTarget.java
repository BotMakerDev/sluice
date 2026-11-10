package com.botmaker.sluice.spi;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.model.Coordinate;

import java.util.Optional;

/** Where a module's release is published, and so where its dependents resolve it from. Internal in v1. */
public interface PublishTarget {

    /** The word the configuration uses for it. */
    String name();

    /** Whether anything resolves a release from it; false for a module published nowhere. */
    boolean publishes();

    /** How a dependent's pin spells a release of a module published here. */
    Spelling spelling();

    /**
     * The repository a dependent's pom must declare to resolve from it; empty when Maven knows it by itself
     * (Central) or nothing resolves from it.
     */
    Optional<String> repository();

    /** Whether it serves {@code coordinate} at {@code version}, the version spelled as {@link #spelling} says. */
    Served serves(Coordinate coordinate, String version);

    /** What a target answered about one release. */
    enum Served {
        YES,
        NO,
        /** It did not answer: nothing is known. */
        UNREACHABLE
    }
}
