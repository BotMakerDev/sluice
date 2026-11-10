package com.botmaker.sluice.gate;

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

    /** This refusal as {@code --force} leaves it; any other verdict unchanged. */
    public GateVerdict forced() {
        return stops() ? new GateVerdict(gate, Status.FORCED, line + " (forced)", "") : this;
    }
}
