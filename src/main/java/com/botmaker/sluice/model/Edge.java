package com.botmaker.sluice.model;

import java.util.List;

/**
 * {@code downstream} depends on {@code upstream}: it is tagged after it, and owed a release when it releases.
 *
 * @param pins every spelling of the upstream's version the downstream holds; empty when none is its own to move
 * @param why  the reasons, in words
 */
public record Edge(Module upstream, Module downstream, List<Pin> pins, String why) {

    public Edge {
        pins = List.copyOf(pins);
    }

    /** Whether a pom of the downstream spells the upstream's version: what a build resolves. */
    public boolean pinnedInAPom() {
        return pins.stream().anyMatch(pin -> !(pin instanceof Pin.Text));
    }

    @Override
    public String toString() {
        return upstream + " -> " + downstream + " (" + why + ")";
    }
}
