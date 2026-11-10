package com.botmaker.sluice.execute;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.execute.Release.Options;
import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.execute.Release.Wiring;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.testing.FixtureUmbrella;
import com.botmaker.sluice.testing.GateFixture.FixedTarget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.botmaker.sluice.testing.PlainUmbrella.dep;
import static com.botmaker.sluice.testing.PlainUmbrella.pom;

/** Umbrellas a release runs on, and the run itself with local stand-ins for everything outside. */
final class ReleaseFixture {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    static final Options EXECUTE = new Options(true, false, false, true);
    static final Options PREVIEW = new Options(false, false, false, true);

    private ReleaseFixture() {
    }

    /** A target that serves every release, spelled as a tag. */
    static PublishTarget served() {
        return new FixedTarget(Spelling.TAG, Optional.empty(), PublishTarget.Served.YES);
    }

    /** No GitHub, a sleeper that never sleeps, Maven offline, the clock stopped. */
    static Wiring wiring(PublishTarget target) {
        return new Wiring(config -> module -> target, Optional::empty, stop -> duration -> true, List.of("-o"),
                CLOCK);
    }

    /** {@code a} at 1.0.0 then changed; {@code b} pinning it by a property, at 0.1.0 then changed. */
    static FixtureUmbrella pinned(Path tmp) {
        return FixtureUmbrella.create(tmp)
                .module("a", pom("a", "1.0.1-SNAPSHOT", "")).tag("a", "v1.0.0")
                .commit("a", "src/A.java", "class A {}\n").push("a")
                .module("b", pom("b", "0.1.1-SNAPSHOT", "  <properties><a.version>1.0.1-SNAPSHOT</a.version>"
                        + "</properties>\n" + dep("a", "${a.version}"))).tag("b", "v0.1.0")
                .commit("b", "src/B.java", "class B {}\n").push("b");
    }

    /** {@link #pinned} and {@code c} pinning {@code b} the same way. */
    static FixtureUmbrella chain(Path tmp) {
        return pinned(tmp)
                .module("c", pom("c", "0.2.1-SNAPSHOT", "  <properties><b.version>0.1.1-SNAPSHOT</b.version>"
                        + "</properties>\n" + dep("b", "${b.version}"))).tag("c", "v0.2.0")
                .commit("c", "src/C.java", "class C {}\n").push("c");
    }

    static void config(FixtureUmbrella umbrella, String yaml) {
        try {
            Files.writeString(umbrella.root().resolve("sluice.yml"), yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Outcome run(FixtureUmbrella umbrella, Options options, PublishTarget target, List<String> lines,
                       String... modules) {
        return Release.run(umbrella.root(), Requests.parse(List.of(modules), Optional.empty()), options,
                () -> false, event -> said(event, lines), wiring(target));
    }

    static Outcome resume(FixtureUmbrella umbrella, PublishTarget target, List<String> lines) {
        return Release.resume(umbrella.root(), EXECUTE, () -> false, event -> said(event, lines), wiring(target));
    }

    private static void said(Event event, List<String> lines) {
        switch (event) {
            case Event.Line line -> lines.add(line.text());
            case Event.Verdict verdict -> lines.add(verdict.verdict().status() + " " + verdict.verdict().line()
                    + " " + verdict.verdict().refusal());
            default -> {
            }
        }
    }

    /** {@code file} as {@code ref} of module {@code path} has it. */
    static String show(FixtureUmbrella umbrella, String path, String ref, String file) {
        return FixtureUmbrella.git(umbrella.dir(path), "show", ref + ":" + file).out();
    }

    static List<String> tags(FixtureUmbrella umbrella, String path) {
        return FixtureUmbrella.git(umbrella.dir(path), "tag", "--list").lines();
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A target that serves everything except the releases a test says failed to build. */
    static final class Flaky implements PublishTarget {

        final Set<String> failing = new HashSet<>();

        @Override
        public String name() {
            return "flaky";
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
            return Served.YES;
        }

        @Override
        public Optional<String> failure(Coordinate coordinate, String version) {
            return failing.contains(coordinate.artifactId() + ":" + version)
                    ? Optional.of("its build failed") : Optional.empty();
        }
    }

    static List<String> lines() {
        return new ArrayList<>();
    }
}
