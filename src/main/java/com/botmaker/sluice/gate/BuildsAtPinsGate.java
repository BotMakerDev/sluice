package com.botmaker.sluice.gate;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.PomFile;
import com.botmaker.sluice.model.PomReader;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A module released builds at the pins its tag would carry, the way its publish target builds it: from nothing
 * but the repositories its own pom declares.
 *
 * <p>Two checks, the cheap one first:
 * <ol>
 *   <li><b>Its pom declares the repository of each upstream it pins.</b> A developer's local repository holds
 *       every upstream, installed by the last build, so a pom that declares no repository builds everywhere
 *       except where its tag is built.</li>
 *   <li><b>It compiles</b>, its {@code HEAD} checked out in a throwaway clone, each upstream pinned to its
 *       newest release, in an empty local repository. CI builds a module against its upstreams' branch; the tag
 *       builds against their releases, which may lack what the branch has.</li>
 * </ol>
 *
 * <p>The second is skipped, and says so, for a module that pins an upstream this run releases (that release is
 * not published yet) or pins one by anything other than a property.
 */
public final class BuildsAtPinsGate implements Gate {

    private final Function<Module, PublishTarget> targets;
    private final List<String> mavenArgs;

    public BuildsAtPinsGate(Function<Module, PublishTarget> targets) {
        this(targets, List.of());
    }

    /** @param mavenArgs arguments every Maven run gets before its goal: a settings file */
    public BuildsAtPinsGate(Function<Module, PublishTarget> targets, List<String> mavenArgs) {
        this.targets = targets;
        this.mavenArgs = List.copyOf(mavenArgs);
    }

    @Override
    public String name() {
        return "builds-at-pins";
    }

    @Override
    public List<GateVerdict> check(GateContext ctx) {
        Map<Module, Version> releasing = ctx.plan().releasing();
        List<GateVerdict> verdicts = new ArrayList<>();
        Path repository = null;
        try {
            for (Module module : releasing.keySet()) {
                List<Edge> pinned = ctx.graph().upstreams(module).stream()
                        .filter(PinsResolveGate::pinnedInAPom).toList();
                if (module.pom().isEmpty() || pinned.isEmpty()) {
                    continue;
                }
                List<GateVerdict> undeclared = undeclared(ctx, module, pinned);
                if (!undeclared.isEmpty()) {
                    verdicts.addAll(undeclared);
                    continue;
                }
                Optional<String> unbuildable = unbuildable(pinned, releasing.keySet())
                        .or(() -> pinned.stream().map(Edge::upstream)
                                .filter(upstream -> !targets.apply(upstream).publishes())
                                .map(upstream -> upstream + " is published nowhere a build resolves it from")
                                .findFirst());
                if (unbuildable.isPresent()) {
                    verdicts.add(skipped(module + ": not built at its pins (" + unbuildable.get() + ")"));
                    continue;
                }
                Map<String, String> pins = pins(ctx, pinned);
                if (pins.isEmpty()) {
                    continue;
                }
                if (!Proc.onPath("mvn")) {
                    verdicts.add(skipped(module + ": mvn is not on PATH, not built at its pins"));
                    continue;
                }
                if (repository == null) {
                    repository = Files.createTempDirectory("sluice-repository-");
                }
                Optional<Proc.Result> built = compile(module, pins, repository);
                verdicts.add(built.isPresent()
                        ? verdict(module, pins, built.get())
                        : skipped(module + ": its HEAD could not be copied or the build was stopped, not built at "
                                + "its pins"));
            }
        } catch (IOException e) {
            verdicts.add(skipped("no temporary directory could be made (" + e.getMessage() + ")"));
        } finally {
            if (repository != null) {
                delete(repository);
            }
        }
        return verdicts;
    }

    /** A refusal for each pinned upstream whose repository the module's poms do not declare. */
    private List<GateVerdict> undeclared(GateContext ctx, Module module, List<Edge> pinned) {
        Set<String> declared = declared(module);
        List<GateVerdict> verdicts = new ArrayList<>();
        for (Edge edge : pinned) {
            PublishTarget target = targets.apply(edge.upstream());
            Optional<String> url = target.repository().map(PomReader::withoutSlash);
            if (url.isPresent() && !declared.contains(url.get())) {
                verdicts.add(refused(module + " pins " + edge.upstream() + ", which resolves from " + url.get()
                                + ", and its pom declares no such repository",
                        "     Its tag is built from the repositories " + ctx.said(module.pom().get().file())
                                + " declares and nothing else.\n"
                                + "     Add there: <repository><id>" + target.name() + "</id><url>" + url.get()
                                + "</url></repository>"));
            }
        }
        return verdicts;
    }

