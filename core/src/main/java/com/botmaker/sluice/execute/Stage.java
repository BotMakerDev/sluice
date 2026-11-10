package com.botmaker.sluice.execute;

/** How far one module's release has gone, in order. The state file records it after every step. */
public enum Stage {
    /** Decided; nothing done yet. */
    DECIDED,
    /** Its release version, pins and changelog committed. */
    RELEASE_COMMITTED,
    /** Its release commit tagged, locally. */
    TAGGED,
    /** Its branch and tag pushed. */
    PUSHED,
    /** Its publish target serves it, or nothing in the run waits for it. */
    PUBLISHED,
    /** Back on its next snapshot, its dependents' pins moved, pushed: done. */
    SNAPSHOT,
    /** Its target failed to build the tag: final, so its next patch repairs it. */
    FAILED
}
