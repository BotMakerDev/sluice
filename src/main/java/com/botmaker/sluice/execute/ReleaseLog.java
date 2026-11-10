package com.botmaker.sluice.execute;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.execute.Release.Status;
import com.botmaker.sluice.gate.GateVerdict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A run's log for a person, {@code releases/<base>.md}: what it decided, what each gate said, every line it
 * printed and where each module ended.
 */
final class ReleaseLog {

    private final List<String> decided = new ArrayList<>();
    private final List<String> gates = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    void decided(Plan plan, String tagPattern) {
        for (Decision d : plan.decisions()) {
            decided.add("| " + d.module() + " | " + d.base() + " | "
                    + d.release().map(v -> v.tag(tagPattern)).orElse("—") + " | " + cell(d.reason()) + " |");
        }
    }

    void verdict(GateVerdict verdict) {
        gates.add("- " + verdict.status() + " " + verdict.gate() + ": " + verdict.line());
    }

    void line(String line) {
        lines.add(line);
    }

    void write(Path file, String title, Status status, ReleaseState state) {
        List<String> out = new ArrayList<>(List.of("# " + title, "", "Status: " + status, ""));
        if (!decided.isEmpty()) {
            out.addAll(List.of("## Decided", "", "| Module | Newest | Release | Why |", "|---|---|---|---|"));
            out.addAll(decided);
            out.add("");
        }
        if (!gates.isEmpty()) {
            out.addAll(List.of("## Gates", ""));
            out.addAll(gates);
            out.add("");
        }
        if (!state.modules().isEmpty()) {
            out.addAll(List.of("## Where each module ended", "", "| Module | Version | Stage | Detail |",
                    "|---|---|---|---|"));
            state.modules().forEach(e -> out.add("| " + e.module() + " | " + e.version() + " | " + e.stage()
                    + " | " + cell(e.detail()) + " |"));
            out.add("");
        }
        out.addAll(List.of("## Run", "", "```"));
        out.addAll(lines);
        out.addAll(List.of("```", ""));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", out));
        } catch (IOException e) {
            throw new Refusal(file + ": the release's log could not be written (" + e.getMessage() + ")");
        }
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
