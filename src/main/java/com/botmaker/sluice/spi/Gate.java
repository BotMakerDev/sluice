package com.botmaker.sluice.spi;

import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.gate.GateVerdict.Status;

import java.util.List;

/**
 * One check a release must pass before its first tag. Internal in v1: the shape every built-in gate has, not yet
 * an API a project can build against; a project's own gates are commands.
 *
 * <p>A gate says what it found and never looks at {@code --force}: the run turns a refusal into a forced verdict.
 */
public interface Gate {

    /** The name a verdict carries: {@code clean}, {@code owed}. */
    String name();

    /** What it found, one verdict per thing checked; empty when it had nothing to check. */
    List<GateVerdict> check(GateContext ctx);

    default GateVerdict ok(String line) {
        return new GateVerdict(name(), Status.OK, line, "");
    }

    /** A check that could not be made, {@code line} saying why. */
    default GateVerdict skipped(String line) {
        return new GateVerdict(name(), Status.SKIPPED, line, "");
    }

    /** A check that failed: {@code line} says what, {@code repair} what would mend it. */
    default GateVerdict refused(String line, String repair) {
        return new GateVerdict(name(), Status.REFUSED, line, repair.isEmpty() ? line : line + "\n" + repair);
    }
}
