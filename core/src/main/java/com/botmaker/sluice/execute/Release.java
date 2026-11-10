package com.botmaker.sluice.execute;

import com.botmaker.sluice.Git;
import com.botmaker.sluice.Level;
import com.botmaker.sluice.PinMoves;
import com.botmaker.sluice.Proc;
import com.botmaker.sluice.Refusal;
import com.botmaker.sluice.Runner;
import com.botmaker.sluice.Version;
import com.botmaker.sluice.config.Config;
import com.botmaker.sluice.config.Config.StepPoint;
import com.botmaker.sluice.config.ConfigLoader;
import com.botmaker.sluice.decide.ChangeKind;
import com.botmaker.sluice.decide.Decider;
import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.decide.Plan;
import com.botmaker.sluice.decide.Requests;
import com.botmaker.sluice.decide.Tags;
import com.botmaker.sluice.execute.ReleaseState.Entry;
import com.botmaker.sluice.gate.CleanGate;
import com.botmaker.sluice.gate.GateVerdict;
import com.botmaker.sluice.gate.Gates;
import com.botmaker.sluice.github.GitHub;
import com.botmaker.sluice.layout.UmbrellaLayout;
import com.botmaker.sluice.model.Edge;
import com.botmaker.sluice.model.Graph;
import com.botmaker.sluice.model.Module;
import com.botmaker.sluice.model.Pin;
import com.botmaker.sluice.model.TagOrder;
import com.botmaker.sluice.spi.Facts;
import com.botmaker.sluice.spi.Gate;
import com.botmaker.sluice.spi.GateContext;
import com.botmaker.sluice.spi.PublishTarget;
import com.botmaker.sluice.target.Targets;
import com.botmaker.sluice.target.Waits;
import com.botmaker.sluice.target.Waits.Published;
import com.botmaker.sluice.target.Waits.Sleeper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A release: decide, gate, then for each module in tag order — release commit, tag, push, wait for its target when
 * a later module resolves it, back to the next snapshot with its dependents' pins following — and one umbrella
 * pointer commit at the end.
 *
 * <p>Without {@link Options#execute} the same run happens on a dry runner: every command is echoed, none runs, and
 * nothing is written, not even the log. With it, a lock keeps a second run out, and the state file under
 * {@code releases/} records each module's stage after every step, so a stopped run resumes where it stopped
 * ({@link #resume}). Each step is safe to redo: a resumed run may repeat the one it was killed in.
 */
public final class Release {

    private Release() {
    }

    /**
     * How a run goes.
     *
     * @param execute        release, rather than preview
     * @param force          turn each gate's refusal into a forced verdict
     * @param withDependents release, at a patch, every dependent a release leaves pinning an older version
     * @param waitForTargets wait for each target a later module of the run resolves from
     */
    public record Options(boolean execute, boolean force, boolean withDependents, boolean waitForTargets) {
    }

    /** How a run ended. */
    public enum Status {
        /** Every module released. */
        DONE,
        /** A preview that would release. */
        PREVIEWED,
        /** A gate refused: nothing was tagged. */
        REFUSED,
        /** Stopped partway: resumable. */
        STOPPED
    }

    /**
     * @param log   the run's log, when it wrote one
     * @param state the run's state, when it wrote one
     */
    public record Outcome(Status status, Optional<Path> log, Optional<Path> state) {
    }

    private static final String NO_STATE = StateFile.DIR + "/ holds no release's state";

    /** A run's state and the file that holds it. */
    public record Saved(Path file, ReleaseState state) {
    }

    /** The state in {@code file}, or the newest run's under {@code releases/} when it is empty. */
    public static Saved state(Path umbrella, Optional<Path> file) {
        Path at = file.or(() -> StateFile.newest(umbrella)).orElseThrow(() -> new Refusal(NO_STATE));
        if (!Files.isRegularFile(at)) {
            throw new Refusal(at + ": no such file");
        }
        return new Saved(at, StateFile.read(at));
    }

    /** What a run reaches outside itself, so a test can put local stand-ins there. */
    record Wiring(Function<Config, Function<Module, PublishTarget>> targets, Supplier<Optional<GitHub>> github,
                  Function<BooleanSupplier, Sleeper> sleeper, List<String> mavenArgs, Clock clock) {

        static Wiring real() {
            return new Wiring(Targets::of, GitHub::fromEnvironment, Sleeper::real, List.of(),
                    Clock.systemDefaultZone());
        }
    }

    /**
     * Releases {@code requests}, or previews it.
     *
     * @param stop asked between steps and while waiting: true stops the run where it is, resumable
     */
    public static Outcome run(Path umbrella, Requests requests, Options options, BooleanSupplier stop,
                              Consumer<Event> events) {
        return run(umbrella, requests, options, stop, events, Wiring.real());
    }

    static Outcome run(Path umbrella, Requests requests, Options options, BooleanSupplier stop,
                       Consumer<Event> events, Wiring wiring) {
        Config config = ConfigLoader.load(umbrella);
        Graph graph = Graph.build(UmbrellaLayout.of(umbrella), config);
        Plan plan = Decider.decide(graph, config, requests, options.withDependents());
        Session session = new Session(umbrella, config, graph, plan, options, stop, events, wiring);
        List<Entry> entries = plan.releasing().entrySet().stream()
                .map(e -> new Entry(e.getKey().name(), e.getValue(), Stage.DECIDED, "")).toList();
        return session.release(Gates.standard(config, session.targets, session.github), plan, entries);
    }

    /**
     * Goes on with the newest run under {@code releases/} that did not finish: each module from the stage it
     * reached. A module whose target failed to build its tag is released again at its next patch, since a tag is
     * final. Only the clean-tree gate runs again; the rest held when the run started.
     */
    public static Outcome resume(Path umbrella, Options options, BooleanSupplier stop, Consumer<Event> events) {
        return resume(umbrella, options, stop, events, Wiring.real());
    }

    static Outcome resume(Path umbrella, Options options, BooleanSupplier stop, Consumer<Event> events,
                          Wiring wiring) {
        if (StateFile.newest(umbrella).isEmpty()) {
            throw new Refusal("nothing to resume: " + NO_STATE);
        }
        // The same run --status shows.
        Saved saved = state(umbrella, Optional.empty());
        Path file = saved.file();
        ReleaseState state = saved.state();
        if (state.status() == ReleaseState.Status.DONE) {
            events.accept(new Event.Line("nothing to resume: the release of " + file.getFileName() + " is done"));
            return new Outcome(Status.DONE, Optional.empty(), Optional.of(file));
        }
        Config config = ConfigLoader.load(umbrella);
        Graph graph = Graph.build(UmbrellaLayout.of(umbrella), config);
        List<Entry> entries = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        List<Decision> untouched = new ArrayList<>();
        for (Entry entry : state.modules()) {
            if (entry.stage() == Stage.SNAPSHOT) {
                // Done, but kept: the umbrella's pointer commit may not have run.
                entries.add(entry);
                continue;
            }
            Module module = graph.modules().stream().filter(m -> m.name().equals(entry.module())).findFirst()
                    .orElseThrow(() -> new Refusal(file + " names " + entry.module()
                            + ", which the umbrella no longer has"));
            Entry next = entry;
            String why = "resumed at " + entry.stage();
            if (entry.stage() == Stage.FAILED) {
                Version patch = entry.version().bump(Level.PATCH);
                next = new Entry(entry.module(), patch, Stage.DECIDED, "");
                why = "its target failed to build " + entry.version() + ", a final tag: its next patch";
            }
            entries.add(next);
            Version base = Tags.known(module, config.tagPattern()).stream().max(Version::compareTo)
                    .orElse(Version.ZERO);
            Decision decision = new Decision(module, base, Optional.of(next.version()), ChangeKind.CHANGED, why);
            decisions.add(decision);
            if (next.stage() == Stage.DECIDED && next.detail().isEmpty()) {
                untouched.add(decision);
            }
        }
        Session session = new Session(umbrella, config, graph, new Plan(decisions, List.of()), options, stop,
                events, wiring);
        // Only a module the run never touched must be clean: the one it stopped in may hold half a step.
        return session.release(List.of(new CleanGate()), new Plan(untouched, List.of()), entries);
    }

    /**
     * Moves every pom pin to its upstream's version on {@code main}: the repair for pins a release left behind.
     * One local commit per module it moves, never pushed.
     */
    public static Status syncPins(Path umbrella, boolean execute, Consumer<Event> events) {
        return syncPins(umbrella, execute, events, Wiring.real());
    }

    static Status syncPins(Path umbrella, boolean execute, Consumer<Event> events, Wiring wiring) {
        Config config = ConfigLoader.load(umbrella);
        Graph graph = Graph.build(UmbrellaLayout.of(umbrella), config);
        Runner runner = new Runner(!execute, line -> events.accept(new Event.Line(line)));
        if (!execute) {
            return syncPins(graph, runner, wiring);
        }
        try (Lock ignored = Lock.take(umbrella, runner::say)) {
            return syncPins(graph, runner, wiring);
        }
    }

    private static Status syncPins(Graph graph, Runner runner, Wiring wiring) {
        PinMoves moves = new PinMoves(runner, wiring.mavenArgs());
        boolean all = true;
        for (Module module : graph.modules()) {
            List<Move> stale = new ArrayList<>();
            for (Edge edge : graph.upstreams(module)) {
                Optional<String> main = edge.upstream().version();
                for (Pin pin : edge.pins()) {
                    if (pin instanceof Pin.Text || main.isEmpty()) {
                        continue;
                    }
                    Optional<String> held = PinMoves.held(pin);
                    if (!held.equals(main)) {
                        runner.say(module + ": its pin of " + edge.upstream() + " holds "
                                + held.orElse("nothing") + ", moving it to " + main.get());
                        stale.add(new Move(pin, main.get()));
                    }
                }
            }
            if (stale.isEmpty()) {
                continue;
            }
            try {
                Session.moveAll(moves, stale);
                Session.commitFiles(runner, module, stale, "pins synced to their upstreams' main");
            } catch (Refusal refusal) {
                // One module's pins failing is no reason to leave the others stale.
                runner.say(module + ": its pins were not synced: " + refusal.getMessage());
                all = false;
            }
        }
        return !all ? Status.STOPPED : runner.dryRun() ? Status.PREVIEWED : Status.DONE;
    }

    /** One pin and the value it moves to. */
    private record Move(Pin pin, String value) {
    }

    /** One run. */
    private static final class Session {

        private final Path umbrella;
        private final Config config;
        private final Graph graph;
        private final Plan plan;
        private final Options options;
        private final Consumer<Event> events;
        private final Wiring wiring;
        private final ReleaseLog log = new ReleaseLog();
        private final Runner runner;
        private final PinMoves moves;
        private final StepRunner steps;
        private final Function<Module, PublishTarget> targets;
        private final Optional<GitHub> github;
        private final Sleeper sleeper;
        private final Map<Module, Version> released;
        private final Map<Module, Optional<Version>> tagged = new HashMap<>();
        private final Set<Module> moved = new LinkedHashSet<>();
        private final List<Entry> entries = new ArrayList<>();
        private Optional<Path> base = Optional.empty();

        Session(Path umbrella, Config config, Graph graph, Plan plan, Options options, BooleanSupplier stop,
                Consumer<Event> events, Wiring wiring) {
            this.umbrella = umbrella;
            this.config = config;
            this.graph = graph;
            this.plan = plan;
            this.options = options;
            this.events = events;
            this.wiring = wiring;
            this.runner = new Runner(!options.execute(), line -> {
                log.line(line);
                events.accept(new Event.Line(line));
            }, stop);
            this.moves = new PinMoves(runner, wiring.mavenArgs());
            this.steps = new StepRunner(config);
            this.targets = wiring.targets().apply(config);
            this.github = wiring.github().get();
            this.sleeper = wiring.sleeper().apply(stop);
            this.released = plan.releasing();
            log.decided(plan, config.tagPattern());
            events.accept(new Event.Decided(plan));
        }

        /** Gates {@code gated}'s modules, then takes each entry as far as it goes. */
        Outcome release(List<Gate> gates, Plan gated, List<Entry> initial) {
            if (initial.isEmpty()) {
                runner.say("nothing to release");
                return new Outcome(options.execute() ? Status.DONE : Status.PREVIEWED, Optional.empty(),
                        Optional.empty());
            }
            if (!options.execute()) {
                return gated(gates, gated, initial);
            }
            try (Lock ignored = Lock.take(umbrella, runner::say)) {
                return gated(gates, gated, initial);
            }
        }

        private Outcome gated(List<Gate> gates, Plan gated, List<Entry> initial) {
            entries.addAll(initial);
            initial.stream().filter(e -> e.stage() == Stage.SNAPSHOT).forEach(e -> moved.add(module(e.module())));
            List<GateVerdict> verdicts = Gates.run(new GateContext(umbrella, graph, gated, config, runner,
                    options.force()), gates);
            verdicts.forEach(verdict -> {
                log.verdict(verdict);
                events.accept(new Event.Verdict(verdict));
            });
            if (Gates.refused(verdicts)) {
                if (!options.execute()) {
                    return new Outcome(Status.REFUSED, Optional.empty(), Optional.empty());
                }
                Path at = StateFile.base(umbrella, wiring.clock());
                Path md = sibling(at, ".md");
                log.write(md, title(), Status.REFUSED, new ReleaseState(ReleaseState.Status.STOPPED, entries));
                return new Outcome(Status.REFUSED, Optional.of(md), Optional.empty());
            }
            if (options.execute()) {
                base = Optional.of(StateFile.base(umbrella, wiring.clock()));
                save(ReleaseState.Status.RUNNING);
            }
            Set<Module> owed = Waits.owed(plan, graph, TagOrder.of(graph, config), targets);
            boolean done = true;
            for (int at = 0; at < entries.size(); at++) {
                if (entries.get(at).stage() == Stage.SNAPSHOT) {
                    continue;
                }
                Module module = module(entries.get(at).module());
                try {
                    if (advance(at, module, owed.contains(module)) != Stage.SNAPSHOT) {
                        done = false;
                        break;
                    }
                } catch (Refusal refusal) {
                    runner.say("stopped at " + module + ": " + refusal.getMessage());
                    entries.set(at, entries.get(at).at(entries.get(at).stage(), refusal.getMessage()));
                    done = false;
                    break;
                }
            }
            ReleaseState.Status status = done ? ReleaseState.Status.DONE : ReleaseState.Status.STOPPED;
            // Only what was tagged: a stopped run names no tag that does not exist.
            String tags = entries.stream().filter(e -> e.stage().compareTo(Stage.TAGGED) >= 0)
                    .map(e -> e.module() + " " + e.version().tag(config.tagPattern()))
                    .collect(Collectors.joining(", "));
            String message = (done ? "release: " : "release (stopped): ") + (tags.isEmpty() ? "nothing tagged" : tags);
            Status outcome = done ? (options.execute() ? Status.DONE : Status.PREVIEWED) : Status.STOPPED;
            if (base.isPresent()) {
                save(status);
                log.write(sibling(base.get(), ".md"), title(), outcome, new ReleaseState(status, entries));
            }
            PointerCommit.commit(runner, umbrella, moved, base, message);
            return new Outcome(outcome, base.map(b -> sibling(b, ".md")), base.map(b -> sibling(b, ".json")));
        }

        /** Takes one module from the stage it is at as far as it goes; the stage it ends at. */
        private Stage advance(int at, Module module, boolean owed) {
            Version version = entries.get(at).version();
            Map<String, String> facts = Facts.of(umbrella, released, module, config.tagPattern(), runner.dryRun());
            Stage stage = entries.get(at).stage();
            moved.add(module);
            if (stage == Stage.DECIDED) {
                stopIfAsked();
                releaseCommit(module, version, facts);
                stage = reach(at, Stage.RELEASE_COMMITTED, "");
            }
            if (stage == Stage.RELEASE_COMMITTED) {
                stopIfAsked();
                tag(module, version);
                stage = reach(at, Stage.TAGGED, "");
            }
            if (stage == Stage.TAGGED) {
                stopIfAsked();
                must(runner.git(module.dir().dir(), "push", "-q", "--atomic", "origin", config.branch(),
                        "refs/tags/" + version.tag(config.tagPattern())), module + ": the push");
                GitHubRelease.create(runner, github, config, module, version, facts);
                steps.run(StepPoint.AFTER_TAG, module, facts, runner);
                stage = reach(at, Stage.PUSHED, "");
            }
            if (stage == Stage.PUSHED) {
                Published published = publish(module, version, owed);
                switch (published.outcome()) {
                    case PUBLISHED -> stage = reach(at, Stage.PUBLISHED, "");
                    case FAILED -> {
                        return reach(at, Stage.FAILED, published.detail());
                    }
                    case TIMEOUT, STOPPED -> {
                        entries.set(at, entries.get(at).at(Stage.PUSHED, "not published yet: resume waits again"));
                        save(ReleaseState.Status.RUNNING);
                        return Stage.PUSHED;
                    }
                }
            }
            if (stage == Stage.PUBLISHED) {
                stopIfAsked();
                snapshot(module, version, facts);
                follow(module, version);
                stage = reach(at, Stage.SNAPSHOT, "");
            }
            return stage;
        }

        private void releaseCommit(Module module, Version version, Map<String, String> facts) {
            Set<String> before = Git.untracked(module.dir().dir());
            List<Move> pins = new ArrayList<>();
            Map<Edge, Version> pinnedAt = new LinkedHashMap<>();
            for (Edge edge : graph.upstreams(module)) {
                for (Pin pin : edge.pins()) {
                    Optional<String> value = releaseValue(edge.upstream(), pin);
                    if (value.isEmpty()) {
                        runner.say("    " + edge.upstream() + " was never released: its pin in "
                                + umbrella.relativize(pin.file().toAbsolutePath()) + " stays as it is");
                    } else {
                        pins.add(new Move(pin, value.get()));
                        pinnedAt.put(edge, releaseVersion(edge.upstream()).orElseThrow());
                    }
                }
            }
            if (config.changelog().enabled() && config.changelog().pins()) {
                Optional<String> tag = tagged.computeIfAbsent(module, m -> Tags.newest(m, config.tagPattern()))
                        .map(v -> v.tag(config.tagPattern()));
                PinNotes.write(runner, module, PinNotes.lines(module, pinnedAt, tag, config.tagPattern()));
            }
            // The parent first: every goal of versions:set resolves the parent the pom names.
            pins.stream().filter(m -> m.pin() instanceof Pin.Parent).forEach(m -> moves.move(m.pin(), m.value()));
            pomOf(module).ifPresent(pom -> moves.version(pom, version.toString()));
            pins.stream().filter(m -> !(m.pin() instanceof Pin.Parent)).forEach(m -> moves.move(m.pin(), m.value()));
            if (config.changelog().enabled()) {
                ChangelogStamp.stamp(runner, module, version, LocalDate.now(wiring.clock()));
            }
            steps.run(StepPoint.BEFORE_RELEASE_COMMIT, module, facts, runner);
            commit(module, "release: " + module + " " + version, before);
        }

        /** What a release commit pins {@code upstream} at: this run's version of it, else its newest tag. */
        private Optional<String> releaseValue(Module upstream, Pin pin) {
            return releaseVersion(upstream).map(v -> (pin instanceof Pin.Text text ? text.spelling()
                    : targets.apply(upstream).spelling()).spell(v, config.tagPattern()));
        }

        /** The version a release commit pins {@code upstream} at, unspelled. */
        private Optional<Version> releaseVersion(Module upstream) {
            return released.containsKey(upstream) ? Optional.of(released.get(upstream))
                    : tagged.computeIfAbsent(upstream, u -> Tags.newest(u, config.tagPattern()));
        }

        private void tag(Module module, Version version) {
            Path dir = module.dir().dir();
            String tag = version.tag(config.tagPattern());
            Optional<String> existing = Git.line(dir, "rev-parse", "-q", "--verify", "refs/tags/" + tag + "^{commit}");
            if (existing.isPresent()) {
                if (existing.equals(Git.line(dir, "rev-parse", "HEAD"))) {
                    runner.say("    " + module + " " + tag + " is on its release commit already");
                    return;
                }
                throw new Refusal(module + ": the tag " + tag + " is on another commit already; a tag is final, so "
                        + "release the next version");
            }
            must(runner.git(dir, "tag", "-a", tag, "-m", "release: " + module + " " + version), module + ": the tag");
        }

        private Published publish(Module module, Version version, boolean owed) {
            if (!owed) {
                runner.say("    " + Waits.notWaiting(module));
                return new Published(Waits.Outcome.PUBLISHED, "");
            }
            if (!options.waitForTargets()) {
                runner.say("    not waiting on " + module + " (--no-wait)");
                return new Published(Waits.Outcome.PUBLISHED, "");
            }
            return Waits.await(targets.apply(module), module, version, config, runner, sleeper);
        }

        private void snapshot(Module module, Version version, Map<String, String> facts) {
            Set<String> before = Git.untracked(module.dir().dir());
            Optional<Path> pom = pomOf(module);
            if (pom.isPresent()) {
                List<Move> mains = new ArrayList<>();
                for (Edge edge : graph.upstreams(module)) {
                    Optional<String> main = mainValue(edge.upstream());
                    edge.pins().stream().filter(pin -> !(pin instanceof Pin.Text))
                            .forEach(pin -> main.ifPresent(value -> mains.add(new Move(pin, value))));
                }
                moves.version(pom.get(), version.nextSnapshot());
                // Every goal before the parent moves: once it names a snapshot, no goal can resolve it.
                AfterRelease.set(runner, moves, config, module, pom.get(), version);
                moveAll(moves, mains);
            }
            commit(module, "back to snapshot: " + module + " " + version.nextSnapshot(), before);
            steps.run(StepPoint.AFTER_SNAPSHOT, module, facts, runner);
            must(runner.git(module.dir().dir(), "push", "-q", "origin", config.branch()), module + ": the push");
        }

        /** Each dependent this run does not release follows {@code module} to its next snapshot; never fatal. */
        private void follow(Module module, Version version) {
            String next = version.nextSnapshot();
            for (Edge edge : graph.dependents(module)) {
                Module dependent = edge.downstream();
                List<Move> pins = edge.pins().stream().filter(pin -> !(pin instanceof Pin.Text))
                        .map(pin -> new Move(pin, next)).toList();
                if (released.containsKey(dependent) || pins.isEmpty() || pomOf(dependent).isEmpty()) {
                    continue;
                }
                Path dir = dependent.dir().dir();
                if (!runner.dryRun() && !Git.branch(dir).equals(Optional.of(config.branch()))) {
                    runner.say("    " + dependent + " is not on " + config.branch() + ": its pin of " + module
                            + " stays");
                    continue;
                }
                // Its commit and push must carry the pin only: never the author's own work.
                if (!runner.dryRun() && (Git.changed(dir) || !Git.run(dir, "rev-list", "origin/" + config.branch()
                        + "..HEAD").out().isBlank())) {
                    runner.say("    " + dependent + " has uncommitted or unpushed work: its pin of " + module
                            + " stays");
                    continue;
                }
                try {
                    moveAll(moves, pins);
                    if (commitFiles(runner, dependent, pins, "pin " + module + " " + next)) {
                        must(runner.git(dir, "push", "-q", "origin", config.branch()), dependent + ": the push");
                    }
                    moved.add(dependent);
                } catch (Refusal refusal) {
                    runner.say("    " + dependent + " keeps its pin of " + module + ": " + refusal.getMessage());
                }
            }
        }

        /** {@code upstream}'s version on its branch once this run is done with it. */
        private Optional<String> mainValue(Module upstream) {
            return released.containsKey(upstream) ? Optional.of(released.get(upstream).nextSnapshot())
                    : upstream.version();
        }

        /** Moves each pin, the parent last. */
        static void moveAll(PinMoves moves, List<Move> pins) {
            pins.stream().filter(m -> !(m.pin() instanceof Pin.Parent)).forEach(m -> moves.move(m.pin(), m.value()));
            pins.stream().filter(m -> m.pin() instanceof Pin.Parent).forEach(m -> moves.move(m.pin(), m.value()));
        }

        /** Commits only the files the moves touched; whether there was anything to commit. */
        static boolean commitFiles(Runner runner, Module module, List<Move> pins, String message) {
            Path dir = module.dir().dir();
            List<String> args = new ArrayList<>(List.of("commit", "-q", "-m", message, "--"));
            pins.stream().map(m -> m.pin().file().toAbsolutePath().toString()).distinct().forEach(args::add);
            List<String> status = new ArrayList<>(List.of("status", "--porcelain", "--"));
            status.addAll(args.subList(5, args.size()));
            if (!runner.dryRun() && Git.run(dir, status.toArray(String[]::new)).out().isBlank()) {
                return false;
            }
            must(runner.git(dir, args.toArray(String[]::new)), module + ": the commit");
            return true;
        }

        /**
         * Commits what the release changed: every tracked file, and each file created while this commit's own work
         * ran ({@code before} is what was untracked when it started). A file untracked before then (an editor's, a
         * build's, another step's) is never added, and each one added is named, since the push publishes it.
         */
        private void commit(Module module, String message, Set<String> before) {
            Path dir = module.dir().dir();
            must(runner.git(dir, "add", "-u"), module + ": staging");
            List<String> created = Git.untracked(dir).stream().filter(f -> !before.contains(f)).toList();
            if (!created.isEmpty()) {
                runner.say("    " + module + ": adds " + String.join(", ", created) + ", created by this commit's steps");
                List<String> add = new ArrayList<>(List.of("add", "--"));
                add.addAll(created);
                must(runner.git(dir, add.toArray(String[]::new)), module + ": staging");
            }
            if (!runner.dryRun() && !Git.staged(dir)) {
                runner.say("    " + module + ": nothing to commit");
                return;
            }
            must(runner.git(dir, "commit", "-q", "-m", message), module + ": the commit");
        }

        private Optional<Path> pomOf(Module module) {
            return module.kind() == Module.Kind.MAVEN ? module.pom().map(pom -> pom.file()) : Optional.empty();
        }

        private Stage reach(int at, Stage stage, String detail) {
            entries.set(at, entries.get(at).at(stage, detail));
            events.accept(new Event.Reached(module(entries.get(at).module()), stage));
            save(ReleaseState.Status.RUNNING);
            return stage;
        }

        private void save(ReleaseState.Status status) {
            base.ifPresent(b -> StateFile.write(sibling(b, ".json"), new ReleaseState(status, entries)));
        }

        private void stopIfAsked() {
            if (runner.stopping()) {
                throw new Refusal("stopped by the operator");
            }
        }

        private Module module(String name) {
            return graph.modules().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        }

        private String title() {
            return "Release " + entries.stream().map(e -> e.module() + " " + e.version())
                    .collect(Collectors.joining(", "));
        }

        private static Path sibling(Path base, String extension) {
            return base.resolveSibling(base.getFileName() + extension);
        }

        private static void must(Proc.Result result, String what) {
            if (!result.ok()) {
                throw new Refusal(what + " failed (exit " + result.exit() + ")\n     " + result.out().strip());
            }
        }
    }
}
