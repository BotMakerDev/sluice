package com.botmaker.sluice.gate;

import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.util.List;

/**
 * No dependent is left behind: when a module releases, each module that pins it is owed a release, so its pin
 * moves. One left out would keep naming the release before.
 */
public final class OwedGate implements Gate {

    @Override
    public String name() {
        return "owed";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        List<Edge> owed = ctx.plan().owedUnrequested();
        if (owed.isEmpty()) {
            return ctx.plan().releasing().isEmpty() ? List.of() : List.of(ok("no dependent is owed a release"));
        }
        return owed.stream()
                .map(edge -> refused(edge.downstream() + " is owed a release: " + edge.upstream()
                                + " releases, and it depends on it (" + edge.why() + ")",
                        "     Add --module " + edge.downstream() + ", or --with-dependents for every owed one."))
                .toList();
    }
}
