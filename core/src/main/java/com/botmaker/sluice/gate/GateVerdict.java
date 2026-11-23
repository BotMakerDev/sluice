// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.gate;

import java.util.List;

/**
 * What a gate answered about one thing it checked.
 *
 * @param gate    the gate's name
 * @param line    the one line a run prints
 * @param refusal the whole refusal, with what repairs it; empty unless {@link Status#REFUSED}
 */
public record GateVerdict(String gate, Status status, String line, String refusal) {

    /** A gate's four answers. */
    public enum Status {
        /** Checked and passed. */
        OK,
        /** Could not be checked: said, never passed silently, and never lifted by {@code --force}. */
        SKIPPED,
        /** Checked, failed, and {@code --force} was given: still told apart from a pass. */
        FORCED,
        /** Checked and failed: the release stops before its first tag. */
        REFUSED
    }

    /** Whether the release stops here. */
    public boolean stops() {
        return status == Status.REFUSED;
    }

    /**
     * What a refusal says after its {@link #line}, one entry per line, for a printer that has shown the line
     * already; nothing for any other verdict.
     */
    public List<String> repair() {
        if (!stops()) {
            return List.of();
        }
        String rest = refusal.startsWith(line) ? refusal.substring(line.length()) : refusal;
        return (rest.startsWith("\n") ? rest.substring(1) : rest).lines().toList();
    }

    /** This refusal as {@code --force} leaves it; any other verdict unchanged. */
    public GateVerdict forced() {
        return stops() ? new GateVerdict(gate, Status.FORCED, line + " (forced)", "") : this;
    }
}