    /**
     * The repositories a module's pom declares, with those of each parent that is a file of the same module. A
     * parent's default path points out of a submodule at the umbrella's pom, which the module's tag is built
     * without: a file outside the module, or one that is not the parent named, declares nothing here.
     */
    private static Set<String> declared(Module module) {
        Path root = module.dir().dir().toAbsolutePath().normalize();
        Set<String> urls = new LinkedHashSet<>();
        Set<Path> seen = new LinkedHashSet<>();
        Optional<PomFile> pom = module.pom();
        while (pom.isPresent() && seen.add(pom.get().file())) {
            PomFile at = pom.get();
            urls.addAll(PomReader.repositories(at.file()));
            pom = at.parent().flatMap(parent -> parent.file(at.file())
                    .filter(file -> file.startsWith(root) && Files.isRegularFile(file))
                    .flatMap(BuildsAtPinsGate::read)
                    .filter(file -> file.coordinate().equals(parent.coordinate())));
        }
        return urls;
    }

    private static Optional<PomFile> read(Path pom) {
        try {
            return Optional.of(PomReader.read(pom));
        } catch (Refusal unreadable) {
            return Optional.empty();
        }
    }

    /** Why the module cannot be built at its pins here, when it cannot. */
    private static Optional<String> unbuildable(List<Edge> pinned, Set<Module> releasing) {
        List<String> unpublished = pinned.stream().map(Edge::upstream).filter(releasing::contains)
                .map(Module::name).toList();
        if (!unpublished.isEmpty()) {
            return Optional.of(String.join(", ", unpublished) + " released by this run, not published yet");
        }
        return pinned.stream()
                .filter(edge -> edge.pins().stream()
                        .anyMatch(pin -> !(pin instanceof Pin.Property) && !(pin instanceof Pin.Text)))
                .map(edge -> "its pin of " + edge.upstream() + " is not a property")
                .findFirst();
    }

    /** Each pin's property and the release it would name; an upstream never released has none. */
    private Map<String, String> pins(GateContext ctx, List<Edge> pinned) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (Edge edge : pinned) {
            Version newest = ctx.plan().decision(edge.upstream()).map(Decision::base).orElse(Version.ZERO);
            if (newest.equals(Version.ZERO)) {
                continue;
            }
            String release = targets.apply(edge.upstream()).spelling().spell(newest, ctx.config().tagPattern());
            for (Pin pin : edge.pins()) {
                if (pin instanceof Pin.Property property) {
                    pins.put(property.name(), release);
                }
            }
        }
        return pins;
    }

    /**
     * {@code mvn compile} of the module's {@code HEAD} in a throwaway clone, with its pins; empty when the build
     * could not be made. A clone, not a worktree: it leaves the module's repository as it was.
     */
    private Optional<Proc.Result> compile(Module module, Map<String, String> pins, Path repository)
            throws IOException {
        Path tree = Files.createTempDirectory("sluice-at-pins-");
        try {
            if (!Git.run(module.dir().dir(), "clone", "--quiet", "--no-checkout", ".", tree.toString()).ok()
                    || !Git.run(tree, "checkout", "--quiet", "--detach",
                    Git.line(module.dir().dir(), "rev-parse", "HEAD").orElse("HEAD")).ok()) {
                return Optional.empty();
            }
            List<String> argv = new ArrayList<>(List.of("mvn", "-B", "-q"));
            argv.addAll(mavenArgs);
            argv.add("-Dmaven.repo.local=" + repository);
            pins.forEach((property, release) -> argv.add("-D" + property + "=" + release));
            argv.add("compile");
            Proc.Result run = Proc.run(tree, argv);
            return run.exit() == Proc.INTERRUPTED || run.exit() == Proc.NOT_STARTED
                    ? Optional.empty() : Optional.of(run);
        } finally {
            delete(tree);
        }
    }

    private GateVerdict verdict(Module module, Map<String, String> pins, Proc.Result run) {
        String said = pins.entrySet().stream().map(pin -> pin.getKey() + "=" + pin.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("");
        if (run.ok()) {
            return ok(module + ": builds at its pins (" + said + ")");
        }
        String errors = String.join("\n", run.out().lines().filter(line -> line.contains("[ERROR]")).toList());
        return refused(module + " does not build at the pins its tag would carry (" + said + ")",
                "     Built from an empty local repository, as its publish target builds it.\n"
                        + Gates.quoted(errors.isEmpty() ? run.out() : errors));
    }

    private static void delete(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A temporary directory that will not go is not worth failing a gate over.
        }
    }
}
