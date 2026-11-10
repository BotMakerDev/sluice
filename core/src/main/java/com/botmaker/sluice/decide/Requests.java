package com.botmaker.sluice.decide;

import com.botmaker.sluice.Level;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a person asked to release.
 *
 * @param all   the level every changed module is released at ({@code --all minor})
 * @param named the modules asked for by name; one named wins over {@code all} for its module
 */
public record Requests(Optional<Level> all, List<Request> named) {

    public Requests {
        named = List.copyOf(named);
    }

    /**
     * The requests the command line spells: each of {@code moduleArgs} is {@code NAME}, {@code NAME=LEVEL} or
     * {@code NAME=VERSION}.
     */
    public static Requests parse(List<String> moduleArgs, Optional<Level> all) {
        List<Request> named = new ArrayList<>();
        for (String arg : moduleArgs) {
            int equals = arg.indexOf('=');
            String name = (equals < 0 ? arg : arg.substring(0, equals)).strip();
            if (name.isEmpty()) {
                throw new Refusal("\"" + arg + "\" names no module: write NAME, NAME=LEVEL or NAME=VERSION");
            }
            if (equals < 0) {
                named.add(new Request(name, Optional.empty(), Optional.empty()));
                continue;
            }
            String value = arg.substring(equals + 1).strip();
            Optional<Level> level = Level.of(value);
            Optional<Version> exact = level.isPresent() ? Optional.empty() : Version.parse(value);
            if (level.isEmpty() && exact.isEmpty()) {
                throw new Refusal(name + "=" + value + ": \"" + value + "\" is neither a version (1.2.3) nor one of "
                        + Arrays.stream(Level.values()).map(Level::spelling).collect(Collectors.joining(", ")));
            }
            named.add(new Request(name, exact, level));
        }
        return new Requests(all, named);
    }

    /** Each named request by the module it means, an unknown name or a module named twice refused. */
    public Map<Module, Request> resolve(Graph graph) {
        Map<Module, Request> byModule = new LinkedHashMap<>();
        for (Request request : named) {
            Module module = graph.module(request.name()).orElseThrow(() -> unknown(graph, request.name()));
            Request other = byModule.putIfAbsent(module, request);
            if (other != null) {
                throw new Refusal(module + " is requested twice"
                        + (other.name().equals(request.name()) ? "" : ", as " + other.name() + " and as "
                        + request.name()));
            }
        }
        return byModule;
    }

    private static Refusal unknown(Graph graph, String word) {
        Set<String> words = new LinkedHashSet<>();
        for (Module module : graph.modules()) {
            words.add(module.name());
            module.alias().ifPresent(words::add);
            module.coordinate().ifPresent(c -> words.add(c.artifactId()));
        }
        Optional<String> closest = words.stream().min(Comparator.comparingInt(w -> distance(word, w)));
        return new Refusal("\"" + word + "\" is no module"
                + closest.map(c -> "; did you mean \"" + c + "\"?").orElse("")
                + " (modules: " + graph.modules().stream().map(Module::name).collect(Collectors.joining(", ")) + ")");
    }

    /** The edit distance between two words, case ignored. */
    private static int distance(String a, String b) {
        String from = a.toLowerCase();
        String to = b.toLowerCase();
        int[] previous = new int[to.length() + 1];
        for (int j = 0; j <= to.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= from.length(); i++) {
            int[] current = new int[to.length() + 1];
            current[0] = i;
            for (int j = 1; j <= to.length(); j++) {
                int swap = previous[j - 1] + (from.charAt(i - 1) == to.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(swap, Math.min(previous[j], current[j - 1]) + 1);
            }
            previous = current;
        }
        return previous[to.length()];
    }
}
