package com.botmaker.sluice.execute;

import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Module;

/** What a release tells whoever shows it, a terminal or a window, as it happens. */
public sealed interface Event {

    /** What the run releases. */
    record Decided(Plan plan) implements Event {
    }

    /** One gate's answer about one thing it checked. */
    record Verdict(GateVerdict verdict) implements Event {
    }

    /** {@code module} has reached {@code stage}. */
    record Reached(Module module, Stage stage) implements Event {
    }

    /** One line of narration, or a command echoed. */
    record Line(String text) implements Event {
    }
}
