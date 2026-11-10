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
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads {@code sluice.yml}. Strict: an unknown key, a wrong type or a bad value is refused naming where it is, so a
 * typo is never a setting silently ignored.
 */
public final class ConfigLoader {

    public static final String FILE = "sluice.yml";

    private static final Set<String> TOP = Set.of("target", "branch", "tag", "relevance", "modules", "text-pins",
            "edges", "after-release", "changelog", "gates", "steps");

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

    /** {@code text} read as a configuration; {@code label} names it in a refusal. */
    public static Config parse(String text, String label) {
        Object root;
        try {
            root = new Load(LoadSettings.builder().setLabel(label).build()).loadFromString(text);
        } catch (YamlEngineException e) {
            throw new Refusal(label + ": not valid YAML (" + e.getMessage() + ")");
        }
        Config defaults = Config.defaults();
        if (root == null) {
            return defaults;
        }
        Map<String, Node> top = new Node(label, "", root).map(TOP);

        String tag = optional(top, "tag").map(Node::string).orElse(defaults.tagPattern());
        if (!tag.contains(Version.PLACEHOLDER)) {
            throw top.get("tag").wrong("must contain " + Version.PLACEHOLDER);
        }
        List<String> ignore = optional(top, "relevance")
                .map(r -> r.map(Set.of("ignore")))
                .flatMap(r -> optional(r, "ignore"))
                .map(Node::strings)
                .orElse(defaults.relevanceIgnore());

        Map<String, ModuleConfig> modules = new LinkedHashMap<>();
        optional(top, "modules").ifPresent(m -> m.anyMap().forEach((path, node) -> modules.put(path, module(node))));
        Map<String, Map<String, String>> afterRelease = new LinkedHashMap<>();
        optional(top, "after-release").ifPresent(m -> m.anyMap().forEach((module, node) -> {
            Map<String, String> properties = new LinkedHashMap<>();
            node.anyMap().forEach((name, value) -> properties.put(name, value.string()));
            afterRelease.put(module, properties);
        }));

        return new Config(
                optional(top, "target").map(ConfigLoader::target).orElse(defaults.target()),
                optional(top, "branch").map(Node::string).orElse(defaults.branch()),
                tag,
                ignore,
                modules,
                optional(top, "text-pins").map(n -> n.list().stream().map(ConfigLoader::textPin).toList()).orElse(List.of()),
                optional(top, "edges").map(n -> n.list().stream().map(ConfigLoader::edge).toList()).orElse(List.of()),
                afterRelease,
                optional(top, "changelog").map(ConfigLoader::changelog).orElse(ChangelogConfig.OFF),
                optional(top, "gates").map(n -> n.list().stream().map(ConfigLoader::gate).toList()).orElse(List.of()),
                optional(top, "steps").map(n -> n.list().stream().map(ConfigLoader::step).toList()).orElse(List.of()));
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
        boolean tagOnly = optional(keys, "kind").map(k -> switch (k.string()) {
            case "maven" -> false;
            case "tag-only" -> true;
            default -> throw k.wrong("must be maven or tag-only");
        }).orElse(false);
        return new ModuleConfig(optional(keys, "alias").map(Node::string), tagOnly,
                optional(keys, "place").map(ConfigLoader::place), optional(keys, "target").map(ConfigLoader::target));
    }

    private static Place place(Node node) {
        if (node.value() instanceof Map<?, ?>) {
            return new Place.After(node.map(Set.of("after")).get("after").string());
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
            return on ? new ChangelogConfig(true, Optional.empty()) : ChangelogConfig.OFF;
        }
        Map<String, Node> keys = node.map(Set.of("extractor"));
        return new ChangelogConfig(true, optional(keys, "extractor").map(ConfigLoader::command));
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

    /** A YAML value and where it sits, so a refusal can say {@code modules.core.alias}. */
    private record Node(String label, String path, Object value) {

        Refusal wrong(String what) {
            return new Refusal(label + ": " + (path.isEmpty() ? "the top level" : path) + " " + what);
        }

        Node child(String key) {
            return new Node(label, path.isEmpty() ? key : path + "." + key, null);
        }

        /** A map whose every key is one of {@code known}. */
        Map<String, Node> map(Set<String> known) {
            Map<String, Node> keys = anyMap();
            for (String key : keys.keySet()) {
                if (!known.contains(key)) {
                    throw keys.get(key).wrong("is not a key sluice knows (known here: "
                            + String.join(", ", new TreeSet<>(known)) + ")");
                }
            }
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
                keys.put(name, new Node(label, child(name).path(), child));
            });
            return keys;
        }

        List<Node> list() {
            if (!(value instanceof List<?> items)) {
                throw wrong("must be a list");
            }
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                nodes.add(new Node(label, path + "[" + i + "]", items.get(i)));
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
