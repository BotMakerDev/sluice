// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.update.Install;
import com.botmaker.sluice.update.SelfUpdate;

/**
 * What Check for updates says, in the words {@code sluice update} uses. Every answer is core's
 * ({@link SelfUpdate}); this only phrases it.
 */
public final class UpdateText {

    private UpdateText() {
    }

    /** Where this sluice stands against the latest release. */
    public static String standing(SelfUpdate.Check check) {
        String installed = check.installed().map(v -> "sluice " + v).orElse("This development build");
        return installed + "; the latest release is " + check.latest() + ".";
    }

    /** How this install would be updated, or why it is not updated from here. */
    public static String install(SelfUpdate.Check check) {
        return check.install() instanceof Install.Unmanaged(String why) ? "Not updated from here: " + why + "."
                : "Installed as " + check.install().describe() + ".";
    }

    /** The dialog's header: a development build is never called up to date, since it has no version to compare. */
    public static String header(SelfUpdate.Check check) {
        if (check.installed().isEmpty()) {
            return "This is a development build";
        }
        if (check.updatable()) {
            return "sluice " + check.latest() + " is out";
        }
        return check.newer() ? "A newer sluice is out" : "sluice is up to date";
    }

    /** What an update did, and what the user does next. */
    public static String outcome(SelfUpdate.Outcome outcome) {
        return switch (outcome) {
            case SelfUpdate.Outcome.Current current ->
                    "sluice is up to date (latest release " + current.tag() + ").";
            case SelfUpdate.Outcome.Updated updated -> "sluice updated to " + updated.tag() + ": " + updated.how()
                    + ". Close and reopen sluice to run it.";
            case SelfUpdate.Outcome.Later later -> "sluice " + later.tag() + " is downloaded and checked: "
                    + later.how() + ". Close sluice to finish.";
        };
    }
}
