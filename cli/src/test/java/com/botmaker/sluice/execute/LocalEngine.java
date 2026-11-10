package com.botmaker.sluice.execute;

import com.botmaker.sluice.cli.Engine;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.execute.Release.Options;
import com.botmaker.sluice.execute.Release.Outcome;
import com.botmaker.sluice.execute.ReleaseFixture.Flaky;
import com.botmaker.sluice.testing.FixtureUmbrella;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The release library with {@link ReleaseFixture}'s local stand-ins, for a test of the command line. */
public final class LocalEngine implements Engine {

    private final Flaky target = new Flaky();

    /** The releases, as {@code artifactId:tag}, whose build the target says failed. */
    public Set<String> failing() {
        return target.failing;
    }

    /** {@code a} ← {@code b} ← {@code c}, each pinning the one before by a property, each changed. */
    public static FixtureUmbrella chain(Path tmp) {
        return ReleaseFixture.chain(tmp);
    }

    public static void config(FixtureUmbrella umbrella, String yaml) {
        ReleaseFixture.config(umbrella, yaml);
    }

    @Override
    public Outcome run(Path umbrella, Requests requests, Options options, BooleanSupplier stop,
                       Consumer<Event> events) {
        return Release.run(umbrella, requests, options, stop, events, ReleaseFixture.wiring(target));
    }

    @Override
    public Outcome resume(Path umbrella, Options options, BooleanSupplier stop, Consumer<Event> events) {
        return Release.resume(umbrella, options, stop, events, ReleaseFixture.wiring(target));
    }

    @Override
    public Release.Status syncPins(Path umbrella, boolean execute, Consumer<Event> events) {
        return Release.syncPins(umbrella, execute, events, ReleaseFixture.wiring(target));
    }
}
