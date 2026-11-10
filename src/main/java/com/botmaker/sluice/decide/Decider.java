package com.botmaker.sluice.decide;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.decide.Changes.Change;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.TagOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decides what a run releases. Modules are decided in tag order, upstreams first, so a module knows which of its
 * upstreams this run releases: each of those owes it a release, since its pin must move.
 *
 * <ul>
 *   <li>Asked for by number: released at that number, changed or not.</li>
 *   <li>Asked for by level, by name or by {@code --all}: released when it changed or is owed; skipped, with the
 *       reason, when a tag would publish what the last one did.</li>
 *   <li>Owed and not asked for: listed as owed, for a gate to refuse; {@code withDependents} releases it at its
 *       next patch instead.</li>
 * </ul>
 */
public final class Decider {

    private static final String SNAPSHOT = "-SNAPSHOT";

    private Decider() {
    }

    /** The plan for {@code requests} over {@code graph}. Reads each module's tags (fetching them) and changes. */
    public static Plan decide(Graph graph, Config config, Requests requests, boolean withDependents) {
        Map<Module, Request> named = requests.resolve(graph);
        Map<Module, Optional<Version>> newest = new HashMap<>();
        Changes changes = new Changes(graph, config,
                m -> newest.computeIfAbsent(m, k -> Tags.newest(k, config.tagPattern())));

        Map<Module, Version> releasing = new LinkedHashMap<>();
        List<Decision> decisions = new ArrayList<>();
        List<Edge> owedUnrequested = new ArrayList<>();
        for (Module module : TagOrder.of(graph, config)) {
            Change change = changes.of(module);
            Version base = newest.get(module).orElse(Version.ZERO);
            List<Edge> owed = graph.upstreams(module).stream()
                    .filter(e -> releasing.containsKey(e.upstream()))
                    .toList();
            Optional<Request> request = Optional.ofNullable(named.get(module))
                    .or(() -> requests.all().map(level -> new Request(module.name(), Optional.empty(),
                            Optional.of(level))));

            Optional<Version> release = Optional.empty();
            String reason;
            if (request.isPresent() && request.get().exact().isPresent()) {
                Version exact = request.get().exact().get();
                if (Tags.known(module, config.tagPattern()).contains(exact)) {
                    throw new Refusal(module + " " + exact + " is already tagged (" + exact.tag(config.tagPattern())
                            + "); its newest is " + base);
                }
                // A release is cut from the branch's head, which holds everything the newest tag does.
                if (exact.compareTo(base) < 0) {
                    throw new Refusal(module + " " + exact + " is below its newest release, " + base);
                }
                release = Optional.of(exact);
                reason = "requested at " + exact;
            } else if (request.isPresent() && (change.kind() == ChangeKind.CHANGED || !owed.isEmpty())) {
                release = Optional.of(next(module, newest.get(module), request.get().bump()));
                reason = change.kind() == ChangeKind.CHANGED ? change.why() : owedBy(owed, releasing);
            } else if (request.isPresent()) {
                reason = change.why() + ": a tag would publish the same artifact";
            } else if (!owed.isEmpty() && withDependents) {
                release = Optional.of(next(module, newest.get(module), Level.PATCH));
                reason = "added, " + owedBy(owed, releasing);
            } else if (!owed.isEmpty()) {
                owedUnrequested.addAll(owed);
                reason = "not requested, " + owedBy(owed, releasing);
            } else {
                reason = "not requested";
            }
            release.ifPresent(v -> releasing.put(module, v));
            decisions.add(new Decision(module, base, release, change.kind(), reason));
        }
        return new Plan(decisions, owedUnrequested);
    }

    /**
     * The version a module releases at {@code level}: its newest tag bumped, or, never tagged, the version its branch
     * has been building ({@code 0.1.0-SNAPSHOT} releases {@code 0.1.0}) when its pom gives one.
     */
    /**
     * The release a level asks for: that level above the newest tag. A module never tagged releases its branch's
     * version, or that level above {@code 0.0.0} when it is higher ({@code major} still means {@code 1.0.0}).
     */
    private static Version next(Module module, Optional<Version> newest, Level level) {
        if (newest.isPresent()) {
            return newest.get().bump(level);
        }
        Version fromZero = Version.ZERO.bump(level);
        return module.version().map(v -> v.endsWith(SNAPSHOT) ? v.substring(0, v.length() - SNAPSHOT.length()) : v)
                .flatMap(Version::parse).filter(v -> v.compareTo(fromZero) > 0)
                .orElse(fromZero);
    }

    private static String owedBy(List<Edge> owed, Map<Module, Version> releasing) {
        return "owed a release: " + owed.stream()
                .map(e -> e.upstream() + " releases " + releasing.get(e.upstream()) + " (" + e.why() + ")")
                .collect(Collectors.joining("; "));
    }
}
