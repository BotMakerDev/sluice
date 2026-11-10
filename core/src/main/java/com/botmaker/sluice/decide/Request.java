package com.botmaker.sluice.decide;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Version;

import java.util.Optional;

/**
 * One module a person asked to release: {@code sdk}, {@code sdk=minor} or {@code sdk=1.10.0}.
 *
 * @param name  the module as typed: its directory, its artifactId or its alias
 * @param exact the version asked for by number
 * @param level how far to bump otherwise; {@link Level#DEFAULT} when neither is given
 */
public record Request(String name, Optional<Version> exact, Optional<Level> level) {

    public Request {
        if (exact.isPresent() && level.isPresent()) {
            throw new IllegalArgumentException(name + " is requested by a version and by a level");
        }
    }

    /** The level a request without a number bumps by. */
    public Level bump() {
        return level.orElse(Level.DEFAULT);
    }
}
