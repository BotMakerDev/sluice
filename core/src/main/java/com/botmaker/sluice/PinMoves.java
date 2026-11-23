// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.sluice;

import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PinReader;
import com.botmaker.sluice.model.PomReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves a pom's version and each kind of pin, through {@link Runner}: {@code versions-maven-plugin} for what Maven
 * spells, a text replacement for a text pin and for a {@code <parent>}.
 *
 * <p><b>A parent pin is moved as text, never by {@code versions:update-parent}</b>: every mojo first resolves the
 * parent the pom names now, a snapshot nothing may serve, so the goal fails wherever the local repository lacks
 * that exact snapshot. The same holds of any goal on a pom whose parent is not resolvable, which is why a release
 * moves a parent pin before {@code versions:set} and back after it.
 *
 * <p><b>Every move is read back</b> once it ran: {@code set-property} is silent about a property the pom does not
 * declare, and a release that believes it moved a pin it did not move is the failure worth being loud about.
 *
 * <p><b>Each run sorts with the JDK's legacy merge sort</b> ({@link #LEGACY_SORT}, added to {@code MAVEN_OPTS}): the
 * plugin sorts every version a repository lists before it sets a property, and its comparator is not consistent over
 * a mix such as {@code 0.16.0}, {@code v0.16.0} and {@code 0.16.1-SNAPSHOT}, which JitPack serves side by side. The
 * JDK's default sort then throws {@code Comparison method violates its general contract!} and the move fails; the
 * legacy sort does not check, and the plugin only needs the version it was given (2026-11-21).
 */
public final class PinMoves {

    public static final String PLUGIN = "org.codehaus.mojo:versions-maven-plugin:2.18.0";

    /** The JVM option that keeps the plugin's inconsistent version sort from throwing. */
    public static final String LEGACY_SORT = "-Djava.util.Arrays.useLegacyMergeSort=true";

    private static final Pattern PARENT_VERSION =
            Pattern.compile("(<parent>(?:(?!</parent>)[\\s\\S])*?<version>)\\s*[^<]*?\\s*(</version>)");

    private final Runner runner;
    private final Goals goals;

    /** @param mavenArgs arguments every Maven run gets before its goal: {@code -o}, a settings file */
    public PinMoves(Runner runner, List<String> mavenArgs) {
        this(runner, Goals.maven(runner, mavenArgs));
    }

    /** @param goals how each {@code versions-maven-plugin} goal is carried out */
    public PinMoves(Runner runner, Goals goals) {
        this.runner = runner;
        this.goals = goals;
    }

    /**
     * How one {@code versions-maven-plugin} goal is carried out on a pom: by Maven ({@link #maven}), or by a stand-in
     * a test gives, which must make the same edit through the same {@link Runner}. Each move is read back either way.
     */
    @FunctionalInterface
    public interface Goals {

        /** @param properties the goal's {@code -Dname=value} arguments */
        void run(Path pom, String goal, List<String> properties);

        /** Each goal a Maven run of its own. */
        static Goals maven(Runner runner, List<String> mavenArgs) {
            List<String> args = List.copyOf(mavenArgs);
            return (pom, goal, properties) -> {
                List<String> argv = new ArrayList<>(List.of("mvn", "-B", "-q"));
                argv.addAll(args);
                argv.addAll(List.of("-f", pom.toString(), PLUGIN + ":" + goal));
                argv.addAll(properties);
                Proc.Result result = runner.run(pom.toAbsolutePath().getParent(), argv,
                        mavenEnv(Optional.ofNullable(System.getenv("MAVEN_OPTS"))));
                if (!result.ok()) {
                    String errors = String.join("\n", result.out().lines()
                            .filter(line -> line.contains("[ERROR]")).limit(10).toList());
                    throw new Refusal(pom + ": versions:" + goal + " failed\n"
                            + (errors.isEmpty() ? result.out().strip() : errors));
                }
            };
        }
    }

    /** The pom's own version, by {@code versions:set}, which moves each module of a multi-module pom with it. */
    public void version(Path pom, String version) {
        mvn(pom, "set", "-DnewVersion=" + version, "-DgenerateBackupPoms=false");
        if (!runner.dryRun()) {
            Optional<String> now = PomReader.read(pom).version().map(String::strip);
            if (!now.equals(Optional.of(version))) {
                throw new Refusal(pom + ": its version did not move to " + version + " (it is "
                        + now.orElse("not written") + ")");
            }
        }
    }

    /** {@code pin} moved to {@code value}, by the goal its kind needs, then read back. */
    public void move(Pin pin, String value) {
        switch (pin) {
            case Pin.Property property -> mvn(property.pom(), "set-property", "-Dproperty=" + property.name(),
                    "-DnewVersion=" + value, "-DgenerateBackupPoms=false");
            case Pin.Literal literal -> mvn(literal.pom(), "use-dep-version",
                    "-Dincludes=" + literal.dep().groupId() + ":" + literal.dep().artifactId(),
                    "-DdepVersion=" + value, "-DforceVersion=true", "-DgenerateBackupPoms=false");
            case Pin.Parent parent ->
                    runner.replace(parent.pom(), PARENT_VERSION, "$1" + Matcher.quoteReplacement(value) + "$2");
            case Pin.Text text -> runner.replaceGroup(text.file(), text.match(), value);
        }
        if (!runner.dryRun()) {
            Optional<String> now = held(pin);
            if (!now.equals(Optional.of(value))) {
                throw new Refusal(pin.file() + ": its pin did not move to " + value + " (it holds "
                        + now.orElse("nothing") + ")");
            }
        }
    }

    /** What {@code pin} holds in its file now; empty when the file or the pin cannot be read. */
    public static Optional<String> held(Pin pin) {
        try {
            return PinReader.value(pin, Files.readString(pin.file())).map(String::strip);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** {@code MAVEN_OPTS} for a plugin run: the caller's {@code opts}, with {@link #LEGACY_SORT} added once. */
    static Map<String, String> mavenEnv(Optional<String> opts) {
        String given = opts.map(String::strip).orElse("");
        if (List.of(given.split("\\s+")).contains(LEGACY_SORT)) {
            return Map.of("MAVEN_OPTS", given);
        }
        return Map.of("MAVEN_OPTS", given.isEmpty() ? LEGACY_SORT : given + " " + LEGACY_SORT);
    }

    private void mvn(Path pom, String goal, String... properties) {
        goals.run(pom, goal, List.of(properties));
    }
}
