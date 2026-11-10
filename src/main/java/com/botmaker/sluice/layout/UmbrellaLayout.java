package com.botmaker.sluice.layout;

import com.botmaker.sluice.Refusal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An umbrella repository whose {@code .gitmodules} lists the project's repositories, in its own order.
 *
 * <p>Read as text, not through {@code git config}: the file is a handful of {@code [submodule "x"]} sections and
 * reading it needs no process. Only {@code path} and {@code url} are used.
 */
public record UmbrellaLayout(Path root, List<ModuleDir> modules) implements Layout {

    private static final Pattern SECTION = Pattern.compile("^\\s*\\[submodule\\s+\"([^\"]+)\"\\s*]\\s*$");
    private static final Pattern KEY = Pattern.compile("^\\s*(\\w+)\\s*=\\s*(.*?)\\s*$");

    public UmbrellaLayout {
        modules = List.copyOf(modules);
    }

    /** The umbrella at {@code root}, refused when it has no {@code .gitmodules}. */
    public static UmbrellaLayout of(Path root) {
        Path file = root.resolve(".gitmodules");
        if (!Files.isRegularFile(file)) {
            throw new Refusal(root + " has no .gitmodules: sluice releases an umbrella of git submodules");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
        return new UmbrellaLayout(root, parse(root, lines));
    }

    private static List<ModuleDir> parse(Path root, List<String> lines) {
        List<ModuleDir> modules = new ArrayList<>();
        String path = null;
        String url = null;
        boolean inSection = false;
        for (String line : lines) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                if (inSection) {
                    add(root, modules, path, url);
                }
                inSection = true;
                path = null;
                url = null;
                continue;
            }
            Matcher key = KEY.matcher(line);
            if (inSection && key.matches()) {
                switch (key.group(1)) {
                    case "path" -> path = key.group(2);
                    case "url" -> url = key.group(2);
                    default -> {
                        // branch, update, shallow: the release reads none of them.
                    }
                }
            }
        }
        if (inSection) {
            add(root, modules, path, url);
        }
        return modules;
    }

    private static void add(Path root, List<ModuleDir> modules, String path, String url) {
        if (path == null || path.isBlank()) {
            throw new Refusal(".gitmodules has a submodule with no path");
        }
        String normalized = path.replace('\\', '/');
        modules.add(new ModuleDir(normalized, root.resolve(normalized), Optional.ofNullable(url)));
    }
}
