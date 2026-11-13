package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.decide.Decision;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Lane;
import com.botmaker.sluice.desktop.model.ReleaseProgress.Phase;
import com.botmaker.sluice.execute.Event;
import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.Stage;
import com.botmaker.sluice.gate.GateVerdict;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run being watched: takes its {@link Event}s as they come, from the run's thread, and answers what it has done
 * so far as a {@link ReleaseProgress}, from any thread. A module runs every step before the next one starts, so
 * the module a running release is at is the first one not done.
 */
public final class ReleaseWatch {

    private static final String STOPPED_AT = "stopped at ";

    private final String tagPattern;
    private final Instant started;

    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final List<GateVerdict> verdicts = new ArrayList<>();
    private boolean decided;
    private Instant mark;
    private Optional<Phase> ended = Optional.empty();
    private Instant endedAt;

    /** One module of the run, as its events left it. */
    private static final class Module {
        final String tag;
        Stage stage = Stage.DECIDED;
        Instant began;
        Instant finished;
        final List<String> errors = new ArrayList<>();

        Module(String tag) {
            this.tag = tag;
        }
    }

    public ReleaseWatch(String tagPattern, Instant started) {
        this.tagPattern = tagPattern;
        this.started = started;
        this.mark = started;
    }

    public synchronized void accept(Event event, Instant at) {
        switch (event) {
            case Event.Decided d -> {
                // A run says its plan once; a resumed run says the part it goes on with.
                for (Decision decision : d.plan().decisions()) {
                    decision.release().ifPresent(version -> modules.putIfAbsent(decision.module().name(),
                            new Module(version.tag(tagPattern))));
                }
                decided = true;
                mark = at;
            }
            case Event.Verdict v -> {
                verdicts.add(v.verdict());
                mark = at;
            }
            case Event.Reached r -> {
                Module module = modules.computeIfAbsent(r.module().name(), name -> new Module(""));
                if (module.began == null) {
                    module.began = mark;
                }
                module.stage = r.stage();
                if (r.stage() == Stage.SNAPSHOT || r.stage() == Stage.FAILED) {
                    module.finished = at;
                }
                mark = at;
            }
            case Event.Line l -> {
                String text = l.text();
                if (text.startsWith(STOPPED_AT)) {
                    for (Map.Entry<String, Module> entry : modules.entrySet()) {
                        if (text.startsWith(STOPPED_AT + entry.getKey() + ":")) {
                            entry.getValue().errors.add(text.substring(STOPPED_AT.length()));
                        }
                    }
                }
            }
        }
    }

    /** The run returned. */
    public synchronized void ended(Release.Status status, Instant at) {
        ended = Optional.of(switch (status) {
            case DONE, PREVIEWED -> Phase.DONE;
            case REFUSED -> Phase.REFUSED;
            case STOPPED -> Phase.STOPPED;
        });
        endedAt = at;
    }

    /**
     * The run threw. Before any module moved it refused, a lock or a config it could not read, and tagged
     * nothing; after, it stopped where it was, and resumes from there.
     */
    public synchronized void threw(String why, Instant at) {
        boolean moved = modules.values().stream().anyMatch(m -> m.began != null);
        if (moved) {
            current().ifPresent(module -> module.errors.add(why));
        }
        ended = Optional.of(moved ? Phase.STOPPED : Phase.REFUSED);
        endedAt = at;
    }

    public synchronized boolean running() {
        return ended.isEmpty();
    }

    public synchronized ReleaseProgress progress(Instant now) {
        Instant end = ended.isPresent() ? endedAt : now;
        Phase phase = ended.orElse(decided && verdicts.stream().noneMatch(GateVerdict::stops)
                && modules.values().stream().anyMatch(m -> m.began != null) ? Phase.RELEASING : Phase.GATING);
        boolean stopped = phase == Phase.STOPPED || phase == Phase.DIED;
        boolean moving = phase == Phase.RELEASING || stopped;
        Optional<Module> current = moving ? current() : Optional.empty();
        List<Lane> lanes = new ArrayList<>();
        for (Map.Entry<String, Module> entry : modules.entrySet()) {
            Module module = entry.getValue();
            // A stopped run marks the module it stopped in, never the next one it did not reach.
            boolean here = current.isPresent() && current.get() == module
                    && (!stopped || module.stage != Stage.DECIDED || !module.errors.isEmpty());
            Instant began = module.began != null ? module.began : here ? mark : null;
            Optional<Duration> elapsed = Optional.ofNullable(began).map(from -> Duration.between(from,
                    module.finished != null ? module.finished : end));
            lanes.add(ReleaseProgress.lane(entry.getKey(), module.tag, module.stage, here, stopped, module.errors,
                    elapsed));
        }
        return new ReleaseProgress(phase, lanes, verdicts, started, Duration.between(started, end));
    }

    private Optional<Module> current() {
        return modules.values().stream().filter(m -> m.stage != Stage.SNAPSHOT && m.stage != Stage.FAILED)
                .findFirst();
    }
}
