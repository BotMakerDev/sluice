package com.botmaker.sluice.gate;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Every pin a tag would carry on an upstream outside this release names a release its publish target serves.
 * A pin on an upstream inside the release is the release's own wait; this is the one nothing else checks, and a
 * tag built against a release that was never published is broken for good.
 */
public final class PinsResolveGate implements Gate {

    private final Function<Module, PublishTarget> targets;

    public PinsResolveGate(Function<Module, PublishTarget> targets) {
        this.targets = targets;
    }

    @Override
    public String name() {
        return "pins-resolve";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        Map<Module, Version> releasing = ctx.plan().releasing();
        List<GateVerdict> verdicts = new ArrayList<>();
        int served = 0;
        for (Module module : releasing.keySet()) {
            for (Edge edge : ctx.graph().upstreams(module)) {
                Module upstream = edge.upstream();
                if (releasing.containsKey(upstream) || !pinnedInAPom(edge) || upstream.coordinate().isEmpty()) {
                    continue;
                }
                Version newest = ctx.plan().decision(upstream).map(Decision::base).orElse(Version.ZERO);
                if (newest.equals(Version.ZERO)) {
                    verdicts.add(refused(module + " pins " + upstream + ", which has no release to pin",
                            "     Release " + upstream + " with it: add --module " + upstream + "."));
                    continue;
                }
                PublishTarget target = targets.apply(upstream);
                if (!target.publishes()) {
                    continue;
                }
                String pin = target.spelling().spell(newest, ctx.config().tagPattern());
                switch (target.serves(upstream.coordinate().get(), pin)) {
                    case YES -> served++;
                    case UNREACHABLE -> verdicts.add(skipped(module + ": " + target.name()
                            + " did not answer, its pin of " + upstream + " " + pin + " was not checked"));
                    case NO -> verdicts.add(refused(module + " would pin " + upstream + " " + pin + ", which "
                                    + target.name() + " does not serve",
                            "     A tag built against it fails, and a pushed tag cannot be edited.\n"
                                    + "     Release " + upstream + " too, so the tag pins a build that exists: add "
                                    + "--module " + upstream + "."));
                }
            }
        }
        if (served > 0 && verdicts.isEmpty()) {
            verdicts.add(ok("every pin outside this release resolves (" + served + ")"));
        }
        return verdicts;
    }

    /** Whether a pom of the downstream spells the upstream's version: what a build resolves. */
    static boolean pinnedInAPom(Edge edge) {
        return edge.pins().stream().anyMatch(pin -> !(pin instanceof Pin.Text));
    }
}
