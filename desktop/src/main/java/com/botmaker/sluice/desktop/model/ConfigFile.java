package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.config.ConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** What the Config tab does with the file around a save: check it is the one read, show what changed, commit it. */
public final class ConfigFile {

    /** Unchanged lines kept around each change. */
    private static final int CONTEXT = 2;

    private ConfigFile() {
    }

    /** The umbrella's {@code sluice.yml} as it is on disk now; empty when it has none. */
    public static String onDisk(Path umbrella) {
        Path file = umbrella.resolve(ConfigLoader.FILE);
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new Refusal(file + ": could not be read (" + e.getMessage() + ")");
        }
    }

    /**
     * Commits the umbrella's {@code sluice.yml} alone, with {@code message}.
     *
     * @throws Refusal when git refuses, with its words
     */
    public static void commit(Path umbrella, String message) {
        GitCommit.alone(umbrella, ConfigLoader.FILE, message);
    }

    /**
     * The lines that differ, {@code - } removed and {@code + } added, with {@value CONTEXT} unchanged lines around
     * each change and {@code ⋯} for the rest; empty when nothing differs.
     */
    public static String diff(String before, String after) {
        List<String> a = before.lines().toList();
        List<String> b = after.lines().toList();
        int[][] common = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                common[i][j] = a.get(i).equals(b.get(j)) ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<String> lines = new ArrayList<>();
        List<Boolean> changed = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                lines.add("  " + a.get(i++));
                j++;
                changed.add(false);
            } else if (i < a.size() && (j == b.size() || common[i + 1][j] >= common[i][j + 1])) {
                // A removed line before the one that replaces it, as a reader expects.
                lines.add("- " + a.get(i++));
                changed.add(true);
            } else {
                lines.add("+ " + b.get(j++));
                changed.add(true);
            }
        }
        StringBuilder out = new StringBuilder();
        boolean skipped = false;
        for (int k = 0; k < lines.size(); k++) {
            boolean near = false;
            for (int d = Math.max(0, k - CONTEXT); d <= Math.min(lines.size() - 1, k + CONTEXT) && !near; d++) {
                near = changed.get(d);
            }
            if (near) {
                out.append(lines.get(k)).append('\n');
                skipped = false;
            } else if (!skipped) {
                out.append("  ⋯\n");
                skipped = true;
            }
        }
        return changed.contains(true) ? out.toString() : "";
    }
}
