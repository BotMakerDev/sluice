package com.botmaker.sluice.target;

import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.spi.PublishTarget;

import java.util.Optional;

/** Nowhere: an application nothing resolves, shipped by its own tag's build. */
public final class NoneTarget implements PublishTarget {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean publishes() {
        return false;
    }

    @Override
    public Spelling spelling() {
        return Spelling.VERSION;
    }

    @Override
    public Optional<String> repository() {
        return Optional.empty();
    }

    @Override
    public Served serves(Coordinate coordinate, String version) {
        return Served.NO;
    }
}
