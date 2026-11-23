// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.decide;

import com.botmaker.sluice.config.Owed;

/** What a run does with a dependent that pins a module it releases, when the dependent was not asked for. */
public enum Dependents {
    /** Released too, at its next patch, so its pin moves to the release. */
    ADD,
    /** Not released: its released versions keep the pin they have, and its branch's pin follows the release. */
    LEAVE,
    /** Listed as owed, for the owed gate to refuse. */
    LIST;

    /** What {@code sluice.yml}'s {@code owed} does when the command line does not say. */
    public static Dependents of(Owed owed) {
        return switch (owed) {
            case RELEASE -> ADD;
            case REFUSE -> LIST;
        };
    }
}
