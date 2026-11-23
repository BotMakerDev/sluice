// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice.gate;

import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * No dependent is left behind: when a module releases, each module that pins it is owed a release, so its pin
 * moves. One left out would keep naming the release before. Only {@code owed: refuse} leaves one out to refuse.
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
        String which = owed.stream().map(edge -> edge.downstream() + " (pins " + edge.upstream() + ", "
                + edge.why() + ")").distinct().collect(Collectors.joining(", "));
        Set<Module> added = new LinkedHashSet<>();
        owed.forEach(edge -> added.add(edge.downstream()));
        String command = "sluice " + ctx.plan().releasing().entrySet().stream()
                .map(e -> flag(e.getKey(), e.getValue().toString())).collect(Collectors.joining(" "))
                + added.stream().map(module -> " " + flag(module, "patch")).collect(Collectors.joining());
        return List.of(refused("owed a release: " + which + "; release them too: " + command
                + ", or add --no-dependents", ""));
    }

    /** How a person asks for {@code module} at {@code value}: by its alias, else by {@code --module}. */
    private static String flag(Module module, String value) {
        return module.alias().map(alias -> "--" + alias + " " + value)
                .orElse("--module " + module.name() + "=" + value);
    }
}
