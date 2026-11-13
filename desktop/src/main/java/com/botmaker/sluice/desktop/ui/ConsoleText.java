package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.gate.GateVerdict;

import java.util.ArrayList;
import java.util.List;

/**
 * A run's events as the command prints them, line for line, so the window's output and a terminal's read the
 * same and one can be checked against the other.
 */
final class ConsoleText {

    private ConsoleText() {
    }

    static List<String> event(Event event) {
        return switch (event) {
            case Event.Decided decided -> decided(decided.plan());
            case Event.Verdict verdict -> verdict(verdict.verdict());
            case Event.Reached reached -> List.of(reached.module() + ": " + words(reached.stage().name()));
            case Event.Line line -> List.of(line.text());
        };
    }

    /** A preview as the command's preview prints it: the plan, then each gate. */
    static List<String> preview(Release.Preview preview) {
        List<String> lines = new ArrayList<>(decided(preview.plan()));
        preview.verdicts().forEach(verdict -> lines.addAll(verdict(verdict)));
        return lines;
    }

    static String ended(Release.Status status) {
        return switch (status) {
            case DONE -> "done";
            case PREVIEWED -> "previewed: nothing was written";
            case REFUSED -> "refused: nothing was tagged";
            case STOPPED -> "stopped partway: Resume goes on from here";
        };
    }

    static List<String> decided(Plan plan) {
        List<String> lines = new ArrayList<>(List.of("decided:"));
        for (Decision decision : plan.decisions()) {
            if (decision.releasing()) {
                lines.add("  " + decision.module() + " " + decision.base() + " -> " + decision.release().get());
            }
        }
        return lines;
    }

    static List<String> verdict(GateVerdict verdict) {
        List<String> lines = new ArrayList<>(List.of("[" + verdict.status() + "] " + verdict.gate() + ": "
                + verdict.line()));
        if (verdict.status() == GateVerdict.Status.REFUSED && !verdict.refusal().isBlank()) {
            verdict.refusal().lines().forEach(line -> lines.add("    " + line));
        }
        return lines;
    }

    /** {@code RELEASE_COMMITTED} as {@code release committed}. */
    private static String words(String constant) {
        return constant.toLowerCase().replace('_', ' ');
    }
}
