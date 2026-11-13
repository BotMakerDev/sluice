package com.botmaker.sluice.desktop.ui;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.desktop.model.ReleaseSpec;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.ReleaseState;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * What the Release tab asks of the library, so a test can answer instead. {@link #REAL} is the library itself:
 * the same calls the command makes of the same flags.
 */
interface ReleaseBackend {

    Release.Preview preview(Path umbrella, ReleaseSpec spec);

    Release.Outcome run(Path umbrella, ReleaseSpec spec, BooleanSupplier stop, Consumer<Event> events);

    Release.Outcome resume(Path umbrella, ReleaseSpec spec, BooleanSupplier stop, Consumer<Event> events);

    /** The newest run's state when it did not finish, so a run of it can go on. */
    Optional<ReleaseState> resumable(Path umbrella);

    ReleaseBackend REAL = new ReleaseBackend() {
        @Override
        public Release.Preview preview(Path umbrella, ReleaseSpec spec) {
            return Release.preview(umbrella, spec.requests(), spec.options(false));
        }

        @Override
        public Release.Outcome run(Path umbrella, ReleaseSpec spec, BooleanSupplier stop, Consumer<Event> events) {
            return Release.run(umbrella, spec.requests(), spec.options(true), stop, events);
        }

        @Override
        public Release.Outcome resume(Path umbrella, ReleaseSpec spec, BooleanSupplier stop,
                                      Consumer<Event> events) {
            return Release.resume(umbrella, spec.options(true), stop, events);
        }

        @Override
        public Optional<ReleaseState> resumable(Path umbrella) {
            try {
                ReleaseState state = Release.state(umbrella, Optional.empty()).state();
                return state.status() == ReleaseState.Status.DONE ? Optional.empty() : Optional.of(state);
            } catch (Refusal e) {
                return Optional.empty();
            }
        }
    };
}
