package com.botmaker.sluice.cli;

import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.execute.ReleaseState;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.model.Edge;

import java.io.PrintWriter;
import java.util.function.Consumer;

/** A release's events as terminal lines. */
final class Console implements Consumer<Event> {

    private final PrintWriter out;
    private final boolean why;

    /** @param why also say why each module is released or left, and which dependents a release owes */
    Console(PrintWriter out, boolean why) {
        this.out = out;
        this.why = why;
    }

    @Override
    public void accept(Event event) {
        switch (event) {
            case Event.Decided decided -> decided(decided.plan());
            case Event.Verdict verdict -> verdict(verdict.verdict());
            case Event.Reached reached -> out.println(reached.module() + ": " + words(reached.stage().name()));
            case Event.Line line -> out.println(line.text());
        }
        out.flush();
    }

    private void decided(Plan plan) {
        out.println("decided:");
        for (Decision decision : plan.decisions()) {
            if (decision.releasing()) {
                out.println("  " + decision.module() + " " + decision.base() + " -> " + decision.release().get()
                        + (why ? "  (" + decision.reason() + ")" : ""));
            } else if (why) {
                out.println("  " + decision.module() + " " + decision.base() + ", not released  ("
                        + decision.reason() + ")");
            }
        }
        if (why) {
            for (Edge edge : plan.owedUnrequested()) {
                out.println("  owed, not requested: " + edge);
            }
        }
    }

    private void verdict(GateVerdict verdict) {
        out.println("[" + verdict.status() + "] " + verdict.gate() + ": " + verdict.line());
        if (verdict.status() == GateVerdict.Status.REFUSED && !verdict.refusal().isBlank()) {
            verdict.refusal().lines().forEach(line -> out.println("    " + line));
        }
    }

    /** The run's last lines: how it ended and where its files are. */
    void ended(Outcome outcome) {
        out.println(switch (outcome.status()) {
            case DONE -> "done";
            case PREVIEWED -> "previewed: nothing was written; --execute releases it";
            case REFUSED -> "refused: nothing was tagged";
            case STOPPED -> "stopped partway: sluice --resume --execute goes on from here";
        });
        outcome.log().ifPresent(log -> out.println("log: " + log));
        outcome.state().ifPresent(state -> out.println("state: " + state));
        out.flush();
    }

    /** A state file, one line per module. */
    void state(ReleaseState state) {
        out.println("status: " + words(state.status().name()));
        for (ReleaseState.Entry entry : state.modules()) {
            out.println("  " + entry.module() + " " + entry.version() + ": " + words(entry.stage().name())
                    + (entry.detail().isEmpty() ? "" : " (" + entry.detail() + ")"));
        }
        out.flush();
    }

    /** {@code RELEASE_COMMITTED} as {@code release committed}. */
    private static String words(String constant) {
        return constant.toLowerCase().replace('_', ' ');
    }
}
