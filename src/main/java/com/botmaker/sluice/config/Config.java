package com.botmaker.sluice.config;

import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Spelling;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An umbrella's {@code sluice.yml}, every part optional. Module names in it are what a person types (a path, an
 * artifactId or an alias); the graph checks each one names a module.
 *
 * @param target          where every module publishes, unless its own entry says otherwise
 * @param branch          the release branch of every module
 * @param tagPattern      a tag's name, {@code {version}} standing for the version
 * @param relevanceIgnore globs of files whose change alone does not make a module changed
 * @param modules         per-module settings, keyed by the submodule's path
 * @param textPins        versions written as text in a file
 * @param edges           dependencies no pom says
 * @param afterRelease    per module, pom properties set to the released version in its back-to-snapshot commit
 * @param changelog       the Keep a Changelog feature
 * @param gates           project gates, each a command
 * @param steps           project steps, each a command at a point of the release
 */
public record Config(Target target, String branch, String tagPattern, List<String> relevanceIgnore,
                     Map<String, ModuleConfig> modules, List<TextPinConfig> textPins, List<EdgeConfig> edges,
                     Map<String, Map<String, String>> afterRelease, ChangelogConfig changelog,
                     List<CommandConfig> gates, List<StepConfig> steps) {

    public static final List<String> DEFAULT_IGNORE = List.of("**/*.md", "docs/**");

    public Config {
        relevanceIgnore = List.copyOf(relevanceIgnore);
        modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
        textPins = List.copyOf(textPins);
        edges = List.copyOf(edges);
        afterRelease = Collections.unmodifiableMap(new LinkedHashMap<>(afterRelease));
        gates = List.copyOf(gates);
        steps = List.copyOf(steps);
    }

    /** What an umbrella without a {@code sluice.yml} gets. */
    public static Config defaults() {
        return new Config(new Target.None(), "main", "v{version}", DEFAULT_IGNORE, Map.of(), List.of(), List.of(),
                Map.of(), ChangelogConfig.OFF, List.of(), List.of());
    }

    /** The settings of the submodule at {@code path}, none when the file gives it none. */
    public ModuleConfig module(String path) {
        return modules.getOrDefault(path, ModuleConfig.NONE);
    }

    /** Where a module publishes. */
    public sealed interface Target {

        /** JitPack: the pin is the tag's name. */
        record Jitpack() implements Target {
        }

        /** A Maven repository at {@code url}: the pin is the bare version. */
        record Maven(String url) implements Target {
        }

        /** Nowhere anything resolves it from. */
        record None() implements Target {
        }
    }

    /** Where a module goes in the tag order when the graph does not say. */
    public sealed interface Place {

        record First() implements Place {
        }

        record Last() implements Place {
        }

        /** Right after {@code module}. */
        record After(String module) implements Place {
        }
    }

    /**
     * One submodule's settings.
     *
     * @param tagOnly a submodule tagged with the rest but never pinned or published: one with no pom
     */
    public record ModuleConfig(Optional<String> alias, boolean tagOnly, Optional<Place> place,
                               Optional<Target> target) {

        public static final ModuleConfig NONE = new ModuleConfig(Optional.empty(), false, Optional.empty(),
                Optional.empty());
    }

    /**
     * A version written as text: {@code match}'s one group in the file {@code in} is the version of {@code pins}.
     *
     * @param in path of the file from the umbrella's root, {@code /}-separated
     */
    public record TextPinConfig(String in, String pins, String match, Spelling spelled) {
    }

    /** {@code to} depends on {@code from}, for the reason {@code why}. */
    public record EdgeConfig(String from, String to, String why) {
    }

    /**
     * The Keep a Changelog feature.
     *
     * @param extractor a command that prints a version's section, instead of the built-in reader
     */
    public record ChangelogConfig(boolean enabled, Optional<Command> extractor) {

        public static final ChangelogConfig OFF = new ChangelogConfig(false, Optional.empty());
    }

    /**
     * A project gate.
     *
     * @param modules  the modules it runs in; empty for every module released
     * @param skipExit the exit code meaning it could not run, which makes it {@code SKIPPED}
     */
    public record CommandConfig(String name, List<String> modules, Command run, Optional<Integer> skipExit) {

        public CommandConfig {
            modules = List.copyOf(modules);
        }
    }

    /** A project step: {@code run} in each of {@code modules} at {@code at}. */
    public record StepConfig(StepPoint at, List<String> modules, Command run) {

        public StepConfig {
            modules = List.copyOf(modules);
        }
    }

    /** The points of a module's release a step can run at. */
    public enum StepPoint {
        BEFORE_RELEASE_COMMIT("before-release-commit"),
        AFTER_TAG("after-tag"),
        AFTER_SNAPSHOT("after-snapshot");

        private final String id;

        StepPoint(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<StepPoint> fromId(String id) {
            return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst();
        }
    }

    /** A command for each system, either one missing when the configuration gives it none. */
    public record Command(Optional<Line> linux, Optional<Line> windows) {

        /** The one this system runs. */
        public Optional<Line> forThisSystem() {
            return Proc.isWindows() ? windows : linux;
        }

        /** What this system starts to run it, a string through its shell; empty when it has none here. */
        public Optional<List<String>> argv() {
            return argv(List.of());
        }

        /**
         * The same with {@code arguments} after its own: more of an argument list, {@code $1}… of a string run
         * through {@code sh}, the end of the line run through {@code cmd}.
         */
        public Optional<List<String>> argv(List<String> arguments) {
            return forThisSystem().map(line -> switch (line) {
                case Line.Args args -> Stream.concat(args.argv().stream(), arguments.stream()).toList();
                case Line.Shell shell -> Proc.isWindows()
                        ? List.of("cmd", "/c", String.join(" ",
                                Stream.concat(Stream.of(shell.text()), arguments.stream()).toList()))
                        // The word after the text is $0, so the arguments start at $1.
                        : Stream.concat(Stream.of("sh", "-c", shell.text(), "sh"), arguments.stream()).toList();
            });
        }

        /** An argument list run without a shell, or a string run through {@code sh -c} / {@code cmd /c}. */
        public sealed interface Line {

            record Args(List<String> argv) implements Line {

                public Args {
                    argv = List.copyOf(argv);
                }
            }

            record Shell(String text) implements Line {
            }
        }
    }
}
