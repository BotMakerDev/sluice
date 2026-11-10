package com.botmaker.sluice.gate;

import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Each publish target's own checks of the modules released to it: what would fail its build of the tag. */
public final class TargetGate implements Gate {

    private final Function<Module, PublishTarget> targets;

    public TargetGate(Function<Module, PublishTarget> targets) {
        this.targets = targets;
    }

    @Override
    public String name() {
        return "target";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Module module : ctx.plan().releasing().keySet()) {
            PublishTarget target = targets.apply(module);
            if (target.publishes()) {
                verdicts.addAll(target.check(ctx, module, this));
            }
        }
        return verdicts;
    }
}
