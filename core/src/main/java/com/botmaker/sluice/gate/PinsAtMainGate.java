package com.botmaker.sluice.gate;

import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PinReader;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On the release branch every pom pin names its upstream's version there. A pin left on an older version makes
 * the module build against something other than what the release would tag beside it. Text pins are not read:
 * a text names a release, never a snapshot.
 */
public final class PinsAtMainGate implements Gate {

    @Override
    public String name() {
        return "pins-at-main";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        String branch = ctx.config().branch();
        List<GateVerdict> verdicts = new ArrayList<>();
        int checked = 0;
        for (Edge edge : ctx.graph().edges()) {
            Optional<String> upstream = edge.upstream().version();
            if (upstream.isEmpty()) {
                continue;
            }
            for (Pin pin : edge.pins()) {
                if (pin instanceof Pin.Text) {
                    continue;
                }
                checked++;
                Optional<String> value = value(pin);
                if (!value.equals(upstream)) {
                    verdicts.add(refused(edge.downstream() + " pins " + edge.upstream() + " at "
                                    + value.orElse("nothing readable") + " in " + ctx.said(pin.file()) + ", and "
                                    + edge.upstream() + " is at " + upstream.get() + " on " + branch,
                            "     sluice --sync-pins --execute moves it."));
                }
            }
        }
        if (verdicts.isEmpty() && checked > 0) {
            verdicts.add(ok("every pin names its upstream's version on " + branch + " (" + checked + ")"));
        }
        return verdicts;
    }

    private static Optional<String> value(Pin pin) {
        try {
            return PinReader.value(pin, Files.readString(pin.file()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
