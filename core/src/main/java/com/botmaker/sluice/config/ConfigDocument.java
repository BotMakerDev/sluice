package com.botmaker.sluice.config;

import com.botmaker.sluice.Refusal;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.CollectionNode;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@code sluice.yml} as its author wrote it, to edit by key path and save. <b>The text is the document</b>: an
 * edit replaces only the text of the value it changes, or adds or removes the lines of one entry, so every comment,
 * blank line, quote and line break elsewhere stays as written, and a document saved unedited is the file it was read
 * from, byte for byte. (A YAML dump would move the comments above list items and rewrap flow lists.)
 *
 * <p>A path is the keys and list indices from the top: {@code List.of("modules", "core", "alias")},
 * {@code List.of("gates", 0, "name")}. Immutable: an edit returns the edited document.
 */
public final class ConfigDocument {

    private static final int WIDTH = 100_000;

    private final String text;
    private final String label;

    private ConfigDocument(String text, String label) {
        this.text = text;
        this.label = label;
    }

    /** {@code text} as a document; {@code label} names it in its problems. */
    public static ConfigDocument of(String text, String label) {
        return new ConfigDocument(text, label);
    }

    /** The umbrella's {@code sluice.yml}, empty when it has none. */
    public static ConfigDocument load(Path umbrella) {
        Path file = umbrella.resolve(ConfigLoader.FILE);
        try {
            return of(Files.isRegularFile(file) ? Files.readString(file) : "", ConfigLoader.FILE);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
    }

    /** Writes the document as the umbrella's {@code sluice.yml}. */
    public void save(Path umbrella) {
        Path file = umbrella.resolve(ConfigLoader.FILE);
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be written (" + e.getMessage() + ")");
        }
    }

    public String text() {
        return text;
    }

    /** Every problem the configuration has, each with its line. */
    public List<ConfigProblem> problems() {
        return ConfigLoader.check(text, label);
    }

    /** The configuration it says; refused at its first problem. */
    public Config config() {
        return ConfigLoader.parse(text, label);
    }

