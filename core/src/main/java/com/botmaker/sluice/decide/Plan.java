package com.botmaker.sluice.decide;

import com.botmaker.sluice.Version;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a run would release.
 *
 * @param decisions       every module, in tag order
 * @param owedUnrequested each edge from a module released to a dependent that is owed a release and gets none
 */
public record Plan(List<Decision> decisions, List<Edge> owedUnrequested) {

    public Plan {
        decisions = List.copyOf(decisions);
        owedUnrequested = List.copyOf(owedUnrequested);
    }

    /** The modules released and their versions, in tag order. */
    public Map<Module, Version> releasing() {
        Map<Module, Version> releasing = new LinkedHashMap<>();
        decisions.forEach(d -> d.release().ifPresent(v -> releasing.put(d.module(), v)));
        return Collections.unmodifiableMap(releasing);
    }

    public Optional<Decision> decision(Module module) {
        return decisions.stream().filter(d -> d.module().equals(module)).findFirst();
    }
}
