package com.botmaker.sluice.spi;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Module;

import java.time.Duration;
import java.util.List;
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

    /**
     * Why it will never serve {@code coordinate} at {@code version}: its build of that release ran and failed.
     * Empty while the build is queued, running or done, when nothing is known, and for a target that builds
     * nothing. Asking may start a build nobody had asked for.
     */
    default Optional<String> failure(Coordinate coordinate, String version) {
        return Optional.empty();
    }

    /** How long a release waits for it to serve what a tag publishes. */
    default Duration patience() {
        return Duration.ofMinutes(10);
    }

    /**
     * Its own checks of a module it publishes, before any tag: what would make its build of the tag fail.
     *
     * @param as the gate the verdicts are said by
     */
    default List<GateVerdict> check(GateContext ctx, Module module, Gate as) {
        return List.of();
    }

    /** What a target answered about one release. */
    enum Served {
        YES,
        NO,
        /** It did not answer: nothing is known. */
        UNREACHABLE
    }
}
