package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.Config.Place;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The order modules are tagged in: every upstream before its downstreams, ties broken by layout order, and the
 * configuration's {@code place} for what the graph leaves free.
 */
public final class TagOrder {

    private TagOrder() {
    }

    /** Every module of {@code graph}, in the order a release tags them. */
    public static List<Module> of(Graph graph, Config config) {
        Map<Module, Place> placed = new LinkedHashMap<>();
        for (Module module : graph.modules()) {
            config.module(module.name()).place().ifPresent(p -> placed.put(module, p));
        }
        List<Module> free = graph.modules().stream().filter(m -> !placed.containsKey(m)).toList();

        List<Module> order = new ArrayList<>();
        order.addAll(topological(graph, placedAs(placed, Place.First.class)));
        order.addAll(topological(graph, free));
        order.addAll(topological(graph, placedAs(placed, Place.Last.class)));
        insertAfters(graph, placed, order);

        for (Edge edge : graph.edges()) {
            if (order.indexOf(edge.upstream()) > order.indexOf(edge.downstream())) {
                throw new Refusal(edge.downstream() + " is placed before " + edge.upstream()
                        + ", which it depends on (" + edge.why() + ")");
            }
        }
        return List.copyOf(order);
    }

    private static List<Module> placedAs(Map<Module, Place> placed, Class<? extends Place> kind) {
        return placed.entrySet().stream().filter(e -> kind.isInstance(e.getValue())).map(Map.Entry::getKey).toList();
    }

    /** Kahn's algorithm, always taking the ready module first in layout order. */
    private static List<Module> topological(Graph graph, List<Module> modules) {
        Set<Module> left = new LinkedHashSet<>(modules);
        List<Module> order = new ArrayList<>();
        while (!left.isEmpty()) {
            Optional<Module> ready = left.stream()
                    .filter(m -> graph.upstreams(m).stream().noneMatch(e -> left.contains(e.upstream())))
                    .findFirst();
            if (ready.isEmpty()) {
                throw new Refusal("the modules depend on each other in a cycle: " + cycle(graph, left));
            }
            order.add(ready.get());
            left.remove(ready.get());
        }
        return order;
    }

    /** One cycle among {@code left}, written upstream first: "a -> b -> a". */
    private static String cycle(Graph graph, Set<Module> left) {
        List<Module> walk = new ArrayList<>();
        Module at = left.iterator().next();
        while (!walk.contains(at)) {
            walk.add(at);
            Module current = at;
            at = graph.upstreams(current).stream().map(Edge::upstream).filter(left::contains)
                    .min(Comparator.comparingInt(graph::index)).orElseThrow();
        }
        List<Module> loop = new ArrayList<>(walk.subList(walk.indexOf(at), walk.size()));
        loop.add(at);
        return loop.reversed().stream().map(Module::name).collect(Collectors.joining(" -> "));
    }

    /** Each {@code after} right behind its module, behind any placed there before it, in layout order. */
    private static void insertAfters(Graph graph, Map<Module, Place> placed, List<Module> order) {
        Map<Module, Module> lastBehind = new HashMap<>();
        List<Module> waiting = new ArrayList<>();
        placed.forEach((m, p) -> {
            if (p instanceof Place.After) {
                waiting.add(m);
            }
        });
        while (!waiting.isEmpty()) {
            boolean moved = false;
            for (Module module : List.copyOf(waiting)) {
                Module target = graph.module(((Place.After) placed.get(module)).module()).orElseThrow();
                Module behind = lastBehind.getOrDefault(target, target);
                int at = order.indexOf(behind);
                if (at >= 0) {
                    order.add(at + 1, module);
                    lastBehind.put(target, module);
                    waiting.remove(module);
                    moved = true;
                }
            }
            if (!moved) {
                throw new Refusal("sluice.yml: place.after goes round in a circle among "
                        + waiting.stream().map(Module::name).toList());
            }
        }
    }
}
