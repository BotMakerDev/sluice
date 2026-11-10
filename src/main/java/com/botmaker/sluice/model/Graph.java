package com.botmaker.sluice.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.Config.EdgeConfig;
import com.botmaker.sluice.config.Config.ModuleConfig;
import com.botmaker.sluice.config.Config.Place;
import com.botmaker.sluice.config.Config.TextPinConfig;
import com.botmaker.sluice.layout.Layout;
import com.botmaker.sluice.layout.ModuleDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The project's modules and who depends on whom: the poms' edges, the configuration's text pins and extra edges,
 * one {@link Edge} per pair of modules.
 */
public final class Graph {

    private static final String POM = "pom.xml";

    private final Path root;
    private final List<Module> modules;
    private final List<Edge> edges;

    private Graph(Path root, List<Module> modules, List<Edge> edges) {
        this.root = root;
        this.modules = List.copyOf(modules);
        this.edges = List.copyOf(edges);
    }

    /** The graph of the project {@code layout} finds, as {@code config} completes it. */
    public static Graph build(Layout layout, Config config) {
        List<Module> modules = modules(layout, config);
        Graph found = new Graph(layout.root(), modules, List.of());
        found.checkNames(config);

        List<Edge> raw = new ArrayList<>(Pins.of(modules));
        for (TextPinConfig pin : config.textPins()) {
            raw.add(found.textPin(pin));
        }
        for (EdgeConfig edge : config.edges()) {
            Module from = found.named(edge.from(), "edges.from");
            Module to = found.named(edge.to(), "edges.to");
            if (from == to) {
                throw new Refusal("sluice.yml: edges has " + from + " depending on itself");
            }
            raw.add(new Edge(from, to, List.of(), edge.why()));
        }
        return new Graph(layout.root(), modules, found.merge(raw));
    }

    private static List<Module> modules(Layout layout, Config config) {
        Set<String> paths = new LinkedHashSet<>();
        layout.modules().forEach(d -> paths.add(d.path()));
        for (String key : config.modules().keySet()) {
            if (!paths.contains(key)) {
                throw new Refusal("sluice.yml: modules." + key + " is no submodule of the umbrella (submodules: "
                        + String.join(", ", paths) + ")");
            }
        }
        List<Module> modules = new ArrayList<>();
        for (ModuleDir dir : layout.modules()) {
            ModuleConfig settings = config.module(dir.path());
            Path pom = dir.dir().resolve(POM);
            if (settings.tagOnly()) {
                modules.add(new Module(dir.path(), dir, Module.Kind.TAG_ONLY, settings.alias(), Optional.empty()));
            } else if (Files.isRegularFile(pom)) {
                modules.add(new Module(dir.path(), dir, Module.Kind.MAVEN, settings.alias(),
                        Optional.of(PomReader.read(pom))));
            } else if (config.modules().containsKey(dir.path())) {
                throw new Refusal(dir.path() + " has no " + POM + ": sluice.yml gives it settings, so it needs "
                        + "kind: tag-only to be released without one");
            }
        }
        refuseDuplicates(modules);
        return modules;
    }

    private static void refuseDuplicates(List<Module> modules) {
        Map<Coordinate, Module> coordinates = new HashMap<>();
        Map<String, Module> words = new HashMap<>();
        for (Module module : modules) {
            module.coordinate().ifPresent(c -> {
                Module other = coordinates.putIfAbsent(c, module);
                if (other != null) {
                    throw new Refusal(other + " and " + module + " are both " + c);
                }
            });
            Set<String> names = new LinkedHashSet<>(List.of(module.name()));
            module.alias().ifPresent(names::add);
            module.coordinate().ifPresent(c -> names.add(c.artifactId()));
            for (String name : names) {
                Module other = words.putIfAbsent(name, module);
                if (other != null && other != module) {
                    throw new Refusal("\"" + name + "\" names both " + other + " and " + module);
                }
            }
        }
    }

