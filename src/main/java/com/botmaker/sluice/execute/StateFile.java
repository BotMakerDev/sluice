package com.botmaker.sluice.execute;

import com.botmaker.sluice.Json;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.execute.ReleaseState.Entry;
import com.botmaker.sluice.execute.ReleaseState.Status;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A run's files under {@code releases/}: {@code <YYYY-MM-DD-HHMM>.json}, its state, beside {@code .md}, its log. A
 * second run in the same minute adds {@code -2}, {@code -3}…
 */
final class StateFile {

    static final String DIR = "releases";

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");
    private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{4})(?:-(\\d+))?\\.json");

    private StateFile() {
    }

    /** A new run's files without their extension: its minute, numbered when the minute has a run already. */
    static Path base(Path umbrella, Clock clock) {
        Path dir = umbrella.resolve(DIR);
        String minute = LocalDateTime.now(clock).format(MINUTE);
        for (int n = 1; ; n++) {
            String name = n == 1 ? minute : minute + "-" + n;
            if (!Files.exists(dir.resolve(name + ".json")) && !Files.exists(dir.resolve(name + ".md"))) {
                return dir.resolve(name);
            }
        }
    }

    /** The newest state under {@code releases/}, by its name. */
    static Optional<Path> newest(Path umbrella) {
        Path dir = umbrella.resolve(DIR);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> NAME.matcher(file.getFileName().toString()).matches())
                    .max(Comparator.comparing((Path file) -> match(file).group(1))
                            .thenComparingInt(file -> match(file).group(2) == null
                                    ? 1 : Integer.parseInt(match(file).group(2))));
        } catch (IOException e) {
            throw new Refusal(dir + ": could not be listed (" + e.getMessage() + ")");
        }
    }

    private static Matcher match(Path file) {
        Matcher matcher = NAME.matcher(file.getFileName().toString());
        matcher.matches();
        return matcher;
    }

    static void write(Path file, ReleaseState state) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json(state));
        } catch (IOException e) {
            throw new Refusal(file + ": the release's state could not be written (" + e.getMessage() + ")");
        }
    }

    static String json(ReleaseState state) {
        String modules = state.modules().stream()
                .map(e -> "    {\"module\": " + Json.quote(e.module()) + ", \"version\": "
                        + Json.quote(e.version().toString()) + ", \"stage\": " + Json.quote(e.stage().name())
                        + ", \"detail\": " + Json.quote(e.detail()) + "}")
                .collect(Collectors.joining(",\n"));
        return "{\n  \"status\": " + Json.quote(state.status().name()) + ",\n  \"modules\": [\n" + modules
                + "\n  ]\n}\n";
    }

    static ReleaseState read(Path file) {
        try {
            Object root = new Load(LoadSettings.builder().setLabel(file.toString()).build())
                    .loadFromString(Files.readString(file));
            if (!(root instanceof Map<?, ?> map) || !(map.get("modules") instanceof List<?> list)) {
                throw new IllegalArgumentException("no modules");
            }
            List<Entry> entries = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    throw new IllegalArgumentException("a module that is not an object");
                }
                entries.add(new Entry(text(entry, "module"),
                        Version.parse(text(entry, "version"))
                                .orElseThrow(() -> new IllegalArgumentException("a version that is not x.y.z")),
                        Stage.valueOf(text(entry, "stage")), text(entry, "detail")));
            }
            return new ReleaseState(Status.valueOf(text(map, "status")), entries);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            throw new Refusal(file + ": not a sluice state file (" + e.getMessage() + ")");
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("no " + key);
        }
        return value.toString();
    }
}
