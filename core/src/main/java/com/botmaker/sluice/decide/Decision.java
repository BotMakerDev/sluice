package com.botmaker.sluice.decide;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.model.Module;

import java.util.Optional;

/**
 * What a run does with one module.
 *
 * @param base    its newest released version, {@link Version#ZERO} when it was never tagged
 * @param release the version this run cuts; empty when it cuts none
 * @param change  what changed in it since {@code base}
 * @param reason  why it is released or not, in words
 */
public record Decision(Module module, Version base, Optional<Version> release, ChangeKind change, String reason) {

    public boolean releasing() {
        return release.isPresent();
    }
}