    /** Every module name the configuration writes must name a module. */
    private void checkNames(Config config) {
        config.afterRelease().keySet().forEach(n -> named(n, "after-release"));
        config.gates().forEach(g -> g.modules().forEach(n -> named(n, "gates." + g.name() + ".modules")));
        config.steps().forEach(s -> s.modules().forEach(n -> named(n, "steps." + s.at().id() + ".modules")));
        config.modules().forEach((path, settings) -> settings.place()
                .filter(p -> p instanceof Place.After)
                .ifPresent(p -> named(((Place.After) p).module(), "modules." + path + ".place.after")));
    }

    private Module named(String word, String where) {
        return module(word).orElseThrow(() -> new Refusal("sluice.yml: " + where + " names \"" + word
                + "\", which is no module (modules: " + String.join(", ", modules.stream().map(Module::name).toList())
                + ")"));
    }

    private Edge textPin(TextPinConfig pin) {
        String where = "text-pins " + pin.in();
        Path file = root.resolve(pin.in()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new Refusal("sluice.yml: " + where + ": no such file");
        }
        Pattern match;
        try {
            match = Pattern.compile(pin.match());
        } catch (PatternSyntaxException e) {
            throw new Refusal("sluice.yml: " + where + ": match is not a regular expression ("
                    + e.getDescription() + ")");
        }
        if (match.matcher("").groupCount() != 1) {
            throw new Refusal("sluice.yml: " + where + ": match must have exactly one group, the version");
        }
        Module up = named(pin.pins(), where + " pins");
        Module down = owner(modules, file).orElseThrow(() -> new Refusal("sluice.yml: " + where
                + " is in no module"));
        if (up == down) {
            throw new Refusal("sluice.yml: " + where + " is in " + up + ", the module it pins");
        }
        return new Edge(up, down, List.of(new Pin.Text(file, match, pin.spelled())), "text pin in " + pin.in());
    }

    /** One edge per pair, its pins and reasons joined, ordered by downstream then upstream in layout order. */
    private List<Edge> merge(List<Edge> raw) {
        record Pair(Module up, Module down) {
        }
        Map<Pair, List<Pin>> pins = new LinkedHashMap<>();
        Map<Pair, Set<String>> whys = new LinkedHashMap<>();
        for (Edge edge : raw) {
            Pair pair = new Pair(edge.upstream(), edge.downstream());
            pins.computeIfAbsent(pair, k -> new ArrayList<>()).addAll(edge.pins());
            whys.computeIfAbsent(pair, k -> new LinkedHashSet<>()).add(edge.why());
        }
        return pins.keySet().stream()
                .map(p -> new Edge(p.up(), p.down(), pins.get(p).stream().distinct().toList(),
                        String.join("; ", whys.get(p))))
                .sorted(Comparator.comparingInt((Edge e) -> index(e.downstream()))
                        .thenComparingInt(e -> index(e.upstream())))
                .toList();
    }

    /** The module whose directory holds {@code file}, the deepest when one module sits inside another. */
    static Optional<Module> owner(List<Module> modules, Path file) {
        Path target = file.toAbsolutePath().normalize();
        return modules.stream()
                .filter(m -> target.startsWith(dir(m)))
                .max(Comparator.comparingInt(m -> dir(m).getNameCount()));
    }

    private static Path dir(Module module) {
        return module.dir().dir().toAbsolutePath().normalize();
    }

    /** The umbrella's directory. */
    public Path root() {
        return root;
    }

    /** Every module, in layout order. */
    public List<Module> modules() {
        return modules;
    }

    public List<Edge> edges() {
        return edges;
    }

    /** Where {@code module} sits in layout order: the order ties are broken by. */
    public int index(Module module) {
        return modules.indexOf(module);
    }

    /** The edges into {@code module}: what it depends on. */
    public List<Edge> upstreams(Module module) {
        return edges.stream().filter(e -> e.downstream().equals(module)).toList();
    }

    /** The edges out of {@code module}: what depends on it. */
    public List<Edge> dependents(Module module) {
        return edges.stream().filter(e -> e.upstream().equals(module)).toList();
    }

    /** The module a person means by {@code word}: its path, its alias or its artifactId. */
    public Optional<Module> module(String word) {
        return modules.stream().filter(m -> m.answersTo(word)).findFirst();
    }
}
