package com.botmaker.sluice.desktop.model;

import com.botmaker.sluice.execute.Release;
import com.botmaker.sluice.execute.ReleaseLogs.LoggedRelease;
import com.botmaker.sluice.execute.ReleaseState;
import com.botmaker.sluice.execute.Stage;
import com.botmaker.sluice.gate.GateVerdict;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a release has done so far, as the board draws it: one immutable value. A run being watched builds it from
 * its events ({@link ReleaseWatch}); a past run from its state file ({@link #past}). Every stage is the library's
 * {@link Stage}; what this adds is where the run is now, which is presentation.
 *
 * @param phase    where the whole run is
 * @param lanes    one per module the run releases, in tag order; empty until the run has decided
 * @param verdicts the gates' answers, as they came
 * @param started  when the run started
 * @param elapsed  from its start to its end, or to now while it runs
 */
public record ReleaseProgress(Phase phase, List<Lane> lanes, List<GateVerdict> verdicts, Instant started,
                              Duration elapsed) {

    public ReleaseProgress {
        lanes = List.copyOf(lanes);
        verdicts = List.copyOf(verdicts);
    }

    /** Where the run is, as a whole. */
    public enum Phase {
        /** Deciding and running the gates: nothing tagged. */
        GATING("Deciding and gating", true),
        RELEASING("Releasing", true),
        DONE("Released", false),
        /** A gate refused: nothing was tagged. */
        REFUSED("Refused — nothing was tagged", false),
        /** Stopped partway, by the operator or a step that failed: resumable. */
        STOPPED("Stopped — resumable", false),
        /** Its state says it was running, and nothing runs it: killed, or the machine went down. Resumable. */
        DIED("Ended without finishing — resumable", false);

        private final String label;
        private final boolean running;

        Phase(String label, boolean running) {
            this.label = label;
            this.running = running;
        }

        public String label() {
            return label;
        }

        public boolean running() {
            return running;
        }
    }

    /** The steps of one module, each the stage that marks it done. */
    public enum Step {
        COMMIT("Commit", Stage.RELEASE_COMMITTED),
        TAG("Tag", Stage.TAGGED),
        PUSH("Push", Stage.PUSHED),
        PUBLISH("Publish", Stage.PUBLISHED),
        SNAPSHOT("Snapshot", Stage.SNAPSHOT);

        private final String label;
        private final Stage done;

        Step(String label, Stage done) {
            this.label = label;
            this.done = done;
        }

        public String label() {
            return label;
        }

        /** The stage a module has reached once this step is done. */
        public Stage done() {
            return done;
        }
    }

    /** One node of a lane's stepper. */
    public enum NodeState {
        PENDING, RUNNING, OK, FAILED, SKIPPED;

        /** {@code step--running} and so on: the stylesheet's name. */
        public String styleClass() {
            return "step--" + name().toLowerCase();
        }

        /** Finished, one way or the other that needs nobody. */
        public boolean settled() {
            return this == OK || this == SKIPPED;
        }
    }

    /** Which lanes the board shows: every one, or those whose step still needs looking at. */
    public enum Filter {
        ALL, TAG, PUBLISH, SNAPSHOT
    }

    /**
     * One module's lane.
     *
     * @param module  the module's name, its path in the umbrella
     * @param tag     the tag it is released at
     * @param stage   the stage it reached
     * @param steps   each step's node, in {@link Step} order
     * @param errors  what stopped it, when something did
     * @param elapsed how long it took, once it started
     */
    public record Lane(String module, String tag, Stage stage, Map<Step, NodeState> steps, List<String> errors,
                       Optional<Duration> elapsed) {

        public Lane {
            steps = Map.copyOf(steps);
            errors = List.copyOf(errors);
        }

        public NodeState state(Step step) {
            return steps.get(step);
        }

        public boolean failed() {
            return steps.containsValue(NodeState.FAILED);
        }

        public boolean running() {
            return steps.containsValue(NodeState.RUNNING);
        }

        public boolean tagged() {
            return stage == Stage.FAILED || stage.compareTo(Stage.TAGGED) >= 0;
        }

        /** Whether the lane belongs under {@code filter}: every lane for {@link Filter#ALL}, else an unsettled step. */
        public boolean shownUnder(Filter filter) {
            return switch (filter) {
                case ALL -> true;
                case TAG -> !state(Step.COMMIT).settled() || !state(Step.TAG).settled();
                case PUBLISH -> !state(Step.PUBLISH).settled();
                case SNAPSHOT -> !state(Step.SNAPSHOT).settled();
            };
        }

        /** The stage's word, for a person. */
        public String stageLabel() {
            return switch (stage) {
                case DECIDED -> "waiting";
                case RELEASE_COMMITTED -> "committed";
                case TAGGED -> "tagged";
                case PUSHED -> "pushed";
                case PUBLISHED -> "published";
                case SNAPSHOT -> "done";
                case FAILED -> "build failed";
            };
        }
    }

    /** The tiles' numbers, counted from the lanes and nothing else. */
    public record Tiles(int total, int tagged, boolean tagFailed, int published, int publishWaiting,
                        int publishFailed, int done, Duration elapsed) {
    }

    /**
     * One module's lane at {@code stage}.
     *
     * @param current whether the run is at this module now: its next step runs, or failed when the run stopped
     * @param stopped whether the run has stopped without finishing
     */
    static Lane lane(String module, String tag, Stage stage, boolean current, boolean stopped, List<String> errors,
                     Optional<Duration> elapsed) {
        Map<Step, NodeState> steps = new EnumMap<>(Step.class);
        boolean next = current;
        for (Step step : Step.values()) {
            NodeState state;
            if (stage == Stage.FAILED) {
                state = step.compareTo(Step.PUBLISH) < 0 ? NodeState.OK
                        : step == Step.PUBLISH ? NodeState.FAILED : NodeState.SKIPPED;
            } else if (stage.compareTo(step.done()) >= 0) {
                state = NodeState.OK;
            } else if (next) {
                state = stopped ? NodeState.FAILED : NodeState.RUNNING;
                next = false;
            } else {
                state = NodeState.PENDING;
            }
            steps.put(step, state);
        }
        return new Lane(module, tag, stage, steps, errors, elapsed);
    }

    private static final DateTimeFormatter LOG_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    /**
     * A run nobody watches, read from what it left under {@code releases/}.
     *
     * @param tagPattern the config's, to spell each module's tag
     */
    public static ReleaseProgress past(LoggedRelease release, String tagPattern) {
        Instant started = started(release.name());
        Optional<ReleaseState> state = release.state();
        Phase phase = state.map(s -> switch (s.status()) {
            case DONE -> Phase.DONE;
            case STOPPED -> Phase.STOPPED;
            case RUNNING -> Phase.DIED;
        }).orElse(release.status().filter(s -> s == Release.Status.REFUSED)
                .map(s -> Phase.REFUSED).orElse(Phase.STOPPED));
        List<Lane> lanes = new ArrayList<>();
        boolean stopped = phase == Phase.STOPPED || phase == Phase.DIED;
        boolean currentTaken = false;
        for (ReleaseState.Entry entry : state.map(ReleaseState::modules).orElse(List.of())) {
            boolean current = !currentTaken && entry.stage() != Stage.SNAPSHOT && entry.stage() != Stage.FAILED;
            currentTaken |= current;
            List<String> errors = entry.detail().isBlank() ? List.of() : List.of(entry.detail());
            // The module it stopped in, never the next one it did not reach.
            boolean stoppedHere = current && stopped && (entry.stage() != Stage.DECIDED || !errors.isEmpty());
            lanes.add(lane(entry.module(), entry.version().tag(tagPattern), entry.stage(), stoppedHere,
                    stopped, errors, Optional.empty()));
        }
        return new ReleaseProgress(phase, lanes, List.of(), started, Duration.ZERO);
    }

    /** When a run of that file name started: the name is its local time. */
    static Instant started(String name) {
        String stamp = name.length() >= 15 ? name.substring(0, 15) : name;
        try {
            return LocalDateTime.parse(stamp, LOG_NAME).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    public Tiles tiles() {
        int tagged = 0;
        boolean tagFailed = false;
        int published = 0;
        int waiting = 0;
        int publishFailed = 0;
        int done = 0;
        for (Lane lane : lanes) {
            tagged += lane.tagged() ? 1 : 0;
            tagFailed |= lane.state(Step.COMMIT) == NodeState.FAILED || lane.state(Step.TAG) == NodeState.FAILED;
            switch (lane.state(Step.PUBLISH)) {
                case OK -> published++;
                case FAILED -> publishFailed++;
                default -> waiting++;
            }
            done += lane.state(Step.SNAPSHOT) == NodeState.OK ? 1 : 0;
        }
        return new Tiles(lanes.size(), tagged, tagFailed, published, waiting, publishFailed, done, elapsed);
    }

    public List<Lane> lanes(Filter filter) {
        return lanes.stream().filter(lane -> lane.shownUnder(filter)).toList();
    }

    /** {@code broken} on any failure, {@code pending} while a node is unanswered, else {@code ok}. */
    public String health() {
        if (failed()) {
            return "broken";
        }
        boolean open = phase.running() || lanes.stream().anyMatch(lane -> !lane.state(Step.SNAPSHOT).settled());
        return open ? "pending" : "ok";
    }

    public boolean failed() {
        return lanes.stream().anyMatch(Lane::failed) || phase == Phase.STOPPED || phase == Phase.DIED
                || phase == Phase.REFUSED;
    }

    /** {@code 12:41}, or {@code 1:02:05} past the hour. */
    public static String clock(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, s) : "%d:%02d".formatted(m, s);
    }
}