    /** The value at {@code path} as YAML reads it (text, number, boolean, list, map); empty when it has none. */
    public Optional<Object> get(List<?> path) {
        Object at;
        try {
            at = new Load(LoadSettings.builder().build()).loadFromString(text);
        } catch (YamlEngineException e) {
            return Optional.empty();
        }
        for (Object segment : path) {
            if (segment instanceof String key && at instanceof Map<?, ?> map) {
                at = map.get(key);
            } else if (segment instanceof Integer i && at instanceof List<?> list && i >= 0 && i < list.size()) {
                at = list.get(i);
            } else {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(at);
    }

    /**
     * The document with {@code value} at {@code path}: text, a number, a boolean, or a list or map of them. The
     * keys on the way that are missing are added; an index may be one past a list's end, to add an item.
     */
    public ConfigDocument set(List<?> path, Object value) {
        Optional<Node> root = compose();
        if (path.isEmpty() || root.isEmpty()) {
            Object whole = nest(path, value);
            String written = whole instanceof Map<?, ?> || whole instanceof List<?> ? block(whole, 0) : inline(whole) + "\n";
            // A document of comments only keeps them: the value goes after.
            String kept = path.isEmpty() || text.isBlank() ? "" : text.endsWith("\n") ? text : text + "\n";
            return edited(kept + written);
        }
        List<Node> trail = trail(root.get(), path);
        Node at = trail.getLast();
        if (trail.size() == path.size() + 1) {
            return replace(trail, value);
        }
        Object segment = path.get(trail.size() - 1);
        Object rest = nest(path.subList(trail.size(), path.size()), value);
        return switch (at) {
            case MappingNode map when segment instanceof String key -> add(map, key, rest);
            case SequenceNode list when segment instanceof Integer i && i == list.getValue().size() -> append(list, rest);
            case ScalarNode ignored -> replace(trail, nest(List.of(segment), rest));
            default -> throw new IllegalArgumentException(label + ": " + display(path) + " has no place to go");
        };
    }

    /** The document without the entry at {@code path}, and the comment lines right above it; as it is without one. */
    public ConfigDocument remove(List<?> path) {
        Optional<Node> root = compose();
        if (root.isEmpty()) {
            return this;
        }
        if (path.isEmpty()) {
            return edited("");
        }
        List<Node> trail = trail(root.get(), path);
        if (trail.size() != path.size() + 1) {
            return this;
        }
        Node parent = trail.get(trail.size() - 2);
        Node node = trail.getLast();
        List<Node> starts = new ArrayList<>();
        Node start = node;
        if (parent instanceof MappingNode map) {
            for (NodeTuple tuple : map.getValue()) {
                starts.add(tuple.getKeyNode());
                if (tuple.getValueNode() == node) {
                    start = tuple.getKeyNode();
                }
            }
        } else {
            starts.addAll(((SequenceNode) parent).getValue());
        }
        CollectionNode<?> collection = (CollectionNode<?>) parent;
        boolean flow = collection.getFlowStyle() == FlowStyle.FLOW;
        // A block document left with no entry is its comments: lines are cut as below.
        if (starts.size() == 1 && (path.size() > 1 || flow)) {
            // A list or map left with nothing would read as nothing at all: say it empty.
            return set(path.subList(0, path.size() - 1), parent instanceof MappingNode ? Map.of() : List.of());
        }
        boolean item = parent instanceof SequenceNode;
        int i = starts.indexOf(start);
        int keyAt = index(start.getStartMark());
        if (!item && i < starts.size() - 1 && !text.substring(lineStart(keyAt), keyAt).isBlank()) {
            // The first key of a list item shares the item's line (`- name: a`): the next key takes its place.
            return edited(text.substring(0, keyAt) + text.substring(index(starts.get(i + 1).getStartMark())));
        }
        if (flow) {
            int from = i < starts.size() - 1 ? index(start.getStartMark()) : contentEnd(previous(parent, i));
            int to = i < starts.size() - 1 ? index(starts.get(i + 1).getStartMark()) : contentEnd(node);
            return edited(text.substring(0, from) + text.substring(to));
        }
        int from = lineStart(dashOrKey(start, item));
        while (from > 0) {
            int above = lineStart(from - 1);
            String line = text.substring(above, from - 1);
            if (!line.strip().startsWith("#") || indent(line) != column(start, item)) {
                break;
            }
            from = above;
        }
        int to = lineEnd(contentEnd(node));
        return edited(text.substring(0, from) + text.substring(Math.min(text.length(), to + 1)));
    }

    private ConfigDocument replace(List<Node> trail, Object value) {
        Node node = trail.getLast();
        if (trail.size() == 1) {
            return edited(nonEmpty(value) ? block(value, 0) : inline(value) + "\n");
        }
        Node parent = trail.get(trail.size() - 2);
        boolean blockParent = ((CollectionNode<?>) parent).getFlowStyle() == FlowStyle.BLOCK;
        boolean inlineNode = !(node instanceof CollectionNode<?> c) || c.getFlowStyle() == FlowStyle.FLOW;
        // `key:` with nothing after it: the empty value's marks may sit on the next line.
        boolean empty = node instanceof ScalarNode && index(node.getStartMark()) == index(node.getEndMark());
        if (parent instanceof MappingNode map && blockParent) {
            Node key = map.getValue().stream().filter(t -> t.getValueNode() == node).findFirst().orElseThrow()
                    .getKeyNode();
            int colon = text.indexOf(':', index(key.getEndMark()));
            // A flow value stays flow, as its author wrote it; a scalar becomes a block when it gets a collection.
            if (inlineNode && !empty && (!nonEmpty(value) || node instanceof CollectionNode<?>)) {
                return splice(index(node.getStartMark()), contentEnd(node), inline(value));
            }
            return splice(colon + 1, empty ? colon + 1 : contentEnd(node), afterKey(value, column(key, false)));
        }
        if (parent instanceof SequenceNode && blockParent && nonEmpty(value)) {
            int col = column(node, true);
            return splice(dashOrKey(node, true), contentEnd(node), "- " + blockItem(value, col));
        }
        return splice(index(node.getStartMark()), contentEnd(node), inline(value));
    }

    /** A list item's value in block form, its first line to follow the dash at {@code dashColumn}. */
    private static String blockItem(Object value, int dashColumn) {
        return block(value, dashColumn + 2).substring(dashColumn + 2).stripTrailing();
    }

    private ConfigDocument add(MappingNode map, String key, Object value) {
        if (map.getFlowStyle() == FlowStyle.FLOW) {
            int close = text.lastIndexOf('}', index(map.getEndMark()));
            String entry = inline(key) + ": " + inline(value);
            return splice(close, close, map.getValue().isEmpty() ? entry : ", " + entry);
        }
        int col = column(map.getValue().getFirst().getKeyNode(), false);
        return insertLine(lineEnd(contentEnd(map)), " ".repeat(col) + inline(key) + ":" + afterKey(value, col));
    }

    private ConfigDocument append(SequenceNode list, Object value) {
        if (list.getFlowStyle() == FlowStyle.FLOW) {
            int close = text.lastIndexOf(']', index(list.getEndMark()));
            return splice(close, close, (list.getValue().isEmpty() ? "" : ", ") + inline(value));
        }
        Node first = list.getValue().getFirst();
        int col = column(first, true);
        String item = nonEmpty(value) ? blockItem(value, col) : inline(value);
        return insertLine(lineEnd(contentEnd(list)), " ".repeat(col) + "- " + item);
    }

    /** What follows {@code key:} for {@code value}: a space and the value, or its block on the next lines. */
    private static String afterKey(Object value, int keyColumn) {
        return nonEmpty(value) ? "\n" + block(value, keyColumn + 2).stripTrailing() : " " + inline(value);
    }

    private static boolean nonEmpty(Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty() || value instanceof List<?> list && !list.isEmpty();
    }

    /** {@code value} on one line, as flow YAML. */
    static String inline(Object value) {
        DumpSettings settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.FLOW).setWidth(WIDTH).build();
        String out = new Dump(settings).dumpToString(value).strip();
        return out.endsWith("\n...") ? out.substring(0, out.length() - 4).strip() : out;
    }

    /** {@code value} as block YAML, each line indented by {@code indent}, ending in a line break. */
    static String block(Object value, int indent) {
        DumpSettings settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2)
                .setIndicatorIndent(2).setIndentWithIndicator(true).setWidth(WIDTH).build();
        StringBuilder out = new StringBuilder();
        new Dump(settings).dumpToString(value).lines().forEach(line -> out.append(" ".repeat(indent)).append(line)
                .append('\n'));
        return out.toString();
    }

