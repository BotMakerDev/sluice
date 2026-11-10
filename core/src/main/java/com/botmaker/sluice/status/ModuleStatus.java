package com.botmaker.sluice.status;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.decide.Tags;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.BranchRuns;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.github.GitHub.Run;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PinReader;
import com.botmaker.sluice.spi.PublishTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a module is now, asked one question at a time, as a table of modules shows it: its newest tag, its CI on
 * the release branch, its publish target's build of a release, and its pins against its upstreams. Each asks what
 * the gates ask, read the same way; none changes anything.
 */
public final class ModuleStatus {

    private ModuleStatus() {
    }

    /** The module's newest released version, after fetching its remote's tags; empty when it was never tagged. */
    public static Optional<Version> latestTag(Module module, Config config) {
        return Tags.newest(module, config.tagPattern());
    }

    /** What CI on the release branch says. */
    public enum CiState {
        /** Every workflow's newest finished run passed. */
        GREEN,
        /** A workflow's newest finished run failed. */
        RED,
        /** Nothing is known: no token, no GitHub remote, no answer or no finished run. */
        UNKNOWN
    }

    /**
     * @param running whether a run newer than its workflow's newest finished one is still going
     * @param line    the state said for a person
     * @param red     the newest finished runs that failed
     */
    public record Ci(CiState state, boolean running, String line, List<Run> red) {

        public Ci {
            red = List.copyOf(red);
        }

        static Ci unknown(String line) {
            return new Ci(CiState.UNKNOWN, false, line, List.of());
        }
    }

    /** CI on {@code config}'s branch, asked of {@code github}; {@link CiState#UNKNOWN} without one. */
    public static Ci ci(Module module, Config config, Optional<GitHub> github) {
        return ci(module, config, github, m -> Repo.ofCheckout(m.dir().dir()));
    }

    static Ci ci(Module module, Config config, Optional<GitHub> github, Function<Module, Optional<Repo>> repos) {
        String branch = config.branch();
        if (github.isEmpty()) {
            return Ci.unknown("no GitHub token (GITHUB_TOKEN, or gh auth token)");
        }
        Optional<Repo> repo = repos.apply(module);
        if (repo.isEmpty()) {
            return Ci.unknown("its origin is not on GitHub");
        }
        Optional<List<Run>> runs = github.get().runs(repo.get(), branch);
        if (runs.isEmpty()) {
            return Ci.unknown("GitHub did not answer for " + repo.get());
        }
        BranchRuns read = BranchRuns.of(runs.get(), module.dir().dir());
        String also = read.running() ? " (a newer run is still going)" : "";
        if (read.newestFinished().isEmpty()) {
            return new Ci(CiState.UNKNOWN, read.running(), "no finished run on " + branch + also, List.of());
        }
        List<Run> red = read.red();
        if (!red.isEmpty()) {
            return new Ci(CiState.RED, read.running(), red.size() == 1
                    ? red.getFirst().workflow() + " failed at " + red.getFirst().commit() + " on " + branch + also
                    : red.size() + " workflows failed on " + branch + also, red);
        }
        String at = read.newestFinished().values().iterator().next().commit();
        if (!read.built(Git.line(module.dir().dir(), "rev-parse", "HEAD"))) {
            // As the gate reads it: a green run of an older commit says nothing of HEAD.
            return new Ci(CiState.UNKNOWN, read.running(), "green at " + at + " on " + branch + also
                    + ", but no finished run built its HEAD", List.of());
        }
        return new Ci(CiState.GREEN, read.running(), "green at " + at + " on " + branch + also, List.of());
    }

    /** Where a publish target's build of one release is. */
    public enum BuildState {
        /** It serves the release. */
        SERVED,
        /** It does not serve it yet, and knows no failure. */
        NOT_YET,
        /** Its build of the release ran and failed: it never will serve it. */
        FAILED,
        /** It did not answer. */
        UNREACHABLE,
        /** The module is published nowhere, or has no coordinate to be asked by. */
        NOT_PUBLISHED
    }

    /** @param line the state said for a person */
    public record TargetBuild(BuildState state, String line) {
    }

    /**
     * {@code target}'s build of {@code module} at {@code version}. Asked whether it serves first: asking a target
     * for a failure may start a build nobody had asked for, so a release it serves is not asked.
     */
    public static TargetBuild build(Module module, Version version, Config config, PublishTarget target) {
        Optional<Coordinate> coordinate = module.coordinate();
        if (!target.publishes() || coordinate.isEmpty()) {
            return new TargetBuild(BuildState.NOT_PUBLISHED, "published nowhere");
        }
        String pin = target.spelling().spell(version, config.tagPattern());
        return switch (target.serves(coordinate.get(), pin)) {
            case YES -> new TargetBuild(BuildState.SERVED, target.name() + " serves " + pin);
            case UNREACHABLE -> new TargetBuild(BuildState.UNREACHABLE, target.name() + " did not answer");
            case NO -> target.failure(coordinate.get(), pin)
                    .map(why -> new TargetBuild(BuildState.FAILED, target.name() + " failed to build " + pin
                            + ": " + why))
                    .orElse(new TargetBuild(BuildState.NOT_YET, target.name() + " does not serve " + pin + " yet"));
        };
    }

    /**
     * One pom pin of an upstream.
     *
     * @param held            the version the pin names now; empty when it could not be read
     * @param upstreamVersion the upstream's version in its checkout; empty when it has none
     */
    public record PinState(Edge edge, Pin pin, Optional<String> held, Optional<String> upstreamVersion) {

        /** Whether the pin names the upstream's version: a pin of an upstream with no version is never behind. */
        public boolean current() {
            return upstreamVersion.isEmpty() || held.equals(upstreamVersion);
        }
    }

    /** The pom pins of each of {@code module}'s upstreams, in the graph's order. */
    public static List<PinState> pins(Graph graph, Module module) {
        return graph.upstreams(module).stream().flatMap(edge -> pins(edge).stream()).toList();
    }

    /**
     * The pom pins of {@code edge}. Text pins are left out: a text names a release, never the version on the
     * branch.
     */
    public static List<PinState> pins(Edge edge) {
        Optional<String> upstream = edge.upstream().version();
        return edge.pins().stream().filter(pin -> !(pin instanceof Pin.Text))
                .map(pin -> new PinState(edge, pin, value(pin), upstream)).toList();
    }

    private static Optional<String> value(Pin pin) {
        try {
            return PinReader.value(pin, Files.readString(pin.file()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
