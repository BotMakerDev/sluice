package com.botmaker.sluice.config;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Spelling;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config.ChangelogConfig;
import com.botmaker.sluice.config.Config.Command;
import com.botmaker.sluice.config.Config.Command.Line;
import com.botmaker.sluice.config.Config.CommandConfig;
import com.botmaker.sluice.config.Config.EdgeConfig;
import com.botmaker.sluice.config.Config.ModuleConfig;
import com.botmaker.sluice.config.Config.Place;
import com.botmaker.sluice.config.Config.StepConfig;
import com.botmaker.sluice.config.Config.StepPoint;
import com.botmaker.sluice.config.Config.Target;
import com.botmaker.sluice.config.Config.TextPinConfig;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reads {@code sluice.yml}. Strict: an unknown key, a wrong type or a bad value is refused naming where it is, so a
 * typo is never a setting silently ignored. {@link #parse} refuses the first; {@link #check} finds every one.
 */
public final class ConfigLoader {

    public static final String FILE = "sluice.yml";

    private static final Set<String> TOP = Set.of("target", "branch", "tag", "relevance", "modules", "text-pins",
            "edges", "after-release", "changelog", "gates", "steps", "github-releases");

    private ConfigLoader() {
    }

    /** The configuration of the umbrella at {@code umbrella}: its {@code sluice.yml}, or the defaults without one. */
    public static Config load(Path umbrella) {
        Path file = umbrella.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Config.defaults();
        }
        try {
            return parse(Files.readString(file), FILE);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
    }

    /** {@code text} read as a configuration; {@code label} names it in a refusal, which names the first problem. */
    public static Config parse(String text, String label) {
        try {
            return read(text, label, new Problems(true));
        } catch (Wrong wrong) {
            throw new Refusal(wrong.getMessage());
        }
    }

    /**
     * Every problem of {@code text} read as a configuration, by line; empty when {@link #parse} would accept it.
     * The first is the one {@link #parse} refuses only when the file's order is the reader's.
     */
    public static List<ConfigProblem> check(String text, String label) {
        Problems problems = new Problems(false);
        read(text, label, problems);
        if (problems.found.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> lines = ConfigDocument.lines(text);
        return problems.found.stream().map(w -> new ConfigProblem(w.path, w.getMessage(),
                        w.line > 0 ? w.line : lineOf(w.path, lines)))
                .sorted(Comparator.comparingInt(ConfigProblem::line)).toList();
    }

    /** The line of {@code path}, else of the nearest of its parents the file has. */
    private static int lineOf(String path, Map<String, Integer> lines) {
        for (String at = path; !at.isEmpty(); at = ConfigDocument.parent(at)) {
            Integer line = lines.get(at);
            if (line != null) {
                return line;
            }
        }
        return 0;
    }

    private static Config read(String text, String label, Problems problems) {
        Object root;
        try {
            root = new Load(LoadSettings.builder().setLabel(label).build()).loadFromString(text);
        } catch (YamlEngineException e) {
            int line = e instanceof MarkedYamlEngineException marked
                    ? marked.getProblemMark().map(mark -> mark.getLine() + 1).orElse(0) : 0;
            problems.report(new Wrong(label + ": not valid YAML (" + e.getMessage() + ")", "", line));
            return Config.defaults();
        }
        Config defaults = Config.defaults();
        if (root == null) {
            return defaults;
        }
        Map<String, Node> top = problems.attempt(() -> new Node(label, "", root, problems).map(TOP), Map.of());

        String tag = problems.attempt(() -> optional(top, "tag").map(t -> {
            if (!t.string().contains(Version.PLACEHOLDER)) {
                throw t.wrong("must contain " + Version.PLACEHOLDER);
            }
            return t.string();
        }), Optional.<String>empty()).orElse(defaults.tagPattern());
        List<String> ignore = problems.attempt(() -> optional(top, "relevance")
                .map(r -> r.map(Set.of("ignore")))
                .flatMap(r -> optional(r, "ignore"))
                .map(Node::strings), Optional.<List<String>>empty())
                .orElse(defaults.relevanceIgnore());

        Map<String, ModuleConfig> modules = new LinkedHashMap<>();
        optional(top, "modules").ifPresent(m -> problems.attempt(() -> m.anyMap(), Map.<String, Node>of())
                .forEach((path, node) -> {
                    ModuleConfig module = problems.attempt(() -> module(node), null);
                    if (module != null) {
                        modules.put(path, module);
                    }
                }));
        Map<String, Map<String, String>> afterRelease = new LinkedHashMap<>();
        optional(top, "after-release").ifPresent(m -> problems.attempt(() -> m.anyMap(), Map.<String, Node>of())
                .forEach((module, node) -> {
                    Map<String, String> properties = new LinkedHashMap<>();
                    problems.attempt(() -> node.anyMap(), Map.<String, Node>of()).forEach((name, value) -> {
                        String set = problems.attempt(value::string, null);
                        if (set != null) {
                            properties.put(name, set);
                        }
                    });
                    afterRelease.put(module, properties);
                }));

        return new Config(
                value(top, "target", ConfigLoader::target, defaults.target(), problems),
                value(top, "branch", Node::string, defaults.branch(), problems),
                tag,
                ignore,
                modules,
                each(top, "text-pins", ConfigLoader::textPin, problems),
                each(top, "edges", ConfigLoader::edge, problems),
                afterRelease,
                value(top, "changelog", ConfigLoader::changelog, ChangelogConfig.OFF, problems),
                each(top, "gates", ConfigLoader::gate, problems),
                each(top, "steps", ConfigLoader::step, problems),
                value(top, "github-releases", ConfigLoader::bool, defaults.githubReleases(), problems));
    }

    /** The top-level {@code key} read by {@code read}, or {@code otherwise} when it is absent or wrong. */
    private static <T> T value(Map<String, Node> top, String key, Function<Node, T> read, T otherwise,
                               Problems problems) {
        return optional(top, key).map(node -> problems.attempt(() -> read.apply(node), otherwise)).orElse(otherwise);
    }

    /** Each item of the top-level list {@code key} that reads; a wrong one is left out. */
    private static <T> List<T> each(Map<String, Node> top, String key, Function<Node, T> read, Problems problems) {
        return optional(top, key).map(node -> problems.attempt(node::list, List.<Node>of()).stream()
                .map(item -> problems.attempt(() -> read.apply(item), null))
                .filter(Objects::nonNull)
                .toList()).orElse(List.of());
    }

    private static Target target(Node node) {
        String shapes = "must be jitpack, none, { maven: <url> } or { jitpack: { maven: <version> } }";
        if (node.value() instanceof Map<?, ?>) {
            Map<String, Node> keys = node.map(Set.of("maven", "jitpack"));
            if (keys.size() != 1) {
                throw node.wrong(shapes);
            }
            return keys.containsKey("maven")
                    ? new Target.Maven(keys.get("maven").string())
                    : new Target.Jitpack(optional(keys.get("jitpack").map(Set.of("maven")), "maven")
                            .map(maven -> {
                                if (!maven.string().matches("\\d+(\\.\\d+)*")) {
                                    throw maven.wrong("must be a Maven version, digits and dots: 3.9.9");
                                }
                                return maven.string();
                            }));
        }
        return switch (node.string()) {
            case "jitpack" -> new Target.Jitpack();
            case "none" -> new Target.None();
            default -> throw node.wrong(shapes);
        };
    }

    private static ModuleConfig module(Node node) {
        if (node.value() == null) {
            return ModuleConfig.NONE;
        }
        Map<String, Node> keys = node.map(Set.of("alias", "kind", "place", "target"));
        ModuleConfig.Kind kind = optional(keys, "kind").map(k -> ModuleConfig.Kind.fromId(k.string())
                .orElseThrow(() -> k.wrong("must be maven, tag-only or ignored"))).orElse(ModuleConfig.Kind.MAVEN);
        return new ModuleConfig(optional(keys, "alias").map(Node::string), kind,
                optional(keys, "place").map(ConfigLoader::place), optional(keys, "target").map(ConfigLoader::target));
    }

    private static Place place(Node node) {
        if (node.value() instanceof Map<?, ?>) {
            return new Place.After(required(node, node.map(Set.of("after")), "after").string());
        }
        return switch (node.string()) {
            case "first" -> new Place.First();
            case "last" -> new Place.Last();
            default -> throw node.wrong("must be first, last or { after: <module> }");
        };
    }

    private static TextPinConfig textPin(Node node) {
        Map<String, Node> keys = node.map(Set.of("in", "pins", "match", "spelled"));
        Spelling spelled = optional(keys, "spelled")
                .map(s -> Spelling.fromId(s.string()).orElseThrow(() -> s.wrong("must be version or tag")))
                .orElse(Spelling.VERSION);
        return new TextPinConfig(required(node, keys, "in").string(), required(node, keys, "pins").string(),
                required(node, keys, "match").string(), spelled);
    }

    private static EdgeConfig edge(Node node) {
        Map<String, Node> keys = node.map(Set.of("from", "to", "why"));
        return new EdgeConfig(required(node, keys, "from").string(), required(node, keys, "to").string(),
                required(node, keys, "why").string());
    }

    private static ChangelogConfig changelog(Node node) {
        if (node.value() instanceof Boolean on) {
            return on ? new ChangelogConfig(true, Optional.empty(), false) : ChangelogConfig.OFF;
        }
        Map<String, Node> keys = node.map(Set.of("extractor", "pins"));
        return new ChangelogConfig(true, optional(keys, "extractor").map(ConfigLoader::command),
                optional(keys, "pins").map(ConfigLoader::bool).orElse(false));
    }

    private static boolean bool(Node node) {
        if (node.value() instanceof Boolean on) {
            return on;
        }
        throw node.wrong("must be true or false");
    }

    private static CommandConfig gate(Node node) {
        Map<String, Node> keys = node.map(Set.of("name", "modules", "run", "skip-exit"));
        return new CommandConfig(required(node, keys, "name").string(),
                optional(keys, "modules").map(Node::strings).orElse(List.of()),
                command(required(node, keys, "run")),
                optional(keys, "skip-exit").map(Node::integer));
    }

    private static StepConfig step(Node node) {
        Map<String, Node> keys = node.map(Set.of("at", "modules", "run"));
        Node at = required(node, keys, "at");
        StepPoint point = StepPoint.fromId(at.string())
                .orElseThrow(() -> at.wrong("must be before-release-commit, after-tag or after-snapshot"));
        return new StepConfig(point, optional(keys, "modules").map(Node::strings).orElse(List.of()),
                command(required(node, keys, "run")));
    }

    /** A list or a string is the same command on both systems; a map gives each its own. */
    private static Command command(Node node) {
        if (node.value() instanceof Map<?, ?>) {
            Map<String, Node> keys = node.map(Set.of("linux", "windows"));
            if (keys.isEmpty()) {
                throw node.wrong("must give linux, windows or both");
            }
            return new Command(optional(keys, "linux").map(ConfigLoader::line),
                    optional(keys, "windows").map(ConfigLoader::line));
        }
        Line line = line(node);
        return new Command(Optional.of(line), Optional.of(line));
    }

    private static Line line(Node node) {
        if (node.value() instanceof List<?>) {
            List<String> argv = node.strings();
            if (argv.isEmpty()) {
                throw node.wrong("must not be empty");
            }
            return new Line.Args(argv);
        }
        return new Line.Shell(node.string());
    }

    private static Optional<Node> optional(Map<String, Node> keys, String key) {
        return Optional.ofNullable(keys.get(key));
    }

    private static Node required(Node owner, Map<String, Node> keys, String key) {
        return optional(keys, key).orElseThrow(() -> owner.wrong("needs " + key));
    }

    /** One problem, as it travels up to the part of the file that reads past it. */
    private static final class Wrong extends RuntimeException {

        private final String path;
        private final int line;

        Wrong(String message, String path, int line) {
            super(message, null, false, false);
            this.path = path;
            this.line = line;
        }
    }

    /**
     * Where problems go: a strict reader throws the first, a checking one keeps each and reads on, the part that
     * was wrong left out.
     */
    private static final class Problems {

        private final boolean strict;
        private final List<Wrong> found = new ArrayList<>();

        Problems(boolean strict) {
            this.strict = strict;
        }

        void report(Wrong wrong) {
            if (strict) {
                throw wrong;
            }
            found.add(wrong);
        }

        /** What {@code read} gives, or {@code otherwise} once its problem is reported. */
        <T> T attempt(Supplier<T> read, T otherwise) {
            try {
                return read.get();
            } catch (Wrong wrong) {
                report(wrong);
                return otherwise;
            }
        }
    }

    /** A YAML value and where it sits, so a refusal can say {@code modules.core.alias}. */
    private record Node(String label, String path, Object value, Problems problems) {

        Wrong wrong(String what) {
            return new Wrong(label + ": " + (path.isEmpty() ? "the top level" : path) + " " + what, path, 0);
        }

        String child(String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        /** A map whose every key is one of {@code known}; an unknown one is a problem, and left out. */
        Map<String, Node> map(Set<String> known) {
            Map<String, Node> keys = new LinkedHashMap<>();
            anyMap().forEach((key, node) -> {
                if (known.contains(key)) {
                    keys.put(key, node);
                } else {
                    problems.report(node.wrong("is not a key sluice knows (known here: "
                            + String.join(", ", new TreeSet<>(known)) + ")"));
                }
            });
            return keys;
        }

        Map<String, Node> anyMap() {
            if (!(value instanceof Map<?, ?> map)) {
                throw wrong("must be a map");
            }
            Map<String, Node> keys = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String name)) {
                    throw wrong("has a key that is not text: " + key);
                }
                keys.put(name, new Node(label, child(name), child, problems));
            });
            return keys;
        }

        List<Node> list() {
            if (!(value instanceof List<?> items)) {
                throw wrong("must be a list");
            }
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                nodes.add(new Node(label, path + "[" + i + "]", items.get(i), problems));
            }
            return nodes;
        }

        List<String> strings() {
            return list().stream().map(Node::string).toList();
        }

        String string() {
            return switch (value) {
                case String text -> text;
                // A whole number reads back as written; 1.10 would read back as 1.1, so it must be quoted.
                case Integer number -> number.toString();
                case Long number -> number.toString();
                case Number number -> throw wrong("is the number " + number + "; quote it to keep it as written");
                case null, default -> throw wrong("must be text");
            };
        }

        int integer() {
            if (!(value instanceof Integer number)) {
                throw wrong("must be a whole number");
            }
            return number;
        }
    }
}