    /** {@code value} under the keys and indices of {@code path}, from the outside in. */
    private static Object nest(List<?> path, Object value) {
        Object at = value;
        for (int i = path.size() - 1; i >= 0; i--) {
            Object segment = path.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            if (segment instanceof String key) {
                map.put(key, at);
                at = map;
            } else {
                at = List.of(at);
            }
        }
        return at;
    }

    /** The nodes from the root along {@code path}, as far as the document has them. */
    private static List<Node> trail(Node root, List<?> path) {
        List<Node> trail = new ArrayList<>(List.of(root));
        Node at = root;
        for (Object segment : path) {
            Optional<Node> next = child(at, segment);
            if (next.isEmpty()) {
                break;
            }
            at = next.get();
            trail.add(at);
        }
        return trail;
    }

    private static Optional<Node> child(Node node, Object segment) {
        if (node instanceof MappingNode map && segment instanceof String key) {
            return map.getValue().stream()
                    .filter(t -> t.getKeyNode() instanceof ScalarNode s && s.getValue().equals(key))
                    .map(NodeTuple::getValueNode).findFirst();
        }
        if (node instanceof SequenceNode list && segment instanceof Integer i && i >= 0 && i < list.getValue().size()) {
            return Optional.of(list.getValue().get(i));
        }
        return Optional.empty();
    }

    private Node previous(Node parent, int i) {
        if (parent instanceof MappingNode map) {
            return map.getValue().get(i - 1).getValueNode();
        }
        return ((SequenceNode) parent).getValue().get(i - 1);
    }

