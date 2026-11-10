package com.botmaker.sluice.gate;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The gates of a run, and running them. */
public final class Gates {

    /** How many lines of a command's output a refusal quotes. */
    private static final int QUOTED = 12;

    private Gates() {
    }

    /**
     * Every gate a run has, the cheap ones first and the ones that run Maven last: the built-in ones, then the
     * project's commands, then the build.
     *
     * @param targets where each module publishes
     * @param github  GitHub, when there is a token to ask it with
     */
    public static List<Gate> standard(Config config, Function<Module, PublishTarget> targets,
                                      Optional<GitHub> github) {
        List<Gate> gates = new ArrayList<>(List.of(new CleanGate(), new OwedGate(), new PinsAtMainGate(),
                new TextPinGate(), new ChangelogGate(), new CiGate(github), new PinsResolveGate(targets),
                new TargetGate(targets)));
        config.gates().forEach(command -> gates.add(new CommandGate(command)));
        gates.add(new BuildsAtPinsGate(targets));
        return List.copyOf(gates);
    }

    /**
     * Runs every gate, never stopping at the first refusal: a person repairs everything a run found at once.
     * {@code --force} turns each refusal into a forced verdict and leaves a skipped one as it is.
     */
    public static List<GateVerdict> run(GateContext ctx, List<Gate> gates) {
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Gate gate : gates) {
            List<GateVerdict> found;
            try {
                found = gate.check(ctx);
            } catch (Refusal refusal) {
                found = List.of(gate.refused(refusal.getMessage(), ""));
            }
            found.forEach(verdict -> verdicts.add(ctx.force() ? verdict.forced() : verdict));
        }
        return List.copyOf(verdicts);
    }

    /** Whether any verdict stops the release. */
    public static boolean refused(List<GateVerdict> verdicts) {
        return verdicts.stream().anyMatch(GateVerdict::stops);
    }

    /** The end of a command's output, indented, as a refusal quotes it. */
    static String quoted(String output) {
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        return lines.subList(Math.max(0, lines.size() - QUOTED), lines.size()).stream()
                .map(line -> "     " + line.stripTrailing())
                .collect(Collectors.joining("\n"));
    }
}
