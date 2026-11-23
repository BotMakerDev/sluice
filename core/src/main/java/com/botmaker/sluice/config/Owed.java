// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.config;

import java.util.Arrays;
import java.util.Optional;

/** {@code owed} in {@code sluice.yml}: what a run does, unless told, with a dependent a release owes. */
public enum Owed {
    /** Release it at its next patch. */
    RELEASE("release"),
    /** Refuse the run until it is asked for. */
    REFUSE("refuse");

    private final String id;

    Owed(String id) {
        this.id = id;
    }

    /** The word the configuration uses. */
    public String id() {
        return id;
    }

    public static Optional<Owed> fromId(String id) {
        return Arrays.stream(values()).filter(o -> o.id.equals(id)).findFirst();
    }
}
