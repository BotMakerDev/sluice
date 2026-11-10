package com.botmaker.sluice.target;

import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.TagOrder;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.target.Waits.Outcome;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitsTest {

    private static final Config JITPACK = ConfigLoader.parse("target: jitpack\n", "t");

    @TempDir
    Path tmp;

    /** {@code b} pins {@code a} by a property; {@code c} pins nothing. */
    private FixtureUmbrella umbrella() {
        return FixtureUmbrella.create(tmp)
                .module("a", pom("a", "0.0.1-SNAPSHOT", ""))
                .module("b", pom("b", "0.0.1-SNAPSHOT",
                        "  <properties><a.version>0.0.1-SNAPSHOT</a.version></properties>\n"
                                + dep("a", "${a.version}")))
                .module("c", pom("c", "0.0.1-SNAPSHOT", ""));
    }

    private Set<Module> owed(FixtureUmbrella umbrella, Config config, String... modules) {
        GateContext ctx = GateFixture.context(umbrella, config, false, modules);
        return Waits.owed(ctx.plan(), ctx.graph(), TagOrder.of(ctx.graph(), config),
                Targets.of(config, new StubHttp(), tmp));
    }

    private static List<String> names(Set<Module> modules) {
        return modules.stream().map(Module::name).toList();
    }

    @Test
    void notOwedWhenNothingLaterResolves() {
        FixtureUmbrella umbrella = umbrella();

        assertEquals(List.of(), names(owed(umbrella, JITPACK, "a", "c")));
        assertEquals(List.of(), names(owed(umbrella, JITPACK, "b")));
    }

    @Test
    void owedWhenALaterModulePinsIt() {
        assertEquals(List.of("a"), names(owed(umbrella(), JITPACK, "a", "b", "c")));
    }

    @Test
    void notOwedWhenItPublishesNowhere() {
        assertEquals(List.of(), names(owed(umbrella(), Config.defaults(), "a", "b")));
    }

    private static final PublishTarget NEVER = new MavenTarget("https://repo.example", new StubHttp());

    @Test
    void timesOutAfterTheTargetsPatience() {
        FixtureUmbrella umbrella = umbrella();
        Module a = GateFixture.context(umbrella, JITPACK, false, "a").graph().module("a").orElseThrow();
        long[] now = {0};
        int[] slept = {0};

        // Each answer takes the server fifty seconds: patience is time, not a number of tries.
        StubHttp slow = new StubHttp() {
            @Override
            public Optional<Integer> head(String url) {
                now[0] += Duration.ofSeconds(50).toNanos();
                return Optional.of(404);
            }
        };

        Waits.Published published = Waits.await(new MavenTarget("https://repo.example", slow), a,
                new Version(0, 0, 1), JITPACK, new Runner(false, line -> { }), duration -> {
                    slept[0]++;
                    now[0] += duration.toNanos();
                    return true;
                }, () -> now[0]);

        // Thirty minutes of patience at a minute a try: 31 answers (the last at 30:50), no sleep after it.
        assertEquals(Outcome.TIMEOUT, published.outcome());
        assertEquals(30, slept[0]);
    }

    @Test
    void stopEndsTheWait() {
        FixtureUmbrella umbrella = umbrella();
        Module a = GateFixture.context(umbrella, JITPACK, false, "a").graph().module("a").orElseThrow();

        assertEquals(Outcome.STOPPED, Waits.await(NEVER, a, new Version(0, 0, 1), JITPACK,
                new Runner(false, line -> { }), duration -> false).outcome());
        assertEquals(Outcome.STOPPED, Waits.await(NEVER, a, new Version(0, 0, 1), JITPACK,
                new Runner(false, line -> { }, () -> true), duration -> true).outcome());
    }

    @Test
    void previewAsksNothing() {
        FixtureUmbrella umbrella = umbrella();
        Module a = GateFixture.context(umbrella, JITPACK, false, "a").graph().module("a").orElseThrow();
        StubHttp http = new StubHttp();
        List<String> said = new ArrayList<>();

        Waits.Published published = Waits.await(new MavenTarget("https://repo.example", http), a,
                new Version(0, 0, 1), JITPACK, new Runner(true, said::add), duration -> true);

        assertEquals(Outcome.PUBLISHED, published.outcome());
        assertEquals(List.of(), http.asked);
        assertTrue(said.getFirst().contains("would wait for maven to serve a 0.0.1"), said.toString());
    }
}
