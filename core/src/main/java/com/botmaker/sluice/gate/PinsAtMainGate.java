package com.botmaker.sluice.gate;

import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.status.ModuleStatus;
import com.botmaker.sluice.status.ModuleStatus.PinState;

import java.util.ArrayList;
import java.util.List;

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
            if (edge.upstream().version().isEmpty()) {
                continue;
            }
            for (PinState pin : ModuleStatus.pins(edge)) {
                checked++;
                if (!pin.current()) {
                    verdicts.add(refused(edge.downstream() + " pins " + edge.upstream() + " at "
                                    + pin.held().orElse("nothing readable") + " in " + ctx.said(pin.pin().file())
                                    + ", and " + edge.upstream() + " is at " + pin.upstreamVersion().orElseThrow()
                                    + " on " + branch,
                            "     sluice --sync-pins --execute moves it."));
                }
            }
        }
        if (verdicts.isEmpty() && checked > 0) {
            verdicts.add(ok("every pin names its upstream's version on " + branch + " (" + checked + ")"));
        }
        return verdicts;
    }
}
