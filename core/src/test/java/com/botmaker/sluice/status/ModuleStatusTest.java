package com.botmaker.sluice.status;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.github.GitHub.Repo;
import com.botmaker.sluice.github.GitHub.Run;
import com.botmaker.sluice.layout.UmbrellaLayout;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.spi.PublishTarget.Served;
import com.botmaker.sluice.status.ModuleStatus.BuildState;
import com.botmaker.sluice.status.ModuleStatus.Ci;
import com.botmaker.sluice.status.ModuleStatus.CiState;
import com.botmaker.sluice.status.ModuleStatus.PinState;
import com.botmaker.sluice.target.NoneTarget;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture.FixedTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleStatusTest {

    private static final Run RUNNING = new Run("ci", "in_progress", "", "https://example.invalid/3", "cccccccccc");
    private static final Run FAILED = new Run("ci", "completed", "failure", "https://example.invalid/2", "bbbbbbbbbb");

    @TempDir
    Path tmp;

    private FixtureUmbrella umbrella(String pinned) {
        return FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", ""))
                .module("b", pom("b", "0.1.0-SNAPSHOT", "  <properties><a.version>" + pinned
                        + "</a.version></properties>\n" + dep("a", "${a.version}")));
    }

    private static Graph graph(FixtureUmbrella umbrella) {
        return Graph.build(UmbrellaLayout.of(umbrella.root()), Config.defaults());
    }

    private static Module module(Graph graph, String name) {
        return graph.module(name).orElseThrow();
    }

    @Test
    void latestTagIsTheNewestVersionTagged() {
        FixtureUmbrella umbrella = umbrella("1.0.0").tag("a", "v1.0.0").tag("a", "v0.9.0").tag("a", "other");
        Graph graph = graph(umbrella);

        assertEquals(Version.parse("1.0.0"), ModuleStatus.latestTag(module(graph, "a"), Config.defaults()));
        assertEquals(Optional.empty(), ModuleStatus.latestTag(module(graph, "b"), Config.defaults()));
    }

    @Test
    void pinsSayWhichNameTheirUpstreamsVersion() {
        Graph stale = graph(umbrella("1.0.0"));

        PinState pin = ModuleStatus.pins(stale, module(stale, "b")).getFirst();
        assertEquals(Optional.of("1.0.0"), pin.held());
        assertEquals(Optional.of("1.0.1-SNAPSHOT"), pin.upstreamVersion());
        assertFalse(pin.current());
        assertEquals(List.of(), ModuleStatus.pins(stale, module(stale, "a")));
    }

    @Test
    void aPinAtTheUpstreamsVersionIsCurrent() {
        FixtureUmbrella umbrella = FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", ""))
                .module("b", pom("b", "0.1.0-SNAPSHOT", "  <properties><a.version>1.0.1-SNAPSHOT"
                        + "</a.version></properties>\n" + dep("a", "${a.version}")));
        Graph graph = graph(umbrella);

        assertTrue(ModuleStatus.pins(graph, module(graph, "b")).getFirst().current());
    }

    private static Ci ci(Module module, Optional<List<Run>> runs) {
        GitHub github = (repo, branch) -> runs;
        return ModuleStatus.ci(module, Config.defaults(), Optional.of(github),
                m -> Optional.of(new Repo("owner", m.name())));
    }

    @Test
    void ciSaysRedGreenOrUnknown() {
        Module a = module(graph(umbrella("1.0.0")), "a");
        Ci red = ci(a, Optional.of(List.of(RUNNING, FAILED)));
        assertEquals(CiState.RED, red.state());
        assertTrue(red.running());
        assertEquals(List.of(FAILED), red.red());
        assertTrue(red.line().contains("ci failed at bbbbbbb on main"), red.line());

        String head = FixtureUmbrella.git(a.dir().dir(), "rev-parse", "HEAD").lines().getFirst();
        Ci green = ci(a, Optional.of(List.of(new Run("ci", "completed", "success", "https://example.invalid/1", head))));
        assertEquals(CiState.GREEN, green.state(), green.line());

        Ci older = ci(a, Optional.of(List.of(
                new Run("ci", "completed", "success", "https://example.invalid/1", "aaaaaaaaaa"))));
        assertEquals(CiState.UNKNOWN, older.state());
        assertTrue(older.line().contains("no finished run built its HEAD"), older.line());

        assertEquals(CiState.UNKNOWN, ci(a, Optional.empty()).state());
        assertEquals(CiState.UNKNOWN, ci(a, Optional.of(List.of(RUNNING))).state());
        assertEquals(CiState.UNKNOWN, ModuleStatus.ci(a, Config.defaults(), Optional.empty()).state());
    }

    @Test
    void buildSaysWhereTheTargetIs() {
        Graph graph = graph(umbrella("1.0.0"));
        Module a = module(graph, "a");
        Version version = Version.parse("1.0.0").orElseThrow();
        Config config = Config.defaults();

        assertEquals(BuildState.SERVED, ModuleStatus.build(a, version, config,
                new FixedTarget(Spelling.TAG, Optional.empty(), Served.YES)).state());
        assertEquals(BuildState.NOT_YET, ModuleStatus.build(a, version, config,
                new FixedTarget(Spelling.TAG, Optional.empty(), Served.NO)).state());
        assertEquals(BuildState.UNREACHABLE, ModuleStatus.build(a, version, config,
                new FixedTarget(Spelling.TAG, Optional.empty(), Served.UNREACHABLE)).state());
        assertEquals(BuildState.NOT_PUBLISHED, ModuleStatus.build(a, version, config, new NoneTarget()).state());

        ModuleStatus.TargetBuild failed = ModuleStatus.build(a, version, config, new Failing());
        assertEquals(BuildState.FAILED, failed.state());
        assertTrue(failed.line().contains("failed to build v1.0.0: the build log ends in an error"), failed.line());
    }

    /** A target whose build of every release failed. */
    private record Failing() implements PublishTarget {

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public boolean publishes() {
            return true;
        }

        @Override
        public Spelling spelling() {
            return Spelling.TAG;
        }

        @Override
        public Optional<String> repository() {
            return Optional.empty();
        }

        @Override
        public Served serves(Coordinate coordinate, String version) {
            return Served.NO;
        }

        @Override
        public Optional<String> failure(Coordinate coordinate, String version) {
            return Optional.of("the build log ends in an error");
        }
    }
}
