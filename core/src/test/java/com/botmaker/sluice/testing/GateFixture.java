package com.botmaker.sluice.testing;

import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.decide.Decider;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.gate.Gates;
import com.botmaker.sluice.layout.UmbrellaLayout;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** What a gate's test needs: a context decided over a fixture umbrella, and the verdicts of one gate. */
public final class GateFixture {

    private GateFixture() {
    }

    /** The context of a preview that asks for {@code modules} (each {@code NAME}, {@code NAME=LEVEL}…). */
    public static GateContext context(FixtureUmbrella umbrella, Config config, boolean force, String... modules) {
        Graph graph = Graph.build(UmbrellaLayout.of(umbrella.root()), config);
        return new GateContext(umbrella.root(), graph,
                Decider.decide(graph, config, Requests.parse(List.of(modules), Optional.empty()), false),
                config, new Runner(true, line -> { }), force);
    }

    /** What {@code gate} answers in a preview without {@code --force}. */
    public static List<GateVerdict> check(Gate gate, FixtureUmbrella umbrella, Config config, String... modules) {
        return Gates.run(context(umbrella, config, false, modules), List.of(gate));
    }

    /** The one verdict of a run that must give exactly one. */
    public static GateVerdict only(List<GateVerdict> verdicts) {
        if (verdicts.size() != 1) {
            throw new AssertionError("expected one verdict, got " + said(verdicts));
        }
        return verdicts.getFirst();
    }

    /** Verdicts as a failed assertion shows them. */
    public static String said(List<GateVerdict> verdicts) {
        return verdicts.stream().map(v -> v.status() + " " + v.line() + " | " + v.refusal())
                .collect(Collectors.joining("\n", "\n", ""));
    }

    /** {@code argv} as a YAML list, each argument quoted so a path or a shell character stays itself. */
    public static String yamlList(List<String> argv) {
        return argv.stream().map(a -> "'" + a.replace("'", "''") + "'").collect(Collectors.joining(", ", "[", "]"));
    }

    /** A command that runs {@link Probe} with this test run's own java. */
    public static List<String> probe(Path record, int exit, String... arguments) {
        List<String> argv = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(),
                record.toString(), Integer.toString(exit)));
        argv.addAll(List.of(arguments));
        return argv;
    }

    /** A publish target that answers what a test says. */
    public record FixedTarget(Spelling spelling, Optional<String> repository, Served served)
            implements PublishTarget {

        @Override
        public String name() {
            return "fixture";
        }

        @Override
        public boolean publishes() {
            return true;
        }

        @Override
        public Served serves(Coordinate coordinate, String version) {
            return served;
        }
    }
}
