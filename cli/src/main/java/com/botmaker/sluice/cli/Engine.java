package com.botmaker.sluice.cli;

import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.Release.Options;
import com.botmaker.sluice.execute.Release.Outcome;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What the command line drives: the release library, or in a test the same library with local stand-ins for
 * GitHub and the publish targets.
 */
public interface Engine {

    /** The release library as it is. */
    Engine REAL = new Engine() {
        @Override
        public Outcome run(Path umbrella, Requests requests, Options options, BooleanSupplier stop,
                           Consumer<Event> events) {
            return Release.run(umbrella, requests, options, stop, events);
        }

        @Override
        public Outcome resume(Path umbrella, Options options, BooleanSupplier stop, Consumer<Event> events) {
            return Release.resume(umbrella, options, stop, events);
        }

        @Override
        public Release.Status syncPins(Path umbrella, boolean execute, Consumer<Event> events) {
            return Release.syncPins(umbrella, execute, events);
        }
    };

    Outcome run(Path umbrella, Requests requests, Options options, BooleanSupplier stop, Consumer<Event> events);

    Outcome resume(Path umbrella, Options options, BooleanSupplier stop, Consumer<Event> events);

    Release.Status syncPins(Path umbrella, boolean execute, Consumer<Event> events);
}
