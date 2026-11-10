package com.botmaker.sluice.decide;

import java.util.List;

/** What a module's changes since its newest tag amount to. */
public enum ChangeKind {

    /** Nothing moved but what a release itself writes. */
    NONE,
    /** Files changed, every one of them ignored: a tag would publish the same artifact. */
    IGNORED_ONLY,
    /**
     * No file of its own changed, but an upstream was released since its tag: a tag cut now builds against
     * another upstream, so it is another artifact. Its notes are the pins it moves.
     */
    UPSTREAMS,
    /** Something a release publishes changed, or the module was never tagged, or it cannot be told. */
    CHANGED;

    /** Whether a tag cut now would publish another artifact than the newest one. */
    public boolean releasable() {
        return this == CHANGED || this == UPSTREAMS;
    }

    /** The rule over the files a diff lists. */
    public static ChangeKind classify(List<String> changedFiles, Relevance relevance) {
        if (changedFiles.isEmpty()) {
            return NONE;
        }
        return changedFiles.stream().anyMatch(relevance::relevant) ? CHANGED : IGNORED_ONLY;
    }
}