    /** Where the last character of {@code node}'s own text ends: never the comments or blank lines after it. */
    private int contentEnd(Node node) {
        if (node instanceof CollectionNode<?> c && c.getFlowStyle() == FlowStyle.BLOCK) {
            if (node instanceof MappingNode map && !map.getValue().isEmpty()) {
                return contentEnd(map.getValue().getLast().getValueNode());
            }
            if (node instanceof SequenceNode list && !list.getValue().isEmpty()) {
                return contentEnd(list.getValue().getLast());
            }
        }
        int end = index(node.getEndMark());
        while (end > index(node.getStartMark()) && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /** Where a list item's dash is, or a map entry's key. */
    private int dashOrKey(Node node, boolean item) {
        int at = index(node.getStartMark());
        // Before the item's start: an item may begin with a dash of its own (`- -B`).
        return item ? text.lastIndexOf('-', at - 1) : at;
    }

    /** The column of a list item's dash, or of a key. */
    private int column(Node node, boolean item) {
        int at = dashOrKey(node, item);
        return at - lineStart(at);
    }

    private int lineStart(int at) {
        return at <= 0 ? 0 : text.lastIndexOf('\n', at - 1) + 1;
    }

    private int lineEnd(int at) {
        int nl = text.indexOf('\n', at);
        return nl < 0 ? text.length() : nl;
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private ConfigDocument insertLine(int lineEnd, String line) {
        String before = text.substring(0, lineEnd);
        String after = text.substring(lineEnd);
        return edited(before + "\n" + line + (after.isEmpty() ? "\n" : after));
    }

    private ConfigDocument splice(int from, int to, String with) {
        return edited(text.substring(0, from) + with + text.substring(to));
    }

    /** The edited text as a document; an edit that breaks the YAML is this class's bug, never the author's. */
    private ConfigDocument edited(String edited) {
        try {
            new Compose(LoadSettings.builder().build()).composeString(edited);
        } catch (YamlEngineException e) {
            throw new IllegalStateException(label + ": an edit left text that is not YAML: " + e.getMessage(), e);
        }
        return new ConfigDocument(edited, label);
    }

    /** A mark's place in {@link #text}: snakeyaml counts code points. */
    private int index(Optional<Mark> mark) {
        return text.offsetByCodePoints(0, mark.orElseThrow().getIndex());
    }

    private Optional<Node> compose() {
        try {
            return new Compose(LoadSettings.builder().build()).composeString(text);
        } catch (YamlEngineException e) {
            throw new Refusal(label + " is not valid YAML, so it cannot be edited by key: " + e.getMessage());
        }
    }

    /** A path as a problem names it: {@code gates[0].name}. */
    static String display(List<?> path) {
        StringBuilder out = new StringBuilder();
        for (Object segment : path) {
            if (segment instanceof Integer i) {
                out.append('[').append(i).append(']');
            } else {
                out.append(out.isEmpty() ? "" : ".").append(segment);
            }
        }
        return out.toString();
    }

    /** {@code path} without its last key or index. */
    static String parent(String path) {
        int cut = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
        return cut < 0 ? "" : path.substring(0, cut);
    }

    /** The line of every key and list item in {@code text}, by its path as a problem names it; empty when not YAML. */
    static Map<String, Integer> lines(String text) {
        Map<String, Integer> lines = new LinkedHashMap<>();
        try {
            new Compose(LoadSettings.builder().build()).composeString(text).ifPresent(root -> walk(root, List.of(), lines));
        } catch (YamlEngineException e) {
            return Map.of();
        }
        return lines;
    }

    private static void walk(Node node, List<Object> path, Map<String, Integer> lines) {
        if (node instanceof MappingNode map) {
            for (NodeTuple tuple : map.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key) {
                    List<Object> at = new ArrayList<>(path);
                    at.add(key.getValue());
                    lines.put(display(at), key.getStartMark().map(m -> m.getLine() + 1).orElse(0));
                    walk(tuple.getValueNode(), at, lines);
                }
            }
        } else if (node instanceof SequenceNode list) {
            for (int i = 0; i < list.getValue().size(); i++) {
                List<Object> at = new ArrayList<>(path);
                at.add(i);
                Node item = list.getValue().get(i);
                lines.put(display(at), item.getStartMark().map(m -> m.getLine() + 1).orElse(0));
                walk(item, at, lines);
            }
        }
    }
}
