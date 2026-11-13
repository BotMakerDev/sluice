package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.config.ConfigDocument;
import com.botmaker.sluice.config.ConfigLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the Config tab's fields show of a {@code sluice.yml} value and what they write back: text a person types,
 * turned into the YAML the loader reads, and the loader's shapes turned into text. The loader decides what is
 * right; a field only spells. An edit writes one path, and a value cleared takes its key away, with the maps it
 * leaves empty.
 */
public final class ConfigValues {

    private static final List<String> CHANGELOG = List.of("changelog");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./${}=+-][A-Za-z0-9_./${}=+@-]*");

    private ConfigValues() {
    }

    /** {@code doc} with {@code value} at {@code path}; a {@linkplain #absent absent} value removes the key. */
    public static ConfigDocument put(ConfigDocument doc, List<?> path, Object value) {
        if (absent(value)) {
            return prune(doc.remove(path), path.subList(0, path.size() - 1));
        }
        return doc.set(path, value);
    }

    /** Nothing to write: null, blank text, an empty list or map. */
    public static boolean absent(Object value) {
        return value == null || value instanceof String s && s.isBlank() || value instanceof List<?> l && l.isEmpty()
                || value instanceof Map<?, ?> m && m.isEmpty();
    }

    /**
     * {@code doc} without the map or list at {@code path} when it is empty, and so on outwards. A list's item is
     * never taken away: an entry just added and not yet filled in stays.
     */
    public static ConfigDocument prune(ConfigDocument doc, List<?> path) {
        List<?> at = path;
        while (!at.isEmpty() && !(at.getLast() instanceof Integer)) {
            Optional<Object> value = doc.get(at);
            if (value.isPresent() && !absent(value.get())) {
                break;
            }
            if (at.equals(CHANGELOG)) {
                // Its presence turns the changelog on: emptied of its options, it stays on.
                return doc.set(at, true);
            }
            doc = doc.remove(at);
            at = at.subList(0, at.size() - 1);
        }
        return doc;
    }

    /** A scalar as a field shows it; a list or map as one line of YAML. */
    public static String text(Optional<Object> value) {
        return value.map(ConfigValues::text).orElse("");
    }

    private static String text(Object value) {
        return switch (value) {
            case List<?> list -> flow(list);
            case Map<?, ?> map -> "{" + String.join(", ", map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + text(e.getValue())).toList()) + "}";
            default -> String.valueOf(value);
        };
    }

    /** A list as {@code a, b, c}: what a list field shows. */
    public static String listText(Optional<Object> value) {
        return value.map(v -> v instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList()) : String.valueOf(v)).orElse("");
    }

    /** {@code a, b, c} as a list; blank is none. */
    public static List<String> list(String text) {
        return Arrays.stream(text.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** A number when the text is one, else the text, for the loader to say what is wrong with it. */
    public static Object integer(String text) {
        String word = text.strip();
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException e) {
            return word;
        }
    }

    /** Where a module, or every module, publishes. */
    public enum TargetKind {
        /** No key: the module takes the umbrella's target, the umbrella sluice's default, none. */
        UNSET("—"), NONE("none"), JITPACK("jitpack"), MAVEN("maven");

        private final String label;

        TargetKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * A target as its two fields hold it.
     *
     * @param detail the Maven repository's URL, or the Maven version JitPack builds with; blank for none
     */
    public record Target(TargetKind kind, String detail) {

        /** The YAML for it; null for {@link TargetKind#UNSET}. */
        public Object yaml() {
            return switch (kind) {
                case UNSET -> null;
                case NONE -> "none";
                case JITPACK -> detail.isBlank() ? "jitpack" : Map.of("jitpack", Map.of("maven", detail.strip()));
                case MAVEN -> Map.of("maven", detail.strip());
            };
        }
    }

    /** The target {@code value} says; empty when it is no shape the loader takes, which the fields leave alone. */
    public static Optional<Target> target(Optional<Object> value) {
        if (value.isEmpty()) {
            return Optional.of(new Target(TargetKind.UNSET, ""));
        }
        return switch (value.get()) {
            case String s when s.equals("none") -> Optional.of(new Target(TargetKind.NONE, ""));
            case String s when s.equals("jitpack") -> Optional.of(new Target(TargetKind.JITPACK, ""));
            case Map<?, ?> map when map.size() == 1 && map.get("maven") != null ->
                    Optional.of(new Target(TargetKind.MAVEN, String.valueOf(map.get("maven"))));
            case Map<?, ?> map when map.size() == 1 && map.containsKey("jitpack") -> {
                Object inner = map.get("jitpack");
                String maven = inner instanceof Map<?, ?> m && m.get("maven") != null ? String.valueOf(m.get("maven"))
                        : "";
                yield Optional.of(new Target(TargetKind.JITPACK, maven));
            }
            default -> Optional.empty();
        };
    }

    /** {@code first}, {@code last}, {@code after <module>}, or blank: a module's place as its choice shows it. */
    public static String place(Optional<Object> value) {
        return value.map(v -> v instanceof Map<?, ?> map && map.get("after") != null ? "after " + map.get("after")
                : String.valueOf(v)).orElse("");
    }

    /** The YAML for a place's choice. */
    public static Object placeYaml(String choice) {
        String word = choice == null ? "" : choice.strip();
        return word.startsWith("after ") ? Map.of("after", word.substring("after ".length()).strip()) : word;
    }

    /**
     * A command's two fields: what runs on Linux and on Windows. A string is a shell line; {@code [a, b]} is the
     * arguments, unquoted by YAML.
     */
    public record Command(String linux, String windows) {

        /** One value when both systems run the same, a map of each system's otherwise, null when neither. */
        public Object yaml() {
            if (linux.isBlank() && windows.isBlank()) {
                return null;
            }
            if (linux.strip().equals(windows.strip())) {
                return line(linux);
            }
            Map<String, Object> each = new LinkedHashMap<>();
            if (!linux.isBlank()) {
                each.put("linux", line(linux));
            }
            if (!windows.isBlank()) {
                each.put("windows", line(windows));
            }
            return each;
        }
    }

    public static Command command(Optional<Object> value) {
        if (value.isEmpty()) {
            return new Command("", "");
        }
        if (value.get() instanceof Map<?, ?> map) {
            return new Command(text(Optional.ofNullable(map.get("linux"))), text(Optional.ofNullable(map.get("windows"))));
        }
        String same = text(value);
        return new Command(same, same);
    }

    /** A command field's text as YAML: {@code [a, b]} a list of arguments, else the shell line. */
    static Object line(String text) {
        String word = text.strip();
        if (word.startsWith("[")) {
            Optional<Object> parsed = ConfigDocument.of("x: " + word + "\n", ConfigLoader.FILE).get(List.of("x"));
            if (parsed.isPresent() && parsed.get() instanceof List<?> list) {
                return new ArrayList<>(list);
            }
        }
        return word;
    }

    /** {@code [a, 'b c']}: a list on one line, each item quoted when YAML would read it otherwise. */
    static String flow(List<?> list) {
        return "[" + String.join(", ", list.stream().map(String::valueOf)
                .map(s -> PLAIN.matcher(s).matches() ? s : "'" + s.replace("'", "''") + "'").toList()) + "]";
    }
}
