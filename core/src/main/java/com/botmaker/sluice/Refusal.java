// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice;

/** A release that cannot go on, and the one line that says why. Never a bug: a bug is any other exception. */
public final class Refusal extends RuntimeException {

    public Refusal(String why) {
        super(why);
    }
}
