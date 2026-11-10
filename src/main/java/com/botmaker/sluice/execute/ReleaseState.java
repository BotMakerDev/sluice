package com.botmaker.sluice.execute;

import com.botmaker.sluice.Version;

import java.util.List;
import java.util.Optional;

/**
 * What a run has done, as its state file records it.
 *
 * @param modules each module the run releases, in tag order
 */
public record ReleaseState(Status status, List<Entry> modules) {

    public ReleaseState {
        modules = List.copyOf(modules);
    }

    /** Where a run is. */
    public enum Status {
        /** Under way, or killed under way: resumable. */
        RUNNING,
        /** Every module done. */
        DONE,
        /** Stopped partway: resumable. */
        STOPPED
    }

    /**
     * One module.
     *
     * @param module its path in the umbrella
     * @param detail why it stopped there, when it did
     */
    public record Entry(String module, Version version, Stage stage, String detail) {

        Entry at(Stage next, String why) {
            return new Entry(module, version, next, why);
        }
    }

    public Optional<Entry> entry(String module) {
        return modules.stream().filter(e -> e.module().equals(module)).findFirst();
    }
}
