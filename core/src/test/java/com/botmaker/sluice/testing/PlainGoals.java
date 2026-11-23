// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.botmaker.sluice.testing;

import com.botmaker.sluice.PinMoves.Goals;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.model.PomReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code versions:set} and {@code versions:set-property} done as the edit they make to a plain pom, through the same
 * {@link Runner}, so a test's release spends no Maven run on them (one is a JVM of its own, a few seconds each, and a
 * fixture release makes several). A pom with a {@code <parent>} or {@code <modules>}, and every other goal, goes to
 * {@code maven}: the stand-in knows plain poms only, and {@code PinMovesTest} and one end-to-end release keep the
 * plugin itself under test.
 */
public record PlainGoals(Runner runner, Goals maven) implements Goals {

    /** The project's own version: the first {@code <version>} of a pom with no parent. */
    private static final Pattern OWN_VERSION = Pattern.compile("<version>([^<]*)</version>");

    @Override
    public void run(Path pom, String goal, List<String> properties) {
        Optional<String> value = property(properties, "newVersion");
        // A preview only echoes the Maven line, which costs nothing and is what a preview test reads.
        if (runner.dryRun() || value.isEmpty() || !plain(pom)) {
            maven.run(pom, goal, properties);
            return;
        }
        switch (goal) {
            case "set" -> {
                // The first <version> is the project's only where it is the one the pom reads as its own.
                Matcher first = OWN_VERSION.matcher(read(pom));
                if (first.find() && PomReader.read(pom).version().map(String::strip)
                        .equals(Optional.of(first.group(1).strip()))) {
                    runner.replaceGroup(pom, OWN_VERSION, value.get());
                } else {
                    maven.run(pom, goal, properties);
                }
            }
            case "set-property" -> {
                // As the plugin: silent about a property the pom does not declare; the read-back says so.
                String name = Pattern.quote(property(properties, "property").orElseThrow());
                Pattern declared = Pattern.compile("<" + name + ">([^<]*)</" + name + ">");
                if (declared.matcher(read(pom)).find()) {
                    runner.replaceGroup(pom, declared, value.get());
                }
            }
            default -> maven.run(pom, goal, properties);
        }
    }

    private static boolean plain(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        String text = read(pom);
        return !text.contains("<parent>") && !text.contains("<modules>");
    }

    private static Optional<String> property(List<String> properties, String name) {
        return properties.stream().filter(p -> p.startsWith("-D" + name + "="))
                .map(p -> p.substring(name.length() + 3)).findFirst();
    }

    private static String read(Path pom) {
        try {
            return Files.readString(pom);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
