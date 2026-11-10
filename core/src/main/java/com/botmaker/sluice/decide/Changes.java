package com.botmaker.sluice.decide;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.model.Coordinate;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PinReader;
import com.botmaker.sluice.model.PomDiff;
import com.botmaker.sluice.model.PomFile;
import com.botmaker.sluice.model.PomReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * Reads what changed in a module since its newest tag. Whatever cannot be read answers {@link ChangeKind#CHANGED}:
 * an unreadable checkout is no evidence that nothing changed.
 */
final class Changes {

    /** A module's changes and the sentence that says them. */
    record Change(ChangeKind kind, String why) {
    }

    private final Graph graph;
    private final Config config;
    private final Relevance relevance;
    private final Function<Module, Optional<Version>> newest;

    /** @param newest each module's newest released version */
    Changes(Graph graph, Config config, Function<Module, Optional<Version>> newest) {
        this.graph = graph;
        this.config = config;
        this.relevance = Relevance.of(config);
        this.newest = newest;
    }

    Change of(Module module) {
        Optional<Version> tagged = newest.apply(module);
        if (tagged.isEmpty()) {
            return new Change(ChangeKind.CHANGED, "never tagged");
        }
        String tag = tagged.get().tag(config.tagPattern());
        if (!Tags.resolves(module, tagged.get(), config.tagPattern())) {
            return new Change(ChangeKind.CHANGED, "its tag " + tag + " cannot be read in this checkout");
        }
        Path dir = module.dir().dir();
        // The full ref: a branch of the tag's name would make the short one ambiguous. No renames: a file moved
        // into an ignored directory left where it was published from. Paths as they are, never quoted.
        String ref = "refs/tags/" + tag;
        Proc.Result diff = Git.run(dir, "-c", "core.quotePath=false", "diff", "--name-only", "--no-renames", ref,
                "HEAD", "--");
        if (!diff.ok()) {
            return new Change(ChangeKind.CHANGED, "its changes since " + tag + " cannot be read");
        }
        List<String> files = diff.lines().stream().filter(f -> !onlyVersionsMoved(module, ref, f)).toList();
        ChangeKind kind = ChangeKind.classify(files, relevance);
        if (kind == ChangeKind.CHANGED) {
            return new Change(kind, "changed since " + tag);
        }
        Optional<String> moved = movedPin(module, tag, ref);
        if (moved.isPresent()) {
            return new Change(ChangeKind.UPSTREAMS, moved.get());
        }
        return new Change(kind, kind == ChangeKind.NONE
                ? "no changes since " + tag
                : "only ignored files changed since " + tag + " (" + String.join(", ", files) + ")");
    }

    /**
     * Whether {@code file} differs from the tag's only where a release writes: the module's version and
     * after-release properties, and its pins.
     */
    private boolean onlyVersionsMoved(Module module, String ref, String file) {
        Path dir = module.dir().dir();
        Path path = dir.resolve(file).toAbsolutePath().normalize();
        boolean ownPom = module.pom().map(PomFile::file).filter(path::equals).isPresent();
        List<Pin> pins = graph.upstreams(module).stream()
                .flatMap(e -> e.pins().stream())
                .filter(p -> p.file().toAbsolutePath().normalize().equals(path))
                .toList();
        boolean childPom = !ownPom && path.getFileName().toString().equals("pom.xml");
        if (!ownPom && !childPom && pins.isEmpty()) {
            return false;
        }
        Optional<String> shownBefore = Git.show(dir, ref, file);
        Optional<String> shownAfter = Git.show(dir, "HEAD", file);
        if (shownBefore.isEmpty() || shownAfter.isEmpty()) {
            return false;
        }
        // A child of the module's own pom: versions:set moved its <parent> version with the root's.
        childPom = childPom && namesAsParent(path, shownAfter.get(), module);
        if (!ownPom && !childPom && pins.isEmpty()) {
            return false;
        }
        List<Pin.Text> texts = pins.stream().filter(Pin.Text.class::isInstance).map(Pin.Text.class::cast).toList();
        String before = withoutVersions(shownBefore.get(), texts);
        String after = withoutVersions(shownAfter.get(), texts);
        if (!ownPom && !childPom && texts.size() == pins.size()) {
            return before.equals(after);
        }
        Set<String> properties = new LinkedHashSet<>();
        Set<Coordinate> dependencies = new LinkedHashSet<>();
        boolean parent = childPom;
        for (Pin pin : pins) {
            switch (pin) {
                case Pin.Property property -> properties.add(property.name());
                case Pin.Literal literal -> dependencies.add(literal.dep());
                case Pin.Parent ignored -> parent = true;
                case Pin.Text ignored -> {
                    // Already left out of both texts above.
                }
            }
        }
        if (ownPom) {
            config.afterRelease().forEach((name, set) -> {
                if (graph.module(name).filter(module::equals).isPresent()) {
                    properties.addAll(set.keySet());
                }
            });
        }
        return PomDiff.sameButVersions(path, before, after,
                new PomDiff.Moved(ownPom, parent, properties, dependencies));
    }

    /** Whether the pom {@code text} names the module's own pom as its {@code <parent>}. */
    private static boolean namesAsParent(Path pom, String text, Module module) {
        try {
            return PomReader.read(pom, text).parent()
                    .filter(p -> module.coordinate().filter(p.coordinate()::equals).isPresent()).isPresent();
        } catch (Refusal unreadable) {
            return false;
        }
    }

    private static String withoutVersions(String text, List<Pin.Text> pins) {
        String left = text;
        for (Pin.Text pin : pins) {
            Matcher matcher = pin.match().matcher(left);
            StringBuilder out = new StringBuilder();
            int from = 0;
            while (matcher.find()) {
                if (matcher.start(1) < 0) {
                    continue;
                }
                out.append(left, from, matcher.start(1));
                from = matcher.end(1);
            }
            left = out.append(left, from, left.length()).toString();
        }
        return left;
    }

    /**
     * The sentence for a pin the tag holds at another release than its upstream's newest: an upstream released
     * since, outside this run. A tag cut now would name another upstream, so it is another artifact whatever the
     * commits say. A pin that is no released version (a snapshot) or an upstream never released moves nothing.
     */
    private Optional<String> movedPin(Module module, String tag, String ref) {
        Path dir = module.dir().dir();
        List<String> moved = new ArrayList<>();
        for (Edge edge : graph.upstreams(module)) {
            Optional<Version> released = newest.apply(edge.upstream());
            if (released.isEmpty()) {
                continue;
            }
            for (Pin pin : edge.pins()) {
                String file = dir.toAbsolutePath().normalize().relativize(pin.file().toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                // A file the tag lacks holds a pin added since: the tag pinned nothing there.
                Optional<Version> pinned = Git.show(dir, ref, file)
                        .flatMap(text -> PinReader.value(pin, text))
                        .flatMap(text -> Tags.released(text, config.tagPattern()));
                if (pinned.filter(p -> !p.equals(released.get())).isPresent()) {
                    moved.add(tag + " pins " + edge.upstream() + " at " + pinned.get() + ", released since at "
                            + released.get());
                    break;
                }
            }
        }
        return moved.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", moved));
    }
}
